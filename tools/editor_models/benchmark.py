#!/usr/bin/env python3
"""Evidence-first utilities for EDIT-11. No downloads and no inferred measurements."""

from __future__ import annotations

import argparse
import hashlib
import json
import platform
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
CATALOGUE = ROOT / "candidates.json"
CAPABILITIES = {
    "upscale_x2_x4", "face_restoration", "monocular_depth", "shadow_removal",
    "reflection_removal", "tap_subject_segmentation",
}


def load_catalogue(path: Path = CATALOGUE) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def validate(data: dict) -> list[str]:
    errors: list[str] = []
    rows = data.get("candidates", [])
    ids = [row.get("id") for row in rows]
    if data.get("schema_version") != 1:
        errors.append("schema_version must be 1")
    if len(ids) != len(set(ids)):
        errors.append("candidate ids must be unique")
    for capability in sorted(CAPABILITIES):
        count = sum(row.get("capability") == capability for row in rows)
        if count not in (2, 3):
            errors.append(f"{capability}: expected 2-3 candidates, found {count}")
    for row in rows:
        for key in ("id", "name", "capability", "source_url", "license", "artifact_url"):
            if not row.get(key):
                errors.append(f"{row.get('id', '<unknown>')}: missing {key}")
        if row.get("capability") not in CAPABILITIES:
            errors.append(f"{row.get('id')}: unknown capability")
        digest = row.get("artifact_sha256")
        if digest is not None and (len(digest) != 64 or any(c not in "0123456789abcdef" for c in digest)):
            errors.append(f"{row.get('id')}: artifact_sha256 is not lowercase SHA-256")
        if row.get("status") != "not measured":
            errors.append(f"{row.get('id')}: unchecked status must remain 'not measured'")
    return errors


def candidate(data: dict, candidate_id: str) -> dict:
    try:
        return next(row for row in data["candidates"] if row["id"] == candidate_id)
    except StopIteration as exc:
        raise SystemExit(f"unknown candidate: {candidate_id}") from exc


def sha256_file(path: Path) -> tuple[int, str]:
    digest = hashlib.sha256()
    size = 0
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            size += len(chunk)
            digest.update(chunk)
    return size, digest.hexdigest()


def tile_plan(width: int, height: int, tile: int, overlap: int) -> list[tuple[int, int, int, int]]:
    if min(width, height, tile) <= 0 or overlap < 0 or overlap >= tile:
        raise ValueError("positive dimensions/tile and 0 <= overlap < tile required")
    step = tile - overlap
    xs = list(range(0, max(1, width - tile + 1), step))
    ys = list(range(0, max(1, height - tile + 1), step))
    if not xs or xs[-1] != max(0, width - tile):
        xs.append(max(0, width - tile))
    if not ys or ys[-1] != max(0, height - tile):
        ys.append(max(0, height - tile))
    return [(x, y, min(width, x + tile), min(height, y + tile)) for y in ys for x in xs]


def write_fixture(path: Path, width: int = 4000, height: int = 3000) -> None:
    """Write deterministic synthetic PPM scanlines without requiring Pillow/numpy."""
    with path.open("wb") as out:
        out.write(f"P6\n{width} {height}\n255\n".encode("ascii"))
        for y in range(height):
            row = bytearray()
            for x in range(width):
                row.extend(((x * 17 + y * 3) & 255, (x * 5 + y * 11) & 255, (x ^ y) & 255))
            out.write(row)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("validate")
    sub.add_parser("report")
    artifact = sub.add_parser("artifact")
    artifact.add_argument("--candidate", required=True); artifact.add_argument("--model", type=Path, required=True)
    probe = sub.add_parser("probe")
    probe.add_argument("--candidate", required=True); probe.add_argument("--model", type=Path, required=True)
    fixture = sub.add_parser("fixture")
    fixture.add_argument("--output", type=Path, required=True)
    tiles = sub.add_parser("tiles")
    for name, default in (("width", 4000), ("height", 3000), ("tile", 512), ("overlap", 32)):
        tiles.add_argument(f"--{name}", type=int, default=default)
    args = parser.parse_args(argv)
    data = load_catalogue()
    errors = validate(data)
    if errors:
        print("\n".join(errors), file=sys.stderr); return 2
    if args.command == "validate":
        print(f"valid: {len(data['candidates'])} candidates across {len(CAPABILITIES)} capabilities"); return 0
    if args.command == "report":
        for row in data["candidates"]:
            print(f"{row['capability']}\t{row['id']}\t{row['status']}")
        return 0
    if args.command == "tiles":
        plan = tile_plan(args.width, args.height, args.tile, args.overlap)
        print(json.dumps({"canvas":[args.width,args.height],"tile":args.tile,"overlap":args.overlap,"count":len(plan),"tiles":plan}))
        return 0
    if args.command == "fixture":
        write_fixture(args.output); print(f"wrote synthetic 4000x3000 PPM: {args.output}"); return 0
    row = candidate(data, args.candidate)
    size, digest = sha256_file(args.model)
    evidence = {"candidate":row["id"], "path":str(args.model), "bytes":size, "sha256":digest}
    if args.command == "artifact":
        print(json.dumps(evidence, indent=2)); return 0
    try:
        import onnxruntime as ort
    except ImportError:
        print("blocked: install onnxruntime==1.23.2 outside protected dependency files", file=sys.stderr); return 3
    evidence.update({"python":sys.version.split()[0], "host":platform.platform(), "ort_version":ort.__version__, "available_providers":ort.get_available_providers()})
    if ort.__version__ != "1.23.2":
        print(json.dumps(evidence, indent=2)); print("blocked: exact onnxruntime 1.23.2 required", file=sys.stderr); return 4
    session = ort.InferenceSession(str(args.model), providers=["CPUExecutionProvider"])
    evidence["cpu_session"] = True
    evidence["inputs"] = [{"name":item.name,"shape":item.shape,"type":item.type} for item in session.get_inputs()]
    evidence["outputs"] = [{"name":item.name,"shape":item.shape,"type":item.type} for item in session.get_outputs()]
    evidence["xnnpack_available"] = "XnnpackExecutionProvider" in ort.get_available_providers()
    print(json.dumps(evidence, indent=2))
    if not evidence["xnnpack_available"]:
        print("blocked: this wheel does not expose XnnpackExecutionProvider", file=sys.stderr); return 5
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
