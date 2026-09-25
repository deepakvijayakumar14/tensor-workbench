"""One execution slot: claim a task, run it to the end, then claim the next.

A slot starts another computation only after its previous one has stopped, so a
worker process with N slots never runs more than N computations at once.
"""

from __future__ import annotations

import json
import logging
import random
import signal
import threading
import time
from typing import Any

from .api_client import ApiClient, ApiRejected, LeaseConflict
from .config import WorkerConfig
from .errors import LeaseLost, TransientError, classify
from .storage import ObjectStorage, S3ObjectStorage
from .tasks import TaskContext, describe, execute_generation, execute_run, iso, task_identity

log = logging.getLogger(__name__)


class Heartbeater(threading.Thread):
    """Renews the lease while the task runs. Sets lease_lost on 409 or when renewals
    have failed for longer than the lease itself (we must assume someone else owns it)."""

    def __init__(self, api: ApiClient, kind: str, task_id: str, token: int, interval: float, lease: float, lease_lost: threading.Event) -> None:
        super().__init__(daemon=True, name=f"heartbeat-{task_id[:8]}")
        self.api, self.kind, self.task_id, self.token = api, kind, task_id, token
        self.interval, self.lease, self.lease_lost = interval, lease, lease_lost
        self._done = threading.Event()

    def run(self) -> None:
        last_ok = time.monotonic()
        while not self._done.wait(self.interval):
            try:
                self.api.heartbeat(self.kind, self.task_id, self.token)
                last_ok = time.monotonic()
            except LeaseConflict:
                log.warning("Heartbeat rejected for %s %s: lease lost", self.kind, self.task_id)
                self.lease_lost.set()
                return
            except (TransientError, ApiRejected) as exc:
                if time.monotonic() - last_ok >= self.lease:
                    log.warning("No successful heartbeat for a full lease period (%s); assuming lease lost", exc)
                    self.lease_lost.set()
                    return

    def stop(self) -> None:
        self._done.set()


def event(name: str, **fields: Any) -> None:
    """Structured start/end events used to verify the concurrency bound from logs."""
    log.info(json.dumps({"event": name, "ts": time.time(), **fields}))


def process_assignment(
    assignment: dict[str, Any],
    api: ApiClient,
    storage: ObjectStorage,
    cfg: WorkerConfig,
    shutdown: Any,
    worker_id: str,
    active: Any = None,
) -> None:
    kind, task_id = task_identity(assignment)
    token = assignment["leaseToken"]
    lease_lost = threading.Event()

    def compute_started() -> None:
        running = _bump(active, +1)
        event("compute_start", worker=worker_id, task=describe(assignment), active=running)

    def compute_ended() -> None:
        running = _bump(active, -1)
        event("compute_end", worker=worker_id, task=describe(assignment), active=running)

    ctx = TaskContext(shutdown=shutdown, lease_lost=lease_lost, on_compute_start=compute_started, on_compute_end=compute_ended)
    heartbeater = Heartbeater(
        api, kind, task_id, token, assignment["heartbeatIntervalSeconds"], assignment["leaseDurationSeconds"], lease_lost
    )
    heartbeater.start()
    log.info("%s claimed %s (token %s)", worker_id, describe(assignment), token)
    try:
        if assignment["type"] == "RUN":
            execute_run(assignment, api, storage, cfg, ctx)
        else:
            execute_generation(assignment, api, storage, cfg, ctx)
        log.info("%s completed %s", worker_id, describe(assignment))
    except (LeaseLost, LeaseConflict) as exc:
        ctx.end_compute()
        # Someone else owns this task now; fencing on the API protects accepted state.
        log.warning("%s abandoned %s: %s", worker_id, describe(assignment), exc)
    except Exception as exc:  # noqa: BLE001 - every failure is classified and reported
        ctx.end_compute()
        category, message = classify(exc)
        log.warning("%s failed %s: %s %s", worker_id, describe(assignment), category, message)
        if not lease_lost.is_set():
            try:
                next_state = api.fail(
                    kind,
                    task_id,
                    {
                        "leaseToken": token,
                        "category": category,
                        "message": message,
                        "computeStartedAt": iso(ctx.compute_started_at),
                        "computeFinishedAt": iso(ctx.compute_finished_at),
                    },
                )
                log.info("Reported %s for %s; task is now %s", category, describe(assignment), next_state)
            except (LeaseConflict, TransientError, ApiRejected) as report_exc:
                log.warning("Could not report failure (%s); lease recovery will handle it", report_exc)
    finally:
        ctx.end_compute()
        heartbeater.stop()


def _bump(active: Any, delta: int) -> int:
    if active is None:
        return -1
    with active.get_lock():
        active.value += delta
        return active.value


def run_slot(slot_index: int, cfg: WorkerConfig, shutdown: Any, active: Any) -> None:
    """Entry point of a slot process."""
    # The supervisor owns signal handling; slots stop via the shared shutdown event.
    signal.signal(signal.SIGINT, signal.SIG_IGN)
    signal.signal(signal.SIGTERM, signal.SIG_IGN)
    logging.basicConfig(level=logging.INFO, format=f"%(asctime)s slot-{slot_index} %(levelname)s %(name)s: %(message)s")

    api = ApiClient(cfg)
    storage = S3ObjectStorage(cfg)
    worker_id = f"{cfg.instance_id}/slot-{slot_index}"
    log.info("%s started", worker_id)
    idle_backoff = cfg.poll_interval_seconds

    while not shutdown.is_set():
        try:
            assignment = api.claim(worker_id, cfg.instance_id, cfg.slots)
        except (TransientError, ApiRejected) as exc:
            log.warning("Claim failed: %s", exc)
            shutdown.wait(min(10.0, idle_backoff * 2))
            continue
        if assignment is None:
            # Jitter keeps three idle slots from polling in lockstep.
            shutdown.wait(cfg.poll_interval_seconds * (0.5 + random.random()))
            continue
        process_assignment(assignment, api, storage, cfg, shutdown, worker_id, active)

    log.info("%s stopped", worker_id)
