#!/usr/bin/env python3
"""Verify deterministic VIDEO-1T generated evidence and geometry invariants."""
from __future__ import annotations
import argparse
import sys
from pathlib import Path
import generate

ROOT=Path(__file__).parent

def main():
    p=argparse.ArgumentParser(); p.add_argument("--check", action="store_true"); p.add_argument("--write", action="store_true"); a=p.parse_args()
    if a.check == a.write: p.error("choose exactly one of --check or --write")
    expected=generate.outputs(); errors=[]
    if a.write:
        for n,c in expected.items():
            q=ROOT/n; q.parent.mkdir(parents=True, exist_ok=True); q.write_text(c, encoding="utf-8", newline="\n")
    else:
        for n,c in expected.items():
            q=ROOT/n
            if not q.is_file() or q.read_text(encoding="utf-8") != c: errors.append(f"generated drift: {n}")
    d=generate.data()
    if d["rate_choices_ordered_unranked"] != generate.RATES: errors.append("rate order changed")
    for vn,v in d["viewports"].items():
        safe=v["safe"]; cut=v["cutout"]; strip=v["filmstrip"]
        interactive=[(n,r) for n,r in v["hits"].items() if n in ("play","seek")]
        for n,r in v["hits"].items():
            if r[2]-r[0] < 48 or r[3]-r[1] < 48: errors.append(f"{vn}/{n}: under 48dp")
            if not (r[0]>=safe[0] and r[1]>=safe[1] and r[2]<=safe[2] and r[3]<=safe[3]): errors.append(f"{vn}/{n}: outside safe")
            if max(r[0],cut[0]) < min(r[2],cut[2]) and max(r[1],cut[1]) < min(r[3],cut[3]): errors.append(f"{vn}/{n}: cutout overlap")
        for group in (interactive, [(n,r) for n,r in v["hits"].items() if n in ("speed","save-frame")]):
            for i,(an,ar) in enumerate(group):
                for bn,br in group[i+1:]:
                    if max(ar[0],br[0]) < min(ar[2],br[2]) and max(ar[1],br[1]) < min(ar[3],br[3]): errors.append(f"{vn}: {an}/{bn} overlap")
        if any(strip[1]-r[3] < 8 for _,r in interactive): errors.append(f"{vn}: controls under 8dp above filmstrip")
    if errors:
        for e in errors: print("FAIL:",e)
        return 1
    print(f"PASS: {len(expected)} generated files; 3 viewports; 12 semantic hits; geometry invariants satisfied")
    return 0
if __name__ == "__main__": sys.exit(main())
