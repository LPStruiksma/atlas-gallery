"""Reusable synthetic Lapis screens (Quiet Frame v3). Each builder draws on a Screen."""

from __future__ import annotations

import math
import random

from lapis_kit import (
    ANNO,
    DEVICE_W,
    VIEWER_SLAB,
    dashed_rect,
    VIEWER_SPHERE,
    Screen,
    justified_rows,
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


def select_sphere(s: Screen, box, n: int | None):
    """Selection: lapis sphere on the tile corner with a 2 dp white halo + order number."""
    r = px(12)
    cx, cy = box[0] + px(8) + r, box[1] + px(8) + r
    s.d.rectangle(box, fill=(255, 255, 255, 40))
    s.sphere((cx, cy), r, halo_dp=2, label=str(n) if n else None, label_role="nav")
    s.target([cx - r, cy - r, cx + r, cy + r], f"select {n}")


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


def anchored_squares(s: Screen, cols: int, anchor_center, anchor_seed: int, y_min: int, seed: int = 5,
                     y_max: int | None = None) -> None:
    """Square tiles positioned so one tile is centred on ``anchor_center`` (pinch anchor)."""
    y_max = y_max or s.bottom
    tile = (DEVICE_W - px(GAP) * (cols - 1)) / cols
    step = tile + px(GAP)
    acx, acy = anchor_center
    acol = min(cols - 1, int(acx // step))
    top0 = acy - tile / 2
    first_row = -int((top0 - y_min) // step) - 1
    r = first_row
    while True:
        y0 = top0 + r * step
        if y0 >= y_max:
            break
        if y0 + tile > y_min:
            for c in range(cols):
                x0 = c * step
                box = (x0, max(y0, y_min), x0 + tile, y0 + tile)
                if box[3] - box[1] < 2:
                    continue
                is_anchor = r == 0 and c == acol
                ph_seed = anchor_seed if is_anchor else seed * 53 + (r + 50) * cols + c
                s.photo(box, ph_seed)
                if is_anchor:
                    s.d.rectangle((x0, y0, x0 + tile, y0 + tile), outline=ANNO, width=px(3))
        r += 1
