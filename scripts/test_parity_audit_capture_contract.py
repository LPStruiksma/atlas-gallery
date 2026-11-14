"""Host-export parity matrix regression, replacing the stale private #623 manifest.

The old instrumented contract wrote unexported Activity-private evidence.
This checks the explicit TSV, source-provenance rules and CI hook instead;
the actual 1080x2340 images still require exact-head emulator CI.
"""
import pathlib
import unittest

import verify_parity_matrix as matrix

ROOT = pathlib.Path(__file__).resolve().parents[1]
LEGACY = (
    "pictures-default", "pictures-pinch-compact", "pictures-pinch-large",
    "pictures-original-ratio", "pictures-header-away", "albums-overview",
    "albums-detail", "people-overview", "people-named", "people-hidden",
    "viewer-photo", "viewer-details-people", "viewer-filmstrip",
    "search-person", "selection-more", "photo-dna", "declutter", "settings",
)


class ParityCaptureContractTest(unittest.TestCase):
    def test_legacy_18_surfaces_and_both_themes_stay_accounted_for(self):
        self.assertEqual(LEGACY, matrix.SURFACES[:18])
        self.assertEqual(("search-overview", "dialog-photo-details"), matrix.SURFACES[18:])
        rows = matrix.read_contract()
        self.assertEqual(2 * len(matrix.SURFACES), len(rows))
        self.assertEqual(
            {(surface, theme) for surface in matrix.SURFACES for theme in matrix.THEMES},
            {(r["surface"], r["theme"]) for r in rows},
        )
        for row in rows:
            if row["source_png"]:
                self.assertEqual("yes", row["required"])
                self.assertFalse(row["reason"])
            else:
                self.assertEqual("no", row["required"])
                self.assertTrue(row["reason"])

    def test_all_required_capture_rows_have_real_instrumented_producers(self):
        rows = matrix.read_contract()
        self.assertEqual(26, sum(bool(r["source_png"]) for r in rows))
        source = (ROOT / "app/src/androidTest/java/nl/atlas/gallery/"
                  "SystemThemeJourneyTest.kt").read_text(encoding="utf-8")
        for name in (
            "parity-24-albums-detail-$mode.png",
            "parity-24-people-hidden-$mode.png",
            "parity-24-viewer-filmstrip-$mode.png",
        ):
            self.assertIn('saveUiEvidence("' + name + '")', source)
        self.assertIn('waitFor(hasTestTag("atlas-filmstrip"))', source)

    def test_matrix_is_verified_and_uploaded_through_existing_ci_artifact(self):
        shell = (ROOT / "scripts/ci_ui_evidence.sh").read_text(encoding="utf-8")
        self.assertIn('python3 "$script_dir/verify_parity_matrix.py" ui-evidence', shell)
        verifier = (ROOT / "scripts/verify_parity_matrix.py").read_text(encoding="utf-8")
        for marker in ("sha256", "NOT_CAPTURED", "CAPTURED",
                       '"git", "rev-parse", "HEAD"', 'dest / "manifest.tsv"',
                       'png_digest(source)'):
            self.assertIn(marker, verifier)


if __name__ == "__main__":
    unittest.main()
