#!/usr/bin/env python3
"""All benchmark identities and similarities are fictional; no biometrics."""
import csv
import json
import math
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import benchmark_reid_scores as reid_module

from benchmark_reid_scores import (
    FIELDS, _upper_wilson, benchmark_model_scores, main
)


def fixture():
    rows = []

    def push(probe, split, truth, session, condition, candidate, score):
        rows.append(dict(probe_uid=probe, split=split, truth_uid=truth,
                         session_uid=session, condition=condition,
                         candidate_uid=candidate,
                         similarity="" if score is None else str(score)))
    # 100 independent known + 100 explicit Unknown calibration trials.
    for i in range(100):
        truth = f"CalKnown_{i}"
        push(f"CalProbeK_{i}", "calibration", truth, f"CalSessionK_{i}",
             "front", truth, .91)
        push(f"CalProbeK_{i}", "calibration", truth, f"CalSessionK_{i}",
             "front", f"CalImpostor_{i}", .33)
        push(f"CalProbeU_{i}", "calibration", "UNKNOWN", f"CalSessionU_{i}",
             "lookalike", f"CalUnknownImpostor_{i}", .93 if i == 0 else .12)
    # Holdout persons/sessions/gallery candidates have NO overlap with
    # calibration; some deliberately hard false offers survive the locked
    # threshold. They must never influence the chosen similarity floor.
    for i in range(25):
        truth = f"HoldKnown_{i}"
        push(f"HoldProbeK_{i}", "holdout", truth, f"HoldSessionK_{i}",
             "back", truth, .72 if i < 5 else .92)
        push(f"HoldProbeK_{i}", "holdout", truth, f"HoldSessionK_{i}",
             "back", f"HoldImpostor_{i}", .97 if i < 5 else .21)
        push(f"HoldProbeU_{i}", "holdout", "UNKNOWN", f"HoldSessionU_{i}",
             "lookalike", f"HoldUnknownImpostor_{i}", .96 if i < 4 else .1)
    return rows


class HeldoutModelScoreBenchmarkTests(unittest.TestCase):
    def test_split_locked_metrics_reveal_hard_negatives(self):
        rows = fixture()
        result = benchmark_model_scores(rows)
        self.assertEqual(result["status"], "local_research_only")
        self.assertFalse(result["auto_confirm_allowed"])
        self.assertTrue(result["unknown_always_allowed"])
        self.assertIsNone(result["calibrated_identity_probability"])
        self.assertEqual(result["threshold_selected_from"], "calibration_only")
        self.assertEqual(result["similarity_floor"], .91)
        cal = result["calibration"]["overall"]
        hold = result["holdout"]["overall"]
        self.assertEqual(cal["correct_known_offers"], 100)
        self.assertEqual(cal["unknown_false_offers"], 1)
        self.assertEqual(cal["wrong_known_offers"], 0)
        self.assertLessEqual(cal["unknown_false_offer_wilson95_upper"], .05)
        self.assertLessEqual(cal["wrong_known_offer_wilson95_upper"], .05)
        self.assertEqual(hold["observations"], 50)
        self.assertEqual(hold["correct_known_offers"], 20)
        self.assertEqual(hold["wrong_known_offers"], 5)
        self.assertEqual(hold["unknown_false_offers"], 4)
        self.assertEqual(hold["correct_unknown_abstentions"], 21)
        self.assertEqual(hold["missed_known_abstentions"], 0)
        self.assertEqual(hold["unknown_false_offer_fraction"], .16)
        self.assertEqual(hold["wrong_known_offer_fraction"], .2)
        self.assertGreater(hold["unknown_false_offer_wilson95_upper"], .05)
        self.assertEqual(set(result["holdout"]["by_condition"]),
                         {"back", "lookalike"})
        serialized = json.dumps(result)
        for private in ("CalKnown_", "HoldKnown_", "CalSession",
                        "HoldSession", "CalProbe", "HoldProbe",
                        "/storage/", "candidate_uid", "probe_uid"):
            self.assertNotIn(private, serialized)
        self.assertEqual(result, benchmark_model_scores(list(reversed(rows))))

    def test_no_holdout_contamination_of_selected_calibration_floor(self):
        baseline = benchmark_model_scores(fixture())
        changed = fixture()
        for row in changed:
            if row["split"] == "holdout" and row["candidate_uid"] != "NONE":
                row["similarity"] = "0.98" if row["candidate_uid"].startswith("HoldImpostor") else "0.01"
        report = benchmark_model_scores(changed)
        self.assertEqual(report["similarity_floor"], baseline["similarity_floor"])
        self.assertNotEqual(report["holdout"]["overall"],
                            baseline["holdout"]["overall"])

    def test_fail_closed_on_leakage_and_missing_reviewed_truth(self):
        rows = fixture()
        invalids = []
        missing = [dict(x) for x in rows]
        missing[0]["truth_uid"] = ""
        invalids.append(missing)
        reused_session = [dict(x) for x in rows]
        for x in reused_session:
            if x["probe_uid"] == "HoldProbeK_0":
                x["session_uid"] = "CalSessionK_0"
        invalids.append(reused_session)
        reused_identity = [dict(x) for x in rows]
        for x in reused_identity:
            if x["probe_uid"] == "HoldProbeK_0":
                x["truth_uid"] = "CalKnown_0"
        invalids.append(reused_identity)
        reused_candidate = [dict(x) for x in rows]
        reused_candidate[0]["candidate_uid"] = "HoldImpostor_0"
        invalids.append(reused_candidate)
        duplicates = [dict(x) for x in rows] + [dict(rows[0])]
        invalids.append(duplicates)
        missing_split = [dict(x) for x in rows if x["split"] != "holdout"]
        invalids.append(missing_split)
        malformed_score = [dict(x) for x in rows]
        malformed_score[0]["similarity"] = "nan"
        invalids.append(malformed_score)
        malformed_header = [dict(x) for x in rows]
        malformed_header[0]["local_photo_path"] = "/private/photo"
        invalids.append(malformed_header)
        hidden_path = [dict(x) for x in rows]
        hidden_path[0]["probe_uid"] = "/storage/emulated/0/private.jpg"
        invalids.append(hidden_path)
        for bad in invalids:
            with self.subTest(case=invalids.index(bad)):
                with self.assertRaises(ValueError):
                    benchmark_model_scores(bad)
        for rate in [0, 1, -1, math.nan, True, "0.05"]:
            with self.assertRaises(ValueError):
                benchmark_model_scores(rows, max_false_offer_rate=rate)

    def test_tied_scores_do_not_choose_arbitrary_identity_and_no_candidate_is_valid(self):
        rows = fixture()
        # Tie for top score must abstain, not settle by alphabetical ID.
        for row in rows:
            if row["probe_uid"] == "HoldProbeK_7" and row["candidate_uid"].startswith("HoldImpostor"):
                row["similarity"] = ".92"
            if row["probe_uid"] == "HoldProbeU_8":
                row["candidate_uid"] = "NONE"
                row["similarity"] = ""
        hold = benchmark_model_scores(rows)["holdout"]["overall"]
        self.assertEqual(hold["correct_known_offers"], 19)
        self.assertEqual(hold["missed_known_abstentions"], 1)
        self.assertEqual(hold["correct_unknown_abstentions"], 21)

    def test_small_strata_suppressed_and_denominator_is_not_fake_zero(self):
        rows = fixture()
        for row in rows:
            if row["probe_uid"] == "HoldProbeU_0":
                row["condition"] = "occluded"
        holdout = benchmark_model_scores(rows)["holdout"]
        self.assertEqual(holdout["suppressed_condition_count"], 1)
        self.assertNotIn("occluded", holdout["by_condition"])
        self.assertEqual(_upper_wilson(0, 0), None)
        self.assertGreater(_upper_wilson(0, 10), .05)

    def test_no_viable_calibration_threshold_is_rejected(self):
        rows = fixture()
        for row in rows:
            if row["split"] == "calibration" and row["truth_uid"] == "UNKNOWN":
                row["similarity"] = "1.0"
        with self.assertRaisesRegex(ValueError, "nontrivial"):
            benchmark_model_scores(rows)

    def test_unrounded_wilson_bound_rejects_borderline_unsafe_floor(self):
        # An exact upper bound just over .05 rounds to .0500 at four
        # decimals. It MUST be rejected, not turned into a false pass.
        rows = [dict(r) for r in fixture() if r["split"] == "holdout"]
        for i in range(100):
            truth = f"BoundaryKnown_{i}"
            rows.append(dict(probe_uid=f"BoundaryKnownProbe_{i}",
                             split="calibration", truth_uid=truth,
                             session_uid=f"BoundarySessionK_{i}", condition="front",
                             candidate_uid=truth, similarity=".91"))
        for i in range(305):
            rows.append(dict(probe_uid=f"BoundaryUnknownProbe_{i}",
                             split="calibration", truth_uid="UNKNOWN",
                             session_uid=f"BoundarySessionU_{i}", condition="lookalike",
                             candidate_uid=f"BoundaryImpostor_{i}",
                             similarity=".93" if i < 9 else ".12"))
        bound = _upper_wilson(9, 305)
        self.assertGreater(bound, .05)
        self.assertEqual(round(bound, 4), .05)
        with self.assertRaisesRegex(ValueError, "nontrivial"):
            benchmark_model_scores(rows)

    def test_calibration_sweep_does_not_rescan_for_each_distinct_score(self):
        rows = fixture()
        for row in rows:
            if (row["split"] == "calibration"
                    and row["candidate_uid"] == row["truth_uid"]):
                index = int(row["truth_uid"].rsplit("_", 1)[1])
                row["similarity"] = str(.75 + index / 10_000)
        # This previously caused a fresh full-catalogue summary for
        # every distinct similarity. Now aggregation occurs exactly
        # once each for final calibration and holdout.
        with patch.object(reid_module, "_summarize",
                          wraps=reid_module._summarize) as spy:
            answer = benchmark_model_scores(rows)
        self.assertEqual(spy.call_count, 2)
        self.assertEqual(answer["calibration"]["overall"]["correct_known_offers"], 100)
        self.assertLessEqual(
            answer["calibration"]["overall"]["unknown_false_offer_wilson95_upper"],
            .05
        )

    def test_sweep_matches_independent_bruteforce_threshold_selection(self):
        rows = fixture()
        for row in rows:
            if row["split"] == "calibration" and row["candidate_uid"] == row["truth_uid"]:
                i = int(row["truth_uid"].rsplit("_", 1)[1])
                row["similarity"] = str(.72 + (i % 17) / 100)
            if row["split"] == "calibration" and row["truth_uid"] == "UNKNOWN":
                i = int(row["probe_uid"].rsplit("_", 1)[1])
                row["similarity"] = str(.82 + i / 1000) if i < 4 else ".13"
        cal = reid_module._parse(rows)["calibration"]
        thresholds = sorted({v[4] for v in cal if math.isfinite(v[4])
                             and not v[6]}, reverse=True)
        oracle = []
        for floor in thresholds:
            counts = reid_module._summarize(cal, floor)["overall"]
            # Compare RAW Wilson, not the rounded presentation field.
            u = _upper_wilson(counts["unknown_false_offers"],
                              counts["unknown_observations"])
            k = _upper_wilson(counts["wrong_known_offers"],
                              counts["known_observations"])
            if u <= .05 and k <= .05:
                oracle.append((
                    counts["correct_known_offers"],
                    -counts["wrong_known_offers"] - counts["unknown_false_offers"],
                    floor
                ))
        self.assertTrue(oracle)
        self.assertEqual(
            benchmark_model_scores(rows)["similarity_floor"],
            max(oracle)[2]
        )

    def test_offline_csv_cli_emits_aggregate_only_and_never_writes_db(self):
        rows = fixture()
        with tempfile.TemporaryDirectory(prefix="atlas-synth-model-benchmark-") as d:
            p = Path(d) / "fictional-scores.csv"
            with p.open("w", encoding="utf-8", newline="") as stream:
                writer = csv.DictWriter(stream, fieldnames=FIELDS)
                writer.writeheader()
                writer.writerows(rows)
            before = p.read_bytes()
            # CLI returns 0 and prints only aggregates.
            self.assertEqual(main([str(p)]), 0)
            self.assertEqual(p.read_bytes(), before)
            self.assertEqual(sorted(x.name for x in Path(d).iterdir()),
                             ["fictional-scores.csv"])


    def test_correlated_calibration_photos_cannot_fake_100_independent_unknowns(self):
        # Baseline probe-level bound is 1/100: 4.36% Wilson upper. But
        # 21 photos are from the SAME session, so only 80 distinct Unknown
        # events remain; its any-error/session bound is 5.41% (>5%).
        baseline = benchmark_model_scores(fixture())
        self.assertLess(baseline["calibration"]["overall"][
            "unknown_false_offer_wilson95_upper"], .05)
        rows = fixture()
        for row in rows:
            if (row["split"] == "calibration"
                    and row["truth_uid"] == "UNKNOWN"
                    and int(row["probe_uid"].rsplit("_", 1)[1]) < 21):
                row["session_uid"] = "CalSharedUnknownEvent"
        self.assertLess(_upper_wilson(1, 100), .05)
        self.assertGreater(_upper_wilson(1, 80), .05)
        with self.assertRaisesRegex(ValueError, "nontrivial"):
            benchmark_model_scores(rows)
        # The heldout rows and the source manifest have not been altered.
        self.assertEqual(benchmark_model_scores(fixture()), baseline)

    def test_heldout_session_error_metrics_count_an_event_only_once(self):
        rows = fixture()
        for row in rows:
            if row["split"] != "holdout":
                continue
            i = int(row["probe_uid"].rsplit("_", 1)[1])
            if row["truth_uid"] == "UNKNOWN" and i < 4:
                row["session_uid"] = "HoldSharedUnknownEvent"
            elif row["truth_uid"] != "UNKNOWN" and i < 5:
                row["session_uid"] = "HoldSharedKnownEvent"
        report = benchmark_model_scores(rows)
        self.assertEqual(report["similarity_floor"], .91)
        holdout = report["holdout"]["overall"]
        self.assertEqual(holdout["unknown_false_offers"], 4)
        self.assertEqual(holdout["wrong_known_offers"], 5)
        self.assertEqual(holdout["distinct_unknown_sessions"], 22)
        self.assertEqual(holdout["distinct_known_sessions"], 21)
        self.assertEqual(holdout["unknown_false_offer_error_sessions"], 1)
        self.assertEqual(holdout["wrong_known_error_sessions"], 1)
        self.assertEqual(holdout["unknown_false_offer_session_error_fraction"],
                         round(1 / 22, 4))
        self.assertEqual(holdout["wrong_known_session_error_fraction"],
                         round(1 / 21, 4))
        self.assertGreater(holdout["wrong_known_session_wilson95_upper"], .05)
        self.assertGreater(holdout["unknown_false_offer_session_wilson95_upper"], .05)
        self.assertEqual(report["holdout"]["by_condition"]["back"][
            "wrong_known_error_sessions"], 1)
        self.assertEqual(report["holdout"]["by_condition"]["lookalike"][
            "unknown_false_offer_error_sessions"], 1)
        self.assertNotIn("HoldSharedKnownEvent", json.dumps(report))
        self.assertNotIn("HoldSharedUnknownEvent", json.dumps(report))
        self.assertEqual(report, benchmark_model_scores(list(reversed(rows))))

    def test_sessions_with_both_known_and_unknown_are_separately_accounted(self):
        rows = fixture()
        for row in rows:
            if (row["split"] == "holdout"
                    and row["probe_uid"] in {"HoldProbeK_0", "HoldProbeU_0"}):
                row["session_uid"] = "HoldMixedEvent"
        holdout = benchmark_model_scores(rows)["holdout"]["overall"]
        # A single event can contain both a known subject and a new person;
        # the Unknown cohort's denominator must not absorb known trials.
        self.assertEqual(holdout["known_observations"], 25)
        self.assertEqual(holdout["unknown_observations"], 25)
        self.assertEqual(holdout["distinct_known_sessions"], 25)
        self.assertEqual(holdout["distinct_unknown_sessions"], 25)
        self.assertEqual(holdout["unknown_false_offer_error_sessions"], 4)
        self.assertEqual(holdout["wrong_known_error_sessions"], 5)


    def test_overlapping_back_occluded_lookalike_conditions_are_auditable(self):
        base = benchmark_model_scores(fixture())
        rows = fixture()
        for row in rows:
            if row["split"] != "holdout":
                continue
            i = int(row["probe_uid"].rsplit("_", 1)[1])
            if row["truth_uid"] == "UNKNOWN" and i < 12:
                row["condition"] = "lookalike+back"  # intentionally unsorted
            elif row["truth_uid"] != "UNKNOWN" and i < 11:
                row["condition"] = "occluded+back"
        result = benchmark_model_scores(rows)
        self.assertEqual(result["similarity_floor"], base["similarity_floor"])
        holdout = result["holdout"]
        exact = holdout["by_condition"]
        marginal = holdout["by_condition_tag"]
        self.assertEqual(set(exact), {
            "back", "back+occluded", "back+lookalike", "lookalike"
        })
        self.assertEqual(exact["back+occluded"]["observations"], 11)
        self.assertEqual(exact["back+occluded"]["wrong_known_offers"], 5)
        self.assertEqual(exact["back+lookalike"]["observations"], 12)
        self.assertEqual(exact["back+lookalike"]["unknown_false_offers"], 4)
        self.assertEqual(marginal["back"]["observations"], 37)
        self.assertEqual(marginal["back"]["known_observations"], 25)
        self.assertEqual(marginal["back"]["unknown_observations"], 12)
        self.assertEqual(marginal["back"]["unknown_false_offer_error_sessions"], 4)
        self.assertEqual(marginal["occluded"]["wrong_known_offers"], 5)
        self.assertEqual(marginal["lookalike"]["unknown_false_offers"], 4)
        self.assertEqual(holdout["overall"]["observations"], 50)
        self.assertEqual(holdout["overall"], base["holdout"]["overall"])
        self.assertEqual(result, benchmark_model_scores(list(reversed(rows))))
        # Canonicalization must not change score metrics if input tag order flips.
        reversed_tags = [dict(row) for row in rows]
        for row in reversed_tags:
            if "+" in row["condition"]:
                row["condition"] = "+".join(reversed(row["condition"].split("+")))
        self.assertEqual(result, benchmark_model_scores(reversed_tags))

    def test_invalid_compound_conditions_fail_closed(self):
        for condition in (
            "unknown+back", "back+unknown", "front+front", "back++occluded",
            "back+made_up", "front+back+side+partial", "", "back+", "BACK"
        ):
            rows = fixture()
            rows[0]["condition"] = condition
            with self.subTest(condition=condition):
                with self.assertRaisesRegex(ValueError, "condition"):
                    benchmark_model_scores(rows)

    def test_one_photo_intersection_is_suppressed_not_an_accuracy_claim(self):
        rows = fixture()
        for row in rows:
            if row["probe_uid"] == "HoldProbeU_0":
                row["condition"] = "back+occluded"
        holdout = benchmark_model_scores(rows)["holdout"]
        self.assertNotIn("back+occluded", holdout["by_condition"])
        self.assertNotIn("occluded", holdout["by_condition_tag"])
        self.assertEqual(holdout["suppressed_condition_count"], 1)
        self.assertEqual(holdout["suppressed_condition_tag_count"], 1)
        self.assertEqual(holdout["overall"]["unknown_false_offers"], 4)


    def test_near_tied_top_two_scores_abstain_without_recalibrating_on_holdout(self):
        rows = fixture()
        for row in rows:
            if row["probe_uid"] == "HoldProbeK_0" and row["candidate_uid"] == "HoldKnown_0":
                row["similarity"] = ".95"  # near wrong top impostor's .97
        unknown = next(row for row in rows if row["probe_uid"] == "HoldProbeU_0")
        second = dict(unknown, candidate_uid="HoldUnknownRunner_0",
                      similarity=".94")  # top Unknown impostor .96
        rows.append(second)

        ungated = benchmark_model_scores(rows)
        gated = benchmark_model_scores(rows, min_top_two_margin=.05)
        self.assertEqual(ungated["similarity_floor"], gated["similarity_floor"])
        self.assertEqual(gated["similarity_floor"], .91)
        self.assertEqual(ungated["holdout"]["overall"]["wrong_known_offers"], 5)
        self.assertEqual(ungated["holdout"]["overall"]["unknown_false_offers"], 4)
        holdout = gated["holdout"]["overall"]
        self.assertEqual(holdout["near_tie_abstentions"], 2)
        self.assertEqual(holdout["wrong_known_offers"], 4)
        self.assertEqual(holdout["unknown_false_offers"], 0)
        self.assertEqual(holdout["missed_known_abstentions"], 1)
        self.assertEqual(holdout["correct_unknown_abstentions"], 25)
        self.assertEqual(holdout["runner_up_unavailable_abstentions"], 3)
        self.assertEqual(holdout["abstentions"], 26)
        self.assertEqual(holdout["abstentions"] - ungated["holdout"]["overall"]["abstentions"], 5)
        self.assertEqual(holdout["exact_tie_abstentions"], 0)
        self.assertEqual(gated["minimum_top_two_margin"], .05)
        self.assertEqual(gated, benchmark_model_scores(
            list(reversed(rows)), min_top_two_margin=.05))
        self.assertNotIn("HoldUnknownRunner", json.dumps(gated))

    def test_single_returned_candidate_has_no_observable_runner_up(self):
        # Unknown runner-up MUST abstain if a positive gap is required.
        # With margin=0 the old score-only benchmark stays backward compatible.
        rows = fixture()
        legacy = benchmark_model_scores(rows, min_top_two_margin=0)
        gated = benchmark_model_scores(rows, min_top_two_margin=.05)
        self.assertEqual(legacy["calibration"]["overall"]["unknown_false_offers"], 1)
        self.assertEqual(legacy["holdout"]["overall"]["unknown_false_offers"], 4)
        self.assertEqual(legacy["holdout"]["overall"]["runner_up_unavailable_abstentions"], 0)
        self.assertEqual(gated["calibration"]["overall"]["unknown_false_offers"], 0)
        self.assertEqual(gated["calibration"]["overall"]["runner_up_unavailable_abstentions"], 1)
        self.assertEqual(gated["holdout"]["overall"]["unknown_false_offers"], 0)
        self.assertEqual(gated["holdout"]["overall"]["runner_up_unavailable_abstentions"], 4)
        self.assertEqual(gated["holdout"]["overall"]["near_tie_abstentions"], 0)
        self.assertEqual(gated["similarity_floor"], legacy["similarity_floor"])
        self.assertEqual(gated, benchmark_model_scores(list(reversed(rows)),
                                                     min_top_two_margin=.05))

    def test_invalid_ambiguity_margin_rejected_before_model_evaluation(self):
        for margin in (True, False, -0.01, 1.1, float("nan"),
                       float("inf"), "0.05", None):
            with self.subTest(margin=margin):
                with self.assertRaisesRegex(ValueError, "margin"):
                    benchmark_model_scores(fixture(), min_top_two_margin=margin)


    def test_repeated_known_person_across_events_cannot_fake_many_identities(self):
        # 100 distinct observations and sessions of ONE known person are not
        # 100 independent known-person error trials, even at zero errors.
        rows = fixture()
        for row in rows:
            if row["split"] == "calibration" and row["truth_uid"] != "UNKNOWN":
                if row["candidate_uid"] == row["truth_uid"]:
                    row["candidate_uid"] = "RepeatedKnownSubject"
                row["truth_uid"] = "RepeatedKnownSubject"
        with self.assertRaisesRegex(ValueError, "nontrivial"):
            benchmark_model_scores(rows)
        self.assertEqual(len({r["session_uid"] for r in rows
                              if r["split"] == "calibration"
                              and r["truth_uid"] != "UNKNOWN"}), 100)

    def test_holdout_known_person_cluster_reports_one_failure_identity(self):
        rows = fixture()
        for row in rows:
            if row["split"] == "holdout" and row["probe_uid"].startswith("HoldProbeK_"):
                if row["candidate_uid"] == row["truth_uid"]:
                    row["candidate_uid"] = "SingleHoldoutPerson"
                row["truth_uid"] = "SingleHoldoutPerson"
        hold = benchmark_model_scores(rows)["holdout"]["overall"]
        self.assertEqual(hold["known_observations"], 25)
        self.assertEqual(hold["wrong_known_offers"], 5)
        self.assertEqual(hold["distinct_known_persons"], 1)
        self.assertEqual(hold["wrong_known_error_persons"], 1)
        self.assertEqual(hold["wrong_known_person_wilson95_upper"], 1.0)
        self.assertEqual(benchmark_model_scores(fixture())["calibration"]["overall"][
            "distinct_known_persons"], 100)


# Exercise the optional runtime probe without new GitHub workflow edits.
from test_benchmark_onnx_runtime import SyntheticOnnxRuntimeBenchmarkTests


from test_verify_candidate_artifact import CandidateArtifactRegisterTests


from test_model_preprocessing import SyntheticPreprocessingContractTests


from test_audit_correlation_sensitivity import CorrelationStressAuditTests


from test_benchmark_resize_parity import ResizeParitySyntheticTests


from test_verify_split_provenance import SplitProvenanceTests


from test_candidate_list_coverage import CandidateListCoverageTests


from test_build_frozen_roster import FrozenRosterGenerationTests


from test_benchmark_retrieval_recall import RetrievalRecallTests


from test_compare_paired_models import PairedModelComparisonTests


from test_preregistered_comparison import FrozenStudyProtocolTests


from test_protected_condition_calibration import ProtectedConditionCalibrationTests


from test_protected_preregistration import ProtectedPreregistrationTests


if __name__ == "__main__":
    unittest.main(verbosity=2)
