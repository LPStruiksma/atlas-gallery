"""Read-only, offline photo-lineage candidate search for small image collections."""
from __future__ import annotations

import argparse
from itertools import combinations
import json
from pathlib import Path

from .matcher import compare_files
from .retrieval import index_folder, shortlist, review_shortlist, audit_cache

EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp", ".tif", ".tiff", ".bmp"}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    pair = commands.add_parser("pair", help="Compare exactly two image files; never alter them")
    pair.add_argument("a", type=Path)
    pair.add_argument("b", type=Path)
    scan = commands.add_parser("scan", help="Exhaustive bounded small-folder scan; reports edges, never deletes or merges")
    scan.add_argument("directory", type=Path)
    scan.add_argument("--max-images", type=int, default=20)
    scan.add_argument("--max-pairs", type=int, default=190)
    for p in (pair, scan):
        p.add_argument("--algorithm", choices=["sift", "orb", "akaze"], default="sift")
        p.add_argument("--max-side", type=int, default=1100)
    index = commands.add_parser("index", help="Opt-in persistent local shortlist cache OUTSIDE photo folder")
    index.add_argument("directory", type=Path)
    index.add_argument("--db", type=Path, required=True)
    index.add_argument("--limit", type=int, default=200, help="Incremental number of new/changed photos")
    find = commands.add_parser("find", help="Local approximate shortlist; optionally verify top hits geometrically")
    find.add_argument("query", type=Path)
    find.add_argument("--db", type=Path, required=True)
    find.add_argument("--top-k", type=int, default=15)
    find.add_argument("--verify", action="store_true", help="Run local geometric verification on shortlisted photos")
    find.add_argument("--algorithm", choices=["sift", "orb", "akaze"], default="orb")
    audit = commands.add_parser('audit-cache', help='Audit cached paths; --apply removes CACHE rows only')
    audit.add_argument('--db', type=Path, required=True)
    audit.add_argument('--max-entries', type=int, default=50000)
    audit.add_argument('--apply', action='store_true', help='Explicitly delete STALE CACHE records, never photos')
    args = parser.parse_args()
    if args.command == 'audit-cache':
        print(json.dumps(audit_cache(args.db, apply=args.apply,
                                     max_entries=args.max_entries), indent=2))
        return
    if args.command == "index":
        print(json.dumps(index_folder(args.directory, args.db, limit=args.limit), indent=2))
        return
    if args.command == "find":
        if args.verify:
            result = review_shortlist(args.query, args.db, top_k=args.top_k, algorithm=args.algorithm)
        else:
            result = {"policy": "retrieval scores are NOT lineage proof", "shortlist":
                      [hit.json() for hit in shortlist(args.query, args.db, top_k=args.top_k)]}
        print(json.dumps(result, indent=2))
        return
    if args.command == "pair":
        result = compare_files(args.a, args.b, args.algorithm, args.max_side)
        print(json.dumps(result.json(), indent=2))
        return
    if not args.directory.is_dir() or args.directory.is_symlink():
        parser.error("scan target must be a real directory, not a symlink")
    if not 2 <= args.max_images <= 100 or args.max_pairs < 1:
        parser.error("max-images must be 2..100; max-pairs must be positive")
    images = sorted(p for p in args.directory.iterdir() if p.is_file() and not p.is_symlink()
                    and p.suffix.lower() in EXTENSIONS)
    pairs = len(images) * (len(images) - 1) // 2
    if len(images) > args.max_images or pairs > args.max_pairs:
        parser.error(f"Found {len(images)} images / {pairs} pairs; explicit limits required. "
                     "This first prototype is quadratic, not intended for full libraries.")
    edges, errors = [], []
    for a, b in combinations(images, 2):
        try:
            result = compare_files(a, b, args.algorithm, args.max_side)
            if result.verdict in {"candidate", "review"}:
                edges.append(result.json())
        except ValueError as exc:
            errors.append({"a": str(a), "b": str(b), "error": str(exc)})
    print(json.dumps({"policy": "review-only; no mutations", "images": len(images),
                      "pairs_examined": pairs, "candidate_edges": edges,
                      "errors": errors}, indent=2))


if __name__ == "__main__":
    main()
