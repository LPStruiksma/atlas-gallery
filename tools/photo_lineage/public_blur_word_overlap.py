"""Pinned public blur-versus-ORB-word eligibility; never infers shutter identity."""
from __future__ import annotations
import argparse
import json
import numpy as np

from .blur_robustness_matrix import CONDITIONS, SOURCES, STEREO_NAMES, apply_blur
from .public_domain_smoke import PINNED_PHOTOS, _bundled_root, _read_pinned_photo
from .middlebury_stereo_smoke import STEREO_SOURCES
from .retrieval import words_for_image, BANDS, KEYPOINTS, FINGERPRINT_VERSION

EXPECTED_FINGERPRINT = "orb-96-4x24-v1-800px"


def _validated_words(array: np.ndarray) -> set[int]:
    words = words_for_image(array)
    if (type(words) is not set or len(words) > KEYPOINTS * len(BANDS)
            or any(type(x) is not int or not 0 <= x < 1 << 26 for x in words)):
        raise ValueError("Unexpected bounded ORB visual-word output")
    return words


def run() -> dict:
    if (FINGERPRINT_VERSION != EXPECTED_FINGERPRINT or len(CONDITIONS) != 6
            or len(set(CONDITIONS)) != 6 or set(SOURCES) != set(PINNED_PHOTOS)
            or set(STEREO_NAMES) != set(STEREO_SOURCES)):
        raise ValueError("Pinned public ORB overlap protocol changed")
    root = _bundled_root()
    photos = {}
    for name in SOURCES:
        filename, sha, _credit = PINNED_PHOTOS[name]
        photos[name] = _read_pinned_photo(root / filename, sha)
    for name in STEREO_NAMES:
        filename, sha = STEREO_SOURCES[name]
        photos[name] = _read_pinned_photo(root / filename, sha)
    if (photos[STEREO_NAMES[0]].shape != photos[STEREO_NAMES[1]].shape
            or np.array_equal(photos[STEREO_NAMES[0]], photos[STEREO_NAMES[1]])):
        raise ValueError("Require two distinct stereo viewpoint images")
    source_words = {name: _validated_words(photo) for name, photo in photos.items()}
    conditions = {}
    for kind, strength in CONDITIONS:
        key = f"{kind}_{strength:g}"
        shared = {}
        for name, photo in photos.items():
            blurred_words = _validated_words(apply_blur(photo, kind, strength))
            shared[name] = len(source_words[name] & blurred_words)
        right_blurred = _validated_words(apply_blur(photos["right_view"], kind, strength))
        conditions[key] = {
            "same_source_shared_word_counts": shared,
            "same_source_with_zero_shared_words": sum(x == 0 for x in shared.values()),
            "same_source_with_one_shared_word": sum(x == 1 for x in shared.values()),
            "same_source_meeting_necessary_two_word_minimum": sum(x >= 2 for x in shared.values()),
            "stereo_left_vs_blurred_right_shared_words": len(source_words["left_view"] & right_blurred),
        }
    return {
        "policy": "ORB word-overlap eligibility diagnostic only; no source identity or automatic linking",
        "fingerprint_version": FINGERPRINT_VERSION,
        "public_source_count": len(photos),
        "source_scene_groups_at_most": len(SOURCES) + 1,
        "source_word_counts": {name: len(value) for name, value in source_words.items()},
        "public_source_credits": {name: PINNED_PHOTOS[name][2] for name in SOURCES},
        "stereo_rights_documented_in": "MIDDLEBURY_STEREO_SMOKE.md",
        "condition_counts": conditions,
        "same_source_edit_pairs_correlated": len(photos) * len(CONDITIONS),
        "different_viewpoint_stereo_pairs_correlated": len(CONDITIONS),
        "sha256_source_bytes_checked": True,
        "actual_sqlite_shortlist_executed": False,
        "two_words_sufficient_to_ensure_retrieval": False,
        "source_exposure_identity_verified": False,
        "actual_retrieval_recall_estimated": False,
        "source_media_copied_written_or_uploaded": False,
        "limitations": (
            "At most six scene groups; each photo is re-used in correlated edits. "
            "Two common visual words are necessary in the existing SQLite shortlist, "
            "not sufficient to retrieve/verify the correct photo. No representative "
            "rank@K, full-corpus document frequency, same-camera shutter negatives, "
            "or S24+ memory/latency is measured."
        ),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.parse_args()
    print(json.dumps(run(), indent=2))


if __name__ == "__main__":
    main()
