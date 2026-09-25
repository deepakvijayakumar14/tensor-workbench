"""Executes one claimed task: download, compute in slabs, upload, publish."""

from __future__ import annotations

import logging
import threading
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any, Callable

from . import compute as computation
from .api_client import ApiClient, ApiRejected
from .config import WorkerConfig
from .errors import InvalidInputError, LeaseLost, ShutdownRequested, TransientError, UnsupportedVersionError
from .files import rows_per_slab, sha256_file, task_workspace
from .generate import GENERATOR_VERSION, generate_dataset
from .preview import build_preview_stack
from .storage import ObjectStorage

log = logging.getLogger(__name__)

NPY_HEADER_ALLOWANCE = 4096


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


def iso(ts: datetime | None) -> str | None:
    return ts.isoformat().replace("+00:00", "Z") if ts else None


@dataclass
class TaskContext:
    """Cooperative cancellation: numerical loops call check() between slabs."""

    shutdown: Any  # multiprocessing.Event or threading.Event
    lease_lost: threading.Event = field(default_factory=threading.Event)
    compute_started_at: datetime | None = None
    compute_finished_at: datetime | None = None
    on_compute_start: Callable[[], None] = lambda: None
    on_compute_end: Callable[[], None] = lambda: None

    def check(self) -> None:
        if self.lease_lost.is_set():
            raise LeaseLost("lease lost; stopping")
        if self.shutdown.is_set():
            raise ShutdownRequested("worker is shutting down")

    def start_compute(self) -> None:
        self.compute_started_at = utc_now()
        self.on_compute_start()

    def end_compute(self) -> None:
        if self.compute_started_at and not self.compute_finished_at:
            self.compute_finished_at = utc_now()
            self.on_compute_end()


class SyntheticDelay:
    """Stretches a computation to `total_seconds` so the scheduler is visible in a demo.

    Clearly synthetic: it sleeps in short ticks, checking for cancellation, in
    proportion to real progress through the slabs.
    """

    def __init__(self, total_seconds: float, ctx: TaskContext) -> None:
        self.total = max(0.0, total_seconds)
        self.ctx = ctx
        self.started = time.monotonic()

    def advance(self, fraction_done: float) -> None:
        target = self.started + self.total * fraction_done
        while True:
            self.ctx.check()
            remaining = target - time.monotonic()
            if remaining <= 0:
                return
            time.sleep(min(0.1, remaining))


def execute_run(assignment: dict[str, Any], api: ApiClient, storage: ObjectStorage, cfg: WorkerConfig, ctx: TaskContext) -> None:
    run = assignment["run"]
    config = run["config"]
    attempt_id = run["attemptId"]
    token = assignment["leaseToken"]
    prefix = assignment["objectPrefix"]

    # Pinned version: never silently run different code than the run requested.
    if config.get("function") != computation.FUNCTION_NAME or config.get("implementationVersion") != computation.IMPLEMENTATION_VERSION:
        raise UnsupportedVersionError(
            f"Worker implements {computation.FUNCTION_NAME} {computation.IMPLEMENTATION_VERSION}; "
            f"run requested {config.get('function')} {config.get('implementationVersion')}"
        )
    fault = config.get("faultInjection")
    if fault == "INVALID_INPUT":
        raise InvalidInputError("Fault injection (test): input rejected as invalid")

    gain = float(config["parameters"]["gain"])
    bias = float(config["parameters"]["bias"])
    source = config["input"]
    shape = tuple(int(n) for n in source["shape"])
    elements = shape[0] * shape[1] * shape[2]
    max_dim = int(config["preview"]["maxDimension"])
    rows = rows_per_slab(shape[1] * shape[2], 4, cfg.slab_bytes)
    needed = elements * 4 * 2 + 3 * max(shape) * max_dim * max_dim + 3 * NPY_HEADER_ALLOWANCE

    with task_workspace(cfg.tmp_dir, needed) as work:
        input_path = work / "input.npy"
        storage.download_file(source["objectKey"], input_path)
        if sha256_file(input_path) != source["sha256"]:
            raise InvalidInputError("Downloaded input does not match the dataset's recorded SHA-256")
        ctx.check()

        output_path = work / "output.npy"
        preview_path = work / "preview.u8"
        delay = SyntheticDelay(cfg.synthetic_delay_seconds, ctx)
        first_attempt = run["attemptNumber"] == 1

        def on_slab(fraction: float) -> None:
            delay.advance(fraction)
            if fault == "TRANSIENT_ON_FIRST_ATTEMPT" and first_attempt and fraction >= 0.5:
                raise TransientError("Fault injection (demo): simulated transient failure on the first attempt")

        ctx.start_compute()
        summary = computation.compute(input_path, output_path, shape, gain, bias, rows, on_slab)  # type: ignore[arg-type]
        preview_meta = build_preview_stack(output_path, preview_path, summary["min"], summary["max"], max_dim, rows)
        ctx.end_compute()
        ctx.check()

        output_sha = sha256_file(output_path)
        preview_sha = sha256_file(preview_path)
        output_key = prefix + "output.npy"
        preview_key = prefix + "preview.u8"
        storage.upload_file(output_path, output_key, content_type="application/octet-stream", sha256=output_sha)
        storage.upload_file(preview_path, preview_key, content_type="application/octet-stream", sha256=preview_sha)
        # Uploaded but not yet published. If the lease is gone now, these objects stay
        # unreferenced (orphans) and can never become authoritative.
        ctx.check()

        body = {
            "leaseToken": token,
            "computeStartedAt": iso(ctx.compute_started_at),
            "computeFinishedAt": iso(ctx.compute_finished_at),
            "summary": summary,
            "output": {
                "kind": "OUTPUT_TENSOR",
                "objectKey": output_key,
                "contentType": "application/octet-stream",
                "sizeBytes": output_path.stat().st_size,
                "sha256": output_sha,
                "dtype": "float32",
                "shape": list(shape),
                "metadata": {
                    "format": "npy",
                    "function": computation.FUNCTION_NAME,
                    "implementationVersion": computation.IMPLEMENTATION_VERSION,
                    "gain": config["parameters"]["gain"],
                    "bias": config["parameters"]["bias"],
                    "inputSha256": source["sha256"],
                },
            },
            "preview": {
                "kind": "PREVIEW_STACK",
                "objectKey": preview_key,
                "contentType": "application/octet-stream",
                "sizeBytes": preview_path.stat().st_size,
                "sha256": preview_sha,
                "dtype": "uint8",
                "shape": [preview_path.stat().st_size],
                "metadata": preview_meta,
            },
        }
        try:
            api.complete("attempts", attempt_id, body)
        except ApiRejected as exc:
            if exc.status == 422:
                raise TransientError(f"Upload verification failed: {exc}") from exc
            raise


def execute_generation(assignment: dict[str, Any], api: ApiClient, storage: ObjectStorage, cfg: WorkerConfig, ctx: TaskContext) -> None:
    task = assignment["generation"]
    if task["generatorVersion"] != GENERATOR_VERSION:
        raise UnsupportedVersionError(f"Worker implements generator {GENERATOR_VERSION}, not {task['generatorVersion']}")
    shape = tuple(int(n) for n in task["shape"])
    elements = shape[0] * shape[1] * shape[2]
    rows = rows_per_slab(shape[1] * shape[2], 4, cfg.slab_bytes)

    with task_workspace(cfg.tmp_dir, elements * 4 + NPY_HEADER_ALLOWANCE) as work:
        path = work / "input.npy"
        ctx.start_compute()
        generate_dataset(shape, int(task["seed"]), path, rows, lambda fraction: ctx.check())  # type: ignore[arg-type]
        ctx.end_compute()
        sha = sha256_file(path)
        key = assignment["objectPrefix"] + "input.npy"
        storage.upload_file(path, key, content_type="application/octet-stream", sha256=sha)
        ctx.check()
        api.complete(
            "datasets",
            task["datasetId"],
            {
                "leaseToken": assignment["leaseToken"],
                "artifact": {
                    "kind": "INPUT_TENSOR",
                    "objectKey": key,
                    "contentType": "application/octet-stream",
                    "sizeBytes": path.stat().st_size,
                    "sha256": sha,
                    "dtype": "int32",
                    "shape": list(shape),
                    "metadata": {"format": "npy", "generatorVersion": GENERATOR_VERSION, "seed": task["seed"]},
                },
            },
        )


def task_identity(assignment: dict[str, Any]) -> tuple[str, str]:
    """(URL kind, id) used by heartbeat/complete/fail endpoints."""
    if assignment["type"] == "RUN":
        return "attempts", assignment["run"]["attemptId"]
    return "datasets", assignment["generation"]["datasetId"]


def describe(assignment: dict[str, Any]) -> str:
    if assignment["type"] == "RUN":
        run = assignment["run"]
        ordinal = f" #{run['sweepOrdinal']}" if run.get("sweepOrdinal") is not None else ""
        return f"run {run['runId'][:8]}{ordinal} attempt {run['attemptNumber']}"
    return f"dataset {assignment['generation']['datasetId'][:8]}"
