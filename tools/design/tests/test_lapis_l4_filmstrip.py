"""Viewer F1 filmstrip hit-target invariants for Lapis L4 (#889).

Run: python3 -m unittest discover -s tools/design/tests
Synthetic only; the DejaVu fallback font is enough, the filmstrip has no text."""

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from lapis_kit import DEVICE_W, FontSource, Fonts, Screen, px  # noqa: E402
import lapis_screens as L  # noqa: E402

CASES = [(t, f) for t in ("light", "dark") for f in (1.0, 2.0)]


def _viewer(theme, scale):
    s = Screen(Fonts(FontSource(None, True)), theme=theme, font_scale=scale, viewer=True)
    L.viewer(s)
    return s


def _overlap(a, b):
    return min(a[2], b[2]) - max(a[0], b[0]) > 0 and min(a[3], b[3]) - max(a[1], b[1]) > 0


def _strip(s):
    return sorted((t.box for t in s.targets if t.label == "filmstrip item"), key=lambda b: b[0])


class ViewerFilmstrip(unittest.TestCase):
    def test_seven_distinct_48dp_targets_that_do_not_overlap(self):
        for theme, scale in CASES:
            with self.subTest(theme=theme, scale=scale):
                boxes = _strip(_viewer(theme, scale))
                self.assertEqual(len(boxes), 7)
                for b in boxes:
                    self.assertGreaterEqual(b[2] - b[0], px(48) - 1)
                    self.assertGreaterEqual(b[3] - b[1], px(48) - 1)
                for a, b in zip(boxes, boxes[1:]):
                    self.assertLessEqual(a[2], b[0], "adjacent effective targets overlap")
                for i, a in enumerate(boxes):
                    for b in boxes[i + 1:]:
                        self.assertFalse(_overlap(a, b), (a, b))
                pitches = {(b[0] + b[2]) / 2 - (a[0] + a[2]) / 2 for a, b in zip(boxes, boxes[1:])}
                self.assertTrue(all(abs(p - px(48)) <= 1 for p in pitches), pitches)  # >= 48 dp pitch

    def test_targets_are_inside_the_usable_viewport_and_centred(self):
        for theme, scale in CASES:
            with self.subTest(theme=theme, scale=scale):
                s = _viewer(theme, scale)
                boxes = _strip(s)
                self.assertGreaterEqual(boxes[0][0], 0)
                self.assertLessEqual(boxes[-1][2], DEVICE_W)
                self.assertAlmostEqual((boxes[0][0] + boxes[-1][2]) / 2, DEVICE_W / 2, delta=2)
                actions = [t.box for t in s.targets if t.label in L.VIEWER_ACTIONS]
                for b in boxes:
                    self.assertGreaterEqual(b[1], s.top)
                    self.assertLessEqual(b[3], s.bottom)
                    for a in actions:
                        self.assertFalse(_overlap(b, a), "filmstrip target collides with an action")

    def test_current_item_ring_and_sphere_stay_attached(self):
        s = _viewer("light", 1.0)
        boxes = _strip(s)
        cur = boxes[3]
        cx, cy = (cur[0] + cur[2]) // 2, (cur[1] + cur[3]) // 2
        rgb = s.img.convert("RGB")
        self.assertEqual(rgb.getpixel((cx - px(20) - px(1), cy)), (255, 255, 255))  # ring left edge
        self.assertEqual(rgb.getpixel((cx + px(20) + px(1), cy)), (255, 255, 255))  # ring right edge
        sphere = rgb.getpixel((cx, cur[1] + (px(52) - (cur[3] - cur[1])) // 2 - px(11)))
        self.assertEqual("#%02X%02X%02X" % sphere, L.VIEWER_SPHERE.upper())
        for k, b in enumerate(boxes):  # only the current item carries the ring
            if k != 3:
                bx = (b[0] + b[2]) // 2 - px(20) - px(1)
                self.assertNotEqual(rgb.getpixel((bx, cy)), (255, 255, 255), k)


if __name__ == "__main__":
    unittest.main()
