"""Offline, read-only photographic-lineage evidence. No identity or deletion decisions.

Requires numpy, OpenCV, and Pillow. All thresholds are conservative prototype
heuristics: they are not calibrated probabilities or validated production cutoffs.
"""
from __future__ import annotations

from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Literal
import os
import stat

import cv2
import numpy as np
from PIL import Image, ImageOps, UnidentifiedImageError

Algorithm = Literal["sift", "orb", "akaze"]


@dataclass(frozen=True)
class Evidence:
    verdict: str
    reason: str
    algorithm: str
    image_a: str
    image_b: str
    keypoints_a: int = 0
    keypoints_b: int = 0
    mutual_matches: int = 0
    inliers: int = 0
    inlier_fraction: float = 0.0
    spread_a: float = 0.0
    spread_b: float = 0.0
    gray_correlation: float = 0.0
    gradient_correlation: float = 0.0
    overlap_a: float = 0.0
    overlap_b: float = 0.0
    local_consistency: float = 0.0
    texture_tiles: int = 0
    rotation_degrees: float | None = None
    scale: float | None = None
    global_hash_distance: int | None = None

    def json(self) -> dict:
        return asdict(self)


def _open_nofollow_components(path: Path) -> int:
    """Open every directory and final image as descriptor-anchored nofollow.

    O_NOFOLLOW on a leaf alone cannot stop symlinked parent-directory
    traversal. The caller must close the returned regular-file descriptor.
    """
    if not getattr(os, "O_NOFOLLOW", 0) or not getattr(os, "O_DIRECTORY", 0):
        raise ValueError("Secure nofollow directory reads unavailable")
    absolute = path.absolute()
    if ".." in absolute.parts or len(absolute.parts) < 2:
        raise ValueError("Parent traversal in image path is refused")
    flags = os.O_RDONLY | os.O_NOFOLLOW | getattr(os, "O_CLOEXEC", 0)
    directory_fd = os.open(absolute.anchor, flags | os.O_DIRECTORY)
    try:
        for component in absolute.parts[1:-1]:
            next_fd = os.open(component, flags | os.O_DIRECTORY, dir_fd=directory_fd)
            os.close(directory_fd)
            directory_fd = next_fd
        return os.open(absolute.name, flags, dir_fd=directory_fd)
    finally:
        os.close(directory_fd)


def read_grayscale(path: str | Path, max_side: int = 1100) -> np.ndarray:
    """Read with EXIF orientation. Refuse giant/decompression-bomb images.

    Symlinks are forbidden to reduce surprises when traversing media roots.
    This function only reads; no original image is modified.
    """
    path = Path(path)
    if path.is_symlink() or not path.is_file():
        raise ValueError(f"Not a regular non-symlink file: {path}")
    if max_side < 128 or max_side > 4096:
        raise ValueError("max_side must be 128..4096")
    # Walk parent directories by nofollow descriptors as well: a secure
    # leaf open alone cannot prevent an attacker swapping a parent symlink.
    try:
        fd = _open_nofollow_components(path)
        with os.fdopen(fd, "rb") as stream:
            if not stat.S_ISREG(os.fstat(stream.fileno()).st_mode):
                raise ValueError("Image source must remain an ordinary regular file")
            with Image.open(stream) as img:
                if img.width * img.height > 95_000_000:
                    raise ValueError("Image over 95 megapixels: refused")
                img = ImageOps.exif_transpose(img)
                img = img.convert("L")
                img.thumbnail((max_side, max_side), Image.Resampling.LANCZOS)
                gray = np.asarray(img).copy()
    except (UnidentifiedImageError, OSError, Image.DecompressionBombError) as exc:
        raise ValueError(f"Unsupported or unsafe image: {path}") from exc
    if gray.shape[0] < 48 or gray.shape[1] < 48:
        raise ValueError(f"Image too small for geometric verification: {path}")
    return gray


def phash(gray: np.ndarray) -> int:
    """64-bit DCT hash; advisory only, never an independent confirmation."""
    tile = cv2.resize(gray, (32, 32), interpolation=cv2.INTER_AREA)
    dct = cv2.dct(np.float32(tile))[:8, :8]
    values = dct.flatten()
    med = np.median(values[1:])
    bits = values > med
    n = 0
    for bit in bits:
        n = (n << 1) | int(bit)
    return n


def cardinal_hash_distance(a: np.ndarray, b: np.ndarray) -> int:
    """Global hash distance across 90-degree rotations (not arbitrary angle)."""
    h = phash(a)
    return min((h ^ phash(np.rot90(b, i))).bit_count() for i in range(4))


def detector(name: Algorithm, nfeatures: int = 900):
    if name == "sift":
        return cv2.SIFT_create(nfeatures=nfeatures), cv2.NORM_L2, 0.73
    if name == "orb":
        return cv2.ORB_create(nfeatures=nfeatures, fastThreshold=10), cv2.NORM_HAMMING, 0.78
    if name == "akaze":
        return cv2.AKAZE_create(), cv2.NORM_HAMMING, 0.76
    raise ValueError(f"Unsupported feature algorithm: {name}")


def _mutual_ratio(desc_a: np.ndarray, desc_b: np.ndarray, norm: int, ratio: float):
    matcher = cv2.BFMatcher(norm)
    forward = matcher.knnMatch(desc_a, desc_b, k=2)
    backward = matcher.knnMatch(desc_b, desc_a, k=2)

    def good(rows):
        return {m.queryIdx: m.trainIdx for row in rows if len(row) == 2
                for m, other in [row] if m.distance < ratio * other.distance}

    fwd, rev = good(forward), good(backward)
    return [(ia, ib) for ia, ib in fwd.items() if rev.get(ib) == ia]


def _spread(points: np.ndarray, shape: tuple[int, int]) -> float:
    """Normalized bounding rectangle area, to penalize logos/repeated local motifs."""
    if len(points) < 3:
        return 0.0
    span = np.ptp(points, axis=0)
    return float(np.prod(span / np.array([shape[1], shape[0]])))


def _correlation(x: np.ndarray, y: np.ndarray) -> float:
    x, y = x.astype(np.float32), y.astype(np.float32)
    x -= x.mean()
    y -= y.mean()
    norm = float(np.linalg.norm(x) * np.linalg.norm(y))
    return float(np.dot(x, y) / norm) if norm > 1e-5 else 0.0


def _photometry(a: np.ndarray, b: np.ndarray, matrix: np.ndarray):
    h, w = b.shape
    warped = cv2.warpAffine(a, matrix, (w, h), flags=cv2.INTER_LINEAR)
    mask = cv2.warpAffine(np.full_like(a, 255), matrix, (w, h), flags=cv2.INTER_NEAREST)
    mask = cv2.erode(mask, np.ones((7, 7), dtype=np.uint8)) > 0
    pixels = int(np.count_nonzero(mask))
    frac_a = min(1.0, pixels / a.size)  # Approximate for very large scale changes.
    frac_b = min(1.0, pixels / b.size)
    if pixels < 1024:
        return 0.0, 0.0, frac_a, frac_b, 0.0, 0
    # Boundary effects from warping are excluded by eroding overlap mask.
    corr_gray = _correlation(warped[mask], b[mask])

    def edges(gray):
        img = cv2.GaussianBlur(gray, (3, 3), 0).astype(np.float32)
        dx = cv2.Sobel(img, cv2.CV_32F, 1, 0, ksize=3)
        dy = cv2.Sobel(img, cv2.CV_32F, 0, 1, ksize=3)
        return cv2.magnitude(dx, dy)
    ea, eb = edges(warped), edges(b)
    corr_edges = _correlation(ea[mask], eb[mask])
    # Different exposures may share a static background. Global correlations
    # can conceal a large changed foreground, so check local consistency too.
    eligible = passing = 0
    for ys in np.array_split(np.arange(h), 8):
        for xs in np.array_split(np.arange(w), 8):
            if not len(ys) or not len(xs):
                continue
            sl = np.s_[ys[0]:ys[-1] + 1, xs[0]:xs[-1] + 1]
            m = mask[sl]
            if np.count_nonzero(m) < max(200, 0.5 * m.size):
                continue
            eligible += 1
            if _correlation(ea[sl][m], eb[sl][m]) >= 0.45:
                passing += 1
    local_consistency = passing / eligible if eligible else 0.0
    return corr_gray, corr_edges, frac_a, frac_b, local_consistency, eligible


MAX_COMPARE_SIDE = 4096
MAX_COMPARE_PIXELS = MAX_COMPARE_SIDE * MAX_COMPARE_SIDE
MIN_COMPARE_SIDE = 48


def _validate_compare_array(gray: np.ndarray) -> None:
    """Bound image cost before pHash or native feature extraction.

    Negative-stride 2-D views (e.g. np.rot90) are allowed and never changed.
    """
    if (type(gray) is not np.ndarray or gray.ndim != 2
            or gray.dtype != np.uint8 or gray.shape[0] < MIN_COMPARE_SIDE
            or gray.shape[1] < MIN_COMPARE_SIDE
            or gray.shape[0] > MAX_COMPARE_SIDE
            or gray.shape[1] > MAX_COMPARE_SIDE
            or gray.size > MAX_COMPARE_PIXELS):
        raise ValueError("Expected bounded 2-D uint8 grayscale array (48..4096 per side)")


def compare_arrays(a: np.ndarray, b: np.ndarray, algorithm: Algorithm = "sift") -> Evidence:
    """Compare grayscale image arrays; candidate != proven same source exposure."""
    _validate_compare_array(a)
    _validate_compare_array(b)
    hash_distance = cardinal_hash_distance(a, b)
    features, norm, ratio = detector(algorithm)
    kp_a, desc_a = features.detectAndCompute(a, None)
    kp_b, desc_b = features.detectAndCompute(b, None)
    base = dict(algorithm=algorithm, image_a="<array>", image_b="<array>",
                keypoints_a=len(kp_a), keypoints_b=len(kp_b), global_hash_distance=hash_distance)
    if desc_a is None or desc_b is None or len(desc_a) < 8 or len(desc_b) < 8:
        return Evidence(verdict="unknown", reason="Insufficient distinctive visual features", **base)
    matches = _mutual_ratio(desc_a, desc_b, norm, ratio)
    base["mutual_matches"] = len(matches)
    if len(matches) < 9:
        return Evidence(verdict="unknown", reason="Insufficient independent descriptor matches", **base)
    pts_a = np.float32([kp_a[i].pt for i, _ in matches])
    pts_b = np.float32([kp_b[j].pt for _, j in matches])
    # A similarity transform accepts crop, rotation, translation, resizing, and
    # mirror-free photo edits but has fewer degrees of freedom than homography.
    matrix, mask = cv2.estimateAffinePartial2D(
        pts_a, pts_b, method=cv2.RANSAC, ransacReprojThreshold=3.5,
        maxIters=3500, confidence=0.995, refineIters=10)
    if matrix is None or mask is None:
        return Evidence(verdict="unknown", reason="No coherent geometric transformation", **base)
    keep = mask.ravel().astype(bool)
    count = int(keep.sum())
    fraction = count / len(matches)
    spread_a = _spread(pts_a[keep], a.shape)
    spread_b = _spread(pts_b[keep], b.shape)
    scale = float(np.hypot(matrix[0, 0], matrix[1, 0]))
    rotation = float(np.degrees(np.arctan2(matrix[1, 0], matrix[0, 0])))
    # A tiny repeated watermark/object must not confirm entire photographs.
    evidence = dict(base, inliers=count, inlier_fraction=round(fraction, 4),
                    spread_a=round(spread_a, 4), spread_b=round(spread_b, 4),
                    rotation_degrees=round(rotation, 2), scale=round(scale, 4))
    if count < 9 or fraction < 0.32 or max(spread_a, spread_b) < 0.04 or scale < 0.15 or scale > 7.0:
        return Evidence(verdict="unknown", reason="Weak, localized, or implausible geometric agreement", **evidence)
    gray, edge, coverage_a, coverage_b, local, tiles = _photometry(a, b, matrix)
    evidence.update(gray_correlation=round(gray, 4), gradient_correlation=round(edge, 4),
                    overlap_a=round(coverage_a, 4), overlap_b=round(coverage_b, 4),
                    local_consistency=round(local, 4), texture_tiles=tiles)
    if count >= 14 and fraction >= 0.48 and max(spread_a, spread_b) >= 0.12 and min(spread_a, spread_b) >= 0.012 and local >= 0.88 and tiles >= 4 and (gray >= 0.76 or edge >= 0.53):
        return Evidence(verdict="candidate", reason="Distributed geometric inliers with compatible image structure; review required", **evidence)
    if count >= 10 and fraction >= 0.36 and max(spread_a, spread_b) >= 0.07 and (gray >= 0.55 or edge >= 0.36):
        return Evidence(verdict="review", reason="Plausible shared photographic content; insufficient for strong candidate", **evidence)
    return Evidence(verdict="unknown", reason="Photometric or geometric evidence below conservative review gate", **evidence)


def compare_files(path_a: str | Path, path_b: str | Path, algorithm: Algorithm = "sift", max_side: int = 1100) -> Evidence:
    a, b = read_grayscale(path_a, max_side), read_grayscale(path_b, max_side)
    result = compare_arrays(a, b, algorithm)
    details = result.json()
    details["image_a"] = str(Path(path_a))
    details["image_b"] = str(Path(path_b))
    return Evidence(**details)
