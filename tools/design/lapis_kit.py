"""Drawing primitives for the synthetic Lapis (Quiet Frame v3) preflight boards.

Everything here is synthetic: generated gradient placeholders, schematic dots and
fictional labels. Nothing reads private media, location data or the network.
Tokens come from docs/design/QUIET_FRAME.md v3 and must not be extended here.
"""

from __future__ import annotations

import math
import os
import random
from dataclasses import dataclass, field
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

# ---------------------------------------------------------------------------
# Device geometry (simulated, stated on every board)
# ---------------------------------------------------------------------------
DEVICE_W, DEVICE_H = 1080, 2340  # S24+ default FHD+ resolution in px
DENSITY = 2.8125  # 1080 px / 384 dp, same viewport as the FIND-5 frames (docs/design/declutter-search-341)
STATUS_DP = 29  # assumed status-bar inset (FIND-5 value; measure on the S24+)
NAV_DP = 16  # assumed gesture-navigation inset (FIND-5 value)
IME_DP = 324  # test IME height measured on the CI emulator (atlas #437)


def px(dp: float) -> int:
    return int(round(dp * DENSITY))


# ---------------------------------------------------------------------------
# Quiet Frame v3 colour tokens (no new tokens)
# ---------------------------------------------------------------------------
TOKENS = {
    "light": {
        "ground": "#FFFFFF",
        "surface": "#F2F3F8",
        "ink": "#0E1030",
        "ink2": "#5A5F7A",
        "brand_fill": "#2639C9",
        "brand_text": "#2639C9",
        "on_brand": "#FFFFFF",
        "on_brand2": "#DCE1FF",
    },
    "dark": {
        "ground": "#000000",
        "surface": "#14152A",
        "ink": "#F2F3F8",
        "ink2": "#9AA0B8",
        "brand_fill": "#3448E0",
        "brand_text": "#8C9BFF",
        "on_brand": "#FFFFFF",
        "on_brand2": "#DCE1FF",
    },
}
VIEWER_GROUND = "#000000"
VIEWER_SLAB = "#3448E0"
VIEWER_SPHERE = "#8C9BFF"

# Annotation layer: deliberately outside the palette so it can never be read as UI.
ANNO = "#E0007A"
ANNO_SOFT = (224, 0, 122, 46)
BOARD_BG = "#E9EAF0"
BOARD_INK = "#0E1030"
BOARD_INK2 = "#5A5F7A"


def rgb(hexstr: str) -> tuple[int, int, int]:
    h = hexstr.lstrip("#")
    return tuple(int(h[i : i + 2], 16) for i in (0, 2, 4))  # type: ignore[return-value]


# ---------------------------------------------------------------------------
# Fonts: Funnel Display / Funnel Sans (OFL), variable weight axis 300-800
# ---------------------------------------------------------------------------
FONT_FILES = {"display": "funnel_display.ttf", "sans": "funnel_sans.ttf"}
FALLBACK = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"


@dataclass
class FontSource:
    directory: Path | None
    fallback: bool

    def describe(self) -> str:
        if self.fallback:
            return "FALLBACK FONT (DejaVu Sans), not Funnel: do not use for review"
        return "Funnel Display + Funnel Sans (OFL), variable weight"


def find_fonts(explicit: str | None, repo_root: Path, allow_fallback: bool) -> FontSource:
    candidates = []
    if explicit:
        candidates.append(Path(explicit))
    if os.environ.get("ATLAS_FUNNEL_DIR"):
        candidates.append(Path(os.environ["ATLAS_FUNNEL_DIR"]))
    # BRAND-L2 (#720) bundles the fonts here once it lands on main.
    candidates.append(repo_root / "app/src/main/res/font")
    for c in candidates:
        if all((c / f).is_file() for f in FONT_FILES.values()):
            return FontSource(c, False)
    if allow_fallback and Path(FALLBACK).is_file():
        return FontSource(None, True)
    raise SystemExit(
        "Funnel fonts not found. Pass --font-dir with funnel_display.ttf and funnel_sans.ttf "
        "(the BRAND-L2 files from PR #720, app/src/main/res/font/), set ATLAS_FUNNEL_DIR, "
        "or use --allow-fallback-font for a layout-only smoke run."
    )


class Fonts:
    def __init__(self, src: FontSource):
        self.src = src
        self._cache: dict[tuple, ImageFont.FreeTypeFont] = {}

    def get(self, face: str, weight: int, size_px: float) -> ImageFont.FreeTypeFont:
        size = max(6, int(round(size_px)))
        key = (face, weight, size)
        f = self._cache.get(key)
        if f is None:
            if self.src.fallback:
                path = FALLBACK
                f = ImageFont.truetype(path, size)
            else:
                path = str(self.src.directory / FONT_FILES[face])  # type: ignore[operator]
                f = ImageFont.truetype(path, size)
                f.set_variation_by_axes([weight])
            self._cache[key] = f
        return f


# Type roles from Quiet Frame v3 (size sp, line sp, face, weight)
ROLES = {
    "title": (38, 44, "display", 700),
    "section": (18, 24, "sans", 600),
    "date": (16, 22, "sans", 600),
    "body": (15, 22, "sans", 400),
    "secondary": (14, 20, "sans", 400),
    "nav": (14, 20, "sans", 600),
    "wordmark": (38, 44, "display", 800),
}


# ---------------------------------------------------------------------------
# Synthetic photo placeholders
# ---------------------------------------------------------------------------
PLACEHOLDER_HUES = [
    ((236, 170, 120), (120, 70, 140)),
    ((120, 190, 210), (30, 70, 120)),
    ((200, 220, 150), (60, 110, 70)),
    ((240, 210, 160), (170, 90, 60)),
    ((180, 170, 230), (60, 50, 120)),
    ((230, 180, 190), (110, 60, 80)),
    ((170, 210, 190), (40, 90, 90)),
    ((220, 200, 230), (90, 70, 110)),
]


def placeholder(w: int, h: int, seed: int) -> Image.Image:
    """Abstract generated picture: a two-stop gradient with soft shapes. Never a photo."""
    r = random.Random(seed)
    a, b = PLACEHOLDER_HUES[seed % len(PLACEHOLDER_HUES)]
    sw, sh = max(4, w // 6), max(4, h // 6)
    small = Image.new("RGB", (sw, sh))
    angle = r.random() * math.pi
    ca, sa = math.cos(angle), math.sin(angle)
    pix = small.load()
    for y in range(sh):
        for x in range(sw):
            t = ((x / sw - 0.5) * ca + (y / sh - 0.5) * sa) + 0.5
            t = min(1.0, max(0.0, t))
            pix[x, y] = tuple(int(a[i] * (1 - t) + b[i] * t) for i in range(3))
    d = ImageDraw.Draw(small, "RGBA")
    for _ in range(3):
        cx, cy = r.random() * sw, r.random() * sh
        rad = (0.15 + r.random() * 0.35) * min(sw, sh)
        tone = 255 if r.random() > 0.5 else 0
        d.ellipse([cx - rad, cy - rad, cx + rad, cy + rad], fill=(tone, tone, tone, 40))
    # horizon band to suggest a scene without depicting anything real
    hy = int(sh * (0.55 + r.random() * 0.2))
    d.rectangle([0, hy, sw, sh], fill=(0, 0, 0, 28))
    return small.resize((w, h), Image.BICUBIC)


# ---------------------------------------------------------------------------
# Justified real-shape rows (day zoom)
# ---------------------------------------------------------------------------
def justified_rows(ratios: list[float], width: float, target_h: float, gap: float,
                   last_row_max: float | None = None) -> list[list[tuple[int, float, float]]]:
    """Greedy justified layout. Returns rows of (index, width, height) in the same unit.

    Every item keeps its aspect ratio (no crop, no letterbox). A panorama wider than the row at
    target height gets a row of its own at full width (lower than target). The final short row
    keeps target height instead of stretching (``last_row_max``)."""
    rows: list[list[tuple[int, float, float]]] = []
    cur: list[tuple[int, float]] = []

    def flush_partial():
        if not cur:
            return
        sum_ar = sum(a for _, a in cur)
        h = min(target_h * 1.25, (width - gap * (len(cur) - 1)) / sum_ar)
        rows.append([(j, a * h, h) for j, a in cur])
        cur.clear()

    for i, ar in enumerate(ratios):
        if ar * target_h >= width:
            flush_partial()
            rows.append([(i, width, width / ar)])
            continue
        cur.append((i, ar))
        sum_ar = sum(a for _, a in cur)
        if sum_ar * target_h + gap * (len(cur) - 1) >= width:
            h = (width - gap * (len(cur) - 1)) / sum_ar
            rows.append([(j, a * h, h) for j, a in cur])
            cur = []
    if cur:
        h = target_h if last_row_max is None else min(target_h, last_row_max)
        rows.append([(j, a * h, h) for j, a in cur])
    return rows


# ---------------------------------------------------------------------------
# Screen: one full 1080x2340 device frame
# ---------------------------------------------------------------------------
@dataclass
class Target:
    box: tuple[int, int, int, int]
    label: str
    show_size: bool = True


@dataclass
class Screen:
    fonts: Fonts
    theme: str = "light"
    font_scale: float = 1.0
    viewer: bool = False
    img: Image.Image = field(init=False)
    d: ImageDraw.ImageDraw = field(init=False)
    targets: list[Target] = field(default_factory=list)
    notes: list[tuple[tuple[int, int], str]] = field(default_factory=list)
    focus: list[tuple[tuple[int, int, int, int], str]] = field(default_factory=list)

    def __post_init__(self):
        self.t = TOKENS[self.theme]
        bg = VIEWER_GROUND if self.viewer else self.t["ground"]
        self.img = Image.new("RGB", (DEVICE_W, DEVICE_H), bg)
        self.d = ImageDraw.Draw(self.img, "RGBA")

    # --- geometry helpers
    @property
    def top(self) -> int:
        return px(STATUS_DP)

    @property
    def bottom(self) -> int:
        return DEVICE_H - px(NAV_DP)

    def font(self, role: str, scale_with_user: bool = True, weight: int | None = None):
        size, _line, face, w = ROLES[role]
        s = self.font_scale if scale_with_user else 1.0
        return self.fonts.get(face, weight or w, px(size) * s)

    def line_h(self, role: str) -> int:
        size, line, _f, _w = ROLES[role]
        return int(px(line) * self.font_scale)

    def text(self, xy, s, role="body", fill=None, anchor="la", weight=None, scale=True):
        f = self.font(role, scale, weight)
        self.d.text(xy, s, font=f, fill=fill or self.t["ink"], anchor=anchor)
        return self.d.textbbox(xy, s, font=f, anchor=anchor)

    def wrap(self, x, y, s, max_w, role="body", fill=None, max_lines=99):
        f = self.font(role)
        words, line, lines = s.split(), "", []
        for w in words:
            trial = (line + " " + w).strip()
            if self.d.textlength(trial, font=f) <= max_w or not line:
                line = trial
            else:
                lines.append(line)
                line = w
        if line:
            lines.append(line)
        if len(lines) > max_lines:
            lines = lines[:max_lines]
            while lines[-1] and self.d.textlength(lines[-1] + "…", font=f) > max_w:
                lines[-1] = lines[-1][:-1]
            lines[-1] += "…"
        lh = self.line_h(role)
        for i, ln in enumerate(lines):
            self.d.text((x, y + i * lh), ln, font=f, fill=fill or self.t["ink"])
        return y + len(lines) * lh

    def rrect(self, box, r_dp, fill=None, outline=None, width=0):
        self.d.rounded_rectangle(box, radius=px(r_dp), fill=fill, outline=outline, width=width)

    def target(self, box, label, actual=False, show_size=True):
        """Record a touch target. By default the effective area is padded to 48 dp around the
        visual (Compose minimumInteractiveComponentSize); ``actual=True`` records the box as is
        so an undersized target shows up on the board instead of being hidden."""
        x0, y0, x1, y1 = box
        if actual:
            self.targets.append(Target((int(x0), int(y0), int(x1), int(y1)), label, show_size))
            return
        cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
        w, h = max(x1 - x0, px(48)), max(y1 - y0, px(48))
        self.targets.append(Target((int(cx - w / 2), int(cy - h / 2), int(cx + w / 2), int(cy + h / 2)), label))

    def note(self, xy, s):
        self.notes.append((xy, s))

    def focus_order(self, box, label):
        self.focus.append((box, label))

    def photo(self, box, seed, radius_dp=0):
        x0, y0, x1, y1 = [int(round(v)) for v in box]
        w, h = max(1, x1 - x0), max(1, y1 - y0)
        ph = placeholder(w, h, seed)
        if radius_dp:
            m = Image.new("L", (w, h), 0)
            ImageDraw.Draw(m).rounded_rectangle([0, 0, w - 1, h - 1], radius=px(radius_dp), fill=255)
            self.img.paste(ph, (x0, y0), m)
        else:
            self.img.paste(ph, (x0, y0))

    # --- chrome
    def status_bar(self, light_icons=None):
        col = self.t["ink"] if not self.viewer else "#FFFFFF"
        if light_icons is not None:
            col = "#FFFFFF" if light_icons else "#0E1030"
        y = self.top // 2
        self.text((px(20), y), "12:30", "secondary", fill=col, anchor="lm", weight=600, scale=False)
        x = DEVICE_W - px(20)
        self.d.rounded_rectangle([x - px(22), y - px(6), x, y + px(6)], radius=px(2), outline=col, width=px(1.3))
        self.d.rectangle([x - px(20), y - px(4), x - px(6), y + px(4)], fill=col)
        for i in range(4):
            bx = x - px(34) - i * px(5)
            self.d.rectangle([bx, y + px(6) - px(3 + 2 * (3 - i)), bx + px(3), y + px(6)], fill=col)

    def gesture_bar(self, light=None):
        col = self.t["ink"] if not self.viewer else "#FFFFFF"
        if light is not None:
            col = "#FFFFFF" if light else "#0E1030"
        cy = DEVICE_H - px(NAV_DP) // 2
        self.d.rounded_rectangle([DEVICE_W / 2 - px(54), cy - px(2), DEVICE_W / 2 + px(54), cy + px(2)],
                                 radius=px(2), fill=col)

    def sphere(self, center, r_px, fill=None, halo_dp=0, halo="#FFFFFF", label=None, label_role="nav",
               label_fill=None):
        cx, cy = center
        if halo_dp:
            hr = r_px + px(halo_dp)
            self.d.ellipse([cx - hr, cy - hr, cx + hr, cy + hr], fill=halo)
        self.d.ellipse([cx - r_px, cy - r_px, cx + r_px, cy + r_px], fill=fill or self.t["brand_fill"])
        if label:
            self.text((cx, cy), label, label_role, fill=label_fill or self.t["on_brand"], anchor="mm", scale=False)

    def slab(self, active="Pictures", y_bottom=None, search=True, places=("Pictures", "Collections")):
        """Short centred lapis slab: two places + search sphere. Returns the slab box.

        Pills grow with the label at the user's font scale. When pills + sphere no longer fit
        the 384 dp width (around 175-200 % font), the search sphere detaches and floats above
        the slab's right end (a proposal the board flags for review)."""
        f = self.fonts.get("sans", 600, px(14) * self.font_scale)
        pill_h = max(px(48), int(px(20) * self.font_scale) + px(16))
        widths = [max(px(104), int(self.d.textlength(n, font=f)) + px(28)) for n in places]
        inner = sum(widths) + px(4) * (len(widths) - 1) + px(8) * 2
        sphere_w = px(48) + px(8) if search else 0
        detached = search and inner + sphere_w > DEVICE_W - px(16)
        sw = inner + (0 if detached or not search else sphere_w)
        sh = pill_h + px(16)
        y1 = (y_bottom or self.bottom) - px(12)
        y0 = y1 - sh
        x0 = (DEVICE_W - sw) // 2
        x1 = x0 + sw
        self.rrect([x0, y0, x1, y1], 24, fill=self.t["brand_fill"])
        cy = (y0 + y1) // 2
        bx0 = x0 + px(8)
        on_pill = "#2639C9"
        for name, w in zip(places, widths):
            box = [bx0, cy - pill_h // 2, bx0 + w, cy + pill_h // 2]
            if name == active:
                self.rrect(box, 24, fill="#FFFFFF")
                col = on_pill
            else:
                col = self.t["on_brand"]
            self.d.text(((box[0] + box[2]) / 2, cy), name, font=f, fill=col, anchor="mm")
            self.target(box, name)
            bx0 += w + px(4)
        if search:
            if detached:
                scx, scy = x1 - px(28), y0 - px(12) - px(28)
                self.d.ellipse([scx - px(28), scy - px(28), scx + px(28), scy + px(28)], fill=self.t["brand_fill"])
                self.magnifier((scx, scy), px(10), "#FFFFFF")
                self.target([scx - px(28), scy - px(28), scx + px(28), scy + px(28)], "Search")
                self.note((DEVICE_W - px(8), scy + px(32)), "search sphere detaches above slab when pills fill the width")
            else:
                scx, scy = x1 - px(8) - px(24), cy
                self.d.ellipse([scx - px(24), scy - px(24), scx + px(24), scy + px(24)], fill="#FFFFFF")
                self.magnifier((scx, scy), px(9), on_pill)
                self.target([scx - px(24), scy - px(24), scx + px(24), scy + px(24)], "Search")
        self.slab_box = (x0, y0, x1, y1)
        self.slab_detached = bool(detached)
        return (x0, y0, x1, y1)

    def magnifier(self, c, r, col):
        cx, cy = c
        self.d.ellipse([cx - r - px(2), cy - r - px(2), cx + r - px(2), cy + r - px(2)], outline=col, width=px(2.4))
        self.d.line([cx + r * 0.55, cy + r * 0.55, cx + r * 1.25, cy + r * 1.25], fill=col, width=px(2.6))

    def keyboard(self, hint_keys=None):
        """Synthetic IME panel above the nav inset. Returns its top y."""
        kb_top = self.bottom - px(IME_DP)
        bg = "#E3E5EC" if self.theme == "light" else "#1D1F33"
        key = "#FFFFFF" if self.theme == "light" else "#34364F"
        ink = "#0E1030" if self.theme == "light" else "#F2F3F8"
        self.d.rectangle([0, kb_top, DEVICE_W, DEVICE_H], fill=bg)
        rows = ["qwertyuiop", "asdfghjkl", "zxcvbnm"]
        kh, gap = px(46), px(6)
        y = kb_top + px(48)
        # suggestion strip
        sugg = hint_keys or ["photos", "places", "people"]
        for i, s in enumerate(sugg):
            self.d.text((DEVICE_W * (i + 0.5) / 3, kb_top + px(24)), s, font=self.fonts.get("sans", 400, px(14)),
                        fill=ink, anchor="mm")
        for ri, row in enumerate(rows):
            n = len(row)
            kw = (DEVICE_W - px(8) * 2 - gap * 9) / 10
            start = (DEVICE_W - (n * kw + (n - 1) * gap)) / 2
            for i, ch in enumerate(row):
                x = start + i * (kw + gap)
                self.d.rounded_rectangle([x, y, x + kw, y + kh], radius=px(6), fill=key)
                self.d.text((x + kw / 2, y + kh / 2), ch, font=self.fonts.get("sans", 400, px(18)), fill=ink,
                            anchor="mm")
            y += kh + gap
        self.d.rounded_rectangle([DEVICE_W * 0.25, y, DEVICE_W * 0.75, y + kh], radius=px(6), fill=key)
        self.d.rounded_rectangle([DEVICE_W * 0.78, y, DEVICE_W - px(8), y + kh], radius=px(6),
                                 fill=self.t["brand_fill"])
        self.magnifier((DEVICE_W * 0.89, y + kh / 2), px(7), "#FFFFFF")
        self.note((DEVICE_W - px(8), kb_top + px(4)), f"IME inset {IME_DP} dp (synthetic keyboard)")
        return kb_top

    def overlay_annotations(self, show_targets=True, show_insets=True):
        """Draw the annotation layer (magenta, outside the palette) onto the frame."""
        d = self.d
        f = self.fonts.get("sans", 600, px(9.5))
        if show_insets:
            d.rectangle([0, 0, DEVICE_W, self.top], fill=(224, 0, 122, 26))
            d.rectangle([0, self.bottom, DEVICE_W, DEVICE_H], fill=(224, 0, 122, 26))
            d.text((DEVICE_W / 2, self.top / 2), f"status inset {STATUS_DP} dp", font=f, fill=ANNO, anchor="mm")
            d.text((px(6), self.bottom + px(2)), f"gesture inset {NAV_DP} dp", font=f, fill=ANNO, anchor="la")
        if show_targets:
            for t in self.targets:
                x0, y0, x1, y1 = t.box
                dashed_rect(d, t.box, ANNO, px(1.5), px(5))
                wdp, hdp = round((x1 - x0) / DENSITY), round((y1 - y0) / DENSITY)
                if not t.show_size:
                    continue
                flag = "  BELOW 48 dp" if min(wdp, hdp) < 48 else ""
                d.text((x0 + px(2), y0 - px(1)), f"{wdp}x{hdp} dp{flag}", font=f, fill=ANNO, anchor="ld")
        for i, (box, label) in enumerate(self.focus, 1):
            x0, y0 = box[0], box[1]
            r = px(10)
            d.ellipse([x0 - r, y0 - r, x0 + r, y0 + r], fill=ANNO)
            d.text((x0, y0), str(i), font=f, fill="#FFFFFF", anchor="mm")
        for (x, y), s in self.notes:
            d.text((x, y), s, font=f, fill=ANNO, anchor="ra" if x > DEVICE_W / 2 else "la")


def dashed_rect(d, box, col, width, dash):
    x0, y0, x1, y1 = box
    for (ax, ay, bx, by) in ((x0, y0, x1, y0), (x1, y0, x1, y1), (x1, y1, x0, y1), (x0, y1, x0, y0)):
        length = math.hypot(bx - ax, by - ay)
        n = max(1, int(length // (dash * 2)))
        for i in range(n + 1):
            s = i * dash * 2 / length
            e = min(1.0, (i * dash * 2 + dash) / length)
            if s >= 1:
                break
            d.line([ax + (bx - ax) * s, ay + (by - ay) * s, ax + (bx - ax) * e, ay + (by - ay) * e],
                   fill=col, width=width)


def assert_device_frame(img: Image.Image, label: str) -> None:
    if img.size != (DEVICE_W, DEVICE_H):
        raise AssertionError(f"{label}: device frame is {img.size}, expected {(DEVICE_W, DEVICE_H)}")
