"""L7 Whole library handoff evidence (#824): the sheet must show main's existing handoff, not an in-place mode.

Run: python3 -m unittest discover -s tools/design/tests"""

import re
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from lapis_kit import DEVICE_H, DEVICE_W, FontSource, Fonts, px  # noqa: E402
import lapis_preflight as P  # noqa: E402

SRC = Path(P.__file__).read_text(encoding="utf-8")
L7_SRC = SRC[SRC.index("    def l7(self)"):SRC.index("    # ---", SRC.index("    def l7(self)"))]


class WholeLibraryHandoff(unittest.TestCase):
    def test_no_l7_frame_keeps_a_query_in_a_whole_library_scope(self):
        self.assertIsNone(re.search(r'scope="Whole library"', L7_SRC),
                          "an L7 frame draws Whole library as an in-place scope with a retained query")

    def test_handoff_frame_is_a_full_blank_query_frame_with_48dp_targets(self):
        gen = P.Gen(Fonts(FontSource(None, True)))
        for theme, scale in (("light", 1.0), ("dark", 2.0)):
            s = gen.l7_handoff(theme, scale)
            self.assertEqual(s.img.size, (DEVICE_W, DEVICE_H))
            self.assertTrue(s.targets)
            for t in s.targets:
                x0, y0, x1, y1 = t.box
                self.assertGreaterEqual(min(x1 - x0, y1 - y0), px(48) - 1, t.label)

    def test_transition_table_covers_required_states_and_columns(self):
        self.assertEqual(len(P.L7_TRANSITION_HEAD), len(P.L7_TRANSITION_W))
        self.assertAlmostEqual(sum(P.L7_TRANSITION_W), 1.0, places=6)
        for row in P.L7_TRANSITIONS:
            self.assertEqual(len(row), len(P.L7_TRANSITION_HEAD))
        text = " ".join(" ".join(r) for r in P.L7_TRANSITIONS).lower()
        for needle in ("collapsed", "ime hidden", "with a query", "empty query", "global search", "back from",
                       "person", "album", "declutter", "trash", "blank"):
            self.assertIn(needle, text)

    def test_sheet_has_b5_and_transition_table_and_unchanged_panel_codes(self):
        board = P.Gen(Fonts(FontSource(None, True))).l7()
        self.assertEqual(board.panel_codes, ["A1", "A2"] + [f"B{i}" for i in range(1, 17)])
        self.assertIn("Search state transitions (#824)", L7_SRC)


if __name__ == "__main__":
    unittest.main()
