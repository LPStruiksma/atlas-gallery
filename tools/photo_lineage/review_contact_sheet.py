"""Opt-in, private, offline *visual* review of bounded Photo DNA pair evidence.

Never mutates originals, declares photographic families, or makes deletion decisions.
The output contains embedded reduced images: treat it as PRIVATE photo data.
"""
from __future__ import annotations

import argparse
import base64
from io import BytesIO
import html
import os
from pathlib import Path
import stat

from PIL import Image, ImageOps, UnidentifiedImageError

from .review_queue import _read_json, normalize, write_new

MAX_EDGES = 32
MAX_PHOTOS = 64
MAX_FILE_BYTES = 100 * 1024 * 1024
MAX_PIXELS = 95_000_000
MAX_JPEG_BYTES = 65_536
MAX_REPORT_BYTES = 9 * 1024 * 1024
PREVIEW_SIDE = 320


def _open_regular_nofollow(path: Path):
    """Read through descriptors anchored at /, refusing every symlink component."""
    if not getattr(os, 'O_NOFOLLOW', 0) or not getattr(os, 'O_DIRECTORY', 0):
        raise ValueError('Secure read unavailable on this platform')
    absolute = path.absolute()
    if '..' in absolute.parts or len(absolute.parts) < 2:
        raise ValueError('Unsafe image path')
    flags = os.O_RDONLY | os.O_NOFOLLOW | getattr(os, 'O_CLOEXEC', 0)
    directory = os.open(absolute.anchor, flags | os.O_DIRECTORY)
    try:
        for component in absolute.parts[1:-1]:
            child = os.open(component, flags | os.O_DIRECTORY, dir_fd=directory)
            os.close(directory)
            directory = child
        fd = os.open(absolute.name, flags, dir_fd=directory)
        try:
            before = os.fstat(fd)
            if not stat.S_ISREG(before.st_mode) or before.st_size > MAX_FILE_BYTES:
                raise ValueError('Not a bounded regular image file')
        except BaseException:
            os.close(fd)
            raise
        return fd
    finally:
        os.close(directory)


def _preview(path: str) -> bytes:
    """Return a metadata-stripped and bounded JPEG; never use path in errors."""
    fd = _open_regular_nofollow(Path(path))
    with os.fdopen(fd, 'rb') as stream:
        before = os.fstat(stream.fileno())
        try:
            with Image.open(stream) as source:
                width, height = source.size
                if width < 1 or height < 1 or width * height > MAX_PIXELS:
                    raise ValueError('Unsafe source dimensions')
                # Pillow can subsample JPEG during draft/thumbnail before full decode.
                source.draft('RGB', (PREVIEW_SIDE * 2, PREVIEW_SIDE * 2))
                source.thumbnail((PREVIEW_SIDE, PREVIEW_SIDE), Image.Resampling.LANCZOS,
                                 reducing_gap=2.0)
                image = ImageOps.exif_transpose(source).convert('RGB')
                image.thumbnail((PREVIEW_SIDE, PREVIEW_SIDE), Image.Resampling.LANCZOS)
                jpeg = BytesIO()
                image.save(jpeg, format='JPEG', quality=78, optimize=True)
                value = jpeg.getvalue()
        except (OSError, UnidentifiedImageError, Image.DecompressionBombError) as exc:
            raise ValueError('Image decode unavailable') from exc
        after = os.fstat(stream.fileno())
        identity = lambda st: (st.st_dev, st.st_ino, st.st_size, st.st_mtime_ns, st.st_ctime_ns)
        if identity(before) != identity(after):
            raise ValueError('Image changed during preview')
        if len(value) > MAX_JPEG_BYTES:
            raise ValueError('Preview exceeds output budget')
        return value


def render_contact_sheet(payload: object, *, show_names: bool = False,
                         max_edges: int = MAX_EDGES) -> tuple[str, dict]:
    """Build no-network HTML, with explicit missing thumbnails and no dedup claims."""
    if type(show_names) is not bool or type(max_edges) is not int or not 1 <= max_edges <= MAX_EDGES:
        raise ValueError('Invalid contact-sheet options')
    edges, stats = normalize(payload, limit=max_edges)
    if not edges:
        raise ValueError('No explicit candidate/review pairs to display')
    unique = {path: token for edge in edges for path, token in
              ((edge['a'], edge['a_token']), (edge['b'], edge['b_token']))}
    if len(unique) > MAX_PHOTOS:
        raise ValueError('Too many unique photo sources')

    previews: dict[str, str | None] = {}
    for path in unique:
        try:
            raw = _preview(path)
            previews[path] = base64.b64encode(raw).decode('ascii')
        except (ValueError, OSError):
            previews[path] = None
    present = sum(value is not None for value in previews.values())
    if present == 0:
        raise ValueError('No photo previews readable; no report was written')

    esc = lambda value: html.escape(str(value), quote=True)
    cards = []
    for index, edge in enumerate(edges, 1):
        items = []
        for path, token in ((edge['a'], edge['a_token']), (edge['b'], edge['b_token'])):
            title = f'{token} — {path}' if show_names else token
            value = previews[path]
            picture = (f'<img alt="Preview for {esc(token)}" loading="lazy" '
                       f'src="data:image/jpeg;base64,{value}">') if value else (
                       '<p class="unavailable">Preview unavailable — check source locally.</p>')
            items.append(f'<figure>{picture}<figcaption>{esc(title)}</figcaption></figure>')
        evidence = ', '.join(f'{esc(key.replace("_", " "))}: {value:.4g}'
                             for key, value in edge['numbers'].items())
        cards.append(f'<section class="pair"><h2>Pair {index} · {esc(edge["verdict"].upper())}'
                     f' · {esc(edge["a_token"])} ↔ {esc(edge["b_token"])}</h2>'
                     f'<div class="images">{"".join(items)}</div>'
                     f'<p><b>Geometric evidence:</b> {esc(edge["algorithm"])} · '
                     f'{esc(edge["reason"])}.</p>'
                     f'<p class="muted">{evidence or "No numeric measurements"}</p>'
                     '<p><b>Human review:</b> same exposure, independent capture, or unsure? '
                     'No decision is saved by this report.</p></section>')

    header = ('<!doctype html>\n<html lang="en"><head><meta charset="utf-8">'
              '<meta name="viewport" content="width=device-width,initial-scale=1">'
              '<meta name="referrer" content="no-referrer">'
              '<meta http-equiv="Content-Security-Policy" content="default-src \'none\'; '
              'img-src data:; style-src \'unsafe-inline\'; base-uri \'none\'; form-action \'none\'">'
              '<title>ATLAS · Private Photo DNA contact sheet</title>'
              '<style>body{background:#101827;color:#eef3f8;font:16px system-ui,sans-serif;'
              'line-height:1.5;max-width:1020px;margin:auto;padding:20px}'
              '.pair{border:1px solid #41546b;background:#1a2638;border-radius:14px;'
              'padding:18px;margin:16px 0}h2{font-size:1.12rem}'
              '.images{display:grid;grid-template-columns:repeat(auto-fit,minmax(210px,1fr));gap:12px}'
              'figure{margin:0;min-width:0}img{width:100%;height:230px;object-fit:contain;'
              'background:#080d15;border-radius:8px}figcaption{overflow-wrap:anywhere;'
              'padding-top:6px}.muted{color:#b5c3d3;font-size:.9rem}'
              '.unavailable{min-height:190px;display:grid;place-items:center;'
              'border:1px dashed #718198;border-radius:8px;padding:12px}</style></head><body>')
    caution = ('<h1>Photo DNA · Private contact sheet</h1>'
               '<p><b>Contains embedded photos: do not upload, email or share this HTML.</b> '
               'The images are resized, with EXIF stripped; all data remains in this one local file.</p>'
               '<p>Each pair is only a geometric suggestion. Two similar exposures can both appear '
               'convincing. <b>Never automatically merge families or delete originals.</b> '
               'A↔B and B↔C do not establish A↔C.</p>'
               f'<p class="muted">{len(edges)} pairs · {len(unique)} photos · '
               f'{present} previews available · {len(unique)-present} unavailable · '
               f'paths {"included" if show_names else "hidden"}.</p>')
    output = header + caution + ''.join(cards) + '</body></html>\n'
    if len(output.encode('utf-8')) > MAX_REPORT_BYTES:
        raise ValueError('Rendered report exceeds private output budget')
    return output, {**stats, 'preview_available': present, 'preview_unavailable': len(unique)-present}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('evidence_json', type=Path)
    parser.add_argument('--output', type=Path, required=True, help='New private HTML (never overwritten)')
    parser.add_argument('--show-names', action='store_true', help='Embed sensitive full paths too')
    parser.add_argument('--max-edges', type=int, default=MAX_EDGES)
    args = parser.parse_args()
    if args.evidence_json.absolute() == args.output.absolute():
        parser.error('Source JSON and HTML output must differ')
    try:
        page, stats = render_contact_sheet(_read_json(args.evidence_json),
                                           show_names=args.show_names, max_edges=args.max_edges)
        write_new(args.output, page)
    except (ValueError, OSError) as exc:
        # Never include image paths or raw decoder exceptions in CLI errors.
        parser.error('Unable to create private contact sheet (missing/unsafe input or output): '
                     + ('too many entries' if 'Too many' in str(exc) else 'check evidence, images and permissions'))
    print(f'Wrote private offline contact sheet: {stats["edges"]} pairs, '
          f'{stats["preview_available"]} previews, {stats["preview_unavailable"]} unavailable. '
          'Treat HTML as sensitive photo data.')


if __name__ == '__main__':
    main()
