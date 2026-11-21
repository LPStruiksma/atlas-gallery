"""Checks for the SAVED-1 synthetic board generator (#779).

Run: python3 -m unittest discover -s tools/design/tests
Uses the DejaVu fallback font, so it needs no Funnel files and no network."""

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from lapis_kit import DEVICE_H, DEVICE_W, FontSource, Fonts, px  # noqa: E402
import saved1_board as S  # noqa: E402

LIB = [
    {"id": 1, "tags": {("place", "Alps"), ("year", "2026")}},
    {"id": 2, "tags": {("place", "Tirol"), ("year", "2026")}},
    {"id": 3, "tags": {("place", "Alps"), ("year", "2025"), ("type", "Screenshots")}},
    {"id": 4, "tags": {("place", "Zeeland"), ("year", "2026")}},
]


class Semantics(unittest.TestCase):
    def ids(self, positives, excludes=frozenset()):
        return [i["id"] for i in S.match(LIB, positives, set(excludes))]

    def test_or_within_family_and_across_families(self):
        self.assertEqual(self.ids({"place": {"Alps", "Tirol"}, "year": {"2026"}}), [1, 2])

    def test_excluded_union_is_subtracted(self):
        self.assertEqual(self.ids({"place": {"Alps", "Tirol"}}, {("type", "Screenshots"), ("year", "2025")}), [1, 2])

    def test_contradiction_is_zero_matches(self):
        self.assertEqual(self.ids({"place": {"Alps"}}, {("place", "Alps")}), [])

    def test_unsorted_is_membership_in_no_manual_album(self):
        albums = {"Family trips": {2}, "Alps hikes": {2, 3}}
        self.assertEqual([i["id"] for i in S.unsorted(LIB, albums)], [1, 4])
        albums["Family trips"].add(1)  # adding to an album removes it from Unsorted
        self.assertEqual([i["id"] for i in S.unsorted(LIB, albums)], [4])
        self.assertEqual(len(LIB), 4)  # nothing is deleted or moved


class Board(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.board, cls.frames = S.build(Fonts(FontSource(None, True)))

    def test_every_frame_is_a_full_device_frame(self):
        self.assertEqual(self.board.frames_checked, len(self.frames))
        self.assertGreaterEqual(len(self.frames), 30)

    def test_panels_a_to_g_each_have_a_light_and_a_dark_pair(self):
        grid = [f for f in self.frames if f["scale_on_board"] == "50%"]
        for panel in "ABCDEFG":
            themes = {f["theme"] for f in grid if f["code"].startswith(panel)}
            self.assertEqual(themes, {"light", "dark"}, panel)

    def test_required_full_scale_frames(self):
        heroes = {(f["theme"], f["type_scale_percent"]) for f in self.frames if f["scale_on_board"] == "100%"}
        self.assertIn(("light", 100), heroes)
        self.assertIn(("dark", 200), heroes)

    def test_all_targets_meet_48dp(self):
        for f in self.frames:
            for t in f["targets"]:
                self.assertGreaterEqual(min(t["size_dp"]), 48, (f["code"], t["label"]))
                self.assertGreaterEqual(min(t["box_px"][2] - t["box_px"][0], t["box_px"][3] - t["box_px"][1]),
                                        px(48) - 1)

    def test_required_actions_are_present_as_targets(self):
        labels = {t["label"] for f in self.frames for t in f["targets"]}
        for needed in ("Save as album", "Retry", "Clear exclusion", "Remove saved album", "Cancel", "Undo",
                       "Exclude", "Include", "Not: Screenshots"):
            self.assertIn(needed, labels)

    def test_generation_is_deterministic(self):
        again, _ = S.build(Fonts(FontSource(None, True)))
        self.assertEqual(self.board.render().tobytes(), again.render().tobytes())

    def test_frame_size_constants(self):
        self.assertEqual((DEVICE_W, DEVICE_H), (1080, 2340))


if __name__ == "__main__":
    unittest.main()
