#!/usr/bin/env python3
"""Render COLLECT-1 synthetic utility-collection evidence (#776).

The renderer reads no pictures. Every thumbnail is produced by ``Screen.photo``
from deterministic gradients and shapes.  ``--check`` renders in memory and
checks the frame/sheet geometry, target bounds, panel order, and source safety;
it deliberately does not require the binary PNG to be committed.
"""

from __future__ import annotations

import argparse
import ast
import json
import sys
from pathlib import Path

from PIL import Image

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from lapis_board import BOARD_W, Board, Panel  # noqa: E402
from lapis_kit import (  # noqa: E402
    DEVICE_H,
    DEVICE_W,
    IME_DP,
    NAV_DP,
    STATUS_DP,
    FontSource,
    Fonts,
    Screen,
    find_fonts,
    px,
)

REPO = HERE.parent.parent
OUT = REPO / "docs/design/lapis/preflight"
FILE = "collect-1-utilities.png"
VERIFIED = "2026-11-21"
CATEGORIES = ("Videos", "Screenshots", "Recently added", "Selfies", "Panoramas", "Slow motion", "Large files")
PANELS = ("A1", "A2", "A3", "B1", "B2", "B3", "C1", "C2", "C3", "C4", "D1", "D2", "D3", "E1", "E2", "E3")


def _title(s: Screen, text: str, subtitle: str = "") -> int:
    y = s.top + px(18)
    s.text((px(20), y), text, "title", fill=s.t["brand_text"])
    y += s.line_h("title")
    if subtitle:
        y = s.wrap(px(20), y + px(4), subtitle, DEVICE_W - px(40), "secondary")
    return y + px(14)


def _chrome(s: Screen) -> None:
    s.slab("Collections")
    s.status_bar()
    s.gesture_bar()


def _strip(s: Screen, y: int, seed: int, available: int = 4) -> int:
    """Draw only available synthetic latest items at intrinsic aspect ratios."""
    ratios = (1.50, 0.72, 1.05, 1.82)[: min(4, available)]
    h, gap, x = px(48), px(4), px(20)
    for i, ratio in enumerate(ratios):
        w = round(h * ratio)
        if x + w > DEVICE_W - px(20):
            break
        s.photo((x, y, x + w, y + h), seed + i, radius_dp=8)
        x += w + gap
    return y + h


def collections(
    theme: str = "light",
    scale: float = 1.0,
    long: bool = False,
    scrolled: bool = False,
    zero_case: bool = False,
) -> Screen:
    """Collections is scrollable: separate panels show its first and later rows.

    Empty categories are omitted, not merely displayed without a numeric zero.
    Counts and strips describe the same fictional in-scope media snapshot.
    """
    s = Screen(FONTS, theme=theme, font_scale=scale)
    y = _title(s, "Collections", "Named rows and Media types · synthetic")
    if not scrolled:
        s.text((px(20), y), "Favourites", "section")
        y = _strip(s, y + px(28), 10, available=2) + px(18)
        s.text((px(20), y), "Media types", "section")
        y += s.line_h("section") + px(10)
    else:
        s.text((px(20), y), "Media types · continued", "section")
        y += s.line_h("section") + px(10)

    # Model a stable seven-category navigation order; zero-count rows disappear.
    counts = (1284, 326, 4912, 7, 12, 3, 84)
    if zero_case:
        counts = (1284, 326, 0, 0, 12, 3, 84)
    entries = [(i, name, counts[i]) for i, name in enumerate(CATEGORIES) if counts[i] > 0]
    if scrolled:
        entries = entries[3:]
    for i, name, count in entries:
        label = name
        if long and name == "Panoramas":
            label = "Panoramas · localized extended label"
        start = y
        label_bottom = s.wrap(px(20), start, label, DEVICE_W - px(160), "body")
        s.text((DEVICE_W - px(20), start + px(6)), f"{count:,} items", "secondary",
               fill=s.t["ink2"], anchor="ra")
        strip_y = max(start + px(30), label_bottom + px(4))
        if strip_y + px(48) > s.bottom - px(108):
            break  # remaining entries are visible after Collections scrolls
        _strip(s, strip_y, 40 + i * 7, available=count)
        box = (px(16), start - px(4), DEVICE_W - px(16), strip_y + px(48))
        s.target(box, f"{name}, {count} items, latest today")
        s.focus_order(box, f"{name}: {count} items, latest today")
        y = strip_y + px(48) + px(14)

    s.note((px(8), s.bottom - px(102)),
           "Scroll continues · empty media types omitted" if zero_case else
           "Scroll continues · full category order in table")
    _chrome(s)
    return s


def category(theme: str = "light", sorted_newest: bool = True, query: str = "") -> Screen:
    s = Screen(FONTS, theme=theme)
    y = _title(s, "Screenshots", "326 items")
    # Normal square category grid, unlike Collections' genuine real-ratio strips.
    gap, side = px(2), (DEVICE_W - px(6)) // 4
    for row in range(5):
        for col in range(4):
            x = col * (side + gap)
            s.photo((x, y + row * (side + gap), x + side, y + row * (side + gap) + side), 70 + row * 4 + col)
    label = "Newest first" if sorted_newest else "Oldest first"
    s.rrect((px(20), s.bottom - px(154), px(152), s.bottom - px(106)), 24, fill=s.t["surface"])
    s.text((px(86), s.bottom - px(130)), label, "nav", anchor="mm")
    s.target((px(20), s.bottom - px(154), px(152), s.bottom - px(106)), f"Sort, {label}")
    if query:
        s.rrect((px(170), s.bottom - px(154), DEVICE_W - px(20), s.bottom - px(106)), 24, fill=s.t["surface"])
        s.text((px(190), s.bottom - px(130)), query, "body", anchor="lm")
    _chrome(s)
    return s


def search(step: int, theme: str = "light") -> Screen:
    s = Screen(FONTS, theme=theme)
    y = _title(s, "Screenshots", "326 items · category search")
    for i in range(8):
        col, row = i % 4, i // 4
        side = (DEVICE_W - px(6)) // 4
        x = col * (side + px(2))
        s.photo((x, y + row * (side + px(2)), x + side,
                 y + row * (side + px(2)) + side), 100 + i)

    # The existing L7 slab is moved above the keyboard rather than covered by it.
    keyboard_top = s.keyboard() if step <= 2 else s.bottom
    slab = s.slab("Collections", y_bottom=keyboard_top)
    if step != 4:
        bar_y = slab[1] - px(64)
        s.rrect((px(16), bar_y, DEVICE_W - px(16), bar_y + px(56)),
                24, fill=s.t["surface"])
        s.magnifier((px(42), bar_y + px(28)), px(8), s.t["ink2"])
        s.text((px(62), bar_y + px(28)),
               "receipt" if step >= 2 else "Search Screenshots", "body", anchor="lm")
        for i, label in enumerate(("This screen", "Whole library")):
            x = px(16 + i * 116)
            fill = s.t["brand_fill"] if i == 0 else s.t["surface"]
            ink = s.t["on_brand"] if i == 0 else s.t["ink"]
            s.rrect((x, bar_y - px(56), x + px(108), bar_y - px(8)), 22, fill=fill)
            s.text((x + px(54), bar_y - px(32)), label, "nav", fill=ink, anchor="mm")
            s.target((x, bar_y - px(56), x + px(108), bar_y - px(8)), label)
        if step == 3:
            s.note((DEVICE_W - px(8), bar_y - px(68)),
                   "Back 1: IME hidden; query and scope kept")
    else:
        s.note((DEVICE_W - px(8), slab[1] - px(8)),
               "Back 2: search closed; category still open")
    s.status_bar()
    s.gesture_bar()
    return s


def state(kind: str, theme: str = "light") -> Screen:
    s = Screen(FONTS, theme=theme)
    y = _title(s, "Utilities", "Fixed category order")
    if kind == "pending":
        s.sphere((px(42), y + px(26)), px(13))
        s.wrap(px(68), y + px(10), "Finding utility items, 7,140 of 12,305", DEVICE_W - px(88), "body")
    elif kind == "error":
        s.wrap(px(20), y, "Couldn't refresh utility collections.", DEVICE_W - px(40), "section")
        s.wrap(px(20), y + px(54), "Check photo access, then try again.", DEVICE_W - px(40), "body")
        s.rrect((px(20), y + px(112), px(132), y + px(160)), 24, fill=s.t["brand_fill"])
        s.text((px(76), y + px(136)), "Try again", "nav", fill=s.t["on_brand"], anchor="mm")
        s.target((px(20), y + px(112), px(132), y + px(160)), "Try again")
    else:
        s.wrap(px(20), y, "No screenshots match. Clear search", DEVICE_W - px(40), "section")
        s.wrap(px(20), y + px(54), "Clear search to restore all Screenshots.", DEVICE_W - px(40), "body")
    _chrome(s)
    return s


def huge_list() -> Screen:
    s = Screen(FONTS, theme="dark")
    y = _title(s, "Recently added", "250,128 items · lazy grid schematic")
    side = (DEVICE_W - px(6)) // 4
    for i in range(24):  # viewport only: evidence of lazy composition, not a performance result
        col, row = i % 4, i // 4
        x, yy = col * (side + px(2)), y + row * (side + px(2))
        if yy >= s.bottom - px(100):
            break
        s.photo((x, yy, x + side, min(yy + side, s.bottom - px(100))), 160 + i)
    s.note((px(8), s.bottom - px(104)), "viewport cells only; no performance claim")
    _chrome(s)
    return s


def build(fonts: Fonts) -> Board:
    global FONTS
    FONTS = fonts
    b = Board(fonts, "collect-1-utilities")
    b.header("COLLECT-1 · Utility collections and scoped search",
             "Synthetic S24+ pre-build evidence; preserves the merged L5 rows and L7 search behaviour.", [
        "Every device frame is 1080 × 2340 px (384 × 832 dp at 2.8125). Light is 100%; dark is 200% where labelled.",
        f"Insets are marked assumptions: status {STATUS_DP} dp, gesture navigation {NAV_DP} dp, keyboard {IME_DP} dp; measure on S24+.",
        "Only deterministic gradients and shapes are drawn. No private image, MediaStore, network, or app code is read.",
    ])
    heroes = [
        Panel("A1", "Collections · light · 100%", "Existing L5 named rows keep latest-photo strips at real ratios; genuine short strips do not stretch.", collections()),
        Panel("A2", "Collections · dark · 200%", "Funnel type scales; long content wraps rather than clipping into system insets.", collections("dark", 2.0, scrolled=True)),
    ]
    b.heroes(heroes, ["Magenta is annotation, never UI.", "Dashed rectangles show effective targets; every interactive row is at least 48 dp.",
        "Seven media types keep their stable order. Empty categories are omitted; screenshots are not duplicated."])
    frames = [
        Panel("A3", "Hidden empty rows", "Zero-count categories disappear while the remaining rows retain their order.", collections(zero_case=True)),
        Panel("B1", "Screenshots grid", "Opening Screenshots reuses its normal square grid.", category()),
        Panel("B2", "Sort", "Sort remains scoped to Screenshots; normal grid geometry is unchanged.", category(sorted_newest=False)),
        Panel("B3", "Scoped result", "A query filters only Screenshots unless Whole library is chosen.", category(query="receipt")),
        Panel("C1", "Search opens", "Sphere opens the L7 bar, This screen selected, and assumed 324 dp keyboard.", search(1)),
        Panel("C2", "Query", "Typed query retains This screen scope.", search(2, "dark")),
        Panel("C3", "Back once", "First Back hides the keyboard and keeps query and bar.", search(3)),
        Panel("C4", "Back twice", "Second Back closes search and returns to Screenshots.", search(4)),
        Panel("D1", "Pending", "Held sphere accompanies a plain progress count.", state("pending")),
        Panel("D2", "Error", "Cause, next step and one 48 dp retry action.", state("error", "dark")),
        Panel("D3", "Empty", "One sentence and one useful next step; no decorative chrome.", state("empty")),
        Panel("E1", "Long utility labels", "200% dark stress frame: wrapping must not overlap counts or insets.", collections("dark", 2.0, long=True, scrolled=True)),
        Panel("E2", "Lazy huge list", "250,128 synthetic items; only viewport cells are composed. No performance claim.", huge_list()),
        Panel("E3", "TalkBack and insets", "Rows are single semantic nodes in visual order; 48 dp targets and assumed insets are marked.", collections()),
    ]
    b.grid("A–E acceptance states", frames)
    b.generated_screens = [panel.screen for panel in heroes + frames]
    b.table("Fixed utility order and row contract", ["Position", "Category", "Rule"], [
        [str(i + 1), name, ("Reuse the existing destination and data contract." if name in {"Videos", "Screenshots"} else
                          "Omit if zero confirmed; never invent missing thumbnails. Latest strip may be genuinely short.")]
        for i, name in enumerate(CATEGORIES)
    ], [0.12, 0.28, 0.60])
    b.table("Observed → proposed → deviation", ["Public reference (checked 2026-11-21)", "Observed", "Atlas proposal / deviation"], [
        ["Apple Photos · Media Types\nsupport.apple.com/guide/iphone/find-photos-and-videos-iphf7de217f0/ios", "Media is grouped by type for direct entry.", "Seven deterministic utility rows; Videos and Screenshots reuse existing Atlas destinations."],
        ["Apple Photos · Utilities\nsupport.apple.com/guide/iphone/find-photos-and-videos-iphf7de217f0/ios", "Utilities expose task- or state-oriented groups.", "Keep type entry in Collections; rows with confirmed zero items disappear, with no extra Utilities hub."],
        ["Google Photos · Recently Added\nsupport.google.com/photos/answer/6128843", "Recently added is a chronological collection.", "Recently added uses proposed last-30-days DATE_ADDED; this horizon is an Atlas proposal, not a Google claim."],
        ["Android Material 3 · SearchBar\ndeveloper.android.com/develop/ui/compose/components/search-bar", "Expanded search holds suggestions or filtered results.", "Preserve L7 bottom expansion, This screen / Whole library scopes and two Back presses; deviation from a top SearchBar."],
    ], [0.30, 0.30, 0.40], "Observations are dated documentation comparisons, not claims that private design boards were inspected.")
    b.notes("Accessibility and implementation gates", [
        "TalkBack order follows the visual order: heading, Favourites, Media types, then visible categories. Each row merges label, count and latest date into one node.",
        "All interactive rows and chips expose at least 48 × 48 dp. Status 29 dp, gesture 16 dp and IME 324 dp are assumptions to measure on the target S24+. If all media types are empty, omit their section.",
        "The huge-list panel proves layout intent only. Post-build tests must establish lazy composition, stable keys and performance with a synthetic large library.",
        "Funnel Display and Funnel Sans plus Quiet Frame v3 tokens are inherited from the merged L5/L7 generator; app behaviour is unchanged.",
    ])
    b.footer(f"Generated by tools/design/collect_1_utilities.py · synthetic only · references checked {VERIFIED} · not a device capture")
    return b


def _source_is_safe() -> None:
    tree = ast.parse(Path(__file__).read_text(encoding="utf-8"))
    forbidden = {"open", "glob", "rglob", "iterdir", "walk", "scandir"}
    for node in ast.walk(tree):
        if isinstance(node, ast.Call):
            name = getattr(node.func, "attr", getattr(node.func, "id", ""))
            if name in forbidden:
                raise AssertionError(f"private-asset safety: forbidden filesystem reader {name}()")


def validate(board: Board, image: Image.Image) -> None:
    _source_is_safe()
    if image.width != BOARD_W or image.height <= DEVICE_H:
        raise AssertionError(f"invalid sheet bounds {image.size}")
    if tuple(board.panel_codes) != PANELS:
        raise AssertionError(f"panel order is {tuple(board.panel_codes)}, expected {PANELS}")
    if board.frames_checked != len(PANELS):
        raise AssertionError("not every panel contains one checked 1080x2340 frame")
    for screen in board.generated_screens:
        if screen.img.size != (DEVICE_W, DEVICE_H):
            raise AssertionError(f"device frame is {screen.img.size}")
        for target in screen.targets:
            x0, y0, x1, y1 = target.box
            if x0 < 0 or y0 < 0 or x1 > DEVICE_W or y1 > DEVICE_H:
                raise AssertionError(f"target outside frame: {target.label} {target.box}")
            if min(x1 - x0, y1 - y0) < px(48) - 1:
                raise AssertionError(f"target below 48dp: {target.label} {target.box}")


def save(image: Image.Image, path: Path) -> None:
    image.quantize(colors=256, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE).save(path, optimize=True)


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--font-dir")
    ap.add_argument("--allow-fallback-font", action="store_true")
    ap.add_argument("--out", default=str(OUT))
    ap.add_argument("--check", action="store_true", help="render in memory and validate dimensions, bounds and source safety")
    args = ap.parse_args(argv)
    fonts = Fonts(find_fonts(args.font_dir, REPO, args.allow_fallback_font or args.check))
    board = build(fonts)
    image = board.render()
    validate(board, image)
    if args.check:
        print(f"OK: {FILE} {image.width}x{image.height}; {board.frames_checked} frames; synthetic-source safety passed")
        return 0
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    save(image, out / FILE)
    return 0


FONTS = Fonts(FontSource(None, True))

if __name__ == "__main__":
    raise SystemExit(main())
