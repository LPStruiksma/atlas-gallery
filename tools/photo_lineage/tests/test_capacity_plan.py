"""Pure numerical Photo DNA disk-planning tests; no media, filesystem IO or writes."""
import unittest

from tools.photo_lineage.capacity_plan import plan_build
from tools.photo_lineage.external_index import _maximum_scratch_reservation


class CapacityPlanTests(unittest.TestCase):
    def test_full_150k_384_descriptor_envelope_is_explicit_and_unknown_without_free_space(self):
        report = plan_build()
        self.assertEqual(report["posting_upper_count"], 57_600_000)
        self.assertEqual(report["packed_snapshot_upper_bytes"], 460_800_000)
        self.assertGreater(report["scratch_advisory_upper_bytes"], 3 * 460_800_000)
        self.assertGreater(report["free_bytes_needed_before_build"], 1_400_000_000)
        self.assertEqual(report["conservative_admission"], "unknown_free_space_do_not_start")
        self.assertIsNone(report["reported_free_bytes"])
        self.assertIn("NEVER permission", report["assumptions"])

    def test_insufficient_same_volume_space_is_explicit_no_go(self):
        demand = plan_build()["free_bytes_needed_before_build"]
        report = plan_build(available_free_bytes=demand - 1)
        self.assertEqual(report["conservative_admission"], "no_go_insufficient_reported_space")
        self.assertEqual(report["reported_shortfall_bytes"], 1)
        self.assertEqual(report["reported_surplus_bytes"], 0)

    def test_exact_headroom_only_advisory_not_device_go(self):
        demand = plan_build()["free_bytes_needed_before_build"]
        report = plan_build(available_free_bytes=demand + 12345)
        self.assertEqual(report["conservative_admission"],
                         "advisory_headroom_only_not_deployment_go")
        self.assertEqual(report["reported_surplus_bytes"], 12345)
        self.assertEqual(report["reported_shortfall_bytes"], 0)

    def test_monotone_disk_growth_and_exact_builder_contract(self):
        small = plan_build(photos=500, max_words_per_photo=64)
        large = plan_build(photos=5000, max_words_per_photo=384)
        self.assertLess(small["free_bytes_needed_before_build"], large["free_bytes_needed_before_build"])
        self.assertEqual(
            large["scratch_advisory_upper_bytes"],
            _maximum_scratch_reservation(5000 * 384, 8192),
        )
        self.assertEqual(large["initial_runs_upper_count"], (5000 * 384 + 8191) // 8192)

    def test_no_false_approval_when_free_space_zero_or_not_supplied(self):
        self.assertEqual(plan_build(available_free_bytes=0)["conservative_admission"],
                         "no_go_insufficient_reported_space")
        self.assertEqual(plan_build()["conservative_admission"],
                         "unknown_free_space_do_not_start")

    def test_builder_100m_posting_ceiling_enforced_before_disk_arithmetic(self):
        with self.assertRaisesRegex(ValueError, "100 million"):
            plan_build(photos=1_000_000, max_words_per_photo=384)
        self.assertEqual(plan_build(photos=100_000, max_words_per_photo=1000)["posting_upper_count"],
                         100_000_000)

    def test_reject_bool_float_negative_and_invalid_bounds(self):
        for params in (
            {"photos": True}, {"photos": 0}, {"photos": 1.5},
            {"max_words_per_photo": False}, {"max_words_per_photo": 0},
            {"max_words_per_photo": 4097},
            {"chunk_records": 511}, {"chunk_records": True},
            {"reserve_free_bytes": -1}, {"reserve_free_bytes": False},
            {"available_free_bytes": -1}, {"available_free_bytes": True},
            {"available_free_bytes": 1.25}
        ):
            with self.subTest(params=params), self.assertRaises(ValueError):
                plan_build(**params)


if __name__ == "__main__":
    unittest.main()
