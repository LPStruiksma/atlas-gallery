#!/usr/bin/env python3
"""Mirror- and view-tolerant body-pose descriptor for query-by-image.

Pipeline: MediaPipe person detector -> BlazePose landmarks (OpenCV Zoo ONNX
exports, Apache-2.0). We keep the 3D "world" landmarks, which are metric and
hip-centred, and describe a pose by the unit directions of 12 bones after
removing yaw (rotation about the vertical axis). Gravity is kept, so an
upside-down cartwheel differs from a standing pose, while a left-to-right
cartwheel matches a right-to-left one (mirror) and a front view roughly
matches a side view (yaw).

Similarity of two descriptors = mean dot product of matching bone
directions, maximised over the mirrored variant. Range [-1, 1].
"""
import numpy as np

# BlazePose indices.
L_SH, R_SH, L_EL, R_EL, L_WR, R_WR = 11, 12, 13, 14, 15, 16
L_HIP, R_HIP, L_KN, R_KN, L_AN, R_AN = 23, 24, 25, 26, 27, 28
BONES = [
    (L_SH, L_EL), (L_EL, L_WR), (R_SH, R_EL), (R_EL, R_WR),
    (L_HIP, L_KN), (L_KN, L_AN), (R_HIP, R_KN), (R_KN, R_AN),
    (L_HIP, L_SH), (R_HIP, R_SH), (R_SH, L_SH), (R_HIP, L_HIP),
]
SWAP = {L_SH: R_SH, R_SH: L_SH, L_EL: R_EL, R_EL: L_EL, L_WR: R_WR, R_WR: L_WR,
        L_HIP: R_HIP, R_HIP: L_HIP, L_KN: R_KN, R_KN: L_KN, L_AN: R_AN, R_AN: L_AN}
DIM = len(BONES) * 3


def _unit(v):
    n = np.linalg.norm(v, axis=-1, keepdims=True)
    return v / np.maximum(n, 1e-6)


def _deyaw(world):
    """Rotate about the y (vertical, image-down) axis so the hip line is +x."""
    hip = world[L_HIP] - world[R_HIP]
    ang = np.arctan2(hip[2], hip[0])
    c, s = np.cos(ang), np.sin(ang)
    rot = np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]], dtype=np.float32)
    return world @ rot.T


def bones(world):
    w = _deyaw(world[:33].astype(np.float32))
    return np.concatenate([_unit(w[b] - w[a]) for a, b in BONES])


def descriptor(world):
    """Returns (2, DIM): the pose and its left/right mirror."""
    d = bones(world)
    m = world[:33].copy()
    m[:, 0] = -m[:, 0]
    perm = np.arange(33)
    for k, v in SWAP.items():
        perm[k] = v
    d2 = bones(m[perm])
    return np.stack([d, d2])


def similarity(da, db):
    """da, db: (2, DIM) descriptors -> scalar in [-1, 1]."""
    nb = len(BONES)
    return float(max((da[0] @ db[0]), (da[0] @ db[1])) / nb)


def similarity_matrix(desc, present):
    """desc: (N, 2, DIM); present: (N,) bool. Missing pose -> NaN row/col."""
    nb = len(BONES)
    a = desc[:, 0, :]
    s = np.maximum(a @ desc[:, 0, :].T, a @ desc[:, 1, :].T) / nb
    s[~present, :] = np.nan
    s[:, ~present] = np.nan
    return s
