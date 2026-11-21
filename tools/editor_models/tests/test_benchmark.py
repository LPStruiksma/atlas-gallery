import importlib.util
import tempfile
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).parents[1] / "benchmark.py"
SPEC = importlib.util.spec_from_file_location("editor_models_benchmark", MODULE_PATH)
benchmark = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(benchmark)


class BenchmarkContractTest(unittest.TestCase):
    def test_checked_in_catalogue_is_valid(self):
        self.assertEqual([], benchmark.validate(benchmark.load_catalogue()))

    def test_every_capability_has_two_or_three_candidates(self):
        rows = benchmark.load_catalogue()["candidates"]
        for capability in benchmark.CAPABILITIES:
            self.assertIn(sum(row["capability"] == capability for row in rows), (2, 3))

    def test_catalogue_contains_no_claimed_hash_or_measurement(self):
        for row in benchmark.load_catalogue()["candidates"]:
            self.assertIsNone(row["artifact_sha256"])
            self.assertEqual("not measured", row["status"])

    def test_tile_plan_covers_edges_and_is_deterministic(self):
        plan = benchmark.tile_plan(4000, 3000, 512, 32)
        self.assertEqual(plan, benchmark.tile_plan(4000, 3000, 512, 32))
        self.assertIn((0, 0, 512, 512), plan)
        self.assertEqual(4000, max(tile[2] for tile in plan))
        self.assertEqual(3000, max(tile[3] for tile in plan))

    def test_hash_reports_bytes_actually_read(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "artifact.onnx"
            path.write_bytes(b"model bytes")
            self.assertEqual((11, "9cb7487000bc86ac36ce83c4acfabe8878552be99572a6770f65ab1d048a5c48"), benchmark.sha256_file(path))


if __name__ == "__main__":
    unittest.main()
