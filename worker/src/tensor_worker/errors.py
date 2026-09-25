"""Explicit failure classification.

The API retries only transient categories. Everything else fails the run with a
useful message; an explicit retry is still available to a user.
"""

from __future__ import annotations

import botocore.exceptions
import requests


class TaskError(Exception):
    category = "INTERNAL"


class TransientError(TaskError):
    """A retry of the unchanged request may succeed (network, storage, service hiccup)."""

    category = "TRANSIENT"


class InvalidInputError(TaskError):
    category = "INVALID_INPUT"


class UnsupportedVersionError(TaskError):
    category = "UNSUPPORTED_VERSION"


class ResourceExhaustedError(TaskError):
    category = "RESOURCE_EXHAUSTED"


class ShutdownRequested(TaskError):
    """The worker is stopping; the run should be retried elsewhere."""

    category = "WORKER_SHUTDOWN"


class LeaseLost(Exception):
    """This slot no longer owns the task. Stop and discard results; report nothing."""


_TRANSIENT_BOTO = (
    botocore.exceptions.EndpointConnectionError,
    botocore.exceptions.ConnectionClosedError,
    botocore.exceptions.ReadTimeoutError,
    botocore.exceptions.ConnectTimeoutError,
    botocore.exceptions.IncompleteReadError,
)


def classify(exc: BaseException) -> tuple[str, str]:
    """Maps an exception to (category, message) for the failure report."""
    if isinstance(exc, TaskError):
        return exc.category, str(exc) or exc.__class__.__name__
    if isinstance(exc, MemoryError):
        return "RESOURCE_EXHAUSTED", "Out of memory while processing; not retried automatically"
    if isinstance(exc, _TRANSIENT_BOTO):
        return "TRANSIENT", f"Storage transport error: {exc}"
    if isinstance(exc, botocore.exceptions.ClientError):
        status = exc.response.get("ResponseMetadata", {}).get("HTTPStatusCode", 0)
        code = exc.response.get("Error", {}).get("Code", "")
        if status >= 500 or code in {"SlowDown", "RequestTimeout", "ServiceUnavailable", "InternalError"}:
            return "TRANSIENT", f"Storage service error {status} {code}"
        if code in {"NoSuchKey", "404"}:
            return "INVALID_INPUT", f"Input object not found: {exc}"
        return "INTERNAL", f"Storage client error {status} {code}"
    if isinstance(exc, (requests.ConnectionError, requests.Timeout)):
        return "TRANSIENT", f"API transport error: {exc}"
    if isinstance(exc, OSError) and exc.errno == 28:  # ENOSPC
        return "RESOURCE_EXHAUSTED", "No space left on the worker's temporary disk"
    return "INTERNAL", f"{exc.__class__.__name__}: {exc}"
