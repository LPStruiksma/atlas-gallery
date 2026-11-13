#!/usr/bin/env python3
"""Fictional offline gallery/probe inventory, with no actual media access."""
import contextlib
import csv
import hashlib
import io
import json
import os
from pathlib import Path
import tempfile
import unittest

from build_frozen_roster import (
    PROBE_FIELDS, GALLERY_FIELDS, ROSTER_FIELDS, MAX_ROSTER_ROWS,
    build_frozen_roster, canonical_csv_bytes, main, read_pinned_csv,
)
from candidate_list_coverage import audit_candidate_list


def sample():
    probes = [{"split": "calibration", "probe_uid": "CalProbe02"},
              {"split": "holdout", "probe_uid": "HoldProbe02"},
              {"split": "calibration", "probe_uid": "CalProbe01"},
              {"split": "holdout", "probe_uid": "HoldProbe01"}]
    gallery = [{"split": "calibration", "candidate_uid": "CalKnown02"},
               {"split": "calibration", "candidate_uid": "CalKnown01"}]
    return probes, gallery


def write_csv(path, fields, data):
    with path.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=fields, lineterminator="\n")
        writer.writeheader()
        writer.writerows(data)
    return hashlib.sha256(path.read_bytes()).hexdigest()


class FrozenRosterGenerationTests(unittest.TestCase):
    def test_permutation_invariance_and_zero_eligible_gallery_sentinel(self):
        probes, gallery = sample()
        rows, report = build_frozen_roster(probes, gallery)
        self.assertEqual(rows, [
            {"split":"calibration", "probe_uid":"CalProbe01", "candidate_uid":"CalKnown01"},
            {"split":"calibration", "probe_uid":"CalProbe01", "candidate_uid":"CalKnown02"},
            {"split":"calibration", "probe_uid":"CalProbe02", "candidate_uid":"CalKnown01"},
            {"split":"calibration", "probe_uid":"CalProbe02", "candidate_uid":"CalKnown02"},
            {"split":"holdout", "probe_uid":"HoldProbe01", "candidate_uid":"NONE"},
            {"split":"holdout", "probe_uid":"HoldProbe02", "candidate_uid":"NONE"},
        ])
        inverted, report_again = build_frozen_roster(
            list(reversed(probes)), list(reversed(gallery)))
        self.assertEqual(canonical_csv_bytes(rows), canonical_csv_bytes(inverted))
        self.assertEqual(report, report_again)
        self.assertEqual(report["by_split"]["holdout"]["no_candidate_probes"], 2)
        self.assertFalse(report["gallery_exhaustiveness_proven"])
        self.assertFalse(report["source_manifests_are_independently_verified"])
        self.assertFalse(report["auto_confirm_allowed"])
        self.assertEqual(canonical_csv_bytes(rows).splitlines()[0],
                         b"split,probe_uid,candidate_uid")

    def test_duplicates_cross_split_leakage_private_columns_and_budget_rejected(self):
        probes, gallery = sample()
        cases = [
            (probes + [dict(probes[0])], gallery),
            (probes + [{"split":"holdout", "probe_uid":"CalProbe01"}], gallery),
            (probes, gallery + [dict(gallery[0])]),
            (probes, gallery + [{"split":"holdout","candidate_uid":"CalKnown01"}]),
            ([dict(probes[0], private_path="/storage/private.jpg")] + probes[1:], gallery),
            (probes, gallery + [{"split":"calibration","candidate_uid":"UNKNOWN"}]),
            (probes, gallery + [{"split":"calibration","candidate_uid":"../../face"}]),
            ([{"split":"calibration","probe_uid":"P1"}], gallery),
        ]
        for count, (p, g) in enumerate(cases):
            with self.subTest(case=count):
                with self.assertRaises(ValueError):
                    build_frozen_roster(p, g)
        big_probes = [
            {"split": "calibration" if n < 200 else "holdout",
             "probe_uid": "Probe_%04d" % n}
            for n in range(400)]
        big_gallery = [
            {"split": "calibration" if n < 400 else "holdout",
             "candidate_uid": "Known_%04d" % n}
            for n in range(800)]
        with self.assertRaisesRegex(ValueError, "budget"):
            build_frozen_roster(big_probes, big_gallery)

    def test_pinned_csv_rejects_mutations_and_symlinked_sources(self):
        p, g = sample()
        with tempfile.TemporaryDirectory() as folder:
            src = Path(folder) / "opaque-probes.csv"
            digest = write_csv(src, PROBE_FIELDS, p)
            self.assertEqual(read_pinned_csv(src, digest, PROBE_FIELDS), p)
            for pin in ("0" * 64, digest.upper(), "short", ""):
                with self.subTest(pin=pin):
                    with self.assertRaises(ValueError):
                        read_pinned_csv(src, pin, PROBE_FIELDS)
            link = Path(folder) / "source-link.csv"
            link.symlink_to(src)
            with self.assertRaises(ValueError):
                read_pinned_csv(link, digest, PROBE_FIELDS)
            src.write_bytes(src.read_bytes() + b"changed")
            with self.assertRaises(ValueError):
                read_pinned_csv(src, digest, PROBE_FIELDS)

    def test_dry_run_and_restricted_nonoverwriting_output_and_no_path_leak(self):
        p, g = sample()
        with tempfile.TemporaryDirectory(prefix="ATLAS-private-gallery-") as folder:
            probes = Path(folder) / "real-private-probes.csv"
            gallery = Path(folder) / "real-private-gallery.csv"
            out = Path(folder) / "opaque-roster.csv"
            probe_pin = write_csv(probes, PROBE_FIELDS, p)
            gal_pin = write_csv(gallery, GALLERY_FIELDS, g)
            args = ["--probes", str(probes), "--probes-sha256", probe_pin,
                    "--gallery", str(gallery), "--gallery-sha256", gal_pin]
            with contextlib.redirect_stdout(io.StringIO()) as buf:
                self.assertEqual(main(args), 0)
            self.assertFalse(out.exists())
            report = json.loads(buf.getvalue())
            self.assertFalse(report["csv_written"])
            expected, _ = build_frozen_roster(p, g)
            self.assertEqual(report["canonical_roster_sha256"],
                             hashlib.sha256(canonical_csv_bytes(expected)).hexdigest())
            with contextlib.redirect_stdout(io.StringIO()) as buf:
                self.assertEqual(main(args + ["--out", str(out)]), 0)
            self.assertTrue(out.is_file())
            self.assertEqual(out.read_bytes(), canonical_csv_bytes(expected))
            self.assertEqual(out.stat().st_mode & 0o777, 0o600)
            prior = out.read_bytes()
            with contextlib.redirect_stderr(io.StringIO()) as errors:
                self.assertEqual(main(args + ["--out", str(out)]), 2)
            self.assertEqual(prior, out.read_bytes())
            self.assertNotIn(folder, errors.getvalue())
            self.assertNotIn("private", errors.getvalue())
            self.assertNotIn(folder, buf.getvalue())

    def test_generated_roster_finds_missing_scores_in_independent_harness(self):
        # The gallery snapshot is assembled without consulting score outputs.
        # Synthetic score rows deliberately cover every gallery identity.
        probes, gallery = [], []
        for split, known, unknown in (("calibration", 70, 90),
                                      ("holdout", 25, 25)):
            prefix = "Cal" if split == "calibration" else "Hold"
            for n in range(known):
                probes.append({"split": split, "probe_uid": prefix + "KnownProbe_" + str(n)})
                gallery.append({"split": split, "candidate_uid": prefix + "Known_" + str(n)})
            for n in range(unknown):
                probes.append({"split": split, "probe_uid": prefix + "UnknownProbe_" + str(n)})
        roster, _ = build_frozen_roster(probes, gallery)
        scores = []
        for row in roster:
            split, probe, candidate = (row["split"], row["probe_uid"], row["candidate_uid"])
            prefix = "Cal" if split == "calibration" else "Hold"
            truth = (prefix + "Known_" + probe.rsplit("_", 1)[1]
                     if "KnownProbe_" in probe else "UNKNOWN")
            similarity = .92 if candidate == truth else .1
            if probe == "HoldUnknownProbe_0" and candidate == "HoldKnown_0":
                similarity = .97
            scores.append(dict(probe_uid=probe, split=split, truth_uid=truth,
                               session_uid="Session_" + probe,
                               condition="front" if truth != "UNKNOWN" else "lookalike",
                               candidate_uid=candidate, similarity=str(similarity)))
        audit = audit_candidate_list(scores, roster)
        self.assertEqual(audit["by_split"]["calibration"]["scored_candidates"], 160 * 70)
        from benchmark_reid_scores import benchmark_model_scores
        report = benchmark_model_scores(
            scores, candidate_roster_rows=roster, require_candidate_roster=True)
        self.assertEqual(report["similarity_floor"], .92)
        self.assertEqual(report["holdout"]["overall"]["unknown_false_offers"], 1)
        with self.assertRaisesRegex(ValueError, "Incomplete"):
            benchmark_model_scores(scores[:-1], candidate_roster_rows=roster,
                                   require_candidate_roster=True)


if __name__ == "__main__":
    unittest.main(verbosity=2)
