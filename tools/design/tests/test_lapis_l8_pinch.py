"""L8 six-stop density, pinch-anchor and precision-selection regressions (atlas-gallery #836, #855).

Run: python3 -m unittest discover -s tools/design/tests
The geometry depends on line heights and the 1080 x 2340 frame only, so the DejaVu fallback font gives the
same numbers as the Funnel render that the committed sheet uses."""

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import lapis_screens as L  # noqa: E402
from lapis_kit import DENSITY, DEVICE_W, FontSource, Fonts, Screen, px  # noqa: E402

REPO = Path(__file__).resolve().parents[3]


def env(scale=1.0):
    return L.l8_env(Screen(Fonts(FontSource(None, True)), font_scale=scale))


class Layouts(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.items, cls.lays, cls.vp = env()

    def test_six_stops_in_the_approved_order_and_densities(self):
        self.assertEqual(L.LADDER, ("original", "large", "medium", "small", "month", "year"))
        self.assertEqual(L.SQUARE_COLS, {"small": 7, "month": 12, "year": 28})
        self.assertEqual([self.lays[k].cols for k in L.LADDER], [1, 0, 0, 7, 12, 28])
        self.assertEqual([self.lays[k].square for k in L.LADDER], [False, False, False, True, True, True])

    def test_large_and_medium_rows_hold_about_three_and_four(self):
        for kind, want in (("large", 3), ("medium", 4)):
            rows = {}
            for r in self.lays[kind].rects.values():
                rows[round(r[1])] = rows.get(round(r[1]), 0) + 1
            self.assertAlmostEqual(sum(rows.values()) / len(rows), want, delta=0.25, msg=kind)

    def test_grid_rects_are_square_and_never_overlap_across_months(self):
        for kind in ("small", "month", "year"):
            lay = self.lays[kind]
            rects = sorted(lay.rects.values(), key=lambda r: (r[1], r[0]))
            for r in rects:
                self.assertAlmostEqual(r[2] - r[0], r[3] - r[1], places=6)
            for a, b in zip(rects, rects[1:]):
                same_row = abs(a[1] - b[1]) < 1e-6
                self.assertTrue(b[0] >= a[2] if same_row else b[1] >= a[3], (kind, a, b))
        self.assertEqual([h[1][:3] for h in self.lays["year"].headers], ["Nov", "Sep", "Aug", "Jul", "Jun", "May",
                                                                         "Apr", "Mar"])

    def test_real_ratio_stops_keep_every_ratio_unless_a_tall_original_is_capped(self):
        for kind in ("large", "medium", "original"):
            for k, (x0, y0, x1, y1) in self.lays[kind].rects.items():
                self.assertAlmostEqual((x1 - x0) / (y1 - y0), self.items[k].ratio, places=6, msg=(kind, k))

    def test_original_is_one_per_row_and_never_wider_than_the_screen(self):
        for x0, y0, x1, y1 in self.lays["original"].rects.values():
            self.assertGreaterEqual(x0, 0)
            self.assertLessEqual(x1, DEVICE_W + 1e-6)
            self.assertLessEqual(y1 - y0, px(L.ORIGINAL_MAX_DP) + 1e-6)
        ys = [r[1] for r in self.lays["original"].rects.values()]
        self.assertEqual(len(ys), len(set(ys)))

    def test_column_is_position_in_month_mod_columns(self):
        # Keys 0-267 are November; keys 268+ are September, so their position in the month is key - 268.
        for kind, cols in L.SQUARE_COLS.items():
            lay = self.lays[kind]
            tile = (DEVICE_W - px(L.GAP) * (cols - 1)) / cols
            for k in (0, 5, 83, 88, 267):
                self.assertAlmostEqual(lay.rects[k][0], (k % cols) * (tile + px(L.GAP)), places=6)
            for k in (268, 828, 969):
                self.assertAlmostEqual(lay.rects[k][0], ((k - 268) % cols) * (tile + px(L.GAP)), places=6)


class Targets(unittest.TestCase):
    """48 dp rule (Quiet Frame v3): direct per-media taps only where the tile is >= 48 dp."""

    def test_tile_sizes_from_the_1080_px_frame(self):
        self.assertAlmostEqual(L.tile_dp(7), 53.0, delta=0.05)
        self.assertAlmostEqual(L.tile_dp(12), 30.0, delta=0.05)
        self.assertAlmostEqual(L.tile_dp(28), 11.7, delta=0.05)
        self.assertGreaterEqual(L.tile_dp(7), L.MIN_TARGET_DP)
        self.assertLess(L.tile_dp(12), L.MIN_TARGET_DP)
        self.assertLess(L.tile_dp(28), L.MIN_TARGET_DP)

    def test_dense_overviews_record_no_direct_tile_target_and_small_records_compliant_ones(self):
        import lapis_preflight
        gen = lapis_preflight.Gen(Fonts(FontSource(None, True)))
        for kind, scale in (("small", 1.0), ("month", 1.0), ("month", 2.0), ("year", 1.0), ("year", 2.0)):
            s = gen.l8_month_sig(kind, "light" if scale == 1.0 else "dark", scale)
            labels = [t.label for t in s.targets]
            self.assertIn("month thumb", labels)
            sel = [t for t in s.targets if t.label.startswith("select ") and t.label != "select precisely"]
            if kind == "small":
                self.assertEqual(sorted(t.label for t in sel), ["select 1", "select 2", "select 3"])
                for t in sel:
                    self.assertGreaterEqual((t.box[2] - t.box[0]) / DENSITY, 48)
            else:
                self.assertEqual(sel, [], kind)
                pills = [t for t in s.targets if t.label == "select precisely"]
                self.assertEqual(len(pills), 1, kind)
                self.assertGreaterEqual((pills[0].box[3] - pills[0].box[1]) / DENSITY, 48)

    def test_every_dense_and_small_frame_keeps_spheres_and_the_thumb_visible(self):
        import lapis_preflight
        gen = lapis_preflight.Gen(Fonts(FontSource(None, True)))
        for kind in ("small", "month", "year"):
            s = gen.l8_month_sig(kind, "light", 1.0)
            self.assertIn("month thumb", [t.label for t in s.targets])


class Storyboard(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.items, cls.lays, cls.vp = env()
        cls.st = L.storyboard(cls.lays, cls.vp)

    def test_every_main_step_restores_the_anchor_height_and_none_clamp(self):
        st = self.st
        for name in ("in_medium", "in_small", "in_month", "in_year"):
            self.assertEqual(st[name].key, L.MAIN_KEY, name)
            self.assertEqual(st[name].clamped, "", name)
            self.assertAlmostEqual(st[name].y_residual, 0.0, delta=0.5, msg=name)
        for name in ("out_month", "out_small", "out_medium", "out_large"):
            self.assertEqual(st[name].key, st["out_month"].key, name)
            self.assertEqual(st[name].clamped, "", name)
            self.assertAlmostEqual(st[name].y_residual, 0.0, delta=1.5, msg=name)

    def test_a_key_lies_under_F_in_every_pinch_in_frame_and_is_named(self):
        for name in ("in_medium", "in_small", "in_month", "in_year"):
            under = self.st[name].extra["key_under_pointer"]
            self.assertIsNotNone(under, name)
            self.assertEqual(L.key_at(self.lays[self.st[name].kind], self.st[name].scroll, self.vp,
                                      self.st["start"].focal), under)
        self.assertEqual(self.st["out_month"].key, self.st["in_year"].extra["key_under_pointer"])

    def test_x_deltas_are_reported_not_hidden(self):
        st = self.st
        # Centre shifts are the column quantization, never claimed to be zero.
        self.assertEqual([round(st[n].dx) for n in ("in_medium", "in_small", "in_month", "in_year")],
                         [537, -926, 692, -750])
        self.assertEqual([round(st[n].dx) for n in ("int_small", "int_month", "int_year")], [188, -200, 491])
        for name in ("in_medium", "in_small", "in_month", "in_year", "int_small", "int_month", "int_year", "top",
                     "bottom", "out_month", "out_small", "out_medium", "out_large"):
            self.assertNotEqual(round(st[name].dx), 0, name)
            want = L.centre(st[name].dst)[0] - L.centre(st[name].src)[0]
            self.assertAlmostEqual(st[name].dx, want, places=6)

    def test_pointer_departures_are_measured_not_assumed(self):
        # Only some frames keep F on the anchor's rect; the board prints `hit` and the key that is under F.
        st = self.st
        self.assertTrue(st["start"].hit)
        for name in ("in_medium", "in_small", "in_month", "in_year"):
            self.assertEqual(st[name].hit, st[name].extra["key_under_pointer"] == L.MAIN_KEY, name)
        self.assertFalse(all(st[n].hit for n in ("in_medium", "in_small", "in_month", "in_year")))

    def test_interior_key_is_measured_at_every_overview(self):
        st = self.st
        for name, under in (("int_small", 824), ("int_month", 825), ("int_year", 813)):
            self.assertEqual(st[name].key, L.INTERIOR_KEY)
            self.assertAlmostEqual(st[name].y_residual, 0.0, delta=0.5)
            self.assertEqual(st[name].extra["key_under_pointer"], under, name)
            self.assertEqual(st[name].hit, under == L.INTERIOR_KEY, name)

    def test_clamped_top_and_bottom(self):
        top, bot = self.st["top"], self.st["bottom"]
        self.assertEqual(top.clamped, "top")
        self.assertEqual(top.scroll, 0.0)
        self.assertLess(top.wanted_scroll, 0)
        self.assertEqual(round(top.y_residual), -90)
        self.assertEqual(bot.clamped, "bottom")
        self.assertAlmostEqual(bot.scroll, L.max_scroll(self.lays["small"], self.vp))
        self.assertGreater(bot.wanted_scroll, bot.scroll)
        self.assertGreater(bot.y_residual, 100)
        self.assertEqual(bot.key, max(self.lays["large"].rects))

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

    def test_only_a_few_columns_can_share_a_point_between_neighbouring_overviews(self):
        self.assertEqual(L.same_point_columns(7, 12), [0])
        self.assertEqual(L.same_point_columns(12, 28), [0, 20, 21])

    def test_selection_keys_are_visible_in_every_non_original_frame(self):
        st = self.st
        for kind, step in (("large", st["start"]), ("medium", st["in_medium"]), ("small", st["in_small"]),
                           ("month", st["in_month"]), ("year", st["in_year"])):
            lay = self.lays[kind]
            for k in L.SELECTED:
                r = L.to_screen(lay.rects[k], step.scroll, self.vp)
                self.assertTrue(self.vp.top <= r[1] and r[3] <= self.vp.bottom, (kind, k))


class Precision(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.items, cls.lays, cls.vp = env()

    def test_focus_to_seven_is_reversible_and_keeps_key_and_height(self):
        for src in ("month", "year"):
            pf = L.precision_flow(self.lays, self.vp, src)
            self.assertEqual({pf[n].key for n in pf}, {L.PRECISION_KEY})
            self.assertEqual(pf["focus"].kind, "small")
            self.assertEqual(pf["back"].kind, src)
            self.assertEqual(pf["focus"].clamped, "", src)
            self.assertAlmostEqual(pf["focus"].y_residual, 0.0, delta=0.5)
            self.assertAlmostEqual(pf["back"].scroll, pf["press"].scroll, delta=0.5, msg=src)
            self.assertAlmostEqual(pf["back"].dx, -pf["focus"].dx, delta=0.5)

    def test_the_focused_stop_gives_real_48dp_tiles_and_the_dense_stop_does_not(self):
        self.assertGreaterEqual(L.tile_dp(L.SQUARE_COLS["small"]), L.MIN_TARGET_DP)
        for kind in ("month", "year"):
            self.assertLess(L.tile_dp(L.SQUARE_COLS[kind]), L.MIN_TARGET_DP)

    def test_pill_is_a_48dp_target_and_grows_at_200_percent(self):
        for scale in (1.0, 2.0):
            s = Screen(Fonts(FontSource(None, True)), font_scale=scale)
            box = L.precision_pill(s, 200, "Select precisely")
            self.assertGreaterEqual((box[3] - box[1]) / DENSITY, 48 - 0.2)
            self.assertLessEqual(box[2], DEVICE_W)
            self.assertEqual(s.targets[-1].box[3] - s.targets[-1].box[1], round(box[3] - box[1]))
        small, big = (L.precision_pill(Screen(Fonts(FontSource(None, True)), font_scale=sc), 200, "Select precisely")
                      for sc in (1.0, 2.0))
        self.assertGreater(big[3] - big[1], small[3] - small[1])


class Readme(unittest.TestCase):
    def test_readme_quotes_the_measured_values(self):
        items, lays, vp = env()
        st = L.storyboard(lays, vp)
        text = (REPO / "docs/design/lapis/preflight/README.md").read_text()

        def m(v):
            return L.fmt_d(v).replace("-", "−")

        for v in (st["in_medium"].dx, st["in_small"].dx, st["in_month"].dx, st["in_year"].dx,
                  st["int_small"].dx, st["int_month"].dx, st["int_year"].dx,
                  st["top"].y_residual, st["bottom"].y_residual):
            self.assertIn(m(v) + " px", text)
        for cols in L.SQUARE_COLS.values():
            self.assertIn(f"{L.tile_dp(cols):.1f} dp", text)

    def test_readme_no_longer_calls_four_or_eight_columns_month_or_year(self):
        text = (REPO / "docs/design/lapis/preflight/README.md").read_text().lower()
        section = text.split("## l8 six stops")[-1]
        for stale in (" 8-column", "4-column", "4- and 8", "8 columns", "eight columns", "46.25", "(52.6 dp)"):
            self.assertNotIn(stale, section, stale)


if __name__ == "__main__":
    unittest.main()
