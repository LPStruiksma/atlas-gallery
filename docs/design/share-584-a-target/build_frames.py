#!/usr/bin/env python3
"""Generate the SHARE-1A-E focused share-privacy frames and geometry.json.

Unit #1117. Self-contained: this file does not import the v3 target in
``../share-584-target`` (read-only evidence), it re-draws flow A only.

Deterministic: the same source always yields byte-identical SVG and JSON.
The sheet has three zones:

* a pinned header that always holds the title and the item identity: a fitted
  (never cropped) thumbnail of the photo being shared plus its name and date;
* a scroll region with the status message and the Remove location row;
* a pinned footer with Cancel and Share, or Cancel and Retry.

Text is never shrunk: 200 % frames use exactly twice the 100 % sizes. When the
scroll region overflows, an initial frame and a scrolled-to-end frame are both
rendered, with the header and footer anchored.

The art is a synthetic proposal. It is not an Android screenshot, a Samsung
observation or runtime evidence. Text uses DejaVu Sans as a deterministic
stand-in for Funnel Sans so widths can be measured; runtime typography is
untested.
"""
import json
import math
from pathlib import Path

from PIL import ImageFont

ROOT = Path(__file__).resolve().parent
DENSITY = 2.8125
MIN_HIT = 135  # 48 dp at 2.8125 px/dp
TITLE = round(18 * DENSITY, 2)      # Quiet Frame section title 18 sp
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
        'on_brand': '#FFFFFF',
    },
    'dark': {
        'ground': '#000000', 'mist': '#14152A', 'ink': '#F2F3F8',
        'ink2': '#9AA0B8', 'brand_fill': '#3448E0', 'brand_text': '#8C9BFF',
        'on_brand': '#FFFFFF',
    },
}
VIEWER = {'ground': '#000000', 'slab': '#3448E0', 'sphere': '#8C9BFF',
          'ring': '#FFFFFF', 'keyline': '#000000', 'pill': '#14152A', 'on': '#FFFFFF'}
PALETTE = sorted({v for t in TOKENS.values() for v in t.values()} | set(VIEWER.values()))

PORTRAIT = {'px': [1080, 2340], 'insets': [0, 125, 0, 135]}
LANDSCAPE = {'px': [2340, 1080], 'insets': [125, 68, 135, 0]}

# The synthetic item. Same photo in every frame so identity is comparable.
ART_W, ART_H = 900, 1300
ITEM_NAME = 'Lake photo'
ITEM_DATE = '14 Jul 2024, 14:05'
ITEM_LABEL = f'Photo being shared: {ITEM_NAME}, {ITEM_DATE} (synthetic)'
# Landmarks in art coordinates and their exact fill, for the verifier's
# "whole photo visible, not cropped" pixel check on the thumbnail.
LANDMARKS = [
    {'name': 'sky top-left', 'xy': [60, 60], 'rgb': '#DDEEFF'},
    {'name': 'sun', 'xy': [666, 299], 'rgb': '#FFF1AA'},
    {'name': 'lighthouse tower', 'xy': [292, 700], 'rgb': '#FFFFFF'},
    {'name': 'sail', 'xy': [762, 846], 'rgb': '#FFFFFF'},
    {'name': 'hull', 'xy': [770, 930], 'rgb': '#3448E0'},
    {'name': 'shore bottom-left', 'xy': [60, 1260], 'rgb': '#244F61'},
]
CONTENT_PAD = 24  # px between the viewport edge and the first scroll-region control
THUMB_BOX = {'portrait': 286, 'landscape': 300}  # px, square box; not scaled with text

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


def r1(rect):
    return [round(v, 1) for v in rect]


def art_svg(x, y, w, h, fit):
    """The synthetic lake photo. ``fit`` is 'meet' (whole photo) or 'slice'."""
    return (f'<svg class="art" data-fit="{fit}" x="{f(x)}" y="{f(y)}" width="{f(w)}" height="{f(h)}" '
            f'viewBox="0 0 {ART_W} {ART_H}" preserveAspectRatio="xMidYMid {fit}">'
            f'<rect width="900" height="1300" fill="#DDEEFF"/>'
            f'<circle cx="666" cy="299" r="86" fill="#FFF1AA"/>'
            f'<path d="M0 936 L225 507 L450 910 L657 611 L900 975 L900 1300 L0 1300 Z" fill="#53778E"/>'
            f'<path d="M0 988 Q405 780 900 1027 L900 1300 L0 1300 Z" fill="#759BBB"/>'
            f'<path d="M0 1144 L252 962 L576 1157 L900 1014 L900 1300 L0 1300 Z" fill="#244F61"/>'
            f'<rect x="270" y="429" width="45" height="390" fill="#FFFFFF"/>'
            f'<rect x="261" y="416" width="63" height="39" fill="#BDB9D2"/>'
            f'<rect x="270" y="520" width="45" height="33" fill="#3448E0"/>'
            f'<path d="M252 429 L292 357 L333 429 Z" fill="#7D3E3B"/>'
            f'<path d="M738 884 L738 689 L828 884 Z" fill="#FFFFFF"/>'
            f'<path d="M684 897 L855 897 L810 962 L729 962 Z" fill="#3448E0"/>'
            f'</svg>')


def fitted(box):
    """Rectangle the whole photo occupies inside ``box`` with preserveAspectRatio meet."""
    x, y, w, h = box
    k = min(w / ART_W, h / ART_H)
    fw, fh = ART_W * k, ART_H * k
    return [x + (w - fw) / 2, y + (h - fh) / 2, fw, fh]


class Frame:
    def __init__(self, name, state, theme, orientation, scale=1, scroll='top'):
        self.name, self.state, self.theme = name, state, theme
        self.orientation, self.scale, self.scroll_pos = orientation, scale, scroll
        prof = PORTRAIT if orientation == 'portrait' else LANDSCAPE
        self.w, self.h = prof['px']
        self.insets = list(prof['insets'])
        self.t = TOKENS[theme]
        self.body = []
        self.controls = []
        self.inert = []
        self.modal = None
        self.dismiss = None
        self.photo_px = None
        self.thumb = None
        self.focus_ring = None
        self.focused = None
        self.scroll = None
        self.status = {}
        self.on_surface = False
        self._region = 'pinned'

    def surface(self):
        return self.t['ground'] if self.theme == 'light' else self.t['mist']

    def raised(self):
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

    def control(self, cid, role, label, rect, enabled=True, dismiss=False, inert=False, **extra):
        item = {'id': cid, 'role': role, 'label': label, 'rect_px': r1(rect),
                'enabled': enabled, 'region': self._region}
        if dismiss:
            item['dismiss'] = True
        item.update(extra)
        (self.inert if inert else self.controls).append(item)
        return item

    def focus(self, rect, pad=6):
        x, y, w, h = rect
        self.focus_ring = [x - pad, y - pad, w + 2 * pad, h + 2 * pad]

    # viewer behind the sheet ------------------------------------------------
    def viewer(self, inert=True):
        self.rect(0, 0, self.w, self.h, VIEWER['ground'])
        if self.orientation == 'portrait':
            self.photo_px = [90, 280, 900, 1300]
            backs = [('back', [24, 137, 144, 138], 'Back'), ('more', [890, 137, 166, 138], 'More photo actions')]
            slab = (215, 2042)
            strip = (540, 1860)
        else:
            self.photo_px = [330, 90, 900, 800]
            backs = [('back', [145, 90, 144, 138], 'Back'), ('more', [145, 250, 144, 138], 'More photo actions')]
            slab = (455, 915)
            strip = None
        self.body.append(art_svg(*self.photo_px, 'slice'))
        for cid, (x, y, w, h), label in backs:
            self.rect(x, y, w, h, VIEWER['pill'], rx=50)
            if cid == 'back':
                self.body.append(f'<path d="M{f(x+w/2+14)} {f(y+h/2-28)} L{f(x+w/2-16)} {f(y+h/2)} '
                                 f'L{f(x+w/2+14)} {f(y+h/2+28)}" fill="none" stroke="#FFFFFF" '
                                 f'stroke-width="10" stroke-linecap="round" stroke-linejoin="round"/>')
            else:
                for i in (-26, 0, 26):
                    self.circle(x + w / 2 + i, y + h / 2, 8, '#FFFFFF')
            self.control(cid, 'button', label, [x, y, w, h], inert=inert)
        self.control('photo', 'image', 'Synthetic lake photo', self.photo_px, inert=inert)
        if strip:
            cx, y = strip
            for i, tone in enumerate(['#DDEEFF', '#25325F', 'cur', '#25325F', '#DDEEFF']):
                tx = cx - 53 + (i - 2) * 140
                if tone == 'cur':
                    self.body.append(art_svg(tx + 5, y + 5, 96, 130, 'slice'))
                    self.rect(tx + 2, y + 2, 102, 136, 'none', rx=18, stroke=VIEWER['keyline'], sw=13)
                    self.rect(tx + 2, y + 2, 102, 136, 'none', rx=18, stroke=VIEWER['ring'], sw=5.6)
                    self.circle(tx + 53, y - 22, 15, VIEWER['sphere'], stroke=VIEWER['keyline'], sw=6)
                else:
                    self.body.append(f'<svg class="art" x="{f(tx+5)}" y="{f(y+5)}" width="96" height="130">'
                                     f'<rect width="96" height="130" rx="17" fill="{tone}"/></svg>')
            self.control('filmstrip-current', 'button', 'Current photo in filmstrip',
                         [cx - 70, y - 7, 140, 154], inert=inert)
        x, y = slab
        w, h = 650, 145
        self.rect(x, y, w, h, VIEWER['slab'], rx=67)
        slot = w / 4
        for i, label in enumerate(('Favourite', 'Edit', 'Share', 'Delete')):
            cx = x + slot * i + slot / 2
            shown = '♡' if label == 'Favourite' else label
            size = 54 if label == 'Favourite' else 31
            self.text(cx, y + 92, shown, size, VIEWER['on'], 'bold', 'middle', maxw=slot - 12)
            self.control(f'slab-{label.lower()}', 'button', label, [x + slot * i, y, slot, h], inert=inert)

    # sheet pieces ------------------------------------------------------------
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

    def button_row(self, x, y, w, specs, size, gap=24, draw=True):
        """Side by side when labels fit, stacked otherwise. Returns bottom."""
        h = max(MIN_HIT + 15, size * 2.4)
        cell = (w - gap * (len(specs) - 1)) / len(specs)
        fits = all(text_width(s[1], size, 'bold') <= cell - 48 for s in specs)
        if fits:
            if draw:
                for i, spec in enumerate(specs):
                    self._btn(spec, [x + i * (cell + gap), y, cell, h], size)
            return y + h
        cy = y
        for spec in reversed(specs):
            if draw:
                self._btn(spec, [x, cy, w, h], size)
            cy += h + gap
        return cy - gap

    def _btn(self, s, rect, size):
        opts = s[2] if len(s) > 2 else {}
        return self.button(s[0], s[1], rect, opts.get('kind', 'secondary'), size,
                           opts.get('enabled', True), opts.get('dismiss', False))

    # output ---------------------------------------------------------------
    def svg(self):
        ring = ''
        if self.focus_ring:
            x, y, w, h = self.focus_ring
            ring = (f'<rect class="focus-ring" x="{f(x)}" y="{f(y)}" width="{f(w)}" height="{f(h)}" rx="40" '
                    f'fill="none" stroke="{self.t["ink"]}" stroke-width="6"/>')
        return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{self.w}" height="{self.h}" '
                f'viewBox="0 0 {self.w} {self.h}"><title>{self.name}: synthetic SHARE-1A-E design '
                f'proposal (#1117), not an Android screenshot or Samsung observation</title>'
                + ''.join(self.body) + ring + '</svg>\n')

    def geometry(self):
        return {
            'file': self.name, 'flow': 'A', 'stage': self.state, 'theme': self.theme,
            'orientation': self.orientation, 'font_scale': self.scale,
            'scroll_position': self.scroll_pos if self.scroll else None,
            'px': [self.w, self.h], 'insets_ltrb_px': self.insets,
            'density_px_per_dp': DENSITY, 'photo_px': r1(self.photo_px),
            'thumbnail': self.thumb,
            'modal_px': r1(self.modal) if self.modal else None,
            'scroll': self.scroll,
            'dismiss': self.dismiss,
            'controls': self.controls,
            'inert_background_controls': [c['id'] for c in self.inert],
            'focus_order': [c['id'] for c in self.controls],
            'focused': self.focused,
            'focus_ring_px': r1(self.focus_ring) if self.focus_ring else None,
            'status': self.status,
        }


# ---------------------------------------------------------------------------
# A: share without location
# ---------------------------------------------------------------------------
CANCEL = ('cancel', 'Cancel', {'dismiss': True})
A_STATES = {
    # state: (switch, status title, status body, buttons)
    'off': ('Off', None,
            'Off: the original file is shared, including any location stored in it.',
            [CANCEL, ('share', 'Share', {'kind': 'primary'})]),
    'preparing': ('On', 'Preparing a copy without location',
                  'Step 1 of 2, removing location. Share stays unavailable until the copy is checked.',
                  [CANCEL, ('share', 'Share', {'kind': 'primary', 'enabled': False})]),
    'ready': ('On', 'Copy without location is ready',
              'Location was removed and the copy was checked. Your original is unchanged.',
              [CANCEL, ('share', 'Share', {'kind': 'primary'})]),
    'nogps': ('On', 'No location found',
              'This photo has no location. A checked copy is still shared, not the original.',
              [CANCEL, ('share', 'Share', {'kind': 'primary'})]),
    'failed': ('On', "Couldn't prepare a copy without location",
               'Nothing was shared. Try again, or cancel.',
               [CANCEL, ('retry', 'Retry', {'kind': 'primary'})]),
    'unknown': ('On', "Couldn't check the location",
                'Location data is unreadable and may still be there. Nothing was shared.',
                [CANCEL, ('retry', 'Retry', {'kind': 'primary'})]),
    'unsupported': ('On', "Can't make a copy without location",
                    'This file is too large or its format is not supported. Nothing was shared.',
                    [CANCEL]),
}
A_DISPATCH = {'off': 'original URI (user chose Off)', 'preparing': 'disabled',
              'ready': 'verified temporary derivative', 'nogps': 'verified temporary derivative',
              'failed': 'blocked: no intent, no original fallback',
              'unknown': 'blocked: no intent, no original fallback',
              'unsupported': 'blocked: no intent, no original fallback'}
ALERT_STATES = ('failed', 'unknown', 'unsupported')


def identity(fr, x, y, w, s, stacked):
    """Pinned item identity: fitted thumbnail + name + date. Returns bottom."""
    t = fr.t
    box = THUMB_BOX[fr.orientation]
    name_size, date_size = BODY * s, SECONDARY * s
    if stacked:  # landscape left column: thumbnail above the text
        bx, by = x, y
        tx, ty, tw = x, y + box + 24 * s, w
    else:        # portrait: thumbnail left, text right
        bx, by = x, y
        tx, tw = x + box + 30, w - box - 30
        ty = y + 10
    fr.rect(bx, by, box, box, fr.raised(), rx=22)
    fit = fitted([bx + 8, by + 8, box - 16, box - 16])
    fr.body.append(art_svg(*fit, 'meet'))
    end = fr.para(tx, ty, ITEM_NAME, name_size, t['ink'], tw, 'bold')
    end = fr.para(tx, end + 4 * s, ITEM_DATE, date_size, t['ink2'], tw)
    rect = [bx, by, (box if not stacked else w), max(box, end - by) if not stacked else end - by]
    if not stacked:
        rect = [bx, by, w, max(box, end - by)]
    fr.control('item-identity', 'image', ITEM_LABEL, rect, image_px=r1(fit))
    fr.thumb = {'box_px': r1([bx, by, box, box]), 'image_px': r1(fit), 'fit': 'meet',
                'art_px': [ART_W, ART_H], 'landmarks': LANDMARKS, 'name': ITEM_NAME, 'date': ITEM_DATE}
    return max(by + box, end)


def status_glyph(fr, state, x, y, r):
    t = fr.t
    if state == 'preparing':
        # Quiet Frame loading vocabulary: the held sphere climbs an arc.
        cx, cy = x + r, y + r + 6
        a0, a1 = math.radians(200), math.radians(340)
        p0 = (cx + r * math.cos(a0), cy - r * math.sin(a0))
        p1 = (cx + r * math.cos(a1), cy - r * math.sin(a1))
        fr.body.append(f'<path d="M{f(p0[0])} {f(p0[1])} A{f(r)} {f(r)} 0 1 1 {f(p1[0])} {f(p1[1])}" '
                       f'fill="none" stroke="{t["ink2"]}" stroke-width="{f(max(5, r * 0.14))}" '
                       f'stroke-linecap="round"/>')
        a = math.radians(60)
        fr.circle(cx + r * math.cos(a), cy - r * math.sin(a), r * 0.36, t['brand_fill'])
        return
    glyph = {'ready': '✓', 'nogps': '✓', 'failed': '!', 'unknown': '?', 'unsupported': '!'}[state]
    fr.circle(x + r, y + r + 6, r, fr.surface(), stroke=t['ink'], sw=5)
    fr.text(x + r, y + r + 6 + 0.36 * BODY * fr.scale, glyph, BODY * fr.scale, t['ink'], 'bold', 'middle')


def scroll_content(fr, state, x, y, w, s):
    """Status block (first) and the Remove location row. Returns bottom."""
    switch_state, title, body, _ = A_STATES[state]
    t = fr.t

    def status_block(y):
        top = y
        if title:
            r = 0.62 * BODY * s
            status_glyph(fr, state, x + 20, y, r)
            tx0 = x + 20 + 2 * r + 24
            y = fr.para(tx0, y, title, BODY * s, t['ink'], x + w - 20 - tx0, 'bold') + 10 * s
        y = fr.para(x + 20, y, body, SECONDARY * s, t['ink2'], w - 40)
        if title:
            role = 'alert' if state in ALERT_STATES else 'status'
            extra = {'live': 'assertive' if role == 'alert' else 'polite'}
            fr.control('status-message', role, title, [x - 4, top - 14, w + 8, y - top + 28], **extra)
        else:
            fr.control('helper-text', 'text', body, [x - 4, top - 14, w + 8, y - top + 28])
        return y + 30 * s

    def switch_row(y):
        interactive = state in ('off', 'ready', 'nogps')
        label_size, state_size = BODY * s, SECONDARY * s
        track_w, track_h = 140 * min(s, 1.5), 74 * min(s, 1.5)
        label_w = w - 40 - track_w - 24
        lines = wrap('Remove location', label_size, label_w, 'bold')
        sub = switch_state if interactive else 'Stays on for this share'
        sub_lines = wrap(sub, state_size, label_w)
        content_h = len(lines) * label_size * 1.3 + len(sub_lines) * state_size * 1.3 + 6 * s
        row_h = max(MIN_HIT + 15, content_h + 40 * s)
        fr.rect(x, y, w, row_h, fr.raised(), rx=34)
        ly = fr.para(x + 30, y + 20 * s, 'Remove location', label_size, t['ink'], label_w, 'bold')
        fr.para(x + 30, ly + 6 * s, sub, state_size, t['ink2'], label_w)
        tx = x + w - 30 - track_w
        ty = y + 20 * s + (label_size * 1.3 * len(lines) - track_h) / 2 + 4
        on = switch_state == 'On'
        if interactive:
            track = (t['brand_fill'] if fr.theme == 'light' else t['brand_text']) if on else t['ink2']
            knob = t['ground'] if (on and fr.theme == 'dark') else '#FFFFFF'
            fr.rect(tx, ty, track_w, track_h, track, rx=track_h / 2)
            kr = track_h / 2 - 8
            fr.circle(tx + (track_w - track_h / 2) if on else tx + track_h / 2, ty + track_h / 2, kr, knob)
            fr.control('remove-location', 'switch', f'Remove location, {switch_state}', [x, y, w, row_h],
                       indicator_px=r1([tx, ty, track_w, track_h]))
        else:
            fr.rect(tx, ty, track_w, track_h, t['brand_fill'], rx=track_h / 2)
            fr.text(tx + track_w / 2, ty + track_h / 2 + state_size * 0.36, 'On', state_size, t['on_brand'],
                    'bold', 'middle', maxw=track_w - 16)
            fr.control('remove-location-state', 'status', 'Remove location, On, stays on for this share',
                       [x, y, w, row_h])
        return y + row_h

    if state == 'off':
        y = switch_row(y) + 30 * s
        y = status_block(y)
    else:
        y = status_block(y)
        y = switch_row(y) + 30 * s
    return y


def layout(orientation, scale):
    """Sheet box limits: x, width, bottom, minimum top."""
    if orientation == 'portrait':
        L, T, R, B = PORTRAIT['insets']
        return 52, 976, PORTRAIT['px'][1] - B - 10, T + 60
    L, T, R, B = LANDSCAPE['insets']
    W, H = LANDSCAPE['px']
    return L + 20, W - R - 20 - (L + 20), H - B - 20, T + 20


def draw_sheet(fr, state, top):
    """Draw (or measure) the sheet with its top at ``top``. Returns a dict of zone sizes."""
    s, t = fr.scale, fr.t
    x, w, bottom, _ = layout(fr.orientation, s)
    pad = 66
    buttons = A_STATES[state][3]
    if fr.orientation == 'portrait':
        inner = w - 2 * pad
        cx, cw = x + pad, inner
        y = top + 40 * s
        y = fr.para(cx, y, 'Share photo', TITLE * s, t['brand_text'], cw, 'bold') + 20 * s
        y = identity(fr, cx, y, cw, s, stacked=False) + 24 * s
        header_bottom = y
        left_bottom = 0
    else:
        lw = 720
        lx = x + pad
        cx = lx + lw + 48
        cw = x + w - pad - cx
        # landscape: the left column pins title and identity; the right column
        # is all scroll region above the pinned buttons, so it stays tall at 200 %
        y = fr.para(lx, top + 48, 'Share photo', TITLE * s, t['brand_text'], lw, 'bold') + 20 * s
        left_bottom = identity(fr, lx, y, lw, s, stacked=True) + 40
        header_bottom = top + 48
    # footer height
    btn_h = fr.button_row(0, 0, cw, buttons, LABEL * s, draw=False)
    gap = 24 * s if fr.orientation == 'portrait' else 24
    footer_h = gap + btn_h + (40 * s if fr.orientation == 'portrait' else 40)
    return {'x': x, 'w': w, 'bottom': bottom, 'cx': cx, 'cw': cw, 'footer_gap': gap,
            'header_bottom': header_bottom, 'left_bottom': left_bottom, 'footer_h': footer_h, 'buttons': buttons}


def frame_share(state, theme, scale=1, orientation='portrait', scroll='top'):
    parts = [f'A-{state}-{theme}']
    if scale != 1:
        parts.append('200')
    if orientation == 'landscape':
        parts.append('landscape')
    if scroll == 'end':
        parts.append('scrolled')
    elif scroll == 'mid':
        parts.append('scroll-mid')
    fr = Frame('-'.join(parts), state, theme, orientation, scale, scroll)
    fr.viewer(inert=True)
    fr.rect(0, 0, fr.w, fr.h, '#000000', opacity=0.55)
    s = scale
    x, w, bottom, top_min = layout(orientation, s)

    # measure: header height, footer height and natural content height
    probe = Frame('probe', state, theme, orientation, s)
    probe.on_surface = True
    z = draw_sheet(probe, state, 0)
    header_h = z['header_bottom']
    content_h = scroll_content(probe, state, z['cx'], CONTENT_PAD, z['cw'], s)
    natural = max(header_h + content_h + z['footer_h'], z['left_bottom'])
    max_h = bottom - top_min
    if z['left_bottom'] > max_h:
        raise ValueError(f'{fr.name}: pinned identity column is taller than the sheet')
    h = min(natural, max_h)
    top = bottom - h

    fr.body.append('<g class="modal">')
    fr.rect(x, top, w, h, fr.surface(), rx=62)
    fr.on_surface = True
    fr.modal = [x, top, w, h]
    z = draw_sheet(fr, state, top)
    vp_top = z['header_bottom']
    vp_bottom = top + h - z['footer_h']
    vp = [z['cx'] - 16, vp_top, z['cw'] + 32, vp_bottom - vp_top]
    overflow = content_h - (vp_bottom - vp_top)
    scrolls = overflow > 0.5
    if scroll == 'end' and not scrolls:
        raise ValueError(f'{fr.name}: no overflow, scrolled frame is not needed')
    # 'mid' shows the band between the initial and the end frame when the
    # overflow is taller than one viewport, so every line is rendered once.
    step = vp[3] - 60
    if scroll == 'mid' and not overflow > step:
        raise ValueError(f'{fr.name}: overflow fits two frames, mid frame is not needed')
    offset = {'top': 0, 'mid': min(step, overflow), 'end': overflow}[scroll] if scrolls else 0
    # divider between header and the scroll region when it scrolls
    if scrolls:
        fr.rect(z['cx'], vp_top - 4, z['cw'], 4, fr.raised())
    clip_id = 'vp-' + fr.name
    fr.body.append(f'<clipPath id="{clip_id}"><rect x="{f(vp[0])}" y="{f(vp[1])}" '
                   f'width="{f(vp[2])}" height="{f(vp[3])}"/></clipPath>')
    fr.body.append(f'<g class="scroll" clip-path="url(#{clip_id})" '
                   f'data-viewport="{f(vp[0])} {f(vp[1])} {f(vp[2])} {f(vp[3])}">')
    fr._region = 'scroll'
    first_scroll = len(fr.controls)
    scroll_content(fr, state, z['cx'], vp_top - offset + CONTENT_PAD, z['cw'], s)
    fr._region = 'pinned'
    fr.body.append('</g>')
    # visibility of scroll-region controls
    for c in fr.controls[first_scroll:]:
        cx0, cy0, cw0, ch0 = c['rect_px']
        y0, y1 = max(cy0, vp[1]), min(cy0 + ch0, vp[1] + vp[3])
        if y1 <= y0:
            c['visible'], c['visible_rect_px'] = 'none', None
        else:
            c['visible'] = 'full' if (y0 <= cy0 + 0.5 and y1 >= cy0 + ch0 - 0.5) else 'partial'
            c['visible_rect_px'] = r1([cx0, y0, cw0, y1 - y0])
    if scrolls:
        # scroll indicator: thumb in ink-secondary on the right edge of the viewport
        frac = vp[3] / content_h
        th = max(60, vp[3] * frac)
        ty = vp[1] + (vp[3] - th) * (offset / overflow)
        fr.rect(vp[0] + vp[2] - 6, ty, 8, th, fr.t['ink2'], rx=4)
        fr.rect(z['cx'], vp_bottom, z['cw'], 4, fr.raised())
        fr.scroll = {'viewport_px': r1(vp), 'content_h_px': round(content_h, 1),
                     'offset_px': round(offset, 1), 'max_offset_px': round(overflow, 1),
                     'indicator_px': r1([vp[0] + vp[2] - 6, ty, 8, th])}
    # pinned footer
    fr.button_row(z['cx'], top + h - z['footer_h'] + z['footer_gap'], z['cw'], z['buttons'], LABEL * s)
    fr.body.append('</g>')

    # focus order: alert first (focus moved there); else identity, scroll region, footer
    ids = [c['id'] for c in fr.controls]
    if state in ALERT_STATES:
        alert = fr.controls.pop(ids.index('status-message'))
        fr.controls.insert(0, alert)
    if scroll in ('mid', 'end'):
        # traversal reached the last scroll-region control; it is scrolled fully into view
        seen = [c for c in fr.controls if c.get('region') == 'scroll' and c['visible'] == 'full']
        if not seen:
            raise ValueError(f'{fr.name}: no fully visible scroll-region control to focus')
        target = seen[-1]
        fr.focused = target['id']
        fr.focus(target['rect_px'])
    elif state in ALERT_STATES:
        fr.focused = 'status-message'
        fr.focus(fr.controls[0]['rect_px'])
    else:
        fr.focused = None
    fr.dismiss = ('Cancel (pinned footer), system Back, or a tap on the visible scrim; '
                  'returns to the same viewer item, nothing is sent and any temporary copy is deleted')
    fr.status = {'remove_location': A_STATES[state][0], 'dispatch': A_DISPATCH[state],
                 'preference_changed_silently': False,
                 'preference': ('Off on first use only; user-changed in this sheet'
                                if state == 'off' else 'On, remembered from the user\'s last choice'),
                 'original_uri_exposed': state == 'off'}
    return fr


def frame_viewer():
    fr = Frame('A-viewer', 'viewer', 'dark', 'portrait')
    fr.viewer(inert=False)
    fr.controls = fr.inert
    fr.inert = []
    fr.dismiss = None
    return fr


def build():
    frames = [frame_viewer()]
    plan = []
    for scale in (1, 2):
        for theme in ('light', 'dark'):
            for state in A_STATES:
                plan.append((state, theme, scale, 'portrait'))
    for theme in ('light', 'dark'):
        for state in A_STATES:
            plan.append((state, theme, 2, 'landscape'))
    for state, theme in (('ready', 'light'), ('ready', 'dark'), ('failed', 'light'), ('failed', 'dark')):
        plan.append((state, theme, 1, 'landscape'))
    for state, theme, scale, orientation in plan:
        fr = frame_share(state, theme, scale, orientation)
        frames.append(fr)
        if fr.scroll:
            if fr.scroll['max_offset_px'] > fr.scroll['viewport_px'][3] - 60:
                frames.append(frame_share(state, theme, scale, orientation, scroll='mid'))
            frames.append(frame_share(state, theme, scale, orientation, scroll='end'))
    return frames


def main():
    frames = build()
    names = [fr.name for fr in frames]
    if len(set(names)) != len(names):
        raise SystemExit('duplicate frame names')
    svg_dir = ROOT / 'svg'
    svg_dir.mkdir(exist_ok=True)
    for old in svg_dir.glob('*.svg'):
        if old.stem not in names:
            old.unlink()
    for old in (ROOT / 'png').glob('*.png'):
        if old.stem not in names and not old.stem.startswith('contact-'):
            old.unlink()
    for fr in frames:
        (svg_dir / f'{fr.name}.svg').write_text(fr.svg(), encoding='utf-8')
    geometry = {
        'schema': 3,
        'version': 'SHARE-1A-E (#1117)',
        'source': 'synthetic coordinates from build_frames.py; not S24+ runtime measurement',
        'density_px_per_dp': DENSITY, 'min_hit_px': MIN_HIT,
        'palette': PALETTE,
        'frames': [fr.geometry() for fr in frames],
    }
    (ROOT / 'geometry.json').write_text(json.dumps(geometry, indent=1, ensure_ascii=False) + '\n',
                                        encoding='utf-8')
    print(f'Wrote {len(frames)} SVG frames and geometry.json')


if __name__ == '__main__':
    main()
