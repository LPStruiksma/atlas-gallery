"""Fail-closed contract for BRAND-L2 real screen pairing."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


class BrandL2CaptureContractTest(unittest.TestCase):
    def test_exact_24_shot_matrix_and_two_installed_apk_phases(self):
        declared = [line.strip() for line in
                    (ROOT / "scripts/ui-evidence.d/655-brand-l2-visual.txt").read_text().splitlines()
                    if line.strip() and not line.startswith("#")]
        expected = [f"brand-l2-{revision}-{surface}-{theme}-{scale}"
                    for revision in ("before", "after") for theme in ("light", "dark")
                    for scale in ("100", "200") for surface in ("photos", "people", "viewer")]
        self.assertCountEqual(expected, declared)
        self.assertEqual(24, len(set(declared)))
        source = (ROOT / "app/src/androidTest/java/nl/atlas/gallery/SystemThemeJourneyTest.kt").read_text()
        runner = (ROOT / "scripts/ci_android_upgrade_emulator.sh").read_text()
        self.assertIn("fun brandL2PairedEvidence()", source)
        for surface in ("photos", "people", "viewer"):
            self.assertIn('saveUiEvidence("brand-l2-$revision-' + surface + '-$mode-$scale.png")', source)
        for marker in ("capture_brand_l2 before", "capture_brand_l2 after",
                       "for mode in light dark", "for scale in 100 200",
                       "-e atlasBrandRevision", "-e atlasBrandScale",
                       "settings put system font_scale", "OK (1 test)"):
            self.assertIn(marker, runner)


if __name__ == "__main__":
    unittest.main()
