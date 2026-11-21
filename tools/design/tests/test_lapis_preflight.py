"""Layout and safety checks for the synthetic Lapis preflight generator (#753).

Run: python3 -m unittest discover -s tools/design/tests
Uses the DejaVu fallback font, so it needs no Funnel files and no network."""

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from lapis_kit import DEVICE_H, DEVICE_W, TOKENS, justified_rows, px  # noqa: E402
import lapis_preflight  # noqa: E402


class JustifiedRows(unittest.TestCase):
    def test_full_rows_fill_width_and_keep_ratio(self):
        ratios = [1.5, 0.75, 1.0, 4.2, 0.56, 1.33, 1.78, 0.8, 1.5]
        width, gap = 1080, px(2)
        rows = justified_rows(ratios, width, px(118), gap)
        for row in rows[:-1]:
            used = sum(w for _, w, _ in row) + gap * (len(row) - 1)
            self.assertAlmostEqual(used, width, delta=1.0)
        for row in rows:
            for i, w, h in row:
                self.assertAlmostEqual(w / h, ratios[i], places=6)  # no crop, no letterbox

    def test_panorama_gets_its_own_row(self):
        rows = justified_rows([1.0, 4.2, 1.0], 1080, px(118), px(2))
        solo = [r for r in rows if len(r) == 1 and r[0][0] == 1]
        self.assertEqual(len(solo), 1)
        self.assertAlmostEqual(solo[0][0][1], 1080)

    def test_last_row_not_stretched(self):
        rows = justified_rows([1.0, 1.0, 1.0, 1.0, 1.0], 1080, px(118), px(2))
        self.assertAlmostEqual(rows[-1][0][2], px(118))


class Tokens(unittest.TestCase):
    def test_brand_fills_match_quiet_frame_v3(self):
        self.assertEqual(TOKENS["light"]["brand_fill"], "#2639C9")
        self.assertEqual(TOKENS["dark"]["brand_fill"], "#3448E0")


class AtlasTargets(unittest.TestCase):
    def test_two_by_six_month_targets_do_not_overlap_and_meet_48dp(self):
        from lapis_kit import FontSource, Fonts, Screen
        import lapis_screens as L
        s = Screen(Fonts(FontSource(None, True)))
        L.atlas_panel(s, 1.0, grid=True)
        boxes = [t.box for t in s.targets if t.label.startswith("month ")]
        self.assertEqual(len(boxes), 12)
        for i, a in enumerate(boxes):
            self.assertGreaterEqual(min(a[2] - a[0], a[3] - a[1]), px(48) - 1)
            for b in boxes[i + 1:]:
                overlap_w = min(a[2], b[2]) - max(a[0], b[0])
                overlap_h = min(a[3], b[3]) - max(a[1], b[1])
                self.assertFalse(overlap_w > 1 and overlap_h > 1, (a, b))


class Render(unittest.TestCase):
    def test_every_sheet_renders_with_full_device_frames(self):
        with tempfile.TemporaryDirectory() as tmp:
            rc = lapis_preflight.main(["--allow-fallback-font", "--out", tmp])
            self.assertEqual(rc, 0)
            import json
            man = json.loads((Path(tmp) / "manifest.json").read_text())
            self.assertEqual(set(man["boards"]), {"l4", "l5", "l6", "l7", "l8"})
            for entry in man["boards"].values():
                self.assertEqual(entry["device_frame_px"], [DEVICE_W, DEVICE_H])
                self.assertGreaterEqual(entry["device_frames"], 12)
            self.assertEqual(lapis_preflight.main(["--check", "--out", tmp]), 0)


if __name__ == "__main__":
    unittest.main()
