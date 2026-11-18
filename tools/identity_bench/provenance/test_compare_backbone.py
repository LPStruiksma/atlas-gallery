"""Offline tests for compare_backbone (synthetic tensors, no downloads, no PyTorch)."""
import collections
import io
import json
import os
import pickle
import struct
import sys
import tempfile
import types
import unittest
import zipfile

import numpy as np

from tools.identity_bench.provenance import compare_backbone as cb


def _fake_torch():
    """Just enough of a `torch` module for pickle to name torch globals."""
    torch = types.ModuleType("torch")
    utils = types.ModuleType("torch._utils")

    class FloatStorage:
        pass

    def _rebuild_tensor_v2(*args):
        raise AssertionError("never called while writing")

    FloatStorage.__module__, FloatStorage.__qualname__ = "torch", "FloatStorage"
    _rebuild_tensor_v2.__module__, _rebuild_tensor_v2.__qualname__ = "torch._utils", "_rebuild_tensor_v2"
    torch.FloatStorage = FloatStorage
    utils._rebuild_tensor_v2 = _rebuild_tensor_v2
    torch._utils = utils
    return torch, utils


def write_pth(path: str, state: dict) -> None:
    """Write `state` in PyTorch's zip format, the way torch.save lays it out."""
    torch, utils = _fake_torch()
    saved = {k: sys.modules.get(k) for k in ("torch", "torch._utils")}
    sys.modules["torch"], sys.modules["torch._utils"] = torch, utils
    try:
        blobs = {}

        class Tensor:
            def __init__(self, key, array):
                self.key, self.array = key, array

            def __reduce__(self):
                a = self.array
                stride = tuple(s // a.itemsize for s in a.strides) if a.ndim else ()
                return utils._rebuild_tensor_v2, (("storage", self.key), 0, a.shape, stride, False,
                                                  collections.OrderedDict())

        class Writer(pickle.Pickler):
            def persistent_id(self, obj):
                if isinstance(obj, tuple) and len(obj) == 2 and obj[0] == "storage":
                    key = obj[1]
                    return ("storage", torch.FloatStorage, key, "cpu", blobs[key].size)
                return None

        wrapped = collections.OrderedDict()
        for i, (name, array) in enumerate(state.items()):
            array = np.ascontiguousarray(array, np.float32)
            blobs[str(i)] = array
            wrapped[name] = Tensor(str(i), array)
        buffer = io.BytesIO()
        Writer(buffer, protocol=2).dump(wrapped)
        with zipfile.ZipFile(path, "w") as z:
            z.writestr("archive/data.pkl", buffer.getvalue())
            for key, array in blobs.items():
                z.writestr(f"archive/data/{key}", array.tobytes())
    finally:
        for k, v in saved.items():
            if v is None:
                sys.modules.pop(k, None)
            else:
                sys.modules[k] = v


def tiny_iresnet(seed: int = 0) -> collections.OrderedDict:
    """An arcface_torch iresnet state dict with one block per stage and random weights."""
    rng = np.random.default_rng(seed)
    s = collections.OrderedDict()

    def bn(p, c):
        s[p + ".weight"] = rng.uniform(0.5, 1.5, c)
        s[p + ".bias"] = rng.normal(0, 0.1, c)
        s[p + ".running_mean"] = rng.normal(0, 0.1, c)
        s[p + ".running_var"] = rng.uniform(0.5, 1.5, c)

    s["conv1.weight"] = rng.normal(0, 0.1, (64, 3, 3, 3))
    bn("bn1", 64)
    s["prelu.weight"] = np.full(64, 0.25)
    inplanes = 64
    for layer, planes in enumerate((64, 128, 256, 512), start=1):
        p = f"layer{layer}.0"
        bn(p + ".bn1", inplanes)
        s[p + ".conv1.weight"] = rng.normal(0, 0.05, (planes, inplanes, 3, 3))
        bn(p + ".bn2", planes)
        s[p + ".prelu.weight"] = np.full(planes, 0.25)
        s[p + ".conv2.weight"] = rng.normal(0, 0.05, (planes, planes, 3, 3))
        bn(p + ".bn3", planes)
        s[p + ".downsample.0.weight"] = rng.normal(0, 0.05, (planes, inplanes, 1, 1))
        bn(p + ".downsample.1", planes)
        inplanes = planes
    bn("bn2", 512)
    s["fc.weight"] = rng.normal(0, 0.01, (512, 512 * 7 * 7))
    s["fc.bias"] = rng.normal(0, 0.01, 512)
    bn("features", 512)
    s["features.weight"] = np.ones(512)
    return collections.OrderedDict((k, np.asarray(v, np.float32)) for k, v in s.items())


class ReadPth(unittest.TestCase):
    def test_round_trip(self):
        state = collections.OrderedDict(a=np.arange(24, dtype=np.float32).reshape(2, 3, 4),
                                        b=np.linspace(-1, 1, 7, dtype=np.float32))
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "x.pth")
            write_pth(path, state)
            back = cb.read_pth(path)
        self.assertEqual(list(back), ["a", "b"])
        for k in state:
            np.testing.assert_array_equal(back[k], state[k])

    def test_transposed_values(self):
        base = np.arange(12, dtype=np.float32)
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "t.pth")
            write_pth(path, {"t": base.reshape(3, 4).T.copy()})
            rebuilt = cb.read_pth(path)["t"]
        np.testing.assert_array_equal(rebuilt, base.reshape(3, 4).T)

    def test_refuses_code(self):
        class Evil:
            def __reduce__(self):
                return os.system, ("echo pwned",)

        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "evil.pth")
            with zipfile.ZipFile(path, "w") as z:
                z.writestr("archive/data.pkl", pickle.dumps(Evil(), protocol=2))
            with self.assertRaises(pickle.UnpicklingError):
                cb.read_pth(path)


class ReadSafetensors(unittest.TestCase):
    def test_round_trip(self):
        a = np.arange(6, dtype=np.float32).reshape(2, 3)
        b = np.ones(4, dtype=np.float32)
        header = {"b": dict(dtype="F32", shape=[4], data_offsets=[24, 40]),
                  "a": dict(dtype="F32", shape=[2, 3], data_offsets=[0, 24]),
                  "__metadata__": {"format": "pt"}}
        raw = json.dumps(header).encode()
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "x.safetensors")
            with open(path, "wb") as f:
                f.write(struct.pack("<Q", len(raw)) + raw + a.tobytes() + b.tobytes())
            back = cb.read_safetensors(path)
        self.assertEqual(list(back), ["a", "b"])
        np.testing.assert_array_equal(back["a"], a)
        np.testing.assert_array_equal(back["b"], b)


class Tensors(unittest.TestCase):
    def test_identical_state(self):
        s = tiny_iresnet(1)
        r = cb.compare_tensors(s, s)
        self.assertEqual(r["exact_equal"], r["paired"])
        self.assertAlmostEqual(r["median_channel_cosine"], 1.0, places=6)

    def test_folded_batch_norm_still_matches(self):
        """Scaling each output channel (BN folding) keeps per-channel cosine at 1, not exact equality."""
        s = tiny_iresnet(2)
        folded = collections.OrderedDict()
        rng = np.random.default_rng(9)
        for k, v in s.items():
            if v.ndim == 4:
                v = v * rng.uniform(0.2, 3.0, (v.shape[0], 1, 1, 1)).astype(np.float32)
            folded[k] = v
        r = cb.compare_tensors(s, folded)
        self.assertEqual(r["exact_equal"], 1)      # only fc.weight is untouched
        self.assertGreater(r["median_channel_cosine"], 0.999)

    def test_unrelated_state(self):
        r = cb.compare_tensors(tiny_iresnet(3), tiny_iresnet(4))
        self.assertEqual(r["exact_equal"], 0)
        self.assertLess(r["median_channel_cosine"], 0.3)
        self.assertLess(r["best_match_channel_cosine"], 0.3)

    def test_zero_channels_are_skipped(self):
        a = np.ones((4, 2, 3, 3), np.float32)
        a[1] = 0
        self.assertAlmostEqual(cb.channel_cosine(a, a), 1.0)
        self.assertTrue(np.isnan(cb.channel_cosine(np.zeros_like(a), a)))

    def test_pair_by_shape_keeps_order(self):
        a = [("x", np.zeros((2, 2))), ("y", np.zeros((3, 3))), ("z", np.ones((2, 2)))]
        b = [("p", np.ones((3, 3))), ("q", np.zeros((2, 2))), ("r", np.ones((2, 2)))]
        names = [(x[0], y[0]) for x, y in cb.pair_by_shape(a, b)]
        self.assertEqual(names, [("x", "q"), ("y", "p"), ("z", "r")])


class Network(unittest.TestCase):
    def test_iresnet_onnx_runs_and_is_deterministic(self):
        model = cb.iresnet_onnx(tiny_iresnet(5))
        net = cb.OnnxEmbedder(model.SerializeToString())
        crops = np.random.default_rng(0).integers(0, 255, (2, 112, 112, 3), dtype=np.uint8)
        a, b = net.embed(crops), net.embed(crops)
        self.assertEqual(a.shape, (2, 512))
        self.assertTrue(np.isfinite(a).all())
        np.testing.assert_array_equal(a, b)

    def test_features_batch_norm(self):
        """The output equals fc(x) passed through the features batch norm."""
        s = tiny_iresnet(6)
        s["features.running_mean"][:] = 0
        s["features.running_var"][:] = 1 - 1e-5
        s["features.bias"][:] = 0.5
        net = cb.OnnxEmbedder(cb.iresnet_onnx(s).SerializeToString())
        crop = np.zeros((1, 112, 112, 3), np.uint8)
        e = net.embed(crop)
        s2 = collections.OrderedDict(s)
        s2["features.bias"] = np.zeros(512, np.float32)
        e2 = cb.OnnxEmbedder(cb.iresnet_onnx(s2).SerializeToString()).embed(crop)
        np.testing.assert_allclose(e - e2, 0.5, atol=1e-4)


class Embeddings(unittest.TestCase):
    def test_identical_and_structure(self):
        rng = np.random.default_rng(0)
        e = rng.normal(size=(10, 8))
        r = cb.compare_embeddings(e, e.copy())
        self.assertEqual(r["max_abs_difference"], 0.0)
        self.assertAlmostEqual(r["similarity_structure_r"], 1.0)
        rotated = e @ np.linalg.qr(rng.normal(size=(8, 8)))[0]
        r = cb.compare_embeddings(e, rotated)
        self.assertAlmostEqual(r["similarity_structure_r"], 1.0)       # same geometry, other space
        self.assertLess(abs(r["same_space_cosine"]), 0.9)

    def test_same_person_gap(self):
        e = np.array([[1, 0], [1, 0.1], [0, 1], [0.1, 1]], float)
        g = cb.same_person_gap(e, ["a", "a", "b", "b"])
        self.assertGreater(g["same"], 0.99)
        self.assertLess(g["different"], 0.2)


class TrainingLog(unittest.TestCase):
    def test_steps(self):
        text = ("Training: 2021-03-15 09:30:55,063-Total Step is: 124550\n"
                "Training: x-Speed 1 samples/sec   Loss 1.0   Epoch: 0   Global Step: 100   Fp16\n"
                "Training: x-Speed 1 samples/sec   Loss 0.8   Epoch: 24   Global Step: 124550   Fp16\n")
        self.assertEqual(cb.log_steps(text), dict(total_step=124550, last_step=124550))
        self.assertEqual(cb.log_steps("nothing"), dict(total_step=None, last_step=None))


class Registry(unittest.TestCase):
    def test_pins(self):
        for name, spec in cb.CHECKPOINTS.items():
            self.assertTrue(spec["url"].startswith("https://"), name)
            self.assertIn(spec["format"], ("pth", "pth-tensor", "onnx", "safetensors"), name)
            if spec["sha256"]:
                self.assertRegex(spec["sha256"], r"^[0-9a-f]{64}$", name)


if __name__ == "__main__":
    unittest.main()
