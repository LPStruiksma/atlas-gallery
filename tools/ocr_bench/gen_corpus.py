#!/usr/bin/env python3
"""Deterministic synthetic EN/NL OCR corpus (OCR-0A, #873).

Everything is generated from the word pools below (written for this project, no third-party
text) with Pillow and system fonts; the images are NOT committed, only this recipe and the
manifest it emits. Same seed + same Pillow/font files => same manifest (text, strata, split).
Pixel hashes are recorded for reproducibility checks but depend on the Pillow build and fonts.

    python3 -m tools.ocr_bench.gen_corpus OUT_DIR [--seed 873] [--per-cell 30]
"""

from __future__ import annotations

import argparse
import hashlib
import json
import random
import unicodedata
from pathlib import Path

KINDS = ("screenshot", "receipt", "sign")
LANGUAGES = ("en", "nl")
RECIPE = "ocr0a-synthetic-v1"
LICENCE = ("generated: tools/ocr_bench/gen_corpus.py recipe ocr0a-synthetic-v1; "
           "original text, system fonts rendered by Pillow; no third-party pixels")

FONTS = {
    "sans": "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
    "sans_bold": "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
    "lib": "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf",
    "lib_bold": "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf",
    "mono": "/usr/share/fonts/truetype/liberation/LiberationMono-Regular.ttf",
}

# Dutch pool deliberately carries diacritics (ë, é, ï, ö, ü, ç) and the ij digraph.
WORDS = {
    "en": {
        "sign": ["Exit", "Staff only", "No parking", "Wet floor", "Open daily", "Fresh bread",
                 "Please wait here", "Danger high voltage", "Keep clear", "Visitor entrance",
                 "Bicycle repair", "Coffee and cake", "Closed on Sunday", "Platform two",
                 "Fire door keep shut", "Ticket office"],
        "ui": ["Settings", "Notifications", "Battery saver is on", "Storage almost full",
               "Update available", "Back up your photos", "Connected to home network",
               "Do not disturb until tomorrow", "Choose a language", "Delete selected items",
               "Sync paused while charging", "Privacy dashboard", "Download complete",
               "Enter your password", "Recent searches", "Share link copied"],
        "items": ["Milk 1L", "Whole wheat bread", "Free range eggs", "Apple juice", "Coffee beans",
                  "Tomatoes", "Cheddar cheese", "Olive oil", "Tea bags", "Sparkling water",
                  "Chocolate bar", "Pasta penne", "Orange squash", "Butter 250g", "Rice 1kg"],
        "shops": ["Corner Market", "Green Grocer", "City Pharmacy", "River Cafe", "Book Barn"],
        "total": "Total", "thanks": "Thank you for shopping",
    },
    "nl": {
        "sign": ["Uitgang", "Alleen personeel", "Verboden te parkeren", "Natte vloer",
                 "Dagelijks geopend", "Vers brood", "Even geduld alstublieft", "Levensgevaar",
                 "Vrijhouden", "Ingang bezoekers", "Fietsenmaker", "Koffie en gebak",
                 "Zondag gesloten", "Perron twee", "Branddeur gesloten houden", "Kaartverkoop",
                 "België en coördinatie", "Café geopend", "Één uur wachten", "Reïntegratie"],
        "ui": ["Instellingen", "Meldingen", "Batterijbesparing staat aan", "Opslag bijna vol",
               "Update beschikbaar", "Maak een back-up van je foto's", "Verbonden met thuisnetwerk",
               "Niet storen tot morgen", "Kies een taal", "Geselecteerde items verwijderen",
               "Synchronisatie gepauzeerd tijdens opladen", "Privacyoverzicht",
               "Download voltooid", "Voer je wachtwoord in", "Recente zoekopdrachten",
               "Link gekopieerd", "Naïeve instellingen", "Financiële gegevens"],
        "items": ["Melk 1L", "Volkoren brood", "Scharreleieren", "Appelsap", "Koffiebonen",
                  "Tomaten", "Oude kaas", "Olijfolie", "Theezakjes", "Bruisend water",
                  "Chocoladereep", "Pasta penne", "Sinaasappelsap", "Boter 250g", "Rijst 1kg",
                  "Crème fraîche", "Patiënt koekjes", "Één blik tomaten"],
        "shops": ["Hoekwinkel", "Groenteboer", "Stadsapotheek", "Café Rivier", "Boekenschuur"],
        "total": "Totaal", "thanks": "Bedankt voor uw bezoek",
    },
}


def _font(name: str, size: int):
    from PIL import ImageFont
    return ImageFont.truetype(FONTS[name], size)


def _fit_font(name: str, size: int, lines: list[str], max_width: int):
    """Largest font <= size whose widest line fits max_width, so no ground-truth text is clipped.

    Returns (font, widest_line_px). Raises instead of silently clipping when even 14 px is too wide.
    """
    from PIL import Image, ImageDraw
    probe = ImageDraw.Draw(Image.new("RGB", (1, 1)))
    while size >= 14:
        font = _font(name, size)
        widest = max(probe.textlength(line, font=font) for line in lines)
        if widest <= max_width:
            return font, int(widest)
        size -= 2
    raise ValueError(f"cannot fit {lines!r} in {max_width}px")


def _bg(rng: random.Random, dark: bool, low_contrast: bool):
    if dark:
        base = rng.randint(16, 40)
        fg = rng.randint(200, 245)
    else:
        base = rng.randint(225, 255)
        fg = rng.randint(10, 60)
    if low_contrast:
        # Pull the foreground towards the background: contrast of roughly 3.0:1 or lower.
        fg = int(base + (fg - base) * 0.38)
    return (base, base, min(255, base + rng.randint(0, 8))), (fg, fg, fg)


def _postprocess(img, rng: random.Random, blur: bool):
    from PIL import Image, ImageFilter
    if rng.random() < 0.3:
        img = img.rotate(rng.uniform(-2.5, 2.5), resample=Image.BICUBIC,
                         fillcolor=img.getpixel((0, 0)))
    if blur:
        img = img.filter(ImageFilter.GaussianBlur(rng.uniform(0.4, 0.9)))
    return img


def _screenshot(rng: random.Random, lang: str, low: bool):
    from PIL import Image, ImageDraw
    lines = rng.sample(WORDS[lang]["ui"], rng.randint(3, 5))
    dark = rng.random() < 0.5
    bg, fg = _bg(rng, dark, low)
    size = rng.choice([20, 22, 24, 26])
    font, widest = _fit_font(rng.choice(["sans", "lib"]), size, lines, 720 - 44 - 44)
    size = font.size
    width, height = 720, 160 + len(lines) * (size * 3)
    img = Image.new("RGB", (width, height), bg)
    draw = ImageDraw.Draw(img)
    bar = tuple(max(0, min(255, c + (14 if dark else -14))) for c in bg)
    draw.rectangle([0, 0, width, 56], fill=bar)
    y = 90
    for line in lines:
        draw.rounded_rectangle([24, y - 12, width - 24, y + size + 14], radius=10, outline=bar, width=2)
        draw.text((44, y), line, font=font, fill=fg)
        y += size * 3
    return _postprocess(img, rng, False), lines, (widest, 720 - 44 - 44)


def _receipt(rng: random.Random, lang: str, low: bool):
    from PIL import Image, ImageDraw
    pool = WORDS[lang]
    lines = [rng.choice(pool["shops"])]
    total = 0
    for item in rng.sample(pool["items"], rng.randint(4, 6)):
        cents = rng.randint(49, 899)
        total += cents
        sep = "," if lang == "nl" else "."
        lines.append(f"{item}  {cents // 100}{sep}{cents % 100:02d}")
    sep = "," if lang == "nl" else "."
    lines.append(f"{pool['total']}  {total // 100}{sep}{total % 100:02d}")
    lines.append(pool["thanks"])
    bg, fg = _bg(rng, False, low)
    font, widest = _fit_font(rng.choice(["mono", "lib"]), rng.choice([20, 22, 24]), lines, 460 - 24 - 24)
    step = 40
    img = Image.new("RGB", (460, 80 + len(lines) * step), bg)
    draw = ImageDraw.Draw(img)
    for i, line in enumerate(lines):
        draw.text((24, 40 + i * step), line, font=font, fill=fg)
    return _postprocess(img, rng, True), lines, (widest, 460 - 24 - 24)


def _sign(rng: random.Random, lang: str, low: bool):
    from PIL import Image, ImageDraw
    lines = rng.sample(WORDS[lang]["sign"], rng.randint(1, 2))
    palette = [((200, 30, 30), (255, 255, 255)), ((20, 70, 150), (255, 255, 255)),
               ((250, 210, 40), (20, 20, 20)), ((30, 110, 60), (245, 245, 245)),
               ((235, 235, 235), (30, 30, 30))]
    bg, fg = rng.choice(palette)
    if low:
        fg = tuple(int(b + (f - b) * 0.4) for b, f in zip(bg, fg))
    font, widest = _fit_font(rng.choice(["sans_bold", "lib_bold"]), rng.choice([52, 60, 68]), lines, 900 - 2 * 60)
    img = Image.new("RGB", (900, 120 + len(lines) * 110), bg)
    draw = ImageDraw.Draw(img)
    draw.rectangle([14, 14, 885, img.height - 15], outline=fg, width=6)
    for i, line in enumerate(lines):
        box = draw.textbbox((0, 0), line, font=font)
        draw.text(((900 - (box[2] - box[0])) // 2, 70 + i * 110), line, font=font, fill=fg)
    return _postprocess(img, rng, rng.random() < 0.5), lines, (widest, 900 - 2 * 60)


def _no_text(rng: random.Random):
    """Visually varied negatives: gradients, shapes, stripes, grids and noise; no glyphs."""
    from PIL import Image, ImageDraw, ImageFilter
    width, height = rng.choice([(640, 480), (720, 960), (900, 600)])
    img = Image.new("RGB", (width, height), tuple(rng.randint(0, 255) for _ in range(3)))
    draw = ImageDraw.Draw(img)
    style = rng.choice(["gradient", "shapes", "stripes", "grid", "noise", "fine_lines"])
    if style == "gradient":
        c1, c2 = [tuple(rng.randint(0, 255) for _ in range(3)) for _ in range(2)]
        for y in range(height):
            t = y / (height - 1)
            draw.line([0, y, width, y], fill=tuple(int(a + (b - a) * t) for a, b in zip(c1, c2)))
    elif style == "shapes":
        for _ in range(rng.randint(6, 14)):
            x0, y0 = rng.randint(0, width - 60), rng.randint(0, height - 60)
            box = [x0, y0, x0 + rng.randint(30, 220), y0 + rng.randint(30, 220)]
            color = tuple(rng.randint(0, 255) for _ in range(3))
            (draw.ellipse if rng.random() < 0.5 else draw.rectangle)(box, fill=color)
    elif style == "stripes":
        gap = rng.randint(12, 40)
        for x in range(0, width, gap * 2):
            draw.rectangle([x, 0, x + gap, height], fill=tuple(rng.randint(0, 255) for _ in range(3)))
    elif style == "grid":
        gap, color = rng.randint(24, 64), tuple(rng.randint(0, 255) for _ in range(3))
        for x in range(0, width, gap):
            draw.line([x, 0, x, height], fill=color, width=2)
        for y in range(0, height, gap):
            draw.line([0, y, width, y], fill=color, width=2)
    elif style == "noise":
        px = img.load()
        for y in range(0, height):
            for x in range(0, width, 1):
                v = rng.randint(0, 255)
                px[x, y] = (v, v, v)
        img = img.filter(ImageFilter.GaussianBlur(rng.uniform(0.5, 3)))
    else:  # fine_lines: text-like horizontal texture (hard negative), still no glyphs
        color = tuple(rng.randint(0, 120) for _ in range(3))
        for row in range(30, height - 30, 22):
            x = 30
            while x < width - 40:
                run = rng.randint(14, 90)
                draw.rectangle([x, row, x + run, row + 5], fill=color)
                x += run + rng.randint(8, 18)
    return img, style


BUILDERS = {"screenshot": _screenshot, "receipt": _receipt, "sign": _sign}


def _split(index: int) -> str:
    """Fixed 1:2 dev/test interleave inside every stratum: indices 0,1,2 -> dev,test,test."""
    return "dev" if index % 3 == 0 else "test"


def generate(out_dir: Path, seed: int = 873, per_cell: int = 30,
             negatives: int = 60, write_images: bool = True) -> list[dict]:
    """Write images (optional) and return manifest rows in a stable order."""
    (out_dir / "images").mkdir(parents=True, exist_ok=True) if write_images else None
    rows: list[dict] = []

    def emit(row_id: str, img, **fields) -> None:
        path = f"images/{row_id}.png"
        row = {"id": row_id, "path": path, **fields, "licence": LICENCE, "recipe": RECIPE, "seed": seed}
        if write_images:
            img.save(out_dir / path, format="PNG")
            row["image_sha256"] = hashlib.sha256((out_dir / path).read_bytes()).hexdigest()
            row["width"], row["height"] = img.size
        rows.append(row)

    for lang in LANGUAGES:
        for kind in KINDS:
            for index in range(per_cell):
                rng = random.Random(f"{seed}:{lang}:{kind}:{index}")
                low = index % 5 == 4  # every fifth image is a low-contrast hard case
                img, lines, (widest, limit) = BUILDERS[kind](rng, lang, low)
                text = unicodedata.normalize("NFC", "\n".join(lines))
                emit(f"{lang}-{kind}-{index:03d}", img, kind=kind, language=lang, text=text,
                     split=_split(index), low_contrast=low, text_px=widest, text_limit_px=limit)
    for index in range(negatives):
        rng = random.Random(f"{seed}:neg:{index}")
        img, style = _no_text(rng)
        emit(f"none-{index:03d}", img, kind="no_text", text="", split=_split(index), style=style)
    return rows


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("out_dir", type=Path)
    parser.add_argument("--seed", type=int, default=873)
    parser.add_argument("--per-cell", type=int, default=30)
    parser.add_argument("--negatives", type=int, default=60)
    args = parser.parse_args()
    rows = generate(args.out_dir, args.seed, args.per_cell, args.negatives)
    manifest = args.out_dir / "manifest.jsonl"
    manifest.write_text("".join(json.dumps(r, ensure_ascii=False, sort_keys=True) + "\n" for r in rows),
                        encoding="utf-8")
    print(f"wrote {len(rows)} rows to {manifest}")


if __name__ == "__main__":
    main()
