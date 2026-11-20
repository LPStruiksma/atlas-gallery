#!/usr/bin/env python3
"""Generate and check NLS-1 Gemma-tokenizer golden vectors."""

from __future__ import annotations

import argparse
import hashlib
import importlib
import json
from pathlib import Path
import sys
from typing import Any

EXPECTED_SHA256 = "6969e64047744a44bb3abfb5c50f8de0f7ed8b571d5444426ef931f651d1a0ef"
LENGTH = 64
HERE = Path(__file__).resolve().parent
DEFAULT_GOLDENS = HERE / "data" / "nls1_tokenizer_goldens.json"
CASES = (
    ("en_ascii_lower", "A Red Bicycle Beside The Canal"),
    ("nl_ascii_lower", "Een Rode Fiets Naast De Gracht"),
    ("en_unicode", "CAFÉ, naïve coöperation — déjà vu 👋"),
    ("nl_unicode", "IJsvogel bij de gracht: één foto, ’s avonds 🌷"),
    ("empty", ""),
    ("truncate_sticky_eos", " ".join(f"token{i:03d}" for i in range(160))),
)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def sentencepiece_processor(model: Path) -> Any:
    module = importlib.import_module("sentencepiece")
    return module.SentencePieceProcessor(model_file=str(model))


def encode(processor: Any, text: str) -> tuple[list[int], dict[str, Any]]:
    normalized = text.lower()
    unpadded = list(processor.encode(normalized, out_type=int, add_bos=False, add_eos=False))
    eos_id, pad_id = processor.eos_id(), processor.pad_id()
    if eos_id < 0 or pad_id < 0:
        raise ValueError(f"tokenizer must define EOS and PAD IDs, got eos={eos_id}, pad={pad_id}")
    truncated = len(unpadded) + 1 > LENGTH
    token_ids = unpadded[: LENGTH - 1] + [eos_id]
    content_length = len(token_ids)
    token_ids.extend([pad_id] * (LENGTH - content_length))
    return token_ids, {
        "normalized_text": normalized,
        "content_length_including_eos": content_length,
        "truncated": truncated,
        "eos_is_sticky": token_ids[content_length - 1] == eos_id,
        "right_padding_only": all(value == pad_id for value in token_ids[content_length:]),
    }


def generate(model: Path) -> dict[str, Any]:
    actual_hash = sha256(model)
    if actual_hash != EXPECTED_SHA256:
        raise ValueError(f"tokenizer SHA-256 mismatch: expected {EXPECTED_SHA256}, got {actual_hash}")
    processor = sentencepiece_processor(model)
    cases = []
    for name, text in CASES:
        token_ids, semantics = encode(processor, text)
        cases.append({"name": name, "text": text, "token_ids": token_ids, **semantics})
    return {
        "schema_version": 1,
        "status": "verified",
        "contract": 'lower | tok(length=64, model="gemma", bos="no", eos="sticky")',
        "tokenizer": {
            "url": "https://storage.googleapis.com/big_vision/gemma_tokenizer.model",
            "sha256": actual_hash,
            "sentencepiece_version": importlib.import_module("sentencepiece").__version__,
            "bos_id": processor.bos_id(), "eos_id": processor.eos_id(), "pad_id": processor.pad_id(),
        },
        "cases": cases,
    }


def validate(document: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    if document.get("status") != "verified":
        return ["goldens are BLOCKED, not verified"]
    tokenizer = document.get("tokenizer", {})
    if tokenizer.get("sha256") != EXPECTED_SHA256:
        errors.append("goldens name the wrong tokenizer SHA-256")
    eos_id, pad_id = tokenizer.get("eos_id"), tokenizer.get("pad_id")
    if {case.get("name") for case in document.get("cases", [])} != {name for name, _ in CASES}:
        errors.append("golden case set is incomplete or unexpected")
    for case in document.get("cases", []):
        ids = case.get("token_ids", [])
        length = case.get("content_length_including_eos", 0)
        if len(ids) != LENGTH:
            errors.append(f"{case.get('name')}: expected {LENGTH} IDs")
        elif not (1 <= length <= LENGTH):
            errors.append(f"{case.get('name')}: invalid content length")
        else:
            if ids[length - 1] != eos_id:
                errors.append(f"{case.get('name')}: EOS is not sticky")
            if any(value != pad_id for value in ids[length:]):
                errors.append(f"{case.get('name')}: padding is not exclusively on the right")
        if case.get("normalized_text") != case.get("text", "").lower():
            errors.append(f"{case.get('name')}: lowercase normalization mismatch")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    action = parser.add_mutually_exclusive_group(required=True)
    action.add_argument("--generate", action="store_true", help="write verified vectors")
    action.add_argument("--check", action="store_true", help="validate committed vectors")
    parser.add_argument("--model", type=Path, help="external gemma_tokenizer.model")
    parser.add_argument("--output", type=Path, default=DEFAULT_GOLDENS)
    args = parser.parse_args()
    if args.generate:
        if args.model is None:
            parser.error("--generate requires --model")
        args.output.write_text(json.dumps(generate(args.model), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"wrote {args.output}")
        return 0
    document = json.loads(args.output.read_text(encoding="utf-8"))
    errors = validate(document)
    if args.model is not None and not errors and generate(args.model) != document:
        errors.append("committed vectors differ from regeneration")
    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1
    print(f"verified {len(document['cases'])} cases in {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
