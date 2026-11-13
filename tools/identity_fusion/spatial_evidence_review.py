#!/usr/bin/env python3
"""Read-only V5 review guard for same-frame face/body source correspondence.

This is a strict *opt-in research adapter*, not a replacement for regular
review_candidates. A face clue may count only when its existing evidence
source_reference is the opaque ID of a face detection that geometrically
belongs to the stored person observation on the SAME media asset.

A geographic/visual rectangle never proves biometric identity. No photo,
model, V4/native database, People assignment or network is used.
"""
from __future__ import annotations

import sqlite3

from audit_source_families import audit_lineage_source_groups
from evidence_store import review_candidates
from region_correspondence import audit_region_correspondence, TOKEN

MAX_FACE_EVIDENCE = 2000
MAX_TOTAL_EVIDENCE = 5000


def _refusal(db: sqlite3.Connection, observation_uid: str, count: int,
             *, regions: dict, aligned: int,
             source_alias_conflicts: int = 0, lineage_audit=None) -> dict:
    n = db.execute(
        "SELECT COUNT(DISTINCT candidate_person_id) FROM candidate_evidence "
        "WHERE observation_uid=?", (observation_uid,)
    ).fetchone()[0]
    return {
        "status": "manual_region_review_required",
        "observation_uid": observation_uid,
        "proposals": [],
        "total_candidates": n,
        "candidate_results_suppressed": True,
        "unknown_always_allowed": True,
        "auto_confirm_allowed": False,
        "face_evidence_rows": count,
        "face_evidence_geometry_matched": aligned,
        "source_alias_conflicts": source_alias_conflicts,
        "source_independence_checked": True,
        "source_lineage_audit": lineage_audit,
        "spatial_region_aggregate": regions,
        "physical_face_body_identity_proven": False,
        "caveat": "Face/body geometry or independently declared source groups "
                  "need manual review. No identity was suggested.",
    }


def review_with_spatial_sources(db: sqlite3.Connection, observation_uid: str,
                                *, body_regions, face_regions,
                                limit: int = 12, source_lineage_rows=None,
                                require_lineage_audit: bool = False) -> dict:
    """Fail closed if any face evidence source lacks unique same-frame geometry.

    The caller must supply CURRENT-asset detector rectangles with the same
    orientation/crop coordinate system as the stored observation, and each
    same-frame face evidence row's source_reference must equal its face_uid.
    Historical face exemplars belong to another frame and must be verified
    through a separate, explicitly reviewed cross-photo protocol.
    """
    if not isinstance(observation_uid, str) or not TOKEN.fullmatch(observation_uid):
        raise ValueError("Opaque target observation ID required")
    if type(limit) is not int or not 1 <= limit <= 100:
        raise ValueError("Bounded candidate review limit required")
    if type(require_lineage_audit) is not bool:
        raise ValueError("Explicit boolean lineage audit requirement needed")
    if require_lineage_audit and source_lineage_rows is None:
        raise ValueError("Independently supplied complete source-family manifest required")
    changes = db.total_changes
    target = db.execute(
        "SELECT asset_uid, region_x, region_y, region_w, region_h "
        "FROM observations WHERE observation_uid=?", (observation_uid,)
    ).fetchone()
    if target is None:
        raise ValueError("Unknown spatial target observation")
    region_audit = audit_region_correspondence(body_regions, face_regions)
    # The supplied geometric target must EXACTLY describe the already-stored
    # observation; callers cannot relabel another person's rectangle with its
    # ID or convert photo orientation without explicit reprojection.
    stored_asset, *stored_bbox = target
    matches = [
        b for b in body_regions
        if b["observation_uid"] == observation_uid
        and b["asset_uid"] == stored_asset
        and all(abs(b[k] - actual) <= 1e-9
                for k, actual in zip(("x", "y", "w", "h"), stored_bbox))
    ]
    if len(matches) != 1:
        raise ValueError("Spatial target does not match stored person-region geometry")
    # Read only the requested observation, and refuse large candidate sets
    # without emitting paths, people or face source identifiers.
    faces = db.execute(
        "SELECT source_reference FROM candidate_evidence "
        "WHERE observation_uid=? AND family='face' "
        "ORDER BY evidence_uid LIMIT ?",
        (observation_uid, MAX_FACE_EVIDENCE + 1)
    ).fetchall()
    if len(faces) > MAX_FACE_EVIDENCE:
        raise ValueError("Excessive spatial face evidence requires review")
    # The same underlying observation/source is NOT two independent
    # groups just because callers gave it different correlation labels.
    # This applies across ALL cue families for a given candidate, not
    # only facial cues; distinct candidate hypotheses may legitimately
    # refer to the same face detection without being confirmed.
    source_rows = db.execute(
        "SELECT candidate_person_id, source_reference, correlation_group "
        "FROM candidate_evidence WHERE observation_uid=? "
        "ORDER BY evidence_uid LIMIT ?",
        (observation_uid, MAX_TOTAL_EVIDENCE + 1)
    ).fetchall()
    if len(source_rows) > MAX_TOTAL_EVIDENCE:
        raise ValueError("Excessive independent-source audit evidence")
    by_candidate_source = {}
    unsafe_source_token = False
    for pid, source, group in source_rows:
        if (not isinstance(source, str) or not TOKEN.fullmatch(source)
                or not isinstance(group, str) or not TOKEN.fullmatch(group)):
            unsafe_source_token = True
        by_candidate_source.setdefault((pid, source), set()).add(group)
    aliases = sum(len(groups) > 1 for groups in by_candidate_source.values())
    if source_lineage_rows is None:
        lineage_audit = {
            "status": "not_supplied",
            "complete_declared_lineage_coverage": False,
            "actual_photo_lineage_independently_verified": False,
            "physical_source_independence_proven": False,
            "auto_confirm_allowed": False,
        }
    else:
        lineage_audit = audit_lineage_source_groups(
            source_rows, source_lineage_rows)
    aligned = 0
    blocked = (unsafe_source_token or aliases > 0
               or lineage_audit.get("candidate_results_suppressed", False))
    for (source_reference,) in faces:
        if not isinstance(source_reference, str) or not TOKEN.fullmatch(source_reference):
            blocked = True
            continue
        try:
            decision = region_audit.for_face(source_reference)
        except ValueError:
            blocked = True
            continue
        if (decision.asset_uid != stored_asset
                or decision.observation_uid != observation_uid
                or decision.status != "geometry_candidate_only"):
            blocked = True
        else:
            aligned += 1
    if blocked:
        result = _refusal(
            db, observation_uid, len(faces),
            regions=region_audit.aggregate, aligned=aligned,
            source_alias_conflicts=aliases, lineage_audit=lineage_audit)
    else:
        result = review_candidates(db, observation_uid, limit=limit)
        result["face_evidence_rows"] = len(faces)
        result["face_evidence_geometry_matched"] = aligned
        result["source_alias_conflicts"] = 0
        result["source_independence_checked"] = True
        result["source_lineage_audit"] = lineage_audit
        result["candidate_results_suppressed"] = False
        result["spatial_region_aggregate"] = region_audit.aggregate
        result["spatial_guard_status"] = (
            "all_same_frame_face_sources_geometry_matched"
            if faces else "no_same_frame_face_evidence")
        result["physical_face_body_identity_proven"] = False
        result["auto_confirm_allowed"] = False
    if changes != db.total_changes:
        raise RuntimeError("Spatial source review unexpectedly changed evidence database")
    return result
