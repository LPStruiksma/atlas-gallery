"""Measurements for the identity benchmark.  Pure numpy, plus OpenCV filters.

Every number is defined here once, so BENCH.md can point at a function.
"""
from __future__ import annotations

import math

import numpy as np

try:
    import cv2
except ImportError:  # pragma: no cover
    cv2 = None


def unit(v) -> np.ndarray:
    v = np.asarray(v, np.float64)
    return v / max(float(np.linalg.norm(v)), 1e-12)


def cosine(a, b) -> float:
    return float(unit(a) @ unit(b))


def centroid(vectors) -> np.ndarray:
    """Unit mean of unit vectors: one identity's reference set as a single point."""
    return unit(np.mean([unit(v) for v in vectors], axis=0))


def leave_one_out(vectors) -> float:
    """How similar a genuine photo of the person is to the rest of the reference set.

    The mean, over references, of the cosine between that reference and the
    centroid of the others.  A swap cannot be expected to beat this ceiling.
    """
    vectors = [unit(v) for v in vectors]
    if len(vectors) < 2:
        return float("nan")
    return float(np.mean([cosine(v, centroid(vectors[:i] + vectors[i + 1:])) for i, v in enumerate(vectors)]))


def rank_of(probe, gallery: dict, truth: str) -> tuple:
    """(rank of `truth` in the gallery, best wrong label, margin = truth score - best wrong score)."""
    scores = {label: cosine(probe, vector) for label, vector in gallery.items()}
    order = sorted(scores, key=lambda k: -scores[k])
    wrong = next(label for label in order if label != truth)
    return order.index(truth) + 1, wrong, scores[truth] - scores[wrong]


# ---------------------------------------------------------------- pixels

def luma(image: np.ndarray) -> np.ndarray:
    image = image.astype(np.float64)
    return 0.114 * image[..., 0] + 0.587 * image[..., 1] + 0.299 * image[..., 2]


def outside_change(source: np.ndarray, result: np.ndarray, mask: np.ndarray) -> dict:
    """Fidelity of the pixels the edit claims not to touch (mask == 0)."""
    outside = mask <= 0
    count = int(outside.sum())
    if count == 0:
        return dict(outside_px=0, outside_changed_px=0, outside_max_diff=0, outside_psnr=float("inf"), outside_ssim=1.0)
    diff = np.abs(source.astype(np.int16) - result.astype(np.int16))[outside]
    mse = float((diff.astype(np.float64) ** 2).mean())
    return dict(
        outside_px=count,
        outside_changed_px=int((diff.max(axis=-1) > 0).sum()),
        outside_max_diff=int(diff.max()),
        outside_psnr=float("inf") if mse == 0 else 10 * math.log10(255.0 ** 2 / mse),
        outside_ssim=ssim(source, result, outside),
    )


def ssim(a: np.ndarray, b: np.ndarray, where: np.ndarray | None = None) -> float:
    """Mean SSIM on luma (Gaussian window, sigma 1.5), optionally only over `where`."""
    x, y = luma(a), luma(b)
    if np.array_equal(x, y):
        return 1.0
    blur = lambda z: cv2.GaussianBlur(z, (11, 11), 1.5)
    mx, my = blur(x), blur(y)
    vx, vy, cxy = blur(x * x) - mx * mx, blur(y * y) - my * my, blur(x * y) - mx * my
    c1, c2 = (0.01 * 255) ** 2, (0.03 * 255) ** 2
    value = ((2 * mx * my + c1) * (2 * cxy + c2)) / ((mx * mx + my * my + c1) * (vx + vy + c2))
    if where is None:
        return float(value.mean())
    if cv2 is not None:  # keep windows that lie wholly in the region
        where = cv2.erode(where.astype(np.uint8), np.ones((11, 11), np.uint8)) > 0
    return float(value[where].mean()) if where.any() else 1.0


def gradient(image: np.ndarray) -> np.ndarray:
    y = luma(image)
    gx = np.zeros_like(y)
    gy = np.zeros_like(y)
    gx[:, 1:-1] = (y[:, 2:] - y[:, :-2]) / 2
    gy[1:-1, :] = (y[2:, :] - y[:-2, :]) / 2
    return np.hypot(gx, gy)


def seam(source: np.ndarray, result: np.ndarray, mask: np.ndarray) -> dict:
    """Visibility of the blend boundary.

    seam_gradient: in the feather band (0 < mask < 1), the mean edge energy
    the edit added over the source's own (luma levels per pixel, never
    negative).  0 means the band is no busier than before.
    seam_step: how much the colour step across the boundary changed: the
    Lab distance between a thin ring just inside the edit and a thin ring
    just outside it, result minus source (absolute value, Lab units).
    """
    band = (mask > 0.02) & (mask < 0.98)
    if band.sum() < 16:
        return dict(seam_gradient=float("nan"), seam_step=float("nan"))
    added = np.maximum(gradient(result) - gradient(source), 0.0)
    out = dict(seam_gradient=float(added[band].mean()))
    edited = (mask > 0).astype(np.uint8)
    radius = max(3, int(round(math.sqrt(edited.sum()) * 0.04)))
    kernel = np.ones((2 * radius + 1, 2 * radius + 1), np.uint8)
    outer = (cv2.dilate(edited, kernel) > 0) & (edited == 0)
    solid = (mask >= 0.98).astype(np.uint8)
    inner = (solid > 0) & (cv2.erode(solid, kernel) == 0)
    if outer.sum() < 16 or inner.sum() < 16:
        out["seam_step"] = float("nan")
        return out
    lab = lambda im: cv2.cvtColor(im, cv2.COLOR_BGR2LAB).astype(np.float64)
    step = lambda im: lab(im)[inner].mean(0) - lab(im)[outer].mean(0)
    out["seam_step"] = float(np.linalg.norm(step(result) - step(source)))
    return out


def sharpness_ratio(source: np.ndarray, result: np.ndarray, mask: np.ndarray) -> float:
    """Fine detail kept inside the edit: Laplacian energy of the result over the source's, where mask >= 0.98."""
    inside = mask >= 0.98
    if inside.sum() < 64:
        return float("nan")
    energy = lambda im: float(np.var(cv2.Laplacian(luma(im), cv2.CV_64F)[inside]))
    return energy(result) / max(energy(source), 1e-9)


def landmark_shift(before, after) -> float:
    """Mean landmark displacement after the swap, in units of the eye distance (pose and expression drift)."""
    before, after = np.asarray(before, np.float64), np.asarray(after, np.float64)
    eye = float(np.linalg.norm(before[1] - before[0]))
    return float(np.linalg.norm(after - before, axis=1).mean() / max(eye, 1e-9))


def inside_difference(a: np.ndarray, b: np.ndarray, mask: np.ndarray) -> float:
    """Mean absolute pixel difference between two results inside the edit (0..255)."""
    inside = mask > 0
    if not inside.any():
        return 0.0
    return float(np.abs(a.astype(np.float64) - b.astype(np.float64))[inside].mean())


# ---------------------------------------------------------------- aggregation

def summary(values) -> dict:
    values = np.asarray([v for v in values if v is not None and not (isinstance(v, float) and math.isnan(v))], np.float64)
    if values.size == 0:
        return dict(n=0, mean=float("nan"), median=float("nan"), p10=float("nan"), min=float("nan"))
    return dict(n=int(values.size), mean=float(values.mean()), median=float(np.median(values)),
                p10=float(np.percentile(values, 10)), min=float(values.min()))


def paired_bootstrap(a, b, rounds: int = 2000, seed: int = 469) -> dict:
    """Mean of a - b over the same pairs, with a 95 % bootstrap interval."""
    delta = np.asarray(a, np.float64) - np.asarray(b, np.float64)
    if delta.size == 0:
        return dict(mean=float("nan"), low=float("nan"), high=float("nan"))
    rng = np.random.default_rng(seed)
    means = delta[rng.integers(0, delta.size, (rounds, delta.size))].mean(axis=1)
    return dict(mean=float(delta.mean()), low=float(np.percentile(means, 2.5)), high=float(np.percentile(means, 97.5)))


# Written down before the final run (BENCH.md, "Acceptance thresholds").
THRESHOLDS = dict(
    judge_same_person=0.40,     # research judge cosine above every stranger pair seen in the fixture pool (max 0.43 of 34,980, p99 0.30)
    judge_mean_min=0.50,        # A1: mean research-judge similarity to the reference set
    verified_share_min=0.80,    # A1: share of swaps over judge_same_person
    rank1_min=0.90,             # A2: rank-1 against the gallery with look-alikes and the original person
    retention_min=0.75,         # A3: mean similarity as a fraction of the genuine-photo ceiling
    outside_changed_px_max=0,   # A4: pixels changed where the mask is 0
    seam_gradient_share_max=0.25,  # A5: seam_gradient as a fraction of the hard-edge control's
    restore_identity_loss_max=0.02,  # A6: restoration may cost at most this much mean judge similarity
)


def verdict(row: dict, hard_edge_seam: float) -> dict:
    """Pass/fail of one candidate's aggregate row against THRESHOLDS."""
    t = THRESHOLDS
    checks = {
        "A1 identity": row["judge_mean"] >= t["judge_mean_min"] and row["verified_share"] >= t["verified_share_min"],
        "A2 rank-1": row["rank1"] >= t["rank1_min"],
        "A3 retention": row["retention"] >= t["retention_min"],
        "A4 untouched": row["outside_changed_px"] <= t["outside_changed_px_max"],
        # None when the run has no hard-edge control to compare with.
        "A5 seam": None if math.isnan(hard_edge_seam) else
        row["seam_gradient"] <= t["seam_gradient_share_max"] * hard_edge_seam,
    }
    checks["all"] = all(v for v in checks.values() if v is not None)
    return checks
