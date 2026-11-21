#!/usr/bin/env python3
"""Generate the five synthetic Lapis L4-L8 design-preflight sheets (atlas-gallery #753).

    python3 tools/design/lapis_preflight.py --font-dir <dir with funnel_*.ttf>   # render all five
    python3 tools/design/lapis_preflight.py --only l6                             # one sheet
    python3 tools/design/lapis_preflight.py --check                               # verify committed sheets

Output: docs/design/lapis/preflight/brand-l{4..8}-*.png plus manifest.json.
Everything drawn is synthetic. No network, private media or location data is read.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from PIL import Image, ImageDraw  # noqa: E402

import lapis_screens as L  # noqa: E402
from lapis_board import Board, Panel  # noqa: E402
from lapis_kit import (  # noqa: E402
    DEVICE_W,
    IME_DP,
    NAV_DP,
    STATUS_DP,
    Fonts,
    Screen,
    find_fonts,
    px,
)

REPO = HERE.parent.parent
OUT = REPO / "docs/design/lapis/preflight"
VERIFIED = "2026-11-21"
ISSUE = "https://github.com/LPStruiksma/atlas-gallery/issues/"

# Public sources opened and checked on VERIFIED; the quoted observation is what the page says.
REF = {
    "navbar": ("Android Developers, Compose Navigation bar",
               "developer.android.com/develop/ui/compose/components/navigation-bar",
               "Use navigation bars for three to five destinations of equal importance."),
    "touch": ("Google Android Accessibility Help, touch target size",
              "support.google.com/accessibility/android/answer/7101858",
              "Interactive elements should be at least 48dp wide and 48dp high."),
    "anim": ("Android reference, Settings.Global.ANIMATOR_DURATION_SCALE",
             "developer.android.com/reference/android/provider/Settings.Global",
             "A system scaling factor for Animator-based animations."),
    "searchbar": ("Android Developers, Compose Search bar",
                  "developer.android.com/develop/ui/compose/components/search-bar",
                  "SearchBar has an expanded state that shows suggestions or filtered results below the input field."),
    "find5": ("ATLAS FIND-5 design frames (#341)",
              "github.com/LPStruiksma/atlas-gallery/blob/main/docs/design/declutter-search-341/README.md",
              "Top DropdownMenu panel, field not auto-focused, This screen / Whole library chips; Back hides the "
              "keyboard first, a second Back closes the panel."),
    "sheet": ("Android Developers, Compose Bottom sheets",
              "developer.android.com/develop/ui/compose/components/bottom-sheets",
              "ModalBottomSheet: a sheet that rises from the bottom over content and is dismissed through "
              "onDismissRequest."),
    "nested": ("Android Developers, Compose nested scrolling",
               "developer.android.com/develop/ui/compose/touch-input/pointer-input/nested-scroll",
               "Nested scrolling links parent and child scroll containers by sharing scroll deltas."),
    "semantics": ("Android Developers, Compose semantics",
                  "developer.android.com/develop/ui/compose/accessibility/semantics",
                  "Modifier.semantics(mergeDescendants = true) merges a composable's children into one node."),
    "people": ("ATLAS People home today (main)",
               "atlas-gallery main: app/src/main/java/nl/atlas/gallery/featurepeopleui/PeopleHomeScreen.kt",
               "People are entered through circular (CircleShape) face crops."),
    "grid": ("Android Developers, Compose lists and grids",
             "developer.android.com/develop/ui/compose/lists",
             "LazyVerticalGrid forms equal columns, e.g. GridCells.Adaptive(minSize = 128.dp)."),
    "justified": ("flickr justified-layout (npm package README)",
                  "www.npmjs.com/package/justified-layout",
                  "Pass in box aspect ratios and get back sizes and coordinates for a justified layout."),
    "frames": ("Android reference, FrameMetrics",
               "developer.android.com/reference/android/view/FrameMetrics",
               "Per-frame timing data for a window, used for jank measurement."),
}


def ref_row(key: str, adaptation: str) -> list[str]:
    name, url, obs = REF[key]
    return [f"{name}\n{url}\n(checked {VERIFIED})", obs, adaptation]


DECISION_HEAD = ["Source", "Observed convention", "Atlas adaptation or deviation, and why"]
DECISION_W = [0.30, 0.28, 0.42]
DECISION_NOTE = ("Rows compare publicly documented conventions only. The private Round 3, Continuum and Lapis "
                 "boards were not inspected for this sheet and are not credited with any behaviour shown here.")


class Gen:
    def __init__(self, fonts: Fonts):
        self.fonts = fonts
        self._l8_cache: dict = {}

    def scr(self, theme="light", scale=1.0, viewer=False) -> Screen:
        return Screen(self.fonts, theme=theme, font_scale=scale, viewer=viewer)

    def facts(self, unit: int, extra: str = "") -> list[str]:
        f = [
            f"Synthetic pre-build design evidence for #753 and {ISSUE}{unit}. Not a device screenshot, emulator "
            "capture, implementation or performance measurement.",
            f"Device frame 1080 x 2340 px = 384 x 832 dp at density 2.8125 (S24+ FHD+ default, as in FIND-5). "
            f"Insets: status {STATUS_DP} dp and gesture {NAV_DP} dp assumed; keyboard {IME_DP} dp = CI test IME "
            "(#437). Measure the real S24+ before merge.",
            f"Colour: Quiet Frame v3 tokens only. Type: {self.fonts.src.describe()}. Pictures are generated "
            "gradients; names and places are fictional; the map is schematic dots.",
            "Annotation layer in magenta #E0007A (outside the palette, never UI): dashed box = effective touch "
            "target with its size; numbered dot = TalkBack focus order; tinted band = system inset; ring and "
            "arrow = illustrative gesture.",
        ]
        if extra:
            f.append(extra)
        return f

    LEGEND = [
        "Large frames are full scale (1080 x 2340). Small frames are the same size frames scaled to 50 %.",
        "Every dashed box is an effective touch target. Visuals smaller than 48 dp sit inside a padded 48 dp area "
        "(Compose minimumInteractiveComponentSize); 'BELOW 48 dp' marks a real gap for review.",
        "Numbers in dots give the TalkBack traversal order where a frame shows it.",
        "Gestures are drawn, not recorded. Motion is shown as stills plus a stated duration; with Remove "
        "animations on, every transition cuts.",
        "Copy in frames is proposed, plain sentence case, Dutch number grouping as in Quiet Frame (12.305).",
    ]

    # ------------------------------------------------------------------ L4
    def l4(self) -> Board:
        b = Board(self.fonts, "brand-l4-navigation")
        b.header("BRAND-L4 · Slab navigation and the held sphere",
                 "Two places and search in a short lapis slab; one sphere for current, chosen and loading.",
                 self.facts(657))

        a1 = self.scr()
        y, _ = L.pictures(a1, thumb=(0.12, "Nov"), header="Today")
        a1.focus_order((L.px(L.SIDE), a1.top + L.px(20)), "Pictures, heading")
        a1.focus_order((L.px(20), y + L.px(60)), "Photo, 14 November")
        x0, y0, x1, y1 = a1.slab_box
        a1.focus_order((x0 + L.px(14), y0 + L.px(6)), "Pictures, tab, selected")
        a1.focus_order((x0 + L.px(124), y0 + L.px(6)), "Collections, tab")
        a1.focus_order((x1 - L.px(40), y0 + L.px(6)), "Search")
        a1.note((DEVICE_W - L.px(8), y1 + L.px(2)), "slab 24 dp radius, 12 dp above gesture inset")
        a2 = self.scr("dark", 2.0)
        L.pictures(a2, thumb=(0.12, "Nov"), header="Today")
        b.heroes([
            Panel("A1", "Pictures, light, 100 %", "Slab: Pictures (white pill, lapis text), Collections, search "
                  "sphere. 46 dp month sphere at the thumb inside a 48 dp target. Focus order 1-5.", a1),
            Panel("A2", "Pictures, dark, 200 %", "Pills grow with the label. At 200 % they fill the width, so the "
                  "search sphere detaches above the slab's right end (proposal to review). Tiles stay clear of "
                  "both insets.", a2),
        ], self.LEGEND)

        panels = []
        c1 = self.scr()
        L.collections_legacy(c1)
        panels.append(Panel("B1", "Collections, light", "Active pill moves to Collections. Until L5 lands it hosts "
                            "today's Albums, Stories and People screens unchanged.", c1))
        c2 = self.scr("dark", 2.0)
        L.collections_legacy(c2)
        panels.append(Panel("B2", "Collections, dark, 200 %", "Section titles and counts wrap or scale; slab stays "
                            "above the gesture inset.", c2))
        for i, (frac, mon, seed) in enumerate(((0.12, "Nov", 1), (0.48, "Aug", 4), (0.82, "Mar", 9)), 1):
            s = self.scr()
            months = {"Nov": "November 2026", "Aug": "August 2026", "Mar": "March 2026"}
            s.status_bar()
            yy = L.title(s, "Pictures")
            L.square_grid(s, yy, s.bottom, seed=seed, header=months[mon])
            cx, cy = L.fast_thumb(s, frac, mon, yy + L.px(8), s.bottom - L.px(110), dragging=True)
            if i < 3:
                L.finger(s, (cx, cy), (cx, cy + L.px(120)), "drag")
            s.slab("Pictures")
            s.gesture_bar()
            panels.append(Panel(f"C{i}", f"Fast scroll {i}/3", f"Drag the month sphere; it shows '{mon}' and the "
                                "grid jumps to that month. Synthetic storyboard, not a recording.", s))
        s = self.scr()
        L.atlas_panel(s, 1.0)
        L.square_grid(s, s.top + L.px(560), s.bottom, seed=2)
        s.slab("Pictures")
        s.status_bar()
        s.gesture_bar()
        panels.append(Panel("C4", "Tap the month sphere", "A tap (not a drag) on the sphere opens the atlas over "
                            "the grid (L6 handoff).", s))
        d1 = self.scr()
        d1.status_bar()
        d1.text((L.px(L.SIDE), d1.top + L.px(18)), "3 selected", "section")
        L.square_grid(d1, d1.top + L.px(60), d1.bottom, seed=1, header="Today", selected={1: 1, 2: 2, 5: 3})
        d1.slab("Pictures")
        d1.gesture_bar()
        panels.append(Panel("D1", "Ordered selection of 3", "Long press anywhere. Lapis sphere on the tile corner, "
                            "2 dp white halo, pick number 1-3.", d1))
        d2 = self.scr("dark", 2.0)
        d2.status_bar()
        d2.text((L.px(L.SIDE), d2.top + L.px(18)), "3 selected", "section")
        L.square_grid(d2, d2.top + L.px(80), d2.bottom, seed=1, header="Today", selected={1: 1, 2: 2, 5: 3})
        d2.slab("Pictures")
        d2.gesture_bar()
        panels.append(Panel("D2", "Selection, dark, 200 %", "Halo keeps the sphere readable on any picture. "
                            "Numbers stay at a fixed size inside the 24 dp sphere.", d2))
        e1 = self.scr()
        e1.status_bar()
        yy = L.title(e1, "Pictures")
        L.square_grid(e1, yy, e1.bottom, seed=1, header="Today")
        kb = e1.keyboard()
        sb = e1.slab("Pictures", y_bottom=kb, search=False)
        L.search_bar(e1, sb[1], "", True, entries=True)
        panels.append(Panel("E1", "Search sphere › AtlasSearch", "Tap the sphere: the full AtlasSearch bar grows "
                            "above the slab and the keyboard opens (full flow on the L7 sheet).", e1))
        e2 = self.scr()
        e2.status_bar()
        yy = L.title(e2, "Pictures")
        L.square_grid(e2, yy, e2.bottom, seed=1, header="Today")
        sb = e2.slab("Pictures", search=False)
        L.search_bar(e2, sb[1], "beach", False)
        e2.note((DEVICE_W - L.px(8), sb[1] - L.px(150)), "Back 1: keyboard hides, query kept")
        panels.append(Panel("E2", "Back 1", "System Back first hides the keyboard; the bar and query stay.", e2))
        e3 = self.scr()
        L.pictures(e3, header="Today")
        e3.note((DEVICE_W - L.px(8), e3.slab_box[1] - L.px(30)), "Back 2: bar collapses into the sphere")
        panels.append(Panel("E3", "Back 2", "A second Back collapses the bar. A third Back leaves the app as "
                            "today. System Back is never intercepted beyond these steps.", e3))
        f1 = self.scr(viewer=True)
        L.viewer(f1)
        panels.append(Panel("F1", "Viewer", "Black ground, actions on the #3448E0 slab, current filmstrip item "
                            "with a #8C9BFF sphere and 2 dp white ring.", f1))
        g1 = self.scr()
        g1.status_bar()
        yy = L.title(g1, "Pictures")
        yy = L.loading_arc(g1, yy - L.px(6), 0.58, "Finding faces, 7.140 of 12.305")
        L.square_grid(g1, yy, g1.bottom, seed=3, header="Today")
        g1.slab("Pictures")
        g1.gesture_bar()
        panels.append(Panel("G1", "Loading", "The sphere climbs an arc beside a plain count. No banner; the grid "
                            "stays usable.", g1))
        g2 = self.scr()
        g2.status_bar()
        yy = L.title(g2, "Pictures")
        L.message(g2, yy + L.px(8), "No pictures on this phone yet.",
                  "Take a photo, or copy pictures to this phone, and they appear here.")
        g2.slab("Pictures")
        g2.gesture_bar()
        panels.append(Panel("G2", "Empty library", "One sentence and one next step. Slab and search stay.", g2))
        g3 = self.scr("dark")
        g3.status_bar()
        yy = L.title(g3, "Pictures")
        L.message(g3, yy + L.px(8), "Couldn't read your pictures.",
                  "Check that Atlas may use photos and videos, then try again.", "Try again")
        g3.slab("Pictures")
        g3.gesture_bar()
        panels.append(Panel("G3", "Error, dark", "Plain cause and a 48 dp retry. No new chrome.", g3))
        g4 = self.scr()
        g4.status_bar()
        yy = L.title(g4, "Pictures", "148.912 photos and 3.204 videos")
        L.square_grid(g4, yy, g4.bottom, cols=6, seed=8, header="March 2014")
        L.fast_thumb(g4, 0.7, "2014", yy + L.px(8), g4.bottom - L.px(110))
        g4.slab("Pictures")
        g4.gesture_bar()
        panels.append(Panel("G4", "Large library", "Year-range thumb shows the year once months get dense. "
                            "Same sphere and target.", g4))
        h1 = self.scr()
        h1.status_bar()
        yy = L.title(h1, "Pictures")
        boxes = L.square_grid(h1, yy, h1.bottom, seed=1, header="Today", selected={1: 1})
        bx = boxes[1]
        for k, dy in enumerate((26, 13)):
            r = L.px(12)
            cx, cy = bx[0] + L.px(20), bx[1] + L.px(20) + L.px(dy)
            h1.d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=L.ANNO, width=L.px(1.5))
        h1.note((L.px(8), bx[3] + L.px(4)), "Lift: tile rises, sphere arrives from below, 200 ms")
        h1.slab("Pictures")
        h1.gesture_bar()
        panels.append(Panel("H1", "Motion on", "Ghost rings mark the sphere at 0 and 100 ms; solid at 200 ms. "
                            "Nothing moves on its own.", h1))
        h2 = self.scr()
        h2.status_bar()
        yy = L.title(h2, "Pictures")
        L.square_grid(h2, yy, h2.bottom, seed=1, header="Today", selected={1: 1})
        h2.note((L.px(8), yy + L.px(200)), "Remove animations on: end state at once, 0 ms")
        h2.slab("Pictures")
        h2.gesture_bar()
        panels.append(Panel("H2", "Remove animations on", "Same outcome, no transition. Applies to slab, sphere, "
                            "search and atlas.", h2))
        b.grid("States and storyboards", panels)

        b.table("Reference decisions", DECISION_HEAD, [
            ref_row("navbar", "Deviation: two places (Pictures, Collections) plus a search sphere instead of 3-5 "
                    "tabs. Samsung's tab positions do not bind (Quiet Frame v3); every former tab destination stays "
                    "reachable: Albums, Stories and People sit in Collections (B1), Search is the sphere."),
            ref_row("touch", "Adopted: every slab pill is 104 x 48 dp or larger, the search sphere and the 46 dp "
                    "month sphere sit in 48 dp effective targets, filmstrip and viewer actions are padded to 48 dp."),
            ref_row("anim", "Adopted: Lift is 200 ms at scale 1; Atlas reads the system scale and cuts every "
                    "transition when animations are removed (H2)."),
        ], DECISION_W, DECISION_NOTE)
        b.table("Parity and accessibility", ["Item", "Kept or changed", "Notes"], [
            ["System Back", "Kept", "Back hides keyboard, then collapses search, then leaves as today (E1-E3)."],
            ["Long press to select", "Kept", "Anywhere in the grid; ordered numbers shown (D1)."],
            ["Fast scroll", "Changed look", "Held sphere at the thumb; tap opens the atlas (C1-C4)."],
            ["Search", "Placement only", "Same AtlasSearch suite (#175), entered from the sphere (E1)."],
            ["TalkBack names", "Proposed", "'Pictures, tab, selected', 'Collections, tab', 'Search', 'Month "
             "scroller, November 2026', 'Photo, 14 November, selected, 1 of 3'."],
            ["Label truncation", "Checked at 200 %", "Pills widen instead of truncating; sphere detaches when they "
             "fill the width (A2)."],
        ], [0.2, 0.18, 0.62])
        b.footer(f"Generated by tools/design/lapis_preflight.py · synthetic only · references checked {VERIFIED} · "
                 "not a device capture")
        return b

    # ------------------------------------------------------------------ L5
    def l5_collections(self, s: Screen, people_inline=True, focus=False):
        s.status_bar()
        y = L.title(s, "Collections")
        rows = [
            ("Albums", "24 albums, latest 12 November 2026", 0, 11),
            ("Noor", "1.204 photos, latest 14 November 2026", 1, 21) if people_inline else None,
            ("Bram", "866 photos, latest 9 November 2026", 2, 31) if people_inline else None,
            ("People and pets", "500 people and 3 pets", 3, 41),
            ("Places", "38 places, latest Amsterdam", 0, 51),
            ("Stories", "8 stories, latest 2 November 2026", 1, 61),
            ("Favourites", "212 photos, latest 13 November 2026", 2, 71),
        ]
        k = 0
        for row in rows:
            if row is None:
                continue
            k += 1
            name, line, rk, seed = row
            if y > s.bottom - L.px(200):
                break
            y = L.collection_row(s, y, name, line, L.STRIP_RATIOS[rk], seed,
                                 focus_label=f"{name}, {line}" if focus else None)
        s.slab("Collections")
        s.gesture_bar()

    def l5(self) -> Board:
        b = Board(self.fonts, "brand-l5-collections")
        b.header("BRAND-L5 · Collections as photo-strip rows",
                 "Each collection is a row: name, a plain count and date, and its latest photos at real shape.",
                 self.facts(658))
        a1 = self.scr()
        self.l5_collections(a1, focus=True)
        a2 = self.scr("dark", 2.0)
        self.l5_collections(a2)
        b.heroes([
            Panel("A1", "Collections, light, 100 %", "Rows in brief order. The two most recent people appear as "
                  "their own rows so a person is two taps from anywhere. Strip: latest 4 photos, real shape, "
                  "8 dp radius. TalkBack reads one merged node per row (dots).", a1),
            Panel("A2", "Collections, dark, 200 %", "Names and counts wrap to two lines; strip height stays 72 dp; "
                  "each row remains one target.", a2),
        ], self.LEGEND)
        panels = []
        s = self.scr()
        s.status_bar()
        y = L.title(s, "Row variants")
        y = L.collection_row(s, y, "Four shapes", "4 photos: portrait, panorama, square, tall", L.STRIP_RATIOS[0], 5)
        y = L.collection_row(s, y, "Partial", "2 photos, latest 3 November 2026", (1.33, 0.75), 15)
        y = L.collection_row(s, y, "One photo", "1 photo, 1 October 2026", (1.0,), 25)
        y = L.collection_row(s, y, "Portrait-heavy", "64 photos, latest 8 November 2026", (0.56, 0.6, 0.75, 0.56,
                                                                                            0.66), 35)
        y = L.collection_row(s, y, "Road trip", "No photos yet", None, 0,
                             empty_text="No photos in this album yet. Add photos from Pictures to fill it.")
        s.gesture_bar()
        panels.append(Panel("B1", "Row anatomy", "No circular crops, no letterbox, no cropping: each thumbnail "
                            "keeps its shape at 72 dp height, aligned left, 4 dp apart.", s))
        s = self.scr("light", 2.0)
        s.status_bar()
        y = L.title(s, "Collections")
        y = L.collection_row(s, y, "Summer holiday with the whole family at the coast",
                             "1.912 photos and 140 videos, latest 30 August 2026", L.STRIP_RATIOS[1], 45)
        y = L.collection_row(s, y, "Screenshots", "3.401 screenshots", L.STRIP_RATIOS[2], 55)
        s.slab("Collections")
        s.gesture_bar()
        panels.append(Panel("B2", "Long labels, 200 %", "Names wrap to two lines, then end with an ellipsis; "
                            "TalkBack reads the full name.", s))
        s = self.scr()
        L.pictures(s, header="Today")
        x0, y0, x1, y1 = s.slab_box
        L.finger(s, (x0 + L.px(160), (y0 + y1) // 2), None, "tap 1")
        panels.append(Panel("C1", "Tap path 1", "From Pictures (or any screen with the slab): Collections.", s))
        s = self.scr()
        self.l5_collections(s)
        L.finger(s, (L.px(200), s.top + L.px(230)), None, "tap 2: Noor")
        panels.append(Panel("C2", "Tap path 2", "Recent people, albums and places have their own rows: tap 2 "
                            "opens the person. Others: search in Collections (D2).", s))
        s = self.scr()
        s.status_bar()
        y = L.title(s, "Noor", "1.204 photos, latest 14 November 2026")
        L.square_grid(s, y, s.bottom, seed=21, header="November 2026")
        s.note((DEVICE_W - L.px(8), s.top + L.px(4)), "existing person page, unchanged")
        s.gesture_bar()
        panels.append(Panel("C3", "Person opened", "The existing person screen opens; Back returns to "
                            "Collections, a second Back to Pictures.", s))
        s = self.scr()
        s.status_bar()
        y = L.title(s, "People and pets", "500 people and 3 pets")
        chips = ["Most photos", "Name", "Latest"]
        xx = L.px(L.SIDE)
        for i, c in enumerate(chips):
            f = s.font("nav")
            w = s.d.textlength(c, font=f) + L.px(28)
            bb = [xx, y, xx + w, y + L.px(36)]
            s.rrect(bb, 24, fill=s.t["brand_fill"] if i == 0 else s.t["surface"])
            s.d.text(((bb[0] + bb[2]) / 2, (bb[1] + bb[3]) / 2), c, font=f,
                     fill=s.t["on_brand"] if i == 0 else s.t["ink"], anchor="mm")
            s.target(bb, c)
            xx += w + L.px(8)
        y += L.px(52)
        for i, (n, c) in enumerate((("Noor", "1.204 photos"), ("Bram", "866 photos"), ("Sem", "702 photos"),
                                    ("Lotte", "655 photos"), ("Pip (dog)", "540 photos"))):
            y = L.collection_row(s, y, n, f"{c}, latest {14 - i} November 2026", L.STRIP_RATIOS[i % 4], 80 + i * 7)
        sb = s.slab("Collections", search=False)
        L.search_bar(s, sb[1], "", False, scope="This screen")
        panels.append(Panel("D1", "500 people", "Sort by most photos, name or latest; scoped search sits above "
                            "the slab, so nobody scrolls 500 rows.", s))
        s = self.scr()
        s.status_bar()
        y = L.title(s, "People and pets")
        y = L.collection_row(s, y, "Noor", "1.204 photos, latest 14 November 2026", L.STRIP_RATIOS[0], 21)
        y = L.collection_row(s, y, "Nora", "88 photos, latest 2 June 2026", L.STRIP_RATIOS[3], 91)
        kb = s.keyboard(["noor", "nora", "north"])
        sb = s.slab("Collections", y_bottom=kb, search=False)
        L.search_bar(s, sb[1], "no", True, scope="This screen")
        panels.append(Panel("D2", "Scoped search", "'This screen' searches people only; 'Whole library' hands "
                            "the query to AtlasSearch (#175). Matches stay above the keyboard.", s))
        s = self.scr()
        s.status_bar()
        y = L.title(s, "Collections")
        y = L.collection_row(s, y, "Videos", "45 videos, latest 13 November 2026", L.STRIP_RATIOS[3], 121)
        y = L.collection_row(s, y, "Recent", "Added in the last 30 days, 318 photos", L.STRIP_RATIOS[0], 131)
        y = L.collection_row(s, y, "Categories", "Screenshots, documents, food and 9 more", L.STRIP_RATIOS[2],
                             141)
        y = L.collection_row(s, y, "Shared albums", "3 shared albums, latest 10 November 2026",
                             L.STRIP_RATIOS[1], 151)
        s.slab("Collections")
        s.gesture_bar()
        panels.append(Panel("D3", "Further down the list", "Videos, Recent, Categories (screenshots and the "
                            "rest) and Shared albums keep their own rows; each opens today's screen.", s))
        s = self.scr()
        s.status_bar()
        y = L.title(s, "Collections")
        y = L.collection_row(s, y, "Cleanup suggestions", "312 similar photos and 40 large videos",
                             L.STRIP_RATIOS[2], 101)
        y = L.collection_row(s, y, "Trash", "14 items, removed for good after 30 days", L.STRIP_RATIOS[1], 111)
        y = L.collection_row(s, y, "Hidden", "Locked. Unlock to see what's inside.", None, 0,
                             empty_text="Thumbnails stay hidden until you unlock.")
        y = L.collection_row(s, y, "Settings", "Backup, display and privacy", None, 0)
        s.slab("Collections")
        s.gesture_bar()
        panels.append(Panel("E1", "End of list", "Trash shows its items and the 30-day rule; Hidden never "
                            "shows thumbnails before unlock.", s))
        s = self.scr()
        s.status_bar()
        y = L.title(s, "Collections")
        y = L.loading_arc(s, y - L.px(6), 0.25, "Grouping people, 3.120 of 12.305")
        y = L.collection_row(s, y, "Albums", "24 albums, latest 12 November 2026", L.STRIP_RATIOS[0], 11)
        y = L.collection_row(s, y, "People and pets", "Still grouping faces", None, 0,
                             empty_text="People appear here as faces are grouped.")
        s.slab("Collections")
        s.gesture_bar()
        panels.append(Panel("E2", "Loading", "Rows that are ready show; the sphere arc and count sit under the "
                            "title.", s))
        s = self.scr("dark")
        s.status_bar()
        y = L.title(s, "Collections")
        L.message(s, y + L.px(8), "Couldn't load your collections.", "Your pictures are safe. Try again.",
                  "Try again")
        s.slab("Collections")
        s.gesture_bar()
        panels.append(Panel("E3", "Error, dark", "Plain message and a 48 dp retry.", s))
        b.grid("States and tap paths", panels, cols=5, scale=0.5)
        b.table("Tap counts (from any screen with the slab)", ["Goal", "Taps", "Path"], [
            ["A recent person (top 2)", "2", "Collections › person row (C1-C3)."],
            ["Any of 500 people", "2 + typing", "Collections › search sphere, type, pick (D2); or 3 taps via People "
             "and pets."],
            ["An album", "2", "Collections › album row (recent albums inline, as people)."],
            ["A place or trip", "2", "Collections › place row; or Pictures › atlas place (L6)."],
            ["Back", "1 per level", "System Back returns along the same path; nothing is intercepted."],
        ], [0.25, 0.12, 0.63])
        b.table("Destination parity (every former entry keeps a row)", ["Destination", "Row and order",
                                                                         "Opens", "Shown in"], [
            ["Albums", "1 · count and latest date", "Existing Albums screen", "A1, C2"],
            ["People and pets", "2 · recent people as own rows, then the section row", "Existing People screen "
             "and person page", "A1, C2, C3, D1, D2"],
            ["Places", "3", "Existing Places screen; also the L6 atlas", "A1"],
            ["Stories", "4", "Existing Stories screen", "A1"],
            ["Favourites", "5", "Existing Favourites view", "A1"],
            ["Videos", "6", "Existing Videos view", "D3"],
            ["Recent", "7", "Existing Recent view", "D3"],
            ["Categories, incl. Screenshots", "8 · one row, sub-categories inside", "Existing category screens",
             "D3"],
            ["Shared albums", "9", "Existing Shared albums screen", "D3"],
            ["Cleanup suggestions, Trash, Hidden / Secure folder, Settings", "10-13 · end of list",
             "Existing screens; Hidden asks to unlock first", "E1"],
        ], [0.24, 0.28, 0.3, 0.18], "Two taps to the row's screen from any screen with the slab (Collections, then "
           "the row); rows below the fold need a scroll, not an extra tap. Sort and scoped search per #175 still "
           "apply inside each destination.")
        b.table("Reference decisions", DECISION_HEAD, [
            ref_row("people", "Deviation: no circles. People rows show name, count, latest date and a strip of real "
                    "photos, as Quiet Frame v3 requires; the person screen behind the row is unchanged."),
            ref_row("semantics", "Adopted: one merged TalkBack node per row, read as 'Noor, 1.204 photos, latest "
                    "14 November 2026, button'. Strip thumbnails are not separate stops."),
            ref_row("searchbar", "Adapted: the bar sits above the slab (L7) with This screen / Whole library; "
                    "#175 sort, scoped search and destinations are kept."),
        ], DECISION_W, DECISION_NOTE)
        b.footer(f"Generated by tools/design/lapis_preflight.py · synthetic only · references checked {VERIFIED} · "
                 "not a device capture")
        return b

    # ------------------------------------------------------------------ L6
    def l6_frame(self, theme="light", scale=1.0, frac=1.0, hint=False, finger=None, **kw):
        s = self.scr(theme, scale)
        s.status_bar()
        y = L.title(s, "Pictures")
        if hint:
            s.text((L.px(L.SIDE), y - L.px(4)), "Pull down for your atlas", "secondary", fill=s.t["ink2"])
            y += s.line_h("secondary") + L.px(4)
        L.square_grid(s, y, s.bottom, seed=kw.pop("seed", 1), header=kw.pop("header", "Today"))
        if frac > 0:
            bottom = L.atlas_panel(s, frac, **kw)
            if finger:
                L.finger(s, (DEVICE_W // 2, bottom - L.px(30)), (DEVICE_W // 2, bottom - L.px(30) + finger),
                         "finger follows" if finger > 0 else "push up")
        s.slab("Pictures")
        s.gesture_bar()
        return s

    def l6(self) -> Board:
        b = Board(self.fonts, "brand-l6-atlas")
        b.header("BRAND-L6 · The atlas over Pictures",
                 "Pull down to see this year's months and your places; tap one to move the grid there.",
                 self.facts(659))
        a1 = self.l6_frame(frac=1.0)
        a2 = self.l6_frame("dark", 2.0, frac=1.0)
        b.heroes([
            Panel("A1", "Atlas open, light, 100 %", "Month bars sized by count (tallest labelled), sphere on the "
                  "current month, schematic dot map with labelled places. Bars are 29 dp wide: see the flagged "
                  "targets and the 2 x 6 alternative (C6).", a1),
            Panel("A2", "Atlas open, dark, 200 %", "Month labels shorten to one letter; the year and totals scale. "
                  "The atlas stops above the slab and the gesture inset.", a2),
        ], self.LEGEND)
        panels = [
            Panel("B1", "Closed, first use", "Hint line under the title until the atlas has been opened once.",
                  self.l6_frame(frac=0, hint=True)),
            Panel("B2", "Mid-pull, 50 %", "The atlas follows the finger; release past half opens, before half "
                  "closes.", self.l6_frame(frac=0.5, finger=L.px(90))),
            Panel("B3", "Open", "Grid stays underneath; nothing moves on its own.", self.l6_frame(frac=1.0)),
        ]
        s = self.scr()
        s.status_bar()
        y = L.title(s, "Pictures")
        L.square_grid(s, y, s.bottom, seed=4, header="August 2026")
        L.fast_thumb(s, 0.48, "Aug", y + L.px(8), s.bottom - L.px(110))
        s.slab("Pictures")
        s.gesture_bar()
        panels.append(Panel("B4", "Tapped 'Aug'", "Atlas closes and the grid lands on August 2026; the thumb "
                            "sphere shows 'Aug' as jump feedback.", s))
        panels.append(Panel("B5", "Push up to close", "Reverse gesture; the atlas tracks the finger back up.",
                            self.l6_frame(frac=0.35, finger=-L.px(90))))
        panels.append(Panel("B6", "Closed after first use", "Hint gone for good.", self.l6_frame(frac=0)))
        p7 = self.l6_frame(frac=1.0)
        cx, cy = p7.place_centers["Coast"]
        L.finger(p7, (cx, cy), None, "tap 'Coast'")
        panels.append(Panel("B7", "Tap a place", "Tap 1 opens the atlas (pull or sphere); tap 2 on a place dot "
                            "(48 dp target). TalkBack: the 'Coast, 1.408 photos' row in D1 does the same.", p7))
        p8 = self.scr()
        p8.status_bar()
        yy = L.title(p8, "Coast", "1.408 photos, July and August 2026")
        f = p8.font("nav")
        w = p8.d.textlength("Clear place", font=f) + L.px(28)
        cb = [L.px(L.SIDE), yy, L.px(L.SIDE) + w, yy + L.px(40)]
        p8.rrect(cb, 24, fill=p8.t["surface"])
        p8.d.text(((cb[0] + cb[2]) / 2, (cb[1] + cb[3]) / 2), "Clear place", font=f, fill=p8.t["ink"], anchor="mm")
        p8.target(cb, "Clear place")
        L.square_grid(p8, yy + L.px(56), p8.bottom, seed=14, header="August 2026")
        p8.note((DEVICE_W - L.px(8), yy + L.px(14)), "Back or Clear returns to all Pictures at the same spot")
        p8.slab("Pictures")
        p8.gesture_bar()
        panels.append(Panel("B8", "Place in the grid", "Pictures shows only that place, newest first. Back or "
                            "'Clear place' restores the full grid and scroll position.", p8))
        panels.append(Panel("C1", "Year with empty months", "Empty months draw a baseline only, never a fake "
                            "bar.", self.l6_frame(frac=1.0, year="2019", counts=[0, 0, 40, 0, 0, 220, 610, 0, 0, 0,
                                                                                    15, 0], current=6)))
        panels.append(Panel("C2", "No places", "Map area says what is missing and the next step.",
                            self.l6_frame(frac=1.0, no_geo=True)))
        panels.append(Panel("C3", "Dense places", "Overlapping dots merge into a counted cluster; tap zooms the "
                            "list to those places.", self.l6_frame(frac=1.0, dense=True)))
        s = self.scr("dark")
        s.status_bar()
        y = L.title(s, "Pictures")
        L.message(s, y + L.px(8), "Couldn't read dates and places.", "Your pictures are safe. Try again.",
                  "Try again")
        s.slab("Pictures")
        s.gesture_bar()
        panels.append(Panel("C4", "Metadata error, dark", "Pull still opens; the atlas shows this message "
                            "instead of bars.", s))
        panels.append(Panel("C5", "Large library", "Bars scale to the tallest month; totals use full counts.",
                            self.l6_frame(frac=1.0, counts=[9120, 8400, 11020, 12804, 15500, 18210, 22340, 21900,
                                                             13000, 7800, 6100, 2718])))
        panels.append(Panel("C6", "Alternative: 2 x 6 months", "Two rows of six: 58 x 80 dp month targets, rows "
                            "touch but never overlap. Recommended over 29 dp one-row bars.", self.l6_frame(frac=1.0, grid=True)))
        s = self.scr()
        s.status_bar()
        y = L.title(s, "Atlas", "TalkBack list view")
        items = [("Months", None)] + [(f"{m}, {L._nl(c)} photos" if c else f"{m}, no photos", 1)
                                      for m, c in zip(["January", "February", "March", "April", "May", "June"],
                                                      L.COUNTS[:6])]
        items += [("Places", None), ("Amsterdam, 6.120 photos", 1), ("Coast, 1.408 photos", 1),
                  ("Mountains, 912 photos", 1)]
        k = 0
        for text_, kind in items:
            if kind is None:
                s.text((L.px(L.SIDE), y + L.px(8)), text_, "section")
                y += s.line_h("section") + L.px(12)
            else:
                k += 1
                s.text((L.px(L.SIDE), y + L.px(14)), text_, "body", anchor="lm")
                s.target([0, y - L.px(10), DEVICE_W, y + L.px(38)], text_)
                if k <= 4 or text_.startswith("Amsterdam"):
                    s.focus_order((L.px(6), y + L.px(14)), text_)
                y += L.px(48)
        s.slab("Pictures")
        s.gesture_bar()
        panels.append(Panel("D1", "TalkBack lists", "With TalkBack the map is replaced by lists: months, then "
                            "places, e.g. 'Amsterdam, 6.120 photos'. Each is a 48 dp row.", s))
        d2 = self.l6_frame(frac=1.0)
        d2.note((L.px(8), d2.top + L.px(580)), "Remove animations: closed › open in one frame")
        panels.append(Panel("D2", "Remove animations on", "Pull or tap opens at once; push up closes at once.",
                            d2))
        b.grid("Storyboard and states", panels)
        b.table("Gesture precedence", ["Situation", "What a downward drag does", "Alternative"], [
            ["Grid scrolled to the top", "Opens the atlas, finger-tracked", "Tap the month sphere"],
            ["Grid scrolled down", "Scrolls the grid up (nested scroll first)", "Tap the month sphere"],
            ["Atlas open", "Upward drag closes; a month tap jumps, a place tap filters (B4, B7-B8)", "System Back closes"],
            ["TalkBack on", "Two-finger scroll as usual", "Atlas button = month sphere; lists (D1)"],
        ], [0.25, 0.42, 0.33])
        b.table("Reference decisions", DECISION_HEAD, [
            ref_row("sheet", "Deviation: the atlas comes down from the top over the grid and follows the finger; "
                    "push up or Back closes. It is a scrim-free overlay, so the grid position is kept."),
            ref_row("nested", "Adopted: the grid consumes the drag until it is at the top; only the remaining "
                    "downward delta pulls the atlas."),
            ref_row("touch", "Open issue: 12 bars in one row are 29 dp wide. Either accept full-height 29 x 96 dp "
                    "targets with drag-to-scrub, or use 2 x 6 (C6, 58 dp). Recommended: 2 x 6."),
        ], DECISION_W, DECISION_NOTE + " No map tiles, network call or new permission is involved.")
        b.footer(f"Generated by tools/design/lapis_preflight.py · synthetic only · references checked {VERIFIED} · "
                 "not a device capture")
        return b

    # ------------------------------------------------------------------ L7
    SUGG = [("Recent", "beach 2025"), ("Recent", "Noor and Bram"), ("Recent", "Amsterdam"),
            ("More", "More search options")]

    def l7_open(self, theme="light", scale=1.0, query="", scope="This screen", items=None, keyboard=True,
                base="Pictures", thumbs=False, entries=False):
        s = self.scr(theme, scale)
        s.status_bar()
        y = L.title(s, base)
        if base == "Pictures":
            L.square_grid(s, y, s.bottom, seed=1, header="Today")
        else:
            for name, line, rk, seed in (("Albums", "24 albums", 0, 11), ("Places", "38 places", 1, 51)):
                y = L.collection_row(s, y, name, line, L.STRIP_RATIOS[rk], seed)
        s.d.rectangle([0, 0, DEVICE_W, s.img.height], fill=(*_rgb(s.t["ground"]), 150))
        kb = s.keyboard() if keyboard else s.bottom
        sb = s.slab(base, y_bottom=kb, search=False)
        L.search_bar(s, sb[1], query, keyboard, scope, items=items, thumbs=thumbs, entries=entries)
        return s

    def l7(self) -> Board:
        b = Board(self.fonts, "brand-l7-search")
        b.header("BRAND-L7 · Search under the thumb",
                 "The search sphere grows into a full-width AtlasSearch bar right above the slab.",
                 self.facts(660, "Host: only placement changes. The shared AtlasSearch host and back end (#175) "
                            "stay; L7 moves the entry to the slab sphere and the panel above the slab."))
        a1 = self.l7_open(items=self.SUGG, entries=True)
        a1.focus_order((L.px(60), a1.slab_box[1] - L.px(70)), "Search field")
        a2 = self.l7_open("dark", 2.0, items=self.SUGG, entries=True)
        b.heroes([
            Panel("A1", "Focused with keyboard, light", "Bar sits directly above the slab, which rides on the "
                  "keyboard. Entry chips for faces, a picture and details are always visible; recent searches "
                  "above; 'More search options' opens the full suite.", a1),
            Panel("A2", "Same, dark, 200 %", "Chips wrap to more rows and fewer recent rows fit; the rest "
                  "scroll inside the panel. The slab's search sphere becomes the bar, so it leaves the slab.", a2),
        ], self.LEGEND)
        panels = []
        s = self.scr()
        L.pictures(s)
        panels.append(Panel("B1", "At rest", "Search sphere in the slab, 48 dp target.", s))
        s = self.scr()
        L.pictures(s)
        x0, y0, x1, y1 = s.slab_box
        s.rrect([DEVICE_W // 2, y0 - L.px(68), DEVICE_W - L.px(8), y0 - L.px(16)], 24, fill=s.t["surface"])
        s.note((DEVICE_W - L.px(8), y0 - L.px(90)), "expanding from the sphere, 200 ms")
        panels.append(Panel("B2", "Expanding", "Bar grows leftwards from the sphere; with animations removed it "
                            "appears at full width at once.", s))
        panels.append(Panel("B3", "Focused, history", "Keyboard opens with the bar; recent searches and entry "
                            "types.", self.l7_open(items=self.SUGG, entries=True)))
        panels.append(Panel("B4", "Typing", "Autocomplete under the query, with picture previews above the "
                            "keyboard.", self.l7_open(query="bik", thumbs=True,
                                                      items=[("Suggestion", "bike"), ("Place", "Bike trip album"),
                                                             ("Person", "Bike club"), ("More", "Search 'bik'")])))
        panels.append(Panel("B5", "Whole library", "Switching scope keeps the query and the keyboard.",
                            self.l7_open(query="bike", scope="Whole library",
                                         items=[("Suggestion", "bike"), ("Suggestion", "bike 2019"),
                                                ("More", "Search 'bike' everywhere")])))
        s = self.l7_open(query="bike", scope="Whole library", keyboard=False)
        top = s.panel_top - L.px(8)
        s.d.rectangle([0, s.top, DEVICE_W, top], fill=s.t["ground"])
        s.text((L.px(L.SIDE), s.top + L.px(12)), "86 pictures for 'bike'", "section")
        L.square_grid(s, s.top + L.px(48), top - L.px(96), seed=12)
        panels.append(Panel("B6", "Results", "Search key hides the keyboard; results fill the screen above the "
                            "bar.", s))
        s = self.l7_open(query="bike 2019", keyboard=False)
        top = s.panel_top - L.px(8)
        s.d.rectangle([0, s.top, DEVICE_W, top], fill=s.t["ground"])
        L.message(s, s.top + L.px(24), "No pictures match 'bike 2019' on this screen.",
                  "Try fewer words, or search your whole library.", "Search whole library")
        panels.append(Panel("B7", "No results", "One sentence, one next step.", s))
        s = self.l7_open(query="beach", keyboard=False)
        top = s.panel_top - L.px(8)
        s.d.rectangle([0, s.top, DEVICE_W, top], fill=s.t["ground"])
        L.loading_arc(s, s.top + L.px(20), 0.4, "Searching 12.305 pictures")
        panels.append(Panel("B8", "Loading", "Held-sphere arc and count; the bar stays editable.", s))
        s = self.l7_open("dark", query="beach", keyboard=False)
        top = s.panel_top - L.px(8)
        s.d.rectangle([0, s.top, DEVICE_W, top], fill=s.t["ground"])
        yy = L.message(s, s.top + L.px(24), "Search isn't available right now.", "Your pictures are safe.",
                       "Try again")
        L.message(s, yy + L.px(24), "Atlas needs photo access to search.",
                  "Allow photos and videos in Settings.", "Open settings")
        panels.append(Panel("B9", "Error and permission, dark", "Both recoveries are one 48 dp action.", s))
        s = self.l7_open(query="beach", keyboard=False)
        s.note((DEVICE_W - L.px(8), s.slab_box[1] - L.px(200)), "Back 1: keyboard hides, query kept")
        panels.append(Panel("B10", "Back 1", "First Back hides the keyboard; bar, query and scope stay.", s))
        s = self.scr()
        L.pictures(s)
        s.note((DEVICE_W - L.px(8), s.slab_box[1] - L.px(30)), "Back 2: collapses, screen state unchanged")
        panels.append(Panel("B11", "Back 2", "Second Back collapses into the sphere; scroll position and "
                            "selection are kept.", s))
        panels.append(Panel("B12", "In Collections", "'This screen' now means Collections; same bar, same "
                            "place.", self.l7_open(base="Collections", query="tr",
                                                   items=[("Suggestion", "Trash"), ("Suggestion", "Trip to the "
                                                                                    "coast")])))
        panels.append(Panel("B13", "Large library, 200 %, keyboard", "Whole library at 200 % with the "
                            "keyboard: chips wrap, the suggestion list scrolls inside the panel (count noted).",
                            self.l7_open("dark", 2.0, query="beach", scope="Whole library", entries=True,
                                         items=[("Suggestion", "beach"), ("Suggestion", "beach 2019"),
                                                ("Suggestion", "beach with Noor"), ("Place", "Coast"),
                                                ("More", "Search 'beach' everywhere")])))
        s = self.l7_open(query="beach", scope="Whole library", keyboard=False)
        top = s.panel_top - L.px(8)
        s.d.rectangle([0, s.top, DEVICE_W, top], fill=s.t["ground"])
        s.text((L.px(L.SIDE), s.top + L.px(12)), "12.840 pictures for 'beach'", "section")
        L.square_grid(s, s.top + L.px(48), top - L.px(96), cols=6, seed=17, header="August 2026",
                      headers_every=18, month_names=["July 2025", "August 2019"])
        L.fast_thumb(s, 0.55, "2019", s.top + L.px(56), top - L.px(60))
        panels.append(Panel("B14", "Large library results", "12.840 results load page by page; the held-sphere "
                            "thumb jumps through the result years. Bar and query stay.", s))
        s = self.scr()
        L.pictures(s)
        y0s = s.slab_box[1]
        for frac in (0.3, 0.65):
            x0 = DEVICE_W - L.px(8) - (DEVICE_W - L.px(16)) * frac
            L.dashed_rect(s.d, (x0, y0s - L.px(68), DEVICE_W - L.px(8), y0s - L.px(16)), L.ANNO, L.px(1.5), L.px(5))
        s.rrect([L.px(8), y0s - L.px(68), DEVICE_W - L.px(8), y0s - L.px(16)], 24, outline=s.t["brand_fill"],
                width=L.px(2))
        s.note((DEVICE_W - L.px(8), y0s - L.px(90)), "0 ms sphere, 70 ms, 140 ms dashed; 200 ms solid")
        panels.append(Panel("B15", "Motion on", "The bar grows leftwards from the sphere over 200 ms "
                            "(dashed stills), then the keyboard rises.", s))
        s = self.l7_open(items=self.SUGG, entries=True)
        s.note((DEVICE_W - L.px(8), s.top + L.px(4)), "Remove animations on: this frame follows the tap directly")
        panels.append(Panel("B16", "Remove animations on", "Tap the sphere and the next frame is the full bar, "
                            "panel and keyboard; collapse is a cut too.", s))
        b.grid("Flow and states", panels)
        b.table("Copy and TalkBack (proposed)", ["Element", "Text", "TalkBack"], [
            ["Query hint", "Search photos, people, places", "'Search, edit box'"],
            ["Scope", "This screen / Whole library", "'This screen, selected' / 'Whole library'"],
            ["Close", "(icon)", "'Close search'"],
            ["No results", "No pictures match '<query>' on this screen. / Try fewer words, or search your whole "
             "library.", "Read in full, then the button"],
            ["Errors", "Search isn't available right now. / Atlas needs photo access to search.", "Read in full"],
            ["Traversal", "Field › scope chips › suggestions top to bottom › close › slab", "Focus starts in the "
             "field (it opens with the keyboard)"],
        ], [0.18, 0.5, 0.32])
        b.table("Reference decisions", DECISION_HEAD, [
            ref_row("searchbar", "Adapted: suggestions and results stack upward from the bar so they stay above "
                    "the keyboard and near the thumb, instead of below a top bar."),
            ref_row("find5", "Changed: the bar is at the bottom and does auto-focus (the keyboard opens with it, "
                    "per Quiet Frame v3). Kept: the This screen / Whole library chips, query handoff and the "
                    "two-step Back."),
        ], DECISION_W, DECISION_NOTE)
        b.footer(f"Generated by tools/design/lapis_preflight.py · synthetic only · references checked {VERIFIED} · "
                 "not a device capture")
        return b

    # ------------------------------------------------------------------ L8
    def l8_day(self, theme="light", scale=1.0, selected=None, anchor=None, thumb=False, ratios=None):
        s = self.scr(theme, scale)
        s.status_bar()
        y = L.title(s, "Pictures")
        L.day_rows(s, y, s.bottom, selected=selected, anchor=anchor, ratios=ratios)
        if thumb:
            L.fast_thumb(s, 0.3, "Nov", y + L.px(8), s.bottom - L.px(110))
        s.slab("Pictures")
        s.gesture_bar()
        return s

    # --- L8 storyboard helpers (#836): every number drawn comes from lapis_screens.storyboard()
    def l8_env(self, theme="light", scale=1.0):
        key = (theme, scale)
        if key not in self._l8_cache:
            self._l8_cache[key] = L.l8_env(self.scr(theme, scale))
        return self._l8_cache[key]

    def l8_chrome(self, s: Screen, vp: L.Viewport) -> None:
        s.d.rectangle([0, 0, DEVICE_W, vp.top], fill=s.t["ground"])
        s.status_bar()
        L.title(s, "Pictures")

    def l8_zoom(self, kind, scroll, theme="light", scale=1.0, selected=None, outline=None, thumb=True,
                placeholders=False, chrome=True):
        items, lays, vp = self.l8_env(theme, scale)
        s = self.scr(theme, scale)
        vis = L.draw_layout(s, items, lays[kind], scroll, vp, selected=selected, outline=outline,
                            placeholders=placeholders)
        if thumb:
            L.fast_thumb(s, L.thumb_frac(lays[kind], scroll, vp), "Nov", vp.top + L.px(8), s.bottom - L.px(110))
        if chrome:
            self.l8_chrome(s, vp)
            s.slab("Pictures")
            s.gesture_bar()
        return s, vis

    @staticmethod
    def l8_card_y(s: Screen) -> float:
        return s.bottom - L.px(90)

    def l8_end(self, st: L.PinchStep, name: str, kind: str, selected=None):
        s, _vis = self.l8_zoom(kind, st.scroll, selected=selected, outline=st.key)
        L.dashed_rect(s.d, st.src, L.ANNO, L.px(2), L.px(6))
        a, b = L.centre(st.src), L.centre(st.dst)
        if abs(st.dx) + abs(st.dy) > 4:
            L.finger(s, a, b)
        L.crosshair(s, st.focal, f"F {L.fmt_pt(st.focal)}")
        clamp = f"clamped at {st.clamped} (wanted {st.wanted_scroll:.0f})" if st.clamped else "no clamp"
        L.anno_card(s, [
            f"{name} · same key {st.key} · dashed = its rect before, solid = after",
            f"before {L.fmt_rect(st.src)} centre {L.fmt_pt(a)}",
            f"after  {L.fmt_rect(st.dst)} centre {L.fmt_pt(b)}",
            f"centre shift X {L.fmt_d(st.dx)} px, Y {L.fmt_d(st.dy)} px · focal height residual {L.fmt_d(st.y_residual)} px",
            f"F on key {st.key}: {'yes' if st.hit else 'NO'} · scroll {st.scroll:.0f} px, {clamp}",
        ], self.l8_card_y(s))
        return s

    def l8_start(self, st: L.PinchStep, name: str, direction="pinch in", selected=None):
        s, _vis = self.l8_zoom("day", st.scroll, selected=selected, outline=st.key)
        fy = st.focal[1]
        L.draw_pinch(s, st.focal, direction)
        L.crosshair(s, st.focal, f"F {L.fmt_pt(st.focal)}")
        L.anno_card(s, [
            f"{name} · key {st.key} under the focal point F (two-finger centroid)",
            f"photo rect {L.fmt_rect(st.src)} centre {L.fmt_pt(L.centre(st.src))}",
            f"F at {L.rel_v(st.src, fy) * 100:.0f} % of the photo height · day scroll {st.scroll:.0f} px",
        ], self.l8_card_y(s))
        return s

    def l8_mid(self, a_kind, b_kind, st: L.PinchStep, a_scroll, selected=None, direction="pinch in"):
        """Illustrative halfway still: both layouts at 50 % and the key's rect halfway between endpoints."""
        items, _lays, vp = self.l8_env()
        sa, _ = self.l8_zoom(a_kind, a_scroll, selected=selected, thumb=False, chrome=False)
        sb, _ = self.l8_zoom(b_kind, st.scroll, selected=selected, thumb=False, chrome=False)
        s = self.scr()
        s.img = Image.blend(sa.img, sb.img, 0.5)
        s.d = ImageDraw.Draw(s.img, "RGBA")
        mid = tuple((p + q) / 2 for p, q in zip(st.src, st.dst))
        L.key_photo(s, mid, items[st.key], (vp.top, vp.bottom))
        s.d.rectangle(mid, outline=L.ANNO, width=L.px(3))
        a, b, m = L.centre(st.src), L.centre(st.dst), L.centre(mid)
        s.d.line([a, b], fill=L.ANNO, width=L.px(2))
        for p in (a, b):
            s.d.ellipse([p[0] - L.px(4), p[1] - L.px(4), p[0] + L.px(4), p[1] + L.px(4)], outline=L.ANNO,
                        width=L.px(2))
        s.d.ellipse([m[0] - L.px(5), m[1] - L.px(5), m[0] + L.px(5), m[1] + L.px(5)], fill=L.ANNO)
        L.draw_pinch(s, st.focal, direction, far_dp=60, near_dp=36)
        L.crosshair(s, st.focal, f"F {L.fmt_pt(st.focal)}")
        self.l8_chrome(s, vp)
        s.slab("Pictures")
        s.gesture_bar()
        L.anno_card(s, [
            f"halfway still (t = 0.5) · key {st.key} rect {L.fmt_rect(mid)} centre {L.fmt_pt(m)}",
            f"centre path {L.fmt_pt(a)} -> {L.fmt_pt(b)}: the key moves, never vanishes",
            "Illustrative still; FLOW-2 #712 owns runtime motion.",
            "With Remove animations on, the zoom cuts to the end frame.",
        ], self.l8_card_y(s))
        return s

    L8_SIG_SELECTED = {1: 1, 6: 2, 9: 3}  # top of November, so the month heading is in view; 6 is a video

    def l8_month_sig(self, kind, theme, scale, scroll=None, placeholders=False, loading=None):
        """Month/year zoom with the held-sphere vocabulary: ordered selection + halo, video, 46 dp thumb."""
        items, lays, vp = self.l8_env(theme, scale)
        lay = lays[kind]
        scroll = 0.0 if scroll is None else scroll
        s = self.scr(theme, scale)
        top = vp.top
        if loading:  # the arc row sits under the title and pushes the grid down
            top += max(L.loading_arc(self.scr(theme, scale), vp.top, 0.4, loading) - vp.top,
                       L.px(26) + 2 * s.line_h("secondary") + L.px(4))
        cvp = L.Viewport(top, vp.bottom)
        L.draw_layout(s, items, lay, scroll, cvp, selected=self.L8_SIG_SELECTED, placeholders=placeholders)
        L.fast_thumb(s, L.thumb_frac(lay, scroll, cvp), "Nov", cvp.top + L.px(8), s.bottom - L.px(110))
        s.d.rectangle([0, 0, DEVICE_W, top], fill=s.t["ground"])
        s.status_bar()
        L.title(s, "Pictures")
        if loading:
            L.loading_arc(s, vp.top, 0.4, loading)
        s.slab("Pictures")
        s.gesture_bar()
        return s

    def l8(self) -> Board:
        b = Board(self.fonts, "brand-l8-justified")
        b.header("BRAND-L8 · Real-shape rows at day zoom",
                 "Day zoom shows every picture at its real shape in justified rows; month and year stay square.",
                 self.facts(661, "Pinch frames (C) are computed from one synthetic 970-item library by "
                            "tools/design/lapis_screens.py storyboard(); every coordinate printed on a frame is "
                            "the drawn pixel position in the 1080 x 2340 frame (#836)."))
        b.heroes([
            Panel("A1", "Day zoom, light, 100 %", "Rows target 118 dp and stretch to the width; 2 dp gaps. The "
                  "4.2:1 panorama gets its own full-width row, the 0.45 portrait keeps its height, a video shows "
                  "its duration, the final row keeps 118 dp instead of stretching.", self.l8_day()),
            Panel("A2", "Day zoom, dark, 200 %", "Date headers scale; picture geometry does not depend on font "
                  "scale.", self.l8_day("dark", 2.0)),
        ], self.LEGEND)

        _items, lays, vp = self.l8_env()
        st = L.storyboard(lays, vp)
        sel = L.SELECTED
        pinch_panels = [
            Panel("C1", "Pinch in 1/4: day", f"Fingers around key {st['start'].key}; F is the centroid. "
                  "Selection 1-3 is on.", self.l8_start(st["start"], "C1", selected=sel)),
            Panel("C2", "Pinch in 2/4: halfway to month", "Both layouts at 50 %; the key's rect is halfway "
                  "between its day and month positions.", self.l8_mid("day", "month", st["in_month"],
                                                                     st["start"].scroll, sel)),
            Panel("C3", "Pinch in 3/4: month", f"Same key under F. Column quantization moves its centre "
                  f"{L.fmt_d(st['in_month'].dx)} px in X; its height under F is restored exactly.",
                  self.l8_end(st["in_month"], "C3", "month", sel)),
            Panel("C4", "Pinch in 4/4: year", f"Same key under F; centre {L.fmt_d(st['in_year'].dx)} px in X. "
                  "Selection, video mark and thumb carry over.", self.l8_end(st["in_year"], "C4", "year", sel)),
            Panel("C5", "Pinch out 1/3: halfway to month", f"F unchanged; the key under it is still key "
                  f"{st['out_month'].key}.", self.l8_mid("year", "month", st["out_month"], st["in_year"].scroll,
                                                          sel, "pinch out")),
            Panel("C6", "Pinch out 2/3: month", "Lands on the same month scroll as C3.",
                  self.l8_end(st["out_month"], "C6", "month", sel)),
            Panel("C7", "Pinch out 3/3: back to day", f"Day scroll restored: {st['out_day'].scroll:.0f} px "
                  f"(C1: {st['start'].scroll:.0f} px). Selection 1-3 unchanged.",
                  self.l8_end(st["out_day"], "C7", "day", sel)),
            Panel("C8", "Interior key: day", f"Key {st['int_start'].key}, a wide photo whose month and year "
                  "columns lie away from its day position.", self.l8_start(st["int_start"], "C8", selected=sel)),
            Panel("C9", "Interior key: month", f"Height restored, but the fixed column moves it "
                  f"{L.fmt_d(st['int_month'].dx)} px: F now rests on key {st['int_month'].extra['key_under_pointer']}.",
                  self.l8_end(st["int_month"], "C9", "month", sel)),
            Panel("C10", "Interior key: year", f"{L.fmt_d(st['int_year'].dx)} px in X; F rests on key "
                  f"{st['int_year'].extra['key_under_pointer']}. Disclosed, not hidden.",
                  self.l8_end(st["int_year"], "C10", "year", sel)),
            Panel("C11", "Top clamp: first photo", f"Scroll cannot go above 0: the key lands "
                  f"{L.fmt_d(st['top'].y_residual)} px from its focal height.", self.l8_end(st["top"], "C11", "month")),
            Panel("C12", "Bottom clamp: last photo", f"Scroll cannot pass the end: {L.fmt_d(st['bottom'].y_residual)} px "
                  "from its focal height.", self.l8_end(st["bottom"], "C12", "month")),
        ]
        b.grid("Pinch storyboard, both directions (measured, 75 % scale)", pinch_panels, cols=4, scale=0.75)

        panels = [
            Panel("B1", "Month zoom, light, 100 %", "4 squares per row. Ordered selection spheres 1-3 with "
                  "2 dp white halo, 0:14 video badge, 46 dp \"Nov\" thumb in a 48 dp target.",
                  self.l8_month_sig("month", "light", 1.0)),
            Panel("B2", "Month zoom, dark, 200 %", "Headers scale; tiles, spheres and thumb keep their size.",
                  self.l8_month_sig("month", "dark", 2.0)),
            Panel("B3", "Year zoom, light, 100 %", "8 squares per row. Spheres shrink to 20 dp on 46 dp tiles; "
                  "video becomes a play glyph. Tile target 46 dp is flagged below 48 dp.",
                  self.l8_month_sig("year", "light", 1.0)),
            Panel("B4", "Year zoom, dark, 200 %", "Short month headers stay on one line at 200 %.",
                  self.l8_month_sig("year", "dark", 2.0)),
            Panel("D1", "Selection in day rows", "Same sphere, halo and order numbers as month zoom.",
                  self.l8_day(selected={0: 1, 3: 2, 4: 3})),
            Panel("D2", "Fast scroll in day rows", "Same 46 dp sphere in a 48 dp target.",
                  self.l8_day(thumb=True)),
        ]
        s = self.scr()
        s.status_bar()
        y = L.title(s, "Pictures")
        L.message(s, y + L.px(8), "No pictures on this phone yet.",
                  "Take a photo, or copy pictures to this phone, and they appear here.")
        s.slab("Pictures")
        s.gesture_bar()
        panels.append(Panel("E1", "Empty, any zoom", "One sentence and a next step; no grid exists to zoom.", s))
        s = self.scr()
        s.status_bar()
        y = L.title(s, "Pictures")
        y = L.loading_arc(s, y - L.px(6), 0.7, "Measuring picture shapes, 9.400 of 12.305")
        L.day_rows(s, y, s.bottom)
        s.slab("Pictures")
        s.gesture_bar()
        panels.append(Panel("E2", "Loading, day", "Rows render from stored width and height; no layout jump "
                            "when pixels arrive.", s))
        panels.append(Panel("E3", "Loading, month, dark 200 %", "Squares hold their place as surface tiles; "
                            "selection and thumb already work.",
                            self.l8_month_sig("month", "dark", 2.0, placeholders=True,
                                              loading="Reading pictures, 2.100 of 12.305")))
        s = self.scr("dark")
        s.status_bar()
        y = L.title(s, "Pictures")
        L.message(s, y + L.px(8), "Couldn't read your pictures.",
                  "Check that Atlas may use photos and videos, then try again.", "Try again")
        s.slab("Pictures")
        s.gesture_bar()
        panels.append(Panel("E4", "Error, dark, any zoom", "Plain cause, 48 dp retry.", s))
        panels.append(Panel("E5", "Small library", "Five pictures: one justified row and a short final row.",
                            self.l8_day(ratios=[1.33, 0.75, 1.5, 1.0, 0.56])))
        s = self.scr()
        s.status_bar()
        y = L.title(s, "Pictures", "148.912 photos and 3.204 videos (schematic)")
        L.square_grid(s, y, s.bottom, cols=8, seed=9, header="2014")
        L.fast_thumb(s, 0.7, "2014", y + L.px(8), s.bottom - L.px(110))
        s.slab("Pictures")
        s.gesture_bar()
        panels.append(Panel("E6", "150k library, year", "Schematic only. No performance is claimed; see the "
                            "post-build gate below.", s))
        b.grid("Month and year signature, states", panels)

        def row(code, label, x):
            clamp = x.clamped or "none"
            return [code, label, str(x.key), L.fmt_pt(x.focal),
                    f"{L.fmt_rect(x.src)}\ncentre {L.fmt_pt(L.centre(x.src))}",
                    f"{L.fmt_rect(x.dst)}\ncentre {L.fmt_pt(L.centre(x.dst))}",
                    f"{L.fmt_d(x.dx)} / {L.fmt_d(x.dy)}", L.fmt_d(x.y_residual), "yes" if x.hit else "NO",
                    f"{x.scroll:.0f} ({clamp})"]

        b.table("Pinch geometry, measured from the frames above (px in the 1080 x 2340 frame)",
                ["Frame", "Step", "Key", "Focal F", "Key rect before", "Key rect after", "Centre shift X / Y",
                 "Height residual", "F on key", "Scroll (clamp)"], [
                    row("C3", "day -> month", st["in_month"]),
                    row("C4", "month -> year", st["in_year"]),
                    row("C6", "year -> month", st["out_month"]),
                    row("C7", "month -> day", st["out_day"]),
                    row("C9", "day -> month", st["int_month"]),
                    row("C10", "month -> year", st["int_year"]),
                    row("C11", "day -> month, top", st["top"]),
                    row("C12", "day -> month, bottom", st["bottom"]),
                ], [0.05, 0.11, 0.05, 0.08, 0.16, 0.16, 0.10, 0.08, 0.07, 0.14],
                "Rule drawn: the pinch keeps the media key under F and restores the same relative height "
                "inside it by choosing the new scroll offset (clamped to the content). Square grids are "
                "edge-aligned and a key's column is its position in the month modulo the column count, so X "
                "is quantized: tile centres at 4 and 8 columns sit at odd/8 and odd/16 of the width and "
                f"cannot coincide. A stationary F can stay on the same key in both month and year only in year "
                f"columns {L.same_point_columns()} (first and last); elsewhere the key keeps its height and "
                "moves to its column (C9, C10).")
        b.table("Layout rules", ["Rule", "Value", "Notes"], [
            ["Target row height", "118 dp", "Rows scale to fill 384 dp exactly; height varies per row."],
            ["Gap", "2 dp", "Between pictures and rows (Quiet Frame v3)."],
            ["Panorama cap", "One full row", "Wider than the row at target height: shown full width, lower row."],
            ["Final row", "Not stretched", "Keeps target height and aligns left."],
            ["Crop and letterbox", "Never at day zoom", "Day thumbnails decode at row size from the real aspect "
             "ratio; month and year squares are centre crops of the same picture."],
            ["Pinch anchor", "Key under F", "Same key; relative height restored via scroll, clamped at the top "
             "and end; X follows the key's fixed column and the shift is disclosed (table above)."],
            ["Year tile", "46.25 dp", "8 columns x 46.25 dp + 7 x 2 dp = 384 dp. In selection mode the tile is "
             "the tap target and is below 48 dp (B3). Open for the design decision below."],
        ], [0.18, 0.17, 0.65])
        b.table("Reference decisions", DECISION_HEAD, [
            ref_row("justified", "Adopted for day zoom: rows from aspect ratios, with a capped panorama and an "
                    "unstretched final row."),
            ref_row("grid", "Kept for month and year zoom: equal square cells give stable targets for fast "
                    "scanning. Consequence: a key's column is fixed by its position, so the pinch anchor can "
                    "keep the key and its height but not its horizontal position (measured above)."),
            ref_row("touch", "Deviation flagged, not resolved: 8-column year tiles are 46.25 dp, under the "
                    "48 dp guidance, when a tile tap toggles selection."),
            ref_row("frames", "Post-build gate only: a FrameMetrics fling test on a 150k synthetic library against "
                    "the #119 target. No measurement exists yet and none is shown."),
        ], DECISION_W, DECISION_NOTE)
        b.notes("Open for an explicit design decision (Strategy, before the re-preflight)", [
            "Pinch invariant. Proven here: the same media key and its height under the focal point, both "
            "directions, with a day scroll that round-trips exactly (C1 -> C7). Not achievable with fixed "
            "edge-aligned grids: keeping the key horizontally under a stationary pointer, except in the outer "
            "year columns. Proposed wording for #661: 'the pinched photo stays the same photo at the same "
            "height under the fingers; it slides to its column (shift shown during the gesture)'.",
            "Year tile size. Keep 8 columns at 46.25 dp (flagged below 48 dp in selection mode), or use 7 "
            "columns (52.6 dp). No zoom stop or gesture changes in this repair.",
        ])
        b.footer(f"Generated by tools/design/lapis_preflight.py · synthetic only · references checked {VERIFIED} · "
                 "not a device capture")
        return b


def _rgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


BOARDS = {
    "l4": ("brand-l4-navigation.png", Gen.l4),
    "l5": ("brand-l5-collections.png", Gen.l5),
    "l6": ("brand-l6-atlas.png", Gen.l6),
    "l7": ("brand-l7-search.png", Gen.l7),
    "l8": ("brand-l8-justified.png", Gen.l8),
}


def save_png(img: Image.Image, path: Path) -> None:
    # 256-colour palette like the FIND-5 frames keeps the repository small; deterministic.
    q = img.quantize(colors=256, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE)
    q.save(path, optimize=True)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--font-dir", help="directory with funnel_display.ttf and funnel_sans.ttf")
    ap.add_argument("--allow-fallback-font", action="store_true",
                    help="layout smoke run with DejaVu Sans; sheets are stamped as not for review")
    ap.add_argument("--only", choices=sorted(BOARDS), action="append")
    ap.add_argument("--out", default=str(OUT))
    ap.add_argument("--check", action="store_true",
                    help="verify committed sheets against manifest.json (size, sha256, frame count)")
    args = ap.parse_args(argv)
    out = Path(args.out)
    manifest_path = out / "manifest.json"

    if args.check:
        man = json.loads(manifest_path.read_text())
        ok = True
        for key, entry in man["boards"].items():
            p = out / entry["file"]
            if not p.is_file():
                print(f"FAIL {key}: missing {p}")
                ok = False
                continue
            with Image.open(p) as im:
                size = list(im.size)
            digest = hashlib.sha256(p.read_bytes()).hexdigest()
            problems = []
            if size != entry["size"]:
                problems.append(f"size {size} != {entry['size']}")
            if digest != entry["sha256"]:
                problems.append("sha256 differs from manifest")
            if entry["device_frames"] < 2 or entry["device_frame_px"] != [1080, 2340]:
                problems.append("device frame record invalid")
            print(("FAIL " if problems else "PASS ") + f"{key} {entry['file']} {size} frames="
                  f"{entry['device_frames']}" + (" " + "; ".join(problems) if problems else ""))
            ok &= not problems
        return 0 if ok else 1

    src = find_fonts(args.font_dir, REPO, args.allow_fallback_font)
    gen = Gen(Fonts(src))
    out.mkdir(parents=True, exist_ok=True)
    man = json.loads(manifest_path.read_text()) if manifest_path.is_file() else {"boards": {}}
    man.update({
        "generator": "tools/design/lapis_preflight.py",
        "unit": "https://github.com/LPStruiksma/atlas-gallery/issues/753",
        "synthetic_only": True,
        "device_frame": "1080x2340 px, 384x832 dp at 2.8125",
        "references_checked": VERIFIED,
        "fonts": src.describe(),
        "font_sha256": ({} if src.fallback else {
            n: hashlib.sha256((src.directory / n).read_bytes()).hexdigest()  # type: ignore[operator]
            for n in ("funnel_display.ttf", "funnel_sans.ttf")}),
        "pillow": __import__("PIL").__version__,
    })
    for key in args.only or sorted(BOARDS):
        fname, fn = BOARDS[key]
        board = fn(gen)
        img = board.render()
        if src.fallback:
            from PIL import ImageDraw
            ImageDraw.Draw(img).text((40, 8), "FALLBACK FONT · LAYOUT SMOKE RUN · NOT FOR REVIEW",
                                     font=gen.fonts.get("sans", 700, 40), fill="#E0007A")
        path = out / fname
        save_png(img, path)
        man["boards"][key] = {
            "file": fname,
            "size": list(img.size),
            "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            "device_frames": board.frames_checked,
            "device_frame_px": [1080, 2340],
            "panels": board.panel_codes,
        }
        print(f"wrote {path} {img.size} frames={board.frames_checked}")
    manifest_path.write_text(json.dumps(man, indent=2) + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
