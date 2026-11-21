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

    def test_motion_proof_is_timed_synthetic_and_reversible(self):
        path = ROOT / "docs/design/lapis/preflight/brand-l6-pull-motion.svg"
        root = ET.parse(path).getroot()
        text = path.read_text(encoding="utf-8")
        self.assertIn("SYNTHETIC", text)
        self.assertIn("remove animations", text.lower())
        animation = list(root.iter("{http://www.w3.org/2000/svg}animate"))
        self.assertGreaterEqual(len(animation), 2)
        for a in animation:
            self.assertIn("dur", a.attrib)
            self.assertIn("values", a.attrib)
        self.assertIn("cancel", text.lower())
        self.assertIn("release", text.lower())


if __name__ == "__main__":
    unittest.main()
