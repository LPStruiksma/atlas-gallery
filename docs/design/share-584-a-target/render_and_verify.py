#!/usr/bin/env python3
"""Render the SHARE-1A-E frames to PNG, build contact sheets, and verify them.

    python3 build_frames.py                          # SVG + geometry.json
    python3 render_and_verify.py --render --verify   # PNG + contact sheets + checks

``--verify`` checks, for every frame in geometry.json:

* the committed SVG and PNG exist, have the declared size, and the PNG matches
  a fresh CairoSVG render; the contact sheets match a fresh rebuild;
* every UI colour outside the synthetic photo art is a Quiet Frame v3 token;
* every visible text run fits its declared width (DejaVu Sans metrics) and has
  at least 4.5:1 contrast against the rendered, text-free background; text
  clipped by the scroll viewport is counted, not contrast-sampled;
* every fully visible tappable control is >=135 px (48 dp), inside the frame,
  the safe insets and the modal surface, and does not overlap another one;
  focus order lists exactly the active controls; the viewer behind is inert;
  an alert takes focus first and is fully visible in the initial frame;
* item identity (the #584 v3 preflight finding): every share-state frame has a
  pinned thumbnail outside the scroll region, fully inside the modal and the
  safe area, drawn with preserveAspectRatio meet at the photo's own aspect
  ratio, at least 64 dp on its short side, and six landmark pixels of the
  synthetic photo (corners, sun, lighthouse, sail, hull) render with their
  exact colours, so the whole photo is visible rather than a cropped strip;
  the item name and date are visible text;
* actions: Cancel plus Share (or Retry) are pinned outside the scroll region
  and fully visible in every frame, including every scrolled frame;
* scrolling: a frame whose content overflows declares its viewport, offset and
  indicator; it has a scrolled-to-end sibling (and a middle one when the
  overflow is taller than one viewport) and the rendered offsets cover the
  whole content; the scrolled frame's focus ring encloses a fully visible
  control;
* share-state invariants: Share disabled while preparing; no Share after a
  failure; Remove location stays On (remembered) in every non-Off state; the
  original URI is offered only in Off; dispatch blocked on failure;
* 200 % frames use exactly twice the 100 % sheet type sizes (no shrinking).

It does not test Android runtime, TalkBack, OEM behaviour, grants or files.
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
MIN_THUMB_PX = 180  # 64 dp
CONTACT = {'portrait': (6, 216), 'landscape': (4, 468)}  # columns, tile width px


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


def walk(node, art=False, modal=False, viewport=None):
    """Yield (node, inside_art, inside_modal, scroll_viewport)."""
    for child in node:
        classes = (child.get('class') or '').split()
        a = art or 'art' in classes
        m = modal or 'modal' in classes
        vp = viewport
        if 'scroll' in classes:
            vp = [float(v) for v in child.get('data-viewport').split()]
        yield child, a, m, vp
        yield from walk(child, a, m, vp)


def render_png(svg_bytes):
    import cairosvg
    return cairosvg.svg2png(bytestring=svg_bytes)


def inside(r, box, tol=0.5):
    x, y, w, h = r
    bx, by, bw, bh = box
    return x >= bx - tol and y >= by - tol and x + w <= bx + bw + tol and y + h <= by + bh + tol


def overlap(a, b):
    return not (a[0] + a[2] <= b[0] or b[0] + b[2] <= a[0] or a[1] + a[3] <= b[1] or b[1] + b[3] <= a[1])


def check_palette(name, svg, palette, errors):
    allowed = {p.upper() for p in palette} | {'NONE'}
    for node, art, _, _ in walk(svg):
        if art:
            continue
        for attr in ('fill', 'stroke'):
            value = node.get(attr)
            if value and value.upper() not in allowed:
                errors.append(f'{name}: {tag(node)} {attr} {value} is not a Quiet Frame v3 token')


def check_text(name, svg, bg, errors, fonts, modal_px, counts):
    ratios, visible = [], []
    for node, art, in_modal, vp in walk(svg):
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
        box = [x0, y - size, width, size * 1.25]
        if vp and not inside(box, vp, tol=1):
            counts['clipped'] += 1
            continue
        if x0 < 0 or x0 + width > bg.width or y - size < 0 or y > bg.height:
            errors.append(f'{name}: text {value!r} leaves the frame')
            continue
        if modal_px and not in_modal and inside(box, modal_px):
            continue  # viewer label hidden under the opaque sheet
        visible.append(value)
        worst = None
        for frac in (0.1, 0.3, 0.5, 0.7, 0.9):
            px, py = round(x0 + width * frac), round(y - size * 0.35)
            ratio = contrast(node.get('fill'), hexpx(bg.getpixel((px, py))))
            worst = ratio if worst is None else min(worst, ratio)
        ratios.append(round(worst, 2))
        if worst < 4.5:
            errors.append(f'{name}: text {value!r} contrast {worst:.2f}:1 < 4.5:1')
    return ratios, visible


def vis_rect(c):
    return c.get('visible_rect_px') or c['rect_px']


def check_controls(g, min_hit, errors, png):
    name = g['file']
    W, H = g['px']
    L, T, R, B = g['insets_ltrb_px']
    safe = [L, T, W - L - R, H - T - B]
    controls = g['controls']
    ids = [c['id'] for c in controls]
    n = 0
    if len(set(ids)) != len(ids):
        errors.append(f'{name}: duplicate control ids')
    if g['focus_order'] != ids:
        errors.append(f'{name}: focus order != active controls')
    for inert in g['inert_background_controls']:
        if inert in ids:
            errors.append(f'{name}: inert background control {inert} is focusable')
    shown = [c for c in controls if c['role'] in TAPPABLE and c.get('visible', 'full') == 'full']
    for c in controls:
        if not inside(c['rect_px'], [0, 0, W, H]) and c.get('region') != 'scroll':
            errors.append(f'{name}/{c["id"]}: outside frame')
        if c['role'] not in TAPPABLE or c.get('visible', 'full') != 'full':
            n += 1
            continue
        r = c['rect_px']
        if min(r[2], r[3]) < min_hit - 0.01:
            errors.append(f'{name}/{c["id"]}: {r[2]:.0f}x{r[3]:.0f}px < {min_hit}px (48 dp)')
        if not inside(r, safe):
            errors.append(f'{name}/{c["id"]}: outside safe insets')
        if g['modal_px'] and not inside(r, g['modal_px']):
            errors.append(f'{name}/{c["id"]}: outside modal surface')
        n += 3
    tapp = [c for c in controls if c['role'] in TAPPABLE and c.get('visible', 'full') != 'none']
    for i, a in enumerate(tapp):
        for b in tapp[i + 1:]:
            n += 1
            if overlap(vis_rect(a), vis_rect(b)):
                errors.append(f'{name}: {a["id"]} overlaps {b["id"]}')
    alerts = [c for c in controls if c['role'] == 'alert']
    if alerts:
        n += 1
        if controls[0]['role'] != 'alert':
            errors.append(f'{name}: alert is not first in focus order')
        if g.get('scroll_position') in (None, 'top') and alerts[0].get('visible', 'full') != 'full':
            errors.append(f'{name}: alert not fully visible in the initial frame')
    if g['focus_ring_px']:
        n += 1
        target = next((c for c in controls if c['id'] == g['focused']), None)
        if not target or not inside(target['rect_px'], g['focus_ring_px'], tol=1):
            errors.append(f'{name}: focus ring does not enclose the focused control')
        elif target.get('visible', 'full') != 'full':
            errors.append(f'{name}: focused control is not fully visible')
    if g['modal_px']:
        n += 2
        if not inside(g['modal_px'], safe):
            errors.append(f'{name}: modal surface leaves the safe insets')
        if not g['dismiss'] or not any(c.get('dismiss') for c in controls):
            errors.append(f'{name}: modal without a visible dismiss control')
        if not g['inert_background_controls']:
            errors.append(f'{name}: modal does not isolate the viewer behind it')
    for c in controls:
        if c['role'] == 'switch' and c.get('visible', 'full') == 'full':
            x, y, w, h = c['rect_px']
            row = hexpx(png.getpixel((round(x + 12), round(y + h / 2))))
            tx, ty, tw, th = c['indicator_px']
            track = hexpx(png.getpixel((round(tx + tw / 2), round(ty + th / 2))))
            n += 1
            if contrast(row, track) < 3:
                errors.append(f'{name}: switch track {track} vs row {row} < 3:1')
    return n


def close(a, b, tol=6):
    return all(abs(int(a[i:i + 2], 16) - int(b[i:i + 2], 16)) <= tol for i in (1, 3, 5))


def check_identity(g, svg, png, visible_text, errors):
    """Pinned, fitted, whole-photo thumbnail plus visible name and date."""
    if g['stage'] == 'viewer':
        return 0
    name, th = g['file'], g['thumbnail']
    W, H = g['px']
    L, T, R, B = g['insets_ltrb_px']
    safe = [L, T, W - L - R, H - T - B]
    if not th:
        errors.append(f'{name}: no item thumbnail')
        return 0
    x, y, w, h = th['image_px']
    aw, ah = th['art_px']
    n = 0
    if min(w, h) < MIN_THUMB_PX:
        errors.append(f'{name}: thumbnail {w:.0f}x{h:.0f}px below {MIN_THUMB_PX}px (64 dp)')
    if abs((w / h) - (aw / ah)) > 0.01:
        errors.append(f'{name}: thumbnail aspect {w / h:.3f} != photo {aw / ah:.3f} (cropped or stretched)')
    for box in (th['image_px'], th['box_px']):
        if not inside(box, g['modal_px']) or not inside(box, safe):
            errors.append(f'{name}: thumbnail outside the modal or safe area')
    vp = (g.get('scroll') or {}).get('viewport_px')
    if vp and overlap(th['box_px'], vp):
        errors.append(f'{name}: thumbnail is inside the scroll region (must be pinned)')
    n += 4
    fits = [nd for nd, art, _, _ in walk(svg) if tag(nd) == 'svg' and nd.get('data-fit') == 'meet']
    if len(fits) != 1 or 'meet' not in (fits[0].get('preserveAspectRatio') or ''):
        errors.append(f'{name}: thumbnail is not drawn with preserveAspectRatio meet')
    for lm in th['landmarks']:
        ax, ay = lm['xy']
        px = round(x + ax * w / aw)
        py = round(y + ay * h / ah)
        got = hexpx(png.getpixel((px, py)))
        n += 1
        if not close(got, lm['rgb']):
            errors.append(f'{name}: thumbnail landmark {lm["name"]} is {got}, want {lm["rgb"]}')
    for text in (th['name'],):
        if text not in visible_text:
            errors.append(f'{name}: item name {text!r} is not visible')
    if not joined_run(visible_text, th['date']):
        errors.append(f'{name}: complete item date and time {th["date"]!r} is not visible in reading order')
    ident = next((c for c in g['controls'] if c['id'] == 'item-identity'), None)
    if not ident or ident.get('region') != 'pinned' or th['name'] not in ident['label']:
        errors.append(f'{name}: item identity is not a pinned, labelled control')
    return n + 3


def check_actions(g, errors):
    if g['stage'] == 'viewer':
        return 0
    name = g['file']
    need = {'cancel', 'share'} if g['stage'] in ('off', 'preparing', 'ready', 'nogps') else \
        {'cancel', 'retry'} if g['stage'] in ('failed', 'unknown') else {'cancel'}
    got = {c['id']: c for c in g['controls'] if c['id'] in need}
    if set(got) != need:
        errors.append(f'{name}: missing actions {sorted(need - set(got))}')
    for c in got.values():
        if c.get('region') != 'pinned' or c.get('visible', 'full') != 'full':
            errors.append(f'{name}: action {c["id"]} is not pinned and fully visible')
        vp = (g.get('scroll') or {}).get('viewport_px')
        if vp and overlap(c['rect_px'], vp):
            errors.append(f'{name}: action {c["id"]} lies inside the scroll region')
    return len(need)


def check_share_state(g, errors):
    if g['stage'] == 'viewer':
        return 0
    name, st, ids = g['file'], g['stage'], {c['id']: c for c in g['controls']}
    s = g['status']
    if s.get('preference_changed_silently') is not False:
        errors.append(f'{name}: preference change not declared explicit')
    if st != 'off' and (s.get('remove_location') != 'On' or 'remembered' not in s.get('preference', '')):
        errors.append(f'{name}: Remove location must stay On (remembered) in {st}')
    if s.get('original_uri_exposed') != (st == 'off'):
        errors.append(f'{name}: original URI may be offered only when the user chose Off')
    if st in ('failed', 'unknown', 'unsupported'):
        if 'share' in ids:
            errors.append(f'{name}: Share must not be offered after {st}')
        if not s['dispatch'].startswith('blocked'):
            errors.append(f'{name}: dispatch must be blocked')
        actions = {c['id'] for c in g['controls'] if c['role'] in TAPPABLE}
        need = {'cancel', 'retry'} if st != 'unsupported' else {'cancel'}
        if actions != need:
            errors.append(f'{name}: actions {sorted(actions)} != {sorted(need)}')
    if st == 'preparing' and ids.get('share', {}).get('enabled') is not False:
        errors.append(f'{name}: Share must be disabled while preparing')
    if st == 'preparing' and s.get('dispatch') != 'disabled':
        errors.append(f'{name}: dispatch must be disabled while preparing (got {s.get("dispatch")!r})')
    if st in ('ready', 'nogps') and s['dispatch'] != 'verified temporary derivative':
        errors.append(f'{name}: On must dispatch only the verified derivative')
    return 1


def joined_run(lines, want):
    """True when consecutive visible lines, joined by spaces, equal ``want`` exactly."""
    for i in range(len(lines)):
        acc = ''
        for line in lines[i:]:
            acc = line if not acc else acc + ' ' + line
            if acc == want:
                return True
            if not want.startswith(acc):
                break
    return False


STATES = ('off', 'preparing', 'ready', 'nogps', 'failed', 'unknown', 'unsupported')


def expected_base(state, theme, scale, orientation):
    parts = [f'A-{state}-{theme}']
    if scale == 2:
        parts.append('200')
    if orientation == 'landscape':
        parts.append('landscape')
    return '-'.join(parts)


def check_coverage(frames, errors):
    """Every state x theme x text scale x orientation has its base frame, plus the entry."""
    by = {g['file']: g for g in frames}
    n = 0
    if 'A-viewer' not in by:
        errors.append('coverage: A-viewer entry frame missing')
    for state in STATES:
        for theme in ('light', 'dark'):
            for scale in (1, 2):
                for orientation in ('portrait', 'landscape'):
                    n += 1
                    name = expected_base(state, theme, scale, orientation)
                    g = by.get(name)
                    if not g:
                        errors.append(f'coverage: {name} missing')
                        continue
                    if (g['stage'], g['theme'], g['font_scale'], g['orientation']) != \
                            (state, theme, scale, orientation) or g.get('scroll_position') not in (None, 'top'):
                        errors.append(f'coverage: {name} does not declare {state}/{theme}/{scale}/{orientation}')
    return n


def check_scroll(frames, errors):
    by = {g['file']: g for g in frames}
    n = 0
    for g in frames:
        sc = g.get('scroll')
        if not sc or g['scroll_position'] != 'top':
            continue
        base = g['file']
        group = [by.get(base + sfx) for sfx in ('', '-scroll-mid', '-scrolled')]
        group = [x for x in group if x]
        if base + '-scrolled' not in by:
            errors.append(f'{base}: content overflows but no scrolled frame')
            continue
        vh = sc['viewport_px'][3]
        covered = 0.0
        for x in sorted(group, key=lambda q: q['scroll']['offset_px']):
            off = x['scroll']['offset_px']
            if off > covered + 0.5:
                errors.append(f'{base}: content {covered:.0f}-{off:.0f}px is never rendered')
            covered = max(covered, off + vh)
            if x['scroll']['indicator_px'] is None:
                errors.append(f'{x["file"]}: no scroll indicator')
        if covered < sc['content_h_px'] - 0.5:
            errors.append(f'{base}: content beyond {covered:.0f}px is never rendered')
        end = by[base + '-scrolled']
        if abs(end['scroll']['offset_px'] - sc['max_offset_px']) > 0.5:
            errors.append(f'{base}: scrolled frame does not reach the end')
        n += 1
    return n


def sheet_sizes(svg):
    out = set()
    for node, art, in_modal, _ in walk(svg):
        if tag(node) == 'text' and not art and in_modal:
            out.add(round(float(node.get('font-size')), 2))
    return out


def check_scale(frames, svgs, errors):
    by = {g['file']: g for g in frames}
    n = 0
    for g in frames:
        if g['font_scale'] != 2:
            continue
        base = f'A-{g["stage"]}-{g["theme"]}'
        if base not in by:
            errors.append(f'{g["file"]}: no 100 % counterpart')
            continue
        n += 1
        want = {round(2 * v, 2) for v in sheet_sizes(svgs[base])}
        got = sheet_sizes(svgs[g['file']])
        if got != want:
            errors.append(f'{g["file"]}: sheet type sizes {sorted(got)} are not exactly 2x {sorted(want)}')
    return n


def contact_sheets(frames):
    """Downscaled overview images, one per orientation, labelled with file names."""
    from PIL import Image, ImageDraw, ImageFont
    font = ImageFont.truetype(FONT_FILES['normal'], 14)
    out = {}
    for orient, (cols, tw) in CONTACT.items():
      for scale in (1, 2):
        group = [g for g in frames if g['orientation'] == orient and g['font_scale'] == scale]
        if not group:
            continue
        W, H = group[0]['px']
        th = round(H * tw / W)
        cell_w, cell_h = tw + 48, th + 44
        rows = (len(group) + cols - 1) // cols
        sheet = Image.new('RGB', (cols * cell_w + 24, rows * cell_h + 24), (255, 255, 255))
        draw = ImageDraw.Draw(sheet)
        for i, g in enumerate(group):
            with Image.open(ROOT / 'png' / (g['file'] + '.png')) as im:
                tile = im.convert('RGB').resize((tw, th), Image.LANCZOS)
            cx, cy = 24 + (i % cols) * cell_w, 24 + (i // cols) * cell_h
            sheet.paste(tile, (cx, cy))
            draw.rectangle([cx - 1, cy - 1, cx + tw, cy + th], outline=(90, 95, 122))
            draw.text((cx, cy + th + 8), g['file'], fill=(14, 16, 48), font=font)
        out[f'contact-{orient}-{100 * scale}'] = sheet
    return out


def run(render, verify):
    data = json.loads((ROOT / 'geometry.json').read_text())
    frames = data['frames']
    from PIL import Image, ImageChops
    if render:
        (ROOT / 'png').mkdir(exist_ok=True)
        for g in frames:
            src = ROOT / 'svg' / (g['file'] + '.svg')
            im = Image.open(BytesIO(render_png(src.read_bytes())))
            im.save(ROOT / 'png' / (g['file'] + '.png'), optimize=True)
        for stem, im in contact_sheets(frames).items():
            im.save(ROOT / 'png' / f'{stem}.png', optimize=True)
        print(f'Exported {len(frames)} PNG files and contact sheets', file=sys.stderr)
    if not verify:
        return 0
    errors, ratios, fonts, svgs = [], [], {}, {}
    counts = {'clipped': 0}
    n_ctrl = n_ident = n_act = n_state = 0
    dims = {}

    def same(a, b, label):
        if a.size != b.size:
            errors.append(f'{label}: size {a.size} != {b.size}')
            return
        diff = ImageChops.difference(a, b).convert('L').point(lambda v: 255 if v > 24 else 0)
        changed = sum(diff.histogram()[255:]) / (a.size[0] * a.size[1])
        if changed > 0.002:
            errors.append(f'{label}: committed PNG differs from fresh render ({changed:.2%} pixels)')

    for g in frames:
        name = g['file']
        src, png_path = ROOT / 'svg' / (name + '.svg'), ROOT / 'png' / (name + '.png')
        if not src.is_file() or not png_path.is_file():
            errors.append(f'{name}: missing SVG or PNG')
            continue
        svg = ET.parse(src).getroot()
        svgs[name] = svg
        if [int(svg.get('width')), int(svg.get('height'))] != g['px']:
            errors.append(f'{name}: SVG size differs from geometry')
        fresh = Image.open(BytesIO(render_png(src.read_bytes()))).convert('RGB')
        with Image.open(png_path) as im:
            committed = im.convert('RGB')
        same(committed, fresh, name)
        key = f'{g["px"][0]}x{g["px"][1]}'
        dims[key] = dims.get(key, 0) + 1
        check_palette(name, svg, data['palette'], errors)
        backdrop = copy.deepcopy(svg)
        for parent in backdrop.iter():
            for node in list(parent):
                if tag(node) == 'text':
                    parent.remove(node)
        bg = Image.open(BytesIO(render_png(ET.tostring(backdrop)))).convert('RGB')
        r, visible = check_text(name, svg, bg, errors, fonts, g['modal_px'], counts)
        ratios += r
        n_ctrl += check_controls(g, data['min_hit_px'], errors, fresh)
        n_ident += check_identity(g, svg, fresh, visible, errors)
        n_act += check_actions(g, errors)
        n_state += check_share_state(g, errors)
    n_cov = check_coverage(frames, errors)
    n_scroll = check_scroll(frames, errors)
    n_scale = check_scale(frames, svgs, errors)
    for stem, im in contact_sheets(frames).items():
        path = ROOT / 'png' / f'{stem}.png'
        if not path.is_file():
            errors.append(f'{stem}: missing contact sheet')
            continue
        with Image.open(path) as c:
            same(c.convert('RGB'), im, stem)
    try:
        commit = subprocess.run(['git', 'rev-parse', 'HEAD'], cwd=ROOT, capture_output=True,
                                text=True, check=True).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        commit = None
    print(json.dumps({
        'commit': commit, 'frames': len(frames), 'dimensions': dims,
        'scrolled_frames': sum(1 for g in frames if g.get('scroll_position') in ('mid', 'end')),
        'controls': sum(len(g['controls']) for g in frames),
        'control_checks': n_ctrl, 'identity_checks': n_ident, 'action_checks': n_act,
        'share_state_checks': n_state, 'coverage_cells': n_cov, 'scroll_groups': n_scroll, 'scale_200_checks': n_scale,
        'text_contrast_runs': len(ratios), 'clipped_text_runs': counts['clipped'],
        'minimum_text_contrast': min(ratios) if ratios else None, 'errors': errors,
    }, indent=1))
    return 1 if errors else 0


def self_test():
    """Mutate a copy of geometry.json and prove each guarded invariant rejects it."""
    data = json.loads((ROOT / 'geometry.json').read_text())

    def frame(frames, name):
        return next(g for g in frames if g['file'] == name)

    def state_errors(frames):
        errs = []
        for g in frames:
            check_share_state(g, errs)
        return errs

    def mutate(label, fn, check):
        frames = copy.deepcopy(data['frames'])
        fn(frames)
        errs = []
        check(frames, errs)
        return label, bool(errs), errs[:1]

    cases = [
        mutate('preparing dispatches a derivative',
               lambda fr: frame(fr, 'A-preparing-light')['status'].update(dispatch='verified temporary derivative'),
               lambda fr, e: e.extend(state_errors(fr))),
        mutate('preparing dispatches the original',
               lambda fr: frame(fr, 'A-preparing-dark-200-landscape')['status'].update(dispatch='original URI'),
               lambda fr, e: e.extend(state_errors(fr))),
        mutate('preparing offers an enabled Share',
               lambda fr: next(c for c in frame(fr, 'A-preparing-dark')['controls']
                               if c['id'] == 'share').update(enabled=True),
               lambda fr, e: e.extend(state_errors(fr))),
        mutate('failed offers Share',
               lambda fr: frame(fr, 'A-failed-light-200')['controls'].append(
                   {'id': 'share', 'role': 'button', 'label': 'Share', 'rect_px': [0, 0, 1, 1]}),
               lambda fr, e: e.extend(state_errors(fr))),
        mutate('non-Off state exposes the original URI',
               lambda fr: frame(fr, 'A-nogps-light')['status'].update(original_uri_exposed=True),
               lambda fr, e: e.extend(state_errors(fr))),
        mutate('landscape 100 % no-GPS frame removed',
               lambda fr: fr.remove(frame(fr, 'A-nogps-dark-landscape')),
               check_coverage),
        mutate('scrolled sibling removed',
               lambda fr: fr.remove(frame(fr, 'A-failed-light-200-scrolled')),
               check_scroll),
    ]
    date_cases = [
        ('date split in reading order', ['14 Jul 2024,', '14:05'], True),
        ('time missing', ['14 Jul 2024,'], False),
        ('partial date only', ['Jul 2024,', '14:05'], False),
    ]
    ok = True
    report = []
    for label, rejected, sample in cases:
        report.append({'mutation': label, 'rejected': rejected, 'error': sample[0] if sample else None})
        ok &= rejected
    for label, lines, want in date_cases:
        got = joined_run(lines, '14 Jul 2024, 14:05')
        report.append({'date_case': label, 'accepted': got, 'expected': want})
        ok &= got == want
    print(json.dumps({'self_test': 'pass' if ok else 'FAIL', 'cases': report}, indent=1))
    return 0 if ok else 1


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('--render', action='store_true', help='write PNGs and contact sheets from the SVG sources')
    p.add_argument('--verify', action='store_true', help='check SVG, PNG and geometry')
    p.add_argument('--self-test', action='store_true',
                   help='prove the share-state, coverage, scroll and date checks reject mutated geometry')
    a = p.parse_args()
    if a.self_test:
        sys.exit(self_test())
    if not (a.render or a.verify):
        p.print_help()
        sys.exit(2)
    sys.exit(run(a.render, a.verify))
