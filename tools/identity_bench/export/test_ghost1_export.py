"""Offline tests for the GHOST 1 export (synthetic graphs and modules, no downloads)."""
import os
import tempfile
import unittest

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper

from tools.identity_bench.export import ghost1_export as gx

try:
    import torch
except Exception:                     # PyTorch is optional: CI runs without it
    torch = None


def tiny_graph(seed: int = 0) -> onnx.ModelProto:
    """Conv -> Relu -> Flatten -> Gemm, the shape of the ops a generator is made of."""
    rng = np.random.default_rng(seed)
    w = numpy_helper.from_array(rng.normal(0, 0.2, (8, 3, 3, 3)).astype(np.float32), "w")
    b = numpy_helper.from_array(rng.normal(0, 0.1, 8).astype(np.float32), "b")
    fc = numpy_helper.from_array(rng.normal(0, 0.05, (8 * 6 * 6, 10)).astype(np.float32), "fc")
    nodes = [helper.make_node("Conv", ["x", "w", "b"], ["c"], pads=[1, 1, 1, 1]),
             helper.make_node("Relu", ["c"], ["r"]),
             helper.make_node("Flatten", ["r"], ["f"]),
             helper.make_node("Gemm", ["f", "fc"], ["y"])]
    graph = helper.make_graph(nodes, "tiny", [helper.make_tensor_value_info("x", TensorProto.FLOAT, [1, 3, 6, 6])],
                              [helper.make_tensor_value_info("y", TensorProto.FLOAT, [1, 10])], [w, b, fc])
    model = helper.make_model(graph, opset_imports=[helper.make_opsetid("", gx.OPSET)])
    model.ir_version = 8
    onnx.checker.check_model(model)
    return model


class Variants(unittest.TestCase):
    def setUp(self):
        self.x = {"x": np.random.default_rng(1).uniform(-1, 1, (1, 3, 6, 6)).astype(np.float32)}
        self.model = tiny_graph()
        self.ref = gx.ort_run(self.model.SerializeToString(), self.x)

    def test_fp16_weights_halve_storage_and_keep_fp32_io(self):
        half = gx.fp16_weights(self.model)
        onnx.checker.check_model(half)
        out = gx.ort_run(half.SerializeToString(), self.x)
        self.assertEqual(out.dtype, np.float32)
        np.testing.assert_allclose(out, self.ref, atol=5e-3)
        self.assertLess(len(half.SerializeToString()), 0.6 * len(self.model.SerializeToString()))
        self.assertIn("Cast", gx.operators(half))

    def test_fp16_leaves_the_original_alone(self):
        before = self.model.SerializeToString()
        gx.fp16_weights(self.model)
        self.assertEqual(before, self.model.SerializeToString())

    def test_int8_weights_quarter_storage_and_stay_close(self):
        q = gx.int8_weights(self.model, min_size=16)
        onnx.checker.check_model(q)
        out = gx.ort_run(q.SerializeToString(), self.x)
        self.assertEqual(out.dtype, np.float32)
        self.assertLess(np.abs(out - self.ref).max(), 0.02 * np.abs(self.ref).max() + 1e-4)
        self.assertLess(len(q.SerializeToString()), 0.4 * len(self.model.SerializeToString()))
        self.assertIn("DequantizeLinear", gx.operators(q))
        self.assertEqual(gx.android_gaps(q), [])

    def test_int8_dynamic_runs_and_stays_close(self):
        with tempfile.TemporaryDirectory() as d:
            src, dst = os.path.join(d, "a.onnx"), os.path.join(d, "b.onnx")
            onnx.save(self.model, src)
            gx.int8_dynamic(src, dst)
            out = gx.ort_run(dst, self.x)
            self.assertLess(os.path.getsize(dst), os.path.getsize(src))
        self.assertLess(np.abs(out - self.ref).max(), 0.05 * np.abs(self.ref).max() + 1e-3)

    def test_operator_check(self):
        self.assertEqual(gx.operators(self.model), {"Conv": 1, "Flatten": 1, "Gemm": 1, "Relu": 1})
        self.assertEqual(gx.android_gaps(self.model), [])
        self.model.graph.node[1].domain = "com.microsoft"
        self.assertEqual(gx.android_gaps(self.model), ["com.microsoft:Relu"])


class Registry(unittest.TestCase):
    def test_pins(self):
        self.assertRegex(gx.GHOST_COMMIT, r"^[0-9a-f]{40}$")
        for blocks, spec in gx.GENERATORS.items():
            self.assertRegex(spec["sha256"], r"^[0-9a-f]{64}$")
            self.assertIn(f"{blocks}block", spec["file"])
        self.assertGreaterEqual(gx.OPSET, 17)

    def test_variant_paths_stay_in_the_cache(self):
        for variant in gx.VARIANTS:
            path = gx.variant_path("generator", variant)
            self.assertTrue(path.startswith(gx.export_dir()))
            self.assertTrue(path.endswith(f"ghost1_generator_{variant}.onnx"))


@unittest.skipIf(torch is None, "PyTorch not installed")
class TorchRoundTrip(unittest.TestCase):
    def test_tiny_iresnet_export_matches_pytorch_and_the_numpy_builder(self):
        from tools.identity_bench.export import iresnet_torch
        from tools.identity_bench.provenance import compare_backbone as cb
        from tools.identity_bench.provenance.test_compare_backbone import tiny_iresnet
        state = tiny_iresnet(7)
        net = iresnet_torch.tiny()
        missing = net.load_state_dict({k: torch.from_numpy(v) for k, v in state.items()}, strict=False)
        self.assertEqual([k for k in missing.missing_keys if not k.endswith("num_batches_tracked")], [])
        net.eval()
        x = {"input": np.random.default_rng(2).uniform(-1, 1, (1, 3, 112, 112)).astype(np.float32)}
        with tempfile.TemporaryDirectory() as d:
            path = gx.torch_export(net, x, os.path.join(d, "tiny.onnx"))
            out = gx.ort_run(path, x)
        ref = gx.torch_run(net, x)
        np.testing.assert_allclose(out, ref, atol=1e-3, rtol=1e-3)
        crop = np.rint((x["input"][0].transpose(1, 2, 0)[:, :, ::-1] + 1) * 127.5).clip(0, 255).astype(np.uint8)
        x2 = {"input": (crop[:, :, ::-1].astype(np.float32) / 127.5 - 1).transpose(2, 0, 1)[None]}
        numpy_built = cb.OnnxEmbedder(cb.iresnet_onnx(state).SerializeToString()).embed(crop[None])
        np.testing.assert_allclose(numpy_built, gx.torch_run(net, x2), atol=1e-3, rtol=1e-3)


if __name__ == "__main__":
    unittest.main()
