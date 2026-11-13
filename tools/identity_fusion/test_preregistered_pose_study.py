#!/usr/bin/env python3
"""Fictional preregistered pose protocol and immutable-truth abuse tests."""
import contextlib
import csv
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest

from preregistered_comparison import TRUTH_FIELDS
from preregistered_pose_study import (
    CALIBRATION, CALIBRATION_V3, KNOWN_CAPTURE_POLICY_V4, METRIC, PAIR_POLICY,
    audit_exhaustive_pose_pairs,
    evaluate_under_pose_protocol, main, pinned_pose_protocol,
    validate_pose_protocol,
)
from benchmark_reid_scores import _parse
from test_audit_paired_pose_retention import scenario
from test_run_paired_pose_benchmark import frozen


def pin(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def prepare(root):
    argv, paths = frozen(root)
    with paths["scores"].open(newline="", encoding="utf-8") as inp:
        rows = list(csv.DictReader(inp))
    found = {}
    for row in rows:
        found[(row["split"], row["probe_uid"])] = {
            key: row[key] for key in TRUTH_FIELDS
        }
    truth = paths["truth"] = Path(root) / "reviewed-truth.csv"
    write_csv(truth, TRUTH_FIELDS, list(found.values()))
    argv.extend(["--truth", str(truth), "--truth-sha256", pin(truth)])
    spec = {
        "schema_version": 1,
        "study_uid": "MadeUp_Study_01",
        "model_uid": "MadeUp_Model_01",
        "model_checkpoint_sha256": "a" * 64,
        "frozen_truth_sha256": pin(truth),
        "frozen_roster_sha256": pin(paths["roster"]),
        "frozen_provenance_sha256": pin(paths["provenance"]),
        "frozen_pairs_sha256": pin(paths["pairs"]),
        "top_two_margin": 0,
        "min_known_person_pairs": 20,
        "min_condition_pairs": 10,
        "rear_unknown_wilson95_budget": .05,
        "primary_endpoint": METRIC,
        "calibration_policy": CALIBRATION,
    }
    protocol = paths["protocol"] = Path(root) / "pose-protocol.json"
    write_protocol(protocol, spec)
    argv.extend(["--protocol", str(protocol), "--protocol-sha256",
                 pin(protocol)])
    return argv, paths, spec


def write_csv(path, fields, rows):
    with path.open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=fields, lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)


def write_protocol(path, spec):
    path.write_bytes(json.dumps(spec, sort_keys=True, separators=(",", ":")).encode())


def arg_replace(argv, name, value):
    argv[argv.index("--" + name) + 1] = value


def call(argv):
    out, err = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
        code = main(argv)
    return code, out.getvalue(), err.getvalue()


class PreregisteredPoseStudyTests(unittest.TestCase):
    def test_independently_pinned_truth_and_four_frozen_manifests(self):
        with tempfile.TemporaryDirectory(prefix="do-not-log-private-pose-") as root:
            argv, paths, spec = prepare(root)
            code, stdout, stderr = call(argv)
            self.assertEqual(code, 0, stderr)
            self.assertEqual(stderr, "")
            data = json.loads(stdout)
            self.assertEqual(data["overall"]["reviewed_known_person_pairs"], 25)
            self.assertEqual(data["overall"]["front_correct_rear_wrong"], 5)
            self.assertEqual(data["rear_unknown_controls"]["holdout"]["overall"]
                             ["groups_with_false_offers"], 4)
            audit = data["protocol_audit"]
            self.assertTrue(audit["frozen_truth_metadata_matches_model_scores"])
            self.assertTrue(audit["raw_manifest_digests_checked_by_cli"])
            self.assertTrue(audit["rear_unknown_safety_policy_locked"])
            self.assertFalse(audit["external_pre_holdout_protocol_commitment_verified"])
            self.assertFalse(audit["model_checkpoint_bytes_or_execution_verified"])
            self.assertFalse(data["auto_confirm_allowed"])
            for secret in ("HoldKnown", "HoldProbeK", "Lineage_", "Anonymous_", root):
                self.assertNotIn(secret, stdout)
            self.assertEqual(pinned_pose_protocol(paths["protocol"], pin(paths["protocol"])), spec)

    def test_frozen_pair_changes_still_fail_when_user_rehashes_both_files(self):
        with tempfile.TemporaryDirectory() as root:
            argv, paths, _ = prepare(root)
            path = paths["pairs"]
            with path.open("a", encoding="utf-8") as f:
                f.write("HoldFrontK_24,HoldProbeK_0\n")
            arg_replace(argv, "pairs-sha256", pin(path))
            code, stdout, stderr = call(argv)
            self.assertEqual(code, 2)
            self.assertEqual(stdout, "")
            self.assertNotIn(root, stderr)

    def test_independent_truth_mismatch_rejected_under_fully_rehashed_protocol(self):
        with tempfile.TemporaryDirectory() as root:
            argv, paths, spec = prepare(root)
            truth = paths["truth"]
            with truth.open(newline="", encoding="utf-8") as f:
                rows = list(csv.DictReader(f))
            target = next(x for x in rows if x["probe_uid"] == "HoldProbeK_0")
            target["condition"] = "partial"
            write_csv(truth, TRUTH_FIELDS, rows)
            spec["frozen_truth_sha256"] = pin(truth)
            write_protocol(paths["protocol"], spec)
            arg_replace(argv, "truth-sha256", pin(truth))
            arg_replace(argv, "protocol-sha256", pin(paths["protocol"]))
            self.assertEqual(call(argv)[0], 2)

    def test_fewer_than_twenty_registered_pairs_cannot_be_patched_at_execution(self):
        with tempfile.TemporaryDirectory() as root:
            argv, paths, spec = prepare(root)
            with paths["pairs"].open(newline="", encoding="utf-8") as f:
                rows = list(csv.DictReader(f))
            write_csv(paths["pairs"], ("front_probe_uid", "rear_probe_uid"), rows[:19])
            spec["frozen_pairs_sha256"] = pin(paths["pairs"])
            write_protocol(paths["protocol"], spec)
            arg_replace(argv, "pairs-sha256", pin(paths["pairs"]))
            arg_replace(argv, "protocol-sha256", pin(paths["protocol"]))
            self.assertEqual(call(argv)[0], 2)

    def test_frozen_model_margin_policy_and_cohort_settings_reject_relaxation(self):
        with tempfile.TemporaryDirectory() as root:
            argv, paths, spec = prepare(root)
            for key, bad in (
                ("schema_version", True), ("study_uid", "../../home"),
                ("model_uid", "UNKNOWN"),
                ("model_checkpoint_sha256", "short"),
                ("top_two_margin", float("nan")),
                ("top_two_margin", True),
                ("min_known_person_pairs", 1),
                ("min_known_person_pairs", True),
                ("min_condition_pairs", 1),
                ("rear_unknown_wilson95_budget", .2),
                ("primary_endpoint", "posthoc_best_accuracy"),
                ("calibration_policy", "optimize_holdout"),
            ):
                with self.subTest(key=key), self.assertRaises(ValueError):
                    validate_pose_protocol(dict(spec, **{key: bad}))
            with self.assertRaises(ValueError):
                validate_pose_protocol(dict(spec, subject_full_name="secret"))
            self.assertEqual(call(argv)[0], 0)

    def test_v3_component_unknown_calibration_is_strict_and_v2_backwards_compatible(self):
        with tempfile.TemporaryDirectory(prefix="private-v3-component-protocol-") as root:
            argv, paths, spec = prepare(root)
            spec.update(schema_version=3, pair_selection_policy=PAIR_POLICY,
                        calibration_policy=CALIBRATION_V3)
            write_protocol(paths["protocol"], spec)
            arg_replace(argv, "protocol-sha256", pin(paths["protocol"]))
            rc, output, err = call(argv)
            self.assertEqual(rc, 0, err)
            accepted = json.loads(output)
            self.assertTrue(accepted["protocol_audit"][
                "strict_rear_unknown_capture_component_calibration_enforced"])
            self.assertTrue(accepted[
                "rear_unknown_component_calibration_wilson95_gate_enforced"])
            self.assertTrue(accepted["rear_unknown_controls"]["calibration"][
                "component_calibration_risk_gate_enforced"])
            self.assertFalse(accepted["rear_unknown_controls"]["holdout"][
                "component_calibration_risk_gate_enforced"])
            self.assertFalse(accepted["auto_confirm_allowed"])

            with paths["provenance"].open(newline="", encoding="utf-8") as handle:
                provenance = list(csv.DictReader(handle))
            for row in provenance:
                if (row["split"] == "calibration"
                        and row["probe_uid"].startswith("CalProbeU_")):
                    row["photo_lineage_uid"] = "OneDeclaredPhotoFamily"
            from verify_split_provenance import FIELDS as PROVENANCE_FIELDS
            write_csv(paths["provenance"], PROVENANCE_FIELDS, provenance)
            spec["frozen_provenance_sha256"] = pin(paths["provenance"])
            write_protocol(paths["protocol"], spec)
            arg_replace(argv, "provenance-sha256", pin(paths["provenance"]))
            arg_replace(argv, "protocol-sha256", pin(paths["protocol"]))
            rc, output, err = call(argv)
            self.assertEqual(rc, 2)
            self.assertEqual(output, "")
            self.assertNotIn(root, err)
            self.assertNotIn("OneDeclaredPhotoFamily", err)
            # The historic v2 is *not* silently retroactively changed.
            spec["schema_version"] = 2
            spec["calibration_policy"] = CALIBRATION
            write_protocol(paths["protocol"], spec)
            arg_replace(argv, "protocol-sha256", pin(paths["protocol"]))
            rc, output, err = call(argv)
            self.assertEqual(rc, 0, err)
            historic = json.loads(output)
            self.assertFalse(historic["protocol_audit"][
                "strict_rear_unknown_capture_component_calibration_enforced"])
            self.assertEqual(historic["calibration_locked_similarity_floor"],
                             accepted["calibration_locked_similarity_floor"])
            self.assertTrue(historic["rear_unknown_controls"]["calibration"][
                "capture_dependency_component_safety"][
                "component_risk_exceeds_frozen_5pct_ceiling_diagnostic"])
            self.assertFalse(historic["auto_confirm_allowed"])

    def test_v3_rejects_policy_downgrades_even_when_protocol_rehashed(self):
        with tempfile.TemporaryDirectory() as root:
            argv, paths, spec = prepare(root)
            spec.update(schema_version=3, pair_selection_policy=PAIR_POLICY,
                        calibration_policy=CALIBRATION_V3)
            self.assertEqual(validate_pose_protocol(spec), spec)
            for key, value in (
                ("calibration_policy", CALIBRATION),
                ("schema_version", 2),
                ("pair_selection_policy", "score_chosen_pose_pair"),
                ("rear_unknown_wilson95_budget", .08),
                ("min_condition_pairs", 1),
            ):
                with self.subTest(field=key), self.assertRaises(ValueError):
                    validate_pose_protocol(dict(spec, **{key: value}))
            write_protocol(paths["protocol"], spec)
            arg_replace(argv, "protocol-sha256", pin(paths["protocol"]))
            self.assertEqual(call(argv)[0], 0)

    def test_v4_known_pose_capture_components_are_frozen_separately_from_person_count(self):
        from verify_split_provenance import FIELDS as PROVENANCE_FIELDS
        with tempfile.TemporaryDirectory(prefix="private-v4-known-pose-") as root:
            argv, paths, spec = prepare(root)
            spec.update(
                schema_version=4, pair_selection_policy=PAIR_POLICY,
                calibration_policy=CALIBRATION_V3,
                known_capture_dependency_policy=KNOWN_CAPTURE_POLICY_V4)
            write_protocol(paths["protocol"], spec)
            arg_replace(argv, "protocol-sha256", pin(paths["protocol"]))
            code, output, err = call(argv)
            self.assertEqual(code, 0, err)
            independent = json.loads(output)
            self.assertEqual(independent["overall"]["reviewed_known_person_pairs"], 25)
            self.assertEqual(independent["paired_multi_axis_capture_dependency_safety"][
                "connected_event_lineage_session_components"], 25)
            self.assertTrue(independent["protocol_audit"][
                "strict_known_pose_capture_component_minimum_enforced"])
            self.assertTrue(independent["protocol_audit"][
                "strict_rear_unknown_capture_component_calibration_enforced"])
            self.assertFalse(independent["auto_confirm_allowed"])
            with paths["provenance"].open(newline="", encoding="utf-8") as handle:
                rows = list(csv.DictReader(handle))
            for row in rows:
                if row["split"] != "holdout":
                    continue
                if row["probe_uid"].startswith("HoldFrontK_"):
                    row["event_cluster_uid"] = "OneCrowdedFrontEvent"
                elif row["probe_uid"].startswith("HoldProbeK_"):
                    row["event_cluster_uid"] = "OneCrowdedRearEvent"
            write_csv(paths["provenance"], PROVENANCE_FIELDS, rows)
            spec["frozen_provenance_sha256"] = pin(paths["provenance"])
            write_protocol(paths["protocol"], spec)
            arg_replace(argv, "provenance-sha256", pin(paths["provenance"]))
            arg_replace(argv, "protocol-sha256", pin(paths["protocol"]))
            code, output, err = call(argv)
            self.assertEqual(code, 2)
            self.assertEqual(output, "")
            for private in (root, "OneCrowdedFrontEvent", "OneCrowdedRearEvent"):
                self.assertNotIn(private, err)
            # V3 is preserved as registered: the known-pose component
            # evidence appears as a diagnostic, NOT a retroactive gate.
            spec["schema_version"] = 3
            spec.pop("known_capture_dependency_policy")
            write_protocol(paths["protocol"], spec)
            arg_replace(argv, "protocol-sha256", pin(paths["protocol"]))
            code, output, err = call(argv)
            self.assertEqual(code, 0, err)
            historic = json.loads(output)
            self.assertEqual(historic["paired_multi_axis_capture_dependency_safety"][
                "connected_event_lineage_session_components"], 1)
            self.assertFalse(historic["protocol_audit"][
                "strict_known_pose_capture_component_minimum_enforced"])
            self.assertEqual(historic["calibration_locked_similarity_floor"],
                             independent["calibration_locked_similarity_floor"])

    def test_v4_rejects_substituted_or_missing_capture_component_policy(self):
        with tempfile.TemporaryDirectory() as root:
            argv, paths, spec = prepare(root)
            spec.update(
                schema_version=4, pair_selection_policy=PAIR_POLICY,
                calibration_policy=CALIBRATION_V3,
                known_capture_dependency_policy=KNOWN_CAPTURE_POLICY_V4)
            self.assertEqual(validate_pose_protocol(spec), spec)
            for key, value in (
                ("known_capture_dependency_policy", "minimum_1_component"),
                ("known_capture_dependency_policy", True),
                ("pair_selection_policy", "model_selected_easy_pair"),
                ("calibration_policy", CALIBRATION),
                ("min_known_person_pairs", 1),
            ):
                with self.subTest(field=key), self.assertRaises(ValueError):
                    validate_pose_protocol(dict(spec, **{key: value}))
            missing = dict(spec)
            missing.pop("known_capture_dependency_policy")
            with self.assertRaises(ValueError):
                validate_pose_protocol(missing)
            weak_v3 = dict(spec, schema_version=3)
            with self.assertRaises(ValueError):
                validate_pose_protocol(weak_v3)
            write_protocol(paths["protocol"], spec)
            arg_replace(argv, "protocol-sha256", pin(paths["protocol"]))
            self.assertEqual(call(argv)[0], 0)

    def test_v2_rejects_selectively_omitted_hard_eligible_subject_even_if_rehashed(self):
        with tempfile.TemporaryDirectory() as root:
            argv, paths, spec = prepare(root)
            spec.update(schema_version=2, pair_selection_policy=PAIR_POLICY)
            write_protocol(paths["protocol"], spec)
            arg_replace(argv, "protocol-sha256", pin(paths["protocol"]))
            valid, stdout, stderr = call(argv)
            self.assertEqual(valid, 0, stderr)
            report = json.loads(stdout)
            selection = report["pose_selection_audit"]
            self.assertTrue(selection["pair_selection_model_score_independent"])
            self.assertTrue(selection["known_truth_to_reviewed_subject_mapping_one_to_one"])
            self.assertEqual(selection["known_people_with_eligible_distinct_capture_pose_pair"], 25)
            self.assertEqual(selection["known_people_without_eligible_pose_pair"], 0)
            self.assertTrue(report["protocol_audit"]["deterministic_eligible_pair_inventory_enforced"])
            with paths["pairs"].open(newline="", encoding="utf-8") as src:
                pairs = list(csv.DictReader(src))
            write_csv(paths["pairs"], ("front_probe_uid", "rear_probe_uid"), pairs[1:])
            spec["frozen_pairs_sha256"] = pin(paths["pairs"])
            write_protocol(paths["protocol"], spec)
            arg_replace(argv, "pairs-sha256", pin(paths["pairs"]))
            arg_replace(argv, "protocol-sha256", pin(paths["protocol"]))
            self.assertEqual(call(argv)[0], 2)
            # The legacy version-1 protocol remains compatible and can
            # evaluate a reviewer-chosen subset; only v2 enforces coverage.
            spec.pop("pair_selection_policy")
            spec["schema_version"] = 1
            write_protocol(paths["protocol"], spec)
            arg_replace(argv, "protocol-sha256", pin(paths["protocol"]))
            self.assertEqual(call(argv)[0], 0)

    def test_v2_refuses_different_known_people_sharing_one_subject_cluster(self):
        scores, provenance, _, pairs = scenario()
        for row in provenance:
            if row["probe_uid"] in {"HoldFrontK_1", "HoldProbeK_1"}:
                row["subject_cluster_uid"] = "HoldKnown_0"
        # Each truth still has one internally consistent reviewed group,
        # and all 25 known people still have eligible front/rear pairs.
        # The previously missing reverse direction must reject two truth
        # identities claiming the same reviewed physical person.
        with self.assertRaisesRegex(ValueError, "aliases distinct truth"):
            audit_exhaustive_pose_pairs(_parse(scores), provenance, pairs)

    def test_v2_alias_still_fails_after_provenance_and_protocol_rehash(self):
        with tempfile.TemporaryDirectory(prefix="private-two-truths-") as root:
            argv, paths, spec = prepare(root)
            spec.update(schema_version=2, pair_selection_policy=PAIR_POLICY)
            with paths["provenance"].open(newline="", encoding="utf-8") as f:
                source_rows = list(csv.DictReader(f))
            for row in source_rows:
                if row["probe_uid"] in {"HoldFrontK_1", "HoldProbeK_1"}:
                    row["subject_cluster_uid"] = "HoldKnown_0"
            write_csv(paths["provenance"], (
                "probe_uid", "split", "photo_lineage_uid", "event_cluster_uid",
                "subject_cluster_uid"), source_rows)
            spec["frozen_provenance_sha256"] = pin(paths["provenance"])
            write_protocol(paths["protocol"], spec)
            arg_replace(argv, "provenance-sha256", pin(paths["provenance"]))
            arg_replace(argv, "protocol-sha256", pin(paths["protocol"]))
            code, stdout, stderr = call(argv)
            self.assertEqual(code, 2)
            self.assertEqual(stdout, "")
            self.assertNotIn(root, stderr)
            self.assertNotIn("HoldKnown_0", stderr)

    def test_v2_first_valid_lexical_pair_independent_of_model_score(self):
        scores, provenance, roster, pairs = scenario()
        old_rear = "HoldProbeK_0"
        alternative = "AFirstRear_0"
        replacements = []
        for row in scores:
            if row["probe_uid"] == old_rear:
                copy_row = dict(row, probe_uid=alternative,
                                session_uid="AlternateSession_0",
                                similarity=".01" if row["candidate_uid"] == "HoldKnown_0"
                                else ".95")
                replacements.append(copy_row)
        scores.extend(replacements)
        source = next(row for row in provenance if row["probe_uid"] == old_rear)
        provenance.append(dict(source, probe_uid=alternative,
                               event_cluster_uid="AlternativeEvent_0",
                               photo_lineage_uid="AlternativeExposure_0"))
        with self.assertRaisesRegex(ValueError, "not exhaustive/deterministic"):
            audit_exhaustive_pose_pairs(_parse(scores), provenance, pairs)
        selected = [dict(pair) for pair in pairs]
        selected[0]["rear_probe_uid"] = alternative
        baseline = audit_exhaustive_pose_pairs(_parse(scores), provenance, selected)
        self.assertEqual(baseline["known_people_with_eligible_distinct_capture_pose_pair"], 25)
        for row in scores:
            if row["probe_uid"] == alternative:
                row["similarity"] = ".99" if row["candidate_uid"] == "HoldKnown_0" else ".01"
        self.assertEqual(baseline, audit_exhaustive_pose_pairs(
            _parse(scores), list(reversed(provenance)), list(reversed(selected))))

    def test_v2_exposes_eligible_hard_rear_subjects_missing_from_lexical_pair(self):
        scores, provenance, _, pairs = scenario()
        originals = [row for row in scores if row["probe_uid"] == "HoldProbeK_0"]
        self.assertTrue(originals)
        # Both additional rear captures are independently sourced and
        # reviewed, but their opaque IDs sort AFTER the selected easy back.
        extra = (
            ("ZHardOccluded_0", "back+occluded+appearance_shift"),
            ("ZHardPartial_0", "partial+appearance_shift"),
        )
        for uid, condition in extra:
            for row in originals:
                scores.append(dict(row, probe_uid=uid, condition=condition,
                                   session_uid=f"IndependentSession_{uid}"))
            origin = next(x for x in provenance
                          if x["probe_uid"] == "HoldProbeK_0")
            provenance.append(dict(
                origin, probe_uid=uid,
                photo_lineage_uid=f"NewExposure_{uid}",
                event_cluster_uid=f"NewEvent_{uid}"))
        audit = audit_exhaustive_pose_pairs(_parse(scores), provenance, pairs)
        self.assertEqual(audit["known_people_with_eligible_distinct_capture_pose_pair"], 25)
        condition = audit["rear_condition_person_coverage_for_deterministic_pair"]
        for tag in ("partial", "occluded", "appearance_shift"):
            self.assertEqual(condition[tag], {
                "eligible_people": 1, "selected_people": 0,
                "eligible_people_not_selected_for_this_tag": 1,
            }, tag)
        self.assertEqual(condition["back"], {
            "eligible_people": 25, "selected_people": 25,
            "eligible_people_not_selected_for_this_tag": 0,
        })
        self.assertTrue(audit["rear_condition_coverage_uses_all_eligible_declared_capture_pairs"])
        self.assertTrue(audit["condition_coverage_counts_are_overlapping_not_independent"])
        self.assertFalse(audit["actual_capture_independence_or_sample_representativeness_proven"])
        self.assertEqual(audit, audit_exhaustive_pose_pairs(
            _parse(list(reversed(scores))),
            list(reversed(provenance)), list(reversed(pairs))))

    def test_exact_intersection_cannot_be_inferred_from_disjoint_hard_photos(self):
        scores, provenance, _, pairs = scenario()
        originals = [row for row in scores if row["probe_uid"] == "HoldProbeK_0"]
        source = next(row for row in provenance if row["probe_uid"] == "HoldProbeK_0")
        # Two genuinely *different* rear captures of the same known person:
        # one back+occluded, the other back+appearance_shift. No single image
        # has the three-tag intersection back+occluded+appearance_shift.
        for uid, condition in (("ZRearOccluded_0", "back+occluded"),
                               ("ZRearShifted_0", "back+appearance_shift")):
            for row in originals:
                scores.append(dict(
                    row, probe_uid=uid, condition=condition,
                    session_uid="DistinctSession_" + uid))
            provenance.append(dict(
                source, probe_uid=uid, photo_lineage_uid="DistinctLineage_" + uid,
                event_cluster_uid="DistinctEvent_" + uid))
        result = audit_exhaustive_pose_pairs(_parse(scores), provenance, pairs)
        marginal = result["rear_condition_person_coverage_for_deterministic_pair"]
        exact = result["rear_exact_condition_person_coverage_for_deterministic_pair"]
        self.assertEqual(marginal["occluded"]["eligible_people"], 1)
        self.assertEqual(marginal["appearance_shift"]["eligible_people"], 1)
        self.assertEqual(exact["back+occluded"], {
            "eligible_people": 1, "selected_people": 0,
            "eligible_people_not_selected_for_this_exact_condition": 1,
        })
        self.assertEqual(exact["appearance_shift+back"], {
            "eligible_people": 1, "selected_people": 0,
            "eligible_people_not_selected_for_this_exact_condition": 1,
        })
        self.assertNotIn("appearance_shift+back+occluded", exact)
        self.assertEqual(exact["back"]["selected_people"], 25)
        # Report exposes public condition names + counts, never people,
        # original image handles, sessions, exposures or model outcomes.
        public_report = json.dumps(result, sort_keys=True)
        for private in ("ZRearOccluded_0", "ZRearShifted_0", "HoldProbeK_0",
                        "DistinctSession_", "DistinctLineage_", "HoldKnown_0"):
            self.assertNotIn(private, public_report)
        self.assertTrue(
            result["exact_condition_cooccurrence_verified_on_one_eligible_rear_probe"])
        self.assertEqual(result, audit_exhaustive_pose_pairs(
            _parse(list(reversed(scores))), list(reversed(provenance)),
            list(reversed(pairs))))

    def test_exact_multi_tag_selected_rear_is_reported_once_per_person(self):
        scores, provenance, _, pairs = scenario()
        for row in scores:
            if row["probe_uid"] == "HoldProbeK_0":
                row["condition"] = "back+occluded"
        result = audit_exhaustive_pose_pairs(_parse(scores), provenance, pairs)
        exact = result["rear_exact_condition_person_coverage_for_deterministic_pair"]
        self.assertEqual(exact["back+occluded"], {
            "eligible_people": 1, "selected_people": 1,
            "eligible_people_not_selected_for_this_exact_condition": 0,
        })
        self.assertEqual(exact["back"]["selected_people"], 24)
        marginal = result["rear_condition_person_coverage_for_deterministic_pair"]
        self.assertEqual(marginal["occluded"]["selected_people"], 1)
        self.assertEqual(marginal["back"]["selected_people"], 25)
        self.assertFalse(result[
            "actual_capture_independence_or_sample_representativeness_proven"])

    def test_v2_does_not_count_nonindependent_hard_rear_condition(self):
        scores, provenance, _, pairs = scenario()
        originals = [row for row in scores if row["probe_uid"] == "HoldProbeK_0"]
        for row in originals:
            scores.append(dict(row, probe_uid="ZSameEventHard_0",
                               condition="back+occluded",
                               session_uid="NewSessionHard_0"))
        source = next(x for x in provenance if x["probe_uid"] == "HoldProbeK_0")
        front = next(x for x in provenance if x["probe_uid"] == "HoldFrontK_0")
        # Different session and photo lineage alone are not enough.
        provenance.append(dict(source, probe_uid="ZSameEventHard_0",
                               photo_lineage_uid="OtherPhotoHard_0",
                               event_cluster_uid=front["event_cluster_uid"]))
        audit = audit_exhaustive_pose_pairs(_parse(scores), provenance, pairs)
        occluded = audit["rear_condition_person_coverage_for_deterministic_pair"]["occluded"]
        self.assertEqual(occluded, {
            "eligible_people": 0, "selected_people": 0,
            "eligible_people_not_selected_for_this_tag": 0,
        })
        self.assertNotIn("back+occluded",
                         audit["rear_exact_condition_person_coverage_for_deterministic_pair"])

    def test_v2_rejects_unreviewed_and_conflicting_subject_provenance(self):
        scores, provenance, _, pairs = scenario()
        target = next(row for row in provenance if row["probe_uid"] == "HoldFrontK_0")
        for bad in ("UNREVIEWED", "ContradictoryKnownSubject"):
            updated = [dict(row) for row in provenance]
            next(row for row in updated if row["probe_uid"] == target["probe_uid"])[
                "subject_cluster_uid"] = bad
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                audit_exhaustive_pose_pairs(_parse(scores), updated, pairs)

    def test_v2_exhaustive_algorithm_fails_closed_on_quadratic_cohort(self):
        # One pathological subject whose 500 front + 500 rear captures
        # all share a session/event: no independently valid pair exists.
        # A finite work cap is safer than silently sampling easy examples.
        trials, sources = [], []
        for i in range(500):
            for pose in ("front", "back"):
                uid = f"Opaque_{pose}_{i}"
                trials.append((uid, "SharedSession", pose, "KnownOne",
                               .1, "KnownOne", False, None))
                sources.append(dict(
                    split="holdout", probe_uid=uid,
                    photo_lineage_uid=f"Lineage_{pose}_{i}",
                    event_cluster_uid="SharedEvent",
                    subject_cluster_uid="KnownSubjectOne"))
        with self.assertRaisesRegex(ValueError, "comparison budget"):
            audit_exhaustive_pose_pairs(
                {"holdout": trials, "calibration": []}, sources, [])

    def test_invalid_json_duplicate_keys_symlinks_and_score_pin(self):
        with tempfile.TemporaryDirectory(prefix="private-cohort-folder-") as root:
            argv, paths, _ = prepare(root)
            protocol = paths["protocol"]
            raw = protocol.read_bytes()
            protocol.write_bytes(raw[:-1] + b',"study_uid":"Hidden"}')
            arg_replace(argv, "protocol-sha256", pin(protocol))
            code, stdout, stderr = call(argv)
            self.assertEqual(code, 2)
            self.assertNotIn(root, stderr)
            protocol.write_bytes(raw)
            arg_replace(argv, "protocol-sha256", pin(protocol))
            link = Path(root) / "protocol-link.json"
            link.symlink_to(protocol)
            arg_replace(argv, "protocol", str(link))
            self.assertEqual(call(argv)[0], 2)
            arg_replace(argv, "protocol", str(protocol))
            score = paths["scores"]
            score.write_bytes(score.read_bytes() + b"appended")
            self.assertEqual(call(argv)[0], 2)


if __name__ == "__main__":
    unittest.main()
