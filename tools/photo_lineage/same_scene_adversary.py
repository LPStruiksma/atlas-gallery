"""Hard-negative simulator: separately rendered frames of one static scene.

Research-only synthetic grayscale arrays. No disk, real cameras, Gallery,
face/person inference or source-exposure certification. A static backdrop may
yield a strong geometric *candidate* across distinct synthetic time steps.
"""
from __future__ import annotations

import argparse
from collections import Counter
from itertools import combinations
import json

import cv2
import numpy as np

from .benchmark import textured
from .heldout_eval import _derived_edits
from .matcher import compare_arrays
from .scene_risk import summarize_scene_exposure_risk

ALGORITHMS = ("orb", "sift", "akaze")
EDIT_LABELS = ("resized", "gamma", "cropped", "rotated_crop", "jpeg_reencode")
FOREGROUND_MODES = ("moving", "stationary", "absent")

# Finite, privacy-safe matcher-gate vocabulary. Never serialize arbitrary
# exception text, media path, pixel hash or a per-pair synthetic image ID.
# Update this mapping intentionally if matcher review policy ever changes.
_MATCHER_REASONS = {
    "Insufficient distinctive visual features": ("unknown", "features_sparse"),
    "Insufficient independent descriptor matches": ("unknown", "descriptor_correspondences_sparse"),
    "No coherent geometric transformation": ("unknown", "geometry_incoherent"),
    "Weak, localized, or implausible geometric agreement": ("unknown", "geometry_weak"),
    "Photometric or geometric evidence below conservative review gate":
        ("unknown", "evidence_below_review_gate"),
    "Plausible shared photographic content; insufficient for strong candidate":
        ("review", "plausible_but_weak_review"),
    "Distributed geometric inliers with compatible image structure; review required":
        ("candidate", "strong_candidate_still_review_only"),
}


def _reason_code(verdict: str, reason: str) -> str:
    """Collapse matcher outcomes to finite non-identifying diagnostic bins."""
    allowed = _MATCHER_REASONS.get(reason)
    if allowed is None or verdict != allowed[0]:
        raise ValueError("Unknown or mismatched matcher gate reason: refuse incomplete diagnostic")
    return allowed[1]



def simulated_frame(background: np.ndarray, *, scene_seed: int, frame_number: int,
                    foreground_mode: str = "moving") -> np.ndarray:
    """Different framebuffer/time-step from same synthetic static environment.

    A moving occluder, lighting adjustment and independently sampled sensor
    noise are applied. Sharing background is intentional *hard negative* for
    same-source-exposure equivalence, NOT a camera authenticity simulator.
    """
    if (background.ndim != 2 or background.dtype != np.uint8
            or min(background.shape) < 256 or max(background.shape) > 480):
        raise ValueError("Expect grayscale uint8 synthetic scene within 256..480px")
    if type(scene_seed) is not int or not 0 <= scene_seed <= 1_000_000:
        raise ValueError("Synthetic scene seed must be integer 0..1000000")
    if type(frame_number) is not int or not 0 <= frame_number < 4:
        raise ValueError("frame_number must be integer 0..3")
    if foreground_mode not in FOREGROUND_MODES:
        raise ValueError("Foreground mode must be moving, stationary or absent")
    rng = np.random.default_rng(scene_seed * 31 + frame_number * 1033 + 713)
    frame = background.astype(np.float32)
    frame *= (0.97 + (frame_number % 3) * 0.025)
    frame += rng.normal(0, 2.0, frame.shape)
    image = np.uint8(np.clip(frame, 0, 255))
    height, width = image.shape
    # Independently redraw each simulated time step even when the subject
    # is stationary/absent. Sensor noise/illumination are re-sampled above.
    if foreground_mode != "absent":
        pose = frame_number if foreground_mode == "moving" else 0
        x = int(width * (0.15 + pose * 0.19))
        y = int(height * (0.25 + ((scene_seed + pose) % 3) * 0.15))
        cv2.circle(image, (x, y), max(6, width // 17), 248, -1)
        cv2.rectangle(image, (max(0, x - width // 15), min(height - 1, y + height // 10)),
                      (min(width - 1, x + width // 11), min(height - 1, y + height // 7)),
                      12 + pose * 13, -1)
    return image


def run(*, scenes: int = 2, frames_per_scene: int = 3,
        size: int = 320, algorithm: str = "orb", foreground_mode: str = "moving") -> dict:
    if type(scenes) is not int or not 1 <= scenes <= 4:
        raise ValueError("Synthetic scenes must be integer 1..4")
    if type(frames_per_scene) is not int or not 2 <= frames_per_scene <= 4:
        raise ValueError("Frames per scene must be integer 2..4")
    if type(size) is not int or not 256 <= size <= 480:
        raise ValueError("Synthetic frame size must be integer 256..480")
    if algorithm not in ALGORITHMS:
        raise ValueError("Unknown comparison algorithm")
    if foreground_mode not in FOREGROUND_MODES:
        raise ValueError("Unknown foreground simulation mode")

    negative_by_scene = []
    by_edit = {name: Counter() for name in EDIT_LABELS}
    reason_by_condition = {}
    pixel_changed_pairs = 0
    sampled_frames = 0
    for scene_index in range(scenes):
        scene_seed = 101 + 79 * scene_index
        background = textured(scene_seed, size=size)
        captures = [
            simulated_frame(background, scene_seed=scene_seed, frame_number=n,
                            foreground_mode=foreground_mode)
            for n in range(frames_per_scene)
        ]
        sampled_frames += len(captures)
        for src in captures:
            edits = _derived_edits(src)
            if set(edits) != set(EDIT_LABELS):
                raise AssertionError("The heldout evaluator's edit protocol unexpectedly changed")
            for name, altered in edits.items():
                result = compare_arrays(src, altered, algorithm=algorithm)
                if result.verdict not in ("candidate", "review", "unknown"):
                    raise AssertionError("Invalid matcher verdict")
                by_edit[name][result.verdict] += 1
                reason_by_condition.setdefault("edit_" + name, Counter())[
                    _reason_code(result.verdict, result.reason)] += 1
        verdicts = []
        for original_a, original_b in combinations(captures, 2):
            if np.array_equal(original_a, original_b):
                raise AssertionError("Two separately simulated frame steps were identical")
            pixel_changed_pairs += 1
            result = compare_arrays(original_a, original_b, algorithm=algorithm)
            verdicts.append(result.verdict)
            reason_by_condition.setdefault("distinct_frame_same_scene", Counter())[
                _reason_code(result.verdict, result.reason)] += 1
        negative_by_scene.append(verdicts)
    negative_risk = summarize_scene_exposure_risk(negative_by_scene)
    positive = {
        label: {category: counter.get(category, 0)
                for category in ("candidate", "review", "unknown")}
        for label, counter in by_edit.items()
    }
    return {
        "policy": (
            "Synthetic *distinct time-step* scene frames: independent-exposure-like "
            "hard negatives, NOT actual camera shutter ground truth. Even a "
            "strong match here NEVER certifies exposure equivalence."
        ),
        "algorithm": algorithm,
        "foreground_mode": foreground_mode,
        "scenes": scenes,
        "synthetic_separately_rendered_frames": sampled_frames,
        "same_scene_distinct_frame_pairs": pixel_changed_pairs,
        "all_distinct_frame_pairs_pixel_nonidentical": True,
        "same_exposure_derived_edits": positive,
        "matcher_gate_codes_by_condition": {
            condition: dict(sorted(counts.items()))
            for condition, counts in sorted(reason_by_condition.items())
        },
        "same_scene_distinct_frame_risk": negative_risk,
        "unsafe_to_certify_exposure_identity": True,
        "raw_images_saved_or_returned": False,
        "limitations": (
            "Shared procedurally rendered background and displaced foreground are "
            "fictional. This does not establish real independent cameras, legal "
            "image rights, lens optics, shot-to-shot noise distributions, natural "
            "scene variation, actual source-lineage false positives or S24+ latency."
        ),
    }


def contrast_algorithms(*, scenes: int = 1, frames_per_scene: int = 2,
                        size: int = 256, foreground_mode: str = "moving") -> dict:
    """Compare *all* existing classical matchers on identical fictional inputs.

    This is deliberately a very small quota. It is not a leaderboard: one
    procedural scene cannot calibrate either recall or false-offer rates.
    """
    if type(scenes) is not int or not 1 <= scenes <= 2:
        raise ValueError("Algorithm contrast scenes must be integer 1..2")
    if type(frames_per_scene) is not int or not 2 <= frames_per_scene <= 3:
        raise ValueError("Algorithm contrast frames_per_scene must be integer 2..3")
    if type(size) is not int or not 256 <= size <= 360:
        raise ValueError("Algorithm contrast image size must be integer 256..360")
    if foreground_mode not in FOREGROUND_MODES:
        raise ValueError("Unknown foreground simulation mode")
    by_algorithm = {}
    for algorithm in ALGORITHMS:
        study = run(scenes=scenes, frames_per_scene=frames_per_scene,
                    size=size, algorithm=algorithm,
                    foreground_mode=foreground_mode)
        edit_rows = study["same_exposure_derived_edits"]
        counts = {
            name: {
                "reviewable": result["candidate"] + result["review"],
                "abstained_unknown": result["unknown"],
                "total": sum(result.values()),
            }
            for name, result in edit_rows.items()
        }
        total_edits = sum(result["total"] for result in counts.values())
        total_reviewable = sum(result["reviewable"] for result in counts.values())
        risk = study["same_scene_distinct_frame_risk"]
        by_algorithm[algorithm] = {
            "derived_edit_conditions": counts,
            "matcher_gate_codes_by_condition": study["matcher_gate_codes_by_condition"],
            "derived_reviewable": total_reviewable,
            "derived_total_correlated": total_edits,
            "distinct_time_step_pair_count_correlated": risk["correlated_pairs_diagnostic_only"],
            "distinct_time_step_strong_candidate_offers": risk["correlated_strong_candidate_pairs"],
            "scenes_with_any_distinct_step_strong_candidate_offer":
                risk["scenes_with_any_strong_same_scene_candidate"],
            "scenes_tested_not_a_camera_sample": scenes,
        }
    return {
        "policy": (
            "Paired procedural same-scene audit ONLY; all algorithm scores are "
            "uncalibrated, from correlated synthetic frames. NEVER choose a "
            "source-exposure classifier or merge/delete media from this table."
        ),
        "fixed_algorithm_order": list(ALGORITHMS),
        "scenes": scenes,
        "frames_per_scene": frames_per_scene,
        "synthetic_frame_size": size,
        "foreground_mode": foreground_mode,
        "algorithms": by_algorithm,
        "winner_selected": False,
        "unsafe_to_certify_exposure_identity": True,
        "raw_image_or_paths_returned": False,
        "limitations": (
            "Same framebuffer seed and time-step schedule per matcher, "
            "but no real independent shutter captures, rights-verified camera "
            "samples, probability calibration, on-device runtime, or gallery "
            "retrieval distribution. Counts are correlated within a scene."
        ),
    }


def contrast_foreground_modes(*, scenes: int = 1, frames_per_scene: int = 2,
                              size: int = 256, algorithm: str = "orb") -> dict:
    """Repeat the exact seed/time-step schedule for three motion conditions.

    This is *not* independent new test data: the same backgrounds and random
    seeds are reused by design across foreground modes. It illustrates how
    changing foreground motion alters review offers, not camera exposure
    identification or genuine statistical confidence.
    """
    if type(scenes) is not int or not 1 <= scenes <= 2:
        raise ValueError("Motion contrast scenes must be integer 1..2")
    if type(frames_per_scene) is not int or not 2 <= frames_per_scene <= 3:
        raise ValueError("Motion contrast frames_per_scene must be integer 2..3")
    if type(size) is not int or not 256 <= size <= 360:
        raise ValueError("Motion contrast image size must be integer 256..360")
    if algorithm not in ALGORITHMS:
        raise ValueError("Unknown comparison algorithm")
    modes = {}
    for foreground_mode in FOREGROUND_MODES:
        result = run(
            scenes=scenes, frames_per_scene=frames_per_scene,
            size=size, algorithm=algorithm,
            foreground_mode=foreground_mode,
        )
        risk = result["same_scene_distinct_frame_risk"]
        edits = result["same_exposure_derived_edits"]
        modes[foreground_mode] = {
            "distinct_frames_pixel_nonidentical":
                result["all_distinct_frame_pairs_pixel_nonidentical"],
            "correlated_distinct_frame_pairs":
                risk["correlated_pairs_diagnostic_only"],
            "correlated_strong_candidate_offers":
                risk["correlated_strong_candidate_pairs"],
            "correlated_unknown_pairs":
                risk["correlated_unknown_pairs"],
            "scenes_with_any_strong_candidate_offer":
                risk["scenes_with_any_strong_same_scene_candidate"],
            "scenes_with_any_reviewable_offer":
                risk["scenes_with_any_review_or_candidate"],
            "scenes_as_reused_synthetic_seeds": risk["scenes"],
            "derived_edit_verdicts": edits,
            "matcher_gate_codes_by_condition": result["matcher_gate_codes_by_condition"],
        }
    return {
        "policy": (
            "Controlled procedural scene-motion sensitivity only. The same "
            "fictional background and camera-time-step seeds are reused across "
            "moving/stationary/absent foregrounds. Never infer identity or "
            "common original exposure from a geometric candidate."
        ),
        "algorithm": algorithm,
        "scenes": scenes,
        "frames_per_scene": frames_per_scene,
        "size": size,
        "compared_foreground_modes": list(FOREGROUND_MODES),
        "modes": modes,
        "winner_selected": False,
        "not_independent_camera_frames": True,
        "unsafe_to_certify_exposure_identity": True,
        "raw_images_or_paths_returned": False,
        "limitations": (
            "Only procedural simulated frames. Scene risk estimates are "
            "underpowered/correlated and not calibrated real-camera false "
            "same-exposure rates; background/illumination are shared by design. "
            "Do not tune matcher thresholds or auto-merge or delete originals."
        ),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scenes", type=int, default=2)
    parser.add_argument("--frames-per-scene", type=int, default=3)
    parser.add_argument("--size", type=int, default=320)
    parser.add_argument("--algorithm", choices=ALGORITHMS + ("all",), default="orb")
    parser.add_argument("--foreground-mode", choices=FOREGROUND_MODES + ("sweep",),
                        default="moving")
    args = parser.parse_args()
    if args.foreground_mode == "sweep":
        if args.algorithm == "all":
            parser.error("Select one matcher for foreground sweep; avoid unbounded 3x3 work")
        result = contrast_foreground_modes(
            scenes=args.scenes, frames_per_scene=args.frames_per_scene,
            size=args.size, algorithm=args.algorithm,
        )
    elif args.algorithm == "all":
        result = contrast_algorithms(
            scenes=args.scenes, frames_per_scene=args.frames_per_scene,
            size=args.size, foreground_mode=args.foreground_mode,
        )
    else:
        result = run(scenes=args.scenes, frames_per_scene=args.frames_per_scene,
                     size=args.size, algorithm=args.algorithm,
                     foreground_mode=args.foreground_mode)
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
