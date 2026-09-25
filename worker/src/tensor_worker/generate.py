"""Reproducible synthetic input tensors ("layered-inclusions-1").

The input is a 3D int32 array of material codes 0-7 that looks vaguely like a
layered part: a base plate, a thin film, a cap layer, vertical vias and a few
spherical inclusions. It is synthetic; it only gives heatmaps visible structure.

Axis convention: shape = (X, Y, Z); layers stack along Z.

Determinism: every random choice is drawn up front from numpy's PCG64 generator
seeded with `seed`. Each voxel then depends only on its own coordinates, so the
result is identical for any slab size.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Callable

import numpy as np
from numpy.lib.format import open_memmap

GENERATOR_VERSION = "layered-inclusions-1"
MATERIAL_CODES = 8


@dataclass(frozen=True)
class Sphere:
    center: tuple[float, float, float]
    radius: float
    code: int


@dataclass(frozen=True)
class Via:
    x: float
    y: float
    radius: float


@dataclass(frozen=True)
class Structure:
    shape: tuple[int, int, int]
    base_top: int
    film: tuple[int, int]
    cap: tuple[int, int]
    film_footprint: tuple[int, int, int, int]
    cap_footprint: tuple[int, int, int, int]
    vias: tuple[Via, ...]
    spheres: tuple[Sphere, ...]


def plan_structure(shape: tuple[int, int, int], seed: int) -> Structure:
    rng = np.random.default_rng(seed)
    x_n, y_n, z_n = shape
    smallest = min(shape)

    def footprint(margin: float) -> tuple[int, int, int, int]:
        return (int(x_n * margin), int(np.ceil(x_n * (1 - margin))), int(y_n * margin), int(np.ceil(y_n * (1 - margin))))

    film_bottom = int(z_n * rng.uniform(0.38, 0.46))
    film_thickness = max(1, int(z_n * rng.uniform(0.03, 0.08)))
    cap_bottom = int(z_n * rng.uniform(0.74, 0.80))
    vias = tuple(
        Via(float(rng.uniform(0.2, 0.8) * x_n), float(rng.uniform(0.2, 0.8) * y_n), float(max(1.0, 0.035 * smallest)))
        for _ in range(int(rng.integers(2, 5)))
    )
    spheres = tuple(
        Sphere(
            center=tuple(float(rng.uniform(0.15, 0.85) * n) for n in shape),  # type: ignore[arg-type]
            radius=float(rng.uniform(0.06, 0.16) * smallest),
            code=int(rng.choice([3, 4, 7])),
        )
        for _ in range(int(rng.integers(6, 11)))
    )
    return Structure(
        shape=shape,
        base_top=max(1, int(z_n * 0.18)),
        film=(film_bottom, min(z_n, film_bottom + film_thickness)),
        cap=(cap_bottom, min(z_n, cap_bottom + max(1, int(z_n * 0.06)))),
        film_footprint=footprint(0.10),
        cap_footprint=footprint(0.22),
        vias=vias,
        spheres=spheres,
    )


def generate_slab(structure: Structure, x0: int, x1: int) -> np.ndarray:
    """Material codes for planes x0..x1-1, shape (x1-x0, Y, Z), dtype int32."""
    _, y_n, z_n = structure.shape
    out = np.zeros((x1 - x0, y_n, z_n), dtype=np.int32)
    xs = np.arange(x0, x1)[:, None]
    ys = np.arange(y_n)[None, :]

    out[:, :, : structure.base_top] = 1

    def layer(footprint: tuple[int, int, int, int], z_range: tuple[int, int], code: int) -> None:
        fx0, fx1, fy0, fy1 = footprint
        inside = (xs >= fx0) & (xs < fx1) & (ys >= fy0) & (ys < fy1)  # (n, Y)
        z0, z1 = z_range
        out[:, :, z0:z1][inside] = code

    layer(structure.film_footprint, structure.film, 2)
    layer(structure.cap_footprint, structure.cap, 5)

    # Vias: vertical cylinders from the base plate up to the cap layer.
    for via in structure.vias:
        column = (xs + 0.5 - via.x) ** 2 + (ys + 0.5 - via.y) ** 2 <= via.radius**2  # (n, Y)
        out[:, :, structure.base_top : structure.cap[0]][column] = 6

    # Spheres last, restricted to their bounding box to keep work proportional to size.
    for sphere in structure.spheres:
        cx, cy, cz = sphere.center
        r = sphere.radius
        bx0, bx1 = max(x0, int(np.floor(cx - r))), min(x1, int(np.ceil(cx + r)) + 1)
        if bx0 >= bx1:
            continue
        by0, by1 = max(0, int(np.floor(cy - r))), min(y_n, int(np.ceil(cy + r)) + 1)
        bz0, bz1 = max(0, int(np.floor(cz - r))), min(z_n, int(np.ceil(cz + r)) + 1)
        gx = np.arange(bx0, bx1)[:, None, None] + 0.5 - cx
        gy = np.arange(by0, by1)[None, :, None] + 0.5 - cy
        gz = np.arange(bz0, bz1)[None, None, :] + 0.5 - cz
        inside = gx**2 + gy**2 + gz**2 <= r * r
        block = out[bx0 - x0 : bx1 - x0, by0:by1, bz0:bz1]
        block[inside] = sphere.code

    return out


def generate_dataset(
    shape: tuple[int, int, int],
    seed: int,
    out_path: Path,
    rows_per_slab: int,
    on_slab: Callable[[float], None] = lambda fraction: None,
) -> None:
    """Writes the tensor as an uncompressed .npy file, one slab at a time."""
    structure = plan_structure(shape, seed)
    x_n = shape[0]
    target = open_memmap(out_path, mode="w+", dtype="<i4", shape=shape)
    try:
        for x0 in range(0, x_n, rows_per_slab):
            x1 = min(x_n, x0 + rows_per_slab)
            target[x0:x1] = generate_slab(structure, x0, x1)
            on_slab(x1 / x_n)
        target.flush()
    finally:
        del target
