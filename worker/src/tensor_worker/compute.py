"""The synthetic computation: "affine-material-map" version 1.0.0.

    output[x, y, z] = gain * COEFFICIENTS[input[x, y, z]] + bias

Input:  (X, Y, Z) int32 material codes in 0..7 (a generated dataset).
Output: (X, Y, Z) float32, same shape.

COEFFICIENTS is a fixed, made-up table. This is an array-processing example, not
a thermal solver. The map is pointwise, so it can run one slab at a time: memory
per slot is bounded by the slab size, not by the tensor size.

Because gain and bias are applied to the 8-entry table in float32 first, every
output value is exactly lut[code]. A downloaded result can therefore be checked
bit-for-bit against the input and parameters.
"""

from __future__ import annotations

import math
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

import numpy as np
from numpy.lib.format import open_memmap

from .errors import InvalidInputError

FUNCTION_NAME = "affine-material-map"
IMPLEMENTATION_VERSION = "1.0.0"

COEFFICIENTS = np.array([0.0, 1.0, 0.35, 2.2, 1.6, 0.8, 3.1, 0.55], dtype=np.float32)


def lookup_table(gain: float, bias: float) -> np.ndarray:
    if not (math.isfinite(gain) and math.isfinite(bias)):
        raise InvalidInputError("gain and bias must be finite")
    return (np.float32(gain) * COEFFICIENTS + np.float32(bias)).astype(np.float32)


def apply_slab(codes: np.ndarray, lut: np.ndarray) -> np.ndarray:
    lo, hi = int(codes.min()), int(codes.max())
    if lo < 0 or hi >= lut.size:
        raise InvalidInputError(f"Material codes must be in 0..{lut.size - 1}; found {lo}..{hi}")
    return lut[codes]


@dataclass
class RunningStats:
    """Streaming min/max/mean/std. Slabs are merged with Chan et al.'s parallel update."""

    count: int = 0
    mean: float = 0.0
    m2: float = 0.0
    minimum: float = math.inf
    maximum: float = -math.inf

    def add(self, values: np.ndarray) -> None:
        n = int(values.size)
        if n == 0:
            return
        as64 = values.astype(np.float64, copy=False)
        slab_mean = float(as64.mean())
        slab_m2 = float(((as64 - slab_mean) ** 2).sum())
        delta = slab_mean - self.mean
        total = self.count + n
        self.mean += delta * n / total
        self.m2 += slab_m2 + delta * delta * self.count * n / total
        self.count = total
        self.minimum = min(self.minimum, float(values.min()))
        self.maximum = max(self.maximum, float(values.max()))

    def as_dict(self) -> dict[str, float | int]:
        return {
            "min": self.minimum,
            "max": self.maximum,
            "mean": self.mean,
            "std": math.sqrt(self.m2 / self.count) if self.count else 0.0,
            "elementCount": self.count,
        }


def open_input(path: Path, expected_shape: tuple[int, ...]) -> np.ndarray:
    """Memory-maps an input .npy after checking dtype and shape."""
    try:
        array = np.load(path, mmap_mode="r", allow_pickle=False)
    except ValueError as exc:
        raise InvalidInputError(f"Input is not a valid .npy file: {exc}") from exc
    if array.dtype != np.dtype("<i4"):
        raise InvalidInputError(f"Input dtype must be little-endian int32, got {array.dtype}")
    if array.ndim != 3 or tuple(array.shape) != tuple(expected_shape):
        raise InvalidInputError(f"Input shape {array.shape} does not match expected {tuple(expected_shape)}")
    return array


def compute(
    input_path: Path,
    output_path: Path,
    expected_shape: tuple[int, int, int],
    gain: float,
    bias: float,
    rows_per_slab: int,
    on_slab: Callable[[float], None] = lambda fraction: None,
) -> dict[str, float | int]:
    """Streams input slabs through the map into a memory-mapped output .npy."""
    source = open_input(input_path, expected_shape)
    lut = lookup_table(gain, bias)
    target = open_memmap(output_path, mode="w+", dtype="<f4", shape=source.shape)
    stats = RunningStats()
    x_n = source.shape[0]
    try:
        for x0 in range(0, x_n, rows_per_slab):
            x1 = min(x_n, x0 + rows_per_slab)
            result = apply_slab(np.asarray(source[x0:x1]), lut)
            target[x0:x1] = result
            stats.add(result)
            on_slab(x1 / x_n)
        target.flush()
    finally:
        del target
        del source
    return stats.as_dict()
