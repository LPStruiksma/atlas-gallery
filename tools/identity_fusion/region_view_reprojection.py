#!/usr/bin/env python3
"""Reproject caller-declared view boxes into original person-region coordinates.

Only exact 0/90/180/270 rotations and horizontal mirroring are supported.
Cropping is specified in ORIGINAL normalized asset coordinates and happens
BEFORE rotation/mirroring. No image, EXIF, model or phone data is accessed.
Transform metadata is caller-supplied, never independently verified here.

This adapter refuses partial/source-clipped person regions rather than
pretending a rotated or cropped box is the stored person's full region.
"""
from __future__ import annotations

import math

from region_correspondence import BODY_FIELDS, FACE_FIELDS, _checked
from spatial_evidence_review import review_with_spatial_sources

CORNER_KEYS = ("x", "y", "w", "h")
ALLOWED_ROTATIONS = (0, 90, 180, 270)


def _metadata(crop, rotation, mirror):
    if type(rotation) is not int or rotation not in ALLOWED_ROTATIONS:
        raise ValueError("Only explicit orthogonal rotation is supported")
    if type(mirror) is not bool:
        raise ValueError("Mirror flag must be a boolean")
    if (not isinstance(crop, (tuple, list)) or len(crop) != 4
            or any(type(v) not in (int, float) or not math.isfinite(v)
                   for v in crop)):
        raise ValueError("Explicit finite normalized source crop required")
    x, y, w, h = crop
    if (x < 0 or y < 0 or w <= 0 or h <= 0
            or x + w > 1 + 1e-12 or y + h > 1 + 1e-12):
        raise ValueError("Invalid source image crop bounds")
    return x, y, w, h


def _transform_point(x, y, crop, rotation, mirror, direction):
    cx, cy, cw, ch = crop
    if direction == "source_to_view":
        u, v = (x - cx) / cw, (y - cy) / ch
        if rotation == 90:
            u, v = 1 - v, u
        elif rotation == 180:
            u, v = 1 - u, 1 - v
        elif rotation == 270:
            u, v = v, 1 - u
        if mirror:
            u = 1 - u
        return u, v
    if direction == "view_to_source":
        u, v = x, y
        if mirror:
            u = 1 - u
        if rotation == 90:
            u, v = v, 1 - u
        elif rotation == 180:
            u, v = 1 - u, 1 - v
        elif rotation == 270:
            u, v = 1 - v, u
        return cx + cw * u, cy + ch * v
    raise ValueError("Unsupported reprojection direction")


def reproject_regions(regions, *, is_face: bool, crop=(0, 0, 1, 1),
                      rotation: int = 0, mirror: bool = False,
                      direction: str = "view_to_source") -> list[dict]:
    """Return separately validated, explicitly reprojected opaque boxes.

    Each transform is axis-aligned exactly only for right-angle rotation,
    after the indicated *source-coordinate* crop and horizontal mirror.
    A source-to-view transform fails if the original region is clipped.
    View-to-source gives the *visible* region only; the subsequent strict
    stored-region comparison must detect cropped bodies and abstain.
    """
    crop = _metadata(crop, rotation, mirror)
    if type(is_face) is not bool:
        raise ValueError("Explicit box family required")
    if direction not in ("view_to_source", "source_to_view"):
        raise ValueError("Explicit transform direction required")
    fields = FACE_FIELDS if is_face else BODY_FIELDS
    uid_field = "face_uid" if is_face else "observation_uid"
    checked = _checked(regions, fields, uid_field, allow_empty=is_face)
    converted = []
    for original, (_, _, x, y, w, h) in zip(regions, checked):
        if direction == "source_to_view":
            cx, cy, cw, ch = crop
            if (x < cx - 1e-12 or y < cy - 1e-12
                    or x + w > cx + cw + 1e-12
                    or y + h > cy + ch + 1e-12):
                raise ValueError("Person or face region clipped by declared crop")
        corners = [
            _transform_point(px, py, crop, rotation, mirror, direction)
            for px in (x, x + w) for py in (y, y + h)
        ]
        left = min(p[0] for p in corners)
        top = min(p[1] for p in corners)
        right = max(p[0] for p in corners)
        bottom = max(p[1] for p in corners)
        if (left < -1e-10 or top < -1e-10
                or right > 1 + 1e-10 or bottom > 1 + 1e-10):
            raise ValueError("Reprojected ROI exceeds normalized image bounds")
        output = dict(original)
        output.update(x=max(0., left), y=max(0., top),
                      w=min(1., right) - max(0., left),
                      h=min(1., bottom) - max(0., top))
        converted.append(output)
    # Recheck bounding boxes after transformation; never accept tiny or
    # negative extents introduced by rounding or untrusted crop metadata.
    _checked(converted, fields, uid_field, allow_empty=is_face)
    return converted


def review_with_view_coordinates(db, observation_uid: str, *,
                                 view_body_regions, view_face_regions,
                                 source_crop=(0, 0, 1, 1), rotation=0,
                                 mirror=False, limit=12) -> dict:
    """Read-only strict review after reprojection into the ORIGINAL asset.

    A successfully mapped rectangular face is STILL only a geometric
    candidate. This caller-provided orientation/crop is not verified from
    EXIF or pixels. Arbitrary angles and clipped/stale person regions are
    deliberately unsupported and refused.
    """
    kwargs = dict(crop=source_crop, rotation=rotation, mirror=mirror,
                  direction="view_to_source")
    body = reproject_regions(view_body_regions, is_face=False, **kwargs)
    face = reproject_regions(view_face_regions, is_face=True, **kwargs)
    result = review_with_spatial_sources(
        db, observation_uid, body_regions=body, face_regions=face, limit=limit
    )
    result["reprojection_applied"] = True
    result["orientation_and_crop_metadata_independently_verified"] = False
    result["arbitrary_angle_rotation_supported"] = False
    result["physical_face_body_identity_proven"] = False
    result["auto_confirm_allowed"] = False
    return result
