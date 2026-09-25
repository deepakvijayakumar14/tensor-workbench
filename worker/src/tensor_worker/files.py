from __future__ import annotations

import hashlib
import shutil
import tempfile
from contextlib import contextmanager
from pathlib import Path
from typing import Iterator

from .errors import TransientError


def sha256_file(path: Path, chunk_size: int = 1024 * 1024) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        while chunk := f.read(chunk_size):
            digest.update(chunk)
    return digest.hexdigest()


def rows_per_slab(plane_elements: int, itemsize: int, slab_bytes: int) -> int:
    """How many axis-0 planes fit in the slab budget (at least one)."""
    return max(1, slab_bytes // max(1, plane_elements * itemsize))


@contextmanager
def task_workspace(base_dir: str, needed_bytes: int) -> Iterator[Path]:
    """A private temporary directory that is always removed after the task.

    Refuses to start when the disk cannot hold the task's files, rather than
    failing halfway. Another slot may finish and free space, so this is transient.
    """
    base = Path(base_dir)
    base.mkdir(parents=True, exist_ok=True)
    free = shutil.disk_usage(base).free
    if free < needed_bytes:
        raise TransientError(f"Temporary disk has {free} bytes free; task needs about {needed_bytes}")
    path = Path(tempfile.mkdtemp(prefix="task-", dir=base))
    try:
        yield path
    finally:
        shutil.rmtree(path, ignore_errors=True)
