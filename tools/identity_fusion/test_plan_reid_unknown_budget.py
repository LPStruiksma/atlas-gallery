#!/usr/bin/env python3
"""Fictional arithmetic-only regressions: never touch source people/photos."""
import contextlib
import io
import json
import unittest

from plan_reid_unknown_budget import (
    ERROR_CEILING, MAX_GROUPS, main, minimum_groups, plan, wilson_upper,
)


class OpenSetSizingTests(unittest.TestCase):
    def test_zero_error_minimum_one_check(self):
        self.assertEqual(minimum_groups(checks=1, allowed_errors=0), 52)
        self.assertGreater(wilson_upper(0, 51), ERROR_CEILING)
        self.assertLessEqual(wilson_upper(0, 52), ERROR_CEILING)

    def test_six_joint_conditions_and_one_overall_are_seven_checks(self):
        self.assertEqual(minimum_groups(checks=6, allowed_errors=0), 109)
        self.assertEqual(minimum_groups(checks=7, allowed_errors=0), 115)
        self.assertGreater(wilson_upper(0, 114, checks=7), ERROR_CEILING)
        self.assertLessEqual(wilson_upper(0, 115, checks=7), ERROR_CEILING)

    def test_allowing_one_error_needs_more_groups(self):
        for checks in (1, 2, 7, 12):
            sizes = [minimum_groups(checks=checks, allowed_errors=i)
                     for i in range(6)]
            self.assertEqual(sizes, sorted(sizes))
            for errors, n in enumerate(sizes):
                self.assertLessEqual(wilson_upper(errors, n, checks=checks),
                                     ERROR_CEILING)
                self.assertGreater(wilson_upper(errors, n - 1, checks=checks),
                                   ERROR_CEILING)

    def test_more_simultaneous_conditions_never_looks_safer(self):
        for n, errors in ((1, 1), (100, 0), (115, 0), (250, 2)):
            bounds = [wilson_upper(errors, n, checks=k) for k in (1, 2, 6, 7, 12)]
            self.assertEqual(bounds, sorted(bounds))

    def test_correlated_photo_families_collapse_nominal_sample(self):
        independent = plan(checks=1, observations=100, components=100,
                           error_components=0)
        repeated = plan(checks=1, observations=100, components=1,
                        error_components=1)
        self.assertTrue(independent["illustrative_observed"]["meets_numeric_bound"])
        self.assertFalse(repeated["illustrative_observed"]["meets_numeric_bound"])
        self.assertEqual(repeated["illustrative_observed"]["one_sided_adjusted_wilson_upper"], 1.0)
        self.assertEqual(repeated["illustrative_observed"]["declared_independent_capture_components"], 1)
        self.assertFalse(repeated["source_group_independence_physically_verified"])

    def test_underpowered_zero_errors_and_no_data_not_safe(self):
        self.assertFalse(plan()["illustrative_observed"]["meets_numeric_bound"])
        self.assertIsNone(plan()["illustrative_observed"]["one_sided_adjusted_wilson_upper"])
        self.assertFalse(plan(observations=100, components=20)["illustrative_observed"]["meets_numeric_bound"])
        self.assertFalse(plan()["identity_auto_confirmation_allowed"])

    def test_monotonic_false_component_count(self):
        bounds = [wilson_upper(e, 120, checks=7) for e in range(5)]
        self.assertEqual(bounds, sorted(bounds))

    def test_no_person_identifiers_scores_or_paths_returned(self):
        body = json.dumps(plan(observations=100, components=1, error_components=1))
        for sensitive in ("person_uid", "probe_uid", "image_path", "filename",
                          "checkpoint_uri", "embedding", "candidate_uid"):
            self.assertNotIn(sensitive, body)
        for authority in ("identity_auto_confirmation_allowed", "production_approved",
                          "actual_model_or_person_accuracy_measured",
                          "peeking_or_multiple_model_selection_corrected"):
            self.assertFalse(plan()[authority])

    def test_no_hypothetical_group_count_can_exceed_observations(self):
        bad = [
            dict(observations=0, components=1),
            dict(observations=2, components=3),
            dict(observations=2, components=1, error_components=2),
            dict(observations=-1), dict(components=-1),
            dict(checks=0), dict(checks=13), dict(max_errors=6),
            dict(observations=MAX_GROUPS + 1),
            dict(observations=True), dict(checks=1.0),
            dict(max_errors=False), dict(error_components=1.5),
        ]
        for kwargs in bad:
            with self.subTest(kwargs=kwargs), self.assertRaises(ValueError):
                plan(**kwargs)

    def test_unusable_bound_inputs_rejected(self):
        for args in ((-1, 100), (101, 100), (0, -1), (0, MAX_GROUPS + 1),
                     (True, 100), (0, 100, True), (0, 100, 0), (0, 100, 13)):
            with self.subTest(args=args), self.assertRaises(ValueError):
                if len(args) == 2:
                    wilson_upper(*args)
                else:
                    wilson_upper(args[0], args[1], checks=args[2])
        self.assertIsNone(wilson_upper(0, 0))

    def test_main_json_does_not_read_files(self):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            self.assertEqual(main(["--checks", "7", "--observations", "100",
                                   "--components", "1", "--error-components", "1"]), 0)
        payload = json.loads(out.getvalue())
        self.assertEqual(payload["predeclared_simultaneous_checks"], 7)
        self.assertFalse(payload["illustrative_observed"]["meets_numeric_bound"])

    def test_cli_rejects_bad_count_tokens_and_conflicts(self):
        for args in (["--observations", "-2"], ["--observations", "1.0"],
                     ["--observations", "1e8"], ["--checks", "13"],
                     ["--observations", "1", "--components", "2"]):
            with self.subTest(args=args):
                with contextlib.redirect_stderr(io.StringIO()):
                    with self.assertRaises(SystemExit) as error:
                        main(args)
                self.assertEqual(error.exception.code, 2)


if __name__ == "__main__":
    unittest.main()
