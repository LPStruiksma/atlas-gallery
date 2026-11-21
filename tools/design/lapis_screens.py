"""Reusable synthetic Lapis screens (Quiet Frame v3). Each builder draws on a Screen."""

from __future__ import annotations

import math
import random

from lapis_kit import (
    ANNO,
    DENSITY,
    DEVICE_W,
    VIEWER_SLAB,
    dashed_rect,
    VIEWER_SPHERE,
    Screen,
    justified_rows,
    placeholder,
    px,
)

SIDE = 16  # dp page margin
GAP = 2  # dp grid gap


# ---------------------------------------------------------------------------
# Header / title
# ---------------------------------------------------------------------------
def title(s: Screen, text: str, sub: str | None = None, y_dp: float = 12) -> int:
    y = s.top + px(y_dp)
    f = s.font("title")
    avail = DEVICE_W - px(2 * SIDE)
    lh = s.line_h("title")
    if s.d.textlength(text, font=f) > avail:
        # One-word titles cannot wrap: fit to the width (Android 14+ also scales large text non-linearly).
        size = f.size * avail / s.d.textlength(text, font=f)
        f = s.fonts.get("display", 700, size)
        lh = int(size * 44 / 38)
        s.note((DEVICE_W - px(8), y - px(10)), "title fitted to width")
    s.d.text((px(SIDE), y), text, font=f, fill=s.t["brand_text"])
    y += lh
    if sub:
        y = s.wrap(px(SIDE), y + px(2), sub, DEVICE_W - px(2 * SIDE), "secondary", fill=s.t["ink2"])
    return y + px(8)


def finger(s: Screen, start, end=None, label=None):
    """Illustrative gesture (annotation layer), never a captured touch."""
    d = s.d
    x0, y0 = start
    if end:
        d.line([x0, y0, end[0], end[1]], fill=ANNO, width=px(3))
        ang = math.atan2(end[1] - y0, end[0] - x0)
        for da in (2.6, -2.6):
            d.line([end[0], end[1], end[0] + px(10) * math.cos(ang + da), end[1] + px(10) * math.sin(ang + da)],
                   fill=ANNO, width=px(3))
    r = px(16)
    d.ellipse([x0 - r, y0 - r, x0 + r, y0 + r], outline=ANNO, width=px(3))
    if label:
        f = s.fonts.get("sans", 600, px(10))
        d.text((x0 + r + px(4), y0), label, font=f, fill=ANNO, anchor="lm")


# ---------------------------------------------------------------------------
# Pictures timeline
# ---------------------------------------------------------------------------
def square_grid(s: Screen, y: int, y_end: int, cols: int = 4, seed: int = 1, header: str | None = None,
                selected: dict[int, int] | None = None, videos: set[int] | None = None,
                headers_every: int | None = None, month_names=None) -> dict[int, tuple]:
    """Month-zoom square tiles with 2 dp gaps. Returns index -> box."""
    boxes = {}
    tile = (DEVICE_W - px(GAP) * (cols - 1)) / cols
    i = 0
    yy = y
    hdr_i = 0
    months = month_names or ["November 2026", "October 2026", "September 2026", "August 2026"]
    while yy < y_end:
        if header and (i == 0 or (headers_every and i % headers_every == 0)):
            label = header if i == 0 else months[min(hdr_i, len(months) - 1)]
            hdr_i += 1
            s.text((px(SIDE), yy + px(10)), label, "date")
            yy += s.line_h("date") + px(16)
        for c in range(cols):
            x0 = c * (tile + px(GAP))
            box = (x0, yy, x0 + tile, yy + tile)
            if yy < y_end:
                s.photo(box, seed * 97 + i)
                boxes[i] = box
                if videos and i in videos:
                    video_badge(s, box, "0:14")
                if selected and i in selected:
                    select_sphere(s, box, selected[i])
            i += 1
        yy += tile + px(GAP)
    return boxes


def video_badge(s: Screen, box, dur: str):
    x1, y1 = box[2], box[3]
    f = s.fonts.get("sans", 600, px(12))
    w = s.d.textlength(dur, font=f) + px(26)
    b = [x1 - w - px(6), y1 - px(26), x1 - px(6), y1 - px(6)]
    s.d.rounded_rectangle(b, radius=px(10), fill=(0, 0, 0, 150))
    tx = b[0] + px(8)
    s.d.polygon([(tx, b[1] + px(5)), (tx, b[3] - px(5)), (tx + px(8), (b[1] + b[3]) / 2)], fill="#FFFFFF")
    s.d.text((tx + px(12), (b[1] + b[3]) / 2), dur, font=f, fill="#FFFFFF", anchor="lm")


def select_sphere(s: Screen, box, n: int | None, r_dp: float = 12, inset_dp: float = 8, target: str = "sphere"):
    """Selection: lapis sphere on the tile corner with a 2 dp white halo + order number.

    ``target="tile"`` records the whole tile as the touch target as it is (selection mode toggles on a
    tile tap), so an undersized tile is flagged on the board; ``None`` records nothing."""
    r = px(r_dp)
    cx, cy = box[0] + px(inset_dp) + r, box[1] + px(inset_dp) + r
    s.d.rectangle(box, fill=(255, 255, 255, 40))
    s.sphere((cx, cy), r, halo_dp=2, label=str(n) if n else None, label_role="nav")
    if target == "sphere":
        s.target([cx - r, cy - r, cx + r, cy + r], f"select {n}")
    elif target == "tile":
        s.target(box, f"select {n}", actual=True)


def fast_thumb(s: Screen, frac: float, month: str, y0: int, y1: int, dragging=False):
    """46 dp held sphere at the right edge inside a 48 dp effective target."""
    r = px(23)
    cy = y0 + r + (y1 - y0 - 2 * r) * frac
    cx = DEVICE_W - px(6) - r
    if dragging:
        s.d.line([cx, y0, cx, y1], fill=(*_rgb(s.t["ink2"]), 90), width=px(2))
    s.sphere((cx, cy), r, label=month, label_role="secondary")
    s.target([cx - px(24), cy - px(24), cx + px(24), cy + px(24)], "month thumb")
    return (cx, cy)


def _rgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def pictures(s: Screen, *, seed=1, selected=None, thumb=None, header="Today", sub=None, hint=False,
             slab=True, active="Pictures", videos=None) -> tuple[int, int]:
    s.status_bar()
    y = title(s, "Pictures", sub)
    if hint:
        s.text((px(SIDE), y - px(4)), "Pull down for your atlas", "secondary", fill=s.t["ink2"])
        y += s.line_h("secondary") + px(4)
    bottom = s.bottom
    square_grid(s, y, bottom, seed=seed, header=header, selected=selected, videos=videos, headers_every=12)
    if thumb:
        fast_thumb(s, thumb[0], thumb[1], y + px(8), bottom - px(110), dragging=len(thumb) > 2 and thumb[2])
    if slab:
        s.slab(active)
    s.gesture_bar()
    return y, bottom


# ---------------------------------------------------------------------------
# Justified day rows (L8)
# ---------------------------------------------------------------------------
DAY_RATIOS = [1.5, 0.75, 1.0, 4.2, 0.56, 1.33, 1.78, 0.8, 1.5, 0.45, 1.0, 1.33, 2.4, 0.75, 1.5, 1.0]


def day_rows(s: Screen, y: int, y_end: int, ratios=None, seed=5, target_dp=118, videos=(6,),
             selected=None, headers=("Saturday 14 November", "Friday 13 November"), split=9,
             anchor: int | None = None) -> dict[int, tuple]:
    ratios = ratios or DAY_RATIOS
    boxes: dict[int, tuple] = {}
    groups = [ratios[:split], ratios[split:]]
    base = 0
    yy = y
    for gi, grp in enumerate(groups):
        if yy >= y_end:
            break
        s.text((px(SIDE), yy + px(10)), headers[gi], "date")
        yy += s.line_h("date") + px(16)
        # panorama cap: a ratio wider than one full row renders at full width
        rows = justified_rows(list(grp), DEVICE_W, px(target_dp), px(GAP))
        for row in rows:
            x = 0.0
            h = row[0][2]
            if len(row) == 1 and row[0][1] >= DEVICE_W - 1:
                pass  # panorama on its own full-width row
            for j, w, _h in row:
                idx = base + j
                box = (x, yy, x + w, yy + h)
                if yy < y_end:
                    s.photo(box, seed * 31 + idx)
                    boxes[idx] = box
                    if idx in (videos or ()):
                        video_badge(s, box, "1:02")
                    if selected and idx in selected:
                        select_sphere(s, box, selected[idx])
                    if anchor is not None and idx == anchor:
                        s.d.rectangle(box, outline=ANNO, width=px(3))
                x += w + px(GAP)
            yy += h + px(GAP)
        base += len(grp)
    return boxes


# ---------------------------------------------------------------------------
# Collections (L4 legacy sections, L5 rows)
# ---------------------------------------------------------------------------
STRIP_RATIOS = [(0.75, 1.5, 1.0, 0.56), (1.33, 0.8, 1.78, 1.0), (1.0, 0.66, 1.5, 0.75), (1.78, 1.0, 0.75, 1.33)]


def strip(s: Screen, x: int, y: int, h: int, ratios, seed: int, max_w: int) -> int:
    """Latest photos at real shape. If they do not fit at height h, the strip height shrinks
    (never below 56 dp) so every photo stays whole: no crop, no letterbox."""
    gap = px(4)
    total = sum(ratios)
    fit_h = (max_w - gap * (len(ratios) - 1)) / total
    h = int(max(px(56), min(h, fit_h)))
    xx = x
    for k, ar in enumerate(ratios):
        w = int(h * ar)
        if xx + w > x + max_w:
            break
        s.photo((xx, y, xx + w, y + h), seed + k, radius_dp=8)
        xx += w + gap
    return xx


def collection_row(s: Screen, y: int, name: str, line2: str, ratios, seed: int, *, empty_text=None,
                   chevron=True, focus_label=None) -> int:
    """Row: name, plain-language count/date, strip of latest real-shape photos (radius 8)."""
    x = px(SIDE)
    text_w = DEVICE_W - px(2 * SIDE) - px(24)
    y2 = s.wrap(x, y, name, text_w, "section", max_lines=2)
    y2 = s.wrap(x, y2, line2, text_w, "secondary", fill=s.t["ink2"], max_lines=2)
    sh = px(72)
    y3 = y2 + px(8)
    if empty_text:
        s.rrect([x, y3, DEVICE_W - px(SIDE), y3 + sh], 8, fill=s.t["surface"])
        s.wrap(x + px(12), y3 + px(14), empty_text, DEVICE_W - px(2 * SIDE) - px(24), "secondary",
               fill=s.t["ink2"], max_lines=2)
        bottom = y3 + sh
    elif ratios:
        strip(s, x, y3, sh, ratios, seed, DEVICE_W - px(2 * SIDE))
        bottom = y3 + sh
    else:
        bottom = y2
    if chevron:
        cx, cy = DEVICE_W - px(SIDE) - px(6), y + px(10)
        s.d.line([cx - px(5), cy - px(6), cx, cy, cx - px(5), cy + px(6)], fill=s.t["ink2"], width=px(2), joint="curve")
    s.target([0, y - px(6), DEVICE_W, bottom + px(6)], name)
    if focus_label:
        s.focus_order((px(6), y + px(10)), focus_label)
    return bottom + px(20)


def collections_legacy(s: Screen) -> None:
    """L4 interim: Collections hosts today's Albums, Stories and People screens as sections."""
    s.status_bar()
    y = title(s, "Collections")
    for name, line, seed in (("Albums", "Existing Albums screen, 24 albums", 11),
                             ("Stories", "Existing Stories screen, 8 stories", 23),
                             ("People", "Existing People screen, 132 people", 37)):
        s.text((px(SIDE), y), name, "section")
        y += s.line_h("section")
        y = s.wrap(px(SIDE), y, line, DEVICE_W - px(2 * SIDE), "secondary", fill=s.t["ink2"]) + px(8)
        tile = px(104)
        for k in range(3):
            x0 = px(SIDE) + k * (tile + px(8))
            s.photo((x0, y, x0 + tile, y + tile), seed + k, radius_dp=8)
        s.target([0, y - px(48), DEVICE_W, y + tile + px(4)], name)
        y += tile + px(24)
    s.note((DEVICE_W - px(8), y), "interim until L5: legacy screens unchanged, entered from here")
    s.slab("Collections")
    s.gesture_bar()


# ---------------------------------------------------------------------------
# Viewer
# ---------------------------------------------------------------------------
VIEWER_ACTIONS = ["Favourite", "Edit", "Share", "Delete", "More"]


def viewer(s: Screen, seed=7, current=3, n=7) -> None:
    s.status_bar(light_icons=True)
    pw = DEVICE_W
    ph = int(pw / 1.33)
    py = (s.bottom - px(180)) // 2 - ph // 2 + s.top
    s.photo((0, py, pw, py + ph), seed)
    # filmstrip
    fh = px(52)
    fy = s.bottom - px(12) - px(64) - px(20) - fh
    fw = px(40)
    total = n * fw + (n - 1) * px(4)
    x = (DEVICE_W - total) // 2
    for i in range(n):
        box = (x, fy, x + fw, fy + fh)
        s.photo(box, seed + i - current, radius_dp=4)
        if i == current:
            s.d.rounded_rectangle([box[0] - px(2), box[1] - px(2), box[2] + px(2), box[3] + px(2)], radius=px(6),
                                  outline="#FFFFFF", width=px(2))
            cx = (box[0] + box[2]) / 2
            s.d.ellipse([cx - px(5), fy - px(16), cx + px(5), fy - px(6)], fill=VIEWER_SPHERE)
        s.target(box, "filmstrip item")
        x += fw + px(4)
    # action slab (#3448E0)
    sw, sh = px(320), px(64)
    y1 = s.bottom - px(12)
    x0 = (DEVICE_W - sw) // 2
    s.d.rounded_rectangle([x0, y1 - sh, x0 + sw, y1], radius=px(24), fill=VIEWER_SLAB)
    step = sw / len(VIEWER_ACTIONS)
    for i, a in enumerate(VIEWER_ACTIONS):
        cx = x0 + step * (i + 0.5)
        cy = y1 - sh / 2
        glyph(s, a, (cx, cy))
        s.target([cx - px(24), cy - px(24), cx + px(24), cy + px(24)], a)
    s.gesture_bar(light=True)


def glyph(s: Screen, name: str, c, col="#FFFFFF"):
    cx, cy = c
    d = s.d
    w = px(2)
    if name == "Favourite":
        d.polygon([(cx, cy + px(8)), (cx - px(9), cy - px(1)), (cx - px(5), cy - px(7)), (cx, cy - px(3)),
                   (cx + px(5), cy - px(7)), (cx + px(9), cy - px(1))], outline=col, width=w)
    elif name == "Edit":
        d.line([cx - px(7), cy + px(7), cx + px(7), cy - px(7)], fill=col, width=px(3))
    elif name == "Share":
        for dx, dy in ((-6, 0), (6, -6), (6, 6)):
            d.ellipse([cx + px(dx) - px(3), cy + px(dy) - px(3), cx + px(dx) + px(3), cy + px(dy) + px(3)], fill=col)
        d.line([cx - px(6), cy, cx + px(6), cy - px(6)], fill=col, width=w)
        d.line([cx - px(6), cy, cx + px(6), cy + px(6)], fill=col, width=w)
    elif name == "Delete":
        d.rectangle([cx - px(6), cy - px(5), cx + px(6), cy + px(8)], outline=col, width=w)
        d.line([cx - px(8), cy - px(7), cx + px(8), cy - px(7)], fill=col, width=w)
    else:
        for dx in (-7, 0, 7):
            d.ellipse([cx + px(dx) - px(2), cy - px(2), cx + px(dx) + px(2), cy + px(2)], fill=col)


# ---------------------------------------------------------------------------
# Loading / empty / error
# ---------------------------------------------------------------------------
def loading_arc(s: Screen, y: int, frac: float, text: str) -> int:
    """Held sphere climbing an arc beside a plain count. No banner."""
    cx, cy, R = px(SIDE) + px(22), y + px(26), px(20)
    s.d.arc([cx - R, cy - R, cx + R, cy + R], 180, 360, fill=s.t["surface"], width=px(3))
    s.d.arc([cx - R, cy - R, cx + R, cy + R], 180, 180 + 180 * frac, fill=s.t["brand_fill"], width=px(3))
    a = math.radians(180 + 180 * frac)
    s.sphere((cx + R * math.cos(a), cy + R * math.sin(a)), px(6))
    s.wrap(cx + R + px(14), cy - px(10), text, DEVICE_W - (cx + R + px(14)) - px(SIDE), "secondary",
           fill=s.t["ink2"])
    return cy + px(30)


def message(s: Screen, y: int, line1: str, line2: str, button: str | None = None) -> int:
    y = s.wrap(px(SIDE), y, line1, DEVICE_W - px(2 * SIDE), "body")
    y = s.wrap(px(SIDE), y + px(2), line2, DEVICE_W - px(2 * SIDE), "secondary", fill=s.t["ink2"])
    if button:
        f = s.font("nav")
        w = s.d.textlength(button, font=f) + px(32)
        h = max(px(48), s.line_h("nav") + px(20))
        b = [px(SIDE), y + px(12), px(SIDE) + w, y + px(12) + h]
        s.rrect(b, 24, fill=s.t["brand_fill"])
        s.d.text(((b[0] + b[2]) / 2, (b[1] + b[3]) / 2), button, font=f, fill=s.t["on_brand"], anchor="mm")
        s.target(b, button)
        y = b[3]
    return y + px(16)


# ---------------------------------------------------------------------------
# Search (L4 entry, L7 full flow)
# ---------------------------------------------------------------------------
def _fit(s: Screen, text: str, font, max_w: float) -> str:
    if s.d.textlength(text, font=font) <= max_w:
        return text
    while text and s.d.textlength(text + "…", font=font) > max_w:
        text = text[:-1]
    return text + "…"


def _chip_rows(s: Screen, labels, avail, font, gap):
    rows, cur, w = [], [], 0
    for c in labels:
        cw = s.d.textlength(c, font=font) + px(28)
        if cur and w + gap + cw > avail:
            rows.append(cur)
            cur, w = [], 0
        cur.append((c, cw))
        w += cw + (gap if len(cur) > 1 else 0)
    if cur:
        rows.append(cur)
    return rows


ENTRY_CHIPS = ["Faces", "With a picture", "Details"]


def search_bar(s: Screen, y_bottom: int, query: str = "", focused=True, scope="This screen", items=None,
               thumbs=False, entries=False, seed=60) -> int:
    """Expanded AtlasSearch panel directly above the slab (whose top is ``y_bottom``).

    Bottom-up: query bar, scope chips (wrap to two rows when they do not fit), optional entry
    chips (faces, picture, details), then suggestion rows as many as fit below the status bar.
    Returns the panel's top y."""
    side = px(8)
    inner_l, inner_r = side + px(8), DEVICE_W - side - px(8)
    avail = inner_r - inner_l
    f_nav = s.font("nav")
    gap = px(8)
    bar_h = max(px(52), s.line_h("body") + px(24))
    chip_h = max(px(48), s.line_h("nav") + px(14))
    row_h = max(px(48), s.line_h("body") + px(14))
    scope_rows = _chip_rows(s, ["This screen", "Whole library"], avail, f_nav, gap)
    entry_rows = _chip_rows(s, ENTRY_CHIPS, avail, f_nav, gap) if entries else []
    panel_bottom = y_bottom - px(6)
    fixed = px(8) + bar_h + px(8) + len(scope_rows) * (chip_h + px(6)) + len(entry_rows) * (chip_h + px(6))
    room = panel_bottom - fixed - (s.top + px(8)) - px(8)
    items = list(items or [])
    n_rows = max(0, min(len(items), int(room // row_h)))
    shown = items[:n_rows]
    clipped = len(items) - n_rows
    panel_top = panel_bottom - fixed - n_rows * row_h - (px(8) if n_rows else 0)
    s.d.rounded_rectangle([side, panel_top, DEVICE_W - side, panel_bottom], radius=px(22), fill=s.t["surface"])
    # query bar
    y1 = panel_bottom - px(8)
    y0 = y1 - bar_h
    x0, x1 = side + px(6), DEVICE_W - side - px(6)
    s.rrect([x0, y0, x1, y1], 24, fill=s.t["ground"], outline=s.t["brand_fill"] if focused else None,
            width=px(2) if focused else 0)
    s.magnifier((x0 + px(26), (y0 + y1) / 2), px(8), s.t["ink2"])
    tx = x0 + px(48)
    cx = x1 - px(28)
    fb = s.font("body")
    max_text = cx - px(28) - tx
    if query:
        shown_q = _fit(s, query, fb, max_text)
        s.d.text((tx, (y0 + y1) / 2), shown_q, font=fb, fill=s.t["ink"], anchor="lm")
        qx = tx + s.d.textlength(shown_q, font=fb)
    else:
        s.d.text((tx, (y0 + y1) / 2), _fit(s, "Search photos, people, places", fb, max_text), font=fb,
                 fill=s.t["ink2"], anchor="lm")
        qx = tx
    if focused:
        s.d.line([qx + px(2), y0 + px(14), qx + px(2), y1 - px(14)], fill=s.t["brand_fill"], width=px(2))
    cy = (y0 + y1) / 2
    s.d.line([cx - px(6), cy - px(6), cx + px(6), cy + px(6)], fill=s.t["ink2"], width=px(2))
    s.d.line([cx - px(6), cy + px(6), cx + px(6), cy - px(6)], fill=s.t["ink2"], width=px(2))
    s.target([cx - px(24), cy - px(24), cx + px(24), cy + px(24)], "Close search")
    s.target([x0, y0, cx - px(26), y1], "Search field")
    y = y0 - px(8)

    def chips(rows, selected):
        nonlocal y
        for row in reversed(rows):
            xx = inner_l
            for c, cw in row:
                b = [xx, y - chip_h, xx + cw, y]
                sel = c == selected
                s.rrect(b, 24, fill=s.t["brand_fill"] if sel else s.t["ground"])
                s.d.text(((b[0] + b[2]) / 2, (b[1] + b[3]) / 2), c, font=f_nav,
                         fill=s.t["on_brand"] if sel else s.t["ink"], anchor="mm")
                s.target(b, c)
                xx += cw + gap
            y -= chip_h + px(6)

    chips(scope_rows, scope)
    if entry_rows:
        chips(entry_rows, None)
    # suggestion rows
    for kind, text_ in reversed(shown):
        r0 = y - row_h
        x = inner_l
        if thumbs and kind in ("Person", "Place", "Picture"):
            s.photo((x, r0 + px(6), x + row_h - px(12), y - px(6)), seed + len(text_), radius_dp=8)
            x += row_h
        else:
            s.text((x + px(6), (r0 + y) / 2), _kind_icon(kind), "secondary", fill=s.t["ink2"], anchor="lm")
            x += px(28)
        fk = s.font("secondary")
        kw = s.d.textlength(kind, font=fk)
        s.d.text((x, (r0 + y) / 2), _fit(s, text_, fb, inner_r - x - kw - px(12)), font=fb, fill=s.t["ink"],
                 anchor="lm")
        s.d.text((inner_r, (r0 + y) / 2), kind, font=fk, fill=s.t["ink2"], anchor="rm")
        s.target([side, r0, DEVICE_W - side, y], f"{kind}: {text_}")
        y = r0
    if clipped:
        s.note((DEVICE_W - px(12), panel_top + px(2)), f"{clipped} more rows scroll inside the panel")
    s.panel_top = panel_top
    return panel_top


def _kind_icon(kind: str) -> str:
    return {"Recent": "•", "Suggestion": "›", "More": "+"}.get(kind, "•")


# ---------------------------------------------------------------------------
# Atlas overlay (L6)
# ---------------------------------------------------------------------------
MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]
COUNTS = [312, 0, 418, 655, 980, 1420, 2210, 1835, 702, 0, 268, 0]


def atlas_panel(s: Screen, open_frac: float, *, counts=None, current=10, places=True, dense=False,
                no_geo=False, year="2026", seed=3, grid=False) -> int:
    """The atlas slides down over the grid by ``open_frac`` of its full height. Returns its bottom."""
    counts = counts if counts is not None else COUNTS
    full_h = px(560) if places else px(260)
    h = int(full_h * open_frac)
    y0 = s.top
    if h <= 0:
        return y0
    panel = [0, y0, DEVICE_W, y0 + h]
    s.d.rectangle(panel, fill=s.t["surface"])
    # content is drawn into a clipped layer
    from PIL import Image as _I, ImageDraw as _D
    layer = _I.new("RGBA", (DEVICE_W, full_h), (0, 0, 0, 0))
    ld = _D.Draw(layer, "RGBA")
    ld.rectangle([0, 0, DEVICE_W, full_h], fill=s.t["surface"])
    f_t = s.fonts.get("display", 700, px(24) * s.font_scale)
    ld.text((px(SIDE), px(12)), year, font=f_t, fill=s.t["brand_text"])
    total = sum(counts)
    f_s = s.fonts.get("sans", 400, px(14) * s.font_scale)
    ld.text((DEVICE_W - px(SIDE), px(18)), f"{_nl(total)} photos", font=f_s, fill=s.t["ink2"], anchor="ra")
    # month bars, height ~ count, tallest labelled so the scale reads without ambiguity
    mx = max(counts) or 1
    f_m = s.fonts.get("sans", 600, px(12) * min(s.font_scale, 1.6))
    per_row = 6 if grid else 12
    bw = (DEVICE_W - px(2 * SIDE)) / per_row
    bx0 = px(SIDE)
    bar_max = px(44) if grid else px(96)
    row_bases = [px(118), px(198)] if grid else [px(170)]  # grid rows: 80 dp pitch, boxes touch, never overlap
    month_boxes = []
    place_pts: dict[str, tuple[float, float]] = {}
    for i, c in enumerate(counts):
        row, col_i = divmod(i, per_row)
        base_y = row_bases[row]
        x = bx0 + col_i * bw
        bh = int(bar_max * c / mx)
        colr = s.t["brand_fill"] if i == current else s.t["ink2"]
        if c == 0:
            ld.line([x + px(4), base_y, x + bw - px(4), base_y], fill=s.t["ink2"], width=px(1))
        else:
            ld.rounded_rectangle([x + px(4), base_y - bh, x + bw - px(4), base_y], radius=px(4), fill=colr)
        short = s.font_scale > 1.4 and not grid
        ld.text((x + bw / 2, base_y + px(6)), MONTHS[i][:1] if short else MONTHS[i], font=f_m,
                fill=s.t["ink"], anchor="ma")
        if i == current:
            r = px(10)
            cy = base_y - bh - px(14)
            ld.ellipse([x + bw / 2 - r - px(2), cy - r - px(2), x + bw / 2 + r + px(2), cy + r + px(2)],
                       fill="#FFFFFF")
            ld.ellipse([x + bw / 2 - r, cy - r, x + bw / 2 + r, cy + r], fill=s.t["brand_fill"])
        month_boxes.append((x, base_y - bar_max - px(12), x + bw, base_y + px(24)))
    legend_y = row_bases[-1] + px(30) * min(s.font_scale, 1.6)
    ld.text((DEVICE_W - px(SIDE), legend_y), f"tallest bar = {_nl(mx)} photos",
            font=s.fonts.get("sans", 400, px(11) * min(s.font_scale, 1.6)), fill=s.t["ink2"], anchor="ra")
    if places:
        my0, my1 = int(legend_y + px(24) * min(s.font_scale, 1.6)), full_h - px(24)
        ld.rounded_rectangle([px(SIDE), my0, DEVICE_W - px(SIDE), my1], radius=px(22), fill=s.t["ground"])
        if no_geo:
            fb = s.fonts.get("sans", 400, px(15) * s.font_scale)
            ld.text((px(SIDE) + px(16), my0 + px(24)), "No places yet.", font=fb, fill=s.t["ink"])
            ld.text((px(SIDE) + px(16), my0 + px(24) + px(22) * s.font_scale),
                    "Photos with a location will appear here.", font=fs2(s), fill=s.t["ink2"])
            ld.text((px(SIDE) + px(16), my0 + px(24) + px(44) * s.font_scale),
                    "Turn on location tags in the Camera app.", font=fs2(s), fill=s.t["ink2"])
        else:
            r = random.Random(seed)
            # schematic dots only: no tiles, no coastline, no coordinates
            n = 160 if dense else 18
            for k in range(n):
                cx = px(SIDE) + px(24) + r.random() * (DEVICE_W - px(2 * SIDE) - px(48))
                cy = my0 + px(24) + r.random() * (my1 - my0 - px(48))
                if dense:
                    cx = DEVICE_W / 2 + r.gauss(0, px(40))
                    cy = (my0 + my1) / 2 + r.gauss(0, px(30))
                rr = px(3) if dense else px(4 + r.random() * 6)
                ld.ellipse([cx - rr, cy - rr, cx + rr, cy + rr], fill=s.t["brand_fill"])
            if dense:
                cx, cy = DEVICE_W / 2, (my0 + my1) / 2
                ld.ellipse([cx - px(30), cy - px(30), cx + px(30), cy + px(30)], fill=s.t["brand_fill"])
                ld.text((cx, cy), "1.240", font=s.fonts.get("sans", 600, px(14)), fill="#FFFFFF", anchor="mm")
            else:
                labels = [("Amsterdam", 0.30, 0.35), ("Coast", 0.15, 0.7), ("Mountains", 0.7, 0.6)]
                for name, fx, fy in labels:
                    cx = px(SIDE) + fx * (DEVICE_W - px(2 * SIDE))
                    cy = my0 + fy * (my1 - my0)
                    place_pts[name] = (cx, cy)
                    ld.ellipse([cx - px(9), cy - px(9), cx + px(9), cy + px(9)], fill=s.t["brand_fill"])
                    if cx + px(14) + ld.textlength(name, font=fs2(s)) > DEVICE_W - px(SIDE) - px(8):
                        ld.text((cx - px(14), cy), name, font=fs2(s), fill=s.t["ink"], anchor="rm")
                    else:
                        ld.text((cx + px(14), cy), name, font=fs2(s), fill=s.t["ink"], anchor="lm")
    crop = layer.crop((0, full_h - h, DEVICE_W, full_h))
    s.img.paste(crop, (0, y0), crop)
    # grab handle (pull up to close)
    hy = y0 + h - px(12)
    s.d.rounded_rectangle([DEVICE_W / 2 - px(18), hy - px(2), DEVICE_W / 2 + px(18), hy + px(2)], radius=px(2),
                          fill=s.t["ink2"])
    s.place_centers = {n: (x, y0 + yy - (full_h - h)) for n, (x, yy) in place_pts.items()}
    if open_frac >= 1:
        for n, (x, yy) in s.place_centers.items():
            s.target([x - px(9), yy - px(9), x + px(9), yy + px(9)], f"place {n}")
        for i, (a, b, c, d_) in enumerate(month_boxes):
            s.target([a, y0 + b, c, y0 + d_], f"month {MONTHS[i]}", actual=True, show_size=(i == 0))
    return y0 + h


def fs2(s):
    return s.fonts.get("sans", 400, px(14) * s.font_scale)


def _nl(n: int) -> str:
    """Dutch-style thousands separator as used in Quiet Frame copy (12.305)."""
    return f"{n:,}".replace(",", ".")


# ---------------------------------------------------------------------------
# L8 pinch geometry (#836). Pure functions, no drawing: the board and the tests read the same numbers.
#
# Model: the "Pictures" title stays fixed; the timeline scrolls beneath it inside a viewport from the
# title bottom to the gesture inset (the slab floats over the content). Every layout places media keys
# in content coordinates; a scroll offset maps them to the screen. A pinch keeps the media key under
# the focal point (the two-finger centroid), restores its vertical position by choosing the new scroll
# offset, and reports whatever cannot be kept: an edge-aligned square grid fixes every key's column,
# so the horizontal position is quantized and the key may land away from the pointer.
# ---------------------------------------------------------------------------
from dataclasses import dataclass, field as _field  # noqa: E402
from datetime import date as _date  # noqa: E402

DAY_LABELS = ["Saturday 14 November", "Friday 13 November", "Thursday 12 November", "Wednesday 11 November",
              "Tuesday 10 November", "Monday 9 November", "Sunday 8 November", "Saturday 7 November",
              "Friday 6 November", "Thursday 5 November", "Wednesday 4 November", "Tuesday 3 November",
              "Monday 2 November", "Sunday 1 November"]
LIB_RATIOS = [1.33, 0.75, 1.5, 1.0, 0.56, 1.78, 0.8, 2.4, 0.66, 1.25]
LIB_VIDEOS = frozenset({6, 12, 21, 30, 44, 86, 97, 566, 830})


@dataclass(frozen=True)
class Item:
    key: int
    ratio: float
    day: str
    month: str  # "November 2026"
    video: bool = False


OLDER_MONTHS = [("August", 8, 880), ("July", 7, 640), ("June", 6, 910), ("May", 5, 520), ("April", 4, 770),
                ("March", 3, 600)]  # synthetic older months so YEAR (28 columns) has real scroll range


def synthetic_library(n_nov: int = 268, n_sep: int = 702) -> list[Item]:
    """Newest first. Keys 0-15 are the A1 day rows (DAY_RATIOS); the rest are seeded synthetic shapes.
    Counts follow the L6 atlas bars (November 268, October 0, September 702); older months are synthetic padding
    so the 28-column YEAR stop is longer than one screen (#855)."""
    r = random.Random(836)
    items: list[Item] = []
    split = [9, 7]
    per_day = []
    left = n_nov - sum(split)
    days_left = len(DAY_LABELS) - len(split)
    for i in range(days_left):
        c = left // (days_left - i)
        per_day.append(c)
        left -= c
    counts = split + per_day
    k = 0
    for day, c in zip(DAY_LABELS, counts):
        for _ in range(c):
            ratio = DAY_RATIOS[k] if k < len(DAY_RATIOS) else r.choice(LIB_RATIOS)
            items.append(Item(k, ratio, day, "November 2026", k in LIB_VIDEOS))
            k += 1
    for j in range(n_sep):
        d = _date(2026, 9, max(1, 30 - j // 24))
        items.append(Item(k, r.choice(LIB_RATIOS), f"{d:%A} {d.day} September", "September 2026"))
        k += 1
    for name, m, n in OLDER_MONTHS:
        for j in range(n):
            d = _date(2026, m, max(1, 28 - j // 30))
            items.append(Item(k, r.choice(LIB_RATIOS), f"{d:%A} {d.day} {name}", f"{name} 2026"))
            k += 1
    return items


@dataclass
class Layout:
    kind: str  # "original", "large", "medium" (real-ratio rows) or "small", "month", "year" (squares)
    cols: int  # 0 for real-ratio rows; 1 for original
    rects: dict[int, tuple[float, float, float, float]]  # key -> content rect
    headers: list[tuple[float, str]]  # (content y of header band top, label)
    height: float
    header_h: float

    @property
    def square(self) -> bool:
        return self.kind in SQUARE_COLS


# The approved six-stop ladder (SAMSUNG_PARITY_SPEC section 2, FLOW-2 #712): ORIGINAL day real-ratio, LARGE ~3
# justified day, MEDIUM ~4 justified day, SMALL 7 square/month, MONTH 12 square/month, YEAR 28 square/year.
LADDER = ("original", "large", "medium", "small", "month", "year")
SQUARE_COLS = {"small": 7, "month": 12, "year": 28}
STOP_NAMES = {"original": "ORIGINAL (1, real ratio)", "large": "LARGE (~3, real ratio)",
              "medium": "MEDIUM (~4, real ratio)", "small": "SMALL (7 squares)", "month": "MONTH (12 squares)",
              "year": "YEAR (28 squares)"}
ROW_TARGET_DP = {"large": 118, "medium": 86}
ORIGINAL_MAX_DP = 520  # tallest ORIGINAL frame; a taller portrait keeps its ratio and is centred narrower
MIN_TARGET_DP = 48  # Quiet Frame v3 touch target


def tile_dp(cols: int) -> float:
    return ((DEVICE_W - px(GAP) * (cols - 1)) / cols) / DENSITY


def header_h_for(s: Screen) -> int:
    """Date/section header band height, as drawn by day_rows() and square_grid()."""
    return s.line_h("date") + px(16)


def day_layout(items: list[Item], header_h: float, target_dp: int = 118, kind: str = "large") -> Layout:
    rects: dict[int, tuple] = {}
    headers: list[tuple[float, str]] = []
    y = 0.0
    groups: list[list[Item]] = []
    for it in items:
        if not groups or groups[-1][0].day != it.day:
            groups.append([])
        groups[-1].append(it)
    for grp in groups:
        headers.append((y, grp[0].day))
        y += header_h
        for row in justified_rows([it.ratio for it in grp], DEVICE_W, px(target_dp), px(GAP)):
            x = 0.0
            h = row[0][2]
            for j, w, _h in row:
                rects[grp[j].key] = (x, y, x + w, y + h)
                x += w + px(GAP)
            y += h + px(GAP)
    return Layout(kind, 0, rects, headers, y, header_h)


def original_layout(items: list[Item], header_h: float) -> Layout:
    """ORIGINAL stop: one picture per row at its real ratio, full width (or centred narrower when very tall)."""
    rects: dict[int, tuple] = {}
    headers: list[tuple[float, str]] = []
    y = 0.0
    day = None
    for it in items:
        if it.day != day:
            day = it.day
            headers.append((y, day))
            y += header_h
        w, h = float(DEVICE_W), DEVICE_W / it.ratio
        if h > px(ORIGINAL_MAX_DP):
            h = float(px(ORIGINAL_MAX_DP))
            w = h * it.ratio
        x = (DEVICE_W - w) / 2
        rects[it.key] = (x, y, x + w, y + h)
        y += h + px(GAP)
    return Layout("original", 1, rects, headers, y, header_h)


def grid_layout(items: list[Item], cols: int, header_h: float) -> Layout:
    """Edge-aligned square grid, one section per month: a key's column is its index in the month mod cols."""
    kind = {c: k for k, c in SQUARE_COLS.items()}[cols]
    tile = (DEVICE_W - px(GAP) * (cols - 1)) / cols
    step = tile + px(GAP)
    rects: dict[int, tuple] = {}
    headers: list[tuple[float, str]] = []
    y = 0.0
    month, i = None, 0
    for it in items:
        if it.month != month:
            y += (i + cols - 1) // cols * step  # rows of the previous month
            month, i = it.month, 0
            label = it.month if cols <= 12 else it.month[:3] + " " + it.month.split()[-1]
            headers.append((y, label))
            y += header_h
        c, r = i % cols, i // cols
        rects[it.key] = (c * step, y + r * step, c * step + tile, y + r * step + tile)
        i += 1
    y += (i + cols - 1) // cols * step
    return Layout(kind, cols, rects, headers, y, header_h)


@dataclass(frozen=True)
class Viewport:
    top: float  # screen y of the first content pixel (title bottom)
    bottom: float  # screen y where content stops (gesture inset)

    @property
    def h(self) -> float:
        return self.bottom - self.top


def max_scroll(layout: Layout, vp: Viewport) -> float:
    return max(0.0, layout.height - vp.h)


def to_screen(rect, scroll: float, vp: Viewport):
    x0, y0, x1, y1 = rect
    return (x0, y0 - scroll + vp.top, x1, y1 - scroll + vp.top)


def centre(rect) -> tuple[float, float]:
    return ((rect[0] + rect[2]) / 2, (rect[1] + rect[3]) / 2)


@dataclass
class PinchStep:
    key: int
    focal: tuple[float, float]
    src: tuple  # screen rect before
    dst: tuple  # screen rect after
    scroll: float  # destination scroll offset
    wanted_scroll: float  # before clamping to [0, max]
    clamped: str  # "", "top" or "bottom"
    kind: str = ""
    extra: dict = _field(default_factory=dict)

    @property
    def dx(self) -> float:
        return centre(self.dst)[0] - centre(self.src)[0]

    @property
    def dy(self) -> float:
        return centre(self.dst)[1] - centre(self.src)[1]

    @property
    def hit(self) -> bool:
        fx, fy = self.focal
        return self.dst[0] <= fx <= self.dst[2] and self.dst[1] <= fy <= self.dst[3]

    @property
    def y_residual(self) -> float:
        """Screen distance between the focal y and the same relative height inside the new rect."""
        v = rel_v(self.src, self.focal[1])
        return self.dst[1] + v * (self.dst[3] - self.dst[1]) - self.focal[1]


def rel_v(rect, fy: float) -> float:
    return min(1.0, max(0.0, (fy - rect[1]) / (rect[3] - rect[1])))


def pinch(src_screen_rect, focal, key: int, dst: Layout, vp: Viewport) -> PinchStep:
    """Destination scroll that puts ``key`` at the same relative height under the focal y."""
    v = rel_v(src_screen_rect, focal[1])
    r = dst.rects[key]
    want_top_screen = focal[1] - v * (r[3] - r[1])
    wanted = r[1] - (want_top_screen - vp.top)
    hi = max_scroll(dst, vp)
    scroll = min(hi, max(0.0, wanted))
    clamped = "top" if wanted < 0 else ("bottom" if wanted > hi else "")
    return PinchStep(key, focal, tuple(src_screen_rect), to_screen(r, scroll, vp), scroll, wanted, clamped, dst.kind)


def key_at(layout: Layout, scroll: float, vp: Viewport, point) -> int | None:
    for k, r in layout.rects.items():
        x0, y0, x1, y1 = to_screen(r, scroll, vp)
        if x0 <= point[0] <= x1 and y0 <= point[1] <= y1 and vp.top <= point[1] <= vp.bottom:
            return k
    return None


def same_point_columns(cols_a: int = 7, cols_b: int = 12) -> list[int]:
    """Columns of the finer grid whose span lies inside the coarser grid's column for the same key.
    Only for these can one stationary focal point sit on the same key in both grids."""
    ta = (DEVICE_W - px(GAP) * (cols_a - 1)) / cols_a
    tb = (DEVICE_W - px(GAP) * (cols_b - 1)) / cols_b
    out = []
    for c in range(cols_b):
        a0 = (c % cols_a) * (ta + px(GAP))
        b0 = c * (tb + px(GAP))
        if a0 <= b0 and b0 + tb <= a0 + ta:
            out.append(c)
    return out


# ---------------------------------------------------------------------------
# L8 drawing on top of the pinch geometry
# ---------------------------------------------------------------------------
def key_seed(key: int) -> int:
    """Same synthetic picture for a media key at every zoom (day_rows() uses seed 5)."""
    return 5 * 31 + key


def key_photo(s: Screen, rect, item: Item, clip: tuple[float, float] | None = None) -> None:
    """Draw ``item`` into ``rect``: the whole picture when the rect has its real ratio (day), a centre
    crop of the same picture when the rect is square (month/year). Clipped vertically to ``clip``."""
    x0, y0, x1, y1 = rect
    w, h = x1 - x0, y1 - y0
    if w < 1 or h < 1:
        return
    if abs(w / h - item.ratio) < 0.02:
        gw, gh = w, h
    elif item.ratio > w / h:
        gw, gh = h * item.ratio, h
    else:
        gw, gh = w, w / item.ratio
    gw, gh = max(1, int(round(gw))), max(1, int(round(gh)))
    ph = placeholder(gw, gh, key_seed(item.key))
    ox, oy = (gw - w) / 2, (gh - h) / 2
    top, bot = (y0, y1) if clip is None else (max(y0, clip[0]), min(y1, clip[1]))
    if bot - top < 1:
        return
    crop = ph.crop((int(round(ox)), int(round(oy + top - y0)), int(round(ox + w)), int(round(oy + bot - y0))))
    s.img.paste(crop, (int(round(x0)), int(round(top))))


def video_glyph(s: Screen, box) -> None:
    """Compact video mark for 46 dp year tiles, where a duration label cannot fit."""
    r = px(9)
    cx, cy = box[2] - px(4) - r, box[3] - px(4) - r
    s.d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(0, 0, 0, 150))
    s.d.polygon([(cx - px(3), cy - px(4.5)), (cx - px(3), cy + px(4.5)), (cx + px(4.5), cy)], fill="#FFFFFF")


def draw_layout(s: Screen, items: dict[int, Item], layout: Layout, scroll: float, vp: Viewport, *,
                selected: dict[int, int] | None = None, placeholders: bool = False,
                outline: int | None = None) -> dict[int, tuple]:
    """Draw the visible part of ``layout`` and return key -> screen rect for what is visible.

    Selection per stop: day rows and SMALL (53 dp tiles) keep the full sphere and record a direct ≥48 dp target;
    MONTH (30 dp) and YEAR (12 dp) tiles are dense: a smaller sphere is painted (visible, not a target) and no
    direct tap is recorded, because a dense tile may not be passed off as a compliant touch target (#855)."""
    vis: dict[int, tuple] = {}
    for hy, label in layout.headers:
        y = hy - scroll + vp.top
        if vp.top - layout.header_h < y < vp.bottom:
            s.text((px(SIDE), y + px(10)), label, "date")
    for k, r in layout.rects.items():
        sr = to_screen(r, scroll, vp)
        if sr[3] <= vp.top or sr[1] >= vp.bottom:
            continue
        vis[k] = sr
        if placeholders:
            s.d.rectangle([sr[0], max(sr[1], vp.top), sr[2], min(sr[3], vp.bottom)], fill=s.t["surface"])
        else:
            key_photo(s, sr, items[k], (vp.top, vp.bottom))
        if items[k].video and sr[3] < vp.bottom and layout.kind != "year":
            if layout.kind == "month":
                video_glyph(s, sr)
            else:
                video_badge(s, sr, "1:02" if not layout.square else "0:14")
        if selected and k in selected and sr[1] >= vp.top:
            dense_sphere(s, sr, selected[k], layout.kind)
    if outline is not None and outline in vis:
        s.d.rectangle(vis[outline], outline=ANNO, width=px(3))
    return vis


def dense_sphere(s: Screen, box, n: int, kind: str) -> None:
    if kind in ("original", "large", "medium"):
        select_sphere(s, box, n)
    elif kind == "small":  # 53 dp tile: the whole tile is the direct target, >= 48 dp
        select_sphere(s, box, n, r_dp=11, inset_dp=5, target="tile")
    else:
        # MONTH (30 dp tile): sphere inside the tile corner, no direct tap recorded.
        # YEAR (12 dp tile): the sphere is wider than its tile and sits on its centre.
        r = px(8 if kind == "month" else 7)
        if kind == "month":
            cx, cy = box[0] + px(3) + r, box[1] + px(3) + r
        else:
            cx = min(max((box[0] + box[2]) / 2, r + px(2)), DEVICE_W - r - px(2))
            cy = (box[1] + box[3]) / 2
        s.d.rectangle(box, fill=(255, 255, 255, 40 if kind == "month" else 90))
        s.sphere((cx, cy), r, halo_dp=2)
        s.d.text((cx, cy), str(n), font=s.fonts.get("sans", 700, px(10)), fill=s.t["on_brand"], anchor="mm")


def precision_pill(s: Screen, y_top: float, text: str, back: bool = False) -> tuple[float, float, float, float]:
    """48 dp (or taller at large font) floating pill that gives the dense overview its precision-selection
    pathway: "Select precisely" in MONTH/YEAR, "12 columns" (back) in the focused SMALL stop. Records a target."""
    f = s.font("nav", True, None)
    w = s.d.textlength(text, font=f) + px(48 if back else 40)
    h = max(px(MIN_TARGET_DP), s.line_h("nav") + px(20))
    x0 = px(SIDE)
    box = (x0, y_top, x0 + w, y_top + h)
    s.d.rounded_rectangle(box, radius=h / 2, fill=s.t["brand_fill"])
    tx = x0 + px(20)
    if back:  # chevron
        cy = y_top + h / 2
        s.d.line([(x0 + px(24), cy - px(6)), (x0 + px(17), cy), (x0 + px(24), cy + px(6))], fill=s.t["on_brand"],
                 width=px(2))
        tx = x0 + px(30)
    s.d.text((tx, y_top + h / 2), text, font=f, fill=s.t["on_brand"], anchor="lm")
    s.target(box, "back to overview" if back else "select precisely", actual=True)
    return box


def thumb_frac(layout: Layout, scroll: float, vp: Viewport) -> float:
    hi = max_scroll(layout, vp)
    return 0.0 if hi <= 0 else min(1.0, max(0.0, scroll / hi))


def crosshair(s: Screen, p, label: str | None = None) -> None:
    x, y = p
    r = px(9)
    s.d.line([x - r * 2, y, x - r / 2, y], fill=ANNO, width=px(2))
    s.d.line([x + r / 2, y, x + r * 2, y], fill=ANNO, width=px(2))
    s.d.line([x, y - r * 2, x, y - r / 2], fill=ANNO, width=px(2))
    s.d.line([x, y + r / 2, x, y + r * 2], fill=ANNO, width=px(2))
    s.d.ellipse([x - r / 3, y - r / 3, x + r / 3, y + r / 3], fill=ANNO)
    if label:
        f = s.fonts.get("sans", 700, px(11))
        anchor = "rm" if x > DEVICE_W * 0.6 else "lm"
        tx = x - r * 2.4 if anchor == "rm" else x + r * 2.4
        bb = s.d.textbbox((tx, y - px(14)), label, font=f, anchor=anchor)
        s.d.rectangle([bb[0] - px(3), bb[1] - px(2), bb[2] + px(3), bb[3] + px(2)], fill=(255, 255, 255, 225))
        s.d.text((tx, y - px(14)), label, font=f, fill=ANNO, anchor=anchor)


def anno_card(s: Screen, lines: list[str], y_bottom: float) -> None:
    """White annotation card (magenta text) that sits above the slab; part of the annotation layer."""
    f = s.fonts.get("sans", 600, px(11))
    lh = px(15)
    h = lh * len(lines) + px(12)
    y0 = y_bottom - h
    s.d.rounded_rectangle([px(8), y0, DEVICE_W - px(8), y_bottom], radius=px(8), fill=(255, 255, 255, 236),
                          outline=ANNO, width=px(1.5))
    for i, ln in enumerate(lines):
        s.d.text((px(16), y0 + px(6) + i * lh), ln, font=f, fill=ANNO)


def fmt_rect(r) -> str:
    return f"({r[0]:.0f},{r[1]:.0f})-({r[2]:.0f},{r[3]:.0f})"


def fmt_pt(p) -> str:
    return f"({p[0]:.0f}, {p[1]:.0f})"


# ---------------------------------------------------------------------------
# L8 storyboard scenarios: computed once, drawn by the board, asserted by the tests
# ---------------------------------------------------------------------------
MAIN_KEY = 828  # September, deep in the library: no stop clamps at the top or the end
MAIN_TOP_FRAC = 0.45  # day frame scrolled so the main key's top sits at 45 % of the viewport
INTERIOR_KEY = 825  # same day frame, a wide photo whose small/month/year columns lie away from its day position
SELECTED = {822: 1, 830: 2, 837: 3}  # ordered selection carried through every zoom (830 is a video)


def l8_env(s: Screen):
    """Layouts and viewport for a screen at its font scale. The title is measured on a scratch screen."""
    probe = Screen(s.fonts, theme=s.theme, font_scale=s.font_scale)
    vp = Viewport(title(probe, "Pictures"), probe.bottom)
    hh = header_h_for(probe)
    lib = synthetic_library()
    lays = {
        "original": original_layout(lib, hh),
        "large": day_layout(lib, hh, ROW_TARGET_DP["large"], "large"),
        "medium": day_layout(lib, hh, ROW_TARGET_DP["medium"], "medium"),
        **{k: grid_layout(lib, c, hh) for k, c in SQUARE_COLS.items()},
    }
    return {it.key: it for it in lib}, lays, vp


def storyboard(lays: dict[str, Layout], vp: Viewport) -> dict[str, PinchStep]:
    """Pinch in = LARGE -> MEDIUM -> SMALL(7) -> MONTH(12) -> YEAR(28); pinch out walks back. Same F throughout."""
    large, medium, small, month, year = (lays[k] for k in ("large", "medium", "small", "month", "year"))
    out: dict[str, PinchStep] = {}
    # Main: fingers on the main key at 40 % of its height, x at the centre of its LARGE day rect.
    s0 = round(large.rects[MAIN_KEY][1] - MAIN_TOP_FRAC * vp.h)
    r = to_screen(large.rects[MAIN_KEY], s0, vp)
    fy = round(r[1] + 0.4 * (r[3] - r[1]))
    chain: dict[str, PinchStep] = {}
    # F sits at the rect's centre x, nudged sideways (smallest move first) until a key lies under it at every
    # stop: a 2 dp gap between square tiles would make the frame say nothing about the key under F.
    for off in sorted(range(-40, 41, 2), key=abs):
        f = (round(centre(r)[0]) + off, fy)
        if not r[0] <= f[0] <= r[2]:
            continue
        chain = {"in_medium": pinch(r, f, MAIN_KEY, medium, vp)}
        chain["in_small"] = pinch(chain["in_medium"].dst, f, MAIN_KEY, small, vp)
        chain["in_month"] = pinch(chain["in_small"].dst, f, MAIN_KEY, month, vp)
        chain["in_year"] = pinch(chain["in_month"].dst, f, MAIN_KEY, year, vp)
        for name, st_ in chain.items():
            st_.extra["key_under_pointer"] = key_at(lays[st_.kind], st_.scroll, vp, f)
        if all(st_.extra["key_under_pointer"] is not None for st_ in chain.values()):
            break
    start = PinchStep(MAIN_KEY, f, r, r, s0, s0, "", "large")
    out["start"] = start
    out["original"] = pinch(r, f, MAIN_KEY, lays["original"], vp)
    out.update(chain)
    k_back = key_at(year, out["in_year"].scroll, vp, f)
    if k_back is None:  # F fell into a 2 dp gap: take the key nearest to it
        k_back = min(year.rects, key=lambda k: _dist(to_screen(year.rects[k], out["in_year"].scroll, vp), f))
    out["out_month"] = pinch(out["in_year"].dst, f, k_back, month, vp)
    out["out_small"] = pinch(out["out_month"].dst, f, k_back, small, vp)
    out["out_medium"] = pinch(out["out_small"].dst, f, k_back, medium, vp)
    out["out_large"] = pinch(out["out_medium"].dst, f, k_back, large, vp)
    # Interior key: the photo cannot stay under a stationary pointer once columns are fixed.
    ri = to_screen(large.rects[INTERIOR_KEY], s0, vp)
    fi = (round(centre(ri)[0]), round(centre(ri)[1]))
    out["int_start"] = PinchStep(INTERIOR_KEY, fi, ri, ri, s0, s0, "", "large")
    out["int_small"] = pinch(ri, fi, INTERIOR_KEY, small, vp)
    out["int_month"] = pinch(out["int_small"].dst, fi, INTERIOR_KEY, month, vp)
    out["int_year"] = pinch(out["int_month"].dst, fi, INTERIOR_KEY, year, vp)
    for name in ("int_small", "int_month", "int_year"):
        out[name].extra["key_under_pointer"] = key_at(lays[out[name].kind], out[name].scroll, vp, fi)
    # Top clamp: first photo of the library at day scroll 0.
    r0 = to_screen(large.rects[0], 0, vp)
    out["top"] = pinch(r0, (round(centre(r0)[0]), round(centre(r0)[1])), 0, small, vp)
    # Bottom clamp: last photo of the library with the day timeline at its end.
    last = max(large.rects)
    sb = max_scroll(large, vp)
    rl = to_screen(large.rects[last], sb, vp)
    out["bottom"] = pinch(rl, (round(centre(rl)[0]), round(rl[1] + 0.2 * (rl[3] - rl[1]))), last, small, vp)
    out["bottom"].extra["day_scroll"] = sb
    return out


def _dist(rect, p) -> float:
    c = centre(rect)
    return math.hypot(c[0] - p[0], c[1] - p[1])


# Precision selection from a dense overview (MONTH 12 / YEAR 28): long-press or "Select precisely" opens the same
# media key in the 7-column SMALL stop, whose 53 dp tiles are real >= 48 dp targets; Back returns to the overview.
PRECISION_KEY = 568  # a selected item, so continuity across the round trip is visible
PRECISION_SELECTED = {560: 1, 566: 2}  # selected before the long-press; the pressed key becomes 3


def precision_flow(lays: dict[str, Layout], vp: Viewport, from_kind: str = "month") -> dict[str, PinchStep]:
    """Dense overview -> SMALL focus -> back, anchored on PRECISION_KEY at the long-press height."""
    dense, small = lays[from_kind], lays["small"]
    scroll = max(0.0, min(max_scroll(dense, vp), dense.rects[PRECISION_KEY][1] - 0.4 * vp.h))
    r = to_screen(dense.rects[PRECISION_KEY], scroll, vp)
    press = (round(centre(r)[0]), round(centre(r)[1]))
    out = {"press": PinchStep(PRECISION_KEY, press, r, r, scroll, scroll, "", from_kind)}
    out["focus"] = pinch(r, press, PRECISION_KEY, small, vp)
    out["back"] = pinch(out["focus"].dst, press, PRECISION_KEY, dense, vp)
    return out


def fmt_d(v: float) -> str:
    """Signed whole pixels; rounding noise prints as +0, never -0."""
    r = round(v)
    return f"{r:+d}" if r else "+0"


def pinch_fingers(focal, direction: str = "pinch in", far_dp: float = 70, near_dp: float = 30):
    """Two fingertip paths, (start, end) each, mirrored about ``focal`` on the vertical axis, so their
    centroid is the focal point F at every stage. Pinch in moves far -> near; pinch out near -> far."""
    fx, fy = focal
    a, b = (px(far_dp), px(near_dp)) if direction == "pinch in" else (px(near_dp), px(far_dp))
    return [((fx, fy - a), (fx, fy - b)), ((fx, fy + a), (fx, fy + b))]


def draw_pinch(s: Screen, focal, direction: str = "pinch in", **kw) -> None:
    (s1, e1), (s2, e2) = pinch_fingers(focal, direction, **kw)
    finger(s, s1, e1)
    finger(s, s2, e2, direction)
