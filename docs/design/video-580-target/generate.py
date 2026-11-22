#!/usr/bin/env python3
"""VIDEO-1T (#778): proposed VIDEO-1 interaction geometry, derived from #142.

PROPOSED design evidence only, never a runtime capture or OEM placement claim.

Every rectangle starts from the merged #142 proposed target
(`../viewer-142-target/target-geometry.json`, read only, never written) and
the existing Details surface in `ViewerDetailsSheet.kt` / `ViewerDetailsMotion.kt`
(bottom surface = 42% of viewer height, vertical scroll, 24dp side padding,
20dp top / 36dp bottom padding, label-over-value rows with 9dp vertical padding).
VIDEO-1 adds only rows inside that surface, a modal Playback speed sheet and
transient canvas zones. One model produces geometry.json, tables.md and every
SVG, so they cannot drift; `verify.py` re-derives and checks them.

Rectangles are [left, top, right, bottom] in dp. Font sizes are sp at 1.0
scale and double at 200% font.
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).parent
SOURCE = ROOT.parent / "viewer-142-target" / "target-geometry.json"

STATUS = "PROPOSED_DESIGN_EVIDENCE_NOT_RUNTIME_PROOF"
GESTURE_NAV = "UNKNOWN (#627)"
DENSITY = 2.8125
RATES = ["0.25×", "0.5×", "0.75×", "1×", "1.25×", "1.5×", "2×", "3×", "4×"]
SELECTED_RATE = "1×"

# viewport key -> #142 viewport name
VIEWPORTS = {
    "portrait": "portrait-3button",
    "landscape": "landscape-3button",
    "narrow": "landscape-narrow-computed",
}
SCENARIO = {1.0: "video-long", 2.0: "video-long-font200"}

HIT = 48.0
VIDEO_TO_STRIP_MIN = 8.0
CANVAS_CLEARANCE = 8.0          # transient zones keep this gap from chrome hits
SIDE_FRACTION = 0.35            # double-tap -10 s / +10 s zones; centre keeps 0.30
TRANSIENT_MS = 600
SETTLE_MS = 180
TEXT_EM = 0.58                  # average glyph advance, same estimate as #142

# Existing Details surface (ViewerDetailsMotion.kt / ViewerDetailsSheet.kt).
DETAILS_FRACTION = 0.42
DETAILS_PAD_X = 24.0
DETAILS_PAD_TOP = 20.0
DETAILS_PAD_BOTTOM = 36.0
ROW_PAD_Y = 9.0
ROW_GAP = 3.0
EXISTING_ROWS_BELOW = 6         # Name, Path, Size, Resolution, Type, Album (unchanged)

# Type ramp (sp at 1.0, line height sp at 1.0).
T_HEADER = (14.0, 20.0)         # "Details" labelLarge
T_LABEL = (12.0, 16.0)          # row label labelMedium
T_VALUE = (14.0, 20.0)          # row value / action bodyMedium
T_SUPPORT = (14.0, 20.0)        # supporting state text
T_SHEET_TITLE = (18.0, 24.0)    # "Playback speed" sheet title -> 36sp at 200%
T_OPTION = (14.0, 20.0)         # rate option -> 28sp at 200%

SHEET_PAD = 16.0
SHEET_TITLE_GAP = 12.0
SHEET_TOP_SCRIM_MIN = 48.0      # dismiss scrim stays visible above the sheet
OPTION_GAP = 8.0
RADIO = 24.0

COPY = {
    "speed_row_label": "Playback speed",
    "speed_sheet_title": "Playback speed",
    "save_action": "Save current frame",
    "save_permission": "Allow photo access to save this frame",
    "rewind": "−10 s",
    "forward": "+10 s",
    "hold": "2×",
}


def r2(x: float) -> float:
    return round(x + 0.0, 2)


def R(l, t, r, b) -> list:
    return [r2(l), r2(t), r2(r), r2(b)]


def from142(d: dict) -> list:
    return R(d["left"], d["top"], d["right"], d["bottom"])


def overlaps(a, b) -> bool:
    return max(a[0], b[0]) < min(a[2], b[2]) and max(a[1], b[1]) < min(a[3], b[3])


def inside(a, b) -> bool:
    return a[0] >= b[0] - 1e-6 and a[1] >= b[1] - 1e-6 and a[2] <= b[2] + 1e-6 and a[3] <= b[3] + 1e-6


def text_width(text: str, sp: float) -> float:
    return len(text) * sp * TEXT_EM


def wrap(text: str, sp: float, width: float) -> list[str]:
    """Greedy word wrap with the #142 average-advance estimate."""
    lines, cur = [], ""
    for word in text.split(" "):
        trial = word if not cur else f"{cur} {word}"
        if cur and text_width(trial, sp) > width:
            lines.append(cur)
            cur = word
        else:
            cur = trial
    lines.append(cur)
    return lines


def source_digest() -> str:
    return hashlib.sha256(SOURCE.read_bytes()).hexdigest()


def load142() -> dict:
    return json.loads(SOURCE.read_text(encoding="utf-8"))


def base_hits(elements: dict) -> dict:
    """#142 semantic hits that exist while a video is shown with chrome."""
    hits = {}
    for key in ("back", "top-find-person", "top-find-similar", "top-details",
                "action-favourite", "action-edit", "action-share", "action-delete",
                "video-play", "video-seek"):
        hits[key] = from142(elements[key]["hit"])
    hits["filmstrip-band"] = from142(elements["filmstrip"]["band"])
    for i, tile in enumerate(elements["filmstrip"]["tiles"]):
        hits[f"filmstrip-tile-{i}"] = from142(tile["hit"])
    return hits


def is_tile(name: str) -> bool:
    return name.startswith("filmstrip-tile-")


# ---------------------------------------------------------------------------
# Details surface (existing component, VIDEO-1 rows added at the top)

def details_layout(vp: dict, font: float, safe: list, save_state: str) -> dict:
    W, H = vp["W"], vp["H"]
    panel_h = H * DETAILS_FRACTION
    panel = R(0, H - panel_h, W, H)
    viewport = R(max(panel[0], safe[0]), max(panel[1], safe[1]), min(panel[2], safe[2]), min(panel[3], safe[3]))
    row_l, row_r = safe[0] + DETAILS_PAD_X - 12, safe[2] - DETAILS_PAD_X + 12
    text_w = (row_r - row_l) - 24
    rows, y = [], panel[1] + DETAILS_PAD_TOP  # content coordinates at scroll 0
    header_lh = T_HEADER[1] * font
    rows.append({"key": "details-header", "kind": "text", "rect": R(row_l, y, row_r, y + header_lh),
                 "lines": ["Details"], "sp": T_HEADER[0] * font, "lh": header_lh})
    y += header_lh + 12

    # Playback speed: existing row anatomy (label over value), but a button.
    lab_sp, lab_lh = T_LABEL[0] * font, T_LABEL[1] * font
    val_sp, val_lh = T_VALUE[0] * font, T_VALUE[1] * font
    lab_lines = wrap(COPY["speed_row_label"], lab_sp, text_w)
    val_lines = wrap(SELECTED_RATE, val_sp, text_w)
    h = max(HIT, ROW_PAD_Y * 2 + len(lab_lines) * lab_lh + ROW_GAP + len(val_lines) * val_lh)
    rows.append({"key": "playback-speed", "kind": "button", "rect": R(row_l, y, row_r, y + h),
                 "label_lines": lab_lines, "value_lines": val_lines, "label_sp": lab_sp, "label_lh": lab_lh,
                 "value_sp": val_sp, "value_lh": val_lh,
                 "semantics": f"{COPY['speed_row_label']}, {SELECTED_RATE}, button"})
    y += h

    # Save current frame: single action line, optional supporting state text.
    act_lines = wrap(COPY["save_action"], val_sp, text_w)
    sup_sp, sup_lh = T_SUPPORT[0] * font, T_SUPPORT[1] * font
    sup_lines = wrap(COPY["save_permission"], sup_sp, text_w) if save_state == "permission" else []
    h = ROW_PAD_Y * 2 + len(act_lines) * val_lh + (ROW_GAP + len(sup_lines) * sup_lh if sup_lines else 0)
    h = max(HIT, h)
    rows.append({"key": "save-current-frame", "kind": "button", "rect": R(row_l, y, row_r, y + h),
                 "action_lines": act_lines, "support_lines": sup_lines, "value_sp": val_sp, "value_lh": val_lh,
                 "support_sp": sup_sp, "support_lh": sup_lh, "state": save_state,
                 "semantics": f"{COPY['save_action']}, button"
                 + (f". {COPY['save_permission']}" if sup_lines else "")})
    y += h

    existing_row_h = ROW_PAD_Y * 2 + lab_lh + ROW_GAP + val_lh
    ex_h = EXISTING_ROWS_BELOW * existing_row_h
    rows.append({"key": "existing-details-rows", "kind": "existing", "rect": R(row_l, y, row_r, y + ex_h),
                 "lines": wrap("Existing Details rows unchanged: Name, Path, Size, Resolution, Type, Album", T_LABEL[0] * font, text_w),
                 "sp": T_LABEL[0] * font, "lh": lab_lh})
    y += ex_h + DETAILS_PAD_BOTTOM

    content_h = y - panel[1]
    view_h = viewport[3] - viewport[1]
    # The content starts at the panel top; the safe viewport may start lower.
    clip_top = viewport[1] - panel[1]
    max_scroll = max(0.0, content_h - (panel[3] - panel[1]) + (panel[3] - viewport[3]))
    # Scroll so that both VIDEO-1 rows are fully visible when possible, else the save row.
    targets = [r for r in rows if r["kind"] == "button"]
    need = max(0.0, max(r["rect"][3] for r in targets) - viewport[3])
    scroll = min(max_scroll, need)
    if min(r["rect"][1] for r in targets) - scroll < viewport[1]:
        scroll = min(max_scroll, max(0.0, targets[-1]["rect"][3] - viewport[3]))
    scroll = r2(scroll)
    for r in rows:
        x1, y1, x2, y2 = r["rect"]
        r["shown"] = R(x1, y1 - scroll, x2, y2 - scroll)
        r["fully_visible"] = inside(r["shown"], viewport)
        # reachable: some scroll offset in [0, max_scroll] shows the row fully
        lo = r["rect"][3] - viewport[3]
        hi = r["rect"][1] - viewport[1]
        r["reachable"] = (r["rect"][3] - r["rect"][1]) <= view_h + 1e-6 and max(0.0, lo) <= min(max_scroll, hi) + 1e-6
    return {"panel": panel, "viewport": viewport, "clip_top": r2(clip_top), "content_height": r2(content_h),
            "viewport_height": r2(view_h), "max_scroll": r2(max_scroll), "scroll": scroll,
            "scrolls": content_h > (viewport[3] - panel[1]) + 1e-6, "rows": rows, "text_width": r2(text_w)}


# ---------------------------------------------------------------------------
# Playback speed sheet (modal, single choice)

def speed_layout(vp: dict, font: float, safe: list, view: str) -> dict:
    cols = 1 if view == "portrait" else 3
    grid_l, grid_r = safe[0] + 12, safe[2] - 12
    col_w = (grid_r - grid_l - (cols - 1) * OPTION_GAP) / cols
    opt_sp, opt_lh = T_OPTION[0] * font, T_OPTION[1] * font
    title_sp, title_lh = T_SHEET_TITLE[0] * font, T_SHEET_TITLE[1] * font
    label_w = col_w - 24 - RADIO - 8
    title_lines = wrap(COPY["speed_sheet_title"], title_sp, grid_r - grid_l - 24)
    opt_lines = {rate: wrap(rate, opt_sp, label_w) for rate in RATES}
    opt_h = max(HIT, max(len(v) for v in opt_lines.values()) * opt_lh + 16)
    n_rows = -(-len(RATES) // cols)
    header_h = SHEET_PAD + len(title_lines) * title_lh + SHEET_TITLE_GAP
    list_content_h = n_rows * opt_h + SHEET_PAD
    max_h = (safe[3] - safe[1]) - SHEET_TOP_SCRIM_MIN
    sheet_h = min(header_h + list_content_h, max_h)
    sheet = R(safe[0], safe[3] - sheet_h, safe[2], safe[3])
    list_view = R(sheet[0], sheet[1] + header_h, sheet[2], sheet[3])
    list_view_h = list_view[3] - list_view[1]
    max_scroll = max(0.0, list_content_h - list_view_h)
    sel = RATES.index(SELECTED_RATE)
    sel_top = (sel // cols) * opt_h
    scroll = r2(min(max_scroll, max(0.0, sel_top + opt_h - list_view_h)))
    options = []
    for i, rate in enumerate(RATES):  # row-major: reading order == listed order
        c, rw = i % cols, i // cols
        x = grid_l + c * (col_w + OPTION_GAP)
        y = list_view[1] + rw * opt_h
        content = R(x, y, x + col_w, y + opt_h)
        shown = R(x, y - scroll, x + col_w, y + opt_h - scroll)
        options.append({"rate": rate, "order": i + 1, "selected": rate == SELECTED_RATE,
                        "rect": content, "shown": shown, "fully_visible": inside(shown, list_view),
                        "reachable": opt_h <= list_view_h + 1e-6, "lines": opt_lines[rate],
                        "semantics": f"{rate}, radio button, {'selected' if rate == SELECTED_RATE else 'not selected'}"})
    return {"sheet": sheet, "header": R(sheet[0], sheet[1], sheet[2], sheet[1] + header_h),
            "title_lines": title_lines, "title_sp": title_sp, "title_lh": title_lh,
            "option_sp": opt_sp, "option_lh": opt_lh, "columns": cols, "option_height": r2(opt_h),
            "list_viewport": list_view, "list_content_height": r2(list_content_h), "max_scroll": r2(max_scroll),
            "scroll": scroll, "scrolls": max_scroll > 0, "options": options,
            "dismiss_scrim": R(safe[0], safe[1], safe[2], sheet[1])}


# ---------------------------------------------------------------------------
# Transient double-tap / hold zones (video canvas only)

def transient_layout(safe: list, active: dict, layout142: dict, chrome: str) -> dict:
    if chrome == "shown":
        top_hits = [r for n, r in active.items() if n in ("back", "top-find-person", "top-find-similar", "top-details")]
        top = max([layout142["top_chrome_bottom"]] + [r[3] for r in top_hits]) + CANVAS_CLEARANCE
        bottom = layout142["bottom_chrome_top"] - CANVAS_CLEARANCE
    else:
        top, bottom = safe[1] + CANVAS_CLEARANCE, safe[3] - CANVAS_CLEARANCE
    left, right = safe[0] + CANVAS_CLEARANCE, safe[2] - CANVAS_CLEARANCE
    canvas = R(left, top, right, bottom)
    w = right - left
    a, b = left + w * SIDE_FRACTION, right - w * SIDE_FRACTION
    zones = {"rewind": R(left, top, a, bottom), "centre-zoom": R(a, top, b, bottom), "forward": R(b, top, right, bottom)}
    cy = (top + bottom) / 2

    def ind(zone, text, wd):
        cx = (zones[zone][0] + zones[zone][2]) / 2
        return {"text": text, "zone": zone, "rect": R(cx - wd / 2, cy - 16, cx + wd / 2, cy + 16)}

    cx = (left + right) / 2
    indicators = [ind("rewind", COPY["rewind"], 72), ind("forward", COPY["forward"], 72),
                  {"text": COPY["hold"], "zone": "hold-2x", "rect": R(cx - 28, top + 8, cx + 28, top + 40)}]
    return {"chrome": chrome, "canvas": canvas, "zones": zones, "hold_2x": canvas, "indicators": indicators,
            "fractions": [SIDE_FRACTION, r2(1 - 2 * SIDE_FRACTION), SIDE_FRACTION],
            "gesture_navigation_insets": GESTURE_NAV}


# ---------------------------------------------------------------------------

STATES = [
    # (state id, font, theme)
    ("playing", 1.0, "dark"),
    ("paused", 1.0, "light"),
    ("transient-chrome-shown", 1.0, "dark"),
    ("transient-chrome-hidden", 1.0, "light"),
    ("details", 1.0, "dark"),
    ("speed-sheet", 1.0, "light"),
    ("font200-details-permission", 2.0, "light"),
    ("font200-speed-sheet", 2.0, "dark"),
]


def state_model(view: str, vp142: dict, state: str, font: float) -> dict:
    v = vp142["viewport"]
    W, H = v["W"], v["H"]
    s = v["safe"]
    safe = R(s[0], s[1], W - s[2], H - s[3])
    cutouts = [from142(c) for c in v["cutouts"]]
    lay = vp142["scenarios"][SCENARIO[font]]["layout"]
    els = lay["elements"]
    base = base_hits(els)
    out = {"font_scale": font, "chrome": "hidden" if state == "transient-chrome-hidden" else "shown",
           "video_row": from142(els["video-row"]["box"]), "filmstrip": base["filmstrip-band"],
           "video_gap_to_strip": r2(base["filmstrip-band"][1] - from142(els["video-row"]["box"])[3])}
    inactive_reason = {}
    if state == "transient-chrome-hidden":
        active = {}
        inactive_reason = {n: "chrome hidden" for n in base}
    elif state in ("playing", "paused", "transient-chrome-shown"):
        active = dict(base)
    elif "details" in state:
        d = details_layout(v, font, safe, "permission" if "permission" in state else "ready")
        out["details"] = d
        active = {}
        for n, r in base.items():
            if overlaps(r, d["panel"]):
                inactive_reason[n] = "covered by Details surface"
            else:
                active[n] = r
        for row in d["rows"]:
            if row["kind"] == "button" and row["fully_visible"]:
                active[row["key"]] = row["shown"]
    else:  # speed sheet: modal, everything underneath inactive
        sp = speed_layout(v, font, safe, view)
        out["speed_sheet"] = sp
        inactive_reason = {n: "modal Playback speed sheet" for n in base}
        inactive_reason.update({"playback-speed": "modal Playback speed sheet", "save-current-frame": "modal Playback speed sheet"})
        active = {f"rate-{o['order']}": o["shown"] for o in sp["options"] if o["fully_visible"]}
    if state.startswith("transient"):
        out["transient"] = transient_layout(safe, active, lay, out["chrome"])
    out["active_hits"] = active
    out["inactive"] = inactive_reason
    return {"safe": safe, "cutouts": cutouts, **out}


def data() -> dict:
    src = load142()
    by_name = {vp["viewport"]["name"]: vp for vp in src["viewports"]}
    viewports = {}
    for key, name in VIEWPORTS.items():
        vp = by_name[name]
        v = vp["viewport"]
        W, H = v["W"], v["H"]
        viewports[key] = {
            "source_viewport": name, "source_kind": v["kind"], "dp": [W, H],
            "px": [int(W * DENSITY + 0.5), int(H * DENSITY + 0.5)],
            "safe_insets_ltrb": v["safe"], "cutouts": [from142(c) for c in v["cutouts"]],
            "states": {st: state_model(key, vp, st, font) for st, font, _ in STATES},
        }
    return {
        "status": STATUS,
        "source": {"file": "docs/design/viewer-142-target/target-geometry.json", "sha256": source_digest(),
                   "kind": "#142 PROPOSED target (insets copied from #616 three-button emulator measurements)"},
        "density_px_per_dp": DENSITY,
        "gesture_navigation_insets": GESTURE_NAV,
        "rate_choices_ordered_unranked": RATES,
        "copy": COPY,
        "constraints": {"minimum_semantic_hit_dp": HIT, "minimum_video_to_filmstrip_gap_dp": VIDEO_TO_STRIP_MIN,
                        "canvas_clearance_dp": CANVAS_CLEARANCE, "side_fraction": SIDE_FRACTION,
                        "centre_fraction": r2(1 - 2 * SIDE_FRACTION), "transient_ms": TRANSIENT_MS,
                        "pager_settle_ms": SETTLE_MS, "details_fraction": DETAILS_FRACTION,
                        "sheet_top_scrim_min_dp": SHEET_TOP_SCRIM_MIN, "text_em_estimate": TEXT_EM},
        "viewports": viewports,
    }


# ---------------------------------------------------------------------------
# SVG

THEMES = {
    "dark": {"bg": "#000000", "ink": "#ffffff", "muted": "#b9bcc8", "slab": "#1d2f6f", "pill": "#202228",
             "panel": "#000000", "sheet": "#17181d", "line": "#3a3d48", "accent": "#8fa2ff"},
    "light": {"bg": "#000000", "ink": "#ffffff", "muted": "#b9bcc8", "slab": "#1d2f6f", "pill": "#202228",
              "panel": "#000000", "sheet": "#f4f2ee", "line": "#cfcbc3", "accent": "#2f4bd0"},
}


def esc(s: str) -> str:
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def box(r, fill="none", stroke="none", dash="", rx=6, sw=1, opacity=None):
    x1, y1, x2, y2 = r
    extra = (f' stroke-dasharray="{dash}"' if dash else "") + (f' opacity="{opacity}"' if opacity else "")
    return (f'<rect x="{r2(x1)}" y="{r2(y1)}" width="{r2(x2 - x1)}" height="{r2(y2 - y1)}" rx="{rx}" '
            f'fill="{fill}" stroke="{stroke}" stroke-width="{sw}"{extra}/>')


def txt(x, y, s, fill, size, weight="400", anchor="start"):
    return (f'<text x="{r2(x)}" y="{r2(y)}" fill="{fill}" font-family="Funnel Sans, sans-serif" '
            f'font-size="{r2(size)}" font-weight="{weight}" text-anchor="{anchor}">{esc(s)}</text>')


def svg(view: str, vdata: dict, state: str, theme: str) -> str:
    t = THEMES[theme]
    W, H = vdata["dp"]
    pw, ph = vdata["px"]
    m = vdata["states"][state]
    sheet_ink = "#17171b" if theme == "light" else t["ink"]
    sheet_muted = "#5b5d66" if theme == "light" else t["muted"]
    title = f"VIDEO-1T proposed {view} {state} ({theme})"
    o = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{pw}" height="{ph}" viewBox="0 0 {W:g} {H:g}" role="img" aria-labelledby="title desc">',
         f'<title id="title">{esc(title)}</title>',
         '<desc id="desc">Dimensioned PROPOSED design mockup generated from #142 geometry; not a screenshot, runtime capture or Samsung placement claim. Gesture-navigation insets UNKNOWN (#627).</desc>',
         box([0, 0, W, H], t["bg"], rx=0),
         # media placeholder: a mid-grey video frame on the Quiet Frame black ground
         box([m["safe"][0], m["safe"][1] + 40, m["safe"][2], m["video_row"][1] - 4], "#3b4150", rx=0, opacity="0.55"),
         box(m["safe"], stroke="#38d996", dash="6 3", rx=0)]
    for c in m["cutouts"]:
        o.append(box(c, "#ff5277", rx=4, opacity="0.6"))
    active = m["active_hits"]
    font = m["font_scale"]

    # #142 chrome (drawn even when inactive so covering is visible)
    def chrome_hit(name, r, label=None):
        is_active = name in active
        o.append(box(r, "#ffffff10" if is_active else "none", "#9fb0ff" if is_active else "#6b6f7b",
                     "" if is_active else "3 3", rx=8))
        if label:
            o.append(txt((r[0] + r[2]) / 2, (r[1] + r[3]) / 2 + 4, label, t["ink"] if is_active else "#8a8e99", 11, anchor="middle"))

    if m["chrome"] == "shown":
        band = m["filmstrip"]
        o.append(box(band, "#111216", rx=0))
        for n, r in vdata["_base"][font].items():
            if n == "filmstrip-band":
                chrome_hit(n, r)
            elif is_tile(n):
                vis = [r[0] + 2, r[1] + 16, r[2] - 2, r[3] - 4]
                o.append(box(vis, "#5d6270", rx=8))
                if n == "filmstrip-tile-0":
                    o.append(box([r[0], r[1] + 14, r[2], r[3] - 2], stroke="#ffffff", sw=2, rx=9))
                    o.append(f'<circle cx="{r2((r[0] + r[2]) / 2)}" cy="{r2(r[1] + 8)}" r="4" fill="#3d5afe"/>')
            elif n.startswith("action-"):
                o.append(box([r[0] - 4, r[1] - 4, r[2] + 4, r[3] + 4], t["slab"], rx=20, opacity="0.9"))
                chrome_hit(n, r, {"action-favourite": "♡", "action-edit": "✎", "action-share": "↗", "action-delete": "🗑"}[n])
            elif n == "video-play":
                chrome_hit(n, r, "Ⅱ" if state != "paused" else "▶")
            elif n == "video-seek":
                chrome_hit(n, r)
                cy = (r[1] + r[3]) / 2
                o.append(f'<line x1="{r2(r[0] + 8)}" y1="{r2(cy)}" x2="{r2(r[2] - 8)}" y2="{r2(cy)}" stroke="#ffffff" stroke-width="4" stroke-linecap="round"/>')
            else:
                o.append(box([r[0] + 4, r[1] + 4, r[2] - 4, r[3] - 4], t["pill"], rx=20, opacity="0.8"))
                chrome_hit(n, r, {"back": "←", "top-find-person": "◎", "top-find-similar": "≈", "top-details": "ⓘ"}[n])

    if "transient" in m:
        tr = m["transient"]
        o.append(box(tr["canvas"], stroke="#72dcff", dash="2 2", rx=0))
        for zn, z in tr["zones"].items():
            col = "#ffca55" if zn != "centre-zoom" else "#72dcff"
            o.append(box(z, col + "22", col, rx=0))
            o.append(txt((z[0] + z[2]) / 2, z[3] - 8, {"rewind": "double-tap −10 s (35%)", "centre-zoom": "double-tap zoom (30%)",
                                                      "forward": "double-tap +10 s (35%)"}[zn], t["ink"], 9, anchor="middle"))
        for ind in tr["indicators"]:
            o.append(box(ind["rect"], "#000000aa", "#ffffff", rx=16))
            o.append(txt((ind["rect"][0] + ind["rect"][2]) / 2, ind["rect"][3] - 10, ind["text"], "#ffffff", 14, "600", "middle"))
        o.append(txt(tr["canvas"][0] + 4, tr["canvas"][1] + 12,
                     f"hold anywhere on canvas while playing = 2× · chrome {m['chrome']}", t["muted"], 9))

    if "details" in m:
        d = m["details"]
        o.append(box(d["panel"], t["panel"], "#444857", rx=0))
        o.append(box(d["viewport"], stroke="#38d996", dash="2 2", rx=0))
        clip = f'clip-{view}-{state}'
        o.append(f'<clipPath id="{clip}"><rect x="{d["viewport"][0]}" y="{d["viewport"][1]}" width="{r2(d["viewport"][2] - d["viewport"][0])}" height="{r2(d["viewport"][3] - d["viewport"][1])}"/></clipPath>')
        o.append(f'<g clip-path="url(#{clip})">')
        for row in d["rows"]:
            r = row["shown"]
            x = r[0] + 12
            if row["kind"] == "text":
                o.append(txt(x, r[1] + row["sp"], row["lines"][0], t["muted"], row["sp"], "600"))
            elif row["key"] == "playback-speed":
                o.append(box(r, "#ffffff08", "#9fb0ff", rx=8, sw=1.5))
                y = r[1] + ROW_PAD_Y
                for ln in row["label_lines"]:
                    y += row["label_lh"]
                    o.append(txt(x, y - row["label_lh"] * 0.25, ln, t["muted"], row["label_sp"]))
                y += ROW_GAP
                for ln in row["value_lines"]:
                    y += row["value_lh"]
                    o.append(txt(x, y - row["value_lh"] * 0.25, ln, t["ink"], row["value_sp"]))
                o.append(txt(r[2] - 12, (r[1] + r[3]) / 2 + 5, "›", t["ink"], 18 * font, anchor="end"))
            elif row["key"] == "save-current-frame":
                o.append(box(r, "#ffffff08", "#9fb0ff", rx=8, sw=1.5))
                y = r[1] + ROW_PAD_Y
                for ln in row["action_lines"]:
                    y += row["value_lh"]
                    o.append(txt(x, y - row["value_lh"] * 0.25, ln, t["ink"], row["value_sp"], "600"))
                if row["support_lines"]:
                    y += ROW_GAP
                    for ln in row["support_lines"]:
                        y += row["support_lh"]
                        o.append(txt(x, y - row["support_lh"] * 0.25, ln, t["muted"], row["support_sp"]))
            else:
                o.append(box(r, "none", "#555a68", "4 3", rx=4))
                for k, ln in enumerate(row["lines"]):
                    o.append(txt(x, r[1] + 6 + (k + 1) * row["lh"], ln, "#8a8e99", row["sp"]))
        o.append('</g>')
        note = f"Details surface 42% of height · scroll {d['scroll']}/{d['max_scroll']} dp"
        o.append(txt(d["viewport"][0] + 4, d["panel"][1] - 6, note, t["muted"], 10))

    if "speed_sheet" in m:
        sp = m["speed_sheet"]
        o.append(box([0, 0, W, H], "#000000", rx=0, opacity="0.55"))
        o.append(box(sp["sheet"], t["sheet"], t["line"], rx=16))
        y = sp["sheet"][1] + SHEET_PAD
        for ln in sp["title_lines"]:
            y += sp["title_lh"]
            o.append(txt(sp["sheet"][0] + 24, y - sp["title_lh"] * 0.25, ln, sheet_ink, sp["title_sp"], "600"))
        lv = sp["list_viewport"]
        clip = f'clip-{view}-{state}'
        o.append(f'<clipPath id="{clip}"><rect x="{lv[0]}" y="{lv[1]}" width="{r2(lv[2] - lv[0])}" height="{r2(lv[3] - lv[1])}"/></clipPath>')
        o.append(f'<g clip-path="url(#{clip})">')
        for opt in sp["options"]:
            r = opt["shown"]
            o.append(box(r, t["accent"] + "22" if opt["selected"] else "none", t["accent"] if opt["selected"] else t["line"], rx=8))
            cy = (r[1] + r[3]) / 2
            o.append(f'<circle cx="{r2(r[2] - 12 - RADIO / 2)}" cy="{r2(cy)}" r="{RADIO / 2 - 3}" fill="{t["accent"] if opt["selected"] else "none"}" stroke="{t["accent"] if opt["selected"] else sheet_muted}" stroke-width="2"/>')
            ln_y = cy - (len(opt["lines"]) - 1) * sp["option_lh"] / 2 + sp["option_sp"] * 0.35
            for k, ln in enumerate(opt["lines"]):
                o.append(txt(r[0] + 12, ln_y + k * sp["option_lh"], ln, sheet_ink, sp["option_sp"]))
        o.append('</g>')
        o.append(txt(sp["sheet"][2] - 16, sp["sheet"][1] + 18, f"{sp['columns']} col · options {sp['option_height']}dp", sheet_muted, 9, anchor="end"))

    o.append(txt(8, 12, f"PROPOSED · NOT RUNTIME · {W:g}×{H:g}dp / {pw}×{ph}px · font {int(font * 100)}% · {state}", "#ffffff", 9))
    o.append(txt(8, 22, "green=safe · red=cutout · solid outline=active 48dp hit · dashed grey=inactive · gesture-nav insets UNKNOWN (#627)", "#ffffff", 8))
    o.append('</svg>')
    return "".join(o) + "\n"


# ---------------------------------------------------------------------------
# Tables

def fmt(r) -> str:
    return f"{r[0]:g},{r[1]:g} → {r[2]:g},{r[3]:g} ({r2(r[2] - r[0]):g}×{r2(r[3] - r[1]):g})"


def tables(d: dict) -> str:
    L = ["<!-- Generated by generate.py from ../viewer-142-target/target-geometry.json; do not edit by hand. -->",
         "# VIDEO-1T inspectable geometry (PROPOSED)", "",
         f"Source: `{d['source']['file']}` sha256 `{d['source']['sha256'][:16]}…` ({d['source']['kind']}).",
         "Rectangles are dp, left,top → right,bottom (width×height). Gesture-navigation insets: **UNKNOWN (#627)**; no zone below subtracts invented gesture insets.", "",
         "## V1. Viewports", "",
         "| Viewport | #142 source | dp / px | safeDrawing L/T/R/B | Safe rect | Cutout | Filmstrip band | Video row | Video → strip gap (≥8) |",
         "|---|---|---|---|---|---|---|---|---|"]
    for n, v in d["viewports"].items():
        s = v["states"]["playing"]
        L.append(f"| {n} | {v['source_viewport']} ({v['source_kind']}) | {v['dp'][0]:g}×{v['dp'][1]:g} / {v['px'][0]}×{v['px'][1]} | "
                 f"{v['safe_insets_ltrb']} | {fmt(s['safe'])} | {fmt(v['cutouts'][0])} | {fmt(s['filmstrip'])} | {fmt(s['video_row'])} | {s['video_gap_to_strip']:g} |")
    L += ["", "## V2. Active semantic hits per state", "",
          "Only hits that can receive input in that state are listed and collision-checked; covered or modal-hidden controls are listed as inactive with the reason.", ""]
    for n, v in d["viewports"].items():
        for st, _, _ in STATES:
            m = v["states"][st]
            L += [f"### {n} · {st} (font {int(m['font_scale'] * 100)}%)", "", "| Active hit | Rectangle |", "|---|---|"]
            for k, r in m["active_hits"].items():
                L.append(f"| {k} | {fmt(r)} |")
            if not m["active_hits"]:
                L.append("| (none: chrome hidden, canvas gestures only) | — |")
            if m["inactive"]:
                reasons = sorted(set(m["inactive"].values()))
                for reason in reasons:
                    names = [k for k, x in m["inactive"].items() if x == reason]
                    L.append(f"\nInactive ({reason}): {', '.join(names)}.")
            L.append("")
    L += ["## V3. Details surface (existing component, VIDEO-1 rows at the top)", "",
          "| Viewport | Font | Panel | Safe viewport | Content h | Viewport h | Scrolls | Scroll shown / max | Row | Rect at scroll | Lines | Fully visible | Reachable |",
          "|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
    for n, v in d["viewports"].items():
        for st in ("details", "font200-details-permission"):
            dd = v["states"][st]["details"]
            for row in dd["rows"]:
                if row["kind"] != "button":
                    continue
                lines = (row.get("label_lines", []) + row.get("value_lines", [])) or (row.get("action_lines", []) + row.get("support_lines", []))
                L.append(f"| {n} | {int(v['states'][st]['font_scale'] * 100)}% | {fmt(dd['panel'])} | {fmt(dd['viewport'])} | {dd['content_height']:g} | "
                         f"{dd['viewport_height']:g} | {'yes' if dd['scrolls'] else 'no'} | {dd['scroll']:g} / {dd['max_scroll']:g} | {row['key']} | "
                         f"{fmt(row['shown'])} | {' / '.join(lines)} ({len(lines)}) | {'yes' if row['fully_visible'] else 'no'} | {'yes' if row['reachable'] else 'no'} |")
    L += ["", "## V4. Playback speed sheet (modal, single choice, listed order, no ranking)", "",
          "| Viewport | Font | Sheet | Columns | Option h | Title sp | Option sp | List viewport | Scrolls | First option | Last option |",
          "|---|---|---|---|---|---|---|---|---|---|---|"]
    for n, v in d["viewports"].items():
        for st in ("speed-sheet", "font200-speed-sheet"):
            sp = v["states"][st]["speed_sheet"]
            L.append(f"| {n} | {int(v['states'][st]['font_scale'] * 100)}% | {fmt(sp['sheet'])} | {sp['columns']} | {sp['option_height']:g} | {sp['title_sp']:g} | "
                     f"{sp['option_sp']:g} | {fmt(sp['list_viewport'])} | {'yes' if sp['scrolls'] else 'no'} | {sp['options'][0]['rate']} {fmt(sp['options'][0]['shown'])} | "
                     f"{sp['options'][-1]['rate']} {fmt(sp['options'][-1]['shown'])} |")
    L += ["", "## V5. Transient canvas zones (double-tap −10 s / zoom / +10 s, hold 2×)", "",
          "Zones are the safe video canvas minus top chrome, the video row, filmstrip and action slab (chrome shown), or the safe rect (chrome hidden), each with an 8dp clearance. For letterboxed video, implementation intersects them with the displayed video rect and keeps the 35/30/35 split of that width.", "",
          "| Viewport | Chrome | Canvas | −10 s zone | Zoom zone | +10 s zone | 2× indicator |", "|---|---|---|---|---|---|---|"]
    for n, v in d["viewports"].items():
        for st in ("transient-chrome-shown", "transient-chrome-hidden"):
            tr = v["states"][st]["transient"]
            z = tr["zones"]
            L.append(f"| {n} | {tr['chrome']} | {fmt(tr['canvas'])} | {fmt(z['rewind'])} | {fmt(z['centre-zoom'])} | {fmt(z['forward'])} | {fmt(tr['indicators'][2]['rect'])} |")
    return "\n".join(L) + "\n"


def svg_files() -> list[tuple[str, str, str]]:
    return [(f"svg/{view}-{state}.svg", view, state) for view in VIEWPORTS for state, _, _ in STATES]


def outputs() -> dict:
    d = data()
    src = load142()
    by_name = {vp["viewport"]["name"]: vp for vp in src["viewports"]}
    files = {"geometry.json": json.dumps(d, ensure_ascii=False, indent=1) + "\n", "tables.md": tables(d)}
    themes = {st: th for st, _, th in STATES}
    for path, view, state in svg_files():
        vdata = dict(d["viewports"][view])
        vdata["_base"] = {f: base_hits(by_name[VIEWPORTS[view]]["scenarios"][SCENARIO[f]]["layout"]["elements"]) for f in SCENARIO}
        files[path] = svg(view, vdata, state, themes[state])
    return files


if __name__ == "__main__":
    for name, content in outputs().items():
        path = ROOT / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8", newline="\n")
