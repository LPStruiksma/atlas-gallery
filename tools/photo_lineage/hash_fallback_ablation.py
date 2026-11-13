"""Research-only pHash sensitivity: edited-photo recovery VS same-scene collision.

Explicitly NOT a matching or review policy. Fixed small SHA-pinned optional
public camera images only; no downloads, user media, file or index writes.
"""
from __future__ import annotations

from itertools import combinations
import argparse
import json

import numpy as np

from .heldout_eval import EDIT_NAMES, _derived_edits
from .matcher import cardinal_hash_distance
from .public_camera_matrix import PUBLIC_NAMES, VIEW_NAMES
from .public_domain_smoke import PINNED_PHOTOS, _bundled_root, _read_pinned_photo
from .middlebury_stereo_smoke import STEREO_SOURCES

THRESHOLDS = (0, 4, 8, 12, 16, 24)


def _distance(a: np.ndarray, b: np.ndarray) -> int:
    value = cardinal_hash_distance(a, b)
    if type(value) is not int or not 0 <= value <= 64:
        raise ValueError("Invalid perceptual-hash Hamming distance")
    return value


def run() -> dict:
    """Compare 35 derived edits, 10 different-scene pairs and one stereo pair."""
    if set(PINNED_PHOTOS) != set(PUBLIC_NAMES) or set(STEREO_SOURCES) != set(VIEW_NAMES):
        raise ValueError("Unexpected public registry change")
    root = _bundled_root()
    photos = {}
    for name in PUBLIC_NAMES:
        filename, digest, _credit = PINNED_PHOTOS[name]
        photos[name] = _read_pinned_photo(root / filename, digest)
    for name in VIEW_NAMES:
        filename, digest = STEREO_SOURCES[name]
        photos[name] = _read_pinned_photo(root / filename, digest)
    if (photos[VIEW_NAMES[0]].shape != photos[VIEW_NAMES[1]].shape
            or np.array_equal(photos[VIEW_NAMES[0]], photos[VIEW_NAMES[1]])):
        raise ValueError("Invalid same-scene distinct camera views")
    edits_by_source = {}
    for name in PUBLIC_NAMES + VIEW_NAMES:
        variants = _derived_edits(photos[name])
        if set(variants) != set(EDIT_NAMES):
            raise ValueError("Edit protocol mismatch")
        edits_by_source[name] = {
            edit: _distance(photos[name], variants[edit]) for edit in EDIT_NAMES
        }
    different_scene_distances = {
        name_a + "__" + name_b: _distance(photos[name_a], photos[name_b])
        for name_a, name_b in combinations(PUBLIC_NAMES, 2)
    }
    stereo_distance = _distance(photos[VIEW_NAMES[0]], photos[VIEW_NAMES[1]])
    comparisons = {}
    for threshold in THRESHOLDS:
        comparisons[str(threshold)] = {
            "same_source_derived_edits_at_or_below": sum(
                d <= threshold for row in edits_by_source.values() for d in row.values()),
            "clock_source_derived_edits_at_or_below": sum(
                d <= threshold for d in edits_by_source["clock"].values()),
            "unrelated_public_scene_pairs_at_or_below": sum(
                d <= threshold for d in different_scene_distances.values()),
            "distinct_camera_stereo_pair_at_or_below": stereo_distance <= threshold,
            "no_same_exposure_confirmation": True,
        }
    return {
        "policy": "Advisory pHash collision ablation only; DO NOT use thresholds to confirm same exposure, merge, delete or automatically queue reviews",
        "hash": "64-bit DCT pHash, min distance over 0/90/180/270-degree rotations (same as matcher diagnostics)",
        "source_image_count": 7,
        "derived_edit_pairs_correlated": 35,
        "real_different_scene_pairs": 10,
        "different_camera_same_scene_pairs": 1,
        "per_public_source_edit_distances": edits_by_source,
        "unrelated_scene_distances": different_scene_distances,
        "stereo_viewpoint_distance": stereo_distance,
        "thresholds_are_exploratory_not_classification_gates": comparisons,
        "verified_shutter_independence": False,
        "representative_false_review_rate_measured": False,
        "originals_changed_or_uploaded": False,
        "limitations": "One stereo pair is different-camera views, not documented separate-time same-camera shutters. No representative gallery; no prevalence, downstream retrieval cost or threshold calibration. pHash collisions are correlated scene/content evidence, not identical shutter proof.",
    }


def main() -> None:
    argparse.ArgumentParser(description=__doc__).parse_args()
    print(json.dumps(run(), indent=2))


if __name__ == "__main__":
    main()
