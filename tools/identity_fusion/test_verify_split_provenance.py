#!/usr/bin/env python3
"""Fictional score/provenance fixtures, no photographs or pretrained models."""
from copy import deepcopy
import csv
import io
import json
import tempfile
from pathlib import Path
import unittest

from benchmark_reid_scores import _parse, benchmark_model_scores, main, FIELDS as SCORE_FIELDS
from verify_split_provenance import verify_split_provenance, FIELDS


def made_provenance(score_rows):
    unique = {}
    for row in score_rows:
        split, probe = row["split"], row["probe_uid"]
        if (split, probe) not in unique:
            # All labels are synthetic opaque handles; no image paths.
            known = row["truth_uid"] != "UNKNOWN"
            unique[(split, probe)] = {
                "probe_uid": probe, "split": split,
                "photo_lineage_uid": "Lineage_" + probe,
                "event_cluster_uid": "Event_" + probe,
                "subject_cluster_uid": "Subject_" + probe if known else "UNREVIEWED",
            }
    return list(unique.values())


class SplitProvenanceTests(unittest.TestCase):
    def test_fictional_full_coverage_and_report_contains_no_ids(self):
        from test_benchmark_reid_scores import fixture
        scores = fixture()
        provenance = made_provenance(scores)
        # Two variants of the same photo family INSIDE calibration are fine.
        lineage = next(r["photo_lineage_uid"] for r in provenance
                       if r["split"] == "calibration")
        other = next(r for r in provenance if r["split"] == "calibration"
                     and r["photo_lineage_uid"] != lineage)
        other["photo_lineage_uid"] = lineage
        report = verify_split_provenance(provenance, _parse(scores))
        self.assertEqual(report["scored_probes"], 250)
        self.assertEqual(report["distinct_photo_lineages"], 249)
        self.assertEqual(report["lineages_with_multiple_probes"], 1)
        self.assertEqual(report["unreviewed_subject_probes"], 125)
        self.assertFalse(report["physical_independence_proven"])
        self.assertFalse(report["auto_confirm_allowed"])
        output = json.dumps(report)
        for private in ("CalProbeK", "HoldProbeK", "Lineage_Cal", "Event_Hold",
                        "Subject_", "probe_uid", "/storage/emulated/0"):
            self.assertNotIn(private, output)
        full = benchmark_model_scores(scores, provenance_rows=provenance)
        # The model benchmark enriches the base integrity report with the
        # event-aware calibration and holdout diagnostics after thresholding.
        for key, value in report.items():
            self.assertEqual(full["provenance_partition_audit"][key], value)
        self.assertTrue(
            full["provenance_partition_audit"]["event_cluster_constraints_enforced"]
        )
        self.assertEqual(full["similarity_floor"],
                         benchmark_model_scores(scores)["similarity_floor"])
        self.assertEqual(benchmark_model_scores(scores)["provenance_partition_audit"]["status"],
                         "not_supplied")

    def test_exact_probe_reuse_across_split_rejected_without_sidecar(self):
        from test_benchmark_reid_scores import fixture
        scores = fixture()
        for row in scores:
            if row["probe_uid"] == "HoldProbeK_0":
                row["probe_uid"] = "CalProbeK_0"
        with self.assertRaisesRegex(ValueError, "probe"):
            benchmark_model_scores(scores)

    def test_photo_event_and_subject_leakage_caught_despite_different_probe_ids(self):
        from test_benchmark_reid_scores import fixture
        scores = fixture()
        original = made_provenance(scores)
        cal = next(x for x in original if x["split"] == "calibration"
                   and x["subject_cluster_uid"] != "UNREVIEWED")
        hold = next(x for x in original if x["split"] == "holdout"
                    and x["subject_cluster_uid"] != "UNREVIEWED")
        for field in ("photo_lineage_uid", "event_cluster_uid", "subject_cluster_uid"):
            sidecar = deepcopy(original)
            item = next(x for x in sidecar if x["probe_uid"] == hold["probe_uid"])
            item[field] = cal[field]
            with self.subTest(field=field):
                with self.assertRaisesRegex(ValueError, "leaks"):
                    benchmark_model_scores(scores, provenance_rows=sidecar)

    def test_within_partition_two_known_truths_cannot_alias_one_reviewed_subject(self):
        from test_benchmark_reid_scores import fixture
        scores = fixture()
        prov = made_provenance(scores)
        a = next(x for x in prov if x["probe_uid"] == "CalProbeK_0")
        b = next(x for x in prov if x["probe_uid"] == "CalProbeK_1")
        self.assertNotEqual(a["subject_cluster_uid"], b["subject_cluster_uid"])
        b["subject_cluster_uid"] = a["subject_cluster_uid"]
        # The two known truth IDs are different, yet calibration and
        # holdout have NO shared subject/event/lineage metadata. The
        # original split-only gate would have accepted this alias.
        with self.assertRaisesRegex(ValueError, "aliases distinct known truth"):
            verify_split_provenance(prov, _parse(scores))
        with self.assertRaisesRegex(ValueError, "aliases distinct known truth"):
            benchmark_model_scores(scores, provenance_rows=prov)

    def test_within_partition_reviewed_known_unknown_collision_always_rejected(self):
        from test_benchmark_reid_scores import fixture
        scores = fixture()
        base = made_provenance(scores)
        known = next(x for x in base if x["probe_uid"] == "CalProbeK_0")
        unknown = next(x for x in base if x["probe_uid"] == "CalProbeU_0")
        unknown["subject_cluster_uid"] = known["subject_cluster_uid"]
        # The general gate must guard even without the stricter
        # require_reviewed_unknown_subjects opt-in.
        with self.assertRaisesRegex(ValueError, "known and Unknown"):
            verify_split_provenance(base, _parse(scores))
        with self.assertRaisesRegex(ValueError, "known and Unknown"):
            benchmark_model_scores(scores, provenance_rows=base)

    def test_within_partition_same_known_truth_repeated_captures_are_permitted(self):
        from test_benchmark_reid_scores import fixture
        scores = fixture()
        # A second independently annotated known capture of the SAME
        # person can share the reviewed subject cluster.
        first = next(r for r in scores if r["probe_uid"] == "CalProbeK_0")
        added = [dict(row, probe_uid="CalProbeK_0_second",
                      session_uid="CalSessionK_0_second") for row in scores
                 if row["probe_uid"] == first["probe_uid"]]
        scores.extend(added)
        prov = made_provenance(scores)
        a = next(x for x in prov if x["probe_uid"] == "CalProbeK_0")
        b = next(x for x in prov if x["probe_uid"] == "CalProbeK_0_second")
        b["subject_cluster_uid"] = a["subject_cluster_uid"]
        result = verify_split_provenance(prov, _parse(scores))
        self.assertTrue(result["reviewed_subject_truth_coherence_enforced"])
        self.assertFalse(result["physical_independence_proven"])
        self.assertEqual(result["distinct_reviewed_subject_clusters"], 125)
        self.assertEqual(result["scored_probes"], 251)
        serialized = json.dumps(result)
        for private in ("CalProbeK_0", "Subject_Cal", "CalSession", "subject_cluster_uid"):
            self.assertNotIn(private, serialized)

    def test_one_known_truth_cannot_relabel_same_person_as_two_reviewed_subjects(self):
        from test_benchmark_reid_scores import fixture
        scores = fixture()
        extra = [dict(row, probe_uid="CalProbeK_0_later",
                      session_uid="CalSessionK_0_later")
                 for row in scores if row["probe_uid"] == "CalProbeK_0"]
        scores.extend(extra)
        prov = made_provenance(scores)
        # The synthetic sidecar assigns a fresh reviewed cluster for the
        # same known truth, even though source/event/session stay distinct.
        # This would inflate independent-group counts without reverse guard.
        original = next(x for x in prov if x["probe_uid"] == "CalProbeK_0")
        later = next(x for x in prov if x["probe_uid"] == "CalProbeK_0_later")
        self.assertNotEqual(original["subject_cluster_uid"],
                            later["subject_cluster_uid"])
        with self.assertRaisesRegex(ValueError, "known truth aliases distinct reviewed"):
            verify_split_provenance(prov, _parse(scores))
        with self.assertRaisesRegex(ValueError, "known truth aliases distinct reviewed"):
            benchmark_model_scores(scores, provenance_rows=prov)
        later["subject_cluster_uid"] = original["subject_cluster_uid"]
        accepted = verify_split_provenance(prov, _parse(scores))
        self.assertTrue(
            accepted["known_truth_to_reviewed_subject_mapping_one_to_one_enforced"])
        self.assertFalse(accepted["physical_independence_proven"])

    def test_unreviewed_additional_capture_not_assigned_a_guessed_subject(self):
        from test_benchmark_reid_scores import fixture
        scores = fixture()
        extra = [dict(row, probe_uid="CalProbeK_0_unreviewed",
                      session_uid="CalSessionK_0_unreviewed")
                 for row in scores if row["probe_uid"] == "CalProbeK_0"]
        scores.extend(extra)
        prov = made_provenance(scores)
        new_row = next(x for x in prov
                       if x["probe_uid"] == "CalProbeK_0_unreviewed")
        new_row["subject_cluster_uid"] = "UNREVIEWED"
        report = verify_split_provenance(prov, _parse(scores))
        self.assertEqual(report["unreviewed_subject_probes"], 126)
        self.assertTrue(report["known_truth_to_reviewed_subject_mapping_one_to_one_enforced"])
        self.assertFalse(report["physical_independence_proven"])
        self.assertFalse(report["auto_confirm_allowed"])

    def test_reject_incomplete_duplicate_untrusted_and_unreviewed_grouping(self):
        from test_benchmark_reid_scores import fixture
        scores = fixture()
        prov = made_provenance(scores)
        invalid = [
            prov[:-1],
            prov + [dict(prov[0])],
            [dict(x, **{"photo_lineage_uid": "UNREVIEWED"})
             if x is prov[0] else dict(x) for x in prov],
            [dict(x, **{"event_cluster_uid": "UNREVIEWED"})
             if x is prov[0] else dict(x) for x in prov],
            [dict(x, **{"probe_uid": "../PRIVATE.jpg"})
             if x is prov[0] else dict(x) for x in prov],
            [dict(x, **{"event_cluster_uid": "https://example.com/path"})
             if x is prov[0] else dict(x) for x in prov],
            [dict(x, **{"raw_face_embedding": "abc"})
             if x is prov[0] else dict(x) for x in prov],
        ]
        for idx, entry in enumerate(invalid):
            with self.subTest(case=idx):
                with self.assertRaises(ValueError):
                    benchmark_model_scores(scores, provenance_rows=entry)
        with self.assertRaises(ValueError):
            benchmark_model_scores(scores, require_provenance=True)
        self.assertTrue(benchmark_model_scores(
            scores, provenance_rows=prov, require_provenance=True
        )["provenance_partition_audit"]["all_scored_probes_covered"])

    def test_event_bursts_cannot_inflate_independent_calibration_trials(self):
        from test_benchmark_reid_scores import fixture
        scores = fixture()
        provenance = made_provenance(scores)
        clean = benchmark_model_scores(scores, provenance_rows=provenance)
        audited = clean["provenance_partition_audit"]
        self.assertTrue(audited["event_cluster_constraints_enforced"])
        self.assertEqual(
            audited["calibration_event_safety"]["known_event_clusters"], 100)
        self.assertEqual(
            audited["calibration_event_safety"]["unknown_event_clusters"], 100)
        self.assertEqual(
            audited["calibration_event_safety"]["unknown_false_offer_error_events"], 1)
        self.assertEqual(
            audited["holdout_event_safety"]["wrong_known_error_events"], 5)
        self.assertFalse(audited["calibration_event_safety"]["events_are_proven_independent"])
        self.assertEqual(clean["similarity_floor"], .91)

        # All 200 calibration photographs have distinct session labels, but
        # independently reviewed event annotations reveal only two events
        # (one known and one Unknown). Zero errors cannot meet a 5% Wilson
        # bound with one effective group, even though photo-level bounds pass.
        collapsed = deepcopy(provenance)
        for p in collapsed:
            if p["split"] == "calibration":
                p["event_cluster_uid"] = (
                    "SameKnownEvent" if p["probe_uid"].startswith("CalProbeK_")
                    else "SameUnknownEvent")
        with self.assertRaisesRegex(ValueError, "nontrivial"):
            benchmark_model_scores(scores, provenance_rows=collapsed)
        self.assertEqual(benchmark_model_scores(scores)["similarity_floor"], .91)

    def test_unknown_and_known_people_share_event_without_double_counting(self):
        from test_benchmark_reid_scores import fixture
        scores = fixture()
        provenance = made_provenance(scores)
        # An event may contain both a known and unknown person, but each
        # error class has its own per-event denominator.
        a = next(x for x in provenance if x["probe_uid"] == "CalProbeK_0")
        b = next(x for x in provenance if x["probe_uid"] == "CalProbeU_0")
        b["event_cluster_uid"] = a["event_cluster_uid"]
        report = benchmark_model_scores(scores, provenance_rows=provenance)
        audit = report["provenance_partition_audit"]["calibration_event_safety"]
        self.assertEqual(audit["known_event_clusters"], 100)
        self.assertEqual(audit["unknown_event_clusters"], 100)
        self.assertEqual(audit["unknown_false_offer_error_events"], 1)

    def test_reviewed_unknown_subjects_are_distinct_statistical_trials(self):
        from test_benchmark_reid_scores import fixture
        scores = fixture()
        provenance = made_provenance(scores)
        for row in provenance:
            if row["subject_cluster_uid"] == "UNREVIEWED":
                row["subject_cluster_uid"] = "Anonymous_" + row["probe_uid"]
        result = benchmark_model_scores(
            scores, provenance_rows=provenance,
            require_reviewed_unknown_subjects=True)
        details = result["provenance_partition_audit"]
        self.assertTrue(details["reviewed_unknown_constraints_enforced"])
        self.assertEqual(details["calibration_unknown_group_safety"]["groups"], 100)
        self.assertEqual(details["calibration_unknown_group_safety"]["groups_with_errors"], 1)
        self.assertEqual(details["holdout_unknown_group_safety"]["groups"], 25)
        self.assertEqual(details["holdout_unknown_group_safety"]["groups_with_errors"], 4)
        self.assertFalse(details["calibration_unknown_group_safety"]["independence_proven"])
        self.assertEqual(result["similarity_floor"], .91)

        # 100 nominal independent Unknown sessions actually depict the
        # same reviewed but *unnamed* person; no defensible 5% rate claim.
        collapsed = deepcopy(provenance)
        for row in collapsed:
            if row["split"] == "calibration" and row["probe_uid"].startswith("CalProbeU_"):
                row["subject_cluster_uid"] = "OneAnonymousPerson"
        with self.assertRaisesRegex(ValueError, "nontrivial"):
            benchmark_model_scores(scores, provenance_rows=collapsed,
                                   require_reviewed_unknown_subjects=True)

    def test_strict_unknown_mode_rejects_unreviewed_or_conflicted_subjects(self):
        from test_benchmark_reid_scores import fixture
        scores = fixture()
        base = made_provenance(scores)
        with self.assertRaisesRegex(ValueError, "provenance"):
            benchmark_model_scores(scores, require_reviewed_unknown_subjects=True)
        with self.assertRaisesRegex(ValueError, "Incomplete reviewed"):
            benchmark_model_scores(
                scores, provenance_rows=base, require_reviewed_unknown_subjects=True)
        for row in base:
            if row["subject_cluster_uid"] == "UNREVIEWED":
                row["subject_cluster_uid"] = "Anonymous_" + row["probe_uid"]
        unknown = next(r for r in base if r["probe_uid"] == "CalProbeU_0")
        unknown["subject_cluster_uid"] = "Subject_CalProbeK_0"
        with self.assertRaisesRegex(ValueError, "known and Unknown"):
            benchmark_model_scores(
                scores, provenance_rows=base, require_reviewed_unknown_subjects=True)
        with self.assertRaisesRegex(ValueError, "boolean"):
            benchmark_model_scores(scores, require_reviewed_unknown_subjects=1)


    def test_cli_sidecar_and_required_mode_do_not_log_private_identifiers(self):
        from test_benchmark_reid_scores import fixture
        scores = fixture()
        provenance = made_provenance(scores)
        with tempfile.TemporaryDirectory() as folder:
            scorepath = Path(folder) / "private_synthetic_scores.csv"
            prop = Path(folder) / "private_synthetic_provenance.csv"
            for file, headers, records in [
                (scorepath, SCORE_FIELDS, scores), (prop, FIELDS, provenance)
            ]:
                with file.open("w", newline="", encoding="utf-8") as handle:
                    writer = csv.DictWriter(handle, fieldnames=headers)
                    writer.writeheader()
                    writer.writerows(records)
            import contextlib
            import unittest.mock
            with contextlib.redirect_stdout(io.StringIO()) as output:
                rc = main([str(scorepath), "--provenance-csv", str(prop),
                           "--require-provenance"])
            self.assertEqual(rc, 0)
            report = json.loads(output.getvalue())
            self.assertTrue(report["provenance_partition_audit"]["all_scored_probes_covered"])
            prop.write_text("secrets!\n")
            with contextlib.redirect_stderr(io.StringIO()) as errors:
                rc = main([str(scorepath), "--provenance-csv", str(prop),
                           "--require-provenance"])
            self.assertEqual(rc, 2)
            self.assertNotIn(folder, errors.getvalue())
            self.assertNotIn("private", errors.getvalue())


if __name__ == "__main__":
    unittest.main(verbosity=2)
