"""Synthetic same-scene separate-frame retrieval + geometry cautionary funnel.

Strictly fictional images and disposable SQLite. Two simulated time steps
are NOT actual camera exposures and no strong geometric verdict can prove
photographic source equivalence. Never touches private gallery/People data.
"""
from __future__ import annotations

import argparse
from collections import Counter
from contextlib import closing
import json
from pathlib import Path
from tempfile import TemporaryDirectory

import cv2
import numpy as np
from PIL import Image

from .benchmark import textured
from .bounded_posting_query import PostingBudgetExceeded
from .capped_blur_word_tradeoff import LIMITS, select_extra_words
from .matcher import compare_arrays, read_grayscale
from .retrieval import MAX_SIDE, index_folder, open_db, shortlist, words_for_image
from .same_scene_adversary import FOREGROUND_MODES, simulated_frame

CONDITIONS = ("gaussian_8", "rotated_crop")
MAX_SCENES = 3
MAX_FRAMES_PER_SCENE = 3
MAX_DISTRACTORS = 12
TOP_K = 10
MAX_POSTING_VISITS = 50_000
MAX_CANDIDATE_DOCUMENTS = 1_000
VERDICTS = ("candidate", "review", "unknown")


def _edit(gray: np.ndarray, condition: str) -> np.ndarray:
    """Two fixed, bounded edits from the same fictional original."""
    if (type(gray) is not np.ndarray or gray.dtype != np.uint8 or gray.ndim != 2
            or min(gray.shape) < 256 or max(gray.shape) > 360
            or condition not in CONDITIONS):
        raise ValueError("Unsupported bounded synthetic edit")
    if condition == "gaussian_8":
        return cv2.GaussianBlur(gray, (0, 0), 8.0, borderType=cv2.BORDER_REFLECT_101)
    h, w = gray.shape
    affine = cv2.getRotationMatrix2D((w / 2, h / 2), 38, 1.0)
    rotated = cv2.warpAffine(gray, affine, (w, h),
                             flags=cv2.INTER_LINEAR, borderValue=127)
    return rotated[h // 7:6 * h // 7, w // 7:6 * w // 7].copy()


def _rewrite_temporary_sources(db: Path, sources: dict[tuple[int, int], Path],
                               cap: int) -> dict:
    """Check baseline SQLite words and apply bounded extras transactionally.

    This is *not* a production fingerprint migration. Caller owns a newly
    created TemporaryDirectory; no caller source files are ever reindexed.
    """
    if type(cap) is not int or cap not in LIMITS or not 2 <= len(sources) <= 9:
        raise ValueError("Invalid isolated synthetic augmentation request")
    extras = 0
    with closing(open_db(db, write=True)) as conn:
        try:
            conn.execute("BEGIN IMMEDIATE")
            for key in sorted(sources):
                path = sources[key]
                row = conn.execute("SELECT id,word_count FROM photos WHERE path=?",
                                   (str(path),)).fetchone()
                if row is None:
                    raise ValueError("Fictional indexed frame missing")
                photo_id, stored_count = row
                pixels = read_grayscale(path, MAX_SIDE)
                baseline = words_for_image(pixels)
                stored = {word for (word,) in conn.execute(
                    "SELECT word FROM words WHERE photo_id=?", (photo_id,)
                )}
                if baseline != stored or stored_count != len(baseline):
                    raise ValueError("Disposable source fingerprint changed unexpectedly")
                mild = cv2.GaussianBlur(pixels, (0, 0), 2.0,
                                        borderType=cv2.BORDER_REFLECT_101)
                augmented = select_extra_words(baseline, words_for_image(mild), cap)
                if not baseline <= augmented or len(augmented) > len(baseline) + cap:
                    raise ValueError("Augmented fictional source exceeds per-photo budget")
                conn.execute("DELETE FROM words WHERE photo_id=?", (photo_id,))
                conn.executemany(
                    "INSERT INTO words(word,photo_id) VALUES (?,?)",
                    ((word, photo_id) for word in sorted(augmented)),
                )
                conn.execute("UPDATE photos SET word_count=? WHERE id=?",
                             (len(augmented), photo_id))
                extras += len(augmented) - len(baseline)
            conn.commit()
        except Exception:
            conn.rollback()
            raise
        count = conn.execute("SELECT COUNT(*) FROM words").fetchone()[0]
    return {"extra_source_postings": extras, "total_postings": count}


def _checked_hits(hits: list) -> list[str]:
    if type(hits) is not list or len(hits) > TOP_K:
        raise ValueError("Malformed bounded synthetic shortlist")
    paths = [getattr(hit, "path", None) for hit in hits]
    if any(type(path) is not str for path in paths) or len(paths) != len(set(paths)):
        raise ValueError("Missing or duplicate synthetic shortlist path")
    return paths


def run(*, scenes: int = 2, frames_per_scene: int = 2, size: int = 256,
        distractors: int = 4, foreground_mode: str = "stationary",
        cap: int = 128, verify_algorithm: str = "orb") -> dict:
    """Compare known edits against separately simulated same-scene frames.

    A synthetic frame/time step is only a procedural analogy for a distinct
    shutter. This report deliberately does not infer source/exposure identity.
    """
    if (type(scenes) is not int or not 1 <= scenes <= MAX_SCENES
            or type(frames_per_scene) is not int
            or not 2 <= frames_per_scene <= MAX_FRAMES_PER_SCENE
            or type(size) is not int or not 256 <= size <= 360
            or type(distractors) is not int or not 0 <= distractors <= MAX_DISTRACTORS
            or type(cap) is not int or cap not in LIMITS
            or type(foreground_mode) is not str or foreground_mode not in FOREGROUND_MODES
            or type(verify_algorithm) is not str
            or verify_algorithm not in ("orb", "sift", "akaze")):
        raise ValueError("Only finite synthetic scene/exposure budgets allowed")

    counters = {condition: Counter() for condition in CONDITIONS}
    risk_scenes = {condition: {"candidate": set(), "review_or_candidate": set()}
                   for condition in CONDITIONS}
    source_count = scenes * frames_per_scene
    with TemporaryDirectory(prefix="atlas-fictional-exposure-funnel-") as folder:
        scratch = Path(folder)
        source_dir, query_dir = scratch / "fictional", scratch / "queries"
        source_dir.mkdir()
        query_dir.mkdir()
        frame_paths: dict[tuple[int, int], Path] = {}
        originals: dict[tuple[int, int], np.ndarray] = {}
        for scene in range(scenes):
            seed = 101 + 79 * scene
            backdrop = textured(seed, size=size)
            for n in range(frames_per_scene):
                frame = simulated_frame(
                    backdrop, scene_seed=seed, frame_number=n,
                    foreground_mode=foreground_mode,
                )
                for previous in range(n):
                    if np.array_equal(frame, originals[scene, previous]):
                        raise ValueError("Simulated distinct time steps yielded identical images")
                p = source_dir / f"scene_{scene:02d}_frame_{n:02d}.png"
                Image.fromarray(frame).save(p)
                frame_paths[scene, n] = p
                originals[scene, n] = read_grayscale(p, MAX_SIDE)
        for n in range(distractors):
            Image.fromarray(textured(3000 + n, size=256)).save(
                source_dir / f"fictional_distractor_{n:02d}.png")
        db = scratch / "scratch.sqlite"
        built = index_folder(source_dir, db, limit=source_count + distractors)
        if (built["errors"] or built["total_cached"] != source_count + distractors
                or built["new_or_updated"] != source_count + distractors):
            raise ValueError("Incomplete disposable synthetic SQLite cache")
        rewritten = _rewrite_temporary_sources(db, frame_paths, cap)
        if rewritten["total_postings"] != built["feature_postings"] + rewritten["extra_source_postings"]:
            raise ValueError("Synthetic posting-count reconciliation failed")
        for condition in CONDITIONS:
            for scene in range(scenes):
                for n in range(frames_per_scene):
                    key = (scene, n)
                    changed = _edit(originals[key], condition)
                    query_path = query_dir / f"scene_{scene:02d}_frame_{n:02d}_{condition}.png"
                    Image.fromarray(changed).save(query_path)
                    decoded = read_grayscale(query_path, MAX_SIDE)
                    # This call throws PostingBudgetExceeded on incomplete
                    # search; it is *never* treated as an empty shortlist.
                    hits = shortlist(
                        query_path, db, top_k=TOP_K,
                        max_posting_visits=MAX_POSTING_VISITS,
                        max_distinct_documents=MAX_CANDIDATE_DOCUMENTS,
                    )
                    paths = _checked_hits(hits)
                    wanted = str(frame_paths[key])
                    own_rank = paths.index(wanted) + 1 if wanted in paths else None
                    counters[condition]["queries"] += 1
                    counters[condition]["own_original_retrieved"] += own_rank is not None
                    counters[condition]["own_original_rank1"] += own_rank == 1
                    same_exposure_verdict = compare_arrays(
                        originals[key], decoded, algorithm=verify_algorithm).verdict
                    if same_exposure_verdict not in VERDICTS:
                        raise ValueError("Unsupported original-to-derived matcher verdict")
                    own_reviewable = same_exposure_verdict != "unknown"
                    counters[condition]["own_edit_geom_reviewable_direct"] += own_reviewable
                    counters[condition]["own_edit_retrieved_and_geom_reviewable"] += (
                        own_reviewable and own_rank is not None)
                    counters[condition]["own_edit_geom_reviewable_but_not_retrieved"] += (
                        own_reviewable and own_rank is None)
                    for other in range(frames_per_scene):
                        if other == n:
                            continue
                        distinct = str(frame_paths[scene, other])
                        if distinct not in paths:
                            continue
                        counters[condition]["different_time_step_shortlist_offers"] += 1
                        verdict = compare_arrays(
                            originals[scene, other], decoded,
                            algorithm=verify_algorithm).verdict
                        if verdict not in VERDICTS:
                            raise ValueError("Unsupported distinct-time-step matcher verdict")
                        counters[condition]["different_time_step_geom_candidate"] += (
                            verdict == "candidate")
                        counters[condition]["different_time_step_geom_review"] += (
                            verdict == "review")
                        counters[condition]["different_time_step_geom_unknown"] += (
                            verdict == "unknown")
                        if verdict == "candidate":
                            risk_scenes[condition]["candidate"].add(scene)
                        if verdict != "unknown":
                            risk_scenes[condition]["review_or_candidate"].add(scene)
        index_bytes = db.stat().st_size

    results = {}
    for condition in CONDITIONS:
        row = counters[condition]
        results[condition] = {
            "simulated_derived_queries": row["queries"],
            "known_original_retrieved": row["own_original_retrieved"],
            "known_original_rank1": row["own_original_rank1"],
            "known_edit_direct_geometry_reviewable": row["own_edit_geom_reviewable_direct"],
            "known_edit_retrieved_and_geometry_reviewable":
                row["own_edit_retrieved_and_geom_reviewable"],
            "known_edit_geometry_reviewable_but_not_retrieved":
                row["own_edit_geom_reviewable_but_not_retrieved"],
            "separately_rendered_same_scene_shortlist_offers":
                row["different_time_step_shortlist_offers"],
            "separately_rendered_same_scene_candidate_after_geometry":
                row["different_time_step_geom_candidate"],
            "separately_rendered_same_scene_review_after_geometry":
                row["different_time_step_geom_review"],
            "separately_rendered_same_scene_unknown_after_geometry":
                row["different_time_step_geom_unknown"],
            "scene_groups_with_any_distinct_step_candidate":
                len(risk_scenes[condition]["candidate"]),
            "scene_groups_with_any_distinct_step_review_or_candidate":
                len(risk_scenes[condition]["review_or_candidate"]),
        }
    return {
        "policy": "Disposable fictional scene/time-step retrieval-to-geometry audit; human review only",
        "synthetic_scene_seeds": scenes,
        "fictional_frames_per_scene": frames_per_scene,
        "fictional_source_frames": source_count,
        "synthetic_distractors": distractors,
        "foreground_mode": foreground_mode,
        "extra_words_per_photo_cap": cap,
        "verification_algorithm": verify_algorithm,
        "posting_visits_budget": MAX_POSTING_VISITS,
        "disposable_sqlite_bytes": index_bytes,
        "extra_source_postings": rewritten["extra_source_postings"],
        "total_temporary_postings": rewritten["total_postings"],
        "conditions": results,
        "actual_temporary_sqlite_and_geometry_used": True,
        "independent_camera_exposures_observed": 0,
        "scene_seeds_are_not_real_world_sample_units": True,
        "source_identity_or_common_shutter_verified": False,
        "population_precision_or_recall_estimated": False,
        "source_images_copied_to_non_disposable_location": False,
        "original_user_images_read_modified_uploaded_or_deleted": False,
        "production_index_fingerprint_thresholds_modified": False,
        "temporary_files_removed_after_completion": True,
        "limitations": (
            "Only deterministic simulated time steps of shared backgrounds, with separate "
            "noise, minor exposure and optional foreground movement. These are NOT real "
            "camera shutter events or field false-match statistics. A strong geometric "
            "candidate between two time steps may be unsafe to interpret as common "
            "source image. Each query and scene is correlated. The experimental SQLite "
            "index has a baseline fingerprint compatibility marker solely in a "
            "disposable temp directory. No 150k-photo/index, phone latency, "
            "thermals or production source-equivalence evidence."
        ),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scenes", type=int, default=2)
    parser.add_argument("--frames-per-scene", type=int, default=2)
    parser.add_argument("--size", type=int, default=256)
    parser.add_argument("--distractors", type=int, default=4)
    parser.add_argument("--foreground", choices=FOREGROUND_MODES, default="stationary")
    parser.add_argument("--cap", type=int, choices=LIMITS, default=128)
    parser.add_argument("--algorithm", choices=("orb", "sift", "akaze"), default="orb")
    a = parser.parse_args()
    print(json.dumps(run(scenes=a.scenes, frames_per_scene=a.frames_per_scene,
                         size=a.size, distractors=a.distractors,
                         foreground_mode=a.foreground, cap=a.cap,
                         verify_algorithm=a.algorithm), indent=2))


if __name__ == "__main__":
    main()
