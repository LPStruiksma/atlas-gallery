"""Local metric primitives for real benchmark plugins.

No network/model downloads. Pillow is optional for loading arbitrary PNG/JPEGs.
A caller supplies face detections+embeddings and calibrated LPIPS spatial maps.
"""
from __future__ import annotations

import math
from pathlib import Path

def unchanged_ssim(source, output, mask) -> float | None:
    """Global RGB SSIM on pixels OUTSIDE the edit mask.

    Inputs are aligned 2D rows of RGB 0..255 tuples; mask has bool/0..255 values
    where True/positive means EDITED. A global SSIM over unedited channels is
    reproducible but should not be confused with multiscale/windowed SSIM.
    """
    if not source or len(source) != len(output) or len(source) != len(mask):
        raise ValueError("source/output/mask height mismatch")
    lhs, rhs = [], []
    for sr, orow, mr in zip(source, output, mask):
        if len(sr) != len(orow) or len(sr) != len(mr):
            raise ValueError("source/output/mask width mismatch")
        for original, candidate, edit in zip(sr, orow, mr):
            if bool(edit):
                continue
            if len(original) != 3 or len(candidate) != 3:
                raise ValueError("RGB triples required")
            lhs.extend(float(v) / 255 for v in original)
            rhs.extend(float(v) / 255 for v in candidate)
    if not lhs:
        return None
    n = len(lhs)
    mu_a, mu_b = sum(lhs) / n, sum(rhs) / n
    va = sum((v - mu_a)**2 for v in lhs) / n
    vb = sum((v - mu_b)**2 for v in rhs) / n
    cov = sum((a - mu_a)*(b - mu_b) for a, b in zip(lhs, rhs)) / n
    c1, c2 = 0.01**2, 0.03**2
    value = ((2*mu_a*mu_b+c1)*(2*cov+c2)) / (
        (mu_a*mu_a+mu_b*mu_b+c1)*(va+vb+c2))
    return max(0.0, min(1.0, value))

def unchanged_lpips_map(spatial_distances, mask) -> float | None:
    """Aggregate a locally computed LPIPS distance map outside the edit mask.

    Model callback must provide a spatial LPIPS map ALIGNED to this mask grid.
    A global scalar LPIPS is not valid for unchanged-region measurement.
    """
    if len(spatial_distances) != len(mask):
        raise ValueError("LPIPS map/mask mismatch")
    vals = []
    for row, mrow in zip(spatial_distances, mask):
        if len(row) != len(mrow):
            raise ValueError("LPIPS map/mask mismatch")
        for distance, edited in zip(row, mrow):
            if not edited:
                if not isinstance(distance, (float, int)) or not math.isfinite(distance):
                    raise ValueError("invalid LPIPS distance")
                if distance < 0:
                    raise ValueError("negative LPIPS distance")
                vals.append(float(distance))
    return sum(vals) / len(vals) if vals else None

def _cosine(left, right) -> float:
    if len(left) != len(right) or not left:
        raise ValueError("incompatible face embeddings")
    if not all(isinstance(v, (int, float)) and math.isfinite(v)
               for v in list(left) + list(right)):
        raise ValueError("invalid face vector")
    dot = sum(a*b for a, b in zip(left, right))
    l = math.sqrt(sum(a*a for a in left))
    r = math.sqrt(sum(b*b for b in right))
    if not l or not r:
        raise ValueError("zero face embedding")
    return max(0.0, min(1.0, dot / (l*r)))

def _iou(a, b) -> float:
    x0, y0 = max(a[0], b[0]), max(a[1], b[1])
    x1, y1 = min(a[2], b[2]), min(a[3], b[3])
    area = max(0., x1-x0) * max(0., y1-y0)
    ar_a = max(0., a[2]-a[0]) * max(0., a[3]-a[1])
    ar_b = max(0., b[2]-b[0]) * max(0., b[3]-b[1])
    denominator = ar_a + ar_b - area
    return area / denominator if denominator > 0 else 0.

def face_embedding_similarity(source_faces: list[dict],
                              output_faces: list[dict]) -> float | None:
    """One-to-one matched detected faces; missing or ambiguous faces return None.

    Each detected face: {"box":[x0,y0,x1,y1], "embedding":[numbers...]}.
    Coords should be normalized, aligned to the source. No vectors are persisted.
    """
    if not source_faces or len(source_faces) != len(output_faces):
        return None
    used, sims = set(), []
    for person in source_faces:
        matches = sorted(
            ((_iou(person["box"], candidate["box"]), i)
             for i, candidate in enumerate(output_faces) if i not in used),
            reverse=True)
        if not matches or matches[0][0] < 0.1:
            return None
        if len(matches) > 1 and matches[1][0] == matches[0][0]:
            return None
        idx = matches[0][1]
        used.add(idx)
        sims.append(_cosine(person["embedding"], output_faces[idx]["embedding"]))
    return sum(sims) / len(sims)

def score_region_pillow(case: dict, record: dict, source_path: Path,
                        mask_path: Path) -> dict:
    """Callable local judge for real outputs (pip install Pillow, no network at runtime).

    Computes mask-aware global SSIM; other unavailable metrics remain NULL.
    For full LPIPS, face embeddings and adherence use a calibrated local judge
    implementing the callback protocol in README.md.
    """
    from PIL import Image  # optional, never imported in the mock/CI path
    output_name = record.get("output_path")
    if not output_name:
        return {}
    output = Path(output_name)
    root = source_path.parent.parent.resolve()
    output = (root / output).resolve()
    if not output.is_relative_to(root):
        raise ValueError("output_path escapes experiment folder")
    with (Image.open(source_path) as src, Image.open(mask_path) as mask,
          Image.open(output) as edited):
        if src.size != edited.size or src.size != mask.size:
            raise ValueError("image sizes differ; alignment required before SSIM")
        s, e, m = src.convert("RGB"), edited.convert("RGB"), mask.convert("L")
        w, h = s.size
        flat_s, flat_e, flat_m = list(s.getdata()), list(e.getdata()), list(m.getdata())
        rows_s = [flat_s[y*w:(y+1)*w] for y in range(h)]
        rows_e = [flat_e[y*w:(y+1)*w] for y in range(h)]
        rows_m = [flat_m[y*w:(y+1)*w] for y in range(h)]
    return {"unchanged_ssim": unchanged_ssim(rows_s, rows_e, rows_m)}
