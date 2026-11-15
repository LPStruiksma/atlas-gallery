"""Synthetic contract tests for scripts/usage_rank.py. No personal usage data."""
import importlib.util
import json
import subprocess
import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("usage_rank", HERE / "usage_rank.py")
rank = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rank)
FIXTURE = HERE / "testdata" / "usage_synthetic.json"


class UsageRankingTest(unittest.TestCase):
    def setUp(self):
        self.days = rank.parse_export(json.loads(FIXTURE.read_text()))

    def test_frequency_and_timing_rank(self):
        rows = {r[0]: r for r in rank.ranking(self.days, "perf")}
        self.assertEqual(rows["A1"][3], 4)  # 6 actions / active day
        self.assertEqual(rows["A1"][4:7], (5, True, 20))
        self.assertEqual(rows["A3"][4:7], (1, True, 1))
        self.assertIsNone(rows["A2"][3])
        self.assertEqual(rows["A2"][4:6], (5, False))
        self.assertIsNone(rows["A2"][6])

    def test_timing_only_days_do_not_change_measured_leverage(self):
        # A1's active observations remain stable despite stray timing-only rows.
        baseline = [{"day": "2026-11-13", "actions": {"A1": 5},
                     "performance": {"A1": {"p50_ms": 1000}}}]
        timing_only = [
            {"day": "2026-11-14",
             "performance": {"A1": {"p50_ms": 10}}},
            {"day": "2026-11-15", "actions": {"A3": 1},
             "performance": {"A1": {"p50_ms": 10}}},
        ]
        original = {row[0]: row for row in rank.ranking(baseline, "perf")}["A1"]
        augmented = {row[0]: row for row in rank.ranking(baseline + timing_only, "perf")}["A1"]
        self.assertEqual(original[4:6], (5, True))
        self.assertEqual(augmented[4:6], original[4:6])
        self.assertEqual(augmented[3], 3)  # 5 A1 actions / 2 active days
        self.assertEqual(augmented[6], 15)

    def test_jank_is_measured_when_action_is_observed(self):
        observed = [{"day": "2026-11-13", "actions": {"A2": 5},
                     "performance": {"A2": {"jank_percent": 2.5}}}]
        row = {r[0]: r for r in rank.ranking(observed, "perf")}["A2"]
        self.assertEqual(row[3:7], (4, 5, True, 20))

    def test_design_dwell_and_estimated_leverage(self):
        rows = {r[0]: r for r in rank.ranking(self.days, "design")}
        self.assertEqual(rows["D1"][3], 4)  # 120 seconds/day
        self.assertEqual(rows["D1"][4:7], (4, False, 16))
        self.assertIsNone(rows["D2"][3])
        self.assertIn("estimated", rank.markdown(self.days, "design"))

    def test_missing_records_not_zero(self):
        rows = {r[0]: r for r in rank.ranking([], "perf")}
        self.assertTrue(all(row[3] is None and row[6] is None for row in rows))

    def test_reject_bad_schema_and_private_free_text(self):
        invalid = [
            {"schema_version": 2, "days": []},
            {"schema_version": True, "days": []},
            {"schema_version": 1, "days": [{"day": "2026-11-15", "actions": {"PRIVATE": 1}}]},
            {"schema_version": 1, "days": [{"day": "2026-11-15", "actions": {"A1": True}}]},
            {"schema_version": 1, "days": [{"day": "2026-11-15", "screens_seconds": {"D1": "album"}}]},
            {"schema_version": 1, "days": [{"day": "2026-11-15", "performance": {"A1": {"p50_ms": -1}}}]},
            {"schema_version": 1, "days": [{"day": "2026-11-15", "performance": {"A1": {"jank_percent": 101}}}]},
            {"schema_version": 1, "days": [{"day": "2026-02-30"}]},
            {"schema_version": 1, "days": [{"day": "2026-11-15"}, {"day": "2026-11-15"}]},
            {"schema_version": 1, "days": [{"day": "2026-11-15", "unexpected": "secret"}]},
        ]
        for document in invalid:
            with self.subTest(document=document):
                with self.assertRaises(ValueError):
                    rank.parse_export(document)

    def test_cli_outputs_aggregates_for_both_modes(self):
        for inventory in ("perf", "design"):
            result = subprocess.run(
                [sys.executable, str(HERE / "usage_rank.py"), str(FIXTURE),
                 "--inventory", inventory], capture_output=True, text=True, check=True)
            self.assertIn("| ID | Action / aspect |", result.stdout)
            self.assertNotIn("2026-11-15", result.stdout)
            self.assertNotIn(str(FIXTURE), result.stdout)

    def test_cli_errors_never_echo_untrusted_file(self):
        result = subprocess.run([sys.executable, str(HERE / "usage_rank.py"),
                                 str(FIXTURE) + "-nonexistent"], capture_output=True, text=True)
        self.assertEqual(result.returncode, 2)
        self.assertNotIn("nonexistent", result.stderr)


if __name__ == "__main__":
    unittest.main()
