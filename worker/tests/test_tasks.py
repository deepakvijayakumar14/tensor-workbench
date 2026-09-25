import threading
from pathlib import Path
from typing import Any

import botocore.exceptions
import numpy as np
import pytest
import requests

from tensor_worker.api_client import LeaseConflict
from tensor_worker.compute import COEFFICIENTS
from tensor_worker.config import WorkerConfig
from tensor_worker.errors import (
    InvalidInputError,
    LeaseLost,
    ShutdownRequested,
    TransientError,
    UnsupportedVersionError,
    classify,
)
from tensor_worker.files import sha256_file
from tensor_worker.generate import generate_dataset
from tensor_worker.slot import process_assignment
from tensor_worker.storage import LocalObjectStorage
from tensor_worker.tasks import TaskContext, execute_generation, execute_run


class FakeApi:
    def __init__(self) -> None:
        self.completed: list[tuple[str, str, dict[str, Any]]] = []
        self.failed: list[tuple[str, str, dict[str, Any]]] = []
        self.complete_raises: Exception | None = None

    def complete(self, kind: str, task_id: str, body: dict[str, Any]) -> None:
        if self.complete_raises:
            raise self.complete_raises
        self.completed.append((kind, task_id, body))

    def fail(self, kind: str, task_id: str, body: dict[str, Any]) -> str:
        self.failed.append((kind, task_id, body))
        return "QUEUED"

    def heartbeat(self, kind: str, task_id: str, token: int) -> None:
        return None


def config(tmp_path: Path) -> WorkerConfig:
    return WorkerConfig(
        api_url="http://api", worker_token="t", slots=3, poll_interval_seconds=0.1,
        storage_endpoint="", storage_bucket="", storage_access_key="", storage_secret_key="", storage_region="",
        tmp_dir=str(tmp_path / "tmp"), slab_bytes=4096, synthetic_delay_seconds=0.0,
        shutdown_grace_seconds=1.0, instance_id="test",
    )


@pytest.fixture
def storage(tmp_path: Path) -> LocalObjectStorage:
    root = tmp_path / "bucket"
    root.mkdir()
    store = LocalObjectStorage(root)
    (root / "datasets/d1").mkdir(parents=True)
    generate_dataset((12, 10, 8), seed=5, out_path=root / "datasets/d1/input.npy", rows_per_slab=3)
    return store


def run_assignment(storage: LocalObjectStorage, attempt: int = 1, fault: str | None = None, version: str = "1.0.0") -> dict[str, Any]:
    key = "datasets/d1/input.npy"
    return {
        "type": "RUN",
        "leaseToken": 42,
        "leaseDurationSeconds": 15,
        "heartbeatIntervalSeconds": 5,
        "objectPrefix": f"runs/r1/attempt-{attempt}-42/",
        "run": {
            "runId": "r1", "attemptId": "a1", "attemptNumber": attempt, "sweepId": None, "sweepOrdinal": None,
            "config": {
                "function": "affine-material-map",
                "implementationVersion": version,
                "parameters": {"gain": "3", "bias": "0.5"},
                "input": {"objectKey": key, "sha256": sha256_file(storage.root / key), "shape": [12, 10, 8], "dtype": "int32"},
                "preview": {"maxDimension": 4},
                "faultInjection": fault,
            },
        },
    }


def test_run_uploads_result_and_publishes_verified_metadata(tmp_path: Path, storage: LocalObjectStorage) -> None:
    api = FakeApi()
    execute_run(run_assignment(storage), api, storage, config(tmp_path), TaskContext(shutdown=threading.Event()))

    [(kind, task_id, body)] = api.completed
    assert (kind, task_id) == ("attempts", "a1")
    output_key = body["output"]["objectKey"]
    assert output_key == "runs/r1/attempt-1-42/output.npy"  # attempt-scoped, never overwritten by another attempt
    out = np.load(storage.root / output_key)
    codes = np.load(storage.root / "datasets/d1/input.npy")
    assert np.array_equal(out, (np.float32(3) * COEFFICIENTS + np.float32(0.5))[codes])
    assert body["output"]["sha256"] == sha256_file(storage.root / output_key)
    assert storage.metadata[output_key]["sha256"] == body["output"]["sha256"]
    assert body["preview"]["metadata"]["maxDimension"] == 4
    assert body["computeStartedAt"] <= body["computeFinishedAt"]
    assert list(tmp_path.joinpath("tmp").iterdir()) == []  # temporary files released


def test_demo_fault_fails_first_attempt_transiently_then_succeeds(tmp_path: Path, storage: LocalObjectStorage) -> None:
    api = FakeApi()
    with pytest.raises(TransientError, match="first attempt"):
        execute_run(run_assignment(storage, attempt=1, fault="TRANSIENT_ON_FIRST_ATTEMPT"), api, storage, config(tmp_path),
                    TaskContext(shutdown=threading.Event()))
    execute_run(run_assignment(storage, attempt=2, fault="TRANSIENT_ON_FIRST_ATTEMPT"), api, storage, config(tmp_path),
                TaskContext(shutdown=threading.Event()))
    assert len(api.completed) == 1


def test_permanent_failures_are_classified(tmp_path: Path, storage: LocalObjectStorage) -> None:
    ctx = TaskContext(shutdown=threading.Event())
    with pytest.raises(InvalidInputError):
        execute_run(run_assignment(storage, fault="INVALID_INPUT"), FakeApi(), storage, config(tmp_path), ctx)
    with pytest.raises(UnsupportedVersionError):
        execute_run(run_assignment(storage, version="2.0.0"), FakeApi(), storage, config(tmp_path), ctx)


def test_corrupted_input_is_invalid(tmp_path: Path, storage: LocalObjectStorage) -> None:
    assignment = run_assignment(storage)
    assignment["run"]["config"]["input"]["sha256"] = "0" * 64
    with pytest.raises(InvalidInputError, match="SHA-256"):
        execute_run(assignment, FakeApi(), storage, config(tmp_path), TaskContext(shutdown=threading.Event()))


def test_lost_lease_stops_before_publishing(tmp_path: Path, storage: LocalObjectStorage) -> None:
    api = FakeApi()
    ctx = TaskContext(shutdown=threading.Event())
    ctx.lease_lost.set()
    with pytest.raises(LeaseLost):
        execute_run(run_assignment(storage), api, storage, config(tmp_path), ctx)
    assert api.completed == []


def test_process_assignment_reports_failures_but_not_lost_leases(tmp_path: Path, storage: LocalObjectStorage) -> None:
    api = FakeApi()
    process_assignment(run_assignment(storage, fault="INVALID_INPUT"), api, storage, config(tmp_path), threading.Event(), "w/slot-0")
    [(_, _, body)] = api.failed
    assert body["category"] == "INVALID_INPUT" and body["leaseToken"] == 42

    api = FakeApi()
    api.complete_raises = LeaseConflict("stale")
    process_assignment(run_assignment(storage), api, storage, config(tmp_path), threading.Event(), "w/slot-0")
    assert api.failed == []  # a superseded attempt reports nothing


def test_shutdown_is_reported_as_retryable(tmp_path: Path, storage: LocalObjectStorage) -> None:
    stop = threading.Event()
    stop.set()
    api = FakeApi()
    process_assignment(run_assignment(storage), api, storage, config(tmp_path), stop, "w/slot-0")
    assert api.failed[0][2]["category"] == "WORKER_SHUTDOWN"


def test_generation_uploads_input(tmp_path: Path, storage: LocalObjectStorage) -> None:
    api = FakeApi()
    assignment = {
        "type": "GENERATE_DATASET", "leaseToken": 7, "leaseDurationSeconds": 15, "heartbeatIntervalSeconds": 5,
        "objectPrefix": "datasets/d2/generation-7/",
        "generation": {"datasetId": "d2", "attemptNumber": 1, "shape": [6, 5, 4], "seed": 11, "generatorVersion": "layered-inclusions-1"},
    }
    execute_generation(assignment, api, storage, config(tmp_path), TaskContext(shutdown=threading.Event()))
    [(kind, task_id, body)] = api.completed
    assert (kind, task_id) == ("datasets", "d2")
    assert np.load(storage.root / body["artifact"]["objectKey"]).shape == (6, 5, 4)


def test_classify_maps_infrastructure_errors() -> None:
    assert classify(TransientError("x"))[0] == "TRANSIENT"
    assert classify(ShutdownRequested("x"))[0] == "WORKER_SHUTDOWN"
    assert classify(MemoryError())[0] == "RESOURCE_EXHAUSTED"
    assert classify(requests.ConnectionError("down"))[0] == "TRANSIENT"
    assert classify(botocore.exceptions.EndpointConnectionError(endpoint_url="http://s3"))[0] == "TRANSIENT"
    slow = botocore.exceptions.ClientError({"Error": {"Code": "SlowDown"}, "ResponseMetadata": {"HTTPStatusCode": 503}}, "GetObject")
    assert classify(slow)[0] == "TRANSIENT"
    assert classify(ValueError("bug"))[0] == "INTERNAL"
