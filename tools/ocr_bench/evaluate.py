#!/usr/bin/env python3
"""Score OCR JSONL predictions against a corpus manifest, without dependencies."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from .metrics import edit_distance, normalize


def rows(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()
            if line.strip()]


def evaluate(manifest: Path, predictions: Path) -> dict:
    expected = {row["id"]: row for row in rows(manifest)}
    actual = {row["id"]: row for row in rows(predictions)}
    missing = sorted(set(expected) - set(actual))
    extra = sorted(set(actual) - set(expected))
    if missing or extra:
        raise ValueError(f"ID mismatch: missing={missing}, extra={extra}")

    chars = words = char_edits = word_edits = false_text = false_skips = 0
    by_kind: dict[str, dict[str, int]] = {}
    for key, truth in expected.items():
        ref, hyp = normalize(truth["text"]), normalize(actual[key].get("text", ""))
        ref_chars, hyp_chars = list(ref), list(hyp)
        ref_words, hyp_words = ref.split(), hyp.split()
        chars += len(ref_chars); words += len(ref_words)
        char_edits += edit_distance(ref_chars, hyp_chars)
        word_edits += edit_distance(ref_words, hyp_words)
        kind = truth["kind"]
        bucket = by_kind.setdefault(kind, {"samples": 0, "false_text": 0, "false_skips": 0})
        bucket["samples"] += 1
        if not ref and hyp:
            false_text += 1; bucket["false_text"] += 1
        if ref and not hyp:
            false_skips += 1; bucket["false_skips"] += 1
    return {"samples": len(expected), "cer": char_edits / chars if chars else None,
            "wer": word_edits / words if words else None, "false_text": false_text,
            "false_skips": false_skips, "by_kind": by_kind}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("manifest", type=Path)
    parser.add_argument("predictions", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    result = json.dumps(evaluate(args.manifest, args.predictions), indent=2, sort_keys=True) + "\n"
    if args.output: args.output.write_text(result, encoding="utf-8")
    else: print(result, end="")


if __name__ == "__main__":
    main()

