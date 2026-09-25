"""Worker supervisor: runs a fixed number of slot processes (default three).

Processes, not threads, so NumPy work in different slots truly runs in parallel.
This is a CPU worker-pool demonstration of bounded capacity. It is a per-process
limit; running several worker replicas would multiply capacity, which the MVP
does not coordinate globally.
"""

from __future__ import annotations

import logging
import multiprocessing as mp
import signal
import time

from .config import WorkerConfig
from .slot import run_slot

log = logging.getLogger("tensor_worker.supervisor")


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s supervisor %(levelname)s: %(message)s")
    cfg = WorkerConfig.from_env()
    ctx = mp.get_context("spawn")
    shutdown = ctx.Event()
    active = ctx.Value("i", 0)  # computations running right now, across all slots

    # The handler only records the request. Calling shutdown.set() here could deadlock:
    # the main thread may be inside a wait on the same multiprocessing primitive.
    stop_requested: list[int] = []

    def request_shutdown(signum: int, _frame: object) -> None:
        stop_requested.append(signum)

    signal.signal(signal.SIGTERM, request_shutdown)
    signal.signal(signal.SIGINT, request_shutdown)

    def start(index: int) -> mp.process.BaseProcess:
        process = ctx.Process(target=run_slot, args=(index, cfg, shutdown, active), name=f"slot-{index}", daemon=False)
        process.start()
        return process

    log.info(
        "Starting %d slots against %s (synthetic delay %.1fs, slab budget %d bytes)",
        cfg.slots, cfg.api_url, cfg.synthetic_delay_seconds, cfg.slab_bytes,
    )
    slots = {i: start(i) for i in range(cfg.slots)}
    while not stop_requested:
        for index, process in list(slots.items()):
            if not process.is_alive() and not stop_requested:
                # A crashed slot is replaced; its task's lease simply expires and is retried.
                log.error("Slot %d exited with code %s; restarting", index, process.exitcode)
                time.sleep(1)
                slots[index] = start(index)
        time.sleep(0.5)

    log.info("Received signal %s; stopping slots", stop_requested[0])
    shutdown.set()  # in-flight tasks stop at the next slab and report WORKER_SHUTDOWN (retryable)
    deadline = time.monotonic() + cfg.shutdown_grace_seconds
    for process in slots.values():
        process.join(max(0.1, deadline - time.monotonic()))
    for process in slots.values():
        if process.is_alive():
            log.warning("Slot %s did not stop in time; terminating", process.name)
            process.terminate()
    log.info("Worker stopped")


if __name__ == "__main__":
    main()
