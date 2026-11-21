"""L6 pre-build accessibility and motion contract (#817).

Uses only synthetic frames and the bundled fallback fonts; this is not Android
or device acceptance. Rendered PNG hashes are checked separately.
"""
from __future__ import annotations

import sys
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

HERE = Path(__file__).resolve().parents[1]
ROOT = HERE.parents[1]
sys.path.insert(0, str(HERE))

import lapis_screens as L  # noqa: E402
from lapis_kit import Fonts, FontSource, px  # noqa: E402
from lapis_preflight import Gen  # noqa: E402


class L6PrimaryMonthTargets(unittest.TestCase):
    def setUp(self):
        self.gen = Gen(Fonts(FontSource(None, True)))

    def verify_accessible(self, screen):
        months = [t for t in screen.targets if t.label.startswith("month ")]
        self.assertEqual([t.label for t in months], [f"month {m}" for m in L.MONTHS])
        for index, one in enumerate(months):
            left, top, right, bottom = one.box
            self.assertGreaterEqual(right - left, px(48) - 1, one.label)
            self.assertGreaterEqual(bottom - top, px(48) - 1, one.label)
            self.assertGreaterEqual(left, 0)
            self.assertGreaterEqual(top, screen.top)
            self.assertLessEqual(right, screen.img.width)
            self.assertLessEqual(bottom, screen.bottom)
            for other in months[index + 1:]:
                a, b, c, d = other.box
                self.assertFalse(
                    min(right, c) - max(left, a) > 1
                    and min(bottom, d) - max(top, b) > 1,
                    (one.label, other.label),
                )
        # Two chronological rows of six, rather than the unsafe 12-column strip.
        self.assertEqual(len({int(t.box[1]) for t in months[:6]}), 1)
        self.assertEqual(len({int(t.box[1]) for t in months[6:]}), 1)
        self.assertNotEqual(months[0].box[1], months[6].box[1])

    def test_every_primary_open_atlas_state_uses_accessible_targets(self):
        for theme, scale in (("light", 1.0), ("dark", 2.0)):
            for state in (
                {},
                {"year": "2019", "counts": [0, 0, 40, 0, 0, 220, 610, 0, 0, 0, 15, 0], "current": 6},
                {"no_geo": True},
                {"dense": True},
                {"counts": [9120, 8400, 11020, 12804, 15500, 18210, 22340, 21900,
                            13000, 7800, 6100, 2718]},
            ):
                with self.subTest(theme=theme, scale=scale, state=state):
                    self.verify_accessible(self.gen.l6_frame(theme, scale, frac=1.0, **state))

    def test_places_have_effective_48dp_targets_too(self):
        for theme, scale in (("light", 1.0), ("dark", 2.0)):
            frame = self.gen.l6_frame(theme, scale, frac=1.0)
            places = [t for t in frame.targets if t.label.startswith("place ")]
            self.assertEqual(len(places), 3)
            for t in places:
                a, b, c, d = t.box
                self.assertGreaterEqual(c - a, px(48) - 1)
                self.assertGreaterEqual(d - b, px(48) - 1)
                self.assertGreaterEqual(a, 0)
                self.assertLessEqual(c, frame.img.width)
            self.assertFalse(any(t.label.startswith("place ") for t in
                                 self.gen.l6_frame(theme, scale, frac=1.0, no_geo=True).targets))

    def test_200_percent_text_clears_targets_and_labels_do_not_collide(self):
        for theme, scale in (("light", 1.0), ("dark", 2.0)):
            frame = self.gen.l6_frame(theme, scale, frac=1.0)
            months = [t for t in frame.targets if t.label.startswith("month ")]
            self.assertEqual(len(months), 12)
            first_row_top = min(t.box[1] for t in months)
            self.assertLessEqual(frame.year_bottom, first_row_top,
                                 "year/total text must stay above the month targets")
            chips = frame.label_chips
            self.assertEqual(len(chips), 3)
            for i, (_, a) in enumerate(chips):
                self.assertGreaterEqual(a[0], 0)
                self.assertLessEqual(a[2], frame.img.width)
                for _, b in chips[i + 1:]:
                    self.assertTrue(a[2] <= b[0] or b[2] <= a[0] or a[3] <= b[1] or b[3] <= a[1],
                                    "place labels overlap")

    def test_month_glyph_bounds_fit_target_at_full_200_percent(self):
        # Test rendered glyph rectangles, not just geometric target boxes.
        for theme, scale in (("light", 1.0), ("dark", 2.0)):
            for state in (
                {},
                {"year": "2019", "counts": [0, 0, 40, 0, 0, 220, 610, 0, 0, 0, 15, 0],
                 "current": 6},
            ):
                with self.subTest(theme=theme, scale=scale, state=state):
                    frame = self.gen.l6_frame(theme, scale, frac=1.0, **state)
                    targets = [t for t in frame.targets if t.label.startswith("month ")]
                    self.assertEqual(len(frame.month_label_boxes), 12)
                    for (name, glyph), target in zip(frame.month_label_boxes, targets):
                        self.assertEqual(target.label, f"month {name}")
                        left, top, right, bottom = target.box
                        self.assertGreaterEqual(glyph[0], left - 1, name)
                        self.assertGreaterEqual(glyph[1], top - 1, name)
                        self.assertLessEqual(glyph[2], right + 1, name)
                        self.assertLessEqual(glyph[3], bottom + 1, name)

    def test_metadata_failure_is_a_recoverable_atlas_overlay(self):
        frame = self.gen.l6_frame("dark", frac=1.0, error=True)
        self.assertTrue(frame.atlas_error)
        labels = [t.label for t in frame.targets]
        self.assertIn("try atlas again", labels)
        self.assertIn("close atlas (Back)", labels)
        self.assertFalse(any(name.startswith(("month ", "place ")) for name in labels),
                         "failed metadata must not expose stale month or place actions")
        retry = next(t for t in frame.targets if t.label == "try atlas again")
        close = next(t for t in frame.targets if t.label == "close atlas (Back)")
        for t in (retry, close):
            left, top, right, bottom = t.box
            self.assertGreaterEqual(right - left, px(48) - 1)
            self.assertGreaterEqual(bottom - top, px(48) - 1)
            self.assertGreaterEqual(top, frame.top)
            self.assertLessEqual(bottom, frame.slab_box[1])
        self.assertLessEqual(retry.box[3], frame.slab_box[1],
                             "Pictures grid and bottom slab remain reachable after the atlas")

    def test_dense_cluster_is_one_48dp_target(self):
        for theme, scale in (("light", 1.0), ("dark", 2.0)):
            frame = self.gen.l6_frame(theme, scale, frac=1.0, dense=True)
            clusters = [t for t in frame.targets if t.label == "place cluster 1.240"]
            self.assertEqual(len(clusters), 1)
            a, b, c, d = clusters[0].box
            self.assertGreaterEqual(c - a, px(48) - 1)
            self.assertGreaterEqual(d - b, px(48) - 1)
            others = [t for t in frame.targets if t is not clusters[0]]
            for o in others:
                oa, ob, oc, od = o.box
                self.assertTrue(oc <= a or oa >= c or od <= b or ob >= d, o.label)

    def test_motion_proof_is_timed_synthetic_and_reversible(self):
        path = ROOT / "docs/design/lapis/preflight/brand-l6-pull-motion.svg"
        root = ET.parse(path).getroot()
        text = path.read_text(encoding="utf-8")
        self.assertIn("SYNTHETIC", text)
        self.assertIn("remove animations", text.lower())
        ns = "{http://www.w3.org/2000/svg}"
        animation = [e for e in root.iter() if e.tag in (ns + "animate", ns + "animateTransform")]
        self.assertGreaterEqual(len(animation), 2)
        for a in animation:
            self.assertIn("dur", a.attrib)
            self.assertIn("values", a.attrib)
        # The remove-animations cut must be true discrete jumps: two equal
        # consecutive keyTimes, so no interpolation happens between states.
        for a in animation:
            kts = [float(x) for x in a.attrib["keyTimes"].split(";")]
            vals = a.attrib["values"].split(";")
            self.assertEqual(len(kts), len(vals))
            jumps = [i for i in range(1, len(kts)) if kts[i] == kts[i - 1]]
            self.assertEqual(len(jumps), 2, "closed->open and open->closed cuts")
            for i in jumps:
                self.assertNotEqual(vals[i], vals[i - 1])
            # the cut segment shows the open state for a hold, not a ramp
            self.assertEqual(vals[jumps[0] + 1], vals[jumps[0]])
        self.assertIn("cancel", text.lower())
        self.assertIn("release", text.lower())


if __name__ == "__main__":
    unittest.main()
