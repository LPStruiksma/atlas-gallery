"""Pure synthetic capture/scene edit-retention regression tests."""
import unittest

from tools.photo_lineage.edit_retention import EDITS, summarize_edit_retention


def scores(default='review', **overrides):
    return {x: overrides.get(x, default) for x in EDITS}


class EditRetentionTests(unittest.TestCase):
    def test_all_reviewable_does_not_claim_perfect_with_two_scenes(self):
        result = summarize_edit_retention([[scores('candidate'), scores()], [scores()]])
        self.assertEqual(result['captures_all_five_edits_reviewable'], 3)
        self.assertEqual(result['scenes_all_captures_all_edits_reviewable'], 2)
        self.assertGreater(result['scene_any_edit_unknown_wilson95_upper'], 0.6)
        self.assertLess(result['scene_complete_edit_recall_wilson95_lower'], 0.4)

    def test_eight_capture_correlated_scene_counts_once(self):
        result = summarize_edit_retention([[scores('candidate', cropped='unknown')]*8,
                                           [scores('review')]])
        self.assertEqual(result['captures_correlated'], 9)
        self.assertEqual(result['scenes_any_edit_unknown'], 1)
        self.assertEqual(result['per_edit']['cropped']['unknown'], 8)
        self.assertEqual(result['per_edit']['cropped']['scenes_with_unknown'], 1)
        self.assertEqual(result['per_edit']['rotated_crop']['scenes_with_unknown'], 0)

    def test_single_rotation_failure_not_hidden_by_four_candidates(self):
        result = summarize_edit_retention([[scores('candidate', rotated_crop='unknown')]])
        self.assertEqual(result['captures_all_five_edits_reviewable'], 0)
        self.assertEqual(result['scenes_all_captures_all_edits_reviewable'], 0)
        self.assertEqual(result['per_edit']['rotated_crop']['unknown'], 1)
        self.assertEqual(result['per_edit']['gamma']['candidate'], 1)

    def test_exact_five_declared_edits_required(self):
        malformed = ({}, {'cropped': 'review'}, dict(scores(), unexpected='review'),
                     dict(scores(), cropped='unrelated'))
        for data in malformed:
            with self.subTest(data=data), self.assertRaises(ValueError):
                summarize_edit_retention([[data]])

    def test_empty_and_bounds(self):
        for sample in ([], [[]], [[scores()]*9], [[scores()]]*21):
            with self.subTest(scenes=len(sample)), self.assertRaises(ValueError):
                summarize_edit_retention(sample)

    def test_generator_inputs(self):
        result = summarize_edit_retention((iter([scores()]) for _ in range(3)))
        self.assertEqual(result['scenes'], 3)
        self.assertEqual(result['per_edit']['resized']['review'], 3)


if __name__ == '__main__':
    unittest.main()
