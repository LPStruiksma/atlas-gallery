#!/usr/bin/env python3
"""Synthetic-only sensitivity audit of face/body box ownership to detector jitter.

This test intentionally perturbs ONE normalized rectangle field at a time,
with ±epsilon in full-photo coordinate units. It does not model the true
joint detector error distribution, infer identity or access any image.
Only a face which keeps the same uniquely associated body in *all*
tested perturbations can be an optional stable review candidate.
"""
from __future__ import annotations

from dataclasses import dataclass
import hashlib
import math
import random

from region_correspondence import (
    BODY_FIELDS, FACE_FIELDS, _checked, audit_region_correspondence
)
from spatial_evidence_review import review_with_spatial_sources

MAX_TOTAL_REGIONS = 24
COORDS = ("x", "y", "w", "h")


@dataclass(frozen=True)
class StabilityDecision:
    """Ephemeral opaque evidence for local review, not an identity label."""
    asset_uid: str
    face_uid: str
    observation_uid: str | None
    stable_under_sampled_jitter: bool
    perturbations_checked: int
    invalid_perturbations: int
    changed_owner_or_status: int
    joint_perturbations_checked: int = 0
    invalid_joint_perturbations: int = 0
    changed_joint_owner_or_status: int = 0
    identity_confirmed: bool = False


@dataclass(frozen=True)
class StabilityAudit:
    decisions: tuple[StabilityDecision, ...]
    aggregate: dict

    def for_face(self, uid):
        matching = [item for item in self.decisions if item.face_uid == uid]
        if len(matching) != 1:
            raise ValueError("Unknown or duplicate jitter-tested face")
        return matching[0]


def _epsilon(value):
    if (type(value) not in (int, float) or not math.isfinite(value)
            or not 0 < value <= 0.05):
        raise ValueError("Finite 0<epsilon<=0.05 normalized jitter required")
    return float(value)


def _joint_variation(bodies, faces, *, owner_uid, face_uid,
                     epsilon, index):
    """Eight predeclared adversarial directions then reproducible random cases.

    The random cases are seeded from opaque face ID plus fixed trial index,
    sorted by region IDs, and independent of input list insertion order.
    No actual detector-error model is implied by these perturbations.
    """
    bb = sorted((dict(row) for row in bodies),
                key=lambda row: row["observation_uid"])
    ff = sorted((dict(row) for row in faces),
                key=lambda row: row["face_uid"])
    owner = next(row for row in bb
                 if row["observation_uid"] == owner_uid)
    target = next(row for row in ff if row["face_uid"] == face_uid)
    directions = (
        (("face", "y", 1), ("face", "h", 1),
         ("body", "y", -1), ("body", "h", -1)),
        (("face", "x", -1), ("face", "w", 1),
         ("body", "x", 1), ("body", "w", -1)),
        (("face", "x", 1), ("face", "w", 1), ("body", "w", -1)),
        (("face", "y", -1), ("face", "h", 1),
         ("body", "y", 1), ("body", "h", -1)),
        (("face", "h", 1), ("body", "h", -1)),
        (("face", "x", -1), ("body", "x", 1)),
        (("face", "x", 1), ("body", "w", -1)),
        (("face", "y", 1), ("body", "h", -1)),
    )
    if index < len(directions):
        for target_type, key, sign in directions[index]:
            (target if target_type == "face" else owner)[key] += sign * epsilon
    else:
        seed = hashlib.sha256(
            ("ATLAS_V5_JOINT_V1:" + face_uid + ":" + str(index)).encode()
        ).digest()
        rng = random.Random(int.from_bytes(seed, "big"))
        for row in (*bb, *ff):
            for key in COORDS:
                row[key] += epsilon * rng.choice((-1, 0, 1))
    return bb, ff


def audit_spatial_jitter(body_rows, face_rows, *, epsilon=0.01,
                         joint_cases: int = 0) -> StabilityAudit:
    """Fail closed on tested perturbations which remove unique ownership.

    A stable outcome is NOT a measured sensitivity bound on a real
    detector, and is NOT evidence that geometry establishes person ID.
    """
    epsilon = _epsilon(epsilon)
    if (type(joint_cases) is not int
            or joint_cases not in (0, *range(8, 65))):
        raise ValueError("Joint study requires 0 or 8..64 predeclared cases")
    _checked(body_rows, BODY_FIELDS, "observation_uid")
    _checked(face_rows, FACE_FIELDS, "face_uid", allow_empty=True)
    if len(body_rows) + len(face_rows) > MAX_TOTAL_REGIONS:
        raise ValueError("Bounded spatial stress inventory required")
    baseline = audit_region_correspondence(body_rows, face_rows)
    decisions = []
    for initial in baseline.decisions:
        if initial.status != "geometry_candidate_only":
            decisions.append(StabilityDecision(
                initial.asset_uid, initial.face_uid, None, False, 0, 0, 0))
            continue
        # Source frame isolation: moving a bounding box from another frame
        # is not a plausible perturbation of the current face ownership.
        local_body = [dict(x) for x in body_rows
                      if x["asset_uid"] == initial.asset_uid]
        local_face = [dict(x) for x in face_rows
                      if x["asset_uid"] == initial.asset_uid]
        checked = 0
        invalid = 0
        changed = 0
        for category, original in (("body", local_body), ("face", local_face)):
            for index in range(len(original)):
                for key in COORDS:
                    for sign in (-1, 1):
                        perturbed = [dict(row) for row in original]
                        perturbed[index][key] += sign * epsilon
                        args = (perturbed, local_face) if category == "body" else (
                            local_body, perturbed)
                        try:
                            compared = audit_region_correspondence(*args)
                        except ValueError:
                            # At an image boundary, uncertainty must not
                            # turn into false high-confidence acceptance.
                            invalid += 1
                            continue
                        checked += 1
                        decision = compared.for_face(initial.face_uid)
                        if (decision.status != "geometry_candidate_only"
                                or decision.observation_uid != initial.observation_uid):
                            changed += 1
        joint_checked = 0
        joint_invalid = 0
        joint_changed = 0
        for scenario in range(joint_cases):
            compared_bodies, compared_faces = _joint_variation(
                local_body, local_face, owner_uid=initial.observation_uid,
                face_uid=initial.face_uid, epsilon=epsilon, index=scenario)
            try:
                comparison = audit_region_correspondence(
                    compared_bodies, compared_faces)
            except ValueError:
                joint_invalid += 1
                continue
            joint_checked += 1
            candidate = comparison.for_face(initial.face_uid)
            if (candidate.status != "geometry_candidate_only"
                    or candidate.observation_uid != initial.observation_uid):
                joint_changed += 1
        stable = (invalid == 0 and changed == 0
                  and joint_invalid == 0 and joint_changed == 0)
        decisions.append(StabilityDecision(
            initial.asset_uid, initial.face_uid,
            initial.observation_uid if stable else None,
            stable, checked, invalid, changed,
            joint_perturbations_checked=joint_checked,
            invalid_joint_perturbations=joint_invalid,
            changed_joint_owner_or_status=joint_changed))
    stable = sum(d.stable_under_sampled_jitter for d in decisions)
    candidates = baseline.aggregate["geometry_candidate_only"]
    return StabilityAudit(
        decisions=tuple(sorted(decisions, key=lambda item: item.face_uid)),
        aggregate={
            "status": ("synthetic_joint_and_single_axis_jitter_audit_only"
                       if joint_cases else "synthetic_single_axis_jitter_audit_only"),
            "jitter_normalized_image_units": epsilon,
            "faces_evaluated": len(face_rows),
            "base_geometry_candidates": candidates,
            "stable_geometry_candidates": stable,
            "unstable_geometry_candidates": candidates - stable,
            "other_unmatched_or_ambiguous_faces": len(face_rows) - candidates,
            "perturbations_checked": sum(d.perturbations_checked for d in decisions),
            "invalid_perturbations": sum(d.invalid_perturbations for d in decisions),
            "joint_scenarios_predeclared": joint_cases,
            "joint_perturbations_checked": sum(
                d.joint_perturbations_checked for d in decisions),
            "invalid_joint_perturbations": sum(
                d.invalid_joint_perturbations for d in decisions),
            "changed_joint_owner_or_status": sum(
                d.changed_joint_owner_or_status for d in decisions),
            "geometry_stability_is_identity": False,
            "actual_detector_error_distribution_measured": False,
            "multi_axis_joint_perturbations_tested": bool(
                joint_cases and candidates),
            "auto_confirm_allowed": False,
            "caveat": "Even predeclared sampled simultaneous box shifts do "
                      "not establish real detector-error bounds, full "
                      "joint robustness, occlusion safety or identity. "
                      "No actual image or detector is inspected.",
        }
    )


def review_with_jitter_safety(db, observation_uid: str, *, body_regions,
                              face_regions, epsilon=0.01, joint_cases=0, limit=12,
                              source_lineage_rows=None,
                              require_lineage_audit=False) -> dict:
    """Opt-in read-only strict review: every face source must stay associated.

    Existing source-to-person geometry, exact-source group aliases and
    optional crop/derivative family audits still run. If ANY face source
    is not robust in the sampled jitter envelope, withhold ALL candidate
    proposals rather than showing partial corroboration.
    """
    original = review_with_spatial_sources(
        db, observation_uid, body_regions=body_regions,
        face_regions=face_regions, limit=limit,
        source_lineage_rows=source_lineage_rows,
        require_lineage_audit=require_lineage_audit)
    audit = audit_spatial_jitter(
        body_regions, face_regions, epsilon=epsilon,
        joint_cases=joint_cases)
    source_rows = db.execute(
        "SELECT source_reference FROM candidate_evidence "
        "WHERE observation_uid=? AND family='face'",
        (observation_uid,)
    ).fetchall()
    unstable = 0
    for (source,) in source_rows:
        try:
            stable = audit.for_face(source).stable_under_sampled_jitter
        except ValueError:
            stable = False
        unstable += not stable
    if unstable:
        original["proposals"] = []
        original["status"] = "manual_region_review_required"
        original["candidate_results_suppressed"] = True
    original["spatial_jitter_audit"] = audit.aggregate
    original["unstable_face_evidence_sources"] = unstable
    original["auto_confirm_allowed"] = False
    original["physical_face_body_identity_proven"] = False
    return original
