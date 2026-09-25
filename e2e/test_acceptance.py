"""Acceptance checks against the running Compose stack.

    docker compose up --build --wait
    cd e2e && uv run pytest

Everything goes through the public web proxy (http://localhost:3000) plus
`docker compose` for the worker-restart scenario. Nothing here is mocked.
"""

from __future__ import annotations

import io
import json
import os
import subprocess
import time
import uuid
from pathlib import Path
from typing import Any, Callable

import numpy as np
import pytest
import requests

BASE = os.environ.get("WORKBENCH_URL", "http://localhost:3000")
REPO = Path(__file__).resolve().parent.parent
# Must match worker/src/tensor_worker/compute.py (implementation 1.0.0).
COEFFICIENTS = np.array([0.0, 1.0, 0.35, 2.2, 1.6, 0.8, 3.1, 0.55], dtype=np.float32)


# ---------------------------------------------------------------- helpers


def api(method: str, path: str, *, key: str | None = None, body: Any = None, **kwargs: Any) -> requests.Response:
    headers = {"Idempotency-Key": key} if key else {}
    return requests.request(method, BASE + path, json=body, headers=headers, timeout=30, **kwargs)


def new_key() -> str:
    return f"e2e-{uuid.uuid4()}"


def wait_for(predicate: Callable[[], Any], timeout: float, interval: float = 0.5, what: str = "condition") -> Any:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        value = predicate()
        if value:
            return value
        time.sleep(interval)
    raise AssertionError(f"Timed out after {timeout}s waiting for {what}")


def compose(*args: str) -> str:
    return subprocess.run(["docker", "compose", *args], cwd=REPO, check=True, capture_output=True, text=True).stdout


def ready_dataset(shape: list[int], seed: int) -> dict[str, Any]:
    created = api("POST", "/api/datasets", key=new_key(), body={"shape": shape, "seed": seed})
    assert created.status_code == 202, created.text
    dataset_id = created.json()["id"]
    return wait_for(
        lambda: (d := api("GET", f"/api/datasets/{dataset_id}").json())["status"] == "READY" and d,
        timeout=60,
        what="dataset READY",
    )


def submit_sweep(dataset_id: str, end: int, *, demo: bool, key: str | None = None) -> requests.Response:
    body = {
        "datasetId": dataset_id, "parameter": "gain", "start": 1, "end": end, "step": 1, "bias": 0.5,
        "implementationVersion": "1.0.0", "demoTransientFailure": demo,
    }
    return api("POST", "/api/sweeps", key=key or new_key(), body=body)


def wait_sweep_done(sweep_id: str, timeout: float) -> dict[str, Any]:
    return wait_for(lambda: (s := api("GET", f"/api/sweeps/{sweep_id}").json())["done"] and s, timeout, what="sweep done")


def sweep_runs(sweep_id: str) -> list[dict[str, Any]]:
    runs: list[dict[str, Any]] = []
    page = 0
    while True:
        body = api("GET", f"/api/sweeps/{sweep_id}/runs?page={page}&size=100").json()
        runs += body["items"]
        if page + 1 >= body["totalPages"]:
            return runs
        page += 1


def run_count() -> int:
    return sum(api("GET", "/api/system/status").json()["runs"].values())


def load_npy(content: bytes) -> np.ndarray:
    return np.load(io.BytesIO(content), allow_pickle=False)


# ---------------------------------------------------------------- fixtures


@pytest.fixture(scope="session")
def dataset() -> dict[str, Any]:
    return ready_dataset([64, 64, 64], seed=2024)


@pytest.fixture(scope="session")
def demo_sweep(dataset: dict[str, Any]) -> dict[str, Any]:
    response = submit_sweep(dataset["id"], 20, demo=True)
    assert response.status_code == 202, response.text
    return wait_sweep_done(response.json()["id"], timeout=240)


# ---------------------------------------------------------------- checks


def test_stack_serves_ui_api_and_workers() -> None:
    """Check 1: one command starts the stack; storage, schema and UI are ready."""
    ui = requests.get(BASE + "/", timeout=10)
    assert ui.status_code == 200 and "<div id=\"root\">" in ui.text
    info = api("GET", "/api/system").json()
    assert "Synthetic" in info["disclaimer"]
    status = wait_for(lambda: (s := api("GET", "/api/system/status").json())["totalSlots"] and s, 30, what="worker")
    assert status["totalSlots"] == 3
    assert api("GET", "/api/openapi").status_code == 200
    # The worker-only API is not exposed through the public proxy.
    assert requests.post(BASE + "/internal/tasks/claim", json={}, timeout=10).status_code in (404, 405)


def test_twenty_variants_never_exceed_three_simultaneous_computations(demo_sweep: dict[str, Any]) -> None:
    """Check 2: bounded capacity, verified from instrumented start/end events."""
    assert demo_sweep["counts"]["succeeded"] == 20
    timeline = api("GET", f"/api/sweeps/{demo_sweep['id']}/timeline").json()
    assert all(i["measured"] for i in timeline["intervals"])
    assert timeline["peakConcurrency"] == 3, timeline["peakConcurrency"]

    # Independent evidence from the worker's own compute_start events (shared counter across slots).
    events = [
        json.loads(line[line.index("{"):])
        for line in compose("logs", "--no-log-prefix", "worker").splitlines()
        if '"event": "compute_start"' in line
    ]
    assert events and max(e["active"] for e in events) <= 3


def test_demo_child_fails_transiently_once_then_succeeds(demo_sweep: dict[str, Any]) -> None:
    """Check 4a: the controlled transient error retries and succeeds."""
    ordinal = demo_sweep["demoTransientFailureOrdinal"]
    child = next(r for r in sweep_runs(demo_sweep["id"]) if r["sweepOrdinal"] == ordinal)
    detail = api("GET", f"/api/runs/{child['id']}").json()
    outcomes = [(a["attemptNumber"], a["outcome"], a["errorCategory"], a["accepted"]) for a in detail["attempts"]]
    assert outcomes == [(1, "FAILED", "TRANSIENT", False), (2, "SUCCEEDED", None, True)]
    assert demo_sweep["retries"] == 1


def test_permanent_invalid_input_is_not_retried(dataset: dict[str, Any]) -> None:
    """Check 4b: a permanent failure does not consume the transient retry budget."""
    body = {"datasetId": dataset["id"], "gain": 1, "bias": 0, "implementationVersion": "1.0.0", "faultInjection": "INVALID_INPUT"}
    run = api("POST", "/api/runs", key=new_key(), body=body).json()
    final = wait_for(
        lambda: (r := api("GET", f"/api/runs/{run['id']}").json()["run"])["state"] == "FAILED" and r, 60, what="run FAILED"
    )
    assert final["attemptCount"] == 1
    assert final["lastErrorCategory"] == "INVALID_INPUT"


def test_idempotent_resubmission_adds_nothing_and_conflicts_on_change(dataset: dict[str, Any]) -> None:
    """Check 3."""
    key = new_key()
    first = submit_sweep(dataset["id"], 3, demo=False, key=key)
    before = run_count()
    again = submit_sweep(dataset["id"], 3, demo=False, key=key)
    assert again.status_code == 202 and again.json()["id"] == first.json()["id"]
    assert again.headers["Idempotent-Replayed"] == "true"
    assert run_count() == before
    changed = submit_sweep(dataset["id"], 4, demo=False, key=key)
    assert changed.status_code == 409 and changed.json()["error"] == "IDEMPOTENCY_KEY_REUSED"
    assert run_count() == before
    wait_sweep_done(first.json()["id"], timeout=120)


def test_download_matches_input_parameters_version_shape_and_dtype(demo_sweep: dict[str, Any], dataset: dict[str, Any]) -> None:
    """Check 7: download through the presigned URL (as a browser would) and verify every value."""
    child = next(r for r in sweep_runs(demo_sweep["id"]) if r["gain"] == 7)
    detail = api("GET", f"/api/runs/{child['id']}").json()
    output_meta = next(a for a in detail["artifacts"] if a["kind"] == "OUTPUT_TENSOR")

    redirect = api("GET", f"/api/runs/{child['id']}/download", allow_redirects=False)
    assert redirect.status_code == 302
    assert redirect.headers["Location"].startswith("http://localhost:8333/")  # browser-reachable, not a container name
    downloaded = requests.get(redirect.headers["Location"], timeout=60)
    assert downloaded.status_code == 200
    assert "attachment" in downloaded.headers.get("Content-Disposition", "")

    import hashlib

    assert hashlib.sha256(downloaded.content).hexdigest() == output_meta["sha256"]
    output = load_npy(downloaded.content)
    source = load_npy(requests.get(BASE + f"/api/datasets/{dataset['id']}/download", timeout=60).content)
    assert output.shape == tuple(dataset["shape"]) and output.dtype == np.float32
    assert source.dtype == np.int32
    expected = (np.float32(7) * COEFFICIENTS + np.float32(0.5)).astype(np.float32)[source]
    assert np.array_equal(output, expected)
    assert detail["configSnapshot"]["implementationVersion"] == "1.0.0"
    assert detail["configSnapshot"]["input"]["sha256"] == dataset["input"]["sha256"]


def test_list_summary_and_preview_payloads_are_bounded(demo_sweep: dict[str, Any]) -> None:
    """Check 8 (API side): browsing never transfers full arrays."""
    output_size = next(r for r in sweep_runs(demo_sweep["id"]) if r["result"])["result"]["outputSizeBytes"]
    page = api("GET", f"/api/sweeps/{demo_sweep['id']}/runs?page=0&size=20")
    run_id = page.json()["items"][0]["id"]
    detail = api("GET", f"/api/runs/{run_id}")
    preview = api("GET", f"/api/runs/{run_id}/preview?axis=0&index=10")
    assert preview.json()["width"] * preview.json()["height"] <= 128 * 128
    for response in (page, detail, preview):
        assert len(response.content) < min(64 * 1024, output_size), (response.url, len(response.content))


def test_worker_crash_keeps_results_and_recovers_after_lease_expiry(dataset: dict[str, Any]) -> None:
    """Check 5: kill the worker mid-sweep, restart it, and everything still finishes."""
    sweep_id = submit_sweep(dataset["id"], 9, demo=False).json()["id"]
    wait_for(lambda: api("GET", f"/api/sweeps/{sweep_id}").json()["counts"]["succeeded"] >= 1, 60, what="first success")
    wait_for(lambda: api("GET", f"/api/sweeps/{sweep_id}").json()["counts"]["running"] >= 1, 30, what="running work")

    accepted_before = {
        r["id"]: api("GET", f"/api/runs/{r['id']}").json()["artifacts"] for r in sweep_runs(sweep_id) if r["state"] == "SUCCEEDED"
    }
    compose("kill", "-s", "SIGKILL", "worker")  # a crash: no graceful shutdown, leases are left to expire
    try:
        time.sleep(2)
        assert api("GET", f"/api/sweeps/{sweep_id}").json()["counts"]["running"] >= 1  # still leased
    finally:
        compose("start", "worker")

    final = wait_sweep_done(sweep_id, timeout=180)
    assert final["counts"]["succeeded"] == 9
    for run_id, artifacts in accepted_before.items():
        assert api("GET", f"/api/runs/{run_id}").json()["artifacts"] == artifacts  # completed results intact
    outcomes = [a["outcome"] for r in sweep_runs(sweep_id) for a in api("GET", f"/api/runs/{r['id']}").json()["attempts"]]
    assert "LEASE_EXPIRED" in outcomes


def test_graceful_worker_restart_requeues_in_flight_work_immediately(dataset: dict[str, Any]) -> None:
    """Check 5 (graceful variant): SIGTERM stops slots at the next slab and hands work back at once."""
    sweep_id = submit_sweep(dataset["id"], 6, demo=False).json()["id"]
    wait_for(lambda: api("GET", f"/api/sweeps/{sweep_id}").json()["counts"]["running"] == 3, 60, what="three running")

    started = time.monotonic()
    compose("restart", "worker")
    assert time.monotonic() - started < 10  # well inside the 15 s stop grace period: no SIGKILL needed

    final = wait_sweep_done(sweep_id, timeout=120)
    assert final["counts"]["succeeded"] == 6
    categories = [
        a["errorCategory"] for r in sweep_runs(sweep_id) for a in api("GET", f"/api/runs/{r['id']}").json()["attempts"] if a["errorCategory"]
    ]
    assert categories and set(categories) == {"WORKER_SHUTDOWN"}
