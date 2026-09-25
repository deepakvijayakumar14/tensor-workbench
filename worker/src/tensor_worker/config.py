from __future__ import annotations

import os
import socket
from dataclasses import dataclass


def _env(name: str, default: str) -> str:
    return os.environ.get(name, default)


@dataclass(frozen=True)
class WorkerConfig:
    api_url: str
    worker_token: str
    slots: int
    poll_interval_seconds: float
    storage_endpoint: str
    storage_bucket: str
    storage_access_key: str
    storage_secret_key: str
    storage_region: str
    tmp_dir: str
    # Target working-set size of one slab. Bounds per-slot memory independent of tensor size.
    slab_bytes: int
    # Extra seconds per computation so scheduling is visible in the demo. Synthetic, not real work.
    synthetic_delay_seconds: float
    shutdown_grace_seconds: float
    instance_id: str

    @staticmethod
    def from_env() -> "WorkerConfig":
        return WorkerConfig(
            api_url=_env("API_URL", "http://localhost:8080").rstrip("/"),
            worker_token=_env("WORKER_TOKEN", "dev-worker-token"),
            slots=int(_env("WORKER_SLOTS", "3")),
            poll_interval_seconds=float(_env("WORKER_POLL_INTERVAL_SECONDS", "1.0")),
            storage_endpoint=_env("STORAGE_ENDPOINT", "http://localhost:8333"),
            storage_bucket=_env("STORAGE_BUCKET", "tensor-workbench"),
            storage_access_key=_env("STORAGE_ACCESS_KEY", "workbench-dev"),
            storage_secret_key=_env("STORAGE_SECRET_KEY", "workbench-dev-secret"),
            storage_region=_env("STORAGE_REGION", "us-east-1"),
            tmp_dir=_env("WORKER_TMP_DIR", "/tmp/tensor-worker"),
            slab_bytes=int(_env("WORKER_SLAB_BYTES", str(2 * 1024 * 1024))),
            synthetic_delay_seconds=float(_env("WORKER_SYNTHETIC_DELAY_SECONDS", "0")),
            shutdown_grace_seconds=float(_env("WORKER_SHUTDOWN_GRACE_SECONDS", "8")),
            instance_id=_env("WORKER_INSTANCE_ID", socket.gethostname()),
        )
