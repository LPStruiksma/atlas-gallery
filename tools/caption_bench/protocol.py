#!/usr/bin/env python3
"""Offline caption benchmark orchestration; subprocess timings are cold launches only."""

import argparse
import hashlib
import json
import math
import statistics
import subprocess
import sys
import time
from pathlib import Path


def sha(path):
    h = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1048576), b""):
            h.update(chunk)
    return h.hexdigest()


def manifest(path):
    rows = []
    seen = set()
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        row = json.loads(line)
        required = {"id", "image", "sha256", "rights_id", "rights_url"}
        if not isinstance(row, dict) or required - row.keys():
            raise ValueError(f"line {number}: missing manifest fields")
        if not isinstance(row["id"], str) or not row["id"].strip() or row["id"] in seen:
            raise ValueError(f"line {number}: invalid or duplicate id")
        seen.add(row["id"])
        if not isinstance(row["image"], str):
            raise ValueError(f"line {number}: image path must be string")
        image = Path(row["image"])
        if not image.is_absolute() or image.is_symlink() or not image.is_file():
            raise ValueError(f"line {number}: image must be absolute regular non-symlink")
        if sha(image) != row["sha256"]:
            raise ValueError(f"line {number}: SHA-256 mismatch")
        if not str(row["rights_id"]).strip() or not str(row["rights_url"]).strip():
            raise ValueError(f"line {number}: rights metadata empty")
        rows.append(row)
    if not rows:
        raise ValueError("manifest is empty")
    return rows


def fixtures(out):
    out.mkdir(parents=True, exist_ok=False)
    rows = []
    for version in range(2):
        image = (out / f"geometry-{version}.ppm").resolve()
        pixels = bytearray()
        for y in range(32):
            for x in range(32):
                pixels.extend(
                    (30, 90 + 20 * version, 210)
                    if 6 + version <= x < 26 and 7 <= y < 25 - version
                    else (245, 245, 245)
                )
        image.write_bytes(b"P6\n32 32\n255\n" + pixels)
        rows.append({
            "id": f"synthetic-{version}",
            "image": str(image),
            "sha256": sha(image),
            "rights_id": "repository-generated-synthetic-fixture",
            "rights_url": "docs/visual-search/CAP_0_FEASIBILITY.md#dataset-licensing",
        })
    target = out / "manifest.jsonl"
    target.write_text(
        "".join(json.dumps(row, sort_keys=True) + "\n" for row in rows),
        encoding="utf-8",
    )
    print(target)


def bench(args):
    rows = manifest(args.manifest)
    command = args.command[1:] if args.command and args.command[0] == "--" else args.command
    if not command:
        raise ValueError("adapter command required after --")

    # A fresh adapter process is created for *each* call. Discarding trial launches
    # cannot warm model state in a subsequent process; these are cold-start timings.
    valid_ms = []
    attempted = 0
    failed = 0
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("x", encoding="utf-8") as sink:
        for iteration in range(args.discard_launches + args.runs):
            for row in rows:
                started = time.perf_counter_ns()
                completed = None
                error = None
                try:
                    completed = subprocess.run(
                        command + ["--image", row["image"], "--prompt", args.prompt],
                        capture_output=True,
                        text=True,
                        timeout=args.timeout,
                        check=False,
                    )
                except subprocess.TimeoutExpired:
                    error = f"adapter timed out after {args.timeout} seconds"
                elapsed_ns = time.perf_counter_ns() - started
                if iteration < args.discard_launches:
                    continue

                attempted += 1
                caption = None
                returncode = completed.returncode if completed is not None else None
                if completed is not None:
                    if completed.returncode != 0:
                        error = f"adapter exit {completed.returncode}: {completed.stderr.strip()}"
                    else:
                        try:
                            payload = json.loads(completed.stdout)
                            if not isinstance(payload, dict):
                                raise ValueError("expected JSON object")
                            candidate = payload.get("caption")
                            if not isinstance(candidate, str) or not candidate.strip():
                                raise ValueError("caption must be non-empty string")
                            caption = candidate
                        except (json.JSONDecodeError, ValueError) as exc:
                            error = f"invalid adapter output: {exc}"
                success = error is None
                if success:
                    valid_ms.append(elapsed_ns / 1e6)
                else:
                    failed += 1
                sink.write(json.dumps({
                    "id": row["id"],
                    "iteration": iteration - args.discard_launches,
                    "launch_mode": "per-request-cold-process",
                    "elapsed_ns": elapsed_ns,
                    "returncode": returncode,
                    "status": "success" if success else "failed",
                    "caption": caption,
                    "error": error,
                    "memory_status": "NOT_MEASURED",
                }, ensure_ascii=False, sort_keys=True) + "\n")

    sorted_ms = sorted(valid_ms)
    summary = {
        "launch_mode": "per-request-cold-process",
        "warm_inference_measured": False,
        "memory_status": "NOT_MEASURED",
        "discarded_trial_launches": args.discard_launches * len(rows),
        "attempted": attempted,
        "success": len(valid_ms),
        "failed": failed,
        "p50_cold_ms": statistics.median(valid_ms) if valid_ms else None,
        "p95_cold_ms": sorted_ms[math.ceil(0.95 * len(sorted_ms)) - 1] if valid_ms else None,
        "output": str(args.output),
    }
    print(json.dumps(summary, sort_keys=True, allow_nan=False))
    if not valid_ms:
        raise ValueError("no valid captions; benchmark failed closed")


def cli(argv=None):
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="action", required=True)
    fixture_parser = sub.add_parser("make-fixtures")
    fixture_parser.add_argument("--out", type=Path, required=True)
    check_parser = sub.add_parser("check-manifest")
    check_parser.add_argument("manifest", type=Path)
    bench_parser = sub.add_parser("bench")
    bench_parser.add_argument("--discard-launches", type=int, default=0)
    bench_parser.add_argument("--runs", type=int, default=30)
    bench_parser.add_argument("--timeout", type=float, default=300)
    bench_parser.add_argument("--prompt", required=True)
    bench_parser.add_argument("--output", type=Path, required=True)
    bench_parser.add_argument("manifest", type=Path)
    bench_parser.add_argument("command", nargs=argparse.REMAINDER)
    capacity_parser = sub.add_parser("capacity")
    capacity_parser.add_argument("--images", type=int, default=150000)
    capacity_parser.add_argument("--images-per-hour", type=float, required=True)
    capacity_parser.add_argument("--retry-multiplier", type=float, default=1)
    capacity_parser.add_argument("--caption-bytes", type=int, required=True)
    capacity_parser.add_argument("--metadata-bytes", type=int, required=True)
    capacity_parser.add_argument("--model-bytes", type=int, required=True)
    return parser.parse_args(argv)


def main(argv=None):
    args = cli(argv)
    try:
        if args.action == "make-fixtures":
            fixtures(args.out)
        elif args.action == "check-manifest":
            print(json.dumps({"valid": len(manifest(args.manifest))}))
        elif args.action == "bench":
            if (
                args.discard_launches < 0
                or args.runs <= 0
                or not math.isfinite(args.timeout)
                or args.timeout <= 0
            ):
                raise ValueError("invalid run counts/timeout")
            bench(args)
        else:
            if (
                args.images < 0
                or not math.isfinite(args.images_per_hour)
                or args.images_per_hour <= 0
                or not math.isfinite(args.retry_multiplier)
                or args.retry_multiplier < 1
                or min(args.caption_bytes, args.metadata_bytes, args.model_bytes) < 0
            ):
                raise ValueError("invalid capacity input")
            print(json.dumps({
                "initial_hours": args.images * args.retry_multiplier / args.images_per_hour,
                "storage_bytes": args.model_bytes + args.images * (args.caption_bytes + args.metadata_bytes),
                "incremental_hours_per_image": args.retry_multiplier / args.images_per_hour,
            }, sort_keys=True, allow_nan=False))
    except (OSError, ValueError, json.JSONDecodeError, subprocess.SubprocessError, OverflowError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
