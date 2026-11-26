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
UTC = re.compile(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z")
NOTES = ("project_license_note", "checkpoint_grant_note", "training_data_note", "reason")
LINKS = ("project_license_evidence", "checkpoint_grant_evidence", "training_data_evidence")
BYTE_FIELDS = ("retrieved_url", "retrieved_at_utc", "bytes", "sha256")
FIELDS = {
    "id", "project_url", "project_revision", "checkpoint_revision",
    "original_url", "original_url_kind", "status", *NOTES, *LINKS, *BYTE_FIELDS,
}


def nonblank(value):
    return isinstance(value, str) and bool(value.strip())


def https_url(value):
    if not nonblank(value) or any(c.isspace() or ord(c) < 32 for c in value) or "\\" in value:
        return False
    try:
        parsed = urlsplit(value)
        # Keep credentials and transient/signed query strings out of the inventory.
        return (parsed.scheme == "https" and bool(parsed.hostname)
                and parsed.username is None and parsed.password is None
                and not parsed.query and parsed.port in (None, 443))
    except ValueError:
        return False


def digest(value, pattern):
    return isinstance(value, str) and bool(pattern.fullmatch(value)) and any(c != "0" for c in value)


def validate(data, catalogue, require_complete=False):
    if not isinstance(data, dict):
        return ["inventory must be an object"]
    rows = data.get("candidates")
    if not isinstance(rows, list) or not all(isinstance(row, dict) for row in rows):
        return ["inventory candidates must be a list of objects"]
    errors = []
    expected = {r["id"] for r in catalogue["candidates"]}
    ids = [r.get("id") for r in rows]
    if type(data.get("schema_version")) is not int or data["schema_version"] != 1:
        errors.append("unsupported schema")
    if not https_url(data.get("source_pin_receipt")):
        errors.append("invalid source pin receipt")
    if (not all(isinstance(key, str) for key in ids)
            or len(ids) != len(expected) or set(ids) != expected):
        errors.append("inventory must contain exactly one row per catalogue candidate")
    for row in rows:
        key = row.get("id")

        def fail(msg):
            errors.append(f"{key}: {msg}")

        if missing := FIELDS - row.keys():
            fail(f"missing fields: {', '.join(sorted(missing))}")
        for field in ("project_url", "original_url"):
            if not https_url(row.get(field)):
                fail(f"invalid {field}")
        for field in NOTES:
            if not nonblank(row.get(field)):
                fail(f"missing {field}")
        pin = row.get("project_revision")
        if pin is not None and not digest(pin, HEX40):
            fail("invalid project commit pin")
        revision = row.get("checkpoint_revision")
        if revision is not None and (not nonblank(revision) or revision.strip() == pin):
            fail("checkpoint revision must identify the artifact separately from the project commit")
        for field in LINKS:
            if row.get(field) is not None and not https_url(row[field]):
                fail(f"invalid {field}")
        if row.get("original_url_kind") not in ("discovery", "checkpoint"):
            fail("unknown original URL kind")
        status = row.get("status")
        if status not in ("missing", "blocked", "recorded"):
            fail("unknown status")
        if status != "recorded":
            if any(row.get(field) is not None for field in BYTE_FIELDS):
                fail("unrecorded candidate must not claim byte evidence")
            if require_complete and status == "missing":
                fail("unresolved candidate")
            if status == "blocked" and not https_url(row.get("checkpoint_grant_evidence")):
                fail("blocked decision lacks checkpoint-specific evidence")
        else:
            if not pin or not revision:
                fail("missing project or checkpoint revision")
            if row.get("original_url_kind") != "checkpoint":
                fail("original URL must identify a checkpoint, not a discovery page")
            for field in (*LINKS, "retrieved_url"):
                if not https_url(row.get(field)):
                    fail(f"missing or invalid evidence: {field}")
            if type(row.get("bytes")) is not int or row["bytes"] <= 0:
                fail("invalid actual byte size")
            if not digest(row.get("sha256"), HEX64):
                fail("invalid SHA-256")
            try:
                stamp = row.get("retrieved_at_utc")
                if not isinstance(stamp, str) or not UTC.fullmatch(stamp):
                    raise ValueError()
                datetime.datetime.fromisoformat(stamp[:-1] + "+00:00")
            except ValueError:
                fail("retrieval time must be ISO 8601 UTC")
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--require-complete", action="store_true")
    args = parser.parse_args()
    data = json.loads((ROOT / "manifest.json").read_text(encoding="utf-8"))
    catalogue = json.loads((ROOT.parent / "candidates.json").read_text(encoding="utf-8"))
    errors = validate(data, catalogue, args.require_complete)
    if isinstance(data, dict) and isinstance(data.get("candidates"), list):
        for row in data["candidates"]:
            if isinstance(row, dict):
                print(f"{row.get('id')}\t{row.get('status')}\t{row.get('reason')}")
    for error in errors:
        print(error)
    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
