"""Research-only per-photo capped blurred ORB fingerprint ablation.

No production index changes, photo writes, cloud, identity links or deletes.
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
from .retrieval import FINGERPRINT_VERSION, KEYPOINTS, BANDS

LIMITS = (0, 64, 128, 192, 256)
QUERY_CONDITIONS = (("gaussian", 8.0), ("motion", 31))
BASE_WORD_BUDGET = KEYPOINTS * len(BANDS)
MAX_WORD_BUDGET = BASE_WORD_BUDGET + max(LIMITS)


def _mix32(value: int) -> int:
    """Stable numeric scrambling for balanced selection, not a security hash."""
    x = value & 0xffffffff
    x ^= x >> 16
    x = x * 0x7feb352d & 0xffffffff
    x ^= x >> 15
    x = x * 0x846ca68b & 0xffffffff
    return x ^ (x >> 16)


def select_extra_words(base: set[int], blurred: set[int], limit: int) -> set[int]:
    """Preserve all baseline ORB words and select <=limit novel terms.

    Select separately by the embedded ORB descriptor band, using fixed hash
    ordering to avoid systematic bias towards numerically smaller bands.
    """
    if type(limit) is not int or limit not in LIMITS:
        raise ValueError("Only declared four-band extra-word budgets supported")
    for terms in (base, blurred):
        if (type(terms) is not set or len(terms) > BASE_WORD_BUDGET
                or any(type(w) is not int or w < 0 or w >= 1 << 26
                       or w >> 24 not in range(len(BANDS)) for w in terms)):
            raise ValueError("Malformed bounded ORB visual-word set")
    output = set(base)
    novel = blurred - base
    for band in range(len(BANDS)):
        candidates = sorted(
            (word for word in novel if word >> 24 == band),
            key=lambda word: (_mix32(word), word),
        )
        output.update(candidates[:limit // len(BANDS)])
    if len(output) > MAX_WORD_BUDGET or len(output) > len(base) + limit:
        raise ValueError("Augmented ORB per-photo word cap violated")
    return output


def run() -> dict:
    if (FINGERPRINT_VERSION != EXPECTED_FINGERPRINT
            or set(SOURCES) != set(PINNED_PHOTOS)
            or set(STEREO_NAMES) != set(STEREO_SOURCES)
            or LIMITS != (0, 64, 128, 192, 256)
            or QUERY_CONDITIONS != (("gaussian", 8.0), ("motion", 31))):
        raise ValueError("Unreviewed capped blur study configuration")
    root = _bundled_root()
    photos = {}
    for label in SOURCES:
        name, sha, _credit = PINNED_PHOTOS[label]
        photos[label] = _read_pinned_photo(root / name, sha)
    for label in STEREO_NAMES:
        name, sha = STEREO_SOURCES[label]
        photos[label] = _read_pinned_photo(root / name, sha)
    if (photos["left_view"].shape != photos["right_view"].shape
            or np.array_equal(photos["left_view"], photos["right_view"])):
        raise ValueError("Two distinct camera viewpoints required")
    base = {label: _validated_words(image) for label, image in photos.items()}
    extra = {
        label: _validated_words(cv2.GaussianBlur(
            image, (0, 0), 2.0, borderType=cv2.BORDER_REFLECT_101))
        for label, image in photos.items()
    }
    queries = {
        (label, kind, amount): _validated_words(apply_blur(image, kind, amount))
        for label, image in photos.items()
        for kind, amount in QUERY_CONDITIONS
    }
    reports = {}
    for limit in LIMITS:
        index = {
            label: select_extra_words(base[label], extra[label], limit)
            for label in photos
        }
        conditions = {}
        for kind, amount in QUERY_CONDITIONS:
            ranks = {}
            false_offers = 0
            for label in photos:
                query_words = queries[label, kind, amount]
                ranked = sorted(
                    ((candidate, len(query_words & candidate_words))
                     for candidate, candidate_words in index.items()
                     if len(query_words & candidate_words) >= 2),
                    key=lambda row: (-row[1], row[0]),
                )
                ranks[label] = next(
                    (i + 1 for i, (candidate, score) in enumerate(ranked)
                     if candidate == label), None)
                false_offers += sum(candidate != label for candidate, _ in ranked)
            stereo_words = len(queries["right_view", kind, amount] & index["left_view"])
            conditions[f"{kind}_{amount:g}"] = {
                "known_source_eligible": sum(r is not None for r in ranks.values()),
                "known_source_rank_1": sum(r == 1 for r in ranks.values()),
                "nonself_public_review_offers": false_offers,
                "different_viewpoint_stereo_words": stereo_words,
                "stereo_viewpoint_would_enter_shortlist": stereo_words >= 2,
            }
        reports[str(limit)] = {
            "max_extra_words_per_photo": limit,
            "total_words_seven_sources": sum(len(words) for words in index.values()),
            "strict_per_photo_bound": BASE_WORD_BUDGET + limit,
            "conditions": conditions,
        }
    return {
        "policy": "Capped blurred fingerprint review-only research; never source/shutter identity",
        "sample_public_photos": len(photos),
        "scene_groups_at_most": len(SOURCES) + 1,
        "sha256_of_public_sources_verified": True,
        "public_rights": {label: PINNED_PHOTOS[label][2] for label in SOURCES},
        "stereo_rights": "MIDDLEBURY_STEREO_SMOKE.md",
        "fingerprint_version_current": FINGERPRINT_VERSION,
        "base_word_budget": BASE_WORD_BUDGET,
        "index_augmentation_sigma": 2.0,
        "query_strengths_not_identical_to_index_augmentation": True,
        "results": reports,
        "executed_sqlite_index": False,
        "independent_training_source_holdout": False,
        "same_camera_different_shutter_negatives": 0,
        "real_camera_source_identity_confirmed": False,
        "images_modified_copied_uploaded_or_deleted": False,
        "production_thresholds_or_index_changed": False,
        "limitations": (
            "Seven source-reused public photos and one stereo pair. Query/index "
            "strengths differ but original sources are NOT held out. Two-word "
            "offers can be different shutter exposures. No full SQLite/posting "
            "distribution, representative recall/false-review rates or S24+ performance."
        ),
    }


def main() -> None:
    argparse.ArgumentParser(description=__doc__).parse_args()
    print(json.dumps(run(), indent=2))


if __name__ == "__main__":
    main()
