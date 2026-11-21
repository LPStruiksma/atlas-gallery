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

import lapis_fastscroll as FS  # noqa: E402
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

L7_TRANSITION_HEAD = ["Transition", "Screen / host", "Query: owner and value", "Scope", "Focus / IME",
                      "Back goes to", "Kept or discarded"]
L7_TRANSITION_W = [0.13, 0.13, 0.15, 0.1, 0.14, 0.14, 0.21]
L7_TRANSITIONS = [
    ["1 Search collapsed", "Pictures, Collections, person, album, Declutter", "Local to the screen; empty",
     "This screen", "Sphere in the slab (48 dp); no IME", "Previous screen, as today", "All screen state kept"],
    ["2 Scoped query with keyboard", "Same screen under the bar", "Local to the screen; typed text, e.g. 'bike'",
     "This screen (selected)", "Field focused at once (Quiet Frame v3); IME open", "Back 1 (below)",
     "Scroll, selection and any staged choices kept"],
    ["3 IME hidden by Back", "Same screen, bar still open", "Local; value kept", "This screen",
     "IME hidden; focus stays in the bar", "Back 2 (below)", "Everything kept"],
    ["4 Bar collapsed by Back", "Same screen", "Local; cleared when the bar closes (FIND-5: close clears the query)",
     "This screen", "Back on the sphere; no IME", "Previous screen on the next Back", "Scroll and selection kept; query discarded"],
    ["5 Whole library, empty query", "Existing Search tab (tab 3) opens; person / album route is replaced",
     "Search tab owns it; blank", "Whole library (Search tab's own facets)", "Bar closed, IME released",
     "See 8", "Local query discarded; screen state per rows 9-11"],
    ["6 Whole library, with a query", "Same as 5", "Local query 'bike' is NOT carried; Search tab starts blank",
     "Whole library", "Bar closed, IME released; the board does not claim a focus state in the Search tab",
     "See 8", "Query discarded on purpose; nothing shown in place"],
    ["7 Arrival in global Search", "Search tab, schematic B5", "Search tab; blank", "Whole library",
     "As the existing Search tab; not restyled here", "Search tab's back stack", "Search tab unchanged by L7"],
    ["8 Back from global Search", "Search tab", "Search tab; discarded", "-", "IME hidden first, as today",
     "Search tab's normal back stack; the closed scoped route is not reopened",
     "Person / album route stays closed (replaced by the handoff)"],
    ["9 Person route, Whole library", "Person screen replaced by Search tab", "Local; discarded",
     "Whole library", "As 5", "As 8",
     "Route replaced. Whether an active selection is warned is not visible in the code read for this sheet: "
     "#660 must keep it or confirm; no silent discard is sanctioned"],
    ["10 Album route, Whole library", "Album screen replaced by Search tab", "Local; discarded",
     "Whole library", "As 5", "As 8", "Same rule as 9"],
    ["11 Declutter, Whole library", "Declutter closes (closeDeclutter), Search tab opens",
     "Local; discarded", "Whole library", "As 5", "As 8",
     "With staged choices: existing confirmation 'Search whole library?' (Stay / Search library) says they are "
     "cleared and nothing moves to Trash. Nothing staged: straight through. No Trash is ever submitted"],
]
L7_TRANSITION_NOTE = ("Source: MainActivity onScopeChange LIBRARY branch (personFilter, album and overviewQuery "
                      "cleared, tab = 3) and DeclutterScreen's leave confirmation, read on main at the Dev claim; "
                      "FIND-5 D-search-tab and Back order. Consistent with #804's Stories handoff as a reference "
                      "only; not a new implementation claim. Synthetic evidence, not device behaviour: S24+ runtime "
                      "checks (light/dark, 100 % / 200 %, TalkBack, IME) remain a separate pre-merge obligation.")

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
    "apple5": ("Apple Support, Find and name people and pets in Photos on iPhone",
               "support.apple.com/guide/iphone/find-and-name-people-and-pets-iph9c7ee918c/ios",
               "People & Pets is a separate collection with named person/pet photo thumbnails and a sort control; "
               "the depicted thumbnails are individual portraits, not mixed four-photo strips."),
    "immich5": ("Immich, Facial Recognition documentation",
                "docs.immich.app/features/facial-recognition/",
                "Detected faces are grouped into named people; opening a person shows the associated assets. "
                "The current exact mobile thumbnail layout is not verified by this source."),
    "immich_album": ("Immich release v2.4.0, 18 December 2025",
                     "immich.app/blog/v2.4.0-release",
                     "Mobile album sorting and layout preferences persist; no four-photo strip or direct "
                     "named-album navigation is established by this release."),

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
            Panel("A2", "Pictures, dark, 200 %", "Pills grow with the label and still fit with the search "
                  "sphere in the SAME slab (about 360 of 384 dp wide). Three separate targets, none under 48 dp. "
                  "Tiles stay clear of both insets.", a2),
        ], self.LEGEND)

        panels = []
        c1 = self.scr()
        L.collections_legacy(c1)
        panels.append(Panel("B1", "Collections, light", "Active pill moves to Collections. Until L5 lands it hosts "
                            "today's Albums, Stories and People screens unchanged.", c1))
        c2 = self.scr("dark", 2.0)
        L.collections_legacy(c2)
        panels.append(Panel("B2", "Collections, dark, 200 %", "Section titles and counts wrap or scale; Pictures, Collections "
                            "and search stay in one slab above the gesture inset.", c2))
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
                            "with a #8C9BFF sphere and 2 dp white ring. Seven 40 dp tiles, 8 dp gaps: 48 dp pitch, so the "
                            "seven 48 dp effective targets touch but never overlap.", f1))
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
            ["Label truncation", "Checked at 200 %", "Pills widen instead of truncating; the sphere stays inside the "
             "one slab, on a second row only if the width ever runs out (A2)."],
        ], [0.2, 0.18, 0.62])
        b.footer(f"Generated by tools/design/lapis_preflight.py · synthetic only · references checked {VERIFIED} · "
                 "not a device capture")
        return b

    # ------------------------------------------------------------------ L5
    def l5_collections(self, s: Screen, people_inline=True, focus=False):
        s.status_bar()
        y = L.title(s, "Collections")
        # Only visible named/recent members are two-tap direct destinations.
        # Category rows open landings rather than a particular off-screen member.
        rows = [
            ("Noor", "1.204 photos, latest 14 November 2026", 1, 21),
            ("Coastal weekend", "84 photos, latest 12 November 2026", 0, 11),
            ("Dunes trip", "38 photos, latest 8 October 2026", 2, 51),
            ("Albums", "24 albums, latest 12 November 2026", 0, 61),
            ("People and pets", "500 people and 3 pets", 3, 41),
            ("Places", "38 places, latest Amsterdam", 0, 71),
            ("Stories", "8 stories, latest 2 November 2026", 1, 81),
            ("Favourites", "212 photos, latest 13 November 2026", 2, 91),
        ]
        k = 0
        for row in rows:
            if row is None:
                continue
            k += 1
            name, line, rk, seed = row
            # At high font scale, stop before a partial photo strip would sit
            # underneath the slab. Remaining rows are reachable by scrolling.
            if s.font_scale >= 1.75 and k > 2:
                break
            if y > s.bottom - L.px(240):
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
            Panel("A1", "Collections, light, 100 %", "Recent Noor, Coastal weekend and Dunes trip rows "
                  "open in two taps; category rows below open landings.", a1),
            Panel("A2", "Collections, dark, 200 %", "Only complete recent rows sit above navigation. "
                  "Slab geometry follows #816.", a2),
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
        L.finger(s, (L.px(200), s.top + L.px(125)), None, "tap 2: Noor")
        panels.append(Panel("C2", "Named person · tap 2", "Noor is a direct recent row; "
                            "People and pets is a category landing instead.", s))
        s = self.scr()
        s.status_bar()
        y = L.title(s, "Noor", "1.204 photos, latest 14 November 2026")
        L.square_grid(s, y, s.bottom, seed=21, header="November 2026")
        s.note((DEVICE_W - L.px(8), s.top + L.px(4)), "existing person page, unchanged")
        s.gesture_bar()
        panels.append(Panel("C3", "Noor opened", "Existing person detail; system Back returns to "
                            "Collections, then Pictures.", s))
        s = self.scr()
        self.l5_collections(s)
        L.finger(s, (L.px(194), s.top + L.px(270)), None, "tap 2: Coastal weekend")
        panels.append(Panel("C4", "Named album · tap 2", "Collections then Coastal weekend opens "
                            "the named album. Choosing Albums category adds another tap.", s))
        s = self.scr()
        s.status_bar()
        y = L.title(s, "Coastal weekend", "84 photos, latest 12 November 2026")
        L.square_grid(s, y, s.bottom, seed=11, header="November 2026")
        s.gesture_bar()
        panels.append(Panel("C5", "Coastal weekend opened", "Existing album detail; "
                            "system Back returns to Collections, then Pictures.", s))
        s = self.scr()
        self.l5_collections(s)
        L.finger(s, (L.px(194), s.top + L.px(420)), None, "tap 2: Dunes trip")
        panels.append(Panel("C6", "Named place · tap 2", "Collections then Dunes trip opens "
                            "the named place/trip. The Places category is another destination.", s))
        s = self.scr()
        s.status_bar()
        y = L.title(s, "Dunes trip", "38 photos, latest 8 October 2026")
        L.square_grid(s, y, s.bottom, seed=51, header="October 2026")
        s.gesture_bar()
        panels.append(Panel("C7", "Dunes trip opened", "Existing place detail; "
                            "system Back returns to Collections, then Pictures.", s))
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
        for i, (n, c) in enumerate((("Noor", "1.204 photos"), ("Bram", "866 photos"))):
            y = L.collection_row(s, y, n, f"{c}, latest {14 - i} November 2026", L.STRIP_RATIOS[i % 4], 80 + i * 7)
        sb = s.slab("Collections", search=False)
        L.search_bar(s, sb[1], "", False, scope="This screen")
        panels.append(Panel("D1", "500 people · category", "Sort by most photos, name or latest. "
                            "A named person off-screen needs category navigation or a Collections-scoped "
                            "query. The first two rows are only a sample.", s))
        s = self.scr("dark", 2.0)
        s.status_bar()
        L.title(s, "Collections")
        kb = s.keyboard(["lotte", "lot", "photo"])
        sb = s.slab("Collections", y_bottom=kb, search=False)
        L.search_bar(s, sb[1], "lotte", True, scope="This screen",
                     items=[("Person", "Lotte")], thumbs=True)
        s.note((L.px(8), s.top + L.px(8)), "3 taps + typing: Collections > search > Lotte result")
        panels.append(Panel("D2", "500 people · search, dark 200 %", "Lotte is not a visible "
                            "recent row: tap 1 Collections, tap 2 scoped search, type, tap 3 "
                            "direct Lotte result. Back hides IME, then closes the query panel; "
                            "the next Back returns to Pictures. Proposed scoped Collections "
                            "index, not proven global AtlasSearch entity results.", s))
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
            ["Named person: Noor, recent", "2", "Collections (tap 1) > Noor row (tap 2) > person (C1-C3)."],
            ["Named album: Coastal weekend", "2", "Collections (tap 1) > Coastal weekend row (tap 2) "
             "> album (C4-C5)."],
            ["Named place/trip: Dunes trip", "2", "Collections (tap 1) > Dunes trip row (tap 2) "
             "> place detail (C6-C7)."],
            ["Off-screen person: Lotte (of 500)", "3 + typing", "Collections (tap 1) > scoped search "
             "(tap 2) > type 'lotte' > direct Lotte result (tap 3) (D2)."],
            ["People and pets category", "3 for visible", "Collections > category > visible member. "
             "Off-screen members require scroll or further search actions."],
            ["Global search sphere", "Unknown", "No observed evidence that the global #175 search "
             "opens named person/album/place destinations directly."],
            ["System Back", "Per level", "Detail > Collections > original slab screen. With IME: "
             "Back hides keyboard before closing scoped search."],
        ], [0.27, 0.14, 0.59])
        b.table("Destination parity (every former entry keeps a row)", ["Destination", "Row and order",
                                                                         "Opens", "Shown in"], [
            ["Noor, Coastal weekend, Dunes trip (named recent rows)", "1-3 · direct rows, before the categories",
             "Person page, album, place/trip detail", "A1, A2, C1-C7"],
            ["Albums", "4 · category: count and latest date", "Existing Albums screen", "A1, C2, E2"],
            ["People and pets", "5 · category: section row after the named rows", "Existing People screen "
             "and person page", "E2, D1, D2"],
            ["Places", "6 · category", "Existing Places screen; also the L6 atlas", "Scroll; not drawn"],
            ["Stories", "7 · category", "Existing Stories screen", "Scroll; not drawn"],
            ["Favourites", "8 · category", "Existing Favourites view", "Scroll; not drawn"],
            ["Videos", "9", "Existing Videos view", "D3"],
            ["Recent", "10", "Existing Recent view", "D3"],
            ["Categories, incl. Screenshots", "11 · one row, sub-categories inside", "Existing category screens",
             "D3"],
            ["Shared albums", "12", "Existing Shared albums screen", "D3"],
            ["Cleanup suggestions, Trash, Hidden / Secure folder, Settings", "13-16 · end of list",
             "Existing screens; Hidden asks to unlock first", "E1"],
        ], [0.24, 0.28, 0.3, 0.18], "Only visible named/recent members are two-tap direct "
           "destinations. Category rows open landings, not a specific off-screen member; search "
           "requires a separate result tap. No universal two-tap promise or unverified global results.")
        b.table("Reference decisions", DECISION_HEAD, [
            ref_row("apple5", "Deviation: Apple documents People & Pets as a category of portrait "
                    "thumbnails with a sort control; Atlas keeps category access but uses four "
                    "mixed-aspect recent photo shapes per visible named row, radius 8 dp. Apple's "
                    "exact high-font geometry is not established by this source."),
            ref_row("immich5", "Deviation: Immich groups named faces and opens the assets for a person. "
                    "Atlas keeps that detail destination while avoiding a sole face-cover thumbnail. "
                    "Current exact Immich mobile people composition is unknown; no tap equivalence inferred."),
            ref_row("immich_album", "Deviation: Atlas exposes selected recent named albums inline "
                    "but keeps the full Albums landing and sort. Immich's persisted album layout "
                    "preference does not prove a mixed-aspect strip or direct-result search."),
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

    def l7_handoff(self, theme="light", scale=1.0):
        """Arrival frame after Whole library (#824): the existing Search tab, blank query, no keyboard.
        Schematic only; the Search tab itself is unchanged and is not redrawn in detail."""
        s = self.scr(theme, scale)
        s.status_bar()
        y = L.title(s, "Search")
        x0, x1 = L.px(16), DEVICE_W - L.px(16)
        bar_h = max(L.px(52), s.line_h("body") + L.px(24))
        s.rrect([x0, y, x1, y + bar_h], 24, fill=s.t["surface"])
        s.magnifier((x0 + L.px(26), y + bar_h / 2), L.px(8), s.t["ink2"])
        s.d.text((x0 + L.px(48), y + bar_h / 2), "Search photos, people, places", font=s.font("body"),
                 fill=s.t["ink2"], anchor="lm")
        s.target([x0, y, x1, y + bar_h], "Search field")
        s.note((DEVICE_W - L.px(8), y + bar_h + L.px(28)),
               "existing Search tab (tab 3), query blank, keyboard released; layout unchanged")
        s.note((DEVICE_W - L.px(8), y + bar_h + L.px(64)),
               "L7 bar closed; scoped person / album route replaced")
        s.gesture_bar()
        return s

    def l7(self) -> Board:
        b = Board(self.fonts, "brand-l7-search")
        b.header("BRAND-L7 · Search under the thumb",
                 "The search sphere grows into a full-width AtlasSearch bar right above the slab.",
                 self.facts(660, "Host: placement and focus-on-open change. The shared AtlasSearch host, back end (#175) "
                            "and the Whole library handoff stay as on main (#824): Whole library leaves this bar "
                            "and opens the existing Search tab with a blank query."))
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
        panels.append(Panel("B5", "Whole library: handoff", "Choosing Whole library closes this bar, releases the "
                            "keyboard, clears the local query and opens the existing Search tab with a blank "
                            "query. Nothing is shown in place (#824).", self.l7_handoff()))
        s = self.l7_open(query="bike", keyboard=False)
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
        panels.append(Panel("B7", "No results", "One sentence, one next step. 'Search whole library' is the same "
                            "handoff as the Whole library chip: the query is not carried over.", s))
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
        panels.append(Panel("B13", "Large library, 200 %, keyboard", "This screen at 200 % with the "
                            "keyboard: chips wrap, the suggestion list scrolls inside the panel (count noted).",
                            self.l7_open("dark", 2.0, query="beach", entries=True,
                                         items=[("Suggestion", "beach"), ("Suggestion", "beach 2019"),
                                                ("Suggestion", "beach with Noor"), ("Place", "Coast"),
                                                ("More", "More search options")])))
        s = self.l7_open(query="beach", keyboard=False)
        top = s.panel_top - L.px(8)
        s.d.rectangle([0, s.top, DEVICE_W, top], fill=s.t["ground"])
        s.text((L.px(L.SIDE), s.top + L.px(12)), "12.840 pictures for 'beach'", "section")
        L.square_grid(s, s.top + L.px(48), top - L.px(96), cols=6, seed=17, header="August 2026",
                      headers_every=18, month_names=["July 2025", "August 2019"])
        L.fast_thumb(s, 0.55, "2019", s.top + L.px(56), top - L.px(60))
        panels.append(Panel("B14", "Large library results", "12.840 results on this screen load page by page; the "
                            "held-sphere thumb jumps through the result years. Bar and query stay.", s))
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
        b.table("Search state transitions (#824)", L7_TRANSITION_HEAD, L7_TRANSITIONS, L7_TRANSITION_W,
                L7_TRANSITION_NOTE)
        b.table("Reference decisions", DECISION_HEAD, [
            ref_row("searchbar", "Adapted: suggestions and results stack upward from the bar so they stay above "
                    "the keyboard and near the thumb, instead of below a top bar."),
            ref_row("find5", "Changed: the bar is at the bottom and does auto-focus when it opens (L7 opening, "
                    "Quiet Frame v3); FIND-5 opens non-focused. Kept, unchanged: the This screen / Whole library "
                    "chips, the two-step Back and the Whole library handoff of main (close scope, release focus, "
                    "clear the local query, open the Search tab blank). Focus-on-open does not change the scope "
                    "handoff; a retained-query in-place library mode would need its own reviewed UI change."),
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
            L.fast_thumb(s, L.thumb_frac(lays[kind], scroll, vp), L.thumb_month(items, lays[kind], scroll, vp),
                         vp.top + L.px(8), s.bottom - L.px(110))
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
        s, _vis = self.l8_zoom(st.kind, st.scroll, selected=selected, outline=st.key)
        fy = st.focal[1]
        L.draw_pinch(s, st.focal, direction)
        L.crosshair(s, st.focal, f"F {L.fmt_pt(st.focal)}")
        L.anno_card(s, [
            f"{name} · {L.STOP_NAMES[st.kind]} · key {st.key} under F",
            f"photo rect {L.fmt_rect(st.src)} centre {L.fmt_pt(L.centre(st.src))}",
            f"F at {L.rel_v(st.src, fy) * 100:.0f} % of the photo height · scroll {st.scroll:.0f} px",
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
            "Illustrative still; FLOW-2 #712 owns runtime motion; no teleport at commit.",
            "With Remove animations on, the zoom cuts to the end frame.",
        ], self.l8_card_y(s))
        return s

    def l8_stop(self, kind: str, st: L.PinchStep, name: str, selected=None):
        """One rung of the six-stop ladder: same key, same focal height, with its density and touch facts."""
        s, vis = self.l8_zoom(kind, st.scroll, selected=selected, outline=st.key)
        r = st.dst
        w_dp, h_dp = (r[2] - r[0]) / L.DENSITY, (r[3] - r[1]) / L.DENSITY
        lay = self.l8_env()[1][kind]
        if lay.square:
            direct = (f"tile {L.tile_dp(lay.cols):.1f} dp · direct tap "
                      + ("OK (>= 48 dp)" if L.tile_dp(lay.cols) >= L.MIN_TARGET_DP
                         else "NO: dense, use the precision pathway"))
        else:
            direct = f"picture {w_dp:.0f} x {h_dp:.0f} dp, real ratio · direct tap OK"
        L.anno_card(s, [
            f"{name} · {L.STOP_NAMES[kind]} · key {st.key}",
            direct,
            f"key rect {L.fmt_rect(st.dst)} · scroll {st.scroll:.0f} px",
        ], self.l8_card_y(s))
        return s

    def l8_precision(self, kind: str, st: L.PinchStep, name: str, selected, lines: list[str], *, pill=None,
                     press=False, theme="light", scale=1.0, back=False, outline=True):
        """Precision-selection frame: the zoom, selection, the 48 dp pill and an annotation card."""
        items, lays, vp = self.l8_env(theme, scale)
        s, vis = self.l8_zoom(kind, st.scroll, theme, scale, selected=selected, outline=st.key if outline else None)
        box = None
        if pill:
            box = L.precision_pill(s, vp.top + L.px(8), pill, back=back)
        if press:
            cx, cy = L.centre(st.dst)
            r = L.px(26)
            s.d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=L.ANNO, width=L.px(3))
            s.d.ellipse([cx - L.px(5), cy - L.px(5), cx + L.px(5), cy + L.px(5)], fill=L.ANNO)
        L.anno_card(s, lines, self.l8_card_y(s))
        return s

    def l8_pill_frame(self, kind, st: L.PinchStep, name, selected, lines, *, theme="light", scale=1.0,
                      pill_text="Select precisely", back=False, tap_pill=False, tap_tile=False, centre=False,
                      focus=True, tile_target=False):
        """#864 frame: pill-only entry or its focused SMALL / return stop, with the focus anchor outlined.
        ``centre`` draws the viewport-centre mark that decided the anchor; ``tap_pill`` rings the pill."""
        items, lays, vp = self.l8_env(theme, scale)
        s, vis = self.l8_zoom(kind, st.scroll, theme, scale, selected=selected, outline=st.key)
        box = L.precision_pill(s, vp.top + L.px(8), pill_text, back=back)
        if tap_pill:
            cx, cy = L.centre(box)
            r = L.px(30)
            s.d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=L.ANNO, width=L.px(3))
        if centre:
            L.crosshair(s, (DEVICE_W / 2, (vp.top + vp.bottom) / 2), "viewport centre")
        if tile_target:
            t = vis[st.key]
            s.target(t, f"key {st.key}", actual=True)
        if focus:
            s.focus_order(box, "back to overview" if back else "Select precisely")
            if tile_target or tap_tile:
                s.focus_order(vis[st.key], f"key {st.key} tile")
        L.anno_card(s, lines, self.l8_card_y(s))
        return s

    def l8_thumb_frame(self, kind, scroll, name, lines, *, theme="light", scale=1.0, selected=None):
        """#864 frame: a held thumb whose month label comes from the first visible key."""
        items, lays, vp = self.l8_env(theme, scale)
        lay = lays[kind]
        key = L.thumb_anchor_key(lay, scroll, vp)
        s, vis = self.l8_zoom(kind, scroll, theme, scale, selected=selected, outline=key)
        L.anno_card(s, [f"{name} · {L.STOP_NAMES[kind]} · scroll {scroll:.0f} px",
                        f"thumb anchor key {key} = {items[key].month} -> label \"{L.thumb_month(items, lay, scroll, vp)}\""]
                    + lines, self.l8_card_y(s))
        return s

    L8_SIG_SELECTED = {1: 1, 6: 2, 9: 3}  # top of November, so the month heading is in view; 6 is a video

    def l8_month_sig(self, kind, theme, scale, scroll=None, placeholders=False, loading=None):
        """SMALL/MONTH/YEAR with the held-sphere vocabulary: ordered selection + halo, video, 46 dp thumb.
        Dense stops (MONTH, YEAR) in selection mode also carry the 48 dp "Select precisely" pill (#855)."""
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
        L.fast_thumb(s, L.thumb_frac(lay, scroll, cvp), L.thumb_month(items, lay, scroll, cvp),
                     cvp.top + L.px(8), s.bottom - L.px(110))
        s.d.rectangle([0, 0, DEVICE_W, top], fill=s.t["ground"])
        s.status_bar()
        L.title(s, "Pictures")
        if loading:
            L.loading_arc(s, vp.top, 0.4, loading)
        if kind in ("month", "year"):
            L.precision_pill(s, top + L.px(8), "Select precisely")
        s.slab("Pictures")
        s.gesture_bar()
        return s

    def l8(self) -> Board:
        b = Board(self.fonts, "brand-l8-justified")
        items, lays, vp = self.l8_env()
        b.header("BRAND-L8 · Real-shape rows at day zoom",
                 "Six stops: ORIGINAL, LARGE (~3) and MEDIUM (~4) keep real shapes; SMALL (7), MONTH (12) and YEAR "
                 "(28) are square overviews.",
                 self.facts(661, f"Pinch frames are computed from one synthetic {len(items)}-item library by "
                            "tools/design/lapis_screens.py storyboard(); every coordinate printed on a frame is "
                            "the drawn pixel position in the 1080 x 2340 frame (#836, corrected in #855)."))
        b.heroes([
            Panel("A1", "LARGE day zoom, light, 100 %", "Rows target 118 dp and stretch to the width; 2 dp gaps. "
                  "The 4.2:1 panorama gets its own full-width row, the 0.45 portrait keeps its height, a video "
                  "shows its duration, the final row keeps 118 dp instead of stretching.", self.l8_day()),
            Panel("A2", "LARGE day zoom, dark, 200 %", "Date headers scale; picture geometry does not depend on "
                  "font scale.", self.l8_day("dark", 2.0)),
        ], self.LEGEND)

        st = L.storyboard(lays, vp)
        sel = L.SELECTED
        ladder = [
            Panel("S1", "Stop 1 · ORIGINAL", "One picture per row at its real ratio, full width; a tall portrait "
                  "is centred narrower. Same key and focal height as the other stops.",
                  self.l8_stop("original", st["original"], "S1", sel)),
            Panel("S2", "Stop 2 · LARGE", "~3 per row, real-ratio justified rows (the day zoom of A1).",
                  self.l8_stop("large", st["start"], "S2", sel)),
            Panel("S3", "Stop 3 · MEDIUM", "~4 per row, still justified real-ratio rows; no crop.",
                  self.l8_stop("medium", st["in_medium"], "S3", sel)),
            Panel("S4", "Stop 4 · SMALL, 7 squares", "Square month overview. 53 dp tiles are real direct targets.",
                  self.l8_stop("small", st["in_small"], "S4", sel)),
            Panel("S5", "Stop 5 · MONTH, 12 squares", "Dense square overview. Tiles are 30 dp: sphere and thumb "
                  "stay visible, but selection goes through the precision pathway below.",
                  self.l8_stop("month", st["in_month"], "S5", sel)),
            Panel("S6", "Stop 6 · YEAR, 28 squares", "Densest overview. Tiles are 12 dp; the sphere is painted "
                  "wider than its tile. Not a direct tap.", self.l8_stop("year", st["in_year"], "S6", sel)),
        ]
        b.grid("The approved six-stop ladder, same media key (measured, 75 % scale)", ladder, cols=4, scale=0.75)

        pinch_panels = [
            Panel("C1", "Pinch in 1/4: LARGE", f"Fingers around key {st['start'].key}; F is the centroid. "
                  "Selection 1-3 is on.", self.l8_start(st["start"], "C1", selected=sel)),
            Panel("C2", "Pinch in 2/4: MEDIUM", f"Same key; centre {L.fmt_d(st['in_medium'].dx)} px in X. "
                  f"F now rests on key {st['in_medium'].extra['key_under_pointer']}.",
                  self.l8_end(st["in_medium"], "C2", "medium", sel)),
            Panel("C3", "Pinch in 3/4: SMALL (7)", f"Same key; centre {L.fmt_d(st['in_small'].dx)} px in X; height "
                  f"under F restored. F on key {st['in_small'].extra['key_under_pointer']}.",
                  self.l8_end(st["in_small"], "C3", "small", sel)),
            Panel("C4", "Pinch in 4/4: MONTH (12)", f"Centre {L.fmt_d(st['in_month'].dx)} px in X. F on key "
                  f"{st['in_month'].extra['key_under_pointer']}.", self.l8_end(st["in_month"], "C4", "month", sel)),
            Panel("C5", "Pinch in, last stop: YEAR (28)", f"Centre {L.fmt_d(st['in_year'].dx)} px in X. Selection "
                  f"and thumb carry over; no video mark fits on 12 dp tiles. F on key "
                  f"{st['in_year'].extra['key_under_pointer']}.",
                  self.l8_end(st["in_year"], "C5", "year", sel)),
            Panel("C6", "Halfway SMALL to MONTH", "Illustrative still: both layouts at 50 %; the key's rect is "
                  "halfway between its two positions.",
                  self.l8_mid("small", "month", st["in_month"], st["in_small"].scroll, sel)),
            Panel("C7", "Pinch out 1/4: MONTH", f"Anchor is key {st['out_month'].key}, the key under F in C5. "
                  f"Centre {L.fmt_d(st['out_month'].dx)} px in X.", self.l8_end(st["out_month"], "C7", "month", sel)),
            Panel("C8", "Pinch out 2/4: SMALL", f"Centre {L.fmt_d(st['out_small'].dx)} px in X.",
                  self.l8_end(st["out_small"], "C8", "small", sel)),
            Panel("C9", "Pinch out 3/4: MEDIUM", f"Centre {L.fmt_d(st['out_medium'].dx)} px in X; F on key "
                  f"{st['out_medium'].key}: {'yes' if st['out_medium'].hit else 'no'}.",
                  self.l8_end(st["out_medium"], "C9", "medium", sel)),
            Panel("C10", "Pinch out 4/4: LARGE", f"Day scroll {st['out_large'].scroll:.0f} px (C1: "
                  f"{st['start'].scroll:.0f} px): the anchor changed to key {st['out_large'].key}, so the round trip "
                  "does not return to C1.", self.l8_end(st["out_large"], "C10", "large", sel)),
            Panel("C11", "Interior key: LARGE", f"Key {st['int_start'].key}, a wide photo whose columns lie "
                  "away from its day position.", self.l8_start(st["int_start"], "C11", selected=sel)),
            Panel("C12", "Interior key: SMALL", f"Height restored, but the fixed column moves it "
                  f"{L.fmt_d(st['int_small'].dx)} px: F rests on key {st['int_small'].extra['key_under_pointer']}.",
                  self.l8_end(st["int_small"], "C12", "small", sel)),
            Panel("C13", "Interior key: MONTH", f"{L.fmt_d(st['int_month'].dx)} px in X; F on key "
                  f"{st['int_month'].extra['key_under_pointer']}.", self.l8_end(st["int_month"], "C13", "month", sel)),
            Panel("C14", "Interior key: YEAR", f"{L.fmt_d(st['int_year'].dx)} px in X; F rests on key "
                  f"{st['int_year'].extra['key_under_pointer']}. Disclosed, not hidden.",
                  self.l8_end(st["int_year"], "C14", "year", sel)),
            Panel("C15", "Top clamp: first photo", f"Scroll cannot go above 0: the key lands "
                  f"{L.fmt_d(st['top'].y_residual)} px from its focal height.",
                  self.l8_end(st["top"], "C15", "small")),
            Panel("C16", "Bottom clamp: last photo", f"Scroll cannot pass the end: "
                  f"{L.fmt_d(st['bottom'].y_residual)} px from its focal height.",
                  self.l8_end(st["bottom"], "C16", "small")),
        ]
        b.grid("Pinch storyboard, both directions (measured, 75 % scale)", pinch_panels, cols=4, scale=0.75)

        pf = L.precision_flow(lays, vp, "month")
        psel = dict(L.PRECISION_SELECTED)
        pk = L.PRECISION_KEY
        psel_pressed = {**psel, pk: 3}
        psel_more = {**psel_pressed, pk + 2: 4, pk - 3: 5}
        _i2, lays2, vp2 = self.l8_env("dark", 2.0)
        pf2 = L.precision_flow(lays2, vp2, "month")
        pyear = L.precision_flow(lays, vp, "year")
        prec = [
            Panel("P1", "MONTH 12: long-press", f"Tile {L.tile_dp(12):.1f} dp is dense, so a tap here never "
                  f"toggles key {pk}. Long-press enters selection with key {pk} as #3 and opens the 7-column view.",
                  self.l8_precision("month", pf["press"], "P1", psel, [
                      f"P1 · MONTH 12 · long-press on key {pk} at {L.fmt_pt(pf['press'].focal)}",
                      f"tile {L.fmt_rect(pf['press'].src)} = {L.tile_dp(12):.1f} dp: dense, no direct tap",
                      "pill: Select precisely, a 48 dp button, needs no long-press",
                  ], pill="Select precisely", press=True)),
            Panel("P2", "Focused SMALL 7", f"Same key {pk} at the same height ({L.fmt_d(pf['focus'].dx)} px in X, "
                  f"{L.fmt_d(pf['focus'].y_residual)} px height residual). Key is selected #3; tiles are "
                  f"{L.tile_dp(7):.1f} dp.",
                  self.l8_precision("small", pf["focus"], "P2", psel_pressed, [
                      f"P2 · SMALL 7 · focus-to-7 on key {pk}, outlined",
                      f"tile {L.tile_dp(7):.1f} dp: every tap is a real >= 48 dp target",
                      "pill: 12 columns (back), a 48 dp button; system Back does the same",
                  ], pill="12 columns", back=True)),
            Panel("P3", "Select more, precisely", "Neighbours are toggled by direct 53 dp taps; sphere order "
                  "continues 4, 5. Thumb stays at the right edge.",
                  self.l8_precision("small", pf["focus"], "P3", psel_more, [
                      "P3 · SMALL 7 · taps add keys " + f"{pk + 2} and {pk - 3} as #4 and #5",
                      f"each tile {L.tile_dp(7):.1f} dp; spheres and the thumb stay visible",
                      "selection is kept when Back returns to the overview",
                  ], pill="12 columns", back=True)),
            Panel("P4", "Back to MONTH 12", f"Key {pk} returns to the same height; {L.fmt_d(pf['back'].dx)} px in X. "
                  "All five spheres carry over.",
                  self.l8_precision("month", pf["back"], "P4", psel_more, [
                      f"P4 · MONTH 12 · back from the focused view, key {pk} outlined",
                      f"centre shift X {L.fmt_d(pf['back'].dx)} px, Y {L.fmt_d(pf['back'].dy)} px · height residual "
                      f"{L.fmt_d(pf['back'].y_residual)} px",
                      "selection 1-5 intact; Select precisely is offered again",
                  ], pill="Select precisely")),
            Panel("P5", "YEAR 28: same pathway", f"Tile {L.tile_dp(28):.1f} dp. Spheres are visible but tiles are "
                  "not tap targets; a long-press selects and focuses (the pill path is Q5, Q11-Q17).",
                  self.l8_precision("year", pyear["press"], "P5", psel, [
                      "P5 · YEAR 28 · long-press on key %d jumps to SMALL 7" % pk,
                      f"tile {L.tile_dp(28):.1f} dp: dense, no direct tap; the 48 dp pill is the target",
                      "the zoom Back returns to YEAR with the same key and height",
                  ], pill="Select precisely", press=True)),
            Panel("P6", "Focused SMALL, dark, 200 %", "Pill and card scale with the font; tiles and spheres "
                  "keep their size.",
                  self.l8_precision("small", pf2["focus"], "P6", psel_pressed, [
                      f"P6 · SMALL 7 · dark 200 % · key {pk}",
                      f"tile {L.tile_dp(7):.1f} dp; pill grows to fit the label",
                  ], pill="12 columns", back=True, theme="dark", scale=2.0)),
        ]
        b.grid("Precision selection from the dense overview (measured, 75 % scale)", prec, cols=4, scale=0.75)

        # ---- #864: truthful thumb month and pill-only precision entry
        mlay = lays["month"]
        boundary = mlay.rects[267][3]  # bottom of the last November row (key 267 is the last November key)
        s_sep_m = mlay.rects[700][1] - 0.5 * vp.h  # a September scroll position
        s_sep_s = lays["small"].rects[568][1] - 0.4 * vp.h
        s_sep_y = lays["year"].rects[267][3] + 30  # just past the last November row
        _i2, lays2d, vp2d = self.l8_env("dark", 2.0)
        s_sep_d = lays2d["month"].rects[700][1] - 0.5 * vp2d.h
        thumbs = [
            Panel("T1", "MONTH, top: Nov", "First visible key is November, so the thumb reads Nov.",
                  self.l8_thumb_frame("month", 0.0, "T1", ["first visible key is the thumb anchor"])),
            Panel("T2", "MONTH, last Nov row", f"Scroll {boundary - 60:.0f} px: the last November row is still "
                  "partly in view, so the label is still Nov.",
                  self.l8_thumb_frame("month", boundary - 60, "T2", ["tie-break: a partly visible row counts"])),
            Panel("T3", "MONTH, 120 px later: Sep", f"Scroll {boundary + 60:.0f} px: the first visible key is "
                  "September; the thumb turns to Sep.",
                  self.l8_thumb_frame("month", boundary + 60, "T3", ["label turns when the Nov rows have left"])),
            Panel("T4", "MONTH, mid September", "Not a fixed Nov: the label follows the key at any position.",
                  self.l8_thumb_frame("month", s_sep_m, "T4", ["same key as the Select precisely frames"])),
            Panel("T5", "SMALL 7, September", "Same rule at the 7-column stop.",
                  self.l8_thumb_frame("small", s_sep_s, "T5", ["53 dp tiles, thumb label from key"])),
            Panel("T6", "YEAR 28, September", "Same rule at 28 columns, where one screen spans several months.",
                  self.l8_thumb_frame("year", s_sep_y, "T6", ["first visible key sets the month"])),
            Panel("T7", "MONTH, dark, 200 %", "Label follows the key; the thumb keeps its 46 dp size.",
                  self.l8_thumb_frame("month", s_sep_d, "T7", ["dark 200 %, thumb 46 dp in a 48 dp target"],
                                      theme="dark", scale=2.0)),
        ]
        b.grid("Fast-scroll thumb label: month of the first visible key (measured, 75 % scale)", thumbs, cols=4,
               scale=0.75)

        pm = L.pill_flow(lays, vp, "month", s_sep_m)
        py = L.pill_flow(lays, vp, "year", s_sep_y)
        s_sel = lays["month"].rects[845][1] - 0.5 * vp.h
        ps = L.pill_flow(lays, vp, "month", s_sel)
        ptb = L.pill_flow(lays, vp, "month", s_sel, focused=828)
        pd = L.pill_flow(lays2d, vp2d, "month", s_sep_d)
        s_sel_y = lays["year"].rects[845][1] - 0.5 * vp.h
        pys = L.pill_flow(lays, vp, "year", s_sel_y)
        s_sep_yd = lays2d["year"].rects[845][1] - 0.5 * vp2d.h
        pyd = L.pill_flow(lays2d, vp2d, "year", s_sep_yd)
        k_m, k_y, k_s, k_tb, k_d = (x["press"].key for x in (pm, py, ps, ptb, pd))
        k_ys, k_yd = pys["press"].key, pyd["press"].key
        sel3 = dict(L.SELECTED)
        pill = [
            Panel("Q1", "MONTH, no selection: tap the pill",
                  f"Nothing is focused or selected. The pill takes the visible key nearest the viewport centre: "
                  f"key {k_m}. It does not select.",
                  self.l8_pill_frame("month", pm["press"], "Q1", None, [
                      f"Q1 · MONTH 12 · Select precisely · no focus, no selection",
                      f"anchor = nearest visible key to the centre = {k_m} ({pm['press'].extra['rule']})",
                      "tie: lower key. Selection stays empty",
                  ], tap_pill=True, centre=True)),
            Panel("Q2", "Focused SMALL 7, still unselected",
                  f"Key {k_m} outlined at {L.fmt_d(pm['focus'].dx)} px in X and "
                  f"{L.fmt_d(pm['focus'].y_residual)} px height residual. Nothing is selected yet.",
                  self.l8_pill_frame("small", pm["focus"], "Q2", None, [
                      f"Q2 · SMALL 7 · key {k_m} focused, not selected",
                      f"tile {L.fmt_rect(pm['focus'].dst)} = {L.tile_dp(7):.1f} dp: a real >= 48 dp target",
                      "focus order: 1 pill (back), 2 tile; Back returns",
                  ], pill_text=L.return_label("month"), back=True, tile_target=True)),
            Panel("Q3", "Select by a real tap", f"The user taps the {L.tile_dp(7):.1f} dp tile: key {k_m} becomes "
                  "#1. Selecting is the user's tap, never the pill.",
                  self.l8_pill_frame("small", pm["focus"], "Q3", {k_m: 1}, [
                      f"Q3 · SMALL 7 · tap on key {k_m} -> selected #1",
                      f"hit rect {L.fmt_rect(pm['focus'].dst)}, {L.tile_dp(7):.1f} dp",
                      "announce: \"Photo, 14 Sep, selected, 1 of 1\" (proposal)",
                  ], pill_text=L.return_label("month"), back=True, tile_target=True)),
            Panel("Q4", "Back to MONTH 12", f"Back or the return pill restores scroll {pm['back'].scroll:.0f} px "
                  f"(was {pm['press'].scroll:.0f} px) and the selection.",
                  self.l8_pill_frame("month", pm["back"], "Q4", {k_m: 1}, [
                      f"Q4 · MONTH 12 · back, key {k_m} selected #1 kept",
                      f"scroll {pm['back'].scroll:.0f} px = entry scroll {pm['press'].scroll:.0f} px",
                      "pill is offered again; one Back = one return",
                  ])),
            Panel("Q5", "YEAR 28, no selection: tap the pill",
                  f"Same rule at YEAR: nearest visible key to the viewport centre, key {k_y}; 12 dp tiles stay "
                  "dense, the 48 dp pill is the target.",
                  self.l8_pill_frame("year", py["press"], "Q5", None, [
                      f"Q5 · YEAR 28 · Select precisely · anchor key {k_y}",
                      f"tile {L.tile_dp(28):.1f} dp: no direct tap; pill >= 48 dp",
                      "Back returns to YEAR with the same key and height",
                  ], tap_pill=True, centre=True)),
            Panel("Q6", "MONTH, prior selection 1-3",
                  f"Selected keys do not move the anchor. Centre rule: key {k_s}, not a selected key.",
                  self.l8_pill_frame("month", ps["press"], "Q6", sel3, [
                      f"Q6 · MONTH 12 · selection 1-3 on keys {', '.join(map(str, sel3))}",
                      f"anchor = centre rule = key {k_s}; no focus, no auto-select",
                      "the three selected keys stay selected",
                  ], tap_pill=True, centre=True)),
            Panel("Q7", "Focused SMALL, selection kept",
                  f"Key {k_s} outlined and unselected; spheres 1-3 carry over. Tapping it would add #4.",
                  self.l8_pill_frame("small", ps["focus"], "Q7", sel3, [
                      f"Q7 · SMALL 7 · key {k_s} focused, selection 1-3 kept",
                      f"tile {L.tile_dp(7):.1f} dp tap -> #4; Back restores the overview",
                      "focus order: 1 pill, 2 tile",
                  ], pill_text=L.return_label("month"), back=True, tile_target=True)),
            Panel("Q8", "MONTH, TalkBack focus on key 828",
                  f"An explicit visible focus is kept: key 828, not the centre key {k_s}. Same pill, same flow.",
                  self.l8_pill_frame("month", ptb["press"], "Q8", sel3, [
                      f"Q8 · MONTH 12 · TalkBack focus on key {k_tb} (visible)",
                      f"kept as anchor ({ptb['press'].extra['rule']}); centre rule would pick {k_s}",
                      "stale focus (off screen): centre rule instead",
                  ], tap_pill=True)),
            Panel("Q9", "MONTH, dark, 200 %",
                  f"Pill grows with the font; centre rule gives key {k_d}. Tiles keep their size.",
                  self.l8_pill_frame("month", pd["press"], "Q9", None, [
                      f"Q9 · MONTH 12 · dark 200 % · anchor key {k_d}",
                      "pill grows to fit, hit rect >= 48 dp",
                      "no selection; the pill does not select",
                  ], theme="dark", scale=2.0, tap_pill=True, centre=True)),
            Panel("Q10", "Focused SMALL, dark, 200 %",
                  f"Key {k_d} outlined; the 7-column tile is a real {L.tile_dp(7):.1f} dp target; Back pill >= 48 dp.",
                  self.l8_pill_frame("small", pd["focus"], "Q10", None, [
                      f"Q10 · SMALL 7 · dark 200 % · key {k_d} focused",
                      f"tile {L.tile_dp(7):.1f} dp; focus order: 1 pill, 2 tile",
                      "Back or the return pill restores MONTH",
                  ], theme="dark", scale=2.0, pill_text=L.return_label("month"), back=True, tile_target=True)),
            Panel("Q11", "YEAR to SMALL 7, still unselected",
                  f"Key {k_y} outlined at {L.fmt_d(py['focus'].dx)} px in X, {L.fmt_d(py['focus'].y_residual)} px "
                  "height residual. Nothing is selected yet.",
                  self.l8_pill_frame("small", py["focus"], "Q11", None, [
                      f"Q11 · SMALL 7 · from YEAR · key {k_y} focused, not selected",
                      f"tile {L.tile_dp(7):.1f} dp: a real >= 48 dp target; pill hit >= 48 dp",
                      "focus order: 1 pill (back), 2 tile",
                  ], pill_text=L.return_label("year"), back=True, tile_target=True)),
            Panel("Q12", "YEAR path: select by a real tap",
                  f"The tap on the {L.tile_dp(7):.1f} dp tile selects key {k_y} as #1; the pill never does.",
                  self.l8_pill_frame("small", py["focus"], "Q12", {k_y: 1}, [
                      f"Q12 · SMALL 7 · tap on key {k_y} -> selected #1",
                      f"hit rect {L.fmt_rect(py['focus'].dst)}",
                      "announce: \"Photo, selected, 1 of 1\" (proposal)",
                  ], pill_text=L.return_label("year"), back=True, tile_target=True)),
            Panel("Q13", "Back to YEAR 28",
                  f"Return restores scroll {py['back'].scroll:.0f} px (entry {py['press'].scroll:.0f} px) and "
                  "the selection.",
                  self.l8_pill_frame("year", py["back"], "Q13", {k_y: 1}, [
                      f"Q13 · YEAR 28 · back, key {k_y} selected #1 kept",
                      f"scroll {py['back'].scroll:.0f} px = entry scroll {py['press'].scroll:.0f} px",
                      "pill offered again; one Back = one return",
                  ])),
            Panel("Q14", "YEAR, prior selection 1-3",
                  f"Selected keys do not move the anchor. Centre rule: key {k_ys}, not a selected key.",
                  self.l8_pill_frame("year", pys["press"], "Q14", sel3, [
                      f"Q14 · YEAR 28 · selection 1-3 on keys {', '.join(map(str, sel3))}",
                      f"anchor = centre rule = key {k_ys}; no focus, no auto-select",
                      "the three selected keys stay selected",
                  ], tap_pill=True, centre=True)),
            Panel("Q15", "YEAR to SMALL 7, selection kept",
                  f"Key {k_ys} outlined and unselected; spheres 1-3 carry over. A tap would add #4.",
                  self.l8_pill_frame("small", pys["focus"], "Q15", sel3, [
                      f"Q15 · SMALL 7 · from YEAR · key {k_ys} focused, selection 1-3 kept",
                      f"tile {L.tile_dp(7):.1f} dp tap -> #4; Back restores YEAR",
                      "focus order: 1 pill, 2 tile",
                  ], pill_text=L.return_label("year"), back=True, tile_target=True)),
            Panel("Q16", "YEAR, dark, 200 %, prior selection",
                  f"Pill grows with the font; centre rule gives key {k_yd}. Selection 1-3 stays.",
                  self.l8_pill_frame("year", pyd["press"], "Q16", sel3, [
                      f"Q16 · YEAR 28 · dark 200 % · anchor key {k_yd}",
                      "pill grows to fit, hit rect >= 48 dp",
                      "selection 1-3 intact; the pill does not select",
                  ], theme="dark", scale=2.0, tap_pill=True, centre=True)),
            Panel("Q17", "YEAR to SMALL 7, dark, 200 %",
                  f"Key {k_yd} outlined; the 7-column tile is a real {L.tile_dp(7):.1f} dp target; Back pill "
                  ">= 48 dp.",
                  self.l8_pill_frame("small", pyd["focus"], "Q17", sel3, [
                      f"Q17 · SMALL 7 · from YEAR · dark 200 % · key {k_yd}",
                      f"tile {L.tile_dp(7):.1f} dp; focus order: 1 pill, 2 tile",
                      "Back or the return pill restores YEAR",
                  ], theme="dark", scale=2.0, pill_text=L.return_label("year"), back=True, tile_target=True)),
        ]
        b.grid("Select precisely with no prior target: deterministic focus key (measured, 75 % scale)", pill,
               cols=4, scale=0.75)
        b.table("Rules drawn in T1-T7 and Q1-Q17 (#864)", ["Rule", "Definition", "Frames"], [
            ["Thumb label", "Month of the first visible media key: the first key in library order with a pixel "
             "below the top edge of the grid. A heading belongs to the keys under it.", "T1-T7"],
            ["Month change", f"At MONTH scroll {boundary - 60:.0f} px the last November row is still visible: Nov. "
             f"At {boundary + 60:.0f} px it has left: Sep. The label turns when the last row of the previous month "
             "leaves the top edge.", "T2, T3"],
            ["Pill anchor, nothing focused", "Nearest visible key to the viewport centre (distance to tile centre); "
             "equal distance goes to the lower key, the earlier in library order. Selection never moves it.",
             "Q1, Q5, Q6, Q9, Q14, Q16"],
            ["Pill anchor, explicit focus", "A long-press or TalkBack focus key that is still visible is kept; "
             "a stale (off-screen) focus is not eligible and the centre rule applies.", "Q8, P1"],
            ["Pill result", "Focus to SMALL 7 on that key at the same relative height. The key is outlined and not "
             "selected; selecting is a tap on the 53 dp tile. Long-press keeps its selecting semantics.",
             "Q2, Q3, Q7, Q10, Q11, Q12, Q15, Q17"],
            ["Return", "Back or the return pill (12 columns from MONTH, 28 columns from YEAR): previous overview, scroll anchor and selected keys.", "Q4, Q13"],
            ["Targets", f"Pill >= 48 dp (grows at 200 %); SMALL tile {L.tile_dp(7):.1f} dp; return pill >= 48 dp. "
             "Dense 12 / 28 tiles are never direct taps.", "Q1-Q17"],
            ["TalkBack order (proposal)", "1 Select precisely or the return pill, 2 the focused tile, then the "
             "grid; announcement of the pill names the month of the anchor. Not a device test.", "Q2, Q7, Q10, Q11, Q15, Q17"],
        ], [0.18, 0.67, 0.15])

        panels = [
            Panel("B1", "SMALL zoom, light, 100 %", "7 squares per row, 53 dp tiles. Ordered selection spheres 1-3 "
                  "with 2 dp white halo, 0:14 video badge, 46 dp thumb in a 48 dp target (label = month of the first visible key).",
                  self.l8_month_sig("small", "light", 1.0)),
            Panel("B2", "MONTH zoom, light, 100 %", "12 squares per row, 30 dp tiles. Smaller sphere, play glyph, "
                  "Select precisely pill, same thumb.", self.l8_month_sig("month", "light", 1.0)),
            Panel("B3", "MONTH zoom, dark, 200 %", "Headers and pill scale; tiles, spheres and thumb keep their "
                  "size.", self.l8_month_sig("month", "dark", 2.0)),
            Panel("B4", "YEAR zoom, light, 100 %", "28 squares per row, 12 dp tiles. Spheres are wider than a "
                  "tile; no video mark fits.", self.l8_month_sig("year", "light", 1.0)),
            Panel("B5", "YEAR zoom, dark, 200 %", "Short month headers stay on one line at 200 %.",
                  self.l8_month_sig("year", "dark", 2.0)),
            Panel("D1", "Selection in day rows", "Same sphere, halo and order numbers as the overviews.",
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
        panels.append(Panel("E3", "Loading, MONTH, dark 200 %", "Squares hold their place as surface tiles; "
                            "selection, thumb and the precision pill already work.",
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
        L.square_grid(s, y, s.bottom, cols=28, seed=9, header="2014")
        L.fast_thumb(s, 0.7, "2014", y + L.px(8), s.bottom - L.px(110))
        s.slab("Pictures")
        s.gesture_bar()
        panels.append(Panel("E6", "150k library, YEAR 28", "Schematic only. No performance is claimed; see the "
                            "post-build gate below.", s))
        b.grid("Overview signature, states", panels)

        def row(code, label, x):
            clamp = x.clamped or "none"
            return [code, label, str(x.key), L.fmt_pt(x.focal),
                    f"{L.fmt_rect(x.src)}\ncentre {L.fmt_pt(L.centre(x.src))}",
                    f"{L.fmt_rect(x.dst)}\ncentre {L.fmt_pt(L.centre(x.dst))}",
                    f"{L.fmt_d(x.dx)} / {L.fmt_d(x.dy)}", L.fmt_d(x.y_residual), "yes" if x.hit else "NO",
                    f"{x.scroll:.0f} ({clamp})"]

        def per_row(kind):
            rows = {}
            for r in lays[kind].rects.values():
                rows.setdefault(round(r[1]), 0)
                rows[round(r[1])] += 1
            return sum(rows.values()) / len(rows)

        b.table("The six stops, as built in this evidence", ["Stop", "Layout", "Size at 384 dp", "Direct per-media tap",
                                                             "Selection mark"], [
            ["1 ORIGINAL", "1 per row, real ratio", "full width; tall portraits centred narrower",
             "yes, >= 48 dp", "full sphere, 2 dp halo"],
            ["2 LARGE", f"~3 per row ({per_row('large'):.2f} measured), real-ratio rows",
             "row height target 118 dp", "yes, >= 48 dp", "full sphere"],
            ["3 MEDIUM", f"~4 per row ({per_row('medium'):.2f} measured), real-ratio rows",
             "row height target 86 dp", "yes, >= 48 dp", "full sphere"],
            ["4 SMALL", "7 squares, month sections", f"{L.tile_dp(7):.1f} dp tiles", "yes, >= 48 dp",
             "sphere r 11 dp"],
            ["5 MONTH", "12 squares, month sections", f"{L.tile_dp(12):.1f} dp tiles",
             "no: precision pathway", "sphere r 8 dp, painted, not a target"],
            ["6 YEAR", "28 squares, short month headings", f"{L.tile_dp(28):.1f} dp tiles",
             "no: precision pathway", "sphere r 7 dp, wider than its tile, not a target"],
        ], [0.10, 0.25, 0.22, 0.17, 0.26],
            "Tile sizes use the 1080 px frame: (1080 px - gaps) / columns / 2.8125. No stop is added or removed, "
            "and YEAR is never 7 columns.")

        b.table("Pinch geometry, measured from the frames above (px in the 1080 x 2340 frame)",
                ["Frame", "Step", "Key", "Focal F", "Key rect before", "Key rect after", "Centre shift X / Y",
                 "Height residual", "F on key", "Scroll (clamp)"], [
                    row("C2", "LARGE -> MEDIUM", st["in_medium"]),
                    row("C3", "MEDIUM -> SMALL", st["in_small"]),
                    row("C4", "SMALL -> MONTH", st["in_month"]),
                    row("C5", "MONTH -> YEAR", st["in_year"]),
                    row("C7", "YEAR -> MONTH", st["out_month"]),
                    row("C8", "MONTH -> SMALL", st["out_small"]),
                    row("C9", "SMALL -> MEDIUM", st["out_medium"]),
                    row("C10", "MEDIUM -> LARGE", st["out_large"]),
                    row("C12", "LARGE -> SMALL", st["int_small"]),
                    row("C13", "SMALL -> MONTH", st["int_month"]),
                    row("C14", "MONTH -> YEAR", st["int_year"]),
                    row("C15", "LARGE -> SMALL, top", st["top"]),
                    row("C16", "LARGE -> SMALL, bottom", st["bottom"]),
                ], [0.05, 0.12, 0.05, 0.08, 0.16, 0.16, 0.10, 0.07, 0.07, 0.14],
                "Rule drawn: the pinch keeps the media key as the semantic anchor and restores its relative height "
                "under F by choosing the new scroll offset (clamped to the content). Square grids are "
                "edge-aligned and a key's column is its position in the month modulo the column count, so X is "
                "quantized. One stationary F can stay on the same key in two grids only when the key's two horizontal "
                "spans overlap: for SMALL -> MONTH that holds for "
                f"{L.shared_point_positions(7, 12)[0]} of every {L.shared_point_positions(7, 12)[1]} month "
                f"positions, for MONTH -> YEAR for {L.shared_point_positions(12, 28)[0]} of every "
                f"{L.shared_point_positions(12, 28)[1]}; otherwise the key keeps its height and moves to its column "
                "(horizontal shift and any pointer departure are disclosed above). At the top and bottom clamps "
                "the height residual is disclosed instead. No frame claims every stationary pointer stays over "
                "the same tile or a zero X drift.")
        b.table("Precision selection from MONTH or YEAR", ["Step", "Control", "Result", "Target", "Back"], [
            ["Enter by long-press", "Long-press a dense tile", f"Focus to SMALL 7 on the same key at the same "
             f"height; the pressed key is selected", "tile n/a (dense)", "n/a"],
            ["Enter by pill", "Select precisely pill, no prior target", "Focus to SMALL 7 on the visible key nearest "
             "the viewport centre (a visible focus key if one exists); nothing is selected", "pill >= 48 dp", "n/a"],
            ["Select", "Tap tiles or spheres in the 7-column view", f"Toggles per-media selection; sphere order "
             f"continues", f"{L.tile_dp(7):.1f} dp tiles, >= 48 dp", "n/a"],
            ["Return", "Return pill (12 columns from MONTH, 28 columns from YEAR) or system Back", "Back to the overview stop it came from; selection, key "
             "and height continue", "pill >= 48 dp; system Back", "one Back = one return"],
            ["TalkBack", "Dense tile nodes keep their label and expose Select and Select precisely actions",
             "The same selection state; no 48 dp touch is needed because activation targets the node",
             "pill is a normal 48 dp button", "TalkBack Back returns"],
        ], [0.10, 0.28, 0.32, 0.18, 0.12],
            "A proposal for #661, shown as synthetic frames only. The TalkBack row is a design requirement to "
            "verify on the S24+; nothing here is a measurement.")
        b.table("Layout rules", ["Rule", "Value", "Notes"], [
            ["Target row height", "118 dp LARGE, 86 dp MEDIUM", "Rows scale to fill 384 dp exactly; height varies "
             "per row."],
            ["Gap", "2 dp", "Between pictures and rows (Quiet Frame v3)."],
            ["Panorama cap", "One full row", "Wider than the row at target height: shown full width, lower row."],
            ["Final row", "Not stretched", "Keeps target height and aligns left."],
            ["Crop and letterbox", "Never in real-ratio stops", "Day thumbnails decode at row size from the real "
             "aspect ratio; SMALL, MONTH and YEAR squares are centre crops of the same picture."],
            ["Pinch anchor", "Key, relative height", "Same key; relative height restored via scroll, clamped at "
             "the top and end; X follows the key's fixed column and the shift is disclosed (table above)."],
            ["Direct per-media targets", ">= 48 dp", "Rows, ORIGINAL and the 53 dp SMALL tiles qualify. 30 dp "
             "and 12 dp tiles do not, so they use the precision pathway."],
        ], [0.18, 0.17, 0.65])
        b.table("Reference decisions", DECISION_HEAD, [
            ref_row("justified", "Adopted for ORIGINAL, LARGE and MEDIUM: rows from aspect ratios, with a capped "
                    "panorama and an unstretched final row."),
            ref_row("grid", "Kept for SMALL, MONTH and YEAR: equal square cells give stable targets for fast "
                    "scanning. Consequence: a key's column is fixed by its position, so the pinch anchor can keep "
                    "the key and its height but not its horizontal position (measured above)."),
            ref_row("touch", "Kept at 48 dp for every direct per-media action; dense tiles are not offered as "
                    "direct taps and use the 48 dp precision pathway instead."),
            ref_row("frames", "Post-build gate only: a FrameMetrics fling test on a 150k synthetic library against "
                    "the #119 target. No measurement exists yet and none is shown."),
        ], DECISION_W, DECISION_NOTE)
        b.notes("What stays open for the re-preflight and the #661 build", [
            "Pinch invariant. Shown here: the same media key and its relative height under the fingers wherever "
            "scroll bounds allow, both directions, with the X shift disclosed. Not claimed: a stationary pointer "
            "staying over the same tile, zero X drift, or a literal same-screen-point landing. Runtime motion "
            "stays with FLOW-2 #712.",
            "Precision pathway. Long-press and the Select precisely pill are a proposal; if the build cannot keep "
            "the parity behaviour with it, the gap goes back to Strategy, not to a smaller target.",
            "Post-build acceptance on #661: Android gestures, TalkBack, and < 1 % FrameMetrics jank on 150k "
            "synthetic media.",
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
            Image.MAX_IMAGE_PIXELS = None  # our own sheets: the L8 sheet exceeds Pillow's default bomb warning
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
        for key, entry in man.get("recordings", {}).items():
            probs = FS.problems(entry, out / entry["file"])
            print(("FAIL " if probs else "PASS ") + f"recording {key} {entry['file']} frames={entry.get('frames')} "
                  f"ms={entry.get('duration_ms')}" + (" " + "; ".join(probs) if probs else ""))
            ok &= not probs
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
        if key == "l4":
            gif = out / FS.GIF_NAME
            FS.save_gif(FS.render_frames(gen), gif)
            man.setdefault("recordings", {})["l4_fast_scroll"] = FS.describe(gif)
            print(f"wrote {gif}")
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
