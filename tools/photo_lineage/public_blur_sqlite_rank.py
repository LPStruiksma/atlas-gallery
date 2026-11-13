"""Temporary real-SQLite retrieval study on pinned public blur edits only.

No user photos, permanent cache, uploads, source mutation or automatic
photo-family inference. Distractor pixels are procedurally generated.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
from tempfile import TemporaryDirectory

from PIL import Image
import numpy as np

from .benchmark import textured
from .blur_robustness_matrix import SOURCES, STEREO_NAMES, CONDITIONS, apply_blur
from .middlebury_stereo_smoke import STEREO_SOURCES
from .public_domain_smoke import PINNED_PHOTOS, _bundled_root, _read_pinned_photo
from .public_blur_word_overlap import _validated_words, EXPECTED_FINGERPRINT
from .retrieval import FINGERPRINT_VERSION, index_folder, shortlist

TOP_K = 20
POSTING_BUDGET = 50_000
DOCUMENT_BUDGET = 1000


def _rank(hits: list, wanted: Path) -> int | None:
    if not isinstance(hits, list) or len(hits) > TOP_K:
        raise ValueError("Unexpected bounded shortlist output")
    matching = [i + 1 for i, h in enumerate(hits) if getattr(h, "path", None) == str(wanted)]
    if len(matching) > 1:
        raise ValueError("Duplicate source in returned shortlist")
    return matching[0] if matching else None


def run(*, distractors: int = 32) -> dict:
    if type(distractors) is not int or not 0 <= distractors <= 64:
        raise ValueError("Use 0..64 deterministic synthetic distractors")
    if (FINGERPRINT_VERSION != EXPECTED_FINGERPRINT
            or set(SOURCES) != set(PINNED_PHOTOS)
            or set(STEREO_NAMES) != set(STEREO_SOURCES)
            or len(CONDITIONS) != 6 or len(set(CONDITIONS)) != 6):
        raise ValueError("Pinned public retrieval protocol changed")
    root = _bundled_root()
    photos = {}
    for label in SOURCES:
        filename, expected, _credit = PINNED_PHOTOS[label]
        photos[label] = _read_pinned_photo(root / filename, expected)
    for label in STEREO_NAMES:
        filename, expected = STEREO_SOURCES[label]
        photos[label] = _read_pinned_photo(root / filename, expected)
    if (photos["left_view"].shape != photos["right_view"].shape
            or np.array_equal(photos["left_view"], photos["right_view"])):
        raise ValueError("Distinct stereo viewpoints required")
    originals = {k: _validated_words(v) for k, v in photos.items()}
    by_condition = {}
    with TemporaryDirectory(prefix="atlas-public-blur-rank-") as temp:
        scratch = Path(temp)
        corpus = scratch / "public-and-fictional"
        queries = scratch / "queries"
        corpus.mkdir()
        queries.mkdir()
        source_paths = {}
        for name, gray in photos.items():
            path = corpus / f"source__{name}.png"
            Image.fromarray(gray).save(path)
            source_paths[name] = path
        for seed in range(distractors):
            Image.fromarray(textured(2000 + seed, 256)).save(
                corpus / f"fictional__{seed:03d}.png")
        cache = scratch / "temporary.sqlite"
        indexed = index_folder(corpus, cache, limit=len(photos) + distractors)
        if indexed["errors"] or indexed["total_cached"] != len(photos) + distractors:
            raise ValueError("Disposable retrieval index incomplete; abstain")
        for kind, strength in CONDITIONS:
            condition = f"{kind}_{strength:g}"
            ranks = {}
            eligible = 0
            missing_eligible = 0
            stereo_rank = None
            for name, gray in photos.items():
                changed = apply_blur(gray, kind, strength)
                query_words = _validated_words(changed)
                enough = len(originals[name] & query_words) >= 2
                eligible += int(enough)
                path = queries / f"query__{name}__{condition}.png"
                Image.fromarray(changed).save(path)
                # An exhausted posting/document budget must NEVER masquerade
                # as "no relatives": propagate failure and abstain.
                hits = shortlist(path, cache, top_k=TOP_K,
                                 max_posting_visits=POSTING_BUDGET,
                                 max_distinct_documents=DOCUMENT_BUDGET)
                rank = _rank(hits, source_paths[name])
                if rank is not None and not enough:
                    raise ValueError("Retrieval contradicts fixed two-word precondition")
                ranks[name] = rank
                missing_eligible += int(enough and rank is None)
                if name == "right_view":
                    stereo_rank = _rank(hits, source_paths["left_view"])
            by_condition[condition] = {
                "known_source_rank_at_most_20_by_public_label": ranks,
                "rank_1_sources": sum(r == 1 for r in ranks.values()),
                "rank_at_most_5_sources": sum(r is not None and r <= 5 for r in ranks.values()),
                "rank_at_most_20_sources": sum(r is not None for r in ranks.values()),
                "not_in_top_20_sources": sum(r is None for r in ranks.values()),
                "meeting_necessary_two_word_gate": eligible,
                "word_eligible_but_not_in_top_20": missing_eligible,
                "different_viewpoint_left_in_blurred_right_top_20_rank": stereo_rank,
            }
    # TemporaryDirectory closed: all copies, queries and SQLite cache removed.
    return {
        "policy": "Disposable public-photo and synthetic-distractor SQLite study; no automatic source identity",
        "fingerprint_version": FINGERPRINT_VERSION,
        "public_sources": len(photos),
        "public_scene_groups_at_most": len(SOURCES) + 1,
        "synthetic_distractors": distractors,
        "indexed_photos": len(photos) + distractors,
        "blurred_queries_correlated": len(photos) * len(CONDITIONS),
        "top_k_checked": TOP_K,
        "by_condition": by_condition,
        "source_bytes_sha256_pinned": True,
        "source_exposure_identity_verified": False,
        "same_camera_separate_shutter_negatives": 0,
        "actual_full_gallery_recall_calibrated": False,
        "local_originals_modified": False,
        "private_data_accessed_or_uploaded": False,
        "temporary_photo_derivatives_and_index_removed": True,
        "limitations": (
            "At most six public photographic scene groups and one repeated "
            "different-viewpoint stereo pair, plus deterministic fictional "
            "distractors. Ranks are descriptive and correlated, not population "
            "recall or same-exposure certification. Temporary re-encoded "
            "grayscale inputs omit original metadata; no S24+ performance."
        ),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--distractors", type=int, default=32)
    args = parser.parse_args()
    print(json.dumps(run(distractors=args.distractors), indent=2))


if __name__ == "__main__":
    main()
