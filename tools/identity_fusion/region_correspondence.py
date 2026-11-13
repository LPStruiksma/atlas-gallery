#!/usr/bin/env python3
"""Conservative, model-free face/body region association for V5 research.

Consumes ONLY opaque identifiers and normalized rectangles supplied by a
caller. Does not load photographs, databases, embeddings or model weights.
A unique geometric association is a *review clue*, NEVER person identity.
Ambiguous/overlapping faces, multi-person crops and repeated detections
fail closed instead of manufacturing face/body identity agreement.
"""
from __future__ import annotations

from collections import Counter, defaultdict
from dataclasses import dataclass
import math
import re

BODY_FIELDS = frozenset(("asset_uid", "observation_uid", "x", "y", "w", "h"))
FACE_FIELDS = frozenset(("asset_uid", "face_uid", "x", "y", "w", "h"))
TOKEN = re.compile(r"[A-Za-z0-9_-]{1,64}\Z")
MAX_BOXES = 128
DUPLICATE_FACE_IOU = 0.6
HEAD_ZONE_FRACTION = 0.55
BOUNDARY_PADDING_FRACTION = 0.02


@dataclass(frozen=True)
class SpatialDecision:
    """Ephemeral association; never publish IDs without owner permission."""
    asset_uid: str
    face_uid: str
    observation_uid: str | None
    status: str  # geometry_candidate_only, ambiguous, uncertain, unmatched
    identity_confirmed: bool = False


@dataclass(frozen=True)
class SpatialAudit:
    decisions: tuple[SpatialDecision, ...]
    aggregate: dict

    def for_face(self, face_uid: str) -> SpatialDecision:
        """Return in-memory single-face evidence; not an identity assignment."""
        found = [d for d in self.decisions if d.face_uid == face_uid]
        if len(found) != 1:
            raise ValueError("Face key missing or not globally unique")
        return found[0]


def _checked(rows, fields, uid_field, *, allow_empty=False):
    if (not isinstance(rows, (list, tuple))
            or not (0 if allow_empty else 1) <= len(rows) <= MAX_BOXES):
        raise ValueError("Expected bounded region observations")
    seen = set()
    output = []
    for row in rows:
        if not isinstance(row, dict) or set(row) != fields:
            raise ValueError("Unsafe or incomplete region fields")
        asset, uid = row["asset_uid"], row[uid_field]
        if (not isinstance(asset, str) or not isinstance(uid, str)
                or not TOKEN.fullmatch(asset) or not TOKEN.fullmatch(uid)):
            raise ValueError("Opaque asset/region tokens required")
        if uid in seen:
            raise ValueError("Duplicate region identifier")
        seen.add(uid)
        coords = tuple(row[k] for k in ("x", "y", "w", "h"))
        if (any(type(v) not in (int, float) or not math.isfinite(v)
                for v in coords)
                or not all(0 <= v <= 1 for v in coords)
                or coords[2] <= 0 or coords[3] <= 0
                or coords[0] + coords[2] > 1 + 1e-12
                or coords[1] + coords[3] > 1 + 1e-12):
            raise ValueError("Invalid normalized rectangle")
        output.append((asset, uid, *coords))
    return output


def _iou(a, b):
    _, _, ax, ay, aw, ah = a
    _, _, bx, by, bw, bh = b
    intersection = max(0., min(ax + aw, bx + bw) - max(ax, bx))
    intersection *= max(0., min(ay + ah, by + bh) - max(ay, by))
    union = aw * ah + bw * bh - intersection
    return intersection / union if union > 0 else 0.


def _face_intersection_fraction(face, body):
    """A face intersecting two different body boxes is not uniquely owned."""
    _, _, fx, fy, fw, fh = face
    _, _, bx, by, bw, bh = body
    dx = max(0., min(fx + fw, bx + bw) - max(fx, bx))
    dy = max(0., min(fy + fh, by + bh) - max(fy, by))
    return dx * dy / (fw * fh)


def _center_inside(face, body):
    _, _, fx, fy, fw, fh = face
    _, _, bx, by, bw, bh = body
    cx, cy = fx + fw / 2, fy + fh / 2
    return bx <= cx <= bx + bw and by <= cy <= by + bh


def _confident_containment(face, body):
    """Strict geometry-only gate; success does not imply same identity."""
    _, _, fx, fy, fw, fh = face
    _, _, bx, by, bw, bh = body
    mx, my = bw * BOUNDARY_PADDING_FRACTION, bh * BOUNDARY_PADDING_FRACTION
    return (
        bx + mx <= fx and fx + fw <= bx + bw - mx
        and by + my <= fy and fy + fh <= by + HEAD_ZONE_FRACTION * bh
    )


def audit_region_correspondence(body_rows, face_rows) -> SpatialAudit:
    """Associate each face with at most one person-region on its own asset.

    The return contains *ephemeral opaque* per-face routing information for
    human-region-review interfaces. Only `aggregate` may be exported.
    """
    bodies = _checked(body_rows, BODY_FIELDS, "observation_uid")
    faces = _checked(face_rows, FACE_FIELDS, "face_uid", allow_empty=True)
    by_asset = defaultdict(list)
    for body in bodies:
        by_asset[body[0]].append(body)
    duplicate_faces = set()
    for i, a in enumerate(faces):
        for b in faces[i + 1:]:
            if a[0] == b[0] and _iou(a, b) >= DUPLICATE_FACE_IOU:
                duplicate_faces.update((a[1], b[1]))
    intermediate = []
    for face in faces:
        asset, fid, *_ = face
        if fid in duplicate_faces:
            intermediate.append((asset, fid, None, "ambiguous"))
            continue
        # A face box significantly intersecting another person's region
        # is ambiguous even if its CENTER happens to be inside only one.
        candidates = [b for b in by_asset[asset]
                      if _center_inside(face, b)
                      or _face_intersection_fraction(face, b) >= 0.20]
        if not candidates:
            intermediate.append((asset, fid, None, "unmatched"))
        elif len(candidates) != 1:
            intermediate.append((asset, fid, None, "ambiguous"))
        elif not _confident_containment(face, candidates[0]):
            intermediate.append((asset, fid, None, "uncertain"))
        else:
            intermediate.append((asset, fid, candidates[0][1],
                                 "geometry_candidate_only"))

    # One person-region owning two separate face detections is ambiguous,
    # unless an independently verified track can reconcile them later.
    region_face_counts = Counter(
        region for _, _, region, status in intermediate
        if status == "geometry_candidate_only"
    )
    decisions = []
    for asset, fid, region, status in intermediate:
        if region is not None and region_face_counts[region] > 1:
            region, status = None, "ambiguous"
        decisions.append(SpatialDecision(asset, fid, region, status))
    counts = Counter(d.status for d in decisions)
    return SpatialAudit(
        decisions=tuple(sorted(decisions, key=lambda d: d.face_uid)),
        aggregate={
            "status": "geometry_audit_only",
            "person_regions": len(bodies),
            "face_regions": len(faces),
            "geometry_candidate_only": counts["geometry_candidate_only"],
            "ambiguous": counts["ambiguous"],
            "uncertain": counts["uncertain"],
            "unmatched": counts["unmatched"],
            "identity_confirmed": False,
            "auto_confirm_allowed": False,
            "physical_face_body_identity_proven": False,
            "caveat": "Overlap/containment is not person identity. Head pose, "
                      "occlusion, nested detections and cross-frame tracks "
                      "require independent reviewed association.",
        }
    )
