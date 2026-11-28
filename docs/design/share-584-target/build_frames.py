#!/usr/bin/env python3
"""Generate the SHARE-1 v3 synthetic design frames and geometry.json.

Deterministic: the same source always yields byte-identical SVG and JSON.
Every contextual control is declared with a named semantic rectangle, a role
and a place in the focus order, so ``render_and_verify.py`` can check size,
overlap, safe insets, modal isolation, palette and contrast for each one.

The art is a synthetic proposal. It is not an Android screenshot, a Samsung
observation or runtime evidence. Text uses DejaVu Sans as a deterministic
stand-in for Funnel Sans so widths can be measured; runtime typography is
untested.
"""
import json
from pathlib import Path

from PIL import ImageFont

ROOT = Path(__file__).resolve().parent
DENSITY = 2.8125
MIN_HIT = 135  # 48 dp at 2.8125 px/dp
# Quiet Frame v3 type roles in px at 100 % font scale (sp x 2.8125)
TITLE = round(18 * DENSITY, 2)      # section title 18 sp
BODY = round(15 * DENSITY, 2)       # body 15 sp
SECONDARY = round(14 * DENSITY, 2)  # secondary 14 sp
LABEL = round(14 * DENSITY, 2)      # control label 14 sp, weight 600 (bold stand-in)
FONT_FILES = {
    'normal': '/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf',
    'bold': '/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf',
}
FONT_FAMILY = 'DejaVu Sans'

# Quiet Frame v3 tokens (docs/design/QUIET_FRAME.md "Colour tokens").
TOKENS = {
    'light': {
        'ground': '#FFFFFF', 'mist': '#F2F3F8', 'ink': '#0E1030',
        'ink2': '#5A5F7A', 'brand_fill': '#2639C9', 'brand_text': '#2639C9',
        'on_brand': '#FFFFFF', 'on_brand2': '#DCE1FF',
    },
    'dark': {
        'ground': '#000000', 'mist': '#14152A', 'ink': '#F2F3F8',
        'ink2': '#9AA0B8', 'brand_fill': '#3448E0', 'brand_text': '#8C9BFF',
        'on_brand': '#FFFFFF', 'on_brand2': '#DCE1FF',
    },
}
VIEWER = {'ground': '#000000', 'slab': '#3448E0', 'sphere': '#8C9BFF',
          'ring': '#FFFFFF', 'keyline': '#000000', 'pill': '#14152A',
          'on': '#FFFFFF'}
PALETTE = sorted({v for t in TOKENS.values() for v in t.values()} | set(VIEWER.values()))

PORTRAIT = {'px': [1080, 2340], 'insets': [0, 125, 0, 135]}
LANDSCAPE = {'px': [2340, 1080], 'insets': [125, 68, 135, 0]}

_fonts = {}


def font(size, weight):
    key = (round(size, 2), weight)
    if key not in _fonts:
        _fonts[key] = ImageFont.truetype(FONT_FILES[weight], round(size))
    return _fonts[key]


def text_width(value, size, weight='normal'):
    return font(size, weight).getlength(value)


def wrap(value, size, maxw, weight='normal'):
    words, lines, cur = value.split(' '), [], ''
    for word in words:
        trial = (cur + ' ' + word).strip()
        if text_width(trial, size, weight) <= maxw or not cur:
            cur = trial
        else:
            lines.append(cur)
            cur = word
    if cur:
        lines.append(cur)
    for line in lines:
        if text_width(line, size, weight) > maxw:
            raise ValueError(f'cannot wrap {line!r} into {maxw}px at {size}px')
    return lines


def esc(s):
    return s.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')


def f(n):
    return ('%.1f' % n).rstrip('0').rstrip('.')


class Frame:
    def __init__(self, name, flow, stage, theme, orientation, scale=1,
                 photo='bright', note=''):
        self.name, self.flow, self.stage = name, flow, stage
        self.theme, self.orientation, self.scale = theme, orientation, scale
        self.photo, self.note = photo, note
        prof = PORTRAIT if orientation == 'portrait' else LANDSCAPE
        self.w, self.h = prof['px']
        self.insets = list(prof['insets'])
        self.t = TOKENS[theme]
        self.body = []
        self.controls = []       # active layer, in focus order
        self.inert = []          # background controls isolated by a modal
        self.modal = None
        self.dismiss = None
        self.ime = None
        self.system = None
        self.photo_px = None
        self.focus_ring = None
        self.photo_px = None
        self.status = {}
        self.on_surface = False

    def surface(self):
        """Modal surface: ground in light; mist in dark so it separates from the black viewer."""
        return self.t['ground'] if self.theme == 'light' else self.t['mist']

    def raised(self):
        """Rows and secondary buttons: mist on light, ground on a dark mist surface."""
        if self.theme == 'dark' and self.on_surface:
            return self.t['ground']
        return self.t['mist']

    # primitives -----------------------------------------------------------
    def rect(self, x, y, w, h, fill, rx=0, opacity=None, stroke=None, sw=None):
        extra = ''
        if opacity is not None:
            extra += f' fill-opacity="{opacity}"'
        if stroke:
            extra += f' stroke="{stroke}" stroke-width="{f(sw)}"'
        self.body.append(f'<rect x="{f(x)}" y="{f(y)}" width="{f(w)}" height="{f(h)}" '
                         f'rx="{f(rx)}" fill="{fill}"{extra}/>')

    def circle(self, cx, cy, r, fill, stroke=None, sw=None):
        extra = f' stroke="{stroke}" stroke-width="{f(sw)}"' if stroke else ''
        self.body.append(f'<circle cx="{f(cx)}" cy="{f(cy)}" r="{f(r)}" fill="{fill}"{extra}/>')

    def text(self, x, y, value, size, fill, weight='normal', anchor='start', maxw=None):
        w = text_width(value, size, weight)
        maxw = maxw if maxw is not None else w
        if w > maxw + 0.01:
            raise ValueError(f'{self.name}: {value!r} is {w:.0f}px > {maxw:.0f}px')
        fw = 'bold' if weight == 'bold' else 'normal'
        self.body.append(
            f'<text x="{f(x)}" y="{f(y)}" font-family="{FONT_FAMILY}" font-size="{f(size)}" '
            f'font-weight="{fw}" text-anchor="{anchor}" fill="{fill}" '
            f'data-maxw="{f(maxw)}">{esc(value)}</text>')

    def para(self, x, y, value, size, fill, maxw, weight='normal', lh=1.3):
        """Wrapped text; ``y`` is the top of the block. Returns the block bottom."""
        lines = wrap(value, size, maxw, weight)
        for i, line in enumerate(lines):
            self.text(x, y + size * 0.95 + i * size * lh, line, size, fill, weight, maxw=maxw)
        return y + size * (0.95 + 0.3) + (len(lines) - 1) * size * lh

    def control(self, cid, role, label, rect, enabled=True, dismiss=False, inert=False):
        item = {'id': cid, 'role': role, 'label': label,
                'rect_px': [round(v, 1) for v in rect], 'enabled': enabled}
        if dismiss:
            item['dismiss'] = True
        (self.inert if inert else self.controls).append(item)
        return item

    def focus(self, rect, pad=8):
        x, y, w, h = rect
        self.focus_ring = [x - pad, y - pad, w + 2 * pad, h + 2 * pad]

    # composite pieces -----------------------------------------------------
    def art(self, x, y, w, h):
        self.photo_px = [x, y, w, h]
        bright = self.photo == 'bright'
        sky, sun, hills, lake, shore = (
            ('#DDEEFF', '#FFF1AA', '#53778E', '#759BBB', '#244F61') if bright else
            ('#25325F', '#C9D3FF', '#33415F', '#3C5A86', '#14263A'))
        self.body.append(
            f'<svg class="art" x="{f(x)}" y="{f(y)}" width="{f(w)}" height="{f(h)}" '
            f'viewBox="0 0 900 1300" preserveAspectRatio="xMidYMid slice">'
            f'<rect width="900" height="1300" fill="{sky}"/>'
            f'<circle cx="666" cy="299" r="86" fill="{sun}"/>'
            f'<path d="M0 936 L225 507 L450 910 L657 611 L900 975 L900 1300 L0 1300 Z" fill="{hills}"/>'
            f'<path d="M0 988 Q405 780 900 1027 L900 1300 L0 1300 Z" fill="{lake}"/>'
            f'<path d="M0 1144 L252 962 L576 1157 L900 1014 L900 1300 L0 1300 Z" fill="{shore}"/>'
            f'<rect x="270" y="429" width="45" height="390" fill="#FFFFFF"/>'
            f'<rect x="261" y="416" width="63" height="39" fill="#BDB9D2"/>'
            f'<rect x="270" y="520" width="45" height="33" fill="#3448E0"/>'
            f'<path d="M252 429 L292 357 L333 429 Z" fill="#7D3E3B"/>'
            f'<path d="M738 884 L738 689 L828 884 Z" fill="#FFFFFF"/>'
            f'<path d="M684 897 L855 897 L810 962 L729 962 Z" fill="#3448E0"/>'
            f'</svg>')

    def header(self, back, more, inert=False):
        for cid, rect, label in (('back', back, 'Back'), ('more', more, 'More photo actions')):
            if rect is None:
                continue
            x, y, w, h = rect
            self.rect(x, y, w, h, VIEWER['pill'], rx=50)
            if cid == 'back':
                self.body.append(f'<path d="M{f(x+w/2+14)} {f(y+h/2-28)} L{f(x+w/2-16)} {f(y+h/2)} '
                                 f'L{f(x+w/2+14)} {f(y+h/2+28)}" fill="none" stroke="#FFFFFF" '
                                 f'stroke-width="10" stroke-linecap="round" stroke-linejoin="round"/>')
            else:
                for i in (-26, 0, 26):
                    self.circle(x + w / 2 + i, y + h / 2, 8, '#FFFFFF')
            self.control(cid, 'button', label, rect, inert=inert)

    def slab(self, x, y, inert=False):
        w, h = 650, 145
        self.rect(x, y, w, h, VIEWER['slab'], rx=67)
        slot = w / 4
        for i, label in enumerate(('Favourite', 'Edit', 'Share', 'Delete')):
            cx = x + slot * i + slot / 2
            shown = '♡' if label == 'Favourite' else label
            size = 54 if label == 'Favourite' else 31
            self.text(cx, y + 92, shown, size, VIEWER['on'], 'bold', 'middle', maxw=slot - 12)
            self.control(f'slab-{label.lower()}', 'button', label, [x + slot * i, y, slot, h], inert=inert)

    def filmstrip(self, cx, y, inert=False):
        tiles = ['#DDEEFF', '#25325F', 'cur', '#25325F', '#DDEEFF']
        for i, tone in enumerate(tiles):
            tx = cx - 53 + (i - 2) * 140
            if tone == 'cur':
                self.body.append(f'<svg class="art" x="{f(tx+5)}" y="{f(y+5)}" width="96" height="130" '
                                 f'viewBox="0 0 900 1300" preserveAspectRatio="xMidYMid slice">'
                                 f'<rect width="900" height="1300" fill="#DDEEFF"/>'
                                 f'<path d="M0 988 Q405 780 900 1027 L900 1300 L0 1300 Z" fill="#759BBB"/>'
                                 f'<rect x="270" y="429" width="60" height="390" fill="#FFFFFF"/></svg>')
                # documented adaptation: black 1 dp external keyline + 2 dp white ring (#142)
                self.rect(tx + 2, y + 2, 102, 136, 'none', rx=18, stroke=VIEWER['keyline'], sw=13)
                self.rect(tx + 2, y + 2, 102, 136, 'none', rx=18, stroke=VIEWER['ring'], sw=5.6)
                self.circle(tx + 53, y - 22, 15, VIEWER['sphere'], stroke=VIEWER['keyline'], sw=6)
            else:
                self.body.append(f'<svg class="art" x="{f(tx+5)}" y="{f(y+5)}" width="96" height="130">'
                                 f'<rect width="96" height="130" rx="17" fill="{tone}"/></svg>')
        self.control('filmstrip-current', 'button', 'Current photo in filmstrip',
                     [cx - 70, y - 7, 140, 154], inert=inert)

    def viewer(self, inert=False):
        """Photo-first viewer behind every flow; identical item in each frame."""
        self.rect(0, 0, self.w, self.h, VIEWER['ground'])
        if self.orientation == 'portrait':
            self.art(90, 280, 900, 1300)
            self.header([24, 137, 144, 138], [890, 137, 166, 138], inert)
            self.photo_control(inert)
            self.filmstrip(540, 1860, inert)
            self.slab(215, 2042, inert)
        else:
            self.art(330, 90, 900, 800)
            self.header([145, 90, 144, 138], [145, 250, 144, 138], inert)
            self.photo_control(inert)
            self.slab(455, 915, inert)

    def photo_control(self, inert=False, label='Synthetic lake photo', index=None):
        item = {'id': 'photo', 'role': 'image', 'label': label,
                'rect_px': [round(v, 1) for v in self.photo_px], 'enabled': True}
        target = self.inert if inert else self.controls
        target.insert(len(target) if index is None else index, item)

    def scrim(self):
        self.rect(0, 0, self.w, self.h, '#000000', opacity=0.55)

    def button(self, cid, label, rect, kind='secondary', size=39, enabled=True, dismiss=False):
        x, y, w, h = rect
        t = self.t
        if not enabled:
            fill, ink = self.raised(), t['ink2']
        elif kind == 'primary':
            fill, ink = t['brand_fill'], t['on_brand']
        else:
            fill, ink = self.raised(), t['ink']
        self.rect(x, y, w, h, fill, rx=min(h / 2, 60))
        self.text(x + w / 2, y + h / 2 + size * 0.36, label, size, ink, 'bold', 'middle', maxw=w - 24)
        return self.control(cid, 'button', label, rect, enabled=enabled, dismiss=dismiss)

    def button_row(self, x, y, w, specs, size, gap=24):
        """Side-by-side when the labels fit, stacked otherwise. Returns bottom."""
        h = max(MIN_HIT + 15, size * 2.4)
        cell = (w - gap * (len(specs) - 1)) / len(specs)
        fits = all(text_width(s[1], size, 'bold') <= cell - 48 for s in specs)
        if fits:
            for i, spec in enumerate(specs):
                self._btn(spec, [x + i * (cell + gap), y, cell, h], size)
            return y + h
        cy = y
        for spec in reversed(specs):  # stacked: primary on top, read first
            self._btn(spec, [x, cy, w, h], size)
            cy += h + gap
        return cy - gap

    def _btn(self, s, rect, size):
        cid, label = s[0], s[1]
        opts = s[2] if len(s) > 2 else {}
        return self.button(cid, label, rect, opts.get('kind', 'secondary'), size,
                           opts.get('enabled', True), opts.get('dismiss', False))

    def sheet_box(self, x, y, w, h):
        self.body.append('<g class="modal">')
        self.rect(x, y, w, h, self.surface(), rx=62)
        self.on_surface = True
        self.modal = [x, y, w, h]

    def keyboard(self, rect):
        """Conceptual system IME; Android owns the real keyboard."""
        x, y, w, h = rect
        self.ime = [x, y, w, h]
        t = self.t
        self.rect(x, y, w, h, t['mist'])
        rows, key_h, gap = 4, (h - 5 * 22) / 4, 22
        for r in range(rows):
            n = 10 if r < 3 else 3
            kw = (w - 60 - (n - 1) * gap) / n if r < 3 else None
            for k in range(n):
                if r < 3:
                    kx = x + 30 + k * (kw + gap)
                    self.rect(kx, y + 22 + r * (key_h + gap), kw, key_h, t['ground'], rx=14)
                else:
                    widths = [(w - 60 - 2 * gap) * p for p in (0.2, 0.6, 0.2)]
                    kx = x + 30 + sum(widths[:k]) + k * gap
                    self.rect(kx, y + 22 + r * (key_h + gap), widths[k], key_h, t['ground'], rx=14)
        # the IME's own hide key (system-owned): hides the keyboard, keeps the draft
        hx = x + 30
        hy = y + 22 + 3 * (key_h + gap)
        hw = (w - 60 - 2 * gap) * 0.2
        self.body.append(f'<path d="M{f(hx+hw/2-24)} {f(hy+key_h/2-10)} L{f(hx+hw/2)} {f(hy+key_h/2+14)} '
                         f'L{f(hx+hw/2+24)} {f(hy+key_h/2-10)}" fill="none" stroke="{t["ink"]}" '
                         f'stroke-width="8" stroke-linecap="round"/>')
        self.system = (self.system or []) + ['ime']

    # output ---------------------------------------------------------------
    def svg(self):
        ring = ''
        if self.focus_ring:
            x, y, w, h = self.focus_ring
            ring = (f'<rect x="{f(x)}" y="{f(y)}" width="{f(w)}" height="{f(h)}" rx="40" fill="none" '
                    f'stroke="{self.t["ink"]}" stroke-width="6"/>')
        return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{self.w}" height="{self.h}" '
                f'viewBox="0 0 {self.w} {self.h}"><title>{self.name}: synthetic SHARE-1 v3 design '
                f'proposal, not an Android screenshot or Samsung observation</title>'
                + ''.join(self.body) + ring
                + ('</g>' if any(b == '<g class="modal">' for b in self.body) else '') + '</svg>\n')

    def geometry(self):
        focus = [c['id'] for c in self.controls]
        return {
            'file': self.name, 'flow': self.flow, 'stage': self.stage, 'theme': self.theme,
            'orientation': self.orientation, 'font_scale': self.scale, 'photo': self.photo,
            'px': [self.w, self.h], 'insets_ltrb_px': self.insets,
            'density_px_per_dp': DENSITY, 'photo_px': [round(v, 1) for v in self.photo_px],
            'modal_px': [round(v, 1) for v in self.modal] if self.modal else None,
            'dismiss': self.dismiss, 'ime_px': self.ime, 'system_owned': self.system,
            'controls': self.controls, 'inert_background_controls': [c['id'] for c in self.inert],
            'focus_order': focus,
            'focus_ring_px': [round(v, 1) for v in self.focus_ring] if self.focus_ring else None,
            'status': self.status, 'note': self.note,
        }


# ---------------------------------------------------------------------------
# A: share without location
# ---------------------------------------------------------------------------
A_STATES = {
    # state: (switch shown, status title, status body, buttons, focus first)
    'off': ('Off', None,
            'Off: the original file is shared, including any location stored in it.',
            [('cancel', 'Cancel', {'dismiss': True}), ('share', 'Share', {'kind': 'primary'})]),
    'preparing': ('On', 'Preparing a copy without location',
                  'Share is unavailable until the copy is checked.',
                  [('cancel', 'Cancel', {'dismiss': True}),
                   ('share', 'Share', {'kind': 'primary', 'enabled': False})]),
    'ready': ('On', 'Copy without location is ready',
              'Location was removed and the copy was checked. Your original is unchanged.',
              [('cancel', 'Cancel', {'dismiss': True}), ('share', 'Share', {'kind': 'primary'})]),
    'nogps': ('On', 'No location found',
              'This photo has no location. A checked copy is still shared, not the original.',
              [('cancel', 'Cancel', {'dismiss': True}), ('share', 'Share', {'kind': 'primary'})]),
    'failed': ('On', "Couldn't prepare a copy without location",
               'Nothing was shared. Try again, or cancel.',
               [('cancel', 'Cancel', {'dismiss': True}), ('retry', 'Retry', {'kind': 'primary'})]),
    'unknown': ('On', "Couldn't check the location",
                'Location data is unreadable and may still be there. Nothing was shared.',
                [('cancel', 'Cancel', {'dismiss': True}), ('retry', 'Retry', {'kind': 'primary'})]),
    'unsupported': ('On', "Can't make a copy without location",
                    'This file is too large or its format is not supported. Nothing was shared.',
                    [('cancel', 'Cancel', {'dismiss': True})]),
}
A_DISPATCH = {'off': 'original URI (user chose Off)', 'preparing': 'disabled',
              'ready': 'verified temporary derivative', 'nogps': 'verified temporary derivative',
              'failed': 'blocked: no intent, no original fallback',
              'unknown': 'blocked: no intent, no original fallback',
              'unsupported': 'blocked: no intent, no original fallback'}


def share_sheet(fr, state, region):
    """Options sheet. ``region`` is the sheet's available box (x, y_bottom, w, y_min)."""
    switch_state, title, body, buttons = A_STATES[state]
    s = fr.scale
    t = fr.t
    x, bottom, w = region
    pad = 66
    inner = w - 2 * pad
    # measure height with a dry run on a throwaway frame
    probe = Frame('probe', 'A', state, fr.theme, fr.orientation, s)
    h = _share_body(probe, state, x, 0, w, pad, inner, s, measure=True)
    top = bottom - h
    fr.sheet_box(x, top, w, h)
    _share_body(fr, state, x, top, w, pad, inner, s)
    fr.status = {'remove_location': switch_state, 'dispatch': A_DISPATCH[state],
                 'preference_changed_silently': False}


def _share_body(fr, state, x, top, w, pad, inner, s, measure=False):
    switch_state, title, body, buttons = A_STATES[state]
    t = fr.t
    y = top + 40 * s
    y = fr.para(x + pad, y, 'Share photo', TITLE * s, t['brand_text'], inner, 'bold') + 24 * s
    # Remove location row: interactive switch only when the user may change it
    interactive = state in ('off', 'ready', 'nogps')
    label_size = BODY * s
    state_size = SECONDARY * s
    track_w, track_h = 140 * min(s, 1.5), 74 * min(s, 1.5)
    label_w = inner - 40 - track_w - 24
    lines = wrap('Remove location', label_size, label_w, 'bold')
    sub = switch_state if interactive else 'Stays on for this share'
    sub_lines = wrap(sub, state_size, label_w)
    content_h = len(lines) * label_size * 1.3 + len(sub_lines) * state_size * 1.3 + state_size * 0.2
    row_h = max(MIN_HIT + 15, content_h + 40 * s)
    fr.rect(x + pad, y, inner, row_h, fr.raised(), rx=34)
    ly = fr.para(x + pad + 30, y + 20 * s, 'Remove location', label_size, t['ink'], label_w, 'bold')
    fr.para(x + pad + 30, ly - state_size * 0.2, sub, state_size, t['ink2'], label_w)
    tx = x + pad + inner - 30 - track_w
    ty = y + 20 * s + (label_size * 1.3 * len(lines) - track_h) / 2 + 4
    on = switch_state == 'On'
    if interactive:
        # dark theme: brand-text lapis keeps the On track >=3:1 against the row
        track = (t['brand_fill'] if fr.theme == 'light' else t['brand_text']) if on else t['ink2']
        knob = t['ground'] if (on and fr.theme == 'dark') else '#FFFFFF'
        fr.rect(tx, ty, track_w, track_h, track, rx=track_h / 2)
        knob_r = track_h / 2 - 8
        fr.circle(tx + (track_w - track_h / 2) if on else tx + track_h / 2, ty + track_h / 2, knob_r, knob)
        sw_ctl = fr.control('remove-location', 'switch', f'Remove location, {switch_state}', [x + pad, y, inner, row_h])
        sw_ctl['indicator_px'] = [round(tx, 1), round(ty, 1), round(track_w, 1), round(track_h, 1)]
    else:
        # locked while preparing or after a failure: a plain "On" badge, not a switch
        fr.rect(tx, ty, track_w, track_h, t['brand_fill'], rx=track_h / 2)
        fr.text(tx + track_w / 2, ty + track_h / 2 + state_size * 0.36, 'On', state_size, t['on_brand'],
                'bold', 'middle', maxw=track_w - 16)
        fr.control('remove-location-state', 'status', 'Remove location, On, stays on for this share',
                   [x + pad, y, inner, row_h])
    y += row_h + 30 * s
    # status block
    block_top = y
    if title:
        icon_r = 0.62 * BODY * s
        glyph = {'preparing': '…', 'ready': '✓', 'nogps': '✓', 'failed': '!', 'unknown': '?',
                 'unsupported': '!'}[state]
        ix = x + pad + 20
        fr.circle(ix + icon_r, y + icon_r + 6, icon_r, fr.surface(), stroke=t['ink'], sw=5)
        fr.text(ix + icon_r, y + icon_r + 6 + 0.36 * BODY * s, glyph, BODY * s, t['ink'], 'bold', 'middle')
        tx0 = ix + 2 * icon_r + 24
        y = fr.para(tx0, y, title, BODY * s, t['ink'], x + pad + inner - 20 - tx0, 'bold') + 10 * s
    y = fr.para(x + pad + 20, y, body, SECONDARY * s, t['ink2'], inner - 40) + 30 * s
    if title and state in ('failed', 'unknown', 'unsupported', 'preparing'):
        alert = fr.control('status-message', 'alert', title, [x + pad - 4, block_top - 14, inner + 8, y - block_top - 8])
        # failure/progress announcements take focus first
        fr.controls.insert(0, fr.controls.pop())
        if state != 'preparing':
            fr.focus(alert['rect_px'], pad=6)
    y = fr.button_row(x + pad, y, inner, buttons, LABEL * s) + 40 * s
    return y - top


def frame_share(state, theme, scale=1, orientation='portrait', photo='bright'):
    suffix = '' if scale == 1 else '-200'
    name = f'A-{state}-{theme}{suffix}' if orientation == 'portrait' else f'A-{state}-{theme}-landscape'
    fr = Frame(name, 'A', state, theme, orientation, scale, photo)
    fr.viewer(inert=True)
    fr.scrim()
    if orientation == 'portrait':
        share_sheet(fr, state, (52, 2340 - 135 - 10, 976))
    else:
        share_sheet(fr, state, (1330, 1060, 2340 - 135 - 20 - 1330))
    fr.dismiss = 'Cancel button, system Back, or tap on the scrim; returns to the same viewer item'
    return fr


def frame_viewer(name, stage, flow, theme='dark'):
    fr = Frame(name, flow, stage, theme, 'portrait')
    fr.viewer()
    return fr


def frame_system(name, flow, stage, title, lines, theme='light'):
    """Android-owned screen drawn only as a neutral conceptual placeholder."""
    fr = Frame(name, flow, stage, theme, 'portrait')
    fr.viewer(inert=True)
    fr.scrim()
    t = fr.t
    x, w = 52, 976
    h = 980
    y = 2340 - 135 - 10 - h
    fr.sheet_box(x, y, w, h)
    fr.system = ['conceptual']
    cy = fr.para(x + 66, y + 50, title, TITLE, t['ink'], w - 132, 'bold') + 10
    for line in lines:
        cy = fr.para(x + 66, cy, line, SECONDARY, t['ink2'], w - 132) + 8
    for i in range(4):
        cx = x + 66 + 70 + i * 220
        fr.circle(cx, cy + 120, 70, fr.raised())
    fr.text(x + w / 2, y + h - 60, 'System-owned conceptual placeholder', SECONDARY, t['ink2'], 'normal', 'middle')
    fr.dismiss = 'Android owns this screen; system Back returns to the same viewer item'
    return fr


# ---------------------------------------------------------------------------
# B/C: one More menu
# ---------------------------------------------------------------------------
MENU_ITEMS = [('ai-edit', 'AI Edit', 'existing'), ('swap-person', 'Swap person', 'existing, only when eligible'),
              ('set-wallpaper', 'Set as wallpaper', 'proposed'), ('copy-image', 'Copy image', 'proposed')]


def frame_menu(theme, orientation='portrait'):
    name = f'BC-menu-{theme}' if orientation == 'portrait' else f'BC-menu-{theme}-landscape'
    fr = Frame(name, 'BC', 'menu', theme, orientation)
    fr.viewer(inert=True)
    t = fr.t
    item_h, w = 150, 560
    if orientation == 'portrait':
        x, y = 1056 - w, 290
    else:
        x, y = 305, 250
    h = item_h * len(MENU_ITEMS) + 32
    fr.body.append('<g class="modal">')
    fr.rect(x, y, w, h, t['mist'] if theme == 'dark' else t['ground'], rx=44,
            stroke=t['ink2'], sw=3)
    fr.modal = [x, y, w, h]
    for i, (cid, label, _) in enumerate(MENU_ITEMS):
        iy = y + 16 + i * item_h
        fr.text(x + 50, iy + item_h / 2 + 13, label, BODY, t['ink'], 'normal', maxw=w - 100)
        fr.control(cid, 'menuitem', label, [x + 8, iy, w - 16, item_h])
    fr.dismiss = 'Tap outside the menu or system Back closes it with no action (popup menu)'
    fr.status = {'menu_items': [{'id': c, 'label': l, 'origin': o} for c, l, o in MENU_ITEMS]}
    fr.focus(fr.controls[0]['rect_px'], pad=0)
    return fr


def frame_notice(name, flow, stage, theme, title, body, buttons, orientation='portrait'):
    fr = Frame(name, flow, stage, theme, orientation)
    fr.viewer(inert=True)
    fr.scrim()
    t = fr.t
    if orientation == 'portrait':
        x, w, bottom = 52, 976, 2340 - 135 - 10
    else:
        x, w, bottom = 1330, 2340 - 135 - 20 - 1330, 1060
    pad, inner = 66, w - 132
    probe = Frame('probe', flow, stage, theme, orientation)
    h = _notice_body(probe, 0, x, pad, inner, title, body, buttons)
    top = bottom - h
    fr.sheet_box(x, top, w, h)
    _notice_body(fr, top, x, pad, inner, title, body, buttons)
    fr.dismiss = 'The listed button or system Back returns to the same viewer item; nothing is changed'
    return fr


def _notice_body(fr, top, x, pad, inner, title, body, buttons):
    t = fr.t
    y = top + 50
    by = y
    y = fr.para(x + pad + 20, y, title, TITLE, t['ink'], inner - 40, 'bold') + 12
    y = fr.para(x + pad + 20, y, body, SECONDARY, t['ink2'], inner - 40) + 30
    alert = fr.control('notice-message', 'alert', title, [x + pad - 4, by - 14, inner + 8, y - by - 8])
    fr.focus(alert['rect_px'], pad=6)
    y = fr.button_row(x + pad, y, inner, buttons, LABEL) + 40
    return y - top


# ---------------------------------------------------------------------------
# D: date and location
# ---------------------------------------------------------------------------
D_DATE, D_TIME, D_ZONE = '14 Jul 2024', '14:05', 'UTC+02:00, Europe/Amsterdam (example)'
D_POINT = 'Map point 10.00° N, 20.00° E (synthetic)'


def details_frame(name, theme, mode='normal'):
    fr = Frame(name, 'D', 'details-' + mode, theme, 'portrait', photo='bright')
    t = fr.t
    fr.rect(0, 0, 1080, 2340, t['ground'])
    fr.art(90, 290, 900, 640)
    fr.header([24, 137, 144, 138], None)
    x, w = 60, 960
    y = 980
    y = fr.para(x, y, 'Details', 45, t['brand_text'], w, 'bold') + 20
    rows = [('Date', f'{D_DATE}, {D_TIME}'), ('Time zone', D_ZONE),
            ('Location', 'No location' if mode in ('nolocation',) else D_POINT)]
    for label, value in rows:
        fr.text(x, y + 36, label, SECONDARY, t['ink2'], maxw=w)
        y = fr.para(x, y + 50, value, BODY, t['ink'], w) + 26
    if mode == 'readonly':
        y += 10
        by = y
        y = fr.para(x, y, "Date and location can't be edited for this file", BODY, t['ink'], w, 'bold') + 8
        y = fr.para(x, y, 'It is read-only or its format is not supported. Nothing was changed.', SECONDARY, t['ink2'], w) + 20
        fr.control('readonly-message', 'status', 'Date and location cannot be edited for this file',
                   [x - 6, by - 6, w + 12, y - by - 8])
    else:
        fr.button('edit-date-location', 'Edit date and location', [x, y + 10, w, 150], 'secondary', LABEL)
        y += 180
    if mode == 'undo':
        sy = 2340 - 135 - 20 - 170
        fr.rect(40, sy, 1000, 170, t['ink'], rx=40)
        fr.text(90, sy + 98, 'Date and location updated', SECONDARY, t['ground'], maxw=640)
        fr.rect(780, sy + 10, 240, 150, t['ink'], rx=40)
        fr.text(900, sy + 98, 'Undo', LABEL, t['ground'], 'bold', 'middle', maxw=200)
        fr.control('undo', 'button', 'Undo date and location change', [780, sy + 10, 240, 150])
        fr.status = {'undo': 'restores previous values; after restart only with fresh permission (unresolved, gap pass)'}
    fr.photo_control(index=1)
    return fr


def editor_frame(name, theme, scale=1, ime=False, orientation='portrait', mode='edit'):
    fr = Frame(name, 'D', mode + ('-ime' if ime else ''), theme, orientation, scale, photo='bright')
    t = fr.t
    s = scale
    fr.rect(0, 0, fr.w, fr.h, t['ground'])
    L, T, R, B = fr.insets
    if orientation == 'portrait':
        if ime:
            kb_h = 820
            fr.keyboard([0, fr.h - B - kb_h, fr.w, kb_h])
            limit = fr.h - B - kb_h - 16
        else:
            limit = fr.h - B - 20
        photo_h = 640 if not ime else (300 if s == 1 else 170)
        fr.art(90, T + 20, 900, photo_h)
        x, w = 60, 960
        top = T + 20 + photo_h + 30
    else:
        if ime:
            kb_h = 430
            fr.keyboard([L, fr.h - kb_h, fr.w - L - R, kb_h])
            limit = fr.h - kb_h - 14
        else:
            limit = fr.h - 20
        fr.art(L + 20, T + 12, 760 if not ime else 420, (fr.h - T - 32) if not ime else (limit - T - 12))
        x = L + 20 + (760 if not ime else 420) + 50
        w = fr.w - R - 20 - x
        top = T + 12
    # pinned Save/Cancel above IME / bottom inset
    btn_size = LABEL * s
    probe = Frame('probe', 'D', 'p', theme, orientation, s)
    row_h = probe.button_row(0, 0, w, [('cancel', 'Cancel'), ('save', 'Save')], btn_size)
    btn_y = limit - row_h
    fr.rect(x - 10, btn_y - 20, w + 20, row_h + 30, t['ground'])
    # scrollable editor content between top and the pinned row
    y = top
    if not (ime and s > 1):
        y = fr.para(x, y, 'Edit date and location', TITLE * s, t['brand_text'], w, 'bold') + 20 * s
    fields = [('field-date', 'Date', D_DATE), ('field-time', 'Time', D_TIME),
              ('field-zone', 'Time zone', D_ZONE.replace(' (example)', '')),
              ('field-location', 'Location', 'No location. Add location' if mode == 'nolocation' else D_POINT)]
    focused = 'field-time' if ime else None
    if ime:
        # keyboard edits the time; earlier fields scroll above, focused field stays visible
        fields = [fd for fd in fields if fd[0] in ('field-time',)] + \
                 ([fd for fd in fields if fd[0] == 'field-zone'] if s == 1 else [])
    for cid, label, value in fields:
        lab = SECONDARY * s
        val = BODY * s
        has_map = cid == 'field-location' and mode != 'nolocation'
        text_w = w - 60 - (150 if has_map else 0)
        lines = wrap(value, val, text_w)
        fh = max(MIN_HIT + 15, lab * 1.4 + len(lines) * val * 1.3 + 36 * s)
        if y + fh > btn_y - 24:
            break
        fr.rect(x, y, w, fh, fr.raised(), rx=30, stroke=t['ink'] if cid == focused else None,
                sw=5 if cid == focused else None)
        fr.text(x + 30, y + 18 * s + lab, label, lab, t['ink2'], maxw=text_w)
        fr.para(x + 30, y + 26 * s + lab, value, val, t['ink'], text_w)
        if has_map:
            # location is a point picked on an offline map, never free-text place alone
            mx, my = x + w - 150, y + (fh - 110) / 2
            fr.body.append(f'<svg class="art" x="{f(mx)}" y="{f(my)}" width="120" height="110">'
                           f'<rect width="120" height="110" rx="16" fill="#D8E4D0"/>'
                           f'<path d="M0 70 Q60 40 120 80" stroke="#8FB3D9" stroke-width="14" fill="none"/>'
                           f'<path d="M40 0 L52 110 M0 30 L120 38" stroke="#FFFFFF" stroke-width="6"/>'
                           f'<circle cx="62" cy="48" r="16" fill="#2639C9"/>'
                           f'<circle cx="62" cy="48" r="6" fill="#FFFFFF"/></svg>')
        fr.control(cid, 'field', f'{label}, {value}', [x, y, w, fh])
        y += fh + 22 * s
    fr.button_row(x, btn_y, w, [('cancel', 'Cancel', {'dismiss': True}), ('save', 'Save', {'kind': 'primary'})],
                  btn_size)
    fr.modal = [0, 0, fr.w, fr.h]
    fr.dismiss = ('Cancel or system Back: with unsaved changes, asks Discard or Keep editing; '
                  + ('hiding the keyboard (IME hide key or first Back) keeps the draft' if ime else
                     'IME dismissal never discards'))
    fr.photo_control(label='Synthetic lake photo being edited', index=0)
    fr.status = {'focused_field': focused, 'unresolved_pending_gap_pass': [
        'transaction mechanism', 'coordinate validation', 'civil time and UTC offset handling',
        'consent renewal', 'atomicity and rollback', 'backup retention', 'index reconciliation']}
    return fr


# ---------------------------------------------------------------------------

def build():
    frames = []
    add = frames.append
    # A
    add(frame_viewer('A-viewer', 'viewer', 'A'))
    for state in A_STATES:
        for theme in ('light', 'dark'):
            add(frame_share(state, theme, photo='bright' if theme == 'dark' else 'dark'))
    for state in A_STATES:
        add(frame_share(state, 'light', scale=2))
    add(frame_share('failed', 'dark', scale=2))
    add(frame_share('ready', 'light', orientation='landscape'))
    add(frame_share('failed', 'dark', orientation='landscape'))
    add(frame_system('A-sharesheet', 'A', 'dispatch', 'Android share sheet',
                     ['Receives only the checked copy without location, with a temporary read grant.',
                      'Targets are chosen by Android, not ranked by Atlas.']))
    add(frame_viewer('A-return', 'return', 'A'))
    # B/C
    add(frame_menu('light'))
    add(frame_menu('dark'))
    add(frame_menu('dark', 'landscape'))
    add(frame_system('B-wallpaper-handoff', 'B', 'handoff', 'Android wallpaper picker',
                     ['Opened with the photo as readable content and a read grant.',
                      'Android owns cropping and the home or lock screen choice.']))
    add(frame_notice('B-no-handler-light', 'B', 'no-handler', 'light', 'No app can set wallpaper',
                     'Nothing was changed.', [('ok', 'OK', {'kind': 'primary', 'dismiss': True})]))
    add(frame_notice('B-unsupported-dark', 'B', 'unsupported', 'dark', "Can't use this file as wallpaper",
                     'The format is not supported or the photo is no longer available. Nothing was changed.',
                     [('ok', 'OK', {'kind': 'primary', 'dismiss': True})]))
    add(frame_system('C-copy-feedback', 'C', 'feedback', 'Android copy confirmation',
                     ['Android shows its own clipboard preview after the copy.',
                      'Atlas adds no banner of its own.']))
    add(frame_notice('C-revoked-light', 'C', 'revoked', 'light', "Couldn't copy image",
                     "The photo can't be read right now. The clipboard was not changed.",
                     [('ok', 'OK', {'kind': 'primary', 'dismiss': True})]))
    add(frame_notice('C-revoked-dark-landscape', 'C', 'revoked', 'dark', "Couldn't copy image",
                     "The photo can't be read right now. The clipboard was not changed.",
                     [('ok', 'OK', {'kind': 'primary', 'dismiss': True})], orientation='landscape'))
    add(frame_viewer('BC-return', 'return', 'BC'))
    # D
    add(details_frame('D-details-light', 'light'))
    add(details_frame('D-details-dark', 'dark'))
    add(details_frame('D-details-nolocation-light', 'light', 'nolocation'))
    add(details_frame('D-details-readonly-dark', 'dark', 'readonly'))
    add(editor_frame('D-editor-light', 'light'))
    add(editor_frame('D-editor-dark', 'dark'))
    add(editor_frame('D-editor-nolocation-light', 'light', mode='nolocation'))
    add(editor_frame('D-editor-ime-light', 'light', ime=True))
    add(editor_frame('D-editor-200-ime-light', 'light', scale=2, ime=True))
    add(editor_frame('D-editor-dark-landscape', 'dark', orientation='landscape'))
    add(editor_frame('D-editor-200-ime-dark-landscape', 'dark', scale=2, ime=True, orientation='landscape'))
    add(frame_notice('D-discard-light', 'D', 'discard', 'light', 'Discard changes?',
                     "Your new date and location won't be saved. The photo stays as it is.",
                     [('keep-editing', 'Keep editing', {'dismiss': True}),
                      ('discard', 'Discard', {'kind': 'primary'})]))
    add(frame_system('D-consent', 'D', 'consent', 'Android permission to modify this photo',
                     ['Android asks before Atlas may change this file.',
                      'Deny returns to the editor with the draft kept and the photo unchanged.']))
    add(frame_notice('D-write-failed-light', 'D', 'write-failed', 'light', "Couldn't save changes",
                     'Nothing was saved. Your edits are kept in the editor.',
                     [('cancel', 'Cancel', {'dismiss': True}), ('retry', 'Retry', {'kind': 'primary'})]))
    add(frame_notice('D-write-failed-dark', 'D', 'write-failed', 'dark', "Couldn't save changes",
                     'Nothing was saved. Your edits are kept in the editor.',
                     [('cancel', 'Cancel', {'dismiss': True}), ('retry', 'Retry', {'kind': 'primary'})]))
    add(details_frame('D-saved-undo-light', 'light', 'undo'))
    add(details_frame('D-saved-undo-dark', 'dark', 'undo'))
    return frames


def main():
    frames = build()
    svg_dir = ROOT / 'svg'
    svg_dir.mkdir(exist_ok=True)
    names = {fr.name for fr in frames}
    if len(names) != len(frames):
        raise SystemExit('duplicate frame names')
    for old in svg_dir.glob('*.svg'):
        if old.stem not in names:
            old.unlink()
    for old in (ROOT / 'png').glob('*.png'):
        if old.stem not in names:
            old.unlink()
    for fr in frames:
        (svg_dir / f'{fr.name}.svg').write_text(fr.svg(), encoding='utf-8')
    geometry = {
        'schema': 2,
        'version': 'SHARE-1 v3 (#1115)',
        'source': 'synthetic coordinates from build_frames.py; not S24+ runtime measurement',
        'density_px_per_dp': DENSITY, 'min_hit_px': MIN_HIT,
        'palette': PALETTE,
        'frames': [fr.geometry() for fr in frames],
    }
    (ROOT / 'geometry.json').write_text(json.dumps(geometry, indent=1) + '\n', encoding='utf-8')
    print(f'Wrote {len(frames)} SVG frames and geometry.json')


if __name__ == '__main__':
    main()
