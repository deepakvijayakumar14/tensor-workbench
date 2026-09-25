"""Maintenance commands, run inside the worker container:

    docker compose exec worker python -m tensor_worker.admin cleanup-orphans            # dry run
    docker compose exec worker python -m tensor_worker.admin cleanup-orphans --delete
"""

from __future__ import annotations

import argparse
import json

from .api_client import ApiClient
from .config import WorkerConfig


def main() -> None:
    parser = argparse.ArgumentParser(prog="tensor_worker.admin")
    sub = parser.add_subparsers(dest="command", required=True)
    cleanup = sub.add_parser("cleanup-orphans", help="Find (or delete) stored objects no artifact row references")
    cleanup.add_argument("--delete", action="store_true", help="Actually delete (default is a dry run)")
    cleanup.add_argument("--min-age-minutes", type=int, default=30, help="Skip objects younger than this")
    args = parser.parse_args()

    api = ApiClient(WorkerConfig.from_env())
    if args.command == "cleanup-orphans":
        print(json.dumps(api.cleanup_orphans(dry_run=not args.delete, min_age_minutes=args.min_age_minutes), indent=2))


if __name__ == "__main__":
    main()
