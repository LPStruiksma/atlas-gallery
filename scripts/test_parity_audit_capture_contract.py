"""Static safety/coverage regression for #623's isolated instrumentation contract.

Run: python3 scripts/test_parity_audit_capture_contract.py
Android compile/render acceptance still requires exact-head hosted CI.
"""
import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/src/androidTest/java/nl/atlas/gallery/ParityAuditCaptureContractTest.kt"
REQUIRED = (
    "pictures-default", "pictures-pinch-compact", "pictures-pinch-large",
    "pictures-original-ratio", "pictures-header-away", "albums-overview",
    "albums-detail", "people-overview", "people-named", "people-hidden",
    "viewer-photo", "viewer-details-people", "viewer-filmstrip",
    "search-person", "selection-more", "photo-dna", "declutter", "settings",
)


class ParityCaptureContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text = SOURCE.read_text(encoding="utf-8")

    def test_complete_named_matrix_and_no_duplicates(self):
        surfaces = re.findall(r'Surface\("([a-z-]+)"', self.text)
        self.assertEqual(list(REQUIRED), surfaces)
        self.assertEqual(len(surfaces), len(set(surfaces)))
        self.assertIn("surfaces.size * 2", self.text)
        self.assertIn('listOf(expectedLight, expectedDark)', self.text)

    def test_truthful_export_and_constraints(self):
        for fragment in (
            "S24DisplayRule()", "1080", "2340", "manifest.tsv",
            "NOT_CAPTURED", "CAPTURED", "reference-pending/",
            "takeScreenshot()", "SyntheticHeaderFixtures(", "output.listFiles()",
            "states[surface.id] == theme", "File(output, filename).exists()",
            "Theme not controlled in this test run",
        ):
            with self.subTest(fragment=fragment):
                self.assertIn(fragment, self.text)
        self.assertNotIn("saveUiEvidence(", self.text)
        self.assertNotIn("Samsung", self.text.split("class ParityAuditCaptureContractTest")[1])


if __name__ == "__main__":
    unittest.main()
