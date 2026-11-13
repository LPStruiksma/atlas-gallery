"""Conservative, scene-cluster-aware uncertainty for source-exposure audits.

Distinct independent shutter comparisons *within one scene* are correlated.
Never treat every pair as an independent sample or a strong geometric hit as
proven source-exposure equivalence. Standard-library only; no photos/IO.
"""
from __future__ import annotations

from collections.abc import Iterable
from math import sqrt

Z95 = 1.959963984540054


def wilson_upper95(errors: int, count: int) -> float:
    """Two-sided Wilson 95% upper endpoint, including zero-error cohorts.

    Not a production risk bound without genuinely independent representative
    scene sampling, and not a certification of source exposure identity.
    """
    if type(errors) is not int or type(count) is not int or not 0 <= errors <= count:
        raise ValueError("Require integer 0 <= errors <= count")
    if count == 0:
        raise ValueError("At least one independent scene is required")
    p = errors / count
    zz = Z95 * Z95
    return min(1.0, (p + zz / (2 * count) + Z95 * sqrt((p * (1 - p) + zz / (4 * count)) / count)) / (1 + zz / count))


def summarize_scene_exposure_risk(scene_verdicts: Iterable[Iterable[str]]) -> dict:
    """Each iterable holds all independent-exposure pair verdicts in one scene.

    Only candidate/review/unknown are allowed. An all-unknown scene is *not*
    a clean negative proof; all scenes remain in the conservative denominator.
    """
    scene_count = scenes_with_candidate = scenes_with_any_review = 0
    pair_count = candidate_pairs = unknown_pairs = 0
    for verdicts in scene_verdicts:
        # Fail closed on malformed/empty scene. No materialized entire archive.
        count = candidates = reviews = unknowns = 0
        for verdict in verdicts:
            if verdict not in ("candidate", "review", "unknown"):
                raise ValueError("Unrecognized pair verdict")
            count += 1
            candidates += verdict == "candidate"
            reviews += verdict == "review"
            unknowns += verdict == "unknown"
            if count > 28:  # MAX_CAPTURE_PER_SCENE=8 => C(8,2)=28
                raise ValueError("Too many pairs in one scene")
        if count == 0:
            raise ValueError("Scene must have independently compared captures")
        scene_count += 1
        if scene_count > 20:
            raise ValueError("Scene count exceeds heldout evaluator bound")
        scenes_with_candidate += bool(candidates)
        scenes_with_any_review += bool(candidates + reviews)
        pair_count += count
        candidate_pairs += candidates
        unknown_pairs += unknowns
    if not scene_count:
        raise ValueError("No independent scenes evaluated")
    return {
        "unit": "independent_scene_as_curated",
        "scenes": scene_count,
        "scenes_with_any_strong_same_scene_candidate": scenes_with_candidate,
        "scenes_with_any_review_or_candidate": scenes_with_any_review,
        "scene_strong_candidate_fraction": round(scenes_with_candidate / scene_count, 4),
        "scene_strong_candidate_wilson95_upper": round(wilson_upper95(scenes_with_candidate, scene_count), 4),
        "correlated_pairs_diagnostic_only": pair_count,
        "correlated_strong_candidate_pairs": candidate_pairs,
        "correlated_unknown_pairs": unknown_pairs,
        "policy": "Strong geometric same-scene hits are NOT source-exposure proof; Wilson assumes independent representative scenes, which provenance labels cannot guarantee.",
    }
