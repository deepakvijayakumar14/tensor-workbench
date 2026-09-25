from pathlib import Path

import numpy as np
import pytest
from numpy.lib.format import open_memmap

from tensor_worker.compute import COEFFICIENTS, compute, lookup_table
from tensor_worker.errors import InvalidInputError
from tensor_worker.files import rows_per_slab, sha256_file
from tensor_worker.generate import generate_dataset, generate_slab, plan_structure
from tensor_worker.preview import build_preview_stack, preview_layout, quantize, sample_indices


def write_npy(path: Path, array: np.ndarray) -> None:
    target = open_memmap(path, mode="w+", dtype=array.dtype, shape=array.shape)
    target[:] = array
    target.flush()
    del target


# ---- generation ----


def test_generation_is_identical_for_any_slab_size(tmp_path: Path) -> None:
    shape = (37, 29, 41)
    a, b = tmp_path / "a.npy", tmp_path / "b.npy"
    generate_dataset(shape, seed=7, out_path=a, rows_per_slab=1)
    generate_dataset(shape, seed=7, out_path=b, rows_per_slab=16)
    assert sha256_file(a) == sha256_file(b)


def test_generation_depends_on_seed_and_uses_valid_codes(tmp_path: Path) -> None:
    shape = (48, 48, 48)
    one = generate_slab(plan_structure(shape, 1), 0, 48)
    two = generate_slab(plan_structure(shape, 2), 0, 48)
    assert one.dtype == np.int32
    assert not np.array_equal(one, two)
    assert one.min() >= 0 and one.max() < COEFFICIENTS.size
    # The structure is not trivial: background, base plate and inclusions all appear.
    assert {0, 1, 2}.issubset(set(np.unique(one).tolist()))


# ---- computation ----


@pytest.mark.parametrize("rows", [1, 3, 64])
def test_compute_matches_reference_bit_for_bit(tmp_path: Path, rows: int) -> None:
    rng = np.random.default_rng(0)
    codes = rng.integers(0, 8, size=(10, 6, 5), dtype=np.int32)
    write_npy(tmp_path / "in.npy", codes)

    summary = compute(tmp_path / "in.npy", tmp_path / "out.npy", (10, 6, 5), gain=2.5, bias=-1.0, rows_per_slab=rows)

    out = np.load(tmp_path / "out.npy")
    expected = (np.float32(2.5) * COEFFICIENTS + np.float32(-1.0)).astype(np.float32)[codes]
    assert out.dtype == np.float32 and out.shape == codes.shape
    assert np.array_equal(out, expected)
    assert summary["min"] == pytest.approx(float(expected.min()))
    assert summary["max"] == pytest.approx(float(expected.max()))
    assert summary["mean"] == pytest.approx(float(expected.astype(np.float64).mean()))
    assert summary["std"] == pytest.approx(float(expected.astype(np.float64).std()))
    assert summary["elementCount"] == codes.size


def test_compute_rejects_out_of_range_codes(tmp_path: Path) -> None:
    codes = np.zeros((2, 2, 2), dtype=np.int32)
    codes[1, 1, 1] = 9
    write_npy(tmp_path / "in.npy", codes)
    with pytest.raises(InvalidInputError, match="0..7"):
        compute(tmp_path / "in.npy", tmp_path / "out.npy", (2, 2, 2), 1.0, 0.0, rows_per_slab=1)


def test_compute_rejects_wrong_dtype_and_shape(tmp_path: Path) -> None:
    write_npy(tmp_path / "f.npy", np.zeros((2, 2, 2), dtype=np.float32))
    with pytest.raises(InvalidInputError, match="int32"):
        compute(tmp_path / "f.npy", tmp_path / "out.npy", (2, 2, 2), 1.0, 0.0, rows_per_slab=1)
    write_npy(tmp_path / "i.npy", np.zeros((2, 2, 3), dtype=np.int32))
    with pytest.raises(InvalidInputError, match="shape"):
        compute(tmp_path / "i.npy", tmp_path / "out.npy", (2, 2, 2), 1.0, 0.0, rows_per_slab=1)


def test_lookup_table_rejects_non_finite_parameters() -> None:
    with pytest.raises(InvalidInputError):
        lookup_table(float("nan"), 0.0)


def test_rows_per_slab_bounds_memory() -> None:
    assert rows_per_slab(128 * 128, 4, 2 * 1024 * 1024) == 32
    assert rows_per_slab(4096 * 4096, 4, 1024) == 1  # never zero


# ---- previews ----


def test_sample_indices_cover_both_ends() -> None:
    assert sample_indices(5, 128).tolist() == [0, 1, 2, 3, 4]
    picked = sample_indices(300, 128)
    assert len(picked) == 128 and picked[0] == 0 and picked[-1] == 299
    assert len(set(picked.tolist())) == 128


@pytest.mark.parametrize("shape,max_dim", [((9, 7, 5), 128), ((40, 33, 27), 16)])
def test_preview_stack_matches_direct_slicing(tmp_path: Path, shape: tuple[int, int, int], max_dim: int) -> None:
    rng = np.random.default_rng(3)
    output = rng.normal(size=shape).astype(np.float32)
    write_npy(tmp_path / "out.npy", output)
    vmin, vmax = float(output.min()), float(output.max())

    meta = build_preview_stack(tmp_path / "out.npy", tmp_path / "p.u8", vmin, vmax, max_dim, rows_per_slab=4)

    raw = np.fromfile(tmp_path / "p.u8", dtype=np.uint8)
    xs, ys, zs = (sample_indices(n, max_dim) for n in shape)
    expected = {
        0: lambda i: output[i][np.ix_(ys, zs)],
        1: lambda i: output[:, i, :][np.ix_(xs, zs)],
        2: lambda i: output[:, :, i][np.ix_(xs, ys)],
    }
    assert raw.size == sum(a["sliceCount"] * a["height"] * a["width"] for a in meta["axes"])
    for a in meta["axes"]:
        for index in (0, a["sliceCount"] // 2, a["sliceCount"] - 1):
            start = a["offset"] + index * a["height"] * a["width"]
            got = raw[start : start + a["height"] * a["width"]].reshape(a["height"], a["width"])
            assert np.array_equal(got, quantize(expected[a["axis"]](index), vmin, vmax)), (a["axis"], index)
            assert max(a["height"], a["width"]) <= max_dim


def test_preview_layout_offsets_are_contiguous() -> None:
    axes = preview_layout((200, 100, 50), 64)
    assert [a["offset"] for a in axes] == [0, 200 * 64 * 50, 200 * 64 * 50 + 100 * 64 * 50]
    assert (axes[2]["height"], axes[2]["width"]) == (64, 64)


def test_quantize_handles_constant_output() -> None:
    assert quantize(np.ones((2, 2), dtype=np.float32), 1.0, 1.0).tolist() == [[0, 0], [0, 0]]
