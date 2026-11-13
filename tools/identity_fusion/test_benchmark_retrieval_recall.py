#!/usr/bin/env python3
"""Fully fictional top-k retrieval test cases with no biometric artifacts."""
import contextlib
import csv
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest

from benchmark_retrieval_recall import (
    TRUTH_FIELDS, RETRIEVED_FIELDS, ROSTER_FIELDS,
    benchmark_retrieval_recall, main,
)
from build_frozen_roster import build_frozen_roster


def fixture():
    """Frozen gallery is built BEFORE synthetic retrieval rankings."""
    probes, gallery, truth = [], [], []
    for split, prefix in (("calibration", "Cal"), ("holdout", "Hold")):
        for i in range(12):
            gallery.append({"split": split, "candidate_uid": prefix + "Known_" + str(i)})
            for kind in ("Known", "Unknown"):
                uid = prefix + kind + "Probe_" + str(i)
                probes.append({"split": split, "probe_uid": uid})
                truth.append({"split": split, "probe_uid": uid,
                              "truth_uid": prefix + "Known_" + str(i)
                              if kind == "Known" else "UNKNOWN",
                              "session_uid": "Session_" + uid})
    roster, metadata = build_frozen_roster(probes, gallery)
    retrieved = []
    for row in truth:
        prefix = "Cal" if row["split"] == "calibration" else "Hold"
        n = int(row["probe_uid"].rsplit("_", 1)[1])
        key = {"split": row["split"], "probe_uid": row["probe_uid"]}
        if row["truth_uid"] == "UNKNOWN":
            chosen = [prefix + "Known_0"] if n < 8 else []
        else:
            target = row["truth_uid"]
            others = [prefix + "Known_" + str(i) for i in range(12)
                      if prefix + "Known_" + str(i) != target]
            if n < 9:
                chosen = [target, others[0], others[1]]
            elif n == 9:
                chosen = [others[0], target, others[1]]
            elif n == 10:
                chosen = [others[0], others[1], target]
            else:
                chosen = others[:3]
        if not chosen:
            retrieved.append(dict(key, candidate_uid="NONE", rank="0"))
        for rank, candidate in enumerate(chosen, start=1):
            retrieved.append(dict(key, candidate_uid=candidate, rank=str(rank)))
    return truth, roster, retrieved


def write_csv(path, fields, rows):
    with path.open("w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fields, lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)
    return hashlib.sha256(path.read_bytes()).hexdigest()


class RetrievalRecallTests(unittest.TestCase):
    def test_locked_eligible_known_recall_is_separate_from_unknown_retrieval(self):
        truth, roster, retrieved = fixture()
        r = benchmark_retrieval_recall(truth, roster, retrieved, top_k=3)
        self.assertEqual(r["status"], "offline_retrieval_quality_only")
        for split in ("calibration", "holdout"):
            counts = r["by_split"][split]
            self.assertEqual(counts["known_probes"], 12)
            self.assertEqual(counts["known_probes_target_eligible"], 12)
            self.assertEqual(counts["known_eligible_target_returned_at_k"], 11)
            self.assertEqual(counts["known_eligible_target_returned_at_1"], 9)
            self.assertEqual(counts["known_eligible_recall_at_k"], round(11/12, 4))
            self.assertEqual(counts["known_persons_retrieved_on_every_probe"], 11)
            self.assertEqual(counts["unknown_probes"], 12)
            self.assertEqual(counts["unknown_probes_with_any_candidate_retrieved"], 8)
            self.assertEqual(counts["empty_retrieval_probes"], 4)
            self.assertEqual(counts["candidate_lists_at_k_cap"], 12)
        self.assertFalse(r["gallery_exhaustiveness_proven"])
        self.assertFalse(r["auto_confirm_allowed"])
        self.assertTrue(r["unknown_retrieval_is_not_a_false_identity_offer"])
        for private in ("CalKnown_", "Session_", "HoldKnown", "probe_uid",
                        "candidate_uid", "/storage/"):
            self.assertNotIn(private, json.dumps(r))
        self.assertEqual(r, benchmark_retrieval_recall(
            list(reversed(truth)), list(reversed(roster)),
            list(reversed(retrieved)), top_k=3))

    def test_missing_eligible_target_not_counted_as_retrieval_failure(self):
        truth, roster, retrieved = fixture()
        for row in truth:
            if row["probe_uid"] == "HoldKnownProbe_11":
                row["truth_uid"] = "HoldMissingKnown"
        result = benchmark_retrieval_recall(truth, roster, retrieved, top_k=3)
        c = result["by_split"]["holdout"]
        self.assertEqual(c["known_probes_target_absent_from_gallery"], 1)
        self.assertEqual(c["known_probes_target_eligible"], 11)
        self.assertEqual(c["known_eligible_target_returned_at_k"], 11)
        self.assertEqual(c["known_eligible_recall_at_k"], 1.0)
        self.assertEqual(c["known_probes"], 12)

    def test_missing_probe_unauthorized_candidate_and_noncontiguous_rank_rejected(self):
        truth, roster, retrieved = fixture()
        changes = [
            retrieved[:-1],
            retrieved + [dict(retrieved[0])],
            [dict(row, candidate_uid="UnexpectedPerson") if i == 0 else dict(row)
             for i, row in enumerate(retrieved)],
            [dict(row, rank="3") if i == 0 else dict(row)
             for i, row in enumerate(retrieved)],
            [dict(row, rank="03") if i == 0 else dict(row)
             for i, row in enumerate(retrieved)],
            [dict(row, unknown_path="/secret/media.jpg") if i == 0 else dict(row)
             for i, row in enumerate(retrieved)],
            [dict(row, candidate_uid="UNKNOWN") if i == 0 else dict(row)
             for i, row in enumerate(retrieved)],
        ]
        for i, modified in enumerate(changes):
            with self.subTest(case=i):
                with self.assertRaises(ValueError):
                    benchmark_retrieval_recall(truth, roster, modified, top_k=3)
        for k in (0, 101, True, "3", -1):
            with self.assertRaises(ValueError):
                benchmark_retrieval_recall(truth, roster, retrieved, top_k=k)

    def test_known_person_and_session_splits_must_remain_disjoint(self):
        truth, roster, retrieved = fixture()
        for field, bad in (
            ("truth_uid", "CalKnown_0"), ("session_uid", "Session_CalKnownProbe_0")
        ):
            changed = [dict(r) for r in truth]
            next(x for x in changed if x["probe_uid"] == "HoldKnownProbe_0")[field] = bad
            with self.subTest(field=field):
                with self.assertRaises(ValueError):
                    benchmark_retrieval_recall(changed, roster, retrieved, top_k=3)
        changed = [dict(r) for r in truth]
        changed[0]["probe_uid"] = changed[1]["probe_uid"]
        with self.assertRaises(ValueError):
            benchmark_retrieval_recall(changed, roster, retrieved, top_k=3)

    def test_malformed_gallery_or_false_independent_cohort_refused(self):
        truth, roster, retrieved = fixture()
        for rows in (
            roster[:-1],
            roster + [dict(roster[0])],
            [dict(r, candidate_uid="UNKNOWN") if i == 0 else dict(r)
             for i, r in enumerate(roster)],
            [dict(r, candidate_uid="CalKnown_0") if r["split"] == "holdout"
             and r["probe_uid"] == "HoldKnownProbe_0" and r["candidate_uid"] == "HoldKnown_0"
             else dict(r) for r in roster],
        ):
            with self.assertRaises(ValueError):
                benchmark_retrieval_recall(truth, rows, retrieved, top_k=3)
        # A thin reviewed cohort may yield arithmetic, but is not a valid
        # independent heldout test for this reporting contract.
        small = [r for r in truth if r["probe_uid"] not in {"HoldUnknownProbe_0", "HoldUnknownProbe_1", "HoldUnknownProbe_2"}]
        with self.assertRaises(ValueError):
            benchmark_retrieval_recall(small, roster, retrieved, top_k=3)

    def test_cli_exact_sha_pins_and_private_path_redaction(self):
        truth, roster, retrieved = fixture()
        with tempfile.TemporaryDirectory(prefix="ATLAS-private-evidence-") as folder:
            arguments = []
            paths = []
            for label, fields, rows in (("truth", TRUTH_FIELDS, truth),
                                         ("roster", ROSTER_FIELDS, roster),
                                         ("retrieved", RETRIEVED_FIELDS, retrieved)):
                path = Path(folder) / (label + "-private.csv")
                sha = write_csv(path, fields, rows)
                arguments += ["--" + label, str(path), "--" + label + "-sha256", sha]
                paths.append(path)
            with contextlib.redirect_stdout(io.StringIO()) as output:
                self.assertEqual(main(arguments + ["--top-k", "3"]), 0)
            result = json.loads(output.getvalue())
            self.assertEqual(result["by_split"]["holdout"][
                "known_eligible_target_returned_at_k"], 11)
            paths[2].write_bytes(paths[2].read_bytes() + b"tampered")
            with contextlib.redirect_stderr(io.StringIO()) as errors:
                self.assertEqual(main(arguments + ["--top-k", "3"]), 2)
            self.assertNotIn(folder, errors.getvalue())
            self.assertNotIn("private", errors.getvalue())
            self.assertNotIn(folder, output.getvalue())


if __name__ == "__main__":
    unittest.main(verbosity=2)
