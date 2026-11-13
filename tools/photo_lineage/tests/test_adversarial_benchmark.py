"""Deterministic, non-destructive, condition-specific benchmark tests."""
import unittest
import numpy as np

from tools.photo_lineage.benchmark_adversarial import (
    NEGATIVE_CONDITIONS, POSITIVE_CONDITIONS, evaluate, make_cases,
)


class AdversarialBenchmarkTest(unittest.TestCase):
    def test_bounded_inputs(self):
        for seeds in (0, 31):
            with self.subTest(seeds=seeds), self.assertRaisesRegex(ValueError, 'seeds'):
                evaluate(seeds=seeds)
        with self.assertRaisesRegex(ValueError, 'algorithm'):
            evaluate(seeds=1, algorithm='nonexistent')
        with self.assertRaisesRegex(ValueError, 'size'):
            make_cases(1, size=40)
        with self.assertRaisesRegex(ValueError, 'seed'):
            make_cases(-1)

    def test_conditions_labels_and_input_immutability(self):
        cases = make_cases(707, size=480)
        labels = {name for name, _, _, _ in cases}
        self.assertEqual(labels, set(POSITIVE_CONDITIONS) | set(NEGATIVE_CONDITIONS))
        for name, same_exposure, a, b in cases:
            with self.subTest(name=name):
                self.assertEqual(a.dtype, np.uint8)
                self.assertEqual(b.dtype, np.uint8)
                self.assertEqual(a.ndim, 2)
                self.assertEqual(b.ndim, 2)
                self.assertEqual(same_exposure, name in POSITIVE_CONDITIONS)
                self.assertGreater(min(a.shape), 47)
                self.assertGreater(min(b.shape), 47)

    def test_counts_expose_abstention_and_strong_false_candidates(self):
        report = evaluate(seeds=2, algorithm='orb', include_cases=True)
        self.assertEqual(report['same_exposure_synthetic_pairs'], 2 * len(POSITIVE_CONDITIONS))
        self.assertEqual(report['different_exposure_proxy_pairs'], 2 * len(NEGATIVE_CONDITIONS))
        self.assertEqual(len(report['case_results']), 2 * (len(POSITIVE_CONDITIONS) + len(NEGATIVE_CONDITIONS)))
        for condition, data in report['condition_results'].items():
            with self.subTest(condition=condition):
                self.assertEqual(data['cases'], 2)
                self.assertEqual(data['candidate'] + data['review'] + data['unknown'], 2)
                self.assertGreaterEqual(data['candidate_rate'], 0.0)
                self.assertLessEqual(data['abstention_rate'], 1.0)
        self.assertEqual(report['condition_results']['independent_scene']['candidate'], 0)
        self.assertEqual(report['condition_results']['shared_caption']['candidate'], 0)
        # Deliberately DO NOT assert that all same-scene proxy negatives are
        # rejected; revealing that failure is the purpose of this benchmark.

    def test_repeatable_per_case_evidence(self):
        # Only deterministic observables; runtime measurements may vary.
        a = evaluate(seeds=1, algorithm='orb', include_cases=True)
        b = evaluate(seeds=1, algorithm='orb', include_cases=True)
        without_timing = lambda rows: [(r['seed'], r['condition'], r['verdict'], r['inliers'],
                                        r['local_consistency']) for r in rows]
        self.assertEqual(without_timing(a['case_results']), without_timing(b['case_results']))


if __name__ == '__main__':
    unittest.main()
