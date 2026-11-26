#!/usr/bin/env python3
"""Check evidence inventory; --require-complete rejects unresolved evidence."""
import argparse
import datetime
import json
import re
from pathlib import Path
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parent
HEX40 = re.compile(r"[0-9a-f]{40}")
HEX64 = re.compile(r"[0-9a-f]{64}")


def https_url(value):
    if not isinstance(value, str):
        return False
    parsed = urlsplit(value)
    return parsed.scheme == "https" and bool(parsed.hostname) and not parsed.username and not parsed.password


def validate(data, catalogue, require_complete=False):
    errors = []
    rows = data.get("candidates", [])
    expected = {r["id"] for r in catalogue["candidates"]}
    ids = [r.get("id") for r in rows]
    if data.get("schema_version") != 1:
        errors.append("unsupported schema")
    if len(ids) != len(expected) or set(ids) != expected:
        errors.append("inventory must contain exactly one row per catalogue candidate")
    for row in rows:
        key = row.get("id")
        def fail(msg):
            errors.append(f"{key}: {msg}")
        for field in ("project_url", "original_url"):
            if not https_url(row.get(field)):
                fail(f"invalid {field}")
        for field in ("project_license_note", "checkpoint_grant_note", "training_data_note", "reason"):
            if not isinstance(row.get(field), str) or not row[field].strip():
                fail(f"missing {field}")
        pin = row.get("project_revision")
        if pin is not None and not HEX40.fullmatch(str(pin)):
            fail("invalid project commit pin")
        for field in ("project_license_evidence", "checkpoint_grant_evidence", "training_data_evidence"):
            if row.get(field) is not None and not https_url(row[field]):
                fail(f"invalid {field}")
        status = row.get("status")
        if status not in ("missing", "blocked", "recorded"):
            fail("unknown status")
        evidence = ("retrieved_url", "retrieved_at_utc", "bytes", "sha256")
        if status != "recorded":
            if any(row.get(field) is not None for field in evidence):
                fail("unrecorded candidate must not claim byte evidence")
            if require_complete and status == "missing":
                fail("unresolved candidate")
            if require_complete and status == "blocked" and not row.get("checkpoint_grant_evidence"):
                fail("blocked decision lacks checkpoint-specific evidence")
        else:
            if not pin or not row.get("checkpoint_revision"):
                fail("missing project or checkpoint revision")
            if row.get("original_url_kind") != "checkpoint":
                fail("original URL must identify a checkpoint, not a discovery page")
            for field in ("project_license_evidence", "checkpoint_grant_evidence", "training_data_evidence", "retrieved_url"):
                if not https_url(row.get(field)):
                    fail(f"missing evidence: {field}")
            if type(row.get("bytes")) is not int or row["bytes"] <= 0:
                fail("invalid actual byte size")
            if not HEX64.fullmatch(str(row.get("sha256"))):
                fail("invalid SHA-256")
            try:
                stamp = row["retrieved_at_utc"]
                if not stamp.endswith("Z"):
                    raise ValueError()
                datetime.datetime.fromisoformat(stamp[:-1] + "+00:00")
            except (KeyError, TypeError, AttributeError, ValueError):
                fail("retrieval time must be ISO 8601 UTC")
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--require-complete", action="store_true")
    args = parser.parse_args()
    data = json.loads((ROOT / "manifest.json").read_text(encoding="utf-8"))
    catalogue = json.loads((ROOT.parent / "candidates.json").read_text(encoding="utf-8"))
    errors = validate(data, catalogue, args.require_complete)
    for row in data["candidates"]:
        print(f"{row['id']}\t{row['status']}\t{row['reason']}")
    for error in errors:
        print(error)
    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
