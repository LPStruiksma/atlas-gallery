"""Create a privacy-first, offline HTML review *queue*, never photo families.

Input is the JSON stdout from CLI pair, scan or find --verify.  A geometric
match is evidence for a human, never proof of the same camera exposure.
This module uses the standard library and never reads the original images.
"""
from __future__ import annotations

import argparse
import html
import json
import math
import os
from pathlib import Path
import stat

MAX_JSON_BYTES = 2_000_000
MAX_EDGES = 5_000
MAX_STRING = 8_192
SAFE_REASONS = frozenset((
    "Insufficient distinctive visual features",
    "Insufficient independent descriptor matches",
    "No coherent geometric transformation",
    "Weak, localized, or implausible geometric agreement",
    "Distributed geometric inliers with compatible image structure; review required",
    "Plausible shared photographic content; insufficient for strong candidate",
    "Photometric or geometric evidence below conservative review gate",
))
NUMERIC = ("inliers", "inlier_fraction", "rotation_degrees", "scale",
           "overlap_a", "overlap_b", "local_consistency", "gradient_correlation")


def _safe_text(value: object, field: str) -> str:
    if not isinstance(value, str) or not 0 < len(value) <= MAX_STRING or "\x00" in value:
        raise ValueError(f"Invalid {field}")
    return value


def _read_json(source: Path) -> object:
    if source.is_symlink():
        raise ValueError("Refusing symlinked evidence input")
    fd = os.open(source, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
    with os.fdopen(fd, "rb") as stream:
        before = os.fstat(stream.fileno())
        if not stat.S_ISREG(before.st_mode) or before.st_size > MAX_JSON_BYTES:
            raise ValueError("Evidence must be a bounded regular file")
        raw = stream.read(MAX_JSON_BYTES + 1)
        after = os.fstat(stream.fileno())
        if len(raw) > MAX_JSON_BYTES or len(raw) != before.st_size or (before.st_dev, before.st_ino, before.st_mtime_ns, before.st_size) != (after.st_dev, after.st_ino, after.st_mtime_ns, after.st_size):
            raise ValueError("Evidence changed while reading")
    def unique_pairs(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("Duplicate JSON property")
            result[key] = value
        return result
    try:
        return json.loads(raw.decode("utf-8"), object_pairs_hook=unique_pairs,
                          parse_constant=lambda _: (_ for _ in ()).throw(ValueError("Nonfinite JSON number")))
    except (UnicodeError, json.JSONDecodeError) as exc:
        raise ValueError("Invalid UTF-8 JSON evidence") from exc


def normalize(payload: object, *, limit: int = MAX_EDGES) -> tuple[list[dict], dict]:
    """Validate edges without inventing transitive families or confidence.

    Accept pair Evidence.json(), scan JSON, or verified-find JSON. Any
    malformed candidate rejects the entire report, rather than showing a
    plausible but incomplete subset of a potentially damaged review queue.
    """
    if type(limit) is not int or not 1 <= limit <= MAX_EDGES:
        raise ValueError("limit must be 1..5000")
    if not isinstance(payload, dict):
        raise ValueError("Expected JSON object")
    if "candidate_edges" in payload:
        items = payload["candidate_edges"]
    elif "image_a" in payload and "image_b" in payload:
        items = [payload]
    else:
        raise ValueError("Expected pair, scan, or find --verify evidence JSON")
    if not isinstance(items, list) or len(items) > limit:
        raise ValueError("Candidate edge count exceeds explicit bound")
    edges = []
    seen_pairs = set()
    source_tokens: dict[str, str] = {}
    for raw in items:
        if not isinstance(raw, dict):
            raise ValueError("Candidate entry must be an object")
        evidence = raw.get("evidence", raw)
        if not isinstance(evidence, dict):
            raise ValueError("Malformed geometric evidence")
        a = _safe_text(evidence.get("image_a"), "image_a")
        b = _safe_text(evidence.get("image_b"), "image_b")
        if a == b:
            raise ValueError("Self-match cannot be a review edge")
        pair_key = tuple(sorted((a, b)))
        if pair_key in seen_pairs:
            raise ValueError("Duplicate photographic pair; refuse ambiguous evidence")
        seen_pairs.add(pair_key)
        verdict = evidence.get("verdict")
        if verdict not in ("candidate", "review", "unknown"):
            raise ValueError("Unknown geometric verdict")
        reason = _safe_text(evidence.get("reason", "Not provided"), "reason")
        alg = _safe_text(evidence.get("algorithm", "unspecified"), "algorithm")
        # Freeform metadata may contain source paths even when paths are hidden.
        if alg not in ("sift", "orb", "akaze"):
            alg = "unrecognized algorithm (withheld)"
        if reason not in SAFE_REASONS:
            reason = "Unrecognized reason (withheld by privacy policy)"
        numbers = {}
        for field in NUMERIC:
            value = evidence.get(field)
            if value is None:
                continue
            if type(value) not in (int, float) or not math.isfinite(value):
                raise ValueError(f"Invalid numeric {field}")
            if field == "inliers" and (type(value) is not int or value < 0):
                raise ValueError("Invalid inlier count")
            if field in ("inlier_fraction", "overlap_a", "overlap_b", "local_consistency") and not 0 <= value <= 1:
                raise ValueError(f"Invalid range for {field}")
            numbers[field] = value
        for path in (a, b):
            if path not in source_tokens:
                source_tokens[path] = f"Photo {len(source_tokens) + 1:04d}"
        edges.append({"a": a, "b": b, "a_token": source_tokens[a],
                      "b_token": source_tokens[b], "verdict": verdict,
                      "reason": reason, "algorithm": alg, "numbers": numbers})
    edges.sort(key=lambda x: (0 if x["verdict"] == "candidate" else 1 if x["verdict"] == "review" else 2,
                              -x["numbers"].get("inliers", 0), x["a_token"], x["b_token"]))
    stats = {"edges": len(edges), "photos": len(source_tokens),
             "candidate": sum(e["verdict"] == "candidate" for e in edges),
             "review": sum(e["verdict"] == "review" for e in edges),
             "unknown": sum(e["verdict"] == "unknown" for e in edges)}
    return edges, stats


def render(payload: object, *, show_names: bool = False, limit: int = MAX_EDGES) -> str:
    """Return static, self-contained HTML with no JS, images or external URLs."""
    if type(show_names) is not bool:
        raise ValueError("show_names must be boolean")
    edges, stats = normalize(payload, limit=limit)
    esc = lambda s: html.escape(str(s), quote=True)
    content = []
    for idx, e in enumerate(edges, 1):
        left = e["a_token"] + (" — " + e["a"] if show_names else "")
        right = e["b_token"] + (" — " + e["b"] if show_names else "")
        measurements = ", ".join(f"{k.replace('_',' ')}: {v:.4g}" for k, v in e["numbers"].items()) or "No numeric evidence provided"
        content.append(f'<details class="entry"><summary><strong>{idx}. {esc(e["verdict"].upper())}</strong> &middot; {esc(e["a_token"])} ↔ {esc(e["b_token"])} &middot; {esc(e["numbers"].get("inliers", "?"))} inliers</summary>'
                       f'<p>{esc(left)}<br>{esc(right)}</p><p><b>Algorithm:</b> {esc(e["algorithm"])}<br><b>Reason:</b> {esc(e["reason"])}</p><p>{esc(measurements)}</p>'
                       '<p><b>Review question:</b> same original exposure, separate captures of the same scene, or uncertain? No automatic decision is recorded.</p></details>')
    privacy = "Source paths explicitly included — keep this HTML private." if show_names else "Paths withheld; ephemeral photo labels apply only within this report."
    return ("<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            "<meta name=\"referrer\" content=\"no-referrer\"><title>ATLAS Photo DNA — review queue</title>"
            "<style>body{font:16px system-ui,sans-serif;max-width:920px;margin:32px auto;padding:0 16px;background:#111826;color:#f1f5fa;line-height:1.5}"
            "h1{font-size:1.6rem}p{max-width:75ch}.muted{color:#b3c2d5}.entry{border:1px solid #40526a;border-radius:12px;margin:12px 0;padding:14px;background:#1b2638}"
            "summary{cursor:pointer;overflow-wrap:anywhere}summary:focus-visible{outline:3px solid #8ec9ff}details p{overflow-wrap:anywhere}strong{color:#a7e7fa}"
            "</style></head><body><h1>Photo DNA · Manual review queue</h1>"
            "<p><b>These are individual pair suggestions, NOT automatically formed photograph families.</b> "
            "A→B and B→C never implies A→C. The same scene is not the same exposure. "
            "Even a <b>candidate</b> is not verified duplication and authorizes no deletion, identity link or merge.</p>"
            f'<p class="muted">{stats["edges"]} edges · {stats["photos"]} source labels · {stats["candidate"]} candidates · {stats["review"]} review · {stats["unknown"]} unknown.</p>'
            f'<p class="muted">{esc(privacy)} Offline static report: no scripts, remote requests, thumbnails or source-image reads.</p>'
            + "\n".join(content) + "</body></html>\n")


def write_new(path: Path, report: str) -> None:
    """Explicit, no-clobber private publication; no file replacement or deletion."""
    if path.suffix.lower() != ".html" or not path.parent.is_dir() or path.parent.is_symlink():
        raise ValueError("Output requires an existing regular directory and .html suffix")
    if path.is_symlink():
        raise ValueError("Output symlink refused")
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0)
    fd = os.open(path, flags, 0o600)
    with os.fdopen(fd, "wb") as stream:
        stream.write(report.encode("utf-8"))
        stream.flush()
        os.fsync(stream.fileno())


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("evidence_json", type=Path, help="Saved output from pair/scan/find --verify")
    parser.add_argument("--output", type=Path, required=True, help="New private .html path; never overwritten")
    parser.add_argument("--show-names", action="store_true", help="Explicitly include local paths; do not share")
    parser.add_argument("--max-edges", type=int, default=MAX_EDGES)
    args = parser.parse_args()
    if args.evidence_json.absolute() == args.output.absolute():
        parser.error("Source JSON and report output must differ")
    try:
        page = render(_read_json(args.evidence_json), show_names=args.show_names, limit=args.max_edges)
        write_new(args.output, page)
    except (ValueError, OSError) as exc:
        parser.error(str(exc))
    print(f"Wrote offline review queue ({'paths explicitly shown' if args.show_names else 'paths redacted'})")


if __name__ == "__main__":
    main()
