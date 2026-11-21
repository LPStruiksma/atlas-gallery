#!/usr/bin/env python3
"""Deterministically generate VIDEO-1T proposal geometry and SVG evidence."""
from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).parent
RATES = ["0.25×", "0.5×", "0.75×", "1×", "1.25×", "1.5×", "2×", "3×", "4×"]
VIEWS = {
    "portrait": {"dp": [384, 832], "px": [1080, 2340], "safe": [0, 44.44, 384, 784], "cutout": [170.67, 0, 211.56, 44.44], "filmstrip": [0, 632, 384, 708]},
    "landscape": {"dp": [832, 384], "px": [2340, 1080], "safe": [44.44, 0, 784, 384], "cutout": [0, 151.56, 44.44, 192.44], "filmstrip": [44.44, 232, 784, 308]},
    "narrow": {"dp": [640, 384], "px": [1800, 1080], "safe": [44.44, 0, 592, 384], "cutout": [0, 151.56, 44.44, 192.44], "filmstrip": [44.44, 208, 592, 284]},
}
HITS = {
    "portrait": {"play": [12, 560, 60, 608], "seek": [68, 560, 372, 608], "speed": [12, 536, 372, 584], "save-frame": [12, 584, 372, 632]},
    "landscape": {"play": [56, 148, 104, 196], "seek": [112, 148, 772, 196], "speed": [464, 80, 772, 128], "save-frame": [464, 128, 772, 176]},
    "narrow": {"play": [56, 124, 104, 172], "seek": [112, 124, 580, 172], "speed": [320, 64, 580, 112], "save-frame": [320, 112, 580, 160]},
}


def data() -> dict:
    return {
        "status": "PROPOSED_DESIGN_EVIDENCE_NOT_RUNTIME_PROOF",
        "density_px_per_dp": 2.8125,
        "gesture_navigation_insets": "UNKNOWN (#627)",
        "rate_choices_ordered_unranked": RATES,
        "viewports": {name: {**view, "hits": HITS[name]} for name, view in VIEWS.items()},
        "constraints": {"minimum_semantic_hit_dp": 48, "minimum_video_to_filmstrip_gap_dp": 8, "transient_side_fraction": 0.35, "transient_ms": 600, "pager_settle_ms": 180},
    }


def rect(r, fill, stroke="none", dash=""):
    x1, y1, x2, y2 = r
    extra = f' stroke-dasharray="{dash}"' if dash else ""
    return f'<rect x="{x1}" y="{y1}" width="{x2-x1}" height="{y2-y1}" rx="6" fill="{fill}" stroke="{stroke}"{extra}/>'


def svg(view, state, theme="dark"):
    v = VIEWS[view]; w, h = v["dp"]; pw, ph = v["px"]
    bg, ink, slab, accent = (("#101116", "#ffffff", "#282b34", "#7c73ff") if theme == "dark" else ("#f3f0ea", "#17171b", "#ffffff", "#5146d8"))
    out = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{pw}" height="{ph}" viewBox="0 0 {w} {h}" role="img" aria-labelledby="title desc">',
           f'<title id="title">VIDEO-1T proposed {view} {state}</title>',
           '<desc id="desc">Dimensioned design mockup, not a screenshot or runtime capture.</desc>',
           f'<rect width="{w}" height="{h}" fill="{bg}"/>',
           f'<text x="12" y="24" fill="{ink}" font-family="sans-serif" font-size="11">PROPOSED · NOT RUNTIME · {w}×{h}dp / {pw}×{ph}px</text>',
           rect(v["safe"], "none", "#38d996", "6 3"), rect(v["cutout"], "#ff527744"), rect(v["filmstrip"], slab)]
    # Filmstrip and controls (controls are exactly 24dp above strip in these proposals).
    fx1, fy1, fx2, fy2 = v["filmstrip"]
    for x in (fx1+8, (fx1+fx2)/2-20, fx2-48): out.append(rect([x, fy1+12, x+40, min(fy2-4, fy1+68)], "#646874"))
    for name, r in HITS[view].items():
        if state == "speed" and name in ("speed", "save-frame"):
            out += [rect(r, slab, "#9b95ff"), f'<text x="{r[0]+12}" y="{r[1]+30}" fill="{ink}" font-family="sans-serif" font-size="14">{"Video speed   1×" if name=="speed" else "Save current frame"}</text>']
        elif name in ("play", "seek"):
            out += [rect(r, "#00000055", "#9b95ff"), f'<text x="{r[0]+8}" y="{r[1]+29}" fill="{ink}" font-family="sans-serif" font-size="12">{("▶" if state=="playing" else "Ⅱ") if name=="play" else "00:24  ━━━━━  01:40"}</text>']
    if state == "details":
        out += [rect([8, max(48, HITS[view]["speed"][1]-112), w-8, HITS[view]["save-frame"][3]+8], "#1f222bee", "#777"),
                f'<text x="20" y="{max(76,HITS[view]["speed"][1]-76)}" fill="{ink}" font-family="sans-serif" font-size="18">Details · current video</text>']
        # redraw semantic rows over the sheet
        for name in ("speed", "save-frame"):
            r=HITS[view][name]; out += [rect(r, slab, "#9b95ff"), f'<text x="{r[0]+12}" y="{r[1]+30}" fill="{ink}" font-family="sans-serif" font-size="14">{"Video speed   1×" if name=="speed" else "Save current frame"}</text>']
    if state == "speed":
        top = 72 if view != "portrait" else 206
        out += [rect([8, top, w-8, min(h-12, top+430)], slab, "#9b95ff"), f'<text x="20" y="{top+30}" fill="{ink}" font-family="sans-serif" font-size="18">Video speed</text>']
        cols = 1 if view == "portrait" else 3
        for i, rate in enumerate(RATES):
            x=20+(i%cols)*((w-40)/cols); y=top+44+(i//cols)*48
            out += [rect([x,y,x+(w-48)/cols,y+48], "#00000000", "#777"), f'<text x="{x+12}" y="{y+30}" fill="{ink}" font-family="sans-serif" font-size="14">{rate}{"  ✓" if rate=="1×" else ""}</text>']
    if state == "transient":
        left, right = w*.35, w*.65
        out += [rect([0,48,left,h-88], "#00000022", "#ffca55"), rect([right,48,w,h-88], "#00000022", "#ffca55"), rect([left,48,right,h-88], "#00000011", "#72dcff"),
                f'<text x="{left/2-24}" y="{h/2}" fill="{ink}" font-family="sans-serif" font-size="16">−10s</text>', f'<text x="{right+(w-right)/2-24}" y="{h/2}" fill="{ink}" font-family="sans-serif" font-size="16">+10s</text>', f'<text x="{w/2-18}" y="{h/2}" fill="{ink}" font-family="sans-serif" font-size="16">2×</text>']
    out += [f'<text x="12" y="{h-10}" fill="{ink}" font-family="sans-serif" font-size="10">green=safe boundary · red=cutout · outlined=48dp semantic hit</text>', '</svg>']
    return "".join(out)+"\n"


def tables(d):
    lines = ["# Inspectable geometry tables", "", "Generated by `generate.py`; coordinates are dp, rectangles are left,top,right,bottom.", "", "| Viewport | dp / px | Safe boundary | Cutout | Filmstrip |", "|---|---|---|---|---|",]
    for n,v in d["viewports"].items(): lines.append(f'| {n} | {v["dp"][0]}×{v["dp"][1]} / {v["px"][0]}×{v["px"][1]} | {v["safe"]} | {v["cutout"]} | {v["filmstrip"]} |')
    lines += ["", "| Viewport | Semantic hit | Rectangle | Size | Gap above filmstrip |", "|---|---|---|---|---|"]
    for n,v in d["viewports"].items():
        for key,r in v["hits"].items():
            gap = v["filmstrip"][1]-r[3] if key in ("play","seek") else "n/a (sheet)"
            lines.append(f'| {n} | {key} | {r} | {r[2]-r[0]}×{r[3]-r[1]} | {gap} |')
    return "\n".join(lines)+"\n"


def outputs():
    d=data()
    return {"geometry.json": json.dumps(d, ensure_ascii=False, separators=(",", ":"))+"\n", "tables.md": tables(d),
            "svg/portrait-playing.svg": svg("portrait","playing","dark"), "svg/portrait-paused.svg": svg("portrait","paused","light"),
            "svg/portrait-details.svg": svg("portrait","details","dark"), "svg/landscape-speed.svg": svg("landscape","speed","light"),
            "svg/landscape-transient.svg": svg("landscape","transient","dark"), "svg/narrow-font200-details.svg": svg("narrow","details","light")}


if __name__ == "__main__":
    for name, content in outputs().items():
        path=ROOT/name; path.parent.mkdir(parents=True, exist_ok=True); path.write_text(content, encoding="utf-8", newline="\n")
