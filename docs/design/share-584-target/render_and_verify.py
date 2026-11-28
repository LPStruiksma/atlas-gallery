#!/usr/bin/env python3
"""Render the SHARE-1 v3 synthetic SVG frames to PNG and verify them.

    python3 build_frames.py              # regenerate SVG + geometry.json
    python3 render_and_verify.py --render --verify

``--verify`` checks, for every frame in geometry.json:
  * committed SVG and PNG exist, have the declared size, and the PNG matches a
    fresh CairoSVG render of the SVG;
  * every UI colour outside the synthetic photo art is a Quiet Frame v3 token;
  * every text run fits its declared width (DejaVu Sans metrics) and has at
    least 4.5:1 contrast against the rendered, text-free background sampled at
    several points along the run;
  * every tappable control is >=135 px (48 dp) in both dimensions, inside the
    frame, the safe insets, the modal surface and above the IME, and does not
    overlap another active control;
  * focus order lists exactly the active controls, background controls behind a
    modal are inert and absent from focus order, an alert takes focus first,
    and every modal declares a reachable dismissal;
  * share-state invariants (no Share after failure, preference stays On,
    dispatch disabled while preparing) and the exact 2x type scale of the
    200 % frames; switch tracks keep >=3:1 against their row.

It does not test Android runtime, TalkBack, OEM behaviour or file writes.
"""
import argparse
import copy
from io import BytesIO
import json
import subprocess
import sys
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent
NS = '{http://www.w3.org/2000/svg}'
TAPPABLE = {'button', 'switch', 'field', 'menuitem'}
FONT_FILES = {
    'normal': '/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf',
    'bold': '/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf',
}


def lum(hex_rgb):
    rgb = [int(hex_rgb[i:i + 2], 16) / 255 for i in (1, 3, 5)]
    c = [v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4 for v in rgb]
    return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]  # guard: allow-public (sRGB luminance weights)


def contrast(a, b):
    q = sorted((lum(a), lum(b)))
    return (q[1] + 0.05) / (q[0] + 0.05)


def hexpx(rgb):
    return '#%02X%02X%02X' % rgb[:3]


def tag(node):
    return node.tag.replace(NS, '')


def ui_nodes(node, inside_art=False, in_modal=False, with_modal=False):
    """Yield (node, inside_art) or, with ``with_modal``, (node, inside_art, in_modal)."""
    for child in node:
        classes = (child.get('class') or '').split()
        art = inside_art or 'art' in classes
        modal = in_modal or 'modal' in classes
        yield (child, art, modal) if with_modal else (child, art)
        yield from ui_nodes(child, art, modal, with_modal)


def render_png(svg_bytes):
    import cairosvg
    return cairosvg.svg2png(bytestring=svg_bytes)


def check_palette(name, svg, palette, errors):
    allowed = {p.upper() for p in palette} | {'NONE'}
    for node, art in ui_nodes(svg):
        if art:
            continue
        for attr in ('fill', 'stroke'):
            value = node.get(attr)
            if value and value.upper() not in allowed:
                errors.append(f'{name}: {tag(node)} {attr} {value} is not a Quiet Frame v3 token')


def check_text(name, svg, bg, errors, fonts, modal_px):
    """Visible text only: a viewer label fully under the opaque modal is occluded."""
    ratios = []
    for node, art, in_modal in ui_nodes(svg, with_modal=True):
        if art or tag(node) != 'text':
            continue
        value = ''.join(node.itertext()).strip()
        if not value:
            continue
        size = float(node.get('font-size'))
        weight = 'bold' if node.get('font-weight') == 'bold' else 'normal'
        key = (round(size), weight)
        if key not in fonts:
            from PIL import ImageFont
            fonts[key] = ImageFont.truetype(FONT_FILES[weight], round(size))
        width = fonts[key].getlength(value)
        maxw = float(node.get('data-maxw', width))
        if width > maxw + 1:
            errors.append(f'{name}: text {value!r} {width:.0f}px exceeds its {maxw:.0f}px box')
        x, y = float(node.get('x')), float(node.get('y'))
        anchor = node.get('text-anchor', 'start')
        x0 = x if anchor == 'start' else x - width / 2 if anchor == 'middle' else x - width
        if x0 < 0 or x0 + width > bg.width or y - size < 0 or y > bg.height:
            errors.append(f'{name}: text {value!r} leaves the frame')
            continue
        if modal_px and not in_modal and inside([x0, y - size, width, size * 1.2], modal_px):
            continue
        worst = None
        for frac in (0.1, 0.3, 0.5, 0.7, 0.9):
            px = round(x0 + width * frac)
            py = round(y - size * 0.35)
            ratio = contrast(node.get('fill'), hexpx(bg.getpixel((px, py))))
            worst = ratio if worst is None else min(worst, ratio)
        ratios.append(round(worst, 2))
        if worst < 4.5:
            errors.append(f'{name}: text {value!r} contrast {worst:.2f}:1 < 4.5:1')
    return ratios


def inside(r, box, tol=0.5):
    x, y, w, h = r
    bx, by, bw, bh = box
    return x >= bx - tol and y >= by - tol and x + w <= bx + bw + tol and y + h <= by + bh + tol


def overlap(a, b):
    return not (a[0] + a[2] <= b[0] or b[0] + b[2] <= a[0] or a[1] + a[3] <= b[1] or b[1] + b[3] <= a[1])


def check_controls(g, min_hit, errors, png):
    name = g['file']
    W, H = g['px']
    L, T, R, B = g['insets_ltrb_px']
    safe = [L, T, W - L - R, H - T - B]
    controls = g['controls']
    ids = [c['id'] for c in controls]
    checks = 0
    if len(set(ids)) != len(ids):
        errors.append(f'{name}: duplicate control ids')
    if g['focus_order'] != ids:
        errors.append(f'{name}: focus order {g["focus_order"]} != active controls {ids}')
    for inert in g['inert_background_controls']:
        if inert in g['focus_order'] and inert != 'photo':
            errors.append(f'{name}: inert background control {inert} is focusable')
    tappable = [c for c in controls if c['role'] in TAPPABLE]
    for c in controls:
        r = c['rect_px']
        if not inside(r, [0, 0, W, H]):
            errors.append(f'{name}/{c["id"]}: outside frame')
        if c['role'] not in TAPPABLE:
            checks += 1
            continue
        if min(r[2], r[3]) < min_hit - 0.01:
            errors.append(f'{name}/{c["id"]}: {r[2]:.0f}x{r[3]:.0f}px < {min_hit}px (48 dp)')
        if not inside(r, safe):
            errors.append(f'{name}/{c["id"]}: outside safe insets {safe}')
        if g['modal_px'] and not inside(r, g['modal_px']):
            errors.append(f'{name}/{c["id"]}: outside modal surface')
        if g['ime_px'] and overlap(r, g['ime_px']):
            errors.append(f'{name}/{c["id"]}: covered by IME')
        checks += 5
    for i, a in enumerate(tappable):
        for b in tappable[i + 1:]:
            checks += 1
            if overlap(a['rect_px'], b['rect_px']):
                errors.append(f'{name}: {a["id"]} overlaps {b["id"]}')
    focused = (g.get('status') or {}).get('focused_field')
    if g['ime_px']:
        checks += 1
        if not focused or focused not in ids:
            errors.append(f'{name}: IME open but the focused field is not visible')
    alerts = [c for c in controls if c['role'] == 'alert']
    if alerts and controls[0]['role'] != 'alert':
        errors.append(f'{name}: alert is not first in focus order')
    if g['focus_ring_px']:
        checks += 1
        if not inside(controls[0]['rect_px'], g['focus_ring_px'], tol=1):
            errors.append(f'{name}: focus ring does not enclose {controls[0]["id"]}')
    system = g['system_owned'] or []
    if g['modal_px']:
        checks += 2
        if g['modal_px'] != [0, 0, W, H] and not inside(g['modal_px'], safe):
            errors.append(f'{name}: modal surface leaves the safe insets')
        if not g['dismiss']:
            errors.append(f'{name}: modal without declared dismissal')
        popup = g['stage'] == 'menu'
        if not popup and 'conceptual' not in system and not any(c.get('dismiss') for c in controls):
            errors.append(f'{name}: modal has no visible dismiss control')
        if not g['inert_background_controls'] and g['flow'] != 'D':
            errors.append(f'{name}: modal does not isolate the viewer behind it')
    elif 'conceptual' not in system and not tappable:
        errors.append(f'{name}: no controls')
    for c in controls:
        if c['role'] == 'switch':
            x, y, w, h = c['rect_px']
            row = hexpx(png.getpixel((round(x + 12), round(y + h / 2))))
            tx, ty, tw, th = c['indicator_px']
            track = hexpx(png.getpixel((round(tx + tw / 2), round(ty + th / 2))))
            checks += 1
            if contrast(row, track) < 3:
                errors.append(f'{name}: switch track {track} vs row {row} < 3:1')
    return checks


def check_share_state(g, errors):
    if g['flow'] != 'A' or g['stage'] in ('viewer', 'dispatch', 'return'):
        return 0
    name, st, ids = g['file'], g['stage'], {c['id']: c for c in g['controls']}
    s = g['status']
    if s.get('preference_changed_silently') is not False:
        errors.append(f'{name}: preference change not declared explicit')
    if st != 'off' and s.get('remove_location') != 'On':
        errors.append(f'{name}: Remove location must stay On in {st}')
    if st in ('failed', 'unknown', 'unsupported'):
        if 'share' in ids:
            errors.append(f'{name}: Share must not be offered after {st}')
        if not s['dispatch'].startswith('blocked'):
            errors.append(f'{name}: dispatch must be blocked')
        need = {'cancel', 'retry'} if st != 'unsupported' else {'cancel'}
        actions = {c['id'] for c in g['controls'] if c['role'] in TAPPABLE}
        if actions != need:
            errors.append(f'{name}: actions {sorted(actions)} != {sorted(need)}')
    if st == 'preparing' and ids.get('share', {}).get('enabled') is not False:
        errors.append(f'{name}: Share must be disabled while preparing')
    if st in ('ready', 'nogps') and s['dispatch'] != 'verified temporary derivative':
        errors.append(f'{name}: On must dispatch only the verified derivative')
    return 1


def modal_sizes(g):
    svg = ET.parse(ROOT / 'svg' / (g['file'] + '.svg')).getroot()
    out = set()
    for node, art, in_modal in ui_nodes(svg, with_modal=True):
        if tag(node) == 'text' and not art and in_modal:
            out.add(round(float(node.get('font-size')), 2))
    return out


def check_scale(frames, errors):
    by = {g['file']: g for g in frames}
    n = 0
    for g in frames:
        if g['font_scale'] != 2 or g['flow'] != 'A':
            continue
        base = by.get(f'A-{g["stage"]}-{g["theme"]}')
        if not base:
            errors.append(f'{g["file"]}: no 100% counterpart')
            continue
        n += 1
        want = {round(2 * v, 2) for v in modal_sizes(base)}
        got = modal_sizes(g)
        if got != want:
            errors.append(f'{g["file"]}: sheet type sizes {sorted(got)} are not exactly 2x {sorted(want)}')
    return n


def run(render, verify):
    data = json.loads((ROOT / 'geometry.json').read_text())
    frames = data['frames']
    if render:
        (ROOT / 'png').mkdir(exist_ok=True)
        for g in frames:
            src = ROOT / 'svg' / (g['file'] + '.svg')
            (ROOT / 'png' / (g['file'] + '.png')).write_bytes(render_png(src.read_bytes()))
        print(f'Exported {len(frames)} PNG files from SVG sources', file=sys.stderr)
    if not verify:
        return 0
    from PIL import Image, ImageChops
    errors, ratios, fonts = [], [], {}
    control_checks = state_checks = 0
    dims = {}
    for g in frames:
        name = g['file']
        src, png_path = ROOT / 'svg' / (name + '.svg'), ROOT / 'png' / (name + '.png')
        if not src.is_file() or not png_path.is_file():
            errors.append(f'{name}: missing SVG or PNG')
            continue
        svg = ET.parse(src).getroot()
        if [int(svg.get('width')), int(svg.get('height'))] != g['px']:
            errors.append(f'{name}: SVG size differs from geometry')
        fresh = Image.open(BytesIO(render_png(src.read_bytes()))).convert('RGB')
        with Image.open(png_path) as im:
            committed = im.convert('RGB')
        if list(committed.size) != g['px']:
            errors.append(f'{name}: PNG {committed.size} != {g["px"]}')
        else:
            diff = ImageChops.difference(fresh, committed).convert('L').point(lambda v: 255 if v > 24 else 0)
            changed = sum(diff.histogram()[255:]) / (committed.size[0] * committed.size[1])
            if changed > 0.002:
                errors.append(f'{name}: committed PNG differs from SVG render ({changed:.2%} pixels)')
        dims[f'{g["px"][0]}x{g["px"][1]}'] = dims.get(f'{g["px"][0]}x{g["px"][1]}', 0) + 1
        check_palette(name, svg, data['palette'], errors)
        backdrop = copy.deepcopy(svg)
        for parent in backdrop.iter():
            for node in list(parent):
                if tag(node) == 'text':
                    parent.remove(node)
        bg = Image.open(BytesIO(render_png(ET.tostring(backdrop)))).convert('RGB')
        ratios += check_text(name, svg, bg, errors, fonts, g['modal_px'])
        control_checks += check_controls(g, data['min_hit_px'], errors, fresh)
        state_checks += check_share_state(g, errors)
    scale_checks = check_scale(frames, errors)
    try:
        commit = subprocess.run(['git', 'rev-parse', 'HEAD'], cwd=ROOT, capture_output=True,
                                text=True, check=True).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        commit = None
    print(json.dumps({
        'commit': commit, 'frames': len(frames), 'dimensions': dims,
        'controls': sum(len(g['controls']) for g in frames),
        'control_checks': control_checks, 'share_state_checks': state_checks,
        'scale_200_checks': scale_checks, 'text_contrast_runs': len(ratios),
        'minimum_text_contrast': min(ratios) if ratios else None, 'errors': errors,
    }, indent=1))
    return 1 if errors else 0


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('--render', action='store_true', help='write PNGs from the SVG sources')
    p.add_argument('--verify', action='store_true', help='check SVG, PNG and geometry')
    a = p.parse_args()
    if not (a.render or a.verify):
        p.print_help()
        sys.exit(2)
    sys.exit(run(a.render, a.verify))
