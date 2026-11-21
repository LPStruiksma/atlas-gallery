"""L8 six-stop density, pinch-anchor and precision-selection regressions (atlas-gallery #836, #855).

Run: python3 -m unittest discover -s tools/design/tests
The geometry depends on line heights and the 1080 x 2340 frame only, so the DejaVu fallback font gives the
same numbers as the Funnel render that the committed sheet uses."""

import math
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

    def test_shared_point_depends_on_the_key_index_and_overlap_not_containment(self):
        # Review on #856: month position 85 is column 1 in SMALL 7 and MONTH 12; the spans overlap at x = 160 px.
        lo, hi = L.shared_point_x(85, 7, 12)
        self.assertAlmostEqual(lo, 155.14, delta=0.05)
        self.assertAlmostEqual(hi, 175.0, delta=0.05)
        a, b = L.month_span_x(85, 7), L.month_span_x(85, 12)
        self.assertTrue(a[0] <= 160 <= a[1] and b[0] <= 160 <= b[1])
        self.assertEqual(L.shared_point_positions(7, 12), (18, 84))
        self.assertEqual(L.shared_point_positions(12, 28), (8, 84))
        self.assertIsNotNone(L.shared_point_x(0, 12, 28))
        self.assertIsNotNone(L.shared_point_x(20, 12, 28))
        self.assertIsNone(L.shared_point_x(5, 12, 28))
        # Any F inside the shared range lies on the key's rect in both grids.
        for cols_a, cols_b in ((7, 12), (12, 28)):
            for idx in range(math.lcm(cols_a, cols_b)):
                r = L.shared_point_x(idx, cols_a, cols_b)
                if r:
                    x = (r[0] + r[1]) / 2
                    for cols in (cols_a, cols_b):
                        s0, s1 = L.month_span_x(idx, cols)
                        self.assertTrue(s0 <= x <= s1, (idx, cols))

    def test_synthetic_videos_are_flagged_and_year_draws_no_video_mark(self):
        for k in L.LIB_VIDEOS:
            self.assertTrue(self.items[k].video, k)
        self.assertTrue(self.items[830].video)  # selected frame item (L.SELECTED[830] = 2)
        self.assertIn(830, L.SELECTED)
        marks = {}
        for kind in ("small", "month", "year"):
            s = Screen(Fonts(FontSource(None, True)))
            calls = []
            orig = (L.video_badge, L.video_glyph)
            L.video_badge = lambda *a, **k: calls.append("badge")
            L.video_glyph = lambda *a, **k: calls.append("glyph")
            try:
                L.draw_layout(s, self.items, self.lays[kind], 0.0, self.vp)
            finally:
                L.video_badge, L.video_glyph = orig
            marks[kind] = calls
        self.assertTrue(marks["small"] and set(marks["small"]) == {"badge"})
        self.assertTrue(marks["month"] and set(marks["month"]) == {"glyph"})
        self.assertEqual(marks["year"], [])

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


class ThumbLabelAndPillFocus(unittest.TestCase):
    """#864: the thumb month follows the key, and Select precisely picks a deterministic focus key."""

    @classmethod
    def setUpClass(cls):
        cls.items, cls.lays, cls.vp = env()

    def test_label_is_the_month_of_the_first_visible_key_at_every_stop_and_position(self):
        seen = set()
        for kind in ("small", "month", "year"):
            lay = self.lays[kind]
            for frac in [i / 40 for i in range(41)]:
                scroll = L.max_scroll(lay, self.vp) * frac
                first = next(k for k, r in lay.rects.items() if L.to_screen(r, scroll, self.vp)[3] > self.vp.top)
                self.assertEqual(L.thumb_anchor_key(lay, scroll, self.vp), first)
                label = L.thumb_month(self.items, lay, scroll, self.vp)
                self.assertEqual(label, self.items[first].month[:3])
                seen.add(label)
        self.assertTrue({"Nov", "Sep", "Aug"} <= seen, seen)

    def test_a_september_key_never_reads_nov_and_a_november_key_never_reads_sep(self):
        for kind in ("small", "month", "year"):
            lay = self.lays[kind]
            for key, want in ((0, "Nov"), (267, "Nov"), (268, "Sep"), (568, "Sep"), (828, "Sep"), (969, "Sep")):
                scroll = max(0.0, min(L.max_scroll(lay, self.vp), lay.rects[key][1]))
                got = L.thumb_month(self.items, lay, scroll, self.vp)
                if L.thumb_anchor_key(lay, scroll, self.vp) == key:
                    self.assertEqual(got, want, (kind, key))

    def test_month_change_tie_break_is_the_top_edge(self):
        lay = self.lays["month"]
        boundary = lay.rects[267][3]  # bottom of the last November row
        self.assertEqual(L.thumb_month(self.items, lay, boundary - 60, self.vp), "Nov")
        self.assertEqual(L.thumb_month(self.items, lay, boundary - 1, self.vp), "Nov")
        self.assertEqual(L.thumb_month(self.items, lay, boundary + 60, self.vp), "Sep")
        # the September heading is visible while a partly visible November row still names the label
        hdr = next(y for y, label in lay.headers if label.startswith("September"))
        self.assertLess(hdr, boundary + lay.header_h)
        self.assertEqual(L.thumb_month(self.items, lay, hdr - 40, self.vp), "Nov")

    def test_pill_anchor_is_the_visible_key_nearest_the_viewport_centre(self):
        for kind in ("month", "year"):
            lay = self.lays[kind]
            scroll = lay.rects[700][1] - 0.5 * self.vp.h
            key, rule = L.focus_anchor(lay, scroll, self.vp)
            self.assertEqual(rule, "centre")
            cx, cy = DEVICE_W / 2, (self.vp.top + self.vp.bottom) / 2
            dists = {k: L._dist(L.to_screen(lay.rects[k], scroll, self.vp), (cx, cy))
                     for k in L.visible_keys(lay, scroll, self.vp)}
            self.assertEqual(dists[key], min(dists.values()))
            # not a constant: other scroll positions give other keys
            other, _ = L.focus_anchor(lay, scroll + 3 * self.vp.h, self.vp)
            self.assertNotEqual(key, other)
            self.assertNotEqual(key, L.PRECISION_KEY)

    def test_equal_distance_goes_to_the_lower_key(self):
        lay = self.lays["month"]
        # keys at month positions 5 and 6 of a row sit symmetrically about x = 540 px
        k = next(k for k in range(268, 900) if (k - 268) % 12 == 5)
        r = lay.rects[k]
        scroll = r[1] - 0.5 * self.vp.h
        centre_y = L.to_screen(r, scroll, self.vp)[1] / 2 + L.to_screen(r, scroll, self.vp)[3] / 2
        key, _ = L.focus_anchor(lay, scroll, self.vp, viewport_centre_y=centre_y)
        self.assertEqual(key, k)
        a, b = (L._dist(L.to_screen(lay.rects[x], scroll, self.vp), (DEVICE_W / 2, centre_y)) for x in (k, k + 1))
        self.assertAlmostEqual(a, b, places=6)

    def test_explicit_visible_focus_is_kept_and_a_stale_focus_is_not(self):
        lay = self.lays["month"]
        scroll = lay.rects[845][1] - 0.5 * self.vp.h
        centre_key, _ = L.focus_anchor(lay, scroll, self.vp)
        self.assertEqual(L.focus_anchor(lay, scroll, self.vp, focused=828), (828, "focused"))
        self.assertNotEqual(centre_key, 828)
        self.assertEqual(L.focus_anchor(lay, scroll, self.vp, focused=4000), (centre_key, "centre"))
        self.assertEqual(L.focus_anchor(lay, scroll, self.vp, focused=0), (centre_key, "centre"))

    def test_pill_flow_is_reversible_keeps_height_and_never_selects(self):
        for kind in ("month", "year"):
            lay = self.lays[kind]
            for scroll in (lay.rects[700][1] - 0.5 * self.vp.h, lay.rects[267][3] + 30, lay.rects[845][1] - 0.5 * self.vp.h):
                pf = L.pill_flow(self.lays, self.vp, kind, scroll)
                self.assertEqual(len({pf[n].key for n in pf}), 1)
                self.assertEqual(pf["press"].key, L.focus_anchor(lay, scroll, self.vp)[0])
                self.assertEqual(pf["focus"].kind, "small")
                self.assertEqual(pf["focus"].clamped, "")
                self.assertAlmostEqual(pf["focus"].y_residual, 0.0, delta=0.5)
                self.assertAlmostEqual(pf["back"].scroll, pf["press"].scroll, delta=0.5)
                # the focused tile is a >= 48 dp square target
                w = (pf["focus"].dst[2] - pf["focus"].dst[0]) / DENSITY
                self.assertGreaterEqual(w, L.MIN_TARGET_DP)

    def test_prior_selected_keys_do_not_move_the_anchor_and_stay_visible_in_the_frames(self):
        lay = self.lays["month"]
        scroll = lay.rects[845][1] - 0.5 * self.vp.h
        pf = L.pill_flow(self.lays, self.vp, "month", scroll)
        self.assertNotIn(pf["press"].key, L.SELECTED)
        for step, kind in (("press", "month"), ("focus", "small"), ("back", "month")):
            vis = L.visible_keys(self.lays[kind], pf[step].scroll, self.vp)
            for k in L.SELECTED:
                self.assertIn(k, vis, (step, k))

    def test_year_pill_flow_with_and_without_selection_and_in_dark_200(self):
        for scale in (1.0, 2.0):
            items, lays, vp = env(scale)
            lay = lays["year"]
            for scroll in (lay.rects[267][3] + 30, lay.rects[845][1] - 0.5 * vp.h):
                pf = L.pill_flow(lays, vp, "year", scroll)
                self.assertEqual(pf["press"].extra["rule"], "centre")
                self.assertEqual(pf["focus"].kind, "small")
                self.assertEqual(pf["back"].kind, "year")
                self.assertAlmostEqual(pf["back"].scroll, pf["press"].scroll, delta=0.5)
                self.assertAlmostEqual(pf["focus"].y_residual, 0.0, delta=0.5)
        items, lays, vp = env()
        scroll = lays["year"].rects[845][1] - 0.5 * vp.h
        pf = L.pill_flow(lays, vp, "year", scroll)
        self.assertNotIn(pf["press"].key, L.SELECTED)
        for step, kind in (("press", "year"), ("focus", "small"), ("back", "year")):
            vis = L.visible_keys(lays[kind], pf[step].scroll, vp)
            for k in L.SELECTED:
                self.assertIn(k, vis, (step, k))

    def test_p5_no_longer_conflates_long_press_with_the_pill(self):
        src = (Path(__file__).resolve().parents[1] / "lapis_preflight.py").read_text()
        self.assertNotIn("long-press or the pill", src)

    def test_dark_200_percent_geometry_gives_a_valid_anchor(self):
        items, lays, vp = env(2.0)
        lay = lays["month"]
        scroll = lay.rects[700][1] - 0.5 * vp.h
        pf = L.pill_flow(lays, vp, "month", scroll)
        self.assertEqual(pf["press"].key, L.focus_anchor(lay, scroll, vp)[0])
        self.assertEqual(L.thumb_month(items, lay, scroll, vp), items[L.thumb_anchor_key(lay, scroll, vp)].month[:3])

    def test_sheet_declares_the_new_panels(self):
        import json
        man = json.loads((REPO / "docs/design/lapis/preflight/manifest.json").read_text())
        codes = man["boards"]["l8"]["panels"]
        for c in [f"T{i}" for i in range(1, 8)] + [f"Q{i}" for i in range(1, 18)]:
            self.assertIn(c, codes)

    def test_readme_quotes_the_boundary_scrolls(self):
        lay = self.lays["month"]
        boundary = lay.rects[267][3]
        text = (REPO / "docs/design/lapis/preflight/README.md").read_text()
        self.assertIn(f"{boundary - 60:.0f} px", text)
        self.assertIn(f"{boundary + 60:.0f} px", text)


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
