"""One-slab geometry and fast-scroll recording checks for Lapis L4 (#816).

Run: python3 -m unittest discover -s tools/design/tests
Synthetic only; uses the DejaVu fallback font for layout, plus the committed GIF and manifest."""

import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from lapis_kit import DENSITY, DEVICE_H, DEVICE_W, IME_DP, NAV_DP, FontSource, Fonts, Screen, px  # noqa: E402
import lapis_fastscroll as FS  # noqa: E402

PREFLIGHT = Path(__file__).resolve().parents[3] / "docs/design/lapis/preflight"


def _screen(theme, scale):
    return Screen(Fonts(FontSource(None, True)), theme=theme, font_scale=scale)


def _overlap(a, b):
    return min(a[2], b[2]) - max(a[0], b[0]) > 0 and min(a[3], b[3]) - max(a[1], b[1]) > 0


class OneSlab(unittest.TestCase):
    CASES = [(t, f) for t in ("light", "dark") for f in (1.0, 1.3, 1.5, 2.0)]

    def _check(self, s, box):
        x0, y0, x1, y1 = box
        self.assertEqual(set(s.slab_targets), {"Pictures", "Collections", "Search"})
        self.assertLessEqual(x1 - x0, DEVICE_W - px(16))
        self.assertAlmostEqual((x0 + x1) / 2, DEVICE_W / 2, delta=2)  # centred
        boxes = list(s.slab_targets.values())
        for name, b in s.slab_targets.items():
            self.assertGreaterEqual(b[2] - b[0], px(48) - 1, name)
            self.assertGreaterEqual(b[3] - b[1], px(48) - 1, name)
            self.assertTrue(x0 <= b[0] and b[2] <= x1 and y0 <= b[1] and b[3] <= y1, f"{name} leaves the slab")
        for i, a in enumerate(boxes):
            for b in boxes[i + 1:]:
                self.assertFalse(_overlap(a, b), (a, b))

    def test_pictures_collections_and_search_stay_in_one_slab(self):
        for theme, scale in self.CASES:
            for active in ("Pictures", "Collections"):
                with self.subTest(theme=theme, scale=scale, active=active):
                    s = _screen(theme, scale)
                    self._check(s, s.slab(active))

    def test_target_boxes_match_recorded_targets(self):
        s = _screen("dark", 2.0)
        s.slab("Pictures")
        recorded = {t.label: t.box for t in s.targets}
        for name, b in s.slab_targets.items():
            self.assertEqual(tuple(recorded[name]), tuple(b))

    def test_slab_sits_above_gesture_inset_and_keyboard(self):
        s = _screen("light", 2.0)
        box = s.slab("Pictures")
        self.assertLessEqual(box[3], DEVICE_H - px(NAV_DP) - px(12) + 1)
        kb_top = s.keyboard()
        self.assertEqual(kb_top, s.bottom - px(IME_DP))
        s2 = _screen("light", 2.0)
        sb = s2.slab("Collections", y_bottom=kb_top, search=False)
        self.assertLessEqual(sb[3], kb_top - px(12) + 1)
        self.assertEqual(set(s2.slab_targets), {"Pictures", "Collections"})

    def test_slab_stays_short_even_when_the_sphere_wraps(self):
        s = _screen("dark", 2.0)
        box = s.slab("Pictures", places=("Pictures", "Collections", "Albums"))
        self.assertTrue(s.slab_wrapped)
        self._check_wrapped(s, box)

    def _check_wrapped(self, s, box):
        self._check_membership_only(s, box)
        self.assertLess((box[3] - box[1]) / DENSITY, 200)

    def _check_membership_only(self, s, box):
        for b in s.slab_targets.values():
            self.assertTrue(box[0] <= b[0] and b[2] <= box[2] and box[1] <= b[1] and b[3] <= box[3])
        boxes = list(s.slab_targets.values())
        for i, a in enumerate(boxes):
            for b in boxes[i + 1:]:
                self.assertFalse(_overlap(a, b))

    def test_no_detached_sphere_left_in_the_generator(self):
        src = (Path(__file__).resolve().parents[1] / "lapis_kit.py").read_text()
        self.assertNotIn("slab_detached", src)  # no detached-sphere state
        self.assertNotIn("search sphere detaches", src)


class FastScrollRecording(unittest.TestCase):
    def test_script_moves_continuously_and_crosses_three_months(self):
        fr = FS.THUMB_FRACTIONS
        self.assertTrue(all(b >= a for a, b in zip(fr, fr[1:])))
        self.assertEqual(len(set(fr[:-FS.SETTLED_FRAMES])), len(fr) - FS.SETTLED_FRAMES)
        self.assertEqual(fr[-1], fr[-2])  # settled
        self.assertEqual(FS.thumb_months()[0], "Nov")
        self.assertEqual(FS.thumb_months()[-1], "Mar")
        self.assertEqual(set(FS.thumb_months()), {"Nov", "Aug", "Mar"})

    def test_committed_recording_matches_manifest(self):
        man = json.loads((PREFLIGHT / "manifest.json").read_text())
        entry = man["recordings"]["l4_fast_scroll"]
        self.assertEqual(FS.problems(entry, PREFLIGHT / entry["file"]), [])
        self.assertEqual(entry["size"], [DEVICE_W // FS.SCALE, DEVICE_H // FS.SCALE])
        self.assertTrue(entry["synthetic_only"])

    def test_gif_is_playable_and_frames_differ(self):
        from PIL import Image, ImageChops
        with Image.open(PREFLIGHT / FS.GIF_NAME) as im:
            self.assertEqual(im.n_frames, len(FS.THUMB_FRACTIONS))
            frames = []
            for k in range(im.n_frames):
                im.seek(k)
                frames.append(im.convert("RGB"))
        for a, b in zip(frames, frames[1:]):
            self.assertIsNotNone(ImageChops.difference(a, b).getbbox())


if __name__ == "__main__":
    unittest.main()
