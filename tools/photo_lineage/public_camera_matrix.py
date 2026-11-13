"""Offline seven-photo optional sample condition matrix; NEVER an auto-merge gate.

Runs the unchanged Photo DNA matcher on five pinned public examples plus one
pinned Middlebury stereo scene. Requires locally bundled scikit-image samples.
No downloads, path/pixel disclosures, file writes, or privacy-sensitive media.
"""
from __future__ import annotations

from collections import Counter
from itertools import combinations
import argparse
import json

import numpy as np

from .heldout_eval import EDIT_NAMES, _derived_edits
from .matcher import compare_arrays
from .middlebury_stereo_smoke import STEREO_SOURCES
from .public_domain_smoke import PINNED_PHOTOS, _bundled_root, _read_pinned_photo

PUBLIC_NAMES = ("astronaut", "camera", "chelsea", "clock", "coffee")
VIEW_NAMES = ("left_view", "right_view")
VERDICTS = ("candidate", "review", "unknown")
ALGORITHMS = ("orb", "sift", "akaze")


def _verdict(a: np.ndarray, b: np.ndarray, algorithm: str) -> str:
    result = compare_arrays(a, b, algorithm=algorithm)
    if result.verdict not in VERDICTS:
        raise ValueError("Unexpected matcher verdict; refuse misleading evidence")
    return result.verdict


def run(*, algorithm: str = "orb") -> dict:
    """Inspect exactly 7 pinned sample photos: 35 edits + 11 source pairs."""
    if type(algorithm) is not str or algorithm not in ALGORITHMS:
        raise ValueError("Unrecognized classical matcher")
    if set(PUBLIC_NAMES) != set(PINNED_PHOTOS) or set(VIEW_NAMES) != set(STEREO_SOURCES):
        raise ValueError("Pinned photographic cohort changed; requires protocol revision")
    root = _bundled_root()
    photos = {}
    for label in PUBLIC_NAMES:
        file_name, sha, _license_note = PINNED_PHOTOS[label]
        photos[label] = _read_pinned_photo(root / file_name, sha)
    for label in VIEW_NAMES:
        file_name, sha = STEREO_SOURCES[label]
        photos[label] = _read_pinned_photo(root / file_name, sha)
    if (photos[VIEW_NAMES[0]].shape != photos[VIEW_NAMES[1]].shape
            or np.array_equal(photos[VIEW_NAMES[0]], photos[VIEW_NAMES[1]])):
        raise ValueError("Stereo sources must be distinct camera views")

    per_source = {}
    by_edit = {name: Counter() for name in EDIT_NAMES}
    for source in (*PUBLIC_NAMES, *VIEW_NAMES):
        edits = _derived_edits(photos[source])
        if set(edits) != set(EDIT_NAMES):
            raise ValueError("Derived-edit protocol changed")
        per_source[source] = {}
        for condition in EDIT_NAMES:
            verdict = _verdict(photos[source], edits[condition], algorithm)
            per_source[source][condition] = verdict
            by_edit[condition][verdict] += 1

    different_scenes = Counter()
    for a, b in combinations(PUBLIC_NAMES, 2):
        different_scenes[_verdict(photos[a], photos[b], algorithm)] += 1
    cross_view = _verdict(photos[VIEW_NAMES[0]], photos[VIEW_NAMES[1]], algorithm)
    summary = Counter(v for row in per_source.values() for v in row.values())
    return {
        "policy": "Manual photographic-content review only; never auto-group, merge, infer shutter identity or delete",
        "algorithm": algorithm,
        "cohort": "Five individually documented CC0/public-domain samples plus one two-view Middlebury Motorcycle stereo scene",
        "source_photo_count": len(photos),
        "distinct_scene_labels_in_matrix": 6,
        "derived_edit_conditions": list(EDIT_NAMES),
        "source_derived_edit_total": len(photos) * len(EDIT_NAMES),
        "source_edit_verdicts": per_source,
        "source_edit_totals": {v: summary[v] for v in VERDICTS},
        "edit_condition_totals": {
            edit: {verdict: by_edit[edit][verdict] for verdict in VERDICTS}
            for edit in EDIT_NAMES
        },
        "real_different_scene_pairs": 10,
        "different_scene_verdicts": {v: different_scenes[v] for v in VERDICTS},
        "stereo_distinct_camera_viewpoint_pair_count": 1,
        "stereo_distinct_viewpoint_verdict": cross_view,
        "actual_same_camera_separate_shutters_tested": 0,
        "source_sha256_bytes_checked": True,
        "no_photos_or_derivatives_saved_or_uploaded": True,
        "shutter_source_identity_certified": False,
        "valid_for_deployment_false_match_rates": False,
        "limitations": (
            "Only seven pinned camera sample images and six distinct scene labels, with "
            "35 correlated source edits and 11 descriptive negatives. The Middlebury "
            "viewpoints differ in camera position, not verified same-camera shutter time. "
            "Derived edits sharing one source are NOT independent trials; no representative "
            "recall, exposure precision or S24+ latency."
        ),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--algorithm", choices=ALGORITHMS, default="orb")
    args = parser.parse_args()
    print(json.dumps(run(algorithm=args.algorithm), indent=2))


if __name__ == "__main__":
    main()
