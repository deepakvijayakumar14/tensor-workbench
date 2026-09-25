"""Small object-storage interface so numerical code never depends on boto3 directly."""

from __future__ import annotations

import shutil
from pathlib import Path
from typing import Protocol

import boto3
from boto3.s3.transfer import TransferConfig
from botocore.config import Config

from .config import WorkerConfig


class ObjectStorage(Protocol):
    def download_file(self, key: str, path: Path) -> None: ...

    def upload_file(self, path: Path, key: str, *, content_type: str, sha256: str) -> None: ...


class S3ObjectStorage:
    """S3-compatible storage (SeaweedFS locally). Transfers stream to and from disk."""

    def __init__(self, cfg: WorkerConfig) -> None:
        self._bucket = cfg.storage_bucket
        self._client = boto3.client(
            "s3",
            endpoint_url=cfg.storage_endpoint,
            aws_access_key_id=cfg.storage_access_key,
            aws_secret_access_key=cfg.storage_secret_key,
            region_name=cfg.storage_region,
            config=Config(
                s3={"addressing_style": "path"},
                # S3-compatible stores differ in support for the newer default checksums.
                request_checksum_calculation="when_required",
                response_checksum_validation="when_required",
                retries={"max_attempts": 3, "mode": "standard"},
                connect_timeout=5,
                read_timeout=60,
            ),
        )
        # Multipart above 16 MiB; parts are read from disk, never the whole file in memory.
        self._transfer = TransferConfig(multipart_threshold=16 * 1024 * 1024, multipart_chunksize=16 * 1024 * 1024)

    def download_file(self, key: str, path: Path) -> None:
        self._client.download_file(self._bucket, key, str(path), Config=self._transfer)

    def upload_file(self, path: Path, key: str, *, content_type: str, sha256: str) -> None:
        self._client.upload_file(
            str(path),
            self._bucket,
            key,
            ExtraArgs={"ContentType": content_type, "Metadata": {"sha256": sha256}},
            Config=self._transfer,
        )


class LocalObjectStorage:
    """Directory-backed storage for unit tests."""

    def __init__(self, root: Path) -> None:
        self.root = root
        self.metadata: dict[str, dict[str, str]] = {}

    def download_file(self, key: str, path: Path) -> None:
        shutil.copyfile(self.root / key, path)

    def upload_file(self, path: Path, key: str, *, content_type: str, sha256: str) -> None:
        target = self.root / key
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, target)
        self.metadata[key] = {"content_type": content_type, "sha256": sha256}
