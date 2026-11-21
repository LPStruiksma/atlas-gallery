"""OCR metrics with an explicit, shared normalization contract."""

from __future__ import annotations

import re
import unicodedata


def normalize(text: str) -> str:
    """NFC-normalize, case-fold, and collapse Unicode whitespace."""
    return re.sub(r"\s+", " ", unicodedata.normalize("NFC", text).casefold()).strip()


def edit_distance(reference: list[str], hypothesis: list[str]) -> int:
    previous = list(range(len(hypothesis) + 1))
    for row, expected in enumerate(reference, 1):
        current = [row]
        for column, actual in enumerate(hypothesis, 1):
            current.append(min(current[-1] + 1, previous[column] + 1,
                               previous[column - 1] + (expected != actual)))
        previous = current
    return previous[-1]


def error_rate(reference: str, hypothesis: str, *, words: bool = False) -> float:
    ref = normalize(reference)
    hyp = normalize(hypothesis)
    ref_units = ref.split() if words else list(ref)
    hyp_units = hyp.split() if words else list(hyp)
    if not ref_units:
        return 0.0 if not hyp_units else 1.0
    return edit_distance(ref_units, hyp_units) / len(ref_units)

