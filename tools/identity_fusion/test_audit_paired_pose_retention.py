#!/usr/bin/env python3
"""Synthetic, photograph-free stress for reviewed front/back condition pairs."""
import copy
import json
import unittest

from benchmark_reid_scores import FIELDS
from audit_paired_pose_retention import audit_paired_pose_retention
from test_benchmark_reid_scores import fixture


def scenario():
    scores = [dict(row) for row in fixture()]
    pairs = []
    for i in range(25):
        truth = f"HoldKnown_{i}"
        front = f"HoldFrontK_{i}"
        pairs.append({"front_probe_uid": front,
                      "rear_probe_uid": f"HoldProbeK_{i}"})
        for candidate, score in ((truth, ".96"), (f"HoldImpostor_{i}", ".11")):
            scores.append(dict(zip(FIELDS, (
                front, "holdout", truth, f"HoldFrontSessionK_{i}",
                "front", candidate, score))))
    # Fictional hard negatives: calibration and holdout both need rear-facing
    # Unknown subjects, not just easy frontal strangers.
    for row in scores:
        if row["truth_uid"] == "UNKNOWN":
            row["condition"] = "back+lookalike"
    metadata = {}
    roster = []
    for row in scores:
        probe, split = row["probe_uid"], row["split"]
        k = (split, probe)
        if k not in metadata:
            truth = row["truth_uid"]
            metadata[k] = dict(
                probe_uid=probe, split=split,
                photo_lineage_uid=f"Lineage_{probe}",
                event_cluster_uid=f"Event_{probe}",
                subject_cluster_uid=(truth if truth != "UNKNOWN" else f"Anonymous_{probe}"))
        roster.append(dict(split=split, probe_uid=probe,
                           candidate_uid=row["candidate_uid"]))
    return scores, list(metadata.values()), roster, pairs


def run(case, **kwargs):
    return audit_paired_pose_retention(*case, **kwargs)


class PairedPoseRetentionTests(unittest.TestCase):
    def test_back_facing_known_person_loss_is_visible_and_aggregate_only(self):
        case = scenario()
        report = run(case)
        self.assertEqual(report["calibration_locked_similarity_floor"], .91)
        self.assertEqual(report["overall"]["reviewed_known_person_pairs"], 25)
        self.assertEqual(report["overall"]["transitions"]["front_correct_rear_correct"], 20)
        self.assertEqual(report["overall"]["front_correct_rear_wrong"], 5)
        self.assertEqual(report["overall"]["rear_correct_fraction"], .8)
        self.assertEqual(report["by_rear_condition_tag"]["back"]["rear_wrong_offer_pairs"], 5)
        self.assertEqual(report["rear_unknown_controls"]["calibration"]["overall"]
                         ["reviewed_unknown_subject_groups"], 100)
        unknown = report["rear_unknown_controls"]["holdout"]
        self.assertEqual(unknown["overall"]["reviewed_unknown_subject_groups"], 25)
        self.assertEqual(unknown["overall"]["reviewed_unknown_event_groups"], 25)
        self.assertEqual(unknown["overall"]["groups_with_false_offers"], 4)
        self.assertEqual(unknown["by_rear_condition_tag"]["back"]
                         ["groups_with_false_offers"], 4)
        self.assertFalse(report["auto_confirm_allowed"])
        self.assertFalse(report["paired_source_independence_physically_verified"])
        self.assertIsNone(report["calibrated_identity_probability"])
        serialized = json.dumps(report)
        for raw in ("HoldKnown", "HoldFrontK", "HoldProbeK", "Lineage_Hold", "Event_Hold"):
            self.assertNotIn(raw, serialized)
        shuffled = (list(reversed(case[0])), list(reversed(case[1])),
                    list(reversed(case[2])), list(reversed(case[3])))
        self.assertEqual(report, run(shuffled))

    def test_pairwise_event_component_diagnostic_keeps_disjoint_events_distinct(self):
        report = run(scenario())
        overall = report["overall"]
        comp = report["paired_event_component_safety"]
        self.assertEqual(overall["reviewed_known_person_pairs"], 25)
        self.assertEqual(comp["declared_event_nodes"], 50)
        self.assertEqual(comp["connected_capture_event_components"], 25)
        self.assertEqual(comp["pairs_in_largest_connected_component"], 1)
        self.assertEqual(comp["components_with_any_rear_wrong_offer"], 5)
        self.assertEqual(comp["components_with_front_correct_rear_wrong"], 5)
        self.assertEqual(comp["components_with_any_rear_abstention"], 0)
        self.assertIsInstance(comp["component_rear_wrong_offer_wilson95_upper"], float)
        self.assertFalse(comp["component_independence_physically_verified"])
        self.assertTrue(comp["component_bounds_are_research_diagnostics_not_release_gates"])
        self.assertFalse(report["auto_confirm_allowed"])

    def test_25_people_from_one_pair_of_events_count_as_only_one_component(self):
        original = scenario()
        correlated = list(copy.deepcopy(original))
        for row in correlated[1]:
            if row["split"] != "holdout":
                continue
            if row["probe_uid"].startswith("HoldFrontK_"):
                row["event_cluster_uid"] = "CommonFrontCaptureEvent"
            elif row["probe_uid"].startswith("HoldProbeK_"):
                row["event_cluster_uid"] = "CommonRearCaptureEvent"
        baseline, changed = run(original), run(correlated)
        self.assertEqual(baseline["overall"], changed["overall"])
        self.assertEqual(
            baseline["calibration_locked_similarity_floor"],
            changed["calibration_locked_similarity_floor"])
        comp = changed["paired_event_component_safety"]
        self.assertEqual(comp["declared_event_nodes"], 2)
        self.assertEqual(comp["connected_capture_event_components"], 1)
        self.assertEqual(comp["pairs_in_largest_connected_component"], 25)
        self.assertEqual(comp["components_with_any_rear_wrong_offer"], 1)
        self.assertEqual(comp["components_with_front_correct_rear_wrong"], 1)
        self.assertEqual(comp["component_rear_wrong_offer_wilson95_upper"], 1.0)
        self.assertFalse(comp["component_independence_physically_verified"])
        self.assertFalse(changed["auto_confirm_allowed"])
        public = json.dumps(changed)
        for private in ("CommonFrontCaptureEvent", "CommonRearCaptureEvent",
                        "HoldProbeK_", "HoldKnown_"):
            self.assertNotIn(private, public)

    def test_transitive_event_overlap_components_and_order_invariance(self):
        case = list(copy.deepcopy(scenario()))
        # Three pair edges A--B, B--C and C--D form ONE connected
        # correlated component, even though pair 0 and pair 2 do not
        # directly share an event ID with each other.
        mapping = {
            "HoldFrontK_0": "EventA",
            "HoldProbeK_0": "EventB",
            "HoldFrontK_1": "EventB",
            "HoldProbeK_1": "EventC",
            "HoldFrontK_2": "EventC",
            "HoldProbeK_2": "EventD",
        }
        for row in case[1]:
            if row["split"] == "holdout" and row["probe_uid"] in mapping:
                row["event_cluster_uid"] = mapping[row["probe_uid"]]
        result = run(case)
        comp = result["paired_event_component_safety"]
        self.assertEqual(result["overall"]["reviewed_known_person_pairs"], 25)
        self.assertEqual(comp["connected_capture_event_components"], 23)
        self.assertEqual(comp["pairs_in_largest_connected_component"], 3)
        self.assertEqual(comp["declared_event_nodes"], 48)
        self.assertEqual(comp["components_with_any_rear_wrong_offer"], 3)
        self.assertEqual(comp["components_with_front_correct_rear_wrong"], 3)
        self.assertEqual(result, run((
            list(reversed(case[0])), list(reversed(case[1])),
            list(reversed(case[2])), list(reversed(case[3])))))

    def test_source_dependency_overlay_identifies_multi_person_same_photo(self):
        original = scenario()
        shared = list(copy.deepcopy(original))
        sources = {p["probe_uid"]: p for p in shared[1]}
        sources["HoldFrontK_1"]["photo_lineage_uid"] = (
            sources["HoldFrontK_0"]["photo_lineage_uid"])
        baseline, changed = run(original), run(shared)
        self.assertEqual(baseline["overall"], changed["overall"])
        self.assertEqual(
            baseline["calibration_locked_similarity_floor"],
            changed["calibration_locked_similarity_floor"])
        self.assertEqual(
            changed["paired_event_component_safety"]["connected_capture_event_components"], 25)
        dependency = changed["paired_multi_axis_capture_dependency_safety"]
        self.assertEqual(dependency["connected_event_lineage_session_components"], 24)
        self.assertEqual(dependency["pairs_in_largest_connected_component"], 2)
        self.assertEqual(dependency["components_with_any_rear_wrong_offer"], 4)
        self.assertTrue(dependency["axis_namespace_collisions_prevented"])
        self.assertFalse(dependency["component_independence_physically_verified"])
        self.assertFalse(changed["auto_confirm_allowed"])
        for private in ("Lineage_HoldFrontK", "HoldKnown_", "HoldFrontK_"):
            self.assertNotIn(private, json.dumps(changed))

    def test_transitive_cross_axis_session_and_lineage_overlap(self):
        case = list(copy.deepcopy(scenario()))
        sources = {p["probe_uid"]: p for p in case[1]}
        # Pair 0 --(photo lineage)-- pair 1 --(session)-- pair 2.
        # No event overlaps, and pairs 0/2 do not overlap directly.
        sources["HoldFrontK_1"]["photo_lineage_uid"] = (
            sources["HoldProbeK_0"]["photo_lineage_uid"])
        source_sessions = {
            "HoldProbeK_1": "CommonRearToFrontSession",
            "HoldFrontK_2": "CommonRearToFrontSession",
        }
        for row in case[0]:
            if row["split"] == "holdout" and row["probe_uid"] in source_sessions:
                row["session_uid"] = source_sessions[row["probe_uid"]]
        result = run(case)
        self.assertEqual(result["paired_event_component_safety"]
                         ["connected_capture_event_components"], 25)
        details = result["paired_multi_axis_capture_dependency_safety"]
        self.assertEqual(details["connected_event_lineage_session_components"], 23)
        self.assertEqual(details["pairs_in_largest_connected_component"], 3)
        self.assertEqual(details["components_with_any_rear_wrong_offer"], 3)
        self.assertEqual(result, run((
            list(reversed(case[0])), list(reversed(case[1])),
            list(reversed(case[2])), list(reversed(case[3])))))

    def test_provenance_axes_are_namespaced_and_do_not_alias_across_kinds(self):
        base = scenario()
        altered = list(copy.deepcopy(base))
        sources = {p["probe_uid"]: p for p in altered[1]}
        # The same opaque token appears once as an event and once as
        # a lineage. These are unrelated provenance dimensions.
        sources["HoldFrontK_0"]["event_cluster_uid"] = (
            sources["HoldFrontK_1"]["photo_lineage_uid"])
        result = run(altered)
        self.assertEqual(result["overall"], run(base)["overall"])
        self.assertEqual(result["paired_multi_axis_capture_dependency_safety"]
                         ["connected_event_lineage_session_components"], 25)
        self.assertEqual(result["paired_event_component_safety"]
                         ["connected_capture_event_components"], 25)

    def test_holdout_score_change_does_not_move_calibration_floor(self):
        case = scenario()
        changed = copy.deepcopy(case)
        for row in changed[0]:
            if row["split"] == "holdout" and row["probe_uid"].startswith("HoldProbeK"):
                row["similarity"] = ".01" if row["candidate_uid"].startswith("HoldKnown") else ".99"
        a, b = run(case), run(changed)
        self.assertEqual(a["calibration_locked_similarity_floor"],
                         b["calibration_locked_similarity_floor"])
        self.assertNotEqual(a["overall"], b["overall"])

    def test_exact_rear_unknown_intersections_count_only_single_probe_tags(self):
        case = list(copy.deepcopy(scenario()))
        for row in case[0]:
            if row["split"] != "holdout" or row["truth_uid"] != "UNKNOWN":
                continue
            index = int(row["probe_uid"].rsplit("_", 1)[-1])
            if index < 10:
                row["condition"] = "back+occluded"
            elif index < 20:
                row["condition"] = "back+appearance_shift"
        report = run(case)
        controls = report["rear_unknown_controls"]["holdout"]
        exact = controls["by_exact_rear_condition"]
        self.assertEqual(set(exact), {"back+occluded", "appearance_shift+back"})
        self.assertEqual(exact["back+occluded"]["reviewed_unknown_subject_groups"], 10)
        self.assertEqual(exact["back+occluded"]["reviewed_unknown_event_groups"], 10)
        self.assertEqual(exact["back+occluded"]["groups_with_false_offers"], 4)
        self.assertEqual(
            exact["appearance_shift+back"]["reviewed_unknown_subject_groups"], 10)
        self.assertEqual(controls["suppressed_exact_rear_conditions"], 1)
        self.assertNotIn("back+lookalike", exact)  # only five groups
        self.assertNotIn("appearance_shift+back+occluded", exact)
        self.assertTrue(controls["exact_rear_condition_cooccurrence_declared_on_one_probe"])
        self.assertEqual(
            report["calibration_locked_similarity_floor"],
            run(scenario())["calibration_locked_similarity_floor"])
        self.assertFalse(report["auto_confirm_allowed"])
        self.assertEqual(report, run((
            list(reversed(case[0])), list(reversed(case[1])),
            list(reversed(case[2])), list(reversed(case[3]))
        )))
        serialized = json.dumps(report)
        for sensitive in ("HoldProbeU_", "Anonymous_", "HoldKnown_",
                          "Event_", "Lineage_"):
            self.assertNotIn(sensitive, serialized)

    def test_underpowered_exact_rear_unknown_condition_is_suppressed(self):
        case = list(copy.deepcopy(scenario()))
        for row in case[0]:
            if row["probe_uid"] == "HoldProbeU_0":
                row["condition"] = "back+occluded"
        result = run(case)
        controls = result["rear_unknown_controls"]["holdout"]
        self.assertNotIn("back+occluded", controls["by_exact_rear_condition"])
        self.assertEqual(controls["suppressed_exact_rear_conditions"], 1)
        self.assertIn("back+lookalike", controls["by_exact_rear_condition"])
        self.assertEqual(
            controls["by_rear_condition_tag"]["back"]["reviewed_unknown_subject_groups"], 25)
        self.assertFalse(controls["declared_groups_independently_verified"])

    def test_critical_rear_unknown_absent_is_not_mistaken_for_safe(self):
        report = run(scenario())
        control = report["rear_unknown_controls"]["holdout"]
        matrix = control["critical_rear_unknown_intersection_coverage"]
        self.assertEqual(len(matrix), 6)
        self.assertEqual(matrix["back+lookalike"]["status"],
                         "measured_not_safety_qualified")
        self.assertEqual(matrix["back+lookalike"]["metrics"]
                         ["reviewed_unknown_subject_groups"], 25)
        for name in ("back+occluded", "appearance_shift+back",
                     "occluded+partial", "appearance_shift+partial",
                     "lookalike+partial"):
            self.assertEqual(matrix[name],
                             {"status": "not_observed", "metrics": None}, name)
        self.assertEqual(control["critical_rear_intersections_measured_count"], 1)
        self.assertTrue(control["critical_rear_intersection_statuses_are_not_release_gates"])
        self.assertFalse(report["auto_confirm_allowed"])
        self.assertFalse(control["declared_groups_independently_verified"])

    def test_critical_rear_unknown_intersection_detects_underpower_and_supertags(self):
        baseline = run(scenario())
        small = list(copy.deepcopy(scenario()))
        for row in small[0]:
            if row["probe_uid"] == "HoldProbeU_0":
                row["condition"] = "back+occluded+appearance_shift"
        tiny = run(small)["rear_unknown_controls"]["holdout"]
        matrix = tiny["critical_rear_unknown_intersection_coverage"]
        for key in ("back+occluded", "appearance_shift+back"):
            self.assertEqual(matrix[key],
                             {"status": "observed_underpowered", "metrics": None})
        self.assertEqual(tiny["critical_rear_intersections_measured_count"], 1)
        # Ten reviewed independent synthetic strangers with all 3 tags
        # support each corresponding pairwise intersection on the SAME
        # probe; a different photo for each tag would not count.
        powered = list(copy.deepcopy(scenario()))
        for row in powered[0]:
            if (row["split"] == "holdout"
                    and row["truth_uid"] == "UNKNOWN"
                    and int(row["probe_uid"].rsplit("_", 1)[-1]) < 10):
                row["condition"] = "back+occluded+appearance_shift"
        report = run(powered)
        rows = report["rear_unknown_controls"]["holdout"]
        cells = rows["critical_rear_unknown_intersection_coverage"]
        for key in ("back+occluded", "appearance_shift+back"):
            self.assertEqual(cells[key]["status"], "measured_not_safety_qualified")
            self.assertEqual(cells[key]["metrics"]["reviewed_unknown_subject_groups"], 10)
            self.assertEqual(cells[key]["metrics"]["reviewed_unknown_event_groups"], 10)
            self.assertEqual(cells[key]["metrics"]["groups_with_false_offers"], 4)
        self.assertEqual(cells["occluded+partial"],
                         {"status": "not_observed", "metrics": None})
        self.assertEqual(rows["critical_rear_intersections_measured_count"], 3)
        self.assertEqual(report["calibration_locked_similarity_floor"],
                         baseline["calibration_locked_similarity_floor"])
        self.assertFalse(report["auto_confirm_allowed"])
        serialized = json.dumps(rows)
        for secret in ("HoldProbeU_", "Anonymous_", "Event_", "subject_uid"):
            self.assertNotIn(secret, serialized)

    def test_rear_unknown_dependency_components_remain_independent_for_distinct_sources(self):
        report = run(scenario())
        for split, expected_count, expected_errors in (
            ("calibration", 100, 1), ("holdout", 25, 4)
        ):
            with self.subTest(split=split):
                controls = report["rear_unknown_controls"][split]
                comp = controls["capture_dependency_component_safety"]
                self.assertEqual(comp["qualifying_rear_unknown_probes"], expected_count)
                self.assertEqual(comp["connected_unknown_capture_components"], expected_count)
                self.assertEqual(comp["components_with_any_false_review_offer"], expected_errors)
                self.assertEqual(comp["largest_component_probe_count"], 1)
                self.assertTrue(comp["diagnostic_not_calibration_or_release_gate"])
                self.assertFalse(comp["declared_capture_independence_physically_verified"])
                self.assertFalse(comp["auto_confirm_allowed"])
        self.assertFalse(report["auto_confirm_allowed"])

    def test_shared_lineage_collapses_hundred_seemingly_independent_unknown_controls(self):
        original = scenario()
        changed = list(copy.deepcopy(original))
        for row in changed[1]:
            if row["split"] == "calibration" and row["probe_uid"].startswith("CalProbeU_"):
                row["photo_lineage_uid"] = "OneCorrelatedAnonymousPhotoFamily"
        baseline, collapsed = run(original), run(changed)
        # The existing subject/event Wilson calibration gate still sees
        # 100 distinct declared subjects and events with one false offer.
        self.assertEqual(
            baseline["calibration_locked_similarity_floor"],
            collapsed["calibration_locked_similarity_floor"])
        self.assertEqual(baseline["overall"], collapsed["overall"])
        before = baseline["rear_unknown_controls"]["calibration"]
        after = collapsed["rear_unknown_controls"]["calibration"]
        self.assertEqual(before["overall"], after["overall"])
        self.assertEqual(after["overall"]["reviewed_unknown_subject_groups"], 100)
        self.assertEqual(after["overall"]["reviewed_unknown_event_groups"], 100)
        diagnostic = after["capture_dependency_component_safety"]
        self.assertEqual(diagnostic["qualifying_rear_unknown_probes"], 100)
        self.assertEqual(diagnostic["connected_unknown_capture_components"], 1)
        self.assertEqual(diagnostic["largest_component_probe_count"], 100)
        self.assertEqual(diagnostic["components_with_any_false_review_offer"], 1)
        self.assertEqual(diagnostic["component_false_offer_wilson95_upper"], 1.0)
        self.assertTrue(
            diagnostic["component_risk_exceeds_frozen_5pct_ceiling_diagnostic"])
        self.assertTrue(diagnostic["diagnostic_not_calibration_or_release_gate"])
        self.assertFalse(diagnostic["declared_capture_independence_physically_verified"])
        self.assertFalse(collapsed["auto_confirm_allowed"])
        serialized = json.dumps(collapsed)
        for secret in ("OneCorrelatedAnonymousPhotoFamily", "CalProbeU_", "Anonymous_"):
            self.assertNotIn(secret, serialized)

    def test_unknown_cross_axis_transitivity_and_namespace_are_conservative(self):
        case = list(copy.deepcopy(scenario()))
        source = {p["probe_uid"]: p for p in case[1]}
        # One connected trio: U0 --shared event-- U1
        # then U1 --shared session-- U2.
        source["HoldProbeU_1"]["event_cluster_uid"] = (
            source["HoldProbeU_0"]["event_cluster_uid"])
        for row in case[0]:
            if row["probe_uid"] in ("HoldProbeU_1", "HoldProbeU_2"):
                row["session_uid"] = "UnknownSharedSession_1_2"
        report = run(case)
        diag = report["rear_unknown_controls"]["holdout"][
            "capture_dependency_component_safety"]
        self.assertEqual(diag["connected_unknown_capture_components"], 23)
        self.assertEqual(diag["largest_component_probe_count"], 3)
        self.assertEqual(diag["components_with_any_false_review_offer"], 2)
        # The shared string is in DIFFERENT columns here. It must not
        # collapse otherwise unrelated Unknown subjects.
        no_false_alias = list(copy.deepcopy(scenario()))
        source2 = {p["probe_uid"]: p for p in no_false_alias[1]}
        source2["HoldProbeU_0"]["event_cluster_uid"] = (
            source2["HoldProbeU_1"]["photo_lineage_uid"])
        check = run(no_false_alias)["rear_unknown_controls"]["holdout"][
            "capture_dependency_component_safety"]
        self.assertEqual(check["connected_unknown_capture_components"], 25)
        self.assertEqual(diag, run((
            list(reversed(case[0])), list(reversed(case[1])),
            list(reversed(case[2])), list(reversed(case[3]))
        ))["rear_unknown_controls"]["holdout"][
            "capture_dependency_component_safety"])

    def test_reject_easy_front_only_unknown_control_cohorts(self):
        for split in ("calibration", "holdout"):
            case = list(copy.deepcopy(scenario()))
            for row in case[0]:
                if row["split"] == split and row["truth_uid"] == "UNKNOWN":
                    row["condition"] = "front+lookalike"
            with self.subTest(split=split), self.assertRaises(ValueError):
                run(case)

    def test_reject_unknown_event_and_subject_pseudoreplication(self):
        for group in ("event_cluster_uid", "subject_cluster_uid"):
            case = list(copy.deepcopy(scenario()))
            for row in case[1]:
                if row["split"] == "holdout" and row["probe_uid"].startswith("HoldProbeU"):
                    row[group] = "SameUnknownGroup"
            with self.subTest(group=group), self.assertRaises(ValueError):
                run(case)

    def test_report_unknown_false_offers_without_changing_locked_floor(self):
        case = scenario()
        changed = copy.deepcopy(case)
        for row in changed[0]:
            if row["split"] == "holdout" and row["truth_uid"] == "UNKNOWN":
                row["similarity"] = ".01"
        baseline, improved = run(case), run(changed)
        self.assertEqual(baseline["calibration_locked_similarity_floor"],
                         improved["calibration_locked_similarity_floor"])
        self.assertEqual(improved["rear_unknown_controls"]["holdout"]["overall"]
                         ["groups_with_false_offers"], 0)
        self.assertEqual(baseline["overall"], improved["overall"])

    def test_partial_occluded_appearance_shift_intersection_exposes_errors(self):
        case = list(copy.deepcopy(scenario()))
        difficult = {f"HoldProbeK_{i}" for i in range(10)}
        for row in case[0]:
            if row["probe_uid"] in difficult:
                row["condition"] = "partial+occluded+appearance_shift"
        result = run(case)
        self.assertEqual(result["overall"]["rear_wrong_offer_pairs"], 5)
        partial = result["by_rear_condition_tag"]["partial"]
        self.assertEqual(partial["reviewed_known_person_pairs"], 10)
        self.assertEqual(partial["front_correct_rear_wrong"], 5)
        self.assertEqual(partial["rear_correct_fraction"], .5)
        self.assertEqual(result["by_rear_condition_tag"]["appearance_shift"], partial)
        self.assertEqual(result["by_rear_condition_tag"]["occluded"], partial)
        back = result["by_rear_condition_tag"]["back"]
        self.assertEqual(back["reviewed_known_person_pairs"], 15)
        self.assertEqual(back["rear_wrong_offer_pairs"], 0)
        self.assertEqual(back["rear_correct_fraction"], 1.0)
        self.assertEqual(result["rear_unknown_controls"]["holdout"]["overall"]
                         ["groups_with_false_offers"], 4)
        self.assertFalse(result["auto_confirm_allowed"])

    def test_sparse_partial_intersection_is_suppressed_not_reported_as_accuracy(self):
        case = list(copy.deepcopy(scenario()))
        thin = {f"HoldProbeK_{i}" for i in range(9)}
        for row in case[0]:
            if row["probe_uid"] in thin:
                row["condition"] = "partial+occluded+appearance_shift"
        result = run(case)
        self.assertEqual(result["overall"]["reviewed_known_person_pairs"], 25)
        self.assertEqual(result["overall"]["rear_wrong_offer_pairs"], 5)
        for tag in ("partial", "occluded", "appearance_shift"):
            self.assertNotIn(tag, result["by_rear_condition_tag"])
        self.assertGreaterEqual(result["suppressed_rear_tags"], 3)

    def test_rear_unknown_subgroup_cannot_hide_errors_among_easy_front_controls(self):
        # One rear Unknown false offer / 20 independent rear persons+events,
        # but only one out of all 100 calibration Unknown controls. The
        # *global* calibration gate alone accepts that unsafe rear slice.
        case = list(copy.deepcopy(scenario()))
        for row in case[0]:
            if (row["split"] == "calibration" and row["truth_uid"] == "UNKNOWN"
                    and int(row["probe_uid"].rsplit("_", 1)[1]) >= 20):
                row["condition"] = "front+lookalike"
        with self.assertRaisesRegex(ValueError, "Rear Unknown calibration"):
            run(case)

    def test_rear_unknown_zero_error_cohort_requires_sufficient_groups(self):
        # All 100 controls still exist; deliberately move the single high
        # scoring false offer to the front cohort. At zero rear errors,
        # the one-sided 95% Wilson upper bound for 51 groups is >5%,
        # while it is <5% for 52 distinct persons AND distinct events.
        for rear_count in (51, 52):
            case = list(copy.deepcopy(scenario()))
            for row in case[0]:
                if row["split"] != "calibration" or row["truth_uid"] != "UNKNOWN":
                    continue
                i = int(row["probe_uid"].rsplit("_", 1)[1])
                if i == 0 or i > rear_count:
                    row["condition"] = "front+lookalike"
            if rear_count == 51:
                with self.assertRaisesRegex(ValueError, "Rear Unknown calibration"):
                    run(case)
            else:
                result = run(case)
                self.assertEqual(
                    result["rear_unknown_controls"]["calibration"]["overall"]
                    ["reviewed_unknown_subject_groups"], 52)
                self.assertTrue(
                    result["rear_unknown_calibration_subject_event_bounds_passed"])
                self.assertEqual(
                    result["rear_unknown_calibration_wilson95_risk_ceiling"], .05)
                self.assertFalse(result["auto_confirm_allowed"])

    def test_source_alias_event_alias_and_subject_mismatch_refuse_pair(self):
        for key in ("photo_lineage_uid", "event_cluster_uid", "subject_cluster_uid"):
            case = list(copy.deepcopy(scenario()))
            prov = {row["probe_uid"]: row for row in case[1]}
            first = case[3][0]
            front, rear = first["front_probe_uid"], first["rear_probe_uid"]
            prov[rear][key] = prov[front][key] if key != "subject_cluster_uid" else "OtherSubject"
            with self.subTest(key=key), self.assertRaises(ValueError):
                run(case)

    def test_calibration_reuse_duplicate_pairs_and_wrong_pose_fail_closed(self):
        changes = []
        bad = list(copy.deepcopy(scenario()))
        bad[3][0]["front_probe_uid"] = "CalProbeK_0"
        changes.append(bad)
        bad = list(copy.deepcopy(scenario()))
        bad[3].append(dict(bad[3][0]))
        changes.append(bad)
        bad = list(copy.deepcopy(scenario()))
        for row in bad[0]:
            if row["probe_uid"] == "HoldProbeK_0":
                row["condition"] = "front"
        changes.append(bad)
        bad = list(copy.deepcopy(scenario()))
        bad[3][0]["rear_probe_uid"] = "HoldProbeU_0"
        changes.append(bad)
        bad = list(copy.deepcopy(scenario()))
        bad[3][0]["source_path"] = "/storage/emulated/0"
        changes.append(bad)
        for i, case in enumerate(changes):
            with self.subTest(case=i), self.assertRaises(ValueError):
                run(case)

    def test_require_complete_provenance_and_frozen_roster(self):
        for which in (1, 2):
            case = list(copy.deepcopy(scenario()))
            case[which].pop()
            with self.subTest(manifest=which), self.assertRaises(ValueError):
                run(case)
        with self.assertRaises(ValueError):
            run(scenario(), margin=float("nan"))
        with self.assertRaises(ValueError):
            run(scenario(), margin=True)


if __name__ == "__main__":
    unittest.main()
