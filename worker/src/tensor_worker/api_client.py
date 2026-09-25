"""Client for the API's internal worker endpoints."""

from __future__ import annotations

import logging
import random
import time
from typing import Any

import requests

from .config import WorkerConfig
from .errors import TransientError

log = logging.getLogger(__name__)


class LeaseConflict(Exception):
    """409: this lease token no longer owns the task (or the transition is illegal)."""


class ApiRejected(Exception):
    """A 4xx other than 409; the request itself is wrong."""

    def __init__(self, status: int, body: str) -> None:
        super().__init__(f"API rejected request with {status}: {body[:500]}")
        self.status = status


class ApiClient:
    def __init__(self, cfg: WorkerConfig, session: requests.Session | None = None) -> None:
        self._base = cfg.api_url
        self._session = session or requests.Session()
        self._session.headers.update({"X-Worker-Token": cfg.worker_token})

    # ---- low level ----

    def _post(self, path: str, body: dict[str, Any], *, retries: int = 0, timeout: float = 10.0) -> requests.Response:
        """POST with optional retries on transport errors and 5xx responses (bounded, jittered)."""
        for attempt in range(retries + 1):
            try:
                response = self._session.post(self._base + path, json=body, timeout=timeout)
            except (requests.ConnectionError, requests.Timeout) as exc:
                if attempt == retries:
                    raise TransientError(f"API unreachable for {path}: {exc}") from exc
                self._sleep_backoff(attempt)
                continue
            if response.status_code >= 500 and attempt < retries:
                self._sleep_backoff(attempt)
                continue
            return self._check(response)
        raise AssertionError("unreachable")

    @staticmethod
    def _sleep_backoff(attempt: int) -> None:
        delay = min(8.0, 0.5 * 2**attempt)
        time.sleep(delay / 2 + random.random() * delay / 2)

    @staticmethod
    def _check(response: requests.Response) -> requests.Response:
        if response.status_code == 409:
            raise LeaseConflict(response.text)
        if response.status_code >= 500:
            raise TransientError(f"API error {response.status_code}: {response.text[:300]}")
        if response.status_code >= 400:
            raise ApiRejected(response.status_code, response.text)
        return response

    # ---- operations ----

    def claim(self, worker_id: str, instance: str, slot_count: int) -> dict[str, Any] | None:
        response = self._post(
            "/internal/tasks/claim",
            {"workerId": worker_id, "workerInstance": instance, "slotCount": slot_count},
        )
        return None if response.status_code == 204 else response.json()

    def heartbeat(self, kind: str, task_id: str, token: int) -> None:
        self._post(f"/internal/{kind}/{task_id}/heartbeat", {"leaseToken": token}, timeout=5.0)

    def complete(self, kind: str, task_id: str, body: dict[str, Any]) -> None:
        # Completion is idempotent on the API side, so retrying after a lost response is safe.
        self._post(f"/internal/{kind}/{task_id}/complete", body, retries=4, timeout=30.0)

    def fail(self, kind: str, task_id: str, body: dict[str, Any]) -> str:
        return self._post(f"/internal/{kind}/{task_id}/fail", body, retries=4).json()["nextState"]

    def cleanup_orphans(self, dry_run: bool, min_age_minutes: int) -> dict[str, Any]:
        response = self._session.post(
            self._base + "/internal/maintenance/orphans",
            params={"dryRun": str(dry_run).lower(), "minAgeMinutes": min_age_minutes},
            timeout=120,
        )
        return self._check(response).json()
