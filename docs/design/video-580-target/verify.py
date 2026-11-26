#!/usr/bin/env python3
"""Verify VIDEO-1T generated evidence and its geometry/behavior invariants.

    python3 docs/design/video-580-target/verify.py --check   # CI / review
    python3 docs/design/video-580-target/verify.py --write   # regenerate

Checks (each maps to a #778 Done line or a #813 Review finding):
  * every generated file is byte-identical to generate.py output; svg/ has no stray files
  * the #142 source digest recorded in geometry.json matches the file on disk
  * per viewport and state, every ACTIVE semantic hit is >=48dp, inside the safe
    rect, clear of cutouts and collision-free with every other active hit
    (Review 3: cross-group pairs included; covered/modal controls are inactive)
  * video row bottom <= filmstrip top - 8dp
  * Details rows and all nine Playback speed options are contained in their
    surface viewport, in listed order, reachable, and never overlap (Review 1)
  * transient zones lie inside the safe canvas, clear of cutouts, every active
    hit and the filmstrip, are disjoint and keep the 35/30/35 split (Review 2)
  * 200% font really doubles type (28sp options, 36sp title), wraps at least one
    narrow row and needs scrolling where the surface is short (Review 4)
  * behavior.md carries the exact copy and every named transition (Review 4)
  * README/behavior relative links resolve
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

import generate as g

ROOT = Path(__file__).parent
EPS = 1e-6

REQUIRED_TRANSITIONS = [
    "T01", "T02", "T03", "T04", "T05", "T06", "T07", "T08", "T09", "T10", "T11", "T12",
    "T13", "T14", "T15", "T16", "T17", "T18", "T19", "T20", "T21", "T22", "T23", "T24",
]
REQUIRED_PHRASES = [
    "Playback speed", "Save current frame", "0.25×, 0.5×, 0.75×, 1×, 1.25×, 1.5×, 2×, 3×, 4×",
    "paused hold", "selection", "player recreation", "viewer exit", "different video",
    "seek", "zoom", "page", "lifecycle", "ACTION_CANCEL", "sheet entry", "playback ends",
    "Frame saved", "Couldn’t save frame", "Allow photo access to save this frame",
    "UNKNOWN (#627)", "180ms", "600ms", "reduced animation", "TalkBack",
]


def size_ok(name: str, r) -> bool:
    w, h = r[2] - r[0], r[3] - r[1]
    if name == "filmstrip-band":
        return h >= g.HIT - EPS
    return w >= g.HIT - EPS and h >= g.HIT - EPS


def check_geometry(d: dict, errors: list) -> dict:
    stats = {"states": 0, "active_hits": 0, "pairs": 0, "options": 0, "zones": 0}
    if d["gesture_navigation_insets"] != "UNKNOWN (#627)":
        errors.append("gesture-navigation insets must stay UNKNOWN (#627)")
    if d["rate_choices_ordered_unranked"] != g.RATES:
        errors.append("rate order changed")
    if d["source"]["sha256"] != g.source_digest():
        errors.append("#142 source changed since generation: re-run --write and re-review")
    for vn, v in d["viewports"].items():
        for st, m in v["states"].items():
            stats["states"] += 1
            tag = f"{vn}/{st}"
            safe, cuts, act = m["safe"], m["cutouts"], m["active_hits"]
            if m["video_gap_to_strip"] < g.VIDEO_TO_STRIP_MIN - EPS:
                errors.append(f"{tag}: video row under 8dp above filmstrip")
            for n, r in act.items():
                stats["active_hits"] += 1
                if not size_ok(n, r):
                    errors.append(f"{tag}/{n}: under 48dp")
                if not g.inside(r, safe):
                    errors.append(f"{tag}/{n}: outside safe rect")
                if any(g.overlaps(r, c) for c in cuts):
                    errors.append(f"{tag}/{n}: over cutout")
            names = list(act)
            for i, a in enumerate(names):
                for b in names[i + 1:]:
                    if {a, b} & {"filmstrip-band"} and (g.is_tile(a) or g.is_tile(b)):
                        continue  # tiles are the band's own children
                    stats["pairs"] += 1
                    if g.overlaps(act[a], act[b]):
                        errors.append(f"{tag}: active {a} / {b} overlap")
            if "details" in m:
                dd = m["details"]
                for n in m["inactive"]:
                    if n in act:
                        errors.append(f"{tag}: {n} both active and inactive")
                for n, r in act.items():
                    if not n.startswith(("playback-speed", "save-current-frame")) and g.overlaps(r, dd["panel"]):
                        errors.append(f"{tag}: {n} active under the Details surface")
                buttons = [r for r in dd["rows"] if r["kind"] == "button"]
                if [r["key"] for r in buttons] != ["playback-speed", "save-current-frame"]:
                    errors.append(f"{tag}: Details VIDEO-1 rows missing or reordered")
                for r in buttons:
                    if not r["reachable"]:
                        errors.append(f"{tag}: {r['key']} not reachable by scrolling")
                    if r["fully_visible"] and r["key"] not in act:
                        errors.append(f"{tag}: visible {r['key']} not active")
                    if not r["fully_visible"] and r["key"] in act:
                        errors.append(f"{tag}: clipped {r['key']} counted active")
                if not any(r["fully_visible"] and r["key"] == "save-current-frame" for r in buttons):
                    errors.append(f"{tag}: Save current frame not shown at the evidenced scroll offset")
                rows = dd["rows"]
                for i in range(len(rows) - 1):
                    if rows[i]["rect"][3] > rows[i + 1]["rect"][1] + EPS:
                        errors.append(f"{tag}: Details rows {rows[i]['key']} / {rows[i + 1]['key']} overlap")
            if "speed_sheet" in m:
                sp = m["speed_sheet"]
                if any(n in act for n in m["inactive"]) or any(not n.startswith("rate-") for n in act):
                    errors.append(f"{tag}: non-option hit active under modal sheet")
                if [o["rate"] for o in sp["options"]] != g.RATES:
                    errors.append(f"{tag}: options not in listed order")
                if not g.inside(sp["sheet"], safe):
                    errors.append(f"{tag}: sheet outside safe rect")
                if sp["sheet"][1] - safe[1] < g.SHEET_TOP_SCRIM_MIN - EPS:
                    errors.append(f"{tag}: no dismiss scrim above sheet")
                for o in sp["options"]:
                    stats["options"] += 1
                    if not o["reachable"]:
                        errors.append(f"{tag}: option {o['rate']} unreachable")
                    if o["fully_visible"] and not g.inside(o["shown"], sp["list_viewport"]):
                        errors.append(f"{tag}: option {o['rate']} escapes sheet")
                    if not sp["scrolls"] and not o["fully_visible"]:
                        errors.append(f"{tag}: option {o['rate']} clipped without scrolling")
                ordered = sorted(sp["options"], key=lambda o: (o["rect"][1], o["rect"][0]))
                if [o["rate"] for o in ordered] != g.RATES:
                    errors.append(f"{tag}: visual reading order differs from listed order")
            if "transient" in m:
                tr = m["transient"]
                canvas, z = tr["canvas"], tr["zones"]
                if not g.inside(canvas, safe):
                    errors.append(f"{tag}: canvas outside safe rect")
                w = canvas[2] - canvas[0]
                fr = [(z[k][2] - z[k][0]) / w for k in ("rewind", "centre-zoom", "forward")]
                if any(abs(a - b) > 0.002 for a, b in zip(fr, [0.35, 0.30, 0.35])):
                    errors.append(f"{tag}: zone split {fr} != 35/30/35")
                keys = list(z)
                for i, a in enumerate(keys):
                    stats["zones"] += 1
                    r = z[a]
                    if r[2] - r[0] < g.HIT - EPS or r[3] - r[1] < g.HIT - EPS:
                        errors.append(f"{tag}/{a}: zone under 48dp")
                    if not g.inside(r, canvas):
                        errors.append(f"{tag}/{a}: zone outside canvas")
                    if any(g.overlaps(r, c) for c in cuts):
                        errors.append(f"{tag}/{a}: zone over cutout")
                    if m["chrome"] == "shown" and g.overlaps(r, m["filmstrip"]):
                        errors.append(f"{tag}/{a}: zone over filmstrip")
                    for n, h in act.items():
                        if g.overlaps(r, h):
                            errors.append(f"{tag}/{a}: zone over active {n}")
                    for b in keys[i + 1:]:
                        if g.overlaps(r, z[b]):
                            errors.append(f"{tag}: zones {a} / {b} overlap")
                for ind in tr["indicators"]:
                    host = canvas if ind["zone"] == "hold-2x" else z[ind["zone"]]
                    if not g.inside(ind["rect"], host):
                        errors.append(f"{tag}: indicator {ind['text']} outside its zone")
                    if any(g.overlaps(ind["rect"], h) for h in act.values()):
                        errors.append(f"{tag}: indicator {ind['text']} over an active hit")
        # 200% font evidence is real, not a relabelled 100% layout (Review 4)
        f2 = v["states"]["font200-speed-sheet"]["speed_sheet"]
        if f2["title_sp"] != 36 or f2["option_sp"] != 28:
            errors.append(f"{vn}: 200% speed sheet type is {f2['title_sp']}/{f2['option_sp']}sp, expected 36/28")
        if f2["option_height"] <= v["states"]["speed-sheet"]["speed_sheet"]["option_height"]:
            errors.append(f"{vn}: 200% options did not grow")
        dd2 = v["states"]["font200-details-permission"]["details"]
        if dd2["content_height"] <= v["states"]["details"]["details"]["content_height"]:
            errors.append(f"{vn}: 200% Details content did not grow")
    narrow2 = d["viewports"]["narrow"]["states"]["font200-details-permission"]["details"]
    save = next(r for r in narrow2["rows"] if r["key"] == "save-current-frame")
    if len(save["support_lines"]) < 2:
        errors.append("narrow 200%: permission text does not wrap")
    if not narrow2["scrolls"] or narrow2["scroll"] <= 0:
        errors.append("narrow 200%: Details does not demonstrate scrolling")
    return stats


def check_docs(errors: list) -> None:
    beh = (ROOT / "behavior.md").read_text(encoding="utf-8")
    for t in REQUIRED_TRANSITIONS:
        if not re.search(rf"\|\s*{t}\s*\|", beh):
            errors.append(f"behavior.md: transition {t} missing")
    for p in REQUIRED_PHRASES:
        if p not in beh:
            errors.append(f"behavior.md: required text missing: {p!r}")
    if "Video speed" in beh:
        errors.append("behavior.md: brief copy is 'Playback speed', not 'Video speed'")
    for doc in ("README.md", "behavior.md"):
        text = (ROOT / doc).read_text(encoding="utf-8")
        for target in re.findall(r"\]\(([^)#\s]+)(?:#[^)]*)?\)", text):
            if "://" in target:
                continue
            if not (ROOT / target).exists():
                errors.append(f"{doc}: broken link {target}")


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--check", action="store_true")
    p.add_argument("--write", action="store_true")
    a = p.parse_args()
    if a.check == a.write:
        p.error("choose exactly one of --check or --write")
    expected = g.outputs()
    errors: list[str] = []
    if a.write:
        for n, c in expected.items():
            q = ROOT / n
            q.parent.mkdir(parents=True, exist_ok=True)
            q.write_text(c, encoding="utf-8", newline="\n")
        for stray in (ROOT / "svg").glob("*"):
            if f"svg/{stray.name}" not in expected:
                stray.unlink()
    for n, c in expected.items():
        q = ROOT / n
        if not q.is_file() or q.read_text(encoding="utf-8") != c:
            errors.append(f"generated drift: {n}")
    for stray in (ROOT / "svg").glob("*"):
        if f"svg/{stray.name}" not in expected:
            errors.append(f"stray file not produced by generate.py: svg/{stray.name}")
    stats = check_geometry(g.data(), errors)
    check_docs(errors)
    if errors:
        for e in errors:
            print("FAIL:", e)
        return 1
    print(f"PASS: {len(expected)} generated files byte-exact; {stats['states']} viewport-states; "
          f"{stats['active_hits']} active hits, {stats['pairs']} pairs collision-free; "
          f"{stats['options']} speed options contained and ordered; {stats['zones']} transient zones clear; "
          "200%-font wrap/scroll and behavior transitions present")
    return 0


if __name__ == "__main__":
    sys.exit(main())
