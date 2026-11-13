"""Research-only pinned public camera ORB blur-augmentation cost/risk study.

Does not edit media, build indexes, change production fingerprints or merge photos.
"""
from __future__ import annotations
import argparse
import json
import cv2
import numpy as np
from .blur_robustness_matrix import SOURCES, STEREO_NAMES, apply_blur
from .middlebury_stereo_smoke import STEREO_SOURCES
from .public_domain_smoke import PINNED_PHOTOS, _bundled_root, _read_pinned_photo
from .public_blur_word_overlap import _validated_words, EXPECTED_FINGERPRINT
from .retrieval import FINGERPRINT_VERSION, BANDS, KEYPOINTS

PROFILES = (("baseline", ()), ("gauss2", (2.0,)), ("gauss2_4", (2.0, 4.0)))
QUERY_CONDITIONS = (("gaussian", 8.0), ("motion", 31))
MAX_UNION_WORDS = len(BANDS) * KEYPOINTS * 3


def terms_with_augment(gray: np.ndarray, sigmas: tuple[float, ...]) -> set[int]:
    if sigmas not in tuple(profile for _, profile in PROFILES):
        raise ValueError("Unapproved augmentation profile")
    words = set(_validated_words(gray))
    for sigma in sigmas:
        changed = cv2.GaussianBlur(gray, (0, 0), sigma, borderType=cv2.BORDER_REFLECT_101)
        words.update(_validated_words(changed))
    if len(words) > MAX_UNION_WORDS:
        raise ValueError("Augmentation word budget exceeded")
    return words


def run() -> dict:
    if (FINGERPRINT_VERSION != EXPECTED_FINGERPRINT or set(SOURCES) != set(PINNED_PHOTOS)
            or set(STEREO_NAMES) != set(STEREO_SOURCES)
            or PROFILES != (("baseline", ()), ("gauss2", (2.0,)),
                            ("gauss2_4", (2.0, 4.0)))
            or QUERY_CONDITIONS != (("gaussian", 8.0), ("motion", 31))):
        raise ValueError("Pinned ORB augmentation protocol changed")
    root = _bundled_root()
    photos = {}
    for label in SOURCES:
        filename, digest, _credit = PINNED_PHOTOS[label]
        photos[label] = _read_pinned_photo(root / filename, digest)
    for label in STEREO_NAMES:
        filename, digest = STEREO_SOURCES[label]
        photos[label] = _read_pinned_photo(root / filename, digest)
    if (photos["left_view"].shape != photos["right_view"].shape
            or np.array_equal(photos["left_view"], photos["right_view"])):
        raise ValueError("Distinct public stereo viewpoints required")
    queries = {
        (label, kind, strength): _validated_words(apply_blur(photo, kind, strength))
        for label, photo in photos.items() for kind, strength in QUERY_CONDITIONS
    }
    output = {}
    for profile, sigmas in PROFILES:
        index = {label: terms_with_augment(photo, sigmas) for label, photo in photos.items()}
        conditions = {}
        for kind, strength in QUERY_CONDITIONS:
            ranks = {}
            other_offers = 0
            for label in photos:
                words = queries[label, kind, strength]
                ranked = sorted(
                    ((candidate, len(words & entries)) for candidate, entries in index.items()
                     if len(words & entries) >= 2), key=lambda row: (-row[1], row[0]))
                ranks[label] = next((i + 1 for i, (candidate, _) in enumerate(ranked)
                                     if candidate == label), None)
                other_offers += sum(candidate != label for candidate, _ in ranked)
            stereo_words = len(queries["right_view", kind, strength] & index["left_view"])
            conditions[f"{kind}_{strength:g}"] = {
                "known_source_eligible": sum(r is not None for r in ranks.values()),
                "known_source_rank_1": sum(r == 1 for r in ranks.values()),
                "known_source_rank_at_most_5": sum(r is not None and r <= 5 for r in ranks.values()),
                "other_public_source_two_word_offers": other_offers,
                "different_viewpoint_stereo_shared_words": stereo_words,
                "stereo_other_exposure_would_be_offered": stereo_words >= 2,
            }
        output[profile] = {
            "augmented_sigma_index_only": list(sigmas),
            "total_seven_source_words": sum(map(len, index.values())),
            "max_source_words": max(map(len, index.values())),
            "conditions": conditions,
        }
    return {
        "policy": "Only offline manual-review retrieval offers; NEVER shared physical exposure proof",
        "public_sources": len(photos),
        "distinct_scene_groups_at_most": len(SOURCES) + 1,
        "source_sha256_checked": True,
        "public_rights": {name: PINNED_PHOTOS[name][2] for name in SOURCES},
        "stereo_rights_reference": "MIDDLEBURY_STEREO_SMOKE.md",
        "profiles": output,
        "full_sqlite_index_or_shortlist_ran": False,
        "scene_independent_train_vs_test": False,
        "same_camera_separate_shutter_negatives": 0,
        "physical_exposure_identity_verified": False,
        "representative_false_review_or_recall_estimated": False,
        "source_media_copied_written_uploaded": False,
        "limitations": (
            "Seven source-reused photographs from at most six scenes: choosing edit strengths "
            "different from indexed blurs is not an independent source-heldout benchmark. "
            "Extra ORB terms increase cost and other-exposure candidate offers. No actual "
            "150k-image index size/posting budget or S24+ performance; no auto merge or delete."
        ),
    }


def main() -> None:
    argparse.ArgumentParser(description=__doc__).parse_args()
    print(json.dumps(run(), indent=2))


if __name__ == "__main__":
    main()
