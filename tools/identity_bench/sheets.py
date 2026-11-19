"""Blinded side-by-side contact sheets for human judgement.

One sheet per swap pair: original | reference photos | candidates, with the
candidates in a shuffled order under letters.  The letter key is written to
a separate file, so whoever rates the sheets cannot see which pipeline made
which tile.  Sheets show synthetic faces only and say so in the footer.
"""
from __future__ import annotations

import hashlib
import random

import numpy as np

try:
    import cv2
except ImportError:  # pragma: no cover
    cv2 = None

TILE = 320
FOOTER = "Synthetic faces, AI-edited benchmark output. Not real people. ATLAS identity bench (#469)."


def blind_order(labels, salt: str) -> list:
    """Deterministic shuffle of the candidate labels for one sheet."""
    ordered = sorted(labels)
    seed = int.from_bytes(hashlib.sha256(("identity-bench-sheet:" + salt).encode()).digest()[:8], "big")
    random.Random(seed).shuffle(ordered)
    return ordered


def letter(index: int) -> str:
    return chr(ord("A") + index) if index < 26 else f"A{index - 26}"


def fit(image: np.ndarray, side: int = TILE) -> np.ndarray:
    """Letterbox to a square tile without distorting the photo."""
    h, w = image.shape[:2]
    scale = side / max(h, w)
    small = cv2.resize(image, (max(1, round(w * scale)), max(1, round(h * scale))),
                       interpolation=cv2.INTER_AREA if scale < 1 else cv2.INTER_CUBIC)
    tile = np.full((side, side, 3), 24, np.uint8)
    y, x = (side - small.shape[0]) // 2, (side - small.shape[1]) // 2
    tile[y:y + small.shape[0], x:x + small.shape[1]] = small
    return tile


def zoom(image: np.ndarray, box, pad: float = 0.45) -> np.ndarray:
    """Square crop around the face box, so skin detail and seams are visible."""
    x, y, w, h = [float(v) for v in box]
    half = max(w, h) * (0.5 + pad)
    cx, cy = x + w / 2, y + h / 2
    x1, y1 = int(max(0, cx - half)), int(max(0, cy - half))
    x2, y2 = int(min(image.shape[1], cx + half)), int(min(image.shape[0], cy + half))
    return fit(image[y1:y2, x1:x2])


def caption(tile: np.ndarray, text: str) -> np.ndarray:
    bar = np.full((28, tile.shape[1], 3), 24, np.uint8)
    cv2.putText(bar, text, (6, 20), cv2.FONT_HERSHEY_SIMPLEX, 0.55, (235, 235, 235), 1, cv2.LINE_AA)
    return np.vstack([bar, tile])


def reference_tile(references: list) -> np.ndarray:
    """Up to four reference photos in a 2 x 2 grid."""
    half = TILE // 2
    cells = [fit(r, half) for r in references[:4]]
    while len(cells) < 4:
        cells.append(np.full((half, half, 3), 24, np.uint8))
    return np.vstack([np.hstack(cells[:2]), np.hstack(cells[2:])])


def contact_sheet(title: str, original: np.ndarray, references: list, candidates: dict, box, salt: str):
    """Returns (sheet image, {letter: candidate label})."""
    order = blind_order(candidates, salt)
    key = {letter(i): label for i, label in enumerate(order)}
    top = [caption(fit(original), "Original"), caption(reference_tile(references), "Reference person")]
    bottom = [caption(zoom(original, box), "Original, face"), caption(np.full((TILE, TILE, 3), 24, np.uint8), "")]
    for i, label in enumerate(order):
        top.append(caption(fit(candidates[label]), letter(i)))
        bottom.append(caption(zoom(candidates[label], box), letter(i) + ", face"))
    body = np.vstack([np.hstack(top), np.hstack(bottom)])
    head = np.full((34, body.shape[1], 3), 24, np.uint8)
    cv2.putText(head, title, (6, 24), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (255, 255, 255), 2, cv2.LINE_AA)
    foot = np.full((26, body.shape[1], 3), 24, np.uint8)
    cv2.putText(foot, FOOTER, (6, 18), cv2.FONT_HERSHEY_SIMPLEX, 0.45, (170, 170, 170), 1, cv2.LINE_AA)
    return np.vstack([head, body, foot]), key


def rating_form(sheet_ids: list) -> str:
    """CSV template for a rater: one row per sheet, best letter and letters that fail."""
    lines = ["sheet,looks_most_like_reference,most_natural,unacceptable_letters,notes"]
    lines += [f"{sheet_id},,,," for sheet_id in sheet_ids]
    return "\n".join(lines) + "\n"
