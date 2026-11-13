#!/usr/bin/env python3
"""Offline checks for the pose descriptor and the detector anchors.

Run: python3 -m unittest discover -s tools/visual_search_bench -p 'test_*.py'
Needs numpy only (no models, no network, no images).
"""
import os
import sys
import unittest

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pose_descriptor as pd  # noqa: E402


def skeleton(arms_up=False, inverted=False):
    """A synthetic 33-point body, metric, y down, hip-centred."""
    p = np.zeros((33, 3), np.float32)
    sx, hx = 0.18, 0.12
    p[pd.L_SH], p[pd.R_SH] = [sx, -0.5, 0], [-sx, -0.5, 0]
    p[pd.L_HIP], p[pd.R_HIP] = [hx, 0, 0], [-hx, 0, 0]
    p[pd.L_KN], p[pd.R_KN] = [hx, 0.45, 0.05], [-hx, 0.45, 0]
    p[pd.L_AN], p[pd.R_AN] = [hx, 0.9, 0], [-hx, 0.9, 0]
    if arms_up:
        p[pd.L_EL], p[pd.R_EL] = [sx + 0.05, -0.8, 0], [-sx - 0.05, -0.8, 0]
        p[pd.L_WR], p[pd.R_WR] = [sx + 0.1, -1.1, 0], [-sx - 0.1, -1.1, 0]
    else:
        p[pd.L_EL], p[pd.R_EL] = [sx + 0.3, -0.5, 0], [-sx - 0.05, -0.2, 0]
        p[pd.L_WR], p[pd.R_WR] = [sx + 0.6, -0.5, 0], [-sx - 0.05, 0.1, 0]
    if inverted:
        p[:, 1] = -p[:, 1]
    return p


def yaw(p, deg):
    a = np.deg2rad(deg)
    r = np.array([[np.cos(a), 0, np.sin(a)], [0, 1, 0], [-np.sin(a), 0, np.cos(a)]], np.float32)
    return p @ r.T


class PoseDescriptorTest(unittest.TestCase):
    def sim(self, a, b):
        return pd.similarity(pd.descriptor(a), pd.descriptor(b))

    def test_self_similarity_is_one(self):
        self.assertAlmostEqual(self.sim(skeleton(), skeleton()), 1.0, places=5)

    def test_mirror_invariant(self):
        # A horizontally flipped photo: x negated, and the pose model then
        # labels the person's former left limbs as right ones.
        p = skeleton()
        m = p.copy()
        m[:, 0] = -m[:, 0]
        perm = np.arange(33)
        for k, v in pd.SWAP.items():
            perm[k] = v
        self.assertAlmostEqual(self.sim(p, m[perm]), 1.0, places=5)

    def test_yaw_invariant(self):
        p = skeleton()
        for deg in (30, 90, 160):
            self.assertAlmostEqual(self.sim(p, yaw(p, deg)), 1.0, places=4)

    def test_gravity_kept_inverted_differs(self):
        self.assertLess(self.sim(skeleton(arms_up=True), skeleton(arms_up=True, inverted=True)), 0.0)

    def test_arm_pose_changes_score(self):
        a, b = skeleton(arms_up=True), skeleton(arms_up=False)
        self.assertLess(self.sim(a, b), 0.9)
        self.assertGreater(self.sim(a, b), self.sim(a, skeleton(arms_up=True, inverted=True)))

    def test_matrix_matches_pairwise_and_marks_missing(self):
        ps = [skeleton(), skeleton(arms_up=True), skeleton(inverted=True)]
        desc = np.stack([pd.descriptor(p) for p in ps] + [np.zeros((2, pd.DIM), np.float32)])
        present = np.array([True, True, True, False])
        s = pd.similarity_matrix(desc, present)
        for i in range(3):
            for j in range(3):
                self.assertAlmostEqual(s[i, j], pd.similarity(desc[i], desc[j]), places=5)
        self.assertTrue(np.isnan(s[3]).all() and np.isnan(s[:, 3]).all())


class AnchorTest(unittest.TestCase):
    def test_anchor_grid(self):
        try:
            from blazepose import ssd_anchors
        except ImportError as e:  # cv2/onnxruntime not installed: skip, not fail
            self.skipTest(str(e))
        a = ssd_anchors()
        self.assertEqual(a.shape, (2254, 2))
        self.assertAlmostEqual(float(a[0, 0]), 0.5 / 28, places=6)
        self.assertAlmostEqual(float(a[-1, 1]), 6.5 / 7, places=6)


if __name__ == "__main__":
    unittest.main()
