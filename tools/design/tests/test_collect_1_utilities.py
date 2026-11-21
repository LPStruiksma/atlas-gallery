"""Determinism, geometry and privacy checks for COLLECT-1."""

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import collect_1_utilities as collect  # noqa: E402
from lapis_kit import DEVICE_H, DEVICE_W, FontSource, Fonts, px  # noqa: E402


class CollectOneUtilities(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.board = collect.build(Fonts(FontSource(None, True)))
        cls.image = cls.board.render()

    def test_render_has_fixed_dimensions_and_panel_order(self):
        collect.validate(self.board, self.image)
        self.assertEqual(self.image.width, 3480)
        self.assertGreater(self.image.height, DEVICE_H)

    def test_every_screen_and_target_stays_in_device_bounds(self):
        board = collect.build(Fonts(FontSource(None, True)))
        screens = board.generated_screens
        self.assertEqual(len(screens), len(collect.PANELS))
        for screen in screens:
            self.assertEqual(screen.img.size, (DEVICE_W, DEVICE_H))
            for target in screen.targets:
                x0, y0, x1, y1 = target.box
                self.assertGreaterEqual(x0, 0, target.label)
                self.assertGreaterEqual(y0, 0, target.label)
                self.assertLessEqual(x1, DEVICE_W, target.label)
                self.assertLessEqual(y1, DEVICE_H, target.label)
                self.assertGreaterEqual(min(x1 - x0, y1 - y0), px(48) - 1, target.label)

    def test_categories_are_fixed_and_check_rejects_asset_readers(self):
        self.assertEqual(collect.CATEGORIES, (
            "Videos", "Screenshots", "Recently added", "Selfies", "Panoramas", "Slow motion", "Large files"
        ))
        collect._source_is_safe()

    def test_confirmed_zero_categories_are_not_navigable_rows(self):
        # setUpClass initialized the shared synthetic font source.
        zero = collect.collections(zero_case=True)
        labels = [t.label for t in zero.targets]
        self.assertFalse(any("Recently added," in label for label in labels))
        self.assertFalse(any("Selfies," in label for label in labels))
        self.assertTrue(any("Videos," in label for label in labels))


if __name__ == "__main__":
    unittest.main()
