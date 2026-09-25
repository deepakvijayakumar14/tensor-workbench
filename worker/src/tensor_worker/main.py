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

    def request_shutdown(signum: int, _frame: object) -> None:
        log.info("Received signal %s; stopping slots", signum)
        shutdown.set()

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
    while not shutdown.is_set():
        for index, process in list(slots.items()):
            if not process.is_alive() and not shutdown.is_set():
                # A crashed slot is replaced; its task's lease simply expires and is retried.
                log.error("Slot %d exited with code %s; restarting", index, process.exitcode)
                time.sleep(1)
                slots[index] = start(index)
        shutdown.wait(1.0)

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
