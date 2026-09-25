"""Measures the worker's numerical path (generate -> compute -> preview) on one shape.

    docker compose run --rm --no-deps worker python -m tensor_worker.benchmark --shape 256 256 256

This bypasses the API and object storage on purpose: it measures slab processing
and memory behaviour only. Each phase runs in a fresh process; a sampler thread
reads /proc/self/status every 5 ms and records the peak of:

  RssAnon  anonymous memory (NumPy slabs, Python heap) -- the bounded working set
  RssFile  file-backed pages mapped into the process (memory-mapped .npy files);
           these are page cache, grow with tensor size, and are reclaimable

Linux only (reads /proc). Sampling can miss very short spikes.
"""

from __future__ import annotations

import argparse
import json
import multiprocessing as mp
import os
import platform
import tempfile
import threading
import time
from pathlib import Path
from typing import Any, Callable

from .compute import compute
from .files import rows_per_slab
from .generate import generate_dataset
from .preview import build_preview_stack


def _read_status() -> dict[str, int]:
    values: dict[str, int] = {}
    with open("/proc/self/status") as f:
        for line in f:
            key, _, rest = line.partition(":")
            if key in ("RssAnon", "RssFile", "VmHWM"):
                values[key] = int(rest.split()[0]) * 1024
    return values


def _phase(fn: Callable[[], Any], out: Any) -> None:
    peaks = {"RssAnon": 0, "RssFile": 0}
    done = threading.Event()

    def sample() -> None:
        while not done.is_set():
            status = _read_status()
            for k in peaks:
                peaks[k] = max(peaks[k], status.get(k, 0))
            time.sleep(0.005)

    sampler = threading.Thread(target=sample, daemon=True)
    baseline = _read_status()
    sampler.start()
    started = time.perf_counter()
    result = fn()
    elapsed = time.perf_counter() - started
    done.set()
    sampler.join()
    out.put(
        {
            "seconds": round(elapsed, 3),
            "peakRssAnonMiB": round(peaks["RssAnon"] / 2**20, 1),
            "baselineRssAnonMiB": round(baseline.get("RssAnon", 0) / 2**20, 1),
            "peakRssFileMiB": round(peaks["RssFile"] / 2**20, 1),
            "result": result,
        }
    )


def _run(name: str, fn: Callable[[], Any]) -> dict[str, Any]:
    ctx = mp.get_context("fork")
    queue = ctx.Queue()
    process = ctx.Process(target=_phase, args=(fn, queue))
    process.start()
    measurement = queue.get()
    process.join()
    print(f"  {name:<8} {json.dumps({k: v for k, v in measurement.items() if k != 'result'})}", flush=True)
    return measurement


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--shape", type=int, nargs=3, default=[256, 256, 256])
    parser.add_argument("--slab-bytes", type=int, default=2 * 1024 * 1024)
    parser.add_argument("--preview-max", type=int, default=128)
    parser.add_argument("--dir", default=tempfile.gettempdir())
    args = parser.parse_args()

    shape = tuple(args.shape)
    elements = shape[0] * shape[1] * shape[2]
    rows = rows_per_slab(shape[1] * shape[2], 4, args.slab_bytes)
    work = Path(tempfile.mkdtemp(prefix="bench-", dir=args.dir))
    inp, out, prev = work / "input.npy", work / "output.npy", work / "preview.u8"
    print(
        f"shape={shape} elements={elements:,} slab={rows} planes (~{rows * shape[1] * shape[2] * 4 / 2**20:.1f} MiB) "
        f"cpu={os.cpu_count()} python={platform.python_version()} machine={platform.machine()}",
        flush=True,
    )
    try:
        report: dict[str, Any] = {"shape": list(shape), "elements": elements, "slabPlanes": rows}
        report["generate"] = _run("generate", lambda: generate_dataset(shape, 1, inp, rows))
        report["compute"] = _run("compute", lambda: compute(inp, out, shape, 2.0, 0.5, rows))
        summary = report["compute"]["result"]
        report["preview"] = _run(
            "preview", lambda: build_preview_stack(out, prev, summary["min"], summary["max"], args.preview_max, rows)["axes"][0]
        )
        report["files"] = {
            "inputMiB": round(inp.stat().st_size / 2**20, 1),
            "outputMiB": round(out.stat().st_size / 2**20, 1),
            "previewMiB": round(prev.stat().st_size / 2**20, 1),
        }
        print("  files   ", json.dumps(report["files"]), flush=True)
        for phase in ("generate", "compute", "preview"):
            report[phase].pop("result", None)
        print(json.dumps(report))
    finally:
        for p in (inp, out, prev):
            p.unlink(missing_ok=True)
        work.rmdir()


if __name__ == "__main__":
    main()
