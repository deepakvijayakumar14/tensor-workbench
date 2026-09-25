"""Bounded slice previews.

Tradeoff: after computing a run we pre-render, for every axis, every slice as a
downsampled uint8 image (at most max_dim x max_dim, nearest-neighbour sampling,
quantized with the run's global min/max). All slices are stored back to back in
one object, so the API serves any slice with a single small byte-range read and
never touches the full float32 array. The cost is extra storage (for 128^3 about
6 MiB per run, versus 8 MiB for the output itself) and lossy previews: they are
for looking, not measuring. Full precision is always available via download.
"""

from __future__ import annotations

from pathlib import Path
from typing import Any

import numpy as np


def sample_indices(n: int, max_dim: int) -> np.ndarray:
    """Evenly spaced indices, at most max_dim of them, always including both ends."""
    if n <= max_dim:
        return np.arange(n)
    return np.round(np.linspace(0, n - 1, max_dim)).astype(np.int64)


def quantize(values: np.ndarray, value_min: float, value_max: float) -> np.ndarray:
    if value_max <= value_min:
        return np.zeros(values.shape, dtype=np.uint8)
    scaled = (values.astype(np.float64) - value_min) * (255.0 / (value_max - value_min))
    return np.clip(np.rint(scaled), 0, 255).astype(np.uint8)


def preview_layout(shape: tuple[int, int, int], max_dim: int) -> list[dict[str, int]]:
    x_n, y_n, z_n = shape
    hx, hy, hz = (len(sample_indices(n, max_dim)) for n in shape)
    axes = [
        {"axis": 0, "sliceCount": x_n, "height": hy, "width": hz, "sourceHeight": y_n, "sourceWidth": z_n},
        {"axis": 1, "sliceCount": y_n, "height": hx, "width": hz, "sourceHeight": x_n, "sourceWidth": z_n},
        {"axis": 2, "sliceCount": z_n, "height": hx, "width": hy, "sourceHeight": x_n, "sourceWidth": y_n},
    ]
    offset = 0
    for a in axes:
        a["offset"] = offset
        offset += a["sliceCount"] * a["height"] * a["width"]
    return axes


def build_preview_stack(
    output_path: Path,
    preview_path: Path,
    value_min: float,
    value_max: float,
    max_dim: int,
    rows_per_slab: int,
) -> dict[str, Any]:
    """Reads the output once, slab by slab, and writes all three axis stacks."""
    output = np.load(output_path, mmap_mode="r")
    shape = tuple(int(n) for n in output.shape)
    x_n, y_n, z_n = shape
    xs, ys, zs = (sample_indices(n, max_dim) for n in shape)
    axes = preview_layout(shape, max_dim)
    total = sum(a["sliceCount"] * a["height"] * a["width"] for a in axes)

    stack = np.memmap(preview_path, dtype=np.uint8, mode="w+", shape=(total,))
    views = [
        stack[a["offset"] : a["offset"] + a["sliceCount"] * a["height"] * a["width"]].reshape(
            a["sliceCount"], a["height"], a["width"]
        )
        for a in axes
    ]
    by_x, by_y, by_z = views  # slices along axis 0, 1, 2

    for x0 in range(0, x_n, rows_per_slab):
        x1 = min(x_n, x0 + rows_per_slab)
        slab = np.asarray(output[x0:x1])  # (n, Y, Z)
        # Axis-0 slices: every x in the slab, sampled y and z.
        by_x[x0:x1] = quantize(slab[:, ys][:, :, zs], value_min, value_max)
        # Axis-1 and axis-2 slices only need the sampled x rows that fall in this slab.
        picked = np.nonzero((xs >= x0) & (xs < x1))[0]
        if picked.size:
            rows = slab[xs[picked] - x0]  # (k, Y, Z)
            by_y[:, picked, :] = quantize(rows[:, :, zs], value_min, value_max).transpose(1, 0, 2)
            by_z[:, picked, :] = quantize(rows[:, ys, :], value_min, value_max).transpose(2, 0, 1)

    stack.flush()
    del stack
    del output
    return {
        "encoding": "uint8",
        "sampling": "nearest",
        "maxDimension": max_dim,
        "valueMin": value_min,
        "valueMax": value_max,
        "axes": axes,
    }
