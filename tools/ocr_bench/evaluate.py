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


def _index_rows(path: Path) -> dict[str, dict]:
    indexed: dict[str, dict] = {}
    for number, row in enumerate(rows(path), 1):
        if not isinstance(row, dict) or not isinstance(row.get("id"), str) or not row["id"].strip():
            raise ValueError(f"{path}: row {number} needs a nonempty string id")
        key = row["id"]
        if key in indexed:
            raise ValueError(f"{path}: duplicate id {key!r} at row {number}")
        indexed[key] = row
    return indexed


def _empty_bucket() -> dict[str, int]:
    return {"samples": 0, "chars": 0, "words": 0, "char_edits": 0,
            "word_edits": 0, "text_samples": 0, "no_text_samples": 0,
            "false_text": 0, "false_skips": 0}


def _add(bucket: dict[str, int], *, chars: int, words: int, char_edits: int,
         word_edits: int, is_text: bool, has_prediction: bool) -> None:
    bucket["samples"] += 1
    bucket["chars"] += chars
    bucket["words"] += words
    bucket["char_edits"] += char_edits
    bucket["word_edits"] += word_edits
    bucket["text_samples" if is_text else "no_text_samples"] += 1
    if not is_text and has_prediction:
        bucket["false_text"] += 1
    if is_text and not has_prediction:
        bucket["false_skips"] += 1


def _summary(bucket: dict[str, int]) -> dict:
    return {
        "samples": bucket["samples"],
        "chars": bucket["chars"], "words": bucket["words"],
        "char_edits": bucket["char_edits"], "word_edits": bucket["word_edits"],
        "cer": bucket["char_edits"] / bucket["chars"] if bucket["chars"] else None,
        "wer": bucket["word_edits"] / bucket["words"] if bucket["words"] else None,
        "text_samples": bucket["text_samples"],
        "no_text_samples": bucket["no_text_samples"],
        "false_text": bucket["false_text"],
        "false_skips": bucket["false_skips"],
        "false_text_rate": (bucket["false_text"] / bucket["no_text_samples"]
                            if bucket["no_text_samples"] else None),
        "false_skip_rate": (bucket["false_skips"] / bucket["text_samples"]
                            if bucket["text_samples"] else None),
    }


def evaluate(manifest: Path, predictions: Path, split: str | None = None) -> dict:
    expected = _index_rows(manifest)
    actual = _index_rows(predictions)
    # Full-manifest ID integrity is checked BEFORE any split is selected, so a missing or extra
    # row in the excluded split cannot pass silently.
    missing = sorted(set(expected) - set(actual))
    extra = sorted(set(actual) - set(expected))
    if missing or extra:
        raise ValueError(f"ID mismatch: missing={missing}, extra={extra}")
    if split is not None:
        expected = {key: row for key, row in expected.items() if row.get("split") == split}
        if not expected:
            raise ValueError(f"no manifest rows with split {split!r}")
        actual = {key: actual[key] for key in expected}

    total = _empty_bucket()
    by_kind: dict[str, dict[str, int]] = {}
    by_language: dict[str, dict[str, int]] = {}
    by_language_kind: dict[str, dict[str, dict[str, int]]] = {}
    for key, truth in expected.items():
        ref, hyp = normalize(truth["text"]), normalize(actual[key].get("text", ""))
        ref_chars, hyp_chars = list(ref), list(hyp)
        ref_words, hyp_words = ref.split(), hyp.split()
        kind = truth["kind"]
        # Negative fixtures may omit language. Never silently assign them EN or NL.
        language = truth.get("language") or "unspecified"
        counters = (
            total,
            by_kind.setdefault(kind, _empty_bucket()),
            by_language.setdefault(language, _empty_bucket()),
            by_language_kind.setdefault(language, {}).setdefault(kind, _empty_bucket()),
        )
        # CER/WER are defined over text-positive images only. Text hallucinated on a no-text
        # image is reported as false_text, never as insertions inside CER (OCR-0A #873: the
        # merged scorer counted them, contradicting its own test and the README denominators).
        is_text = bool(ref)
        measured = {"chars": len(ref_chars), "words": len(ref_words),
                    "char_edits": edit_distance(ref_chars, hyp_chars) if is_text else 0,
                    "word_edits": edit_distance(ref_words, hyp_words) if is_text else 0,
                    "is_text": is_text, "has_prediction": bool(hyp)}
        for bucket in counters:
            _add(bucket, **measured)

    return {
        **_summary(total),
        "by_kind": {kind: _summary(bucket) for kind, bucket in by_kind.items()},
        "by_language": {language: _summary(bucket) for language, bucket in by_language.items()},
        "by_language_kind": {
            language: {kind: _summary(bucket) for kind, bucket in kinds.items()}
            for language, kinds in by_language_kind.items()
        },
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("manifest", type=Path)
    parser.add_argument("predictions", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--split", help="score only manifest rows with this split (dev or test)")
    args = parser.parse_args()
    result = json.dumps(evaluate(args.manifest, args.predictions, args.split), indent=2, sort_keys=True) + "\n"
    if args.output: args.output.write_text(result, encoding="utf-8")
    else: print(result, end="")


if __name__ == "__main__":
    main()
