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


def ascii_lower(text: str) -> str:
    """Match NLS-0 text_tower.ascii_lower / tf.strings.lower (ASCII only)."""
    return "".join(chr(ord(char) + 32) if "A" <= char <= "Z" else char for char in text)


def encode(processor: Any, text: str) -> tuple[list[int], dict[str, Any]]:
    normalized = ascii_lower(text)
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
        if case.get("normalized_text") != ascii_lower(case.get("text", "")):
            errors.append(f"{case.get('name')}: lowercase normalization mismatch")
    return errors


def self_test() -> bool:
    """Offline contract regression tests; do not require tokenizer bytes or numpy."""
    from copy import deepcopy
    import unittest

    class StubProcessor:
        def __init__(self) -> None:
            self.seen: list[str] = []

        def eos_id(self) -> int:
            return 1

        def pad_id(self) -> int:
            return 0

        def encode(self, value: str, *, out_type: type, add_bos: bool, add_eos: bool) -> list[int]:
            if out_type is not int or add_bos or add_eos:
                raise ValueError("generator must use integer IDs without BOS or automatic EOS")
            self.seen.append(value)
            if value.startswith("token000 "):
                return list(range(2, LENGTH + 8))
            return [] if value == "" else [4, 5]

    class ContractTests(unittest.TestCase):
        def test_ascii_only_normalization(self) -> None:
            self.assertEqual(ascii_lower("Een KERK, ÉÉN Café"), "een kerk, ÉÉn café")
            self.assertEqual(ascii_lower("CAFÉ, IJsvogel 👋"), "cafÉ, ijsvogel 👋")

        def test_short_input_eos_and_right_padding(self) -> None:
            processor = StubProcessor()
            ids, semantics = encode(processor, "A Red Bicycle")
            self.assertEqual(processor.seen, ["a red bicycle"])
            self.assertEqual(ids[:4], [4, 5, 1, 0])
            self.assertEqual(len(ids), LENGTH)
            self.assertEqual(ids[3:], [0] * (LENGTH - 3))
            self.assertEqual(semantics["content_length_including_eos"], 3)
            self.assertFalse(semantics["truncated"])
            self.assertTrue(semantics["eos_is_sticky"])
            self.assertTrue(semantics["right_padding_only"])

        def test_unicode_parity_and_empty_query(self) -> None:
            processor = StubProcessor()
            unicode_ids, unicode_meta = encode(processor, "CAFÉ, ÉÉN Foto 👋")
            self.assertEqual(processor.seen, ["cafÉ, ÉÉn foto 👋"])
            self.assertEqual(unicode_ids[:3], [4, 5, 1])
            self.assertEqual(unicode_meta["normalized_text"], "cafÉ, ÉÉn foto 👋")
            ids, meta = encode(processor, "")
            self.assertEqual(ids, [1] + [0] * (LENGTH - 1))
            self.assertEqual(meta["content_length_including_eos"], 1)

        def test_long_query_keeps_eos_at_position_64(self) -> None:
            processor = StubProcessor()
            long_text = dict(CASES)["truncate_sticky_eos"]
            ids, meta = encode(processor, long_text)
            self.assertEqual(ids, list(range(2, LENGTH + 1)) + [1])
            self.assertEqual(len(ids), LENGTH)
            self.assertTrue(meta["truncated"])
            self.assertTrue(meta["eos_is_sticky"])
            self.assertEqual(meta["content_length_including_eos"], LENGTH)

        def test_validation_accepts_contract_and_rejects_corruption(self) -> None:
            processor = StubProcessor()
            cases = []
            for name, text in CASES:
                ids, meta = encode(processor, text)
                cases.append({"name": name, "text": text, "token_ids": ids, **meta})
            document = {
                "schema_version": 1,
                "status": "verified",
                "tokenizer": {"sha256": EXPECTED_SHA256, "eos_id": 1, "pad_id": 0},
                "cases": cases,
            }
            self.assertEqual(validate(document), [])
            self.assertIn("BLOCKED", validate({"status": "BLOCKED"})[0])

            bad = deepcopy(document)
            bad["cases"][2]["normalized_text"] = bad["cases"][2]["text"].lower()
            self.assertIn("lowercase normalization mismatch", " ".join(validate(bad)))
            bad = deepcopy(document)
            bad["cases"][0]["token_ids"][2] = 8
            self.assertIn("EOS is not sticky", " ".join(validate(bad)))
            bad = deepcopy(document)
            bad["cases"][0]["token_ids"][-1] = 8
            self.assertIn("padding is not exclusively on the right", " ".join(validate(bad)))
            bad = deepcopy(document)
            bad["tokenizer"]["sha256"] = "wrong"
            self.assertIn("wrong tokenizer SHA-256", " ".join(validate(bad)))
            bad = deepcopy(document)
            bad["cases"].pop()
            self.assertIn("case set is incomplete", " ".join(validate(bad)))

    suite = unittest.defaultTestLoader.loadTestsFromTestCase(ContractTests)
    return unittest.TextTestRunner(verbosity=2).run(suite).wasSuccessful()


def main() -> int:
    parser = argparse.ArgumentParser()
    action = parser.add_mutually_exclusive_group(required=True)
    action.add_argument("--generate", action="store_true", help="write verified vectors")
    action.add_argument("--check", action="store_true", help="validate committed vectors")
    action.add_argument("--self-test", action="store_true", help="run offline mock-backed contract regression tests")
    parser.add_argument("--model", type=Path, help="external gemma_tokenizer.model")
    parser.add_argument("--output", type=Path, default=DEFAULT_GOLDENS)
    args = parser.parse_args()
    if args.self_test:
        return 0 if self_test() else 1
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
