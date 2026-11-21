"""Compose labelled device frames, tables and notes into one PNG sheet."""

from __future__ import annotations

from dataclasses import dataclass, field

from PIL import Image, ImageDraw

from lapis_kit import (
    ANNO,
    BOARD_BG,
    BOARD_INK,
    BOARD_INK2,
    DEVICE_H,
    DEVICE_W,
    Fonts,
    Screen,
    assert_device_frame,
)

BOARD_W = 3480
MARGIN = 60
GUTTER = 40


@dataclass
class Panel:
    code: str
    title: str
    caption: str
    screen: Screen
    annotate: bool = True


@dataclass
class Board:
    fonts: Fonts
    name: str
    sections: list[Image.Image] = field(default_factory=list)
    frames_checked: int = 0
    panel_codes: list[str] = field(default_factory=list)

    # -- text helpers on board images
    def f(self, weight: int, size: int, face: str = "sans"):
        return self.fonts.get(face, weight, size)

    def _wrap(self, d, x, y, s, max_w, font, fill, lh):
        for para in s.split("\n"):
            words, line = [], ""
            for w in para.split():
                # break over-long tokens (URLs) so they never overflow a column
                while d.textlength(w, font=font) > max_w and len(w) > 1:
                    k = len(w)
                    while k > 1 and d.textlength(w[:k], font=font) > max_w:
                        k -= 1
                    words.append(w[:k])
                    w = w[k:]
                words.append(w)
            for w in words:
                trial = (line + " " + w).strip()
                if d.textlength(trial, font=font) <= max_w or not line:
                    line = trial
                else:
                    d.text((x, y), line, font=font, fill=fill)
                    y += lh
                    line = w
            if line:
                d.text((x, y), line, font=font, fill=fill)
                y += lh
        return y

    def _measure_wrap(self, s, max_w, font, lh):
        img = Image.new("RGB", (10, 10))
        d = ImageDraw.Draw(img)
        return self._wrap(d, 0, 0, s, max_w, font, "#000", lh)

    # -- sections
    def header(self, title: str, subtitle: str, facts: list[str]):
        h = 120 + 70 + 44 * (len(facts) + 1) + 40
        img = Image.new("RGB", (BOARD_W, h), BOARD_BG)
        d = ImageDraw.Draw(img)
        d.text((MARGIN, 50), title, font=self.f(800, 84, "display"), fill="#2639C9")
        d.text((MARGIN, 160), subtitle, font=self.f(600, 36), fill=BOARD_INK)
        y = 220
        for line in facts:
            d.text((MARGIN, y), line, font=self.f(400, 30), fill=BOARD_INK2)
            y += 44
        self.sections.append(img.crop((0, 0, BOARD_W, y + 30)))

    def _frame(self, p: Panel) -> Image.Image:
        assert_device_frame(p.screen.img, f"{self.name} {p.code}")
        self.frames_checked += 1
        self.panel_codes.append(p.code)
        if p.annotate:
            p.screen.overlay_annotations()
        return p.screen.img

    def heroes(self, panels: list[Panel], legend: list[str]):
        """Full-scale 1080x2340 frames side by side with a legend column."""
        cap_font = self.f(700, 40)
        cap2 = self.f(400, 28)
        col_w = DEVICE_W
        top = 150
        legend_x = MARGIN + len(panels) * (col_w + GUTTER)
        legend_w = BOARD_W - legend_x - MARGIN
        img = Image.new("RGB", (BOARD_W, top + DEVICE_H + 60), BOARD_BG)
        d = ImageDraw.Draw(img)
        for i, p in enumerate(panels):
            x = MARGIN + i * (col_w + GUTTER)
            d.text((x, 20), f"{p.code}  {p.title}", font=cap_font, fill=BOARD_INK)
            self._wrap(d, x, 74, p.caption, col_w, cap2, BOARD_INK2, 36)
            fr = self._frame(p)
            img.paste(fr, (x, top))
            d.rectangle([x - 2, top - 2, x + DEVICE_W + 1, top + DEVICE_H + 1], outline="#B9BCCB", width=2)
        if legend_w > 300:
            d.text((legend_x, 20), "How to read this sheet", font=cap_font, fill=BOARD_INK)
            y = 90
            for item in legend:
                y = self._wrap(d, legend_x, y, item, legend_w, self.f(400, 28), BOARD_INK, 38) + 18
        self.sections.append(img)

    def grid(self, title: str, panels: list[Panel], cols: int = 6, scale: float = 0.5):
        fw, fh = int(DEVICE_W * scale), int(DEVICE_H * scale)
        gap = (BOARD_W - 2 * MARGIN - cols * fw) // max(1, cols - 1)
        cap_font = self.f(700, 30)
        cap2 = self.f(400, 23)
        rows = [panels[i:i + cols] for i in range(0, len(panels), cols)]
        heights = []
        for row in rows:
            ch = max(self._measure_wrap(p.caption, fw, cap2, 29) for p in row)
            heights.append(50 + ch + 16 + fh + 50)
        img = Image.new("RGB", (BOARD_W, 90 + sum(heights)), BOARD_BG)
        d = ImageDraw.Draw(img)
        d.text((MARGIN, 24), title, font=self.f(700, 46), fill=BOARD_INK)
        y = 90
        for row, rh in zip(rows, heights):
            ch = max(self._measure_wrap(p.caption, fw, cap2, 29) for p in row)
            for i, p in enumerate(row):
                x = MARGIN + i * (fw + gap)
                d.text((x, y), f"{p.code}  {p.title}", font=cap_font, fill=BOARD_INK)
                self._wrap(d, x, y + 44, p.caption, fw, cap2, BOARD_INK2, 29)
                fr = self._frame(p).resize((fw, fh), Image.LANCZOS)
                fy = y + 50 + ch + 16
                img.paste(fr, (x, fy))
                d.rectangle([x - 1, fy - 1, x + fw, fy + fh], outline="#B9BCCB", width=2)
            y += rh
        self.sections.append(img)

    def table(self, title: str, header: list[str], rows: list[list[str]], widths: list[float], note: str = ""):
        total = BOARD_W - 2 * MARGIN
        ws = [int(total * w) for w in widths]
        font = self.f(400, 26)
        hfont = self.f(700, 28)
        lh = 34
        heights = [max(self._measure_wrap(c, w - 28, font, lh) for c, w in zip(r, ws)) + 28 for r in rows]
        note_h = self._measure_wrap(note, total, self.f(400, 26), 34) + 20 if note else 0
        h = 90 + 60 + sum(heights) + note_h + 40
        img = Image.new("RGB", (BOARD_W, h), BOARD_BG)
        d = ImageDraw.Draw(img)
        d.text((MARGIN, 24), title, font=self.f(700, 46), fill=BOARD_INK)
        y = 90
        x = MARGIN
        d.rectangle([MARGIN, y, MARGIN + total, y + 60], fill="#D8DAE6")
        for c, w in zip(header, ws):
            d.text((x + 14, y + 14), c, font=hfont, fill=BOARD_INK)
            x += w
        y += 60
        for r, rh in zip(rows, heights):
            x = MARGIN
            d.rectangle([MARGIN, y, MARGIN + total, y + rh], fill="#F6F7FB", outline="#C9CCD9")
            for c, w in zip(r, ws):
                self._wrap(d, x + 14, y + 14, c, w - 28, font, BOARD_INK, lh)
                x += w
            y += rh
        if note:
            self._wrap(d, MARGIN, y + 16, note, total, self.f(400, 26), BOARD_INK2, 34)
        self.sections.append(img)

    def notes(self, title: str, items: list[str]):
        font = self.f(400, 28)
        total = BOARD_W - 2 * MARGIN
        hs = [self._measure_wrap("•  " + it, total, font, 38) for it in items]
        img = Image.new("RGB", (BOARD_W, 100 + sum(hs) + 14 * len(items) + 30), BOARD_BG)
        d = ImageDraw.Draw(img)
        d.text((MARGIN, 24), title, font=self.f(700, 46), fill=BOARD_INK)
        y = 96
        for it in items:
            y = self._wrap(d, MARGIN, y, "•  " + it, total, font, BOARD_INK, 38) + 14
        self.sections.append(img)

    def footer(self, text: str):
        img = Image.new("RGB", (BOARD_W, 110), BOARD_BG)
        d = ImageDraw.Draw(img)
        d.line([MARGIN, 20, BOARD_W - MARGIN, 20], fill="#B9BCCB", width=2)
        d.text((MARGIN, 44), text, font=self.f(400, 26), fill=ANNO)
        self.sections.append(img)

    def render(self) -> Image.Image:
        h = sum(s.height for s in self.sections)
        out = Image.new("RGB", (BOARD_W, h), BOARD_BG)
        y = 0
        for s in self.sections:
            out.paste(s, (0, y))
            y += s.height
        return out
