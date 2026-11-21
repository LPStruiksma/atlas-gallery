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


class L5HighFontLayout(unittest.TestCase):
    """Pre-build geometry only, not runtime UI or a fixed shared L4 slab."""

    def test_complete_rows_stop_before_dark_200_navigation(self):
        from lapis_kit import FontSource, Fonts, Screen

        fonts = Fonts(FontSource(None, True))
        s = Screen(fonts, theme="dark", font_scale=2.0)
        lapis_preflight.Gen(fonts).l5_collections(s)
        self.assertIsNotNone(s.slab_box)
        slab_top = s.slab_box[1]
        named = [t for t in s.targets if t.label in {"Noor", "Coastal weekend", "Dunes trip"}]
        self.assertGreaterEqual(len(named), 2)
        for target in named:
            x0, y0, x1, y1 = target.box
            self.assertGreaterEqual(x1 - x0, px(48) - 1)
            self.assertGreaterEqual(y1 - y0, px(48) - 1)
            self.assertLessEqual(y1, slab_top - px(4), (target.label, y1, slab_top))

    def test_dark_200_scoped_search_result_above_keyboard(self):
        from lapis_kit import FontSource, Fonts, Screen
        import lapis_screens as L

        fonts = Fonts(FontSource(None, True))
        s = Screen(fonts, theme="dark", font_scale=2.0)
        s.status_bar()
        L.title(s, "Collections")
        keyboard_top = s.keyboard(["lotte", "lot", "photo"])
        slab = s.slab("Collections", y_bottom=keyboard_top, search=False)
        L.search_bar(s, slab[1], "lotte", True, scope="This screen",
                     items=[("Person", "Lotte")], thumbs=True)
        self.assertGreaterEqual(s.panel_top, s.top)
        results = [t for t in s.targets if t.label == "Person: Lotte"]
        self.assertEqual(len(results), 1, "Proposed direct scoped entity result must be visible")
        for target in results:
            x0, y0, x1, y1 = target.box
            self.assertGreaterEqual(x1 - x0, px(48) - 1)
            self.assertGreaterEqual(y1 - y0, px(48) - 1)
            self.assertGreaterEqual(y0, s.top)
            self.assertLessEqual(y1, slab[1] - px(6))
        self.assertLess(slab[3], keyboard_top)


class L5GlyphsAndCaptions(unittest.TestCase):
    """The Funnel fonts have no arrow glyph: it rendered as a tofu box in the tap tables."""

    def test_l5_text_has_no_arrow_glyph(self):
        import inspect
        src = inspect.getsource(lapis_preflight.Preflight.l5) if hasattr(lapis_preflight, "Preflight") else ""
        if not src:
            for obj in vars(lapis_preflight).values():
                if isinstance(obj, type) and hasattr(obj, "l5"):
                    src = inspect.getsource(obj.l5)
        self.assertTrue(src)
        self.assertNotIn("\u2192", src)

    def test_l5_hero_captions_fit_two_lines(self):
        import inspect
        for obj in vars(lapis_preflight).values():
            if isinstance(obj, type) and hasattr(obj, "l5"):
                src = inspect.getsource(obj.l5)
        for name in ("A1", "A2"):
            i = src.index('Panel("%s", "Collections' % name)
            cap = src[i:src.index(", a", i + 20)]
            self.assertLess(len(cap.split('", "', 2)[-1]), 130, name)


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
