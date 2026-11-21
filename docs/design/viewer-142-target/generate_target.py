#!/usr/bin/env python3
"""DESIGN-8T #627: proposed Lapis Atlas (Quiet Frame v3) viewer target geometry.

One geometry model produces every number, table and SVG in this folder, so the
diagrams cannot drift from the tables. Run from anywhere:

    python3 docs/design/viewer-142-target/generate_target.py

Inputs:  measured-baseline.json (copied from emulator artifacts by
         extract_baseline.py; MEASURED values).
Outputs: target-geometry.json, tables.md and svg/*.svg (PROPOSED values).

Units are dp unless named px. Origin is the window's top-left corner.
Everything the model *proposes* is a target, not an observation. Text widths
are estimates (see TEXT_EM) and must be re-measured on the implementation PR.
"""
import html
import json
import math
import pathlib

HERE = pathlib.Path(__file__).resolve().parent
SVG_DIR = HERE / "svg"

# ---- Proposed constants (Quiet Frame v3 "Lapis Atlas", #654; each one is a target) ----
# Samsung parity governs WHAT the viewer can do (§1/§6 features), not where or how
# it looks (#654 OWNER_INTENT rule 1). Values v3 does not name are marked "v3 silent".
EDGE = 12.0          # visible chrome edge to safeDrawing edge (v3 silent; Atlas choice)
HIT = 48.0           # semantic hit size for icon actions (v3: touch targets >= 48 dp)
PAD = 4.0            # hit rectangle extends this far beyond the visible pill
TOP_PILL = 40.0      # visible top pill height / back pill diameter
GLYPH = 22.0         # icon artwork (v3 silent; v2 value carried until L2 sets icons)
ACTION_GAP = 8.0     # gap between adjacent bottom-pill hit rectangles
TILE_W, TILE_H, TILE_GAP = 44.0, 56.0, 4.0  # Atlas choice: 48 dp pitch = 48 dp touch cells (C1 resolved)
TILE_R = 8.0         # v3 strip-tile radius
CENTRE_SCALE = 1.0   # current item is marked by the sphere + ring, not by size
RING = 2.0           # v3: 2 dp white ring on the current item
KEYLINE = 1.0        # proposed 1 dp black keyline outside the white ring (C3)
SPHERE_D, SPHERE_GAP = 8.0, 4.0  # v3: small #8C9BFF sphere above the current item (size: Atlas choice)
STRIP_BAND = 4.0 + SPHERE_D + SPHERE_GAP + TILE_H + 4.0  # 76 dp touch band
STRIP_TO_PILL = 8.0  # visible pill top to strip band bottom
VIDEO_TO_STRIP = 12.0  # video row hit bottom to strip band top (>= 8 dp rule)
SEEK_MIN = 120.0     # narrowest seek hit before labels reflow
SCRIM_TAIL = 16.0    # scrim continues this far below the lowest top element
SCRIM_ALPHA = 0.35   # v2 full-width top scrim carried (v3 silent)
METADATA_SCRIM_ALPHA = 0.60  # C2: local black field behind metadata; >= 4.5:1 on white
METADATA_SCRIM_PAD_X = 8.0
METADATA_SCRIM_PAD_Y = 4.0
SLAB_FILL = (0x34, 0x48, 0xE0)  # v3 viewer slab, lapis brand-fill (dark state), solid field
SLAB_R = 24.0        # v3 slab radius
ON_BRAND = (0xFF, 0xFF, 0xFF)
PILL_FILL = (0x14, 0x15, 0x2A)  # top back/group pills: v3 mist (dark), Atlas choice
PILL_ALPHA = 0.80
INK = (0xF2, 0xF3, 0xF8)        # v3 ink (dark)
SPHERE = (0x8C, 0x9B, 0xFF)     # v3 viewer current-item sphere
FADE_MS = 200        # v3 motion "Lift", 200 ms
TEXT_EM = 0.58       # conservative mean advance per character (Funnel Sans), in em; estimate
DATE_SP, DATE_LH = 15.0, 22.0   # v3 body
PLACE_SP, PLACE_LH = 14.0, 20.0  # v3 secondary
TIME_SP, TIME_LH = 14.0, 20.0

SAMPLE = {
    "date": "20 November 2026 at 14:32",   # v3: no middle-dot meta strings
    "place_short": "Utrecht",
    "place_long": "Sint-Michielsgestel, Noord-Brabant",
}

TOP_ACTIONS = ["find-person", "find-similar", "details"]  # conditional; parity features, Atlas placement
BOTTOM_ACTIONS = ["favourite", "edit", "share", "delete"]   # v3 viewer slab; edit opens the one editor (AI edit inside)


def r2(v):
    return round(v + 0.0, 2)


def rect(l, t, r, b):
    return {"left": r2(l), "top": r2(t), "right": r2(r), "bottom": r2(b),
            "width": r2(r - l), "height": r2(b - t)}


def overlap(a, b):
    return a["left"] < b["right"] and b["left"] < a["right"] and a["top"] < b["bottom"] and b["top"] < a["bottom"]


def inter_area(a, b):
    w = min(a["right"], b["right"]) - max(a["left"], b["left"])
    h = min(a["bottom"], b["bottom"]) - max(a["top"], b["top"])
    return max(0.0, w) * max(0.0, h)


def text_w(text, sp, scale):
    return len(text) * TEXT_EM * sp * scale


# ---- Colour maths for the legibility checks ----
def lin(c):
    c = c / 255.0
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def lum(rgb):
    r, g, b = (lin(x) for x in rgb)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def contrast(a, b):
    la, lb = sorted((lum(a), lum(b)), reverse=True)
    return (la + 0.05) / (lb + 0.05)


def over(top, alpha, bottom):
    return tuple(round(alpha * t + (1 - alpha) * u) for t, u in zip(top, bottom))


def hex_colour(rgb):
    return "#%02X%02X%02X" % rgb


def selected_marks(out, ring, sphere):
    """Draw C3's 1 dp external black keylines, then the v3 marks."""
    # A 1 dp centred stroke on a path offset 0.5 dp from the ring is exactly a
    # 1 dp band outside it (inner stroke edge meets the ring boundary).
    out(f'<rect x="{r2(ring["left"] - KEYLINE / 2)}" y="{r2(ring["top"] - KEYLINE / 2)}" '
        f'width="{r2(ring["width"] + KEYLINE)}" height="{r2(ring["height"] + KEYLINE)}" '
        f'rx="{TILE_R + RING + KEYLINE / 2}" fill="none" stroke="#000" stroke-width="{KEYLINE}"/>')
    out(f'<rect x="{ring["left"] + RING / 2}" y="{ring["top"] + RING / 2}" '
        f'width="{ring["width"] - RING}" height="{ring["height"] - RING}" '
        f'rx="{TILE_R + 1}" fill="none" stroke="#FFF" stroke-width="{RING}"/>')
    cx, cy = (sphere["left"] + sphere["right"]) / 2, (sphere["top"] + sphere["bottom"]) / 2
    out(f'<circle cx="{r2(cx)}" cy="{r2(cy)}" r="{SPHERE_D / 2 + KEYLINE}" fill="#000"/>')
    out(f'<circle cx="{r2(cx)}" cy="{r2(cy)}" r="{SPHERE_D / 2}" fill="{hex_colour(SPHERE)}"/>')


def metadata_svg(out, element):
    """Draw the actual sample metadata, never a placeholder rectangle."""
    m = element["box"]
    pad_x, pad_y = METADATA_SCRIM_PAD_X, METADATA_SCRIM_PAD_Y
    out(f'<rect x="{r2(m["left"] - pad_x)}" y="{r2(m["top"] - pad_y)}" '
        f'width="{r2(m["width"] + 2 * pad_x)}" height="{r2(m["height"] + 2 * pad_y)}" '
        f'rx="6" fill="#000" opacity="{METADATA_SCRIM_ALPHA}"/>')
    cx = (m["left"] + m["right"]) / 2
    if "2-lines" in element["mode"]:
        out(f'<text x="{r2(cx)}" y="{r2(m["top"] + DATE_SP)}" text-anchor="middle" '
            f'fill="{hex_colour(INK)}" font-size="{DATE_SP}">{html.escape(SAMPLE["date"])}</text>')
        out(f'<text x="{r2(cx)}" y="{r2(m["top"] + DATE_LH + PLACE_SP)}" text-anchor="middle" '
            f'fill="{hex_colour(INK)}" font-size="{PLACE_SP}">{html.escape(element["display_place"])}</text>')
    else:
        text = SAMPLE["date"] + ((" in " + element["display_place"]) if element["display_place"] else "")
        if "ellipsized" in element["mode"]:
            text = SAMPLE["date"] + " in …"
        out(f'<text x="{r2(cx)}" y="{r2(m["top"] + min(DATE_SP, m["height"] - 2))}" text-anchor="middle" '
            f'fill="{hex_colour(INK)}" font-size="{DATE_SP}">{html.escape(text)}</text>')


def synthetic_media_svg(out, W, H, video=False, tone="bright"):
    """A deterministic, fully synthetic landscape with discernible features."""
    sky = "#F5F7FF" if tone == "bright" else "#101A33"
    ridge = "#6D86B5" if tone == "bright" else "#26375C"
    water = "#8CC8E8" if tone == "bright" else "#122943"
    out(f'<rect width="{W}" height="{H}" fill="{sky}"/>')
    out(f'<path d="M0 {r2(H*.54)} L{r2(W*.18)} {r2(H*.28)} L{r2(W*.34)} {r2(H*.52)} '
        f'L{r2(W*.56)} {r2(H*.20)} L{r2(W*.78)} {r2(H*.50)} L{W} {r2(H*.31)} V{H} H0 Z" fill="{ridge}"/>')
    out(f'<rect y="{r2(H*.58)}" width="{W}" height="{r2(H*.42)}" fill="{water}"/>')
    out(f'<circle cx="{r2(W*.78)}" cy="{r2(H*.22)}" r="{r2(min(W,H)*.07)}" fill="#FFD35A"/>')
    # Lighthouse + two sailboats remain identifiable even through shown chrome.
    out(f'<rect x="{r2(W*.47)}" y="{r2(H*.30)}" width="{r2(W*.035)}" height="{r2(H*.32)}" fill="#F6F0E8"/>')
    out(f'<path d="M{r2(W*.455)} {r2(H*.31)} L{r2(W*.505)} {r2(H*.31)} L{r2(W*.48)} {r2(H*.24)} Z" fill="#C43A47"/>')
    for xf in (.22, .68):
        out(f'<path d="M{r2(W*xf)} {r2(H*.68)} l0 {r2(-H*.16)} l{r2(W*.07)} {r2(H*.16)} Z" fill="#FFF" opacity=".92"/>')
    if video:
        out(f'<rect x="{r2(W*.02)}" y="{r2(H*.48)}" width="{r2(W*.22)}" height="18" rx="9" fill="#000" opacity=".65"/>')
        out(f'<text x="{r2(W*.13)}" y="{r2(H*.48 + 12)}" text-anchor="middle" font-size="10" fill="#FFF">SYNTHETIC VIDEO · 00:42</text>')


# ---- Viewports ----
def load_baseline():
    return json.loads((HERE / "measured-baseline.json").read_text())


def viewport_from_frame(name, frame, source, kind):
    win = frame["window_px"]
    ppd = frame["pixels_per_dp"]
    safe = frame["insets_dp"]["compose_safeDrawing"]
    cut = [rect(c["left"] / ppd, c["top"] / ppd, c["right"] / ppd, c["bottom"] / ppd)
           for c in frame.get("cutout_rectangles_px") or []]
    ins = frame["insets_dp"]
    return {
        "name": name, "kind": kind, "source": source,
        "W": r2(win["width"] / ppd), "H": r2(win["height"] / ppd),
        "safe": safe, "cutouts": cut,
        "status": ins.get("compose_statusBars"), "nav": ins.get("compose_navigationBars"),
        "gestures": ins.get("android_systemGestures"),
        "mandatory": ins.get("android_mandatorySystemGestures"),
        "navigation_mode": frame.get("navigation_mode"), "rotation": frame.get("rotation"),
    }


def viewports(base):
    fr = base["frames"]
    vps = []
    vps.append(viewport_from_frame("portrait-3button", fr["photo/bright/dark/portrait/shown"],
                                   "MEASURED photo/bright/dark/portrait/shown", "measured"))
    vps.append(viewport_from_frame("landscape-3button", fr["photo/bright/dark/landscape/shown"],
                                   "MEASURED photo/bright/dark/landscape/shown", "measured"))
    g_p = fr.get("gesture/photo/bright/dark/portrait/shown")
    g_l = fr.get("gesture/photo/bright/dark/landscape/shown")
    if g_p:
        vps.append(viewport_from_frame("portrait-gesture", g_p,
                                       "MEASURED gesture/photo/bright/dark/portrait/shown", "measured"))
    if g_l:
        vps.append(viewport_from_frame("landscape-gesture", g_l,
                                       "MEASURED gesture/photo/bright/dark/landscape/shown", "measured"))
    # Narrow rotated landscape: a COMPUTED stress case (no device measured it).
    # Same measured insets as landscape-3button on a 640 x 360 dp window, the
    # smallest common phone landscape, so the target proves it still fits.
    l3 = vps[1]
    vps.append({**l3, "name": "landscape-narrow-computed", "kind": "computed",
                "source": "COMPUTED: landscape-3button insets on 640x360 dp", "W": 640.0, "H": 360.0,
                "cutouts": [rect(0, 180 - 20.44, 44.44, 180 + 20.44)]})
    return vps


# ---- Target layout ----
def layout(vp, *, font=1.0, top_n=3, place="place_short", video=False, duration="0:04",
           chrome=True, date=True):
    W, H = vp["W"], vp["H"]
    sl, st, sr, sb = vp["safe"]
    els = {}
    notes = []
    if not chrome:
        return {"elements": {}, "notes": ["Chrome hidden: media only; status and navigation bars hidden with it."],
                "free_media_band": None}
    # Top row
    # visible pill top = st + 8, so the 48 dp hit starts at st + 4
    back_hit = rect(sl + EDGE - PAD, st + 4, sl + EDGE - PAD + HIT, st + 4 + HIT)
    els["back"] = {"hit": back_hit, "visible": rect(back_hit["left"] + PAD, back_hit["top"] + PAD,
                                                    back_hit["right"] - PAD, back_hit["bottom"] - PAD)}
    group_right = W - sr - EDGE + PAD
    group_left = group_right - HIT * top_n
    actions = TOP_ACTIONS[-top_n:]
    for i, name in enumerate(actions):
        h = rect(group_left + i * HIT, st + 4, group_left + (i + 1) * HIT, st + 4 + HIT)
        els["top-" + name] = {"hit": h}
    els["top-group"] = {"visible": rect(group_left + PAD, st + 8, group_right - PAD, st + 8 + TOP_PILL)}
    row_bottom = st + 4 + HIT
    # Metadata
    lowest_top = row_bottom
    if date:
        slot_half = min(W / 2 - (back_hit["right"] + 8), (group_left - 8) - W / 2)
        slot_w = max(0.0, 2 * slot_half)
        one_line = SAMPLE["date"] + ((" in " + SAMPLE[place]) if place else "")
        w1 = text_w(one_line, DATE_SP, font)
        h1 = DATE_LH * font
        wd = text_w(SAMPLE["date"], DATE_SP, font)
        wp = text_w(SAMPLE[place], PLACE_SP, font) if place else 0.0
        h2 = (DATE_LH + (PLACE_LH if place else 0)) * font
        if w1 <= slot_w and h1 <= HIT:
            mode, mw, mh, top = "inline-1-line", w1, h1, st + 4 + (HIT - h1) / 2
        elif place and wd <= slot_w and wp <= slot_w and h2 <= HIT:
            mode, mw, mh, top = "inline-2-lines", max(wd, wp), h2, st + 4 + (HIT - h2) / 2
        elif W > H and h1 <= HIT:
            # Landscape is height-starved and width-rich: never add a second row;
            # keep one centred line and ellipsize the place first (fallback F2).
            mode, mw, mh, top = "inline-1-line-ellipsized", min(w1, slot_w), h1, st + 4 + (HIT - h1) / 2
            if w1 > slot_w:
                notes.append(f"landscape metadata ellipsized inline at {r2(slot_w)} dp (estimate {r2(w1)} dp)")
        else:
            full_w = W - sl - sr - 2 * EDGE
            if w1 <= full_w:
                mode, mw, mh = "below-row-1-line", w1, h1
            else:
                mode, mw, mh = "below-row-2-lines", min(full_w, max(wd, wp)), h2
            top = row_bottom + 4
            slot_w = full_w
            if max(wd, wp) > full_w:
                notes.append(f"metadata ellipsized at {r2(full_w)} dp (estimate {r2(max(wd, wp))} dp)")
        mw = min(mw, slot_w)
        cx = W / 2 if mode.startswith("inline") else (sl + W - sr) / 2
        els["metadata"] = {"box": rect(cx - mw / 2, top, cx + mw / 2, top + mh), "mode": mode,
                           "slot_width": r2(slot_w), "display_place": SAMPLE[place] if place else ""}
        lowest_top = max(lowest_top, top + mh)
    els["scrim"] = {"box": rect(0, 0, W, lowest_top + SCRIM_TAIL), "alpha": SCRIM_ALPHA}
    # Bottom pill
    pill_w = 4 * HIT + 3 * ACTION_GAP + 2 * PAD
    pill_h = HIT + 2 * PAD
    cx = (sl + W - sr) / 2
    pill = rect(cx - pill_w / 2, H - sb - EDGE - pill_h, cx + pill_w / 2, H - sb - EDGE)
    els["action-pill"] = {"visible": pill}
    for i, name in enumerate(BOTTOM_ACTIONS):
        x = pill["left"] + PAD + i * (HIT + ACTION_GAP)
        els["action-" + name] = {"hit": rect(x, pill["top"] + PAD, x + HIT, pill["top"] + PAD + HIT)}
    # Filmstrip
    band = rect(sl, pill["top"] - STRIP_TO_PILL - STRIP_BAND, W - sr, pill["top"] - STRIP_TO_PILL)
    tile_bottom = band["bottom"] - 4.0
    cyb = tile_bottom - TILE_H / 2
    pitch = TILE_W + TILE_GAP
    cw, ch = TILE_W * CENTRE_SCALE, TILE_H * CENTRE_SCALE
    ccx = (sl + W - sr) / 2
    tiles = [{"visible": rect(ccx - cw / 2, cyb - ch / 2, ccx + cw / 2, cyb + ch / 2), "current": True,
              "ring": rect(ccx - cw / 2 - RING, cyb - ch / 2 - RING, ccx + cw / 2 + RING, cyb + ch / 2 + RING),
              "sphere": rect(ccx - SPHERE_D / 2, cyb - ch / 2 - SPHERE_GAP - SPHERE_D,
                             ccx + SPHERE_D / 2, cyb - ch / 2 - SPHERE_GAP)}]
    # neighbour tiles at TILE_GAP until the band edge
    x_r = ccx + cw / 2 + TILE_GAP
    x_l = ccx - cw / 2 - TILE_GAP
    while x_r + TILE_W <= band["right"]:
        tiles.append({"visible": rect(x_r, cyb - TILE_H / 2, x_r + TILE_W, cyb + TILE_H / 2)})
        x_r += pitch
    while x_l - TILE_W >= band["left"]:
        tiles.append({"visible": rect(x_l - TILE_W, cyb - TILE_H / 2, x_l, cyb + TILE_H / 2)})
        x_l -= pitch
    # Hit cells: contiguous, non-overlapping, one per tile; centre cell = its visible width + gaps
    for t in tiles:
        v = t["visible"]
        t["hit"] = rect(v["left"] - TILE_GAP / 2, band["top"], v["right"] + TILE_GAP / 2, band["bottom"])
    els["filmstrip"] = {"band": band, "tiles": tiles, "visible_tiles": len(tiles)}
    bottom_top = band["top"]
    if video:
        row = rect(sl, band["top"] - VIDEO_TO_STRIP - HIT, W - sr, band["top"] - VIDEO_TO_STRIP)
        play = rect(sl + EDGE - PAD, row["top"], sl + EDGE - PAD + HIT, row["bottom"])
        ew = text_w(duration, TIME_SP, font)  # elapsed label sized for the longest value
        dw = ew
        el = rect(play["right"] + 4, row["top"] + (HIT - TIME_LH * font) / 2,
                  play["right"] + 4 + ew, row["top"] + (HIT + TIME_LH * font) / 2)
        du = rect(W - sr - EDGE - dw, el["top"], W - sr - EDGE, el["bottom"])
        seek = rect(el["right"] + 8, row["top"], du["left"] - 8, row["bottom"])
        mode = "labels-inline"
        if seek["width"] < SEEK_MIN or TIME_LH * font > HIT:
            mode = "labels-above"
            lab_h = TIME_LH * font
            row = rect(sl, row["top"] - lab_h - 4, W - sr, row["bottom"])
            el = rect(sl + EDGE, row["top"], sl + EDGE + ew, row["top"] + lab_h)
            du = rect(W - sr - EDGE - dw, row["top"], W - sr - EDGE, row["top"] + lab_h)
            play = rect(play["left"], row["bottom"] - HIT, play["right"], row["bottom"])
            seek = rect(play["right"] + 8, row["bottom"] - HIT, W - sr - EDGE + PAD, row["bottom"])
            notes.append(f"video time labels move above the seek bar (font {font}, {duration})")
        els["video-row"] = {"box": row, "mode": mode}
        els["video-play"] = {"hit": play, "visible": rect(play["left"] + PAD, play["top"] + PAD,
                                                          play["right"] - PAD, play["bottom"] - PAD)}
        els["video-seek"] = {"hit": seek,
                             # 8 dp track inset: the 16 dp thumb stays inside the hit at 0% and 100%
                             "track": rect(seek["left"] + 8, (seek["top"] + seek["bottom"]) / 2 - 2,
                                           seek["right"] - 8, (seek["top"] + seek["bottom"]) / 2 + 2),
                             "thumb_at_start": rect(seek["left"], (seek["top"] + seek["bottom"]) / 2 - 8,
                                                    seek["left"] + 16, (seek["top"] + seek["bottom"]) / 2 + 8),
                             "thumb_at_end": rect(seek["right"] - 16, (seek["top"] + seek["bottom"]) / 2 - 8,
                                                  seek["right"], (seek["top"] + seek["bottom"]) / 2 + 8)}
        els["video-elapsed"] = {"box": el}
        els["video-duration"] = {"box": du}
        els["video-gap-to-strip"] = r2(band["top"] - row["bottom"])
        bottom_top = row["top"]
    free = bottom_top - els["scrim"]["box"]["bottom"]
    # C1 proof is based on the unobstructed band at true viewport scale. The
    # generated scene deliberately places five independently recognizable
    # features across the full frame (sun, two ridges, lighthouse, sailboats).
    media_proof = {"unobstructed_width": r2(W - sl - sr), "unobstructed_height": r2(free),
                   "unobstructed_area": r2(max(0, W - sl - sr) * max(0, free)),
                   "discernible_features": 5, "chrome": "shown"}
    return {"elements": els, "notes": notes, "free_media_band": r2(free),
            "top_chrome_bottom": r2(lowest_top), "bottom_chrome_top": r2(bottom_top),
            "primary_media_proof": media_proof}


def hits(lay):
    out = []
    for name, el in lay["elements"].items():
        if isinstance(el, dict) and "hit" in el:
            out.append((name, el["hit"]))
    fs = lay["elements"].get("filmstrip")
    if fs:
        for i, t in enumerate(fs["tiles"]):
            out.append((f"tile-{i}", t["hit"]))
    return out


def checks(vp, lay):
    W, H = vp["W"], vp["H"]
    sl, st, sr, sb = vp["safe"]
    safe = rect(sl, st, W - sr, H - sb)
    hs = hits(lay)
    res = {"collisions": [], "outside_safe": [], "in_cutout": [], "under_44": [],
           "gesture_side_overlap": [], "in_mandatory_zone": []}
    for i in range(len(hs)):
        for j in range(i + 1, len(hs)):
            if overlap(hs[i][1], hs[j][1]):
                res["collisions"].append(f"{hs[i][0]} x {hs[j][0]}")
    for name, h in hs:
        if h["left"] < safe["left"] - 0.01 or h["top"] < safe["top"] - 0.01 or \
                h["right"] > safe["right"] + 0.01 or h["bottom"] > safe["bottom"] + 0.01:
            res["outside_safe"].append(name)
        for c in vp["cutouts"]:
            if overlap(h, c):
                res["in_cutout"].append(name)
        if h["width"] < 44 or h["height"] < 44:
            res["under_44"].append(f"{name} {h['width']}x{h['height']}")
        g = vp.get("gestures")
        if g and vp.get("navigation_mode") == "2":
            left_zone = rect(0, 0, g[0], H)
            right_zone = rect(W - g[2], 0, W, H)
            a = inter_area(h, left_zone) + inter_area(h, right_zone)
            if a > 0:
                res["gesture_side_overlap"].append(f"{name} {r2(a)} dp2")
        m = vp.get("mandatory")
        if m and m[3] > 0 and h["bottom"] > H - m[3]:
            res["in_mandatory_zone"].append(name)
    # tiles under 44 are summarised, not listed one by one
    tiles_small = [x for x in res["under_44"] if x.startswith("tile-")]
    res["under_44"] = [x for x in res["under_44"] if not x.startswith("tile-")]
    if tiles_small:
        res["under_44"].append(f"{len(tiles_small)} filmstrip tile cells")
    video_gap = lay["elements"].get("video-gap-to-strip")
    res["video_gap_ok"] = None if video_gap is None else video_gap >= 8
    # safe-edge clearance of visible pills
    clear = {}
    for name in ("back", "top-group", "action-pill"):
        v = lay["elements"].get(name, {}).get("visible")
        if v:
            clear[name] = {"left": r2(v["left"] - safe["left"]), "top": r2(v["top"] - safe["top"]),
                           "right": r2(safe["right"] - v["right"]), "bottom": r2(safe["bottom"] - v["bottom"])}
    res["visible_clearance"] = clear
    return res


# ---- SVG ----
def svg_layout(vp, lay, title, subtitle, legend_rows, theme="dark", photo="bright", video=False):
    W, H = vp["W"], vp["H"]
    sl, st, sr, sb = vp["safe"]
    side = 300
    vw, vh = W + side + 24, max(H, 560) + 70
    sphere = "#%02X%02X%02X" % SPHERE
    slab = "#%02X%02X%02X" % SLAB_FILL
    o = []
    a = o.append
    a(f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {r2(vw)} {r2(vh)}" '
      f'font-family="Inter, Arial, sans-serif" font-size="8">')
    a(f'<title>{title}</title>')
    a(f'<rect width="{r2(vw)}" height="{r2(vh)}" fill="#FFFFFF"/>')
    a(f'<text x="8" y="16" font-size="12" font-weight="600" fill="#111318">{title}</text>')
    a(f'<text x="8" y="30" font-size="8.5" fill="#4A4F59">{subtitle}</text>')
    a('<g transform="translate(8,44)">')
    # true-scale deterministic media (the viewport group has no scale transform)
    a(f'<rect width="{W}" height="{H}" fill="#000000"/>')
    synthetic_media_svg(a, W, H, video=video, tone=photo)
    els = lay["elements"]
    if "scrim" in els:
        s = els["scrim"]["box"]
        a(f'<rect x="0" y="0" width="{W}" height="{s["bottom"]}" fill="#000" opacity="{SCRIM_ALPHA}"/>')
    # system areas
    if vp.get("status") and vp["status"][1] > 0:
        a(f'<rect x="0" y="0" width="{W}" height="{vp["status"][1]}" fill="#6E7BFF" opacity="0.25"/>')
    nav = vp.get("nav") or [0, 0, 0, 0]
    if nav[3] > 0:
        a(f'<rect x="0" y="{r2(H - nav[3])}" width="{W}" height="{nav[3]}" fill="#6E7BFF" opacity="0.25"/>')
    if nav[2] > 0:
        a(f'<rect x="{r2(W - nav[2])}" y="0" width="{nav[2]}" height="{H}" fill="#6E7BFF" opacity="0.25"/>')
    if nav[0] > 0:
        a(f'<rect x="0" y="0" width="{nav[0]}" height="{H}" fill="#6E7BFF" opacity="0.25"/>')
    g = vp.get("gestures")
    if g and vp.get("navigation_mode") == "2":
        a(f'<rect x="0" y="0" width="{g[0]}" height="{H}" fill="url(#hatch)" opacity="0.6"/>')
        a(f'<rect x="{r2(W - g[2])}" y="0" width="{g[2]}" height="{H}" fill="url(#hatch)" opacity="0.6"/>')
    for c in vp["cutouts"]:
        a(f'<rect x="{c["left"]}" y="{c["top"]}" width="{c["width"]}" height="{c["height"]}" fill="#FF3B30" opacity="0.55"/>')
    a(f'<rect x="{sl}" y="{st}" width="{r2(W - sl - sr)}" height="{r2(H - st - sb)}" fill="none" '
      f'stroke="#00C2A8" stroke-width="1" stroke-dasharray="4 3"/>')
    pill = "#%02X%02X%02X" % PILL_FILL

    def vis_pill(v, rx=None):
        rr = v["height"] / 2 if rx is None else rx
        a(f'<rect x="{v["left"]}" y="{v["top"]}" width="{v["width"]}" height="{v["height"]}" rx="{r2(rr)}" '
          f'fill="{pill}" opacity="{PILL_ALPHA}"/>')

    def hit_rect(h, colour="#FF2D55"):
        a(f'<rect x="{h["left"]}" y="{h["top"]}" width="{h["width"]}" height="{h["height"]}" fill="none" '
          f'stroke="{colour}" stroke-width="0.8" stroke-dasharray="2 1.5"/>')

    def glyph(h):
        cx, cy = (h["left"] + h["right"]) / 2, (h["top"] + h["bottom"]) / 2
        a(f'<rect x="{r2(cx - GLYPH / 2)}" y="{r2(cy - GLYPH / 2)}" width="{GLYPH}" height="{GLYPH}" rx="5" '
          f'fill="none" stroke="#ECEDF0" stroke-width="1.6"/>')

    if "back" in els:
        vis_pill(els["back"]["visible"])
        glyph(els["back"]["hit"])
        hit_rect(els["back"]["hit"])
    if "top-group" in els:
        vis_pill(els["top-group"]["visible"])
        for n in TOP_ACTIONS:
            if "top-" + n in els:
                glyph(els["top-" + n]["hit"])
                hit_rect(els["top-" + n]["hit"])
    if "metadata" in els:
        m = els["metadata"]["box"]
        a(f'<rect x="{m["left"]}" y="{m["top"]}" width="{m["width"]}" height="{m["height"]}" fill="#FFFFFF" '
          f'opacity="0.18" stroke="#FFD60A" stroke-width="0.8"/>')
        metadata_svg(a, els["metadata"])
    if "filmstrip" in els:
        fs = els["filmstrip"]
        b = fs["band"]
        a(f'<rect x="{b["left"]}" y="{b["top"]}" width="{b["width"]}" height="{b["height"]}" fill="none" '
          f'stroke="#FF9F0A" stroke-width="0.8" stroke-dasharray="3 2"/>')
        for t in fs["tiles"]:
            v = t["visible"]
            a(f'<rect x="{v["left"]}" y="{v["top"]}" width="{v["width"]}" height="{v["height"]}" rx="{TILE_R}" '
              f'fill="#8E8E93"/>')
            if t.get("current"):
                selected_marks(a, t["ring"], t["sphere"])
    if "action-pill" in els:
        v = els["action-pill"]["visible"]
        a(f'<rect x="{v["left"]}" y="{v["top"]}" width="{v["width"]}" height="{v["height"]}" rx="{SLAB_R}" fill="{slab}"/>')
        for n in BOTTOM_ACTIONS:
            glyph(els["action-" + n]["hit"])
            hit_rect(els["action-" + n]["hit"])
    if "video-row" in els:
        pv = els["video-play"]
        vis_pill(pv["visible"])
        glyph(pv["hit"])
        hit_rect(pv["hit"])
        tr = els["video-seek"]["track"]
        a(f'<rect x="{tr["left"]}" y="{tr["top"]}" width="{tr["width"]}" height="4" rx="2" fill="#ECEDF0" opacity="0.6"/>')
        a(f'<rect x="{tr["left"]}" y="{tr["top"]}" width="{r2(tr["width"] * 0.4)}" height="4" rx="2" fill="{sphere}"/>')
        a(f'<circle cx="{r2(tr["left"] + tr["width"] * 0.4)}" cy="{r2(tr["top"] + 2)}" r="8" fill="#ECEDF0"/>')
        hit_rect(els["video-seek"]["hit"])
        for k in ("video-elapsed", "video-duration"):
            bx = els[k]["box"]
            a(f'<rect x="{bx["left"]}" y="{bx["top"]}" width="{bx["width"]}" height="{bx["height"]}" fill="none" '
              f'stroke="#FFD60A" stroke-width="0.6"/>')
        sb_ = els["filmstrip"]["band"]["top"]
        rb = els["video-row"]["box"]["bottom"]
        x = W - sr - 4
        a(f'<line x1="{r2(x)}" y1="{r2(rb)}" x2="{r2(x)}" y2="{r2(sb_)}" stroke="#30D158" stroke-width="1.2"/>')
        a(f'<text x="{r2(x - 3)}" y="{r2((rb + sb_) / 2 + 3)}" text-anchor="end" fill="#30D158" font-size="7">'
          f'{r2(sb_ - rb)} dp ≥ 8</text>')
    a('</g>')
    a('<defs><pattern id="hatch" width="6" height="6" patternUnits="userSpaceOnUse" patternTransform="rotate(45)">'
      '<line x1="0" y1="0" x2="0" y2="6" stroke="#FF9F0A" stroke-width="2"/></pattern></defs>')
    # legend / dimensions column
    x0 = W + 24
    y = 52
    a(f'<g font-size="8" fill="#111318">')
    key = [("#00C2A8", "safeDrawing (measured)"), ("#6E7BFF", "status / navigation bars (measured)"),
           ("#FF3B30", "display cutout (measured)"), ("#FF2D55", "semantic hit rect (proposed)"),
           ("#FF9F0A", "filmstrip touch band (proposed)"), ("#FFD60A", "text box (proposed, estimate)")]
    for colour, label in key:
        a(f'<rect x="{x0}" y="{y - 7}" width="9" height="9" fill="{colour}" opacity="0.8"/>'
          f'<text x="{x0 + 14}" y="{y}">{label}</text>')
        y += 13
    y += 6
    for row in legend_rows:
        a(f'<text x="{x0}" y="{y}">{html.escape(row)}</text>')
        y += 11.5
    a('</g></svg>')
    return "\n".join(o) + "\n"


def fmt_r(r):
    return f'{r["left"]:.1f},{r["top"]:.1f} → {r["right"]:.1f},{r["bottom"]:.1f} ({r["width"]:.1f}×{r["height"]:.1f})'


def legend_for(vp, lay, chk):
    els = lay["elements"]
    rows = [f'Viewport {vp["W"]}×{vp["H"]} dp · {vp["source"]}',
            f'safeDrawing L/T/R/B {vp["safe"]} dp · nav mode {vp.get("navigation_mode")}']
    if vp.get("gestures"):
        rows.append(f'systemGestures {vp["gestures"]} · mandatory {vp.get("mandatory")}')
    rows.append("— proposed (dp, window coordinates) —")
    for name in ["back", "top-group", "metadata", "filmstrip", "action-pill", "video-row"]:
        if name not in els:
            continue
        el = els[name]
        box = el.get("visible") or el.get("box") or el.get("band")
        label = name + (f' [{el["mode"]}]' if "mode" in el else "")
        rows.append(f'{label}: {fmt_r(box)}')
    rows.append(f'back hit {fmt_r(els["back"]["hit"])}')
    rows.append(f'favourite hit {fmt_r(els["action-favourite"]["hit"])}')
    if "video-seek" in els:
        rows.append(f'seek hit {fmt_r(els["video-seek"]["hit"])}')
        rows.append(f'video row → strip gap {els["video-gap-to-strip"]} dp (rule ≥ 8)')
    rows.append(f'free media band between chrome: {lay["free_media_band"]} dp')
    rows.append("— checks —")
    rows.append(f'hit collisions: {len(chk["collisions"]) or "none"}')
    rows.append(f'hits outside safeDrawing: {", ".join(chk["outside_safe"]) or "none"}')
    rows.append(f'hits over cutout: {", ".join(chk["in_cutout"]) or "none"}')
    rows.append(f'hits < 44 dp: {"; ".join(chk["under_44"]) or "none"}')
    if chk["gesture_side_overlap"]:
        rows.append(f'over back-gesture zone: {len(chk["gesture_side_overlap"])} hits (taps pass; drags excluded)')
    for n, c in chk["visible_clearance"].items():
        rows.append(f'{n} clearance to safe edge L{c["left"]} T{c["top"]} R{c["right"]} B{c["bottom"]}')
    return rows


SCENARIOS = [
    # name, layout kwargs
    ("photo", {}),
    ("photo-font200", {"font": 2.0}),
    ("photo-font200-longplace", {"font": 2.0, "place": "place_long"}),
    ("photo-no-place", {"place": None}),
    ("photo-no-date", {"date": False}),
    ("photo-1-top-action", {"top_n": 1}),
    ("video-short", {"video": True, "duration": "0:04"}),
    ("video-long", {"video": True, "duration": "1:23:45"}),
    ("video-long-font200", {"video": True, "duration": "1:23:45", "font": 2.0}),
    ("hidden", {"chrome": False}),
]


def legibility():
    rows = []
    white = (255, 255, 255)
    black = (0, 0, 0)
    for bg_name, bg in (("bright photo (white worst case)", white), ("dark photo (black)", black)):
        scrim = over(black, SCRIM_ALPHA, bg)
        pill_on = over(PILL_FILL, PILL_ALPHA, bg)
        rows.append({"background": bg_name,
                     "metadata_text_on_35pct_scrim": r2(contrast(INK, scrim)),
                     "metadata_text_on_60pct_local_scrim": r2(contrast(INK, over(black, 0.60, bg))),
                     "icon_on_top_pill": r2(contrast(INK, pill_on)),
                     "icon_on_lapis_slab": r2(contrast(ON_BRAND, SLAB_FILL)),
                     "slab_vs_background": r2(contrast(SLAB_FILL, bg)),
                     "sphere_vs_background": r2(contrast(SPHERE, bg)),
                     "white_ring_vs_background": r2(contrast(white, bg)),
                     "ring_with_black_keyline_vs_background": r2(max(contrast(white, bg), contrast(black, bg))),
                     "sphere_with_black_keyline_vs_background": r2(max(contrast(SPHERE, bg), contrast(black, bg)))})
    return rows


# ---- State matrix and interaction model (target concepts) ----
STATES = [
    # label, layout kwargs, photo tone, theme, overlay note
    ("light · white photo", {}, "bright", "light", "actual metadata · 60% local scrim"),
    ("dark · white photo", {}, "bright", "dark", "actual metadata · 60% local scrim"),
    ("light · black photo", {}, "dark", "light", "actual metadata · selected keylines"),
    ("dark · black photo", {}, "dark", "dark", "actual metadata · selected keylines"),
    ("light · white video", {"video": True}, "bright", "light", "shown playback + seek + strip"),
    ("dark · white video", {"video": True}, "bright", "dark", "shown playback + seek + strip"),
    ("light · black video", {"video": True}, "dark", "light", "shown playback + seek + strip"),
    ("dark · black video", {"video": True}, "dark", "dark", "shown playback + seek + strip"),
    ("chrome + system bars hidden", {"chrome": False}, "bright", "dark", "context only; shown proof above"),
    ("no place", {"place": None}, "bright", "dark", "date · time only"),
    ("no date and place", {"date": False}, "bright", "dark", "no metadata row, no placeholder"),
    ("200% font, long place", {"font": 2.0, "place": "place_long"}, "bright", "dark", "2 lines below row"),
    ("video short 0:04 · playing", {"video": True}, "dark", "dark", "pause glyph; seek 40%"),
    ("video long 1:23:45", {"video": True, "duration": "1:23:45"}, "dark", "dark", "labels sized for h:mm:ss"),
    ("video seek start (0%)", {"video": True, "_seek": 0.0}, "dark", "dark", "thumb inside hit, 8 dp inset"),
    ("video seek end (100%)", {"video": True, "_seek": 1.0}, "dark", "dark", "thumb inside hit, 8 dp inset"),
    ("video 200% font long", {"video": True, "duration": "1:23:45", "font": 2.0}, "dark", "dark", "labels above bar"),
    ("pending (decode not ready)", {"_state": "pending"}, "none", "dark", "strip thumb as placeholder; chrome shown"),
    ("unavailable / error", {"_state": "error"}, "none", "dark", "glyph only; back + details live"),
    ("long filmstrip (30+)", {"_long": True}, "bright", "dark", "virtualized; fade at band ends"),
    ("one-handed reach zones", {"_reach": True}, "bright", "dark", "bottom third = thumb zone"),
]


def mini(vp, kw, tone, theme, x0, y0, scale, label, note):
    k = {key: v for key, v in kw.items() if not key.startswith("_")}
    lay = layout(vp, **k)
    W, H = vp["W"], vp["H"]
    sph = "#%02X%02X%02X" % SPHERE
    slab = "#%02X%02X%02X" % SLAB_FILL
    o = [f'<g transform="translate({r2(x0)},{r2(y0)}) scale({scale})">',
         f'<rect width="{W}" height="{H}" fill="#000"/>']
    if tone != "none":
        synthetic_media_svg(o.append, W, H, video=kw.get("video", False), tone=tone)
    st = kw.get("_state")
    if st:
        cx, cy = W / 2, H / 2
        if st == "pending":
            o.append(f'<rect x="{r2(cx - 60)}" y="{r2(cy - 80)}" width="120" height="160" fill="#3A3D45" opacity="0.8"/>')
        o.append(f'<rect x="{r2(cx - 11)}" y="{r2(cy - 11)}" width="22" height="22" rx="5" fill="none" stroke="#B4B8C1" stroke-width="1.6"/>')
    els = lay["elements"]
    if "scrim" in els:
        o.append(f'<rect width="{W}" height="{els["scrim"]["box"]["bottom"]}" fill="#000" opacity="{SCRIM_ALPHA}"/>')
    pill = "#%02X%02X%02X" % PILL_FILL
    for name in ("back", "top-group"):
        if name in els:
            v = els[name]["visible"]
            o.append(f'<rect x="{v["left"]}" y="{v["top"]}" width="{v["width"]}" height="{v["height"]}" '
                     f'rx="{r2(v["height"] / 2)}" fill="{pill}" opacity="{PILL_ALPHA}"/>')
    if "action-pill" in els:
        v = els["action-pill"]["visible"]
        o.append(f'<rect x="{v["left"]}" y="{v["top"]}" width="{v["width"]}" height="{v["height"]}" rx="{SLAB_R}" fill="{slab}"/>')
    if st == "error" and "action-pill" in els:
        for n in ("favourite", "edit", "share"):
            h = els["action-" + n]["hit"]
            o.append(f'<line x1="{h["left"] + 10}" y1="{h["top"] + 10}" x2="{h["right"] - 10}" y2="{h["bottom"] - 10}" stroke="#9A9EA8" stroke-width="2"/>')
    if "metadata" in els:
        metadata_svg(o.append, els["metadata"])
    if "filmstrip" in els:
        for t in els["filmstrip"]["tiles"]:
            v = t["visible"]
            o.append(f'<rect x="{v["left"]}" y="{v["top"]}" width="{v["width"]}" height="{v["height"]}" rx="{TILE_R}" fill="#8E8E93"/>')
            if t.get("current"):
                selected_marks(o.append, t["ring"], t["sphere"])
        if kw.get("_long"):
            b = els["filmstrip"]["band"]
            o.append(f'<rect x="{b["left"]}" y="{b["top"]}" width="24" height="{b["height"]}" fill="#000" opacity="0.6"/>')
            o.append(f'<rect x="{r2(b["right"] - 24)}" y="{b["top"]}" width="24" height="{b["height"]}" fill="#000" opacity="0.6"/>')
    if "video-seek" in els:
        tr = els["video-seek"]["track"]
        f = kw.get("_seek", 0.4)
        o.append(f'<rect x="{tr["left"]}" y="{tr["top"]}" width="{tr["width"]}" height="4" fill="#ECEDF0" opacity="0.6"/>')
        o.append(f'<circle cx="{r2(tr["left"] + tr["width"] * f)}" cy="{r2(tr["top"] + 2)}" r="8" fill="#ECEDF0"/>')
        pv = els["video-play"]["visible"]
        o.append(f'<circle cx="{r2((pv["left"] + pv["right"]) / 2)}" cy="{r2((pv["top"] + pv["bottom"]) / 2)}" r="20" fill="{pill}" opacity="{PILL_ALPHA}"/>')
    if kw.get("_reach"):
        o.append(f'<rect y="{r2(H * 2 / 3)}" width="{W}" height="{r2(H / 3)}" fill="#30D158" opacity="0.30"/>')
        o.append(f'<rect y="{r2(H / 3)}" width="{W}" height="{r2(H / 3)}" fill="#FFD60A" opacity="0.22"/>')
        o.append(f'<rect width="{W}" height="{r2(H / 3)}" fill="#FF453A" opacity="0.22"/>')
    o.append('</g>')
    o.append(f'<text x="{r2(x0)}" y="{r2(y0 + H * scale + 11)}" font-size="8.5" font-weight="600" fill="#111318">{label}</text>')
    o.append(f'<text x="{r2(x0)}" y="{r2(y0 + H * scale + 21)}" font-size="7.5" fill="#4A4F59">{note}</text>')
    return o


def states_svg(vp):
    scale = 0.3
    cw, chh = vp["W"] * scale + 26, vp["H"] * scale + 34
    cols = 4
    rows = math.ceil(len(STATES) / cols)
    vw, vh = cols * cw + 16, rows * chh + 50
    o = [f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {r2(vw)} {r2(vh)}" font-family="Inter, Arial, sans-serif">',
         f'<title>PROPOSED target state matrix — {vp["name"]}</title>',
         f'<rect width="{r2(vw)}" height="{r2(vh)}" fill="#FFFFFF"/>',
         f'<text x="8" y="16" font-size="12" font-weight="600" fill="#111318">PROPOSED Lapis Atlas viewer states — {vp["name"]} (target concept, not captures)</text>',
         f'<text x="8" y="30" font-size="8.5" fill="#4A4F59">Geometry from generate_target.py; insets from {vp["source"]}. Viewer ground is black in both themes; slab, sphere and ring are the same in both.</text>']
    for i, (label, kw, tone, theme, note) in enumerate(STATES):
        x = 8 + (i % cols) * cw
        y = 42 + (i // cols) * chh
        o += mini(vp, kw, tone, theme, x, y, scale, label, note)
    o.append('</svg>')
    return "\n".join(o) + "\n"


def interaction_svg(vp):
    lay = layout(vp, video=True)
    els = lay["elements"]
    W, H = vp["W"], vp["H"]
    vw, vh = W + 360, H + 60
    o = [f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {r2(vw)} {r2(vh)}" font-family="Inter, Arial, sans-serif" font-size="8">',
         f'<title>PROPOSED gesture priority and TalkBack order — {vp["name"]}</title>',
         f'<rect width="{r2(vw)}" height="{r2(vh)}" fill="#FFFFFF"/>',
         f'<text x="8" y="16" font-size="12" font-weight="600" fill="#111318">PROPOSED gesture priority and TalkBack order — {vp["name"]}, video, chrome shown</text>',
         '<g transform="translate(8,30)">', f'<rect width="{W}" height="{H}" fill="#1C1C1E"/>']
    bands = []
    g = vp.get("gestures") or [24, 0, 24, 0]
    gl, gr = g[0] or 24, g[2] or 24
    seek = els["video-seek"]["hit"]
    strip = els["filmstrip"]["band"]
    bands.append(("1 system edge zones", rect(0, 0, gl, H), "#FF453A"))
    bands.append(("1 system edge zones", rect(W - gr, 0, W, H), "#FF453A"))
    bands.append(("2 seek drag", seek, "#BF5AF2"))
    bands.append(("3 strip drag / tap", strip, "#FF9F0A"))
    for name in ["back", "top-find-person", "top-find-similar", "top-details", "video-play",
                 "action-favourite", "action-edit", "action-share", "action-delete"]:
        if name in els:
            bands.append(("4 buttons", els[name]["hit"], "#0A84FF"))
    for label, r, col in bands:
        o.append(f'<rect x="{r["left"]}" y="{r["top"]}" width="{r["width"]}" height="{r["height"]}" fill="{col}" opacity="0.35" stroke="{col}"/>')
    # Interaction boards are a render path too: retain the real selected mark
    # rather than implying selection only through the orange touch band.
    for tile in els["filmstrip"]["tiles"]:
        v = tile["visible"]
        o.append(f'<rect x="{v["left"]}" y="{v["top"]}" width="{v["width"]}" height="{v["height"]}" rx="{TILE_R}" fill="#8E8E93"/>')
        if tile.get("current"):
            selected_marks(o.append, tile["ring"], tile["sphere"])
    metadata_svg(o.append, els["metadata"])
    o.append(f'<text x="{r2(W / 2)}" y="{r2(H / 2)}" text-anchor="middle" fill="#ECEDF0" font-size="10">5 media: tap · double-tap · pinch · swipe</text>')
    # exclusion rects
    for r in (strip, seek):
        o.append(f'<rect x="0" y="{r["top"]}" width="{gl}" height="{r["height"]}" fill="none" stroke="#FFFFFF" stroke-dasharray="2 2"/>')
        o.append(f'<rect x="{r2(W - gr)}" y="{r["top"]}" width="{gr}" height="{r["height"]}" fill="none" stroke="#FFFFFF" stroke-dasharray="2 2"/>')
    order = ["back", "metadata", "top-find-person", "top-find-similar", "top-details", "media", "video-play",
             "video-seek", "filmstrip", "action-favourite", "action-edit", "action-share", "action-delete"]
    n = 0
    for name in order:
        if name == "media":
            cx, cy = W / 2, H / 2 + 16
        elif name == "filmstrip":
            cx, cy = (strip["left"] + strip["right"]) / 2, strip["top"] + 6
        elif name in els:
            box = els[name].get("hit") or els[name].get("box")
            cx, cy = (box["left"] + box["right"]) / 2, box["top"] + 6
        else:
            continue
        n += 1
        o.append(f'<circle cx="{r2(cx)}" cy="{r2(cy)}" r="6" fill="#FFFFFF" stroke="#111318"/>'
                 f'<text x="{r2(cx)}" y="{r2(cy + 3)}" text-anchor="middle" font-size="7" font-weight="700">{n}</text>')
    o.append('</g>')
    x0 = W + 24
    lines = [
        "Gesture priority (highest wins where regions overlap):",
        (f"1  System edge zones (MEASURED systemGestures L{gl} / R{gr} dp) — back/home stay system-owned,"
         if vp.get("navigation_mode") == "2" else
         "1  System edge zones: none in three-button mode; red bands show a 24 dp PLACEHOLDER,"),
        "   EXCEPT inside the strip and seek bands, which request gesture exclusion",
        f"   ({strip['height']} + {seek['height']} = {r2(strip['height'] + seek['height'])} dp per edge, under Android's 200 dp cap).",
        "   Dashed white boxes = requested exclusion rects (only while chrome is shown).",
        "   The bottom mandatory home zone is never excluded; no hit rect enters it.",
        "2  Seek drag: touch-down in the seek hit band consumes the gesture; no chrome",
        "   toggle, no pager swipe; holding swaps the strip band for frame thumbnails",
        "   (VIEW:V16-V18, UNVERIFIED).",
        "3  Strip drag: horizontal slop inside the band scrubs; tap = nearest tile centre.",
        "   Consumed; never toggles chrome.",
        "4  Buttons: 48 dp hit rects; a press that slides off cancels.",
        "5  Media: single tap toggles chrome + system bars (200 ms Lift, waits out",
        "   the double-tap timeout); double-tap zoom x2.31; pinch; horizontal = pager;",
        "   swipe up = Details; swipe down = close (unzoomed only).",
        "",
        "TalkBack order (numbers on the left):",
        "1 Back · 2 date · time · place (one node) · 3-5 top actions (only those shown)",
        "· 6 media (\"Photo N of M: <title>\", state: controls shown/hidden)",
        "· 7 play/pause · 8 seek (adjustable, value = elapsed of duration)",
        "· 9 filmstrip (collection; current tile selected; custom actions",
        "  previous/next) · 10-13 favourite, edit, share, delete.",
        "Hidden chrome: only the media node + system back remain; a TalkBack",
        "double-tap on media shows chrome again. Spoken output is NOT_CAPTURED.",
    ]
    for i, t in enumerate(lines):
        o.append(f'<text x="{x0}" y="{44 + i * 12}" fill="#111318">{html.escape(t)}</text>')
    o.append('</svg>')
    return "\n".join(o) + "\n"


def reach_rows(vp):
    lay = layout(vp, video=True)
    els = lay["elements"]
    H = vp["H"]
    rows = []
    for name in ["back", "top-details", "video-play", "video-seek", "action-favourite", "action-edit",
                 "action-share", "action-delete"]:
        if name not in els:
            continue
        h = els[name]["hit"]
        cy = (h["top"] + h["bottom"]) / 2
        zone = "easy (bottom third)" if cy >= H * 2 / 3 else ("ok (middle)" if cy >= H / 3 else "stretch (top third)")
        rows.append((name, r2(cy), zone))
    return rows


def main():
    base = load_baseline()
    vps = viewports(base)
    SVG_DIR.mkdir(exist_ok=True)
    out = {"constants": {k: v for k, v in globals().items() if k.isupper() and isinstance(v, (int, float))},
           "sample_text": SAMPLE, "viewports": [], "legibility": legibility()}
    for vp in vps:
        entry = {"viewport": {k: vp[k] for k in ("name", "kind", "source", "W", "H", "safe", "status", "nav",
                                                  "gestures", "mandatory", "navigation_mode", "rotation", "cutouts")},
                 "scenarios": {}}
        for sname, kw in SCENARIOS:
            lay = layout(vp, **kw)
            chk = checks(vp, lay) if kw.get("chrome", True) else {}
            entry["scenarios"][sname] = {"layout": lay, "checks": chk}
        out["viewports"].append(entry)
        # Diagrams: the main photo and video variants per viewport
        for sname in ("photo", "video-long-font200", "photo-font200-longplace"):
            kw = dict(SCENARIOS)[sname]
            lay = layout(vp, **kw)
            chk = checks(vp, lay)
            title = f'PROPOSED Lapis Atlas viewer target — {vp["name"]} — {sname}'
            sub = ("Target concept, NOT a runtime capture. Insets/cutout copied from the measured frame named "
                   "below; every other rectangle is proposed.")
            svg = svg_layout(vp, lay, title, sub, legend_for(vp, lay, chk),
                             video=kw.get("video", False))
            (SVG_DIR / f'{vp["name"]}--{sname}.svg').write_text(svg)
        (SVG_DIR / f'{vp["name"]}--states.svg').write_text(states_svg(vp))
        (SVG_DIR / f'{vp["name"]}--interaction.svg').write_text(interaction_svg(vp))
        entry["reach"] = reach_rows(vp)
    (HERE / "target-geometry.json").write_text(json.dumps(out, ensure_ascii=False) + "\n")
    write_tables(base, out)
    write_gesture_page(base, out)
    print("ok:", len(vps), "viewports,", len(SCENARIOS), "scenarios each")


def write_tables(base, out):
    L = []
    a = L.append
    a("<!-- Generated by generate_target.py from measured-baseline.json; do not edit by hand. -->")
    a("")
    a("### T1. Measured insets per viewport (copied from emulator reports)")
    a("")
    a("| Viewport | Source frame | Window dp | nav mode | rotation | safeDrawing L/T/R/B | status | navigation | cutout rects (dp) | systemGestures | mandatory |")
    a("|---|---|---|---|---|---|---|---|---|---|---|")
    for e in out["viewports"]:
        v = e["viewport"]
        cuts = "; ".join(fmt_r(c) for c in v["cutouts"]) or "none"
        a(f'| {v["name"]} | {v["source"]} | {v["W"]}×{v["H"]} | {v["navigation_mode"]} | {v["rotation"]} | '
          f'{v["safe"]} | {v["status"]} | {v["nav"]} | {cuts} | {v["gestures"] or "NOT_RECORDED (#616 schema)"} | '
          f'{v["mandatory"] or "NOT_RECORDED"} |')
    a("")
    a("### T2. Measured current vs proposed target (photo, chrome shown, 100% font)")
    a("")
    a("| Viewport | Element | Measured current (semantic bounds, dp) | Proposed visible (dp) | Proposed hit (dp) | ≥44 |")
    a("|---|---|---|---|---|---|")
    fmap = {"portrait-3button": "photo/bright/dark/portrait/shown",
            "landscape-3button": "photo/bright/dark/landscape/shown",
            "portrait-gesture": "gesture/photo/bright/dark/portrait/shown",
            "landscape-gesture": "gesture/photo/bright/dark/landscape/shown"}
    pairs = [("back", "atlas-back"), ("top-details", "atlas-viewer-details-open"),
             ("action-favourite", "atlas-viewer-favourite"), ("action-edit", "atlas-edit-photo"),
             ("action-share", "atlas-share-media"), ("action-delete", "atlas-viewer-trash"),
             ("filmstrip", "atlas-filmstrip"), ("tile-0", "atlas-thumbnail-0")]
    for e in out["viewports"]:
        v = e["viewport"]
        els = e["scenarios"]["photo"]["layout"]["elements"]
        frame = base["frames"].get(fmap.get(v["name"], ""), {})
        for el, tag in pairs:
            m = frame.get("regions_dp", {}).get(tag) if frame else None
            mtxt = fmt_r(m) if isinstance(m, dict) else (m or "— (computed viewport)")
            if el == "filmstrip":
                pv, ph = fmt_r(els["filmstrip"]["band"]), "band = touch region"
                ok = "band ✓"
            elif el == "tile-0":
                t = els["filmstrip"]["tiles"][0]
                pv, ph = fmt_r(t["visible"]), fmt_r(t["hit"])
                ok = "✓" if t["hit"]["width"] >= 48 and t["hit"]["height"] >= 48 else "✗"
            else:
                pv = fmt_r(els[el]["visible"]) if "visible" in els[el] else ("inside top pill" if el.startswith("top-") else "inside action pill")
                ph = fmt_r(els[el]["hit"])
                ok = "✓" if els[el]["hit"]["width"] >= 44 and els[el]["hit"]["height"] >= 44 else "✗"
            a(f'| {v["name"]} | {el} | {mtxt} | {pv} | {ph} | {ok} |')
    a("")
    a("### T3. Per-scenario checks (every viewport × scenario)")
    a("")
    a("| Viewport | Scenario | Metadata mode | Unobstructed media W×H / area dp² | Features | Video gap to strip dp (≥8) | Hit collisions | Outside safe | Over cutout | <44 dp (non-tile) | Over back-gesture zone | In mandatory zone | Notes |")
    a("|---|---|---|---|---|---|---|---|---|---|---|---|---|")
    for e in out["viewports"]:
        v = e["viewport"]
        for sname, s in e["scenarios"].items():
            lay, chk = s["layout"], s["checks"]
            if not chk:
                a(f'| {v["name"]} | {sname} | — | full screen | context | — | — | — | — | — | — | — | {"; ".join(lay["notes"])} |')
                continue
            md = lay["elements"].get("metadata", {}).get("mode", "none (no date)")
            vg = lay["elements"].get("video-gap-to-strip", "—")
            mp = lay["primary_media_proof"]
            media = f'{mp["unobstructed_width"]}×{mp["unobstructed_height"]} / {mp["unobstructed_area"]}'
            non_tile = [x for x in chk["under_44"] if "tile" not in x]
            a(f'| {v["name"]} | {sname} | {md} | {media} | {mp["discernible_features"]} | {vg} | '
              f'{len(chk["collisions"]) or "0"} | {", ".join(chk["outside_safe"]) or "0"} | '
              f'{", ".join(chk["in_cutout"]) or "0"} | {", ".join(non_tile) or "0"} | '
              f'{len(chk["gesture_side_overlap"]) if v["navigation_mode"] == "2" else "n/a (3-button)"} | '
              f'{", ".join(chk["in_mandatory_zone"]) or "0"} | {"; ".join(lay["notes"]) or ""} |')
    a("")
    a("### T5. One-handed reach (video, chrome shown; centre y of each hit)")
    a("")
    a("| Viewport | Control | Centre y dp | Zone |")
    a("|---|---|---|---|")
    for e in out["viewports"]:
        for name, cy, zone in e["reach"]:
            a(f'| {e["viewport"]["name"]} | {name} | {cy} | {zone} |')
    a("")
    a("### T4. Legibility (WCAG contrast ratios, computed)")
    a("")
    a("Explicit IEC sRGB linearization: `c/12.92` at `c <= 0.04045`, otherwise `((c+0.055)/1.055)^2.4`; luminance uses 0.2126/0.7152/0.0722. Selected-mark contrast is the stronger visible member of each adjacent fill/keyline pair; worst cases are reported below.")
    a("")
    a("| Background | Ink on 35% scrim | Ink on 60% local scrim | Icon on top pill | White icon on lapis slab | Slab vs photo | Sphere vs photo | White ring vs photo | Ring + black keyline | Sphere + black keyline |")
    a("|---|---|---|---|---|---|---|---|---|---|")
    for row in out["legibility"]:
        a(f'| {row["background"]} | {row["metadata_text_on_35pct_scrim"]} | {row["metadata_text_on_60pct_local_scrim"]} | '
          f'{row["icon_on_top_pill"]} | {row["icon_on_lapis_slab"]} | {row["slab_vs_background"]} | '
          f'{row["sphere_vs_background"]} | {row["white_ring_vs_background"]} | {row["ring_with_black_keyline_vs_background"]} | '
          f'{row["sphere_with_black_keyline_vs_background"]} |')
    a("")
    a(f'Worst metadata contrast on the required 60% local scrim: **{min(r["metadata_text_on_60pct_local_scrim"] for r in out["legibility"])}:1** (PASS ≥ 4.5:1). Worst selected ring/keyline: **{min(r["ring_with_black_keyline_vs_background"] for r in out["legibility"])}:1**; worst sphere/keyline: **{min(r["sphere_with_black_keyline_vs_background"] for r in out["legibility"])}:1**.')
    a("")
    (HERE / "tables.md").write_text("\n".join(L) + "\n")


def write_gesture_page(base, out):
    src = next((r for r in base["source_reports"] if r.get("issue") == 627), None)
    vps = {e["viewport"]["name"]: e for e in out["viewports"]}
    L = ["# Gesture navigation: measured insets and target checks (#627)", "",
         "<!-- Generated by generate_target.py from measured-baseline.json; do not edit by hand. -->", ""]
    a = L.append
    if not src or "portrait-gesture" not in vps:
        a("**NOT CAPTURED.** Every #616 frame is three-button (`navigation_mode=0`), so every")
        a("gesture-navigation number in this folder is **UNKNOWN / preflight-gated**.")
        a("")
        a("This unit tried a test-only capture (`Viewer627GestureNavTest`, switching the emulator's")
        a("gestural navbar overlay on and back). The test itself passed on both attempts, but each time")
        a("`OriginalProportionsAlbumJourneyTest.albumAtOriginalProportionsTimelineOff` failed later in")
        a("the same shard ([run 44650698832](https://github.com/LPStruiksma/atlas-gallery/actions/runs/44650698832),")
        a("twice; [run 44656676515](https://github.com/LPStruiksma/atlas-gallery/actions/runs/44656676515)")
        a("after restoring the exact overlay set), while other heads pass it. A global navigation-mode")
        a("flip inside the shared suite is not isolated, so the capture was removed from this PR.")
        a("A follow-up needs an isolated run (own emulator job or last-in-suite) — a workflow")
        a("change that belongs to Strategy.")
        a("")
        a("What the target assumes until then (to verify on that capture):")
        a("- Bottom chrome is anchored to `safeDrawing`, which includes the gesture bar, so the action")
        a("  pill stays 12 dp above it whatever its height.")
        a("- No hit rect may enter `mandatorySystemGestures` (bottom).")
        a("- Strip and seek bands request side exclusion rects (76 + 48 = 124 dp per edge, under 200 dp).")
        a("- Taps on back, top actions and play inside a side back-gesture zone still reach the app.")
        (HERE / "GESTURE_NAV.md").write_text("\n".join(L) + "\n")
        return
    a(f'Source: `Viewer627GestureNavTest` on head `{src.get("head")}`, '
      f'[run {src.get("run")}](https://github.com/LPStruiksma/atlas-gallery/actions/runs/{src.get("run")}), '
      f'shard artifact `ATLAS-android-UI-evidence-shard-<n>` → `ui-evidence/viewer-627-gesture.json`; PNGs '
      '`viewer-627-gesture-photo-dark-{portrait-shown,portrait-hidden,landscape-shown}.png` in '
      '`ATLAS-android-UI-evidence`. Host: ' + str(src.get("host")) + ".")
    a("")
    a("The test switches the emulator to its gestural overlay (`navigation_mode=2`), measures the "
      "**current** viewer, and restores three-button navigation. It renders no target.")
    a("")
    a("## Measured (current viewer, dp)")
    a("")
    a("| Frame key | nav mode | rotation | safeDrawing L/T/R/B | navigationBars | systemGestures | mandatorySystemGestures | tappableElement |")
    a("|---|---|---|---|---|---|---|---|")
    for k, f in base["frames"].items():
        if not k.startswith("gesture/"):
            continue
        i = f["insets_dp"]
        a(f'| `{k}` | {f["navigation_mode"]} | {f["rotation"]} | {i.get("compose_safeDrawing")} | '
          f'{i.get("compose_navigationBars")} | {i.get("android_systemGestures")} | '
          f'{i.get("android_mandatorySystemGestures")} | {i.get("android_tappableElement")} |')
    a("")
    a("## Current semantic bounds under gesture navigation (dp)")
    a("")
    a("| Frame key | back | favourite | filmstrip | thumbnail-0 |")
    a("|---|---|---|---|---|")
    for k, f in base["frames"].items():
        if not k.startswith("gesture/"):
            continue
        r = f["regions_dp"]
        cell = lambda t: fmt_r(r[t]) if isinstance(r.get(t), dict) else str(r.get(t))
        a(f'| `{k}` | {cell("atlas-back")} | {cell("atlas-viewer-favourite")} | {cell("atlas-filmstrip")} | '
          f'{cell("atlas-thumbnail-0")} |')
    a("")
    a("## Target on the gesture insets")
    a("")
    a("| Viewport | Scenario | Free media band | Video gap (≥8) | Collisions | Outside safe | Hits over side back-gesture zones | Hits in mandatory zone |")
    a("|---|---|---|---|---|---|---|---|")
    for name in ("portrait-gesture", "landscape-gesture"):
        if name not in vps:
            continue
        for sname, s_ in vps[name]["scenarios"].items():
            chk = s_["checks"]
            if not chk:
                continue
            lay = s_["layout"]
            a(f'| {name} | {sname} | {lay["free_media_band"]} | {lay["elements"].get("video-gap-to-strip", "—")} | '
              f'{len(chk["collisions"])} | {", ".join(chk["outside_safe"]) or "0"} | '
              f'{"; ".join(chk["gesture_side_overlap"]) or "0"} | {", ".join(chk["in_mandatory_zone"]) or "0"} |')
    a("")
    a("Reading the side-zone column: a hit rect inside the back-gesture zone still receives **taps** "
      "(the system takes only horizontal swipes from the edge). Drags that must not become Back (strip "
      "scrub, seek) request exclusion rects over their bands while chrome is shown; the per-edge total "
      "is 76 + 48 = 124 dp, inside Android's 200 dp limit. Back, top actions and play are taps only, so "
      "they need no exclusion.")
    a("")
    a("Diagrams: `svg/portrait-gesture--*.svg`, `svg/landscape-gesture--*.svg`.")
    a("")
    a("Still UNKNOWN: Lukas's One UI gesture settings (gesture hint on/off, sensitivity), physical panel.")
    (HERE / "GESTURE_NAV.md").write_text("\n".join(L) + "\n")


if __name__ == "__main__":
    main()
