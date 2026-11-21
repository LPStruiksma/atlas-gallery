#!/usr/bin/env python3
"""SAVED-1 (#779): synthetic save / exclude / Unsorted design-evidence board.

Draws real, scaled 1080 x 2340 device frames for panels A-G of docs/design/saved-1/README.md on top of
the shared Lapis kit (Quiet Frame v3 tokens, Funnel fonts, synthetic photo placeholders, 48 dp target
audit). Everything is synthetic: generated gradients, fictional names and counts. Nothing reads private
media, location data or the network, and no runtime or device capture is claimed.

    python3 tools/design/saved1_board.py --font-dir DIR --finalize   # render PNG + write manifest
    python3 tools/design/saved1_board.py --check                      # verify PNG against manifest
    python3 tools/design/saved1_board.py --allow-fallback-font --out /tmp/x   # layout smoke run
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from dataclasses import dataclass
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from PIL import Image, ImageDraw  # noqa: E402

import lapis_screens as L  # noqa: E402
from lapis_board import Board, Panel  # noqa: E402
from lapis_kit import (  # noqa: E402
    ANNO,
    DENSITY,
    DEVICE_H,
    DEVICE_W,
    Fonts,
    Screen,
    find_fonts,
    px,
)

REPO = HERE.parent.parent
OUT_DIR = REPO / "docs/design/saved-1"
ARTIFACT = "saved-1-search-albums.png"
MANIFEST = OUT_DIR / "manifest.json"
UNIT = "https://github.com/LPStruiksma/atlas-gallery/issues/779"
VERIFIED = "2026-11-21"
MIN_TARGET_PX = 135  # 48 dp at 2.8125 px/dp


# ---------------------------------------------------------------------------
# Filter semantics (pure, tested). Drawn states below quote these results.
# ---------------------------------------------------------------------------
def match(items, positives, excludes):
    """OR within one family, AND across families, then subtract the union of excluded sets.

    ``items``: dicts with a "tags" set of (family, value). ``positives``: {family: set(values)}.
    ``excludes``: set of (family, value) pairs."""
    out = []
    for it in items:
        tags = it["tags"]
        if any(tag in excludes for tag in tags):
            continue
        if all(any((fam, v) in tags for v in vals) for fam, vals in positives.items()):
            out.append(it)
    return out


def unsorted(items, albums):
    """Virtual Unsorted: media in no user-managed album, whatever camera folder holds the file."""
    return [it for it in items if not any(it["id"] in members for members in albums.values())]


# ---------------------------------------------------------------------------
# Small drawing helpers (annotation-free; targets are recorded on the Screen)
# ---------------------------------------------------------------------------
def rgb_of(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def scrim(s: Screen, alpha=150):
    s.d.rectangle([0, 0, DEVICE_W, DEVICE_H], fill=(*rgb_of(s.t["ground"]), alpha))


def chip_h(s: Screen) -> int:
    return max(px(32), s.line_h("nav") + px(12))


def chip_w(s: Screen, label: str, closable: bool) -> int:
    f = s.font("nav")
    return int(s.d.textlength(label, font=f)) + px(28) + (px(22) if closable else 0)


def draw_chip(s: Screen, x: int, y_mid: int, label: str, kind="on", closable=True, tag=None) -> int:
    """kind: on (filled lapis), not (outlined 'Not:' chip, no second accent), plain (mist)."""
    f = s.font("nav")
    h, w = chip_h(s), chip_w(s, label, closable)
    box = [x, y_mid - h // 2, x + w, y_mid + h // 2]
    if kind == "on":
        s.rrect(box, 24, fill=s.t["brand_fill"])
        col = s.t["on_brand"]
    elif kind == "not":
        s.rrect(box, 24, fill=s.t["ground"], outline=s.t["brand_text"], width=px(2))
        col = s.t["brand_text"]
    else:
        s.rrect(box, 24, fill=s.t["surface"])
        col = s.t["ink"]
    s.d.text((x + px(14), y_mid), label, font=f, fill=col, anchor="lm")
    if closable:
        cx, r = box[2] - px(18), px(4)
        s.d.line([cx - r, y_mid - r, cx + r, y_mid + r], fill=col, width=px(1.6))
        s.d.line([cx - r, y_mid + r, cx + r, y_mid - r], fill=col, width=px(1.6))
    s.target(box, tag or label)
    return box[2]


def chip_block(s: Screen, x0: int, y_top: int, avail: int, chips, gap=None) -> int:
    """Wrapping chips; rows are 52 dp apart so the padded 48 dp targets never overlap. Returns bottom y."""
    gap = gap or px(8)
    x, y, row_h = x0, y_top, px(52)
    for label, kind, closable in chips:
        w = chip_w(s, label, closable)
        if x > x0 and x + w > x0 + avail:
            x, y = x0, y + row_h
        draw_chip(s, x, y + row_h // 2, label, kind, closable)
        x += w + gap
    return y + row_h


def dots_button(s: Screen, cx: int, cy: int, label="More options"):
    for k in (-1, 0, 1):
        s.d.ellipse([cx - px(2), cy + k * px(6) - px(2), cx + px(2), cy + k * px(6) + px(2)], fill=s.t["ink"])
    s.target([cx - px(24), cy - px(24), cx + px(24), cy + px(24)], label)


def menu(s: Screen, right: int, top: int, rows, highlight=None, width_dp=264) -> list:
    """Popup menu card (mist, radius 16) with 48 dp+ rows. Returns its box."""
    f = s.font("body")
    rh = max(px(48), s.line_h("body") + px(16))
    w = min(px(width_dp) if s.font_scale <= 1 else DEVICE_W - px(32), DEVICE_W - px(16))
    box = [right - w, top, right, top + rh * len(rows) + px(16)]
    s.d.rounded_rectangle([box[0] + px(2), box[1] + px(4), box[2] + px(2), box[3] + px(4)], radius=px(16),
                          fill=(0, 0, 0, 60))
    s.rrect(box, 16, fill=s.t["surface"], outline=s.t["ink2"], width=px(1))
    y = box[1] + px(8)
    for i, label in enumerate(rows):
        rb = [box[0] + px(4), y, box[2] - px(4), y + rh]
        if i == highlight:
            s.rrect(rb, 12, fill=s.t["brand_fill"])
            col = s.t["on_brand"]
        else:
            col = s.t["ink"]
        s.d.text((rb[0] + px(14), (rb[1] + rb[3]) / 2), label, font=f, fill=col, anchor="lm")
        s.target(rb, label)
        y += rh
    return box


def button(s: Screen, x1: int, y_mid: int, label: str, filled=True, disabled=False) -> int:
    """Right-aligned button ending at x1; returns its left x."""
    f = s.font("nav")
    h = max(px(48), s.line_h("nav") + px(20))
    w = max(px(72), int(s.d.textlength(label, font=f)) + px(32))
    box = [x1 - w, y_mid - h // 2, x1, y_mid + h // 2]
    if filled:
        s.rrect(box, 24, fill=s.t["brand_fill"])
        col = s.t["on_brand"]
    else:
        col = s.t["ink2"] if disabled else s.t["brand_text"]
    s.d.text(((box[0] + box[2]) / 2, y_mid), label, font=f, fill=col, anchor="mm")
    s.target(box, label + (" (off)" if disabled else ""))
    return box[0]


def photo_strip(s: Screen, x: int, y: int, h: int, ratios, seed, max_w) -> int:
    return L.strip(s, x, y, h, ratios, seed, max_w)


def card(s: Screen, lines):
    """Annotation card above the slab (or above the bottom inset when there is no slab)."""
    sb = getattr(s, "slab_box", None)
    L.anno_card(s, lines, (sb[1] if sb else s.bottom) - px(10))


def measure(g, builder, theme, scale) -> int:
    """Height a builder draws, measured on a throwaway frame (builder(s, y0) returns its bottom y)."""
    scratch = g.scr(theme, scale)
    return builder(scratch, 0)


def base(s: Screen, title="Pictures", sub=None, dim=0, seed=1) -> int:
    s.status_bar()
    y = L.title(s, title, sub)
    L.square_grid(s, y, s.bottom, seed=seed, header="Today")
    if dim:
        scrim(s, dim)
    return y


# ---------------------------------------------------------------------------
# Frames. Each takes the generator, theme and font scale and returns a Screen.
# ---------------------------------------------------------------------------
class Gen:
    def __init__(self, fonts: Fonts):
        self.fonts = fonts

    def scr(self, theme="light", scale=1.0) -> Screen:
        return Screen(self.fonts, theme=theme, font_scale=scale)

    # A: expanded search with typed positive chips, a Not chip, scope and Save as album in the overflow
    def a(self, theme="light", scale=1.0) -> Screen:
        s = self.scr(theme, scale)
        base(s, dim=160)
        kb = s.keyboard(["mountains", "mountains 2026", "mountain lake"])
        sb = s.slab("Pictures", y_bottom=kb, search=False)
        top = L.search_bar(s, sb[1], "moun", True, scope="Whole library")
        side = px(8)
        avail = DEVICE_W - 2 * side - px(16)
        chips = [("Mountains", "on", True), ("2026", "on", True), ("Not: Screenshots", "not", True)]
        # measure the chip block, then draw the mist container bottom-up above the search panel
        rows_h = chip_block(self.scr(theme, scale), 0, 0, avail, chips)
        y1 = top - px(8)
        y0 = y1 - rows_h - px(12)
        s.rrect([side, y0, DEVICE_W - side, y1], 22, fill=s.t["surface"])
        chip_block(s, side + px(8), y0 + px(6), avail - px(56), chips)
        dots_button(s, DEVICE_W - side - px(30), y0 + px(6) + px(26), "Overflow")
        menu(s, DEVICE_W - side, y0 - px(8) - 2 * max(px(48), s.line_h("body") + px(16)) - px(16),
             ["Save as album", "Clear all"], highlight=0)
        s.gesture_bar()
        return s

    # B: Save sheet (name, query summary, duplicate or failed-save state), keyboard open
    def b(self, theme="light", scale=1.0, state="duplicate") -> Screen:
        def sheet(s, y0):
            x, w = px(16), DEVICE_W - px(32)
            y = y0 + px(16)
            big = s.font_scale > 1.4  # 200%: the summary collapses to text; the strip scrolls below the fold
            s.text((x, y), "Save as album", "section")
            y += s.line_h("section") + px(4)
            if big:
                y = s.wrap(x, y, "Mountains · 2026 · Not: Screenshots · 184 photos", w, "secondary", fill=s.t["ink2"],
                           max_lines=2) + px(8)
            else:
                s.text((x, y), "Saved search · stays up to date", "secondary", fill=s.t["ink2"])
                y += s.line_h("secondary") + px(8)
                y = chip_block(s, x, y, w, [("Mountains", "plain", False), ("2026", "plain", False),
                                            ("Not: Screenshots", "not", False)]) + px(4)
                y = s.wrap(x, y, "Whole library · 184 photos match today", w, "secondary", fill=s.t["ink2"])
                y += px(10)
                photo_strip(s, x, y, px(56), L.STRIP_RATIOS[1], 70, w)
                y += px(56) + px(12)
            fh = max(px(56), s.line_h("body") + px(24))
            fb = [x, y, x + w, y + fh]
            focus_col = s.t["brand_fill"] if state == "ok" else s.t["brand_text"]
            s.rrect(fb, 12, fill=s.t["ground"], outline=focus_col, width=px(2))
            s.text((x + px(14), y + px(5)), "Album name", "secondary", fill=s.t["ink2"], scale=False)
            name = "Mountains 2026"
            s.d.text((x + px(14), y + fh - px(12)), name, font=s.font("body"), fill=s.t["ink"], anchor="ls")
            cx = x + px(14) + s.d.textlength(name, font=s.font("body")) + px(2)
            s.d.line([cx, y + fh - px(34), cx, y + fh - px(10)], fill=s.t["brand_fill"], width=px(2))
            s.target(fb, "Album name field")
            y += fh + px(6)
            if state == "duplicate":
                y = s.wrap(x + px(4), y, "! An album named “Mountains 2026” already exists. Pick another name.",
                           w - px(8), "secondary", fill=s.t["brand_text"])
            elif state == "failed":
                y = s.wrap(x + px(4), y, "! Couldn’t save. Your search and name are kept. Try again.", w - px(8),
                           "secondary", fill=s.t["brand_text"])
            else:
                y += px(4)
            y += px(6)
            ymid = y + px(24)
            sx = button(s, DEVICE_W - px(16), ymid, "Retry save" if state == "failed" else "Save",
                        filled=state not in ("duplicate",), disabled=state == "duplicate")
            button(s, sx - px(8), ymid, "Cancel", filled=False)
            return y + px(52) + px(8)

        s = self.scr(theme, scale)
        base(s, dim=170)
        kb = s.keyboard(["Mountains 2026", "Mountains 2026 (2)", "Alps"])
        h = measure(self, sheet, theme, scale)
        top = kb - px(8) - h
        if top < s.top + px(8) and self.fonts.src.fallback:
            top = s.top + px(8)  # DejaVu is wider than Funnel: the layout smoke run may overflow, the real one may not
        elif top < s.top + px(8):
            raise AssertionError(f"save sheet does not fit above the keyboard at {scale:.0%} ({top} < {s.top})")
        s.d.rounded_rectangle([px(8), top, DEVICE_W - px(8), kb - px(8)], radius=px(28), fill=s.t["surface"])
        s.d.rounded_rectangle([DEVICE_W / 2 - px(20), top + px(6), DEVICE_W / 2 + px(20), top + px(9)],
                              radius=px(2), fill=s.t["ink2"])
        sheet(s, top)
        s.gesture_bar()
        return s

    def b_dup(self, theme="light", scale=1.0) -> Screen:
        return self.b(theme, scale, "duplicate")

    def b_fail(self, theme="light", scale=1.0) -> Screen:
        return self.b(theme, scale, "failed")

    # C: Collections > Albums: ordinary albums, then the Saved searches heading and rows, then Unsorted.
    # The list is long, so it is shown at two scroll positions (C1 top, C2 scrolled down).
    def _collections(self, theme, scale, rows, heading_before=None, scrolled=False) -> Screen:
        s = self.scr(theme, scale)
        s.status_bar()
        y = L.title(s, "Collections")
        for item in rows:
            if item == "heading":
                s.text((px(16), y + px(4)), "Saved searches", "section")
                y += s.line_h("section") + px(12)
                continue
            name, line2, ratios, seed, empty = item
            y = L.collection_row(s, y, name, line2, ratios, seed, empty_text=empty)
        s.slab("Collections")
        if scrolled:
            card(s, ["List scrolled down: the Saved searches heading is above."])
        s.gesture_bar()
        return s

    def c1(self, theme="light", scale=1.0) -> Screen:
        return self._collections(theme, scale, [
            ("Family trips", "Album · 38 photos", L.STRIP_RATIOS[3], 51, None),
            ("Alps hikes", "Album · 112 photos", L.STRIP_RATIOS[0], 61, None),
            "heading",
            ("Mountains 2026", "Saved search · 184 photos", L.STRIP_RATIOS[1], 31, None),
        ])

    def c2(self, theme="light", scale=1.0) -> Screen:
        return self._collections(theme, scale, [
            ("2025 without screenshots", "Saved search · 2 photos", L.STRIP_RATIOS[2][:2], 41, None),
            ("Night walks", "Saved search · 0 photos", None, 0, "No photos match yet. New ones that fit appear here."),
            ("Unsorted", "In no album you manage · 1,204 photos", L.STRIP_RATIOS[0], 21, None),
        ], scrolled=True)

    # D: saved album grid with Sort, shared Search; overflow with edit / rename / remove
    def _saved_header(self, s, sub="Saved search · 184 photos"):
        s.status_bar()
        y = L.title(s, "Mountains 2026", sub)
        return y

    def d1(self, theme="light", scale=1.0) -> Screen:
        s = self.scr(theme, scale)
        y = self._saved_header(s)
        chips = [("Mountains", "plain", False), ("2026", "plain", False), ("Not: Screenshots", "not", False),
                 ("Sort: Newest", "on", False)]
        avail = DEVICE_W - px(32)
        yb = chip_block(s, px(16), y, avail, chips)
        L.square_grid(s, yb, s.bottom, seed=2, header=None)
        dots_button(s, DEVICE_W - px(16) - px(24), s.top + px(12) + px(22), "Overflow")
        s.slab("Collections")
        menu(s, DEVICE_W - px(8), s.top + px(12) + px(50), ["Edit query", "Rename", "Remove saved album"],
             highlight=2)
        card(s, ["Same photo grid as any album; Search is the shared sphere."])
        s.gesture_bar()
        return s

    def d2(self, theme="light", scale=1.0) -> Screen:
        s = self.scr(theme, scale)
        y = self._saved_header(s)
        L.square_grid(s, y + px(60), s.bottom, seed=2)
        s.slab("Collections")
        scrim(s, 170)

        def dialog(sc, y0):
            x, w = px(16) + px(12), DEVICE_W - px(56)
            y = y0 + px(20)
            y = sc.wrap(x, y, "Remove saved album?", w, "section") + px(4)
            y = sc.wrap(x, y, "Only “Mountains 2026” goes away. Your 184 photos stay in the library and in any "
                        "album that holds them.", w, "body", fill=sc.t["ink"]) + px(10)
            photo_strip(sc, x, y, px(56), L.STRIP_RATIOS[1], 80, w)
            y += px(56) + px(14)
            ymid = y + px(24)
            bx = button(sc, DEVICE_W - px(16) - px(12), ymid, "Remove", filled=True)
            button(sc, bx - px(8), ymid, "Cancel", filled=False)
            return y + px(52) + px(12)

        h = measure(self, dialog, theme, scale)
        top = (DEVICE_H - h) // 2
        s.d.rounded_rectangle([px(16), top, DEVICE_W - px(16), top + h], radius=px(28), fill=s.t["surface"])
        dialog(s, top)
        s.gesture_bar()
        return s

    # E: facet menu (Include / Exclude) and the contradictory zero-match state
    def e1(self, theme="light", scale=1.0) -> Screen:
        s = self.scr(theme, scale)
        base(s, dim=160)
        sb = s.slab("Pictures", search=False)
        top = L.search_bar(s, sb[1], "", False, scope="Whole library")
        side = px(8)
        avail = DEVICE_W - 2 * side - px(16)
        chips = [("Place: Alps", "on", True), ("Place: Tirol", "on", True), ("Year: 2026", "on", True)]
        rows_h = chip_block(self.scr(theme, scale), 0, 0, avail, chips)
        y1 = top - px(8)
        y0 = y1 - rows_h - px(12)
        s.rrect([side, y0, DEVICE_W - side, y1], 22, fill=s.t["surface"])
        chip_block(s, side + px(8), y0 + px(6), avail, chips)
        rh = max(px(48), s.line_h("body") + px(16))
        menu(s, DEVICE_W - side, y0 - px(8) - 4 * rh - px(16), ["Type: Screenshots", "Include", "Exclude", "Remove"],
             highlight=2)
        L.anno_card(s, ["OR inside a family, AND across families,", "then subtract every excluded set (Not: chip)."],
                    y0 - px(8) - 4 * rh - px(24))
        s.gesture_bar()
        return s

    def e2(self, theme="light", scale=1.0) -> Screen:
        s = self.scr(theme, scale)
        s.status_bar()
        y = L.title(s, "Pictures")
        yb = chip_block(s, px(16), y, DEVICE_W - px(32), [("Place: Alps", "on", True), ("Not: Alps", "not", True)])
        y = L.message(s, yb + px(24), "No photos match.", "You asked for Alps and excluded Alps. Nothing can fit both.",
                      button="Clear exclusion")
        s.slab("Pictures")
        card(s, ["Zero matches is a result, not an error.", "An empty result can still be saved as an album."])
        s.gesture_bar()
        return s

    # F: virtual Unsorted; adding to a manual album removes the photo from it without moving the file
    def f1(self, theme="light", scale=1.0) -> Screen:
        s = self.scr(theme, scale)
        s.status_bar()
        y = L.title(s, "Unsorted", "In no album you manage · 1,204 photos")
        boxes = L.square_grid(s, y, s.bottom, seed=3, header=None, selected={1: 1, 6: 2})
        scrim(s, 120)

        def sheet(sc, y0):
            x, w = px(16), DEVICE_W - px(32)
            y = y0 + px(18)
            y = sc.wrap(x, y, "Add 2 photos to an album", w, "section") + px(6)
            for name, sub, sel in (("Family trips", "38 photos", True), ("Alps hikes", "112 photos", False)):
                rh = max(px(56), sc.line_h("body") + px(18))
                rb = [x, y, x + w, y + rh]
                sc.rrect(rb, 12, fill=sc.t["brand_fill"] if sel else sc.t["ground"])
                col = sc.t["on_brand"] if sel else sc.t["ink"]
                sc.d.text((x + px(14), y + rh / 2), name, font=sc.font("body"), fill=col, anchor="lm")
                sc.d.text((x + w - px(14), y + rh / 2), sub, font=sc.font("secondary"), fill=col, anchor="rm")
                sc.target(rb, name)
                y += rh + px(6)
            y = sc.wrap(x, y + px(4), "They leave Unsorted. The files stay where they are.", w, "secondary",
                        fill=sc.t["ink2"]) + px(8)
            ymid = y + px(24)
            bx = button(sc, DEVICE_W - px(16), ymid, "Add", filled=True)
            button(sc, bx - px(8), ymid, "Cancel", filled=False)
            return y + px(52) + px(8)

        h = measure(self, sheet, theme, scale)
        bottom = s.bottom - px(8)
        top = bottom - h
        s.d.rounded_rectangle([px(8), top, DEVICE_W - px(8), bottom], radius=px(28), fill=s.t["surface"])
        sheet(s, top)
        s.gesture_bar()
        return s

    def f2(self, theme="light", scale=1.0) -> Screen:
        s = self.scr(theme, scale)
        s.status_bar()
        y = L.title(s, "Unsorted", "In no album you manage · 1,202 photos")
        L.square_grid(s, y, s.bottom, seed=3, header=None)
        s.slab("Collections")
        f = s.font("body")
        h = max(px(56), s.line_h("body") + px(24))
        sb = s.slab_box
        y1 = sb[1] - px(10)
        box = [px(12), y1 - h, DEVICE_W - px(12), y1]
        s.rrect(box, 16, fill=s.t["ink"] if s.theme == "light" else s.t["surface"])
        col = s.t["ground"] if s.theme == "light" else s.t["ink"]
        s.d.text((box[0] + px(16), (box[1] + box[3]) / 2), "2 added to Family trips", font=f, fill=col, anchor="lm")
        ub = [box[2] - px(16) - int(s.d.textlength("Undo", font=s.font("nav"))) - px(16), box[1], box[2], box[3]]
        s.d.text(((ub[0] + ub[2]) / 2, (ub[1] + ub[3]) / 2), "Undo", font=s.font("nav"),
                 fill=s.t["brand_text"] if s.theme == "dark" else "#8C9BFF", anchor="mm")
        s.target(ub, "Undo")
        L.anno_card(s, ["1,204 to 1,202. The two photos are not moved on disk."], DEVICE_H // 2)
        s.gesture_bar()
        return s

    # G: indexing, unavailable + Retry, 200% long name with 10k+, RTL + TalkBack + reduced motion
    def g1(self, theme="light", scale=1.0) -> Screen:
        s = self.scr(theme, scale)
        s.status_bar()
        y = L.title(s, "Mountains 2026", "Saved search · 1,240 photos so far")
        y = L.loading_arc(s, y + px(4), 0.45, "Indexing 5,400 of 12,000 photos. More may appear.") + px(10)
        L.square_grid(s, y, s.bottom, seed=4, header=None)
        s.slab("Collections")
        card(s, ["Held sphere climbs the arc beside a plain count. No banner."])
        s.gesture_bar()
        return s

    def g2(self, theme="light", scale=1.0) -> Screen:
        s = self.scr(theme, scale)
        s.status_bar()
        y = L.title(s, "Mountains 2026", "Saved search")
        y = L.message(s, y + px(20), "Search is unavailable right now.",
                      "The photo index or photo access isn’t ready. Your saved search is kept.", button="Retry")
        s.slab("Collections")
        s.gesture_bar()
        return s

    def g3(self, theme="light", scale=2.0) -> Screen:
        s = self.scr(theme, scale)
        s.status_bar()
        y = L.title(s, "Collections")
        y = L.collection_row(s, y, "Alps weekends with Noor and Bram",
                             "Saved search · 10k+ photos", L.STRIP_RATIOS[1], 31)
        y = L.collection_row(s, y, "Unsorted", "In no album you manage · 1,204 photos", L.STRIP_RATIOS[0], 21)
        s.slab("Collections")
        card(s, ["Counts above 9,999 read 10k+. Long names wrap to 2 lines."])
        s.gesture_bar()
        return s

    def g4(self, theme="light", scale=1.0) -> Screen:
        """RTL layout (mirrored start edge) with TalkBack order. Labels stay Latin: Funnel carries no RTL script,
        so this proves the mirrored geometry only, not RTL glyph rendering."""
        s = self.scr(theme, scale)
        s.status_bar()
        y = s.top + px(12)
        f = s.font("title")
        s.d.text((DEVICE_W - px(16), y), "Collections", font=f, fill=s.t["brand_text"], anchor="ra")
        y += s.line_h("title") + px(8)
        for i, (name, line2, rk, seed) in enumerate((("Mountains 2026", "Saved search · 184 photos", 1, 31),
                                                     ("Unsorted", "In no album you manage · 1,204 photos", 0, 21))):
            x1 = DEVICE_W - px(16)
            ry = y
            s.d.text((x1 - px(24), ry), name, font=s.font("section"), fill=s.t["ink"], anchor="ra")
            ry += s.line_h("section")
            s.d.text((x1 - px(24), ry), line2, font=s.font("secondary"), fill=s.t["ink2"], anchor="ra")
            ry += s.line_h("secondary") + px(8)
            cx, cy = px(16) + px(6), y + px(10)
            s.d.line([cx + px(5), cy - px(6), cx, cy, cx + px(5), cy + px(6)], fill=s.t["ink2"], width=px(2))
            # strip anchored to the start (right) edge
            ratios = L.STRIP_RATIOS[rk]
            h, gap, xx = px(72), px(4), x1
            for k, ar in enumerate(ratios):
                w = int(h * ar)
                if xx - w < px(16):
                    break
                s.photo((xx - w, ry, xx, ry + h), seed + k, radius_dp=8)
                xx -= w + gap
            s.target([0, y - px(6), DEVICE_W, ry + h + px(6)], name)
            s.focus_order((x1 + px(2), y + px(6)), name)
            y = ry + h + px(20)
        s.slab("Collections", places=("Collections", "Pictures"))
        card(s, ["RTL: start edge on the right, chevron left, strips start right.",
                 "TalkBack: 1 Mountains 2026, 2 Unsorted, then the slab.",
                 "Reduced motion: sphere static, no climb or fade."])
        s.gesture_bar()
        return s


# Frame inventory: (code, panel, title, caption, builder name, theme-independent scale)
FRAMES = [
    ("A", "A", "Search chips", "Keyboard open. Typed chips, a Not: chip, scope and Save as album in the "
     "overflow. Back keeps the query.", "a", 1.0),
    ("B1", "B", "Save: duplicate", "Name taken: the sheet stays, query and name kept, Save off.", "b_dup", 1.0),
    ("B2", "B", "Save: failed", "Save failed: nothing lost, Retry and Cancel stay reachable.", "b_fail",
     1.0),
    ("C1", "C", "Albums, Saved searches", "Albums first, then the Saved searches heading and rows with real-shape strips.",
     "c1", 1.0),
    ("C2", "C", "Saved searches, Unsorted", "Scrolled: 2 photos, 0 photos (row stays), then Unsorted last.", "c2", 1.0),
    ("D1", "D", "Saved album", "Standard grid, Sort, shared Search. Overflow: edit, rename, remove.", "d1", 1.0),
    ("D2", "D", "Remove album", "Confirmation says media is not deleted; the photos stay visible.", "d2", 1.0),
    ("E1", "E", "Include/Exclude", "Facet menu: Include, Exclude, Remove. Exclude makes a Not: chip. OR in a family, AND across.", "e1", 1.0),
    ("E2", "E", "Zero matches", "Contradictory filters: zero is a result, Clear exclusion is one tap.", "e2", 1.0),
    ("F1", "F", "Add to album", "Two photos selected; the sheet says they leave Unsorted, files stay.",
     "f1", 1.0),
    ("F2", "F", "After add", "Count 1,204 to 1,202, Undo in the snackbar; no file moved.", "f2", 1.0),
    ("G1", "G", "Indexing", "Held sphere with a plain count; results grow in place.", "g1", 1.0),
    ("G2", "G", "Retry", "Index or permission not ready: plain words, Retry.", "g2", 1.0),
    ("G3", "G", "200% · 10k+", "Long name wraps to two lines; counts over 9,999 read 10k+.", "g3",
     2.0),
    ("G4", "G", "RTL · TalkBack", "Mirrored start edge, reading order 1-2, static sphere.", "g4", 1.0),
]

HERO = [("A", "a", "light", 1.0, "Search with chips (light, 100%)",
         "Full-scale frame at default type: the whole save entry point above the keyboard."),
        ("B1", "b_dup", "dark", 2.0, "Save sheet · duplicate (dark, 200%)",
         "Full-scale frame at 200% type: the sheet fits above the keyboard, actions stay visible; the photo strip scrolls below the fold.")]

REFERENCES = [
    ["Aves dynamic album request (issue 1370)", "Community issue about dynamic albums from filters.",
     "Persist structured live-query facets. Nothing is asserted about Aves visuals."],
    ["Aves filtered album discussion (1864)", "Discussion about creating a filtered collection as an album.",
     "Save from the existing search overflow above the bottom slab, not a copied toolbar."],
    ["Immich no-album search (issue 21001)", "Community request for “Not in any album”.",
     "Persistent virtual Unsorted row with an exact rule: in no user-managed album."],
    ["Material 3 SearchBar", "Documented expandable search and suggestion pattern.",
     "Search stays directly above the slab (Quiet Frame v3), not at the top."],
    ["Merged L5 and L7 boards", "Synthetic layout grammar for photo-led Collections and expandable search.",
     "Reused: real-shape strips, held sphere, two-place slab, one lapis identity."],
]


def build(fonts: Fonts):
    """Returns (Board, frame_records)."""
    g = Gen(fonts)
    b = Board(fonts, "saved-1-search-albums")
    b.header("SAVED-1 · save, exclude, Unsorted",
             "Synthetic design evidence for unit #779 (parent #578, goal #175). Not a device capture.",
             ["Device frame 1080 × 2340 px = 384 × 832 dp at 2.8125 px/dp; status 29 dp, gesture 16 dp, "
              "IME 324 dp are assumptions to verify on device.",
              "Media are generated gradients with real aspect ratios, no personal or stock photos.",
              "Magenta marks (inset bands, dashed target boxes with dp sizes, focus numbers, notes) are annotation, "
              "not UI. Any box under 48 dp is labelled BELOW 48 dp."])
    heroes = []
    for code, fn, theme, scale, title, cap in HERO:
        heroes.append(Panel(code, title, cap, getattr(g, fn)(theme, scale)))
    b.heroes(heroes, [
        "Frames A-G are real device frames, not text boxes: each shows its controls, the media next to them and the "
        "state named in the unit.",
        "Pairs: every state is drawn in light and dark below at half size (full frames are 1080 × 2340 in the "
        "source image of each panel).",
        "Dashed boxes are effective touch targets; the dp figure is their size.",
        "Fonts: Funnel Display and Funnel Sans (OFL), hashes in manifest.json.",
    ])
    frames = []
    panels = []
    for code, panel, title, cap, fn, scale in FRAMES:
        for theme in ("light", "dark"):
            panels.append(Panel(f"{code} {theme}", title, cap, getattr(g, fn)(theme, scale)))
    b.grid("Panels A-G, light and dark pairs (half size)", panels, cols=6, scale=0.5)
    for p in heroes + panels:
        frames.append({
            "code": p.code,
            "title": p.title,
            "theme": p.screen.theme,
            "type_scale_percent": int(p.screen.font_scale * 100),
            "scale_on_board": "100%" if p in heroes else "50%",
            "targets": [{"label": t.label, "box_px": list(t.box),
                         "size_dp": [round((t.box[2] - t.box[0]) / DENSITY), round((t.box[3] - t.box[1]) / DENSITY)]}
                        for t in p.screen.targets],
        })
    b.table("Filter semantics drawn above", ["Rule", "Meaning", "Frame"],
            [["Within one family", "OR: Place Alps OR Place Tirol", "E1"],
             ["Across families", "AND: (Alps OR Tirol) AND Year 2026", "E1"],
             ["Exclude", "Subtract the union of every excluded set; shown as Not: chips", "A, E1, E2"],
             ["Contradiction", "Zero matches with Clear exclusion; Save as album stays available (empty result is saveable)", "E2"],
             ["Unsorted", "Photos in no user-managed album, whatever camera folder holds the file", "C2, F1, F2"],
             ["Add to album", "Leaves Unsorted; the file is not moved or copied", "F1, F2"],
             ["Remove saved album", "Removes the saved search only; no media is deleted", "D2"]],
            [0.22, 0.58, 0.20])
    b.table(f"Reference decisions (checked {VERIFIED}; titles only, nothing copied)",
            ["Source", "Source evidence (not a rendering claim)", "Atlas proposal or deviation"], REFERENCES,
            [0.25, 0.35, 0.40])
    b.footer("Generated by tools/design/saved1_board.py · synthetic only · design evidence, not a device capture")
    return b, frames


def save_png(img: Image.Image, path: Path) -> None:
    q = img.quantize(colors=256, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE)
    q.save(path, optimize=True)


def font_record(src):
    if src.fallback:
        return {"status": "FALLBACK (DejaVu Sans), not for review", "sha256": {}}
    return {"status": "Funnel Display + Funnel Sans (OFL), variable weight",
            "source": "app/src/main/res/font/ (BRAND-L2, PR 720) or the directory passed in --font-dir",
            "sha256": {n: hashlib.sha256((src.directory / n).read_bytes()).hexdigest()
                       for n in ("funnel_display.ttf", "funnel_sans.ttf")}}


def check(out_dir: Path = OUT_DIR) -> int:
    man = json.loads((out_dir / "manifest.json").read_text())
    problems = []
    p = out_dir / man["artifact"]
    if not p.is_file():
        problems.append(f"missing {p.name}")
    else:
        Image.MAX_IMAGE_PIXELS = None
        with Image.open(p) as im:
            size = list(im.size)
        if size != man["board_px"]:
            problems.append(f"size {size} != {man['board_px']}")
        if hashlib.sha256(p.read_bytes()).hexdigest() != man["sha256"]:
            problems.append("sha256 differs from manifest")
        if p.stat().st_size != man["byte_size"]:
            problems.append("byte_size differs from manifest")
    if man["phone_frame_px"] != [1080, 2340]:
        problems.append("phone frame record invalid")
    if sorted({f["code"][0] for f in man["frames"]}) != list("ABCDEFG"):
        problems.append("panels A-G not all present")
    for f in man["frames"]:
        for t in f["targets"]:
            if t["size_dp"][0] < 48 or t["size_dp"][1] < 48:
                problems.append(f"{f['code']} target {t['label']} below 48 dp {t['size_dp']}")
    if man["fonts"]["status"].startswith("FALLBACK"):
        problems.append("committed board was rendered with the fallback font")
    print(("FAIL " if problems else "PASS ") + f"{man['artifact']} frames={len(man['frames'])} "
          f"size={man['board_px']}" + ("" if not problems else " " + "; ".join(problems)))
    return 1 if problems else 0


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--font-dir", help="directory with funnel_display.ttf and funnel_sans.ttf")
    ap.add_argument("--allow-fallback-font", action="store_true", help="layout smoke run, stamped NOT FOR REVIEW")
    ap.add_argument("--out", default=str(OUT_DIR))
    ap.add_argument("--finalize", action="store_true", help="write the PNG and manifest.json to --out")
    ap.add_argument("--check", action="store_true", help="verify the committed PNG against manifest.json")
    args = ap.parse_args(argv)
    out = Path(args.out)
    if args.check:
        return check(out)
    src = find_fonts(args.font_dir, REPO, args.allow_fallback_font)
    board, frames = build(Fonts(src))
    img = board.render()
    if src.fallback:
        ImageDraw.Draw(img).text((40, 8), "FALLBACK FONT · LAYOUT SMOKE RUN · NOT FOR REVIEW",
                                 font=Fonts(src).get("sans", 700, 40), fill=ANNO)
    out.mkdir(parents=True, exist_ok=True)
    path = out / ARTIFACT
    save_png(img, path)
    print(f"wrote {path} {img.size} frames={board.frames_checked}")
    if args.finalize:
        man = {
            "unit": UNIT,
            "artifact": ARTIFACT,
            "generator": "tools/design/saved1_board.py",
            "check": "python3 tools/design/saved1_board.py --check ; python3 -m unittest discover -s tools/design/tests",
            "synthetic_only": True,
            "claims": ["synthetic generated gradients only", "design evidence only", "not a runtime or device capture"],
            "board_px": list(img.size),
            "phone_frame_px": [1080, 2340],
            "canvas_dp": [384, 832],
            "px_per_dp": DENSITY,
            "geometry_assumptions": {"status_bar_dp": 29, "gesture_region_dp": 16, "ime_dp": 324,
                                     "disposition": "proposal; verify on device"},
            "hero_frames": [{"code": c, "theme": th, "type_scale_percent": int(sc * 100)}
                            for c, _fn, th, sc, _t, _c in HERO],
            "panel_ids": list("ABCDEFG"),
            "frames": frames,
            "fonts": font_record(src),
            "pillow": __import__("PIL").__version__,
            "python": ".".join(map(str, sys.version_info[:3])),
            "determinism": "no time, randomness (seeded), locale or network inputs; PNG = 256-colour palette",
            "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            "byte_size": path.stat().st_size,
            "references_checked": VERIFIED,
            "reference_decisions": [{"source": r[0], "evidence": r[1], "proposal": r[2]} for r in REFERENCES],
        }
        (out / "manifest.json").write_text(json.dumps(man, indent=2, ensure_ascii=False) + "\n")
        print(f"wrote {out / 'manifest.json'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
