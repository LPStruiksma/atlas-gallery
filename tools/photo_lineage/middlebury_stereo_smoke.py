"""Offline single real-camera same-scene stereo hard-negative smoke.

Uses independently SHA-pinned locally bundled Middlebury Motorcycle rectified
left/right images. Different camera viewpoints are NOT the same source image.
Neither these frames nor synthetic edits are uploaded/copied/modified.
Middlebury allows use/publication and requests attribution to its 2014 paper;
one stereo scene can never calibrate shutter false-link risk.
"""
from __future__ import annotations

import argparse
import json
from math import isfinite

import numpy as np

from .heldout_eval import EDIT_NAMES, _derived_edits
from .matcher import compare_arrays
from .public_domain_smoke import _bundled_root, _read_pinned_photo

STEREO_SOURCES = {
    "left_view": (
        "motorcycle_left.png",
        "db18e9c4157617403c3537a6ba355dfeafe9a7eabb6b9b94cb33f6525dd49179",
    ),
    "right_view": (
        "motorcycle_right.png",
        "5fc913ae870e42a4b662314bc904d1786bcad8e2f0b9b67dba5a229406357797",
    ),
}
DATASET_URL = "https://vision.middlebury.edu/stereo/data/scenes2014/"
USAGE_TERMS_URL = "https://vision.middlebury.edu/stereo/data/"
SCIKIT_DOC_URL = "https://scikit-image.org/docs/stable/api/skimage.data"
CITATION = (
    "Scharstein et al., High-resolution stereo datasets with subpixel-accurate "
    "ground truth, GCPR 2014. Dataset: Middlebury Motorcycle."
)


# An explicit allowlist prevents a future Evidence field from accidentally
# exposing file paths, pixel samples, source IDs or arbitrarily large arrays.
_NUMERIC_EVIDENCE_FIELDS = (
    "keypoints_a", "keypoints_b", "mutual_matches", "inliers",
    "inlier_fraction", "spread_a", "spread_b",
    "gray_correlation", "gradient_correlation",
    "overlap_a", "overlap_b", "local_consistency", "texture_tiles",
    "rotation_degrees", "scale",
)


def _public_stereo_evidence(evidence) -> dict:
    """Fail closed on impossible numbers; expose no identifiers or raw arrays.

    The full matcher rounds fractions/correlations to four decimals. Validate
    these *reported* values, without changing the classifier's verdict.
    """
    counts = ("keypoints_a", "keypoints_b", "mutual_matches",
              "inliers", "texture_tiles")
    optional = ("rotation_degrees", "scale")
    unit_intervals = (
        "inlier_fraction", "spread_a", "spread_b",
        "overlap_a", "overlap_b", "local_consistency",
    )
    correlations = ("gray_correlation", "gradient_correlation")
    out = {}
    for key in _NUMERIC_EVIDENCE_FIELDS:
        value = getattr(evidence, key)
        if (value is None and key not in optional) or (
            value is not None and (
                type(value) not in (int, float) or not isfinite(value)
                or (key in counts and type(value) is not int)
            )
        ):
            raise ValueError("Nonfinite or malformed public stereo evidence")
        out[key] = value

    if (any(out[key] < 0 for key in counts)
            or out["texture_tiles"] > 64
            or out["mutual_matches"] > min(out["keypoints_a"], out["keypoints_b"])
            or out["inliers"] > out["mutual_matches"]):
        raise ValueError("Inconsistent matcher evidence counts")
    if (any(not 0 <= out[key] <= 1 for key in unit_intervals)
            or any(not -1 <= out[key] <= 1 for key in correlations)):
        raise ValueError("Out-of-range public stereo evidence")
    # A matcher which has not reached geometric verification has zero inliers
    # and a zero fraction. Successful RANSAC rounds inliers/matches to 4dp.
    expected_fraction = (
        round(out["inliers"] / out["mutual_matches"], 4)
        if out["mutual_matches"] else 0.0
    )
    if out["inlier_fraction"] != expected_fraction:
        raise ValueError("Inconsistent matcher inlier fraction")
    if (out["rotation_degrees"] is None) != (out["scale"] is None):
        raise ValueError("Inconsistent matcher transformation evidence")
    if (out["rotation_degrees"] is not None
            and (not -180 <= out["rotation_degrees"] <= 180
                 or out["scale"] < 0)):
        raise ValueError("Out-of-range matcher transformation")
    return out


def run(*, algorithm: str = "orb") -> dict:
    """One original real stereo pair; 5 derived edits on each view."""
    if algorithm not in ("orb", "sift", "akaze"):
        raise ValueError("Only existing ORB/SIFT/AKAZE matchers allowed")
    root = _bundled_root()
    samples = {
        key: _read_pinned_photo(root / filename, digest)
        for key, (filename, digest) in STEREO_SOURCES.items()
    }
    left = samples["left_view"]
    right = samples["right_view"]
    if left.shape != right.shape or np.array_equal(left, right):
        raise ValueError("Expected distinct same-scene camera viewpoints")
    negative = compare_arrays(left, right, algorithm=algorithm)
    if negative.verdict not in ("candidate", "review", "unknown"):
        raise ValueError("Unsupported matcher outcome")
    positives = {}
    for source, image in samples.items():
        edits = _derived_edits(image)
        if set(edits) != set(EDIT_NAMES):
            raise ValueError("Heldout derived-edit protocol changed")
        verdicts = {}
        for name in EDIT_NAMES:
            verdict = compare_arrays(image, edits[name], algorithm=algorithm).verdict
            if verdict not in ("candidate", "review", "unknown"):
                raise ValueError("Unexpected matcher verdict")
            verdicts[name] = verdict
        positives[source] = verdicts
    by_edit = {
        edit: {
            verdict: sum(positives[source][edit] == verdict
                         for source in sorted(positives))
            for verdict in ("candidate", "review", "unknown")
        }
        for edit in EDIT_NAMES
    }
    return {
        "policy": (
            "Read-only ONE real stereo scene: different camera viewpoints are "
            "not the same image/exposure. Any strong geometric match is "
            "REVIEW-ONLY, never permission to auto-merge or delete."
        ),
        "algorithm": algorithm,
        "dataset": "Middlebury 2014 Motorcycle (rectified/downsampled)",
        "dataset_source": DATASET_URL,
        "rights_terms": USAGE_TERMS_URL,
        "skimage_source_notes": SCIKIT_DOC_URL,
        "citation": CITATION,
        "camera_view_count": 2,
        "distinct_viewpoint_same_scene_pair_count": 1,
        "same_scene_independent_viewpoint_verdict": negative.verdict,
        "same_scene_pair_matcher_evidence_numeric_only": _public_stereo_evidence(negative),
        "same_scene_strong_candidate_needing_review": negative.verdict == "candidate",
        "separate_camera_viewpoints_not_shutter_time_proof": True,
        "real_photo_same_scene_sample_size": 1,
        "source_derived_edit_pairs": 2 * len(EDIT_NAMES),
        "source_derived_edit_verdicts": by_edit,
        "pinned_source_file_bytes_checked": True,
        "any_images_saved_or_uploaded": False,
        "unsafe_to_certify_source_exposure_identity": True,
        "limitations": (
            "ONE Middlebury stereo scene with camera baseline/parallax. "
            "Images are RECTIFIED and downsized, not untouched sensor originals. "
            "The two cameras capture different viewpoints, not a benchmark "
            "of burst shots from one camera at different times. Ten edits "
            "are correlated within two source views. No confidence intervals, "
            "deployment precision, representative prevalence, 150k indexing, "
            "Samsung S24+ thermals or latency can follow from this sample."
        ),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--algorithm", choices=("orb", "sift", "akaze"), default="orb")
    args = parser.parse_args()
    print(json.dumps(run(algorithm=args.algorithm), indent=2))


if __name__ == "__main__":
    main()
