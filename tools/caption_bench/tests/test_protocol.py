import contextlib
import io
import json
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parents[1]))
import protocol


class TestProtocol(unittest.TestCase):
    def test_fixture(self):
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory) / "f"
            protocol.fixtures(out)
            self.assertEqual(2, len(protocol.manifest(out / "manifest.jsonl")))

    def test_bad_hash(self):
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory) / "f"
            protocol.fixtures(out)
            manifest = out / "manifest.jsonl"
            rows = [json.loads(line) for line in manifest.read_text().splitlines()]
            rows[0]["sha256"] = "0" * 64
            manifest.write_text("\n".join(map(json.dumps, rows)) + "\n")
            with self.assertRaisesRegex(ValueError, "SHA-256 mismatch"):
                protocol.manifest(manifest)

    def make_adapter(self, directory):
        adapter = Path(directory) / "adapter.py"
        adapter.write_text(textwrap.dedent(r"""
            import json
            import os
            import sys
            from pathlib import Path

            counter = Path(sys.argv[1])
            mode = sys.argv[2]
            previous = counter.read_text().splitlines() if counter.exists() else []
            with counter.open("a") as sink:
                sink.write(str(os.getpid()) + "\n")
            if mode == "variable-memory":
                # The two separate child processes use different amounts of memory.
                ballast = bytearray((1 if len(previous) % 2 == 0 else 16) * 1024 * 1024)
                ballast[0] = 1
            if mode == "nonzero":
                print(json.dumps({"caption": "must not count"}))
                sys.exit(7)
            if mode == "invalid":
                print("not json")
            elif mode == "empty" or (mode == "alternate" and len(previous) % 2):
                print(json.dumps({"caption": ""}))
            else:
                print(json.dumps({"caption": f"caption from {os.getpid()}"}))
        """).lstrip(), encoding="utf-8")
        return adapter

    def run_bench(self, directory, mode="valid", discarded=0, runs=2):
        root = Path(directory)
        fixtures = root / "f"
        protocol.fixtures(fixtures)
        output = root / "output.jsonl"
        counter = root / "launches.log"
        adapter = self.make_adapter(root)
        args = [
            "bench",
            "--discard-launches", str(discarded),
            "--runs", str(runs),
            "--timeout", "5",
            "--prompt", "test",
            "--output", str(output),
            str(fixtures / "manifest.jsonl"), "--",
            sys.executable, str(adapter), str(counter), mode,
        ]
        stdout, stderr = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            code = protocol.main(args)
        summary = json.loads(stdout.getvalue().strip().splitlines()[-1])
        rows = [json.loads(line) for line in output.read_text().splitlines()]
        return code, summary, rows, counter.read_text().splitlines(), stderr.getvalue()

    def test_discarded_launches_are_not_warm_inference(self):
        with tempfile.TemporaryDirectory() as directory:
            code, summary, rows, pids, stderr = self.run_bench(directory, discarded=1)
            self.assertEqual(0, code, stderr)
            self.assertEqual(6, len(pids))  # two fixtures x (one discarded + two recorded)
            self.assertEqual(6, len(set(pids)))  # a new process for every inference
            self.assertEqual(4, summary["attempted"])
            self.assertEqual(2, summary["discarded_trial_launches"])
            self.assertEqual("per-request-cold-process", summary["launch_mode"])
            self.assertFalse(summary["warm_inference_measured"])
            self.assertEqual(4, summary["success"])
            self.assertEqual(0, summary["failed"])
            self.assertIsNotNone(summary["p50_cold_ms"])
            self.assertTrue(all(row["launch_mode"] == "per-request-cold-process" for row in rows))

    def test_consecutive_different_memory_runs_are_not_misreported(self):
        with tempfile.TemporaryDirectory() as directory:
            code, summary, rows, _, stderr = self.run_bench(directory, mode="variable-memory", runs=1)
            self.assertEqual(0, code, stderr)
            self.assertEqual(2, len(rows))
            self.assertEqual("NOT_MEASURED", summary["memory_status"])
            self.assertTrue(all(row["memory_status"] == "NOT_MEASURED" for row in rows))
            self.assertTrue(all("child_maxrss_delta_kib" not in row for row in rows))

    def test_mixed_failure_excludes_invalid_latencies(self):
        with tempfile.TemporaryDirectory() as directory:
            code, summary, rows, _, stderr = self.run_bench(directory, mode="alternate")
            self.assertEqual(0, code, stderr)
            self.assertEqual((4, 2, 2), (
                summary["attempted"], summary["success"], summary["failed"]
            ))
            self.assertEqual(2, sum(row["status"] == "success" for row in rows))
            self.assertTrue(all(row["caption"] is None for row in rows if row["status"] == "failed"))
            self.assertIsNotNone(summary["p50_cold_ms"])
            self.assertTrue(all(row["error"] for row in rows if row["status"] == "failed"))

    def test_all_failed_outputs_fail_closed(self):
        for mode in ("invalid", "empty", "nonzero"):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as directory:
                code, summary, rows, _, stderr = self.run_bench(directory, mode=mode, runs=1)
                self.assertEqual(2, code)
                self.assertIn("no valid captions", stderr)
                self.assertEqual((2, 0, 2), (
                    summary["attempted"], summary["success"], summary["failed"]
                ))
                self.assertIsNone(summary["p50_cold_ms"])
                self.assertIsNone(summary["p95_cold_ms"])
                self.assertTrue(all(row["status"] == "failed" for row in rows))

    def test_capacity_rejects_invalid_retries_and_nonfinite_values(self):
        base = [
            "capacity", "--images", "150000", "--images-per-hour", "1",
            "--retry-multiplier", "1", "--caption-bytes", "1",
            "--metadata-bytes", "1", "--model-bytes", "0",
        ]
        for field, value in (
            ("--retry-multiplier", "0"),
            ("--retry-multiplier", "-1"),
            ("--retry-multiplier", "nan"),
            ("--retry-multiplier", "inf"),
            ("--images-per-hour", "nan"),
            ("--images-per-hour", "inf"),
            ("--images-per-hour", "0"),
        ):
            with self.subTest(field=field, value=value):
                candidate = base.copy()
                candidate[candidate.index(field) + 1] = value
                with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                    self.assertEqual(2, protocol.main(candidate))
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            self.assertEqual(0, protocol.main(base))
        self.assertEqual(150000.0, json.loads(output.getvalue())["initial_hours"])

    def test_benchmark_rejects_nonfinite_timeout(self):
        with tempfile.TemporaryDirectory() as directory:
            fixtures = Path(directory) / "f"
            protocol.fixtures(fixtures)
            args = [
                "bench", "--timeout", "nan", "--prompt", "x",
                "--output", str(Path(directory) / "o.jsonl"),
                str(fixtures / "manifest.jsonl"), "--",
                sys.executable, "unused.py",
            ]
            with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(2, protocol.main(args))


if __name__ == "__main__":
    unittest.main()
