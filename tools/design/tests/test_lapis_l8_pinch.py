"""L8 pinch-anchor and month/year signature regressions (atlas-gallery #836).

Run: python3 -m unittest discover -s tools/design/tests
The geometry depends on line heights and the 1080 x 2340 frame only, so the DejaVu fallback font gives the
same numbers as the Funnel render that the committed sheet uses."""

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import lapis_screens as L  # noqa: E402
from lapis_kit import DEVICE_W, FontSource, Fonts, Screen, px  # noqa: E402

REPO = Path(__file__).resolve().parents[3]


def env(scale=1.0):
    return L.l8_env(Screen(Fonts(FontSource(None, True)), font_scale=scale))


class Layouts(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.items, cls.lays, cls.vp = env()

    def test_grid_rects_are_square_and_never_overlap_across_months(self):
        for kind in ("month", "year"):
            lay = self.lays[kind]
            rects = sorted(lay.rects.values(), key=lambda r: (r[1], r[0]))
            for r in rects:
                self.assertAlmostEqual(r[2] - r[0], r[3] - r[1], places=6)
            for a, b in zip(rects, rects[1:]):
                same_row = abs(a[1] - b[1]) < 1e-6
                self.assertTrue(b[0] >= a[2] if same_row else b[1] >= a[3], (kind, a, b))
            self.assertEqual([h[1][:3] for h in lay.headers], ["Nov", "Sep"])

    def test_day_rects_keep_the_real_ratio(self):
        for k, (x0, y0, x1, y1) in self.lays["day"].rects.items():
            self.assertAlmostEqual((x1 - x0) / (y1 - y0), self.items[k].ratio, places=6)

    def test_column_is_position_in_month_mod_columns(self):
        for kind, cols in (("month", 4), ("year", 8)):
            lay = self.lays[kind]
            tile = (DEVICE_W - px(L.GAP) * (cols - 1)) / cols
            for k in (0, 5, 83, 88, 267):
                self.assertAlmostEqual(lay.rects[k][0], (k % cols) * (tile + px(L.GAP)), places=6)


class Storyboard(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.items, cls.lays, cls.vp = env()
        cls.st = L.storyboard(cls.lays, cls.vp)

    def test_main_key_stays_under_the_focal_point_both_directions(self):
        st = self.st
        for name in ("in_month", "in_year", "out_month", "out_day"):
            self.assertEqual(st[name].key, L.MAIN_KEY, name)
            self.assertTrue(st[name].hit, name)
            self.assertEqual(st[name].clamped, "", name)
            self.assertAlmostEqual(st[name].y_residual, 0.0, delta=0.5)
        self.assertEqual(L.key_at(self.lays["year"], st["in_year"].scroll, self.vp, st["start"].focal), L.MAIN_KEY)

    def test_round_trip_restores_day_scroll(self):
        self.assertAlmostEqual(self.st["out_day"].scroll, self.st["start"].scroll, delta=0.5)
        self.assertAlmostEqual(self.st["out_month"].scroll, self.st["in_month"].scroll, delta=0.5)

    def test_x_deltas_are_reported_not_hidden(self):
        st = self.st
        # Centre shifts are the column quantization, never claimed to be zero.
        self.assertEqual(round(st["in_month"].dx), -144)
        self.assertEqual(round(st["in_year"].dx), -68)
        self.assertEqual(round(st["int_month"].dx), 388)
        self.assertEqual(round(st["int_year"].dx), -475)
        for name in ("in_month", "in_year", "int_month", "int_year", "top", "bottom"):
            self.assertNotEqual(round(st[name].dx), 0, name)
            want = L.centre(st[name].dst)[0] - L.centre(st[name].src)[0]
            self.assertAlmostEqual(st[name].dx, want, places=6)

    def test_interior_key_misses_the_pointer_and_says_which_key_is_there(self):
        st = self.st
        for name, under in (("int_month", 82), ("int_year", 84)):
            self.assertFalse(st[name].hit, name)
            self.assertEqual(st[name].key, L.INTERIOR_KEY)
            self.assertAlmostEqual(st[name].y_residual, 0.0, delta=0.5)
            self.assertEqual(st[name].extra["key_under_pointer"], under)

    def test_clamped_top_and_bottom(self):
        top, bot = self.st["top"], self.st["bottom"]
        self.assertEqual(top.clamped, "top")
        self.assertEqual(top.scroll, 0.0)
        self.assertLess(top.wanted_scroll, 0)
        self.assertEqual(round(top.y_residual), -31)
        self.assertEqual(bot.clamped, "bottom")
        self.assertAlmostEqual(bot.scroll, L.max_scroll(self.lays["month"], self.vp))
        self.assertGreater(bot.wanted_scroll, bot.scroll)
        self.assertEqual(round(bot.y_residual), 53)
        self.assertEqual(bot.key, max(self.lays["day"].rects))

    def test_drawn_fingertips_are_centred_on_the_focal_point_inside_the_viewport(self):
        # The board labels F the two-finger centroid; the drawing inputs must agree (Review on #840).
        cases = [(st.focal, d, kw) for st in (self.st["start"], self.st["int_start"])
                 for d in ("pinch in", "pinch out") for kw in ({}, {"far_dp": 60, "near_dp": 36})]
        for focal, direction, kw in cases:
            (s1, e1), (s2, e2) = L.pinch_fingers(focal, direction, **kw)
            for p, q in ((s1, s2), (e1, e2)):
                self.assertAlmostEqual((p[0] + q[0]) / 2, focal[0], places=6)
                self.assertAlmostEqual((p[1] + q[1]) / 2, focal[1], places=6)
                for x, y in (p, q):
                    self.assertTrue(0 <= x <= DEVICE_W and self.vp.top <= y <= self.vp.bottom, (focal, x, y))
            far = abs(s1[1] - s2[1]) > abs(e1[1] - e2[1])
            self.assertEqual(far, direction == "pinch in")

    def test_only_outer_year_columns_can_share_a_point_with_month(self):
        self.assertEqual(L.same_point_columns(4, 8), [0, 7])

    def test_selection_keys_are_visible_in_every_main_frame(self):
        st = self.st
        for kind, step in (("day", st["start"]), ("month", st["in_month"]), ("year", st["in_year"])):
            lay = self.lays[kind]
            for k in L.SELECTED:
                r = L.to_screen(lay.rects[k], step.scroll, self.vp)
                self.assertTrue(self.vp.top <= r[1] and r[3] <= self.vp.bottom, (kind, k))


class Signature(unittest.TestCase):
    def test_month_and_year_draw_spheres_thumb_and_flag_small_year_tiles(self):
        import lapis_preflight
        gen = lapis_preflight.Gen(Fonts(FontSource(None, True)))
        for kind, scale in (("month", 1.0), ("month", 2.0), ("year", 1.0), ("year", 2.0)):
            s = gen.l8_month_sig(kind, "light" if scale == 1.0 else "dark", scale)
            labels = [t.label for t in s.targets]
            self.assertIn("month thumb", labels)
            sel = [t for t in s.targets if t.label.startswith("select ")]
            self.assertEqual(sorted(t.label for t in sel), ["select 1", "select 2", "select 3"])
            for t in sel:
                side = (t.box[2] - t.box[0]) / 2.8125
                self.assertLess(side, 48) if kind == "year" else self.assertGreaterEqual(side, 48)


class Readme(unittest.TestCase):
    def test_readme_quotes_the_measured_values(self):
        items, lays, vp = env()
        st = L.storyboard(lays, vp)
        text = (REPO / "docs/design/lapis/preflight/README.md").read_text()

        def m(v):
            return L.fmt_d(v).replace("-", "−")

        for v in (st["in_month"].dx, st["in_year"].dx, st["int_month"].dx, st["int_year"].dx,
                  st["top"].y_residual, st["bottom"].y_residual):
            self.assertIn(m(v) + " px", text)
        self.assertIn(f"exactly {st['start'].scroll:.0f} px", text)


if __name__ == "__main__":
    unittest.main()
