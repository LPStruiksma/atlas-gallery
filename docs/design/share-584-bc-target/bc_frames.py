#!/usr/bin/env python3
"""SHARE-1BC-E2 (#1128, after #1118): focused wallpaper/copy evidence for Atlas-owned states.

Self-contained: draws every frame from deterministic shapes, writes SVG,
geometry.json, PNG and annotated geometry overlays, then verifies them.

    python3 docs/design/share-584-bc-target/bc_frames.py --build --render --verify

Drawn: the viewer More menu (with Set as wallpaper and Copy image) for each
eligibility case, the restricted (EXTERNAL, SECURE) and video viewers where B/C
are never offered, and the Atlas-owned B/C error dialogs. Android owns the wallpaper picker and
the clipboard confirmation; they are not drawn here (see the v3 baseline).

This is a synthetic design proposal. It is not an Android screenshot, a Samsung
observation or runtime evidence. Text uses DejaVu Sans as a measurable stand-in
for Funnel Sans; runtime typography, TalkBack and real focus traversal are
untested.
"""
import argparse
import copy
import json
import subprocess
import sys
import xml.etree.ElementTree as ET
from io import BytesIO
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent
DENSITY = 2.8125
MIN_HIT = 135  # 48 dp
DP = DENSITY
# Quiet Frame v3 type roles at 100 % font scale, in px (sp x 2.8125)
TITLE = round(18 * DP, 2)
BODY = round(15 * DP, 2)
SECONDARY = round(14 * DP, 2)
LABEL = round(14 * DP, 2)
# Material 3 DropdownMenu limits (dp, not scaled by font size)
MENU_MIN_W = 112 * DP
MENU_MAX_W = 280 * DP
# Popup placement (#1128 finding 3). The 48 dp margin is VERTICAL ONLY and is measured
# from the safe window (window minus system-bar/cutout insets): the insets are already
# outside the safe window and are not added a second time. Horizontally the popup stays
# inside the safe window with at least 8 dp between it and either safe edge.
MENU_EDGE_MARGIN = 48 * DP   # vertical: top >= safe top + 48 dp, bottom <= safe bottom - 48 dp
MENU_SIDE_GAP = 8 * DP       # horizontal: >= 8 dp inside the safe left and right edges
MENU_PAD_Y = 8 * DP          # list padding above the first and below the last item
ITEM_PAD_X = 50              # label inset (as v3)
ITEM_PAD_Y = 47.6            # per side; 100 % item = 150 px as in v3
FONT_FILES = {
    'normal': '/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf',
    'bold': '/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf',
}
FONT_FAMILY = 'DejaVu Sans'
TOKENS = {
    'light': {'ground': '#FFFFFF', 'mist': '#F2F3F8', 'ink': '#0E1030', 'ink2': '#5A5F7A',
              'brand_fill': '#2639C9', 'brand_text': '#2639C9', 'on_brand': '#FFFFFF'},
    'dark': {'ground': '#000000', 'mist': '#14152A', 'ink': '#F2F3F8', 'ink2': '#9AA0B8',
             'brand_fill': '#3448E0', 'brand_text': '#8C9BFF', 'on_brand': '#FFFFFF'},
}
VIEWER = {'ground': '#000000', 'slab': '#3448E0', 'sphere': '#8C9BFF', 'ring': '#FFFFFF',
          'keyline': '#000000', 'pill': '#14152A', 'on': '#FFFFFF'}
PALETTE = sorted({v for t in TOKENS.values() for v in t.values()} | set(VIEWER.values()))
PORTRAIT = {'px': [1080, 2340], 'insets': [0, 125, 0, 135]}
LANDSCAPE = {'px': [2340, 1080], 'insets': [125, 68, 135, 0]}
ITEM = {'id': 'synthetic-lake', 'label': 'Synthetic lake photo', 'kind': 'still image'}
TAPPABLE = {'button', 'menuitem'}
MIN_PHOTO_VISIBLE = 0.40  # below this an open surface must name the item explicitly

# The proposed menu, in order. Origin and eligibility come from live main
# (GalleryViewer.kt: AI Edit, then Swap person only when eligible).
MENU_ITEMS = [
    ('ai-edit', 'AI Edit', 'existing; still image and PhotoEditorHandoff.eligible'),
    ('swap-person', 'Swap person', 'existing; only when the swap port says eligible'),
    ('set-wallpaper', 'Set as wallpaper', 'proposed; still images only'),
    ('copy-image', 'Copy image', 'proposed; still images only'),
]
# Viewer access modes as on live main (GalleryViewer.kt ViewerAccess, ExternalViewerHost.kt).
# ``host_actions``: whether the host wires onAiEdit/onEdit/swap/share at all. Only the
# library host does; ExternalViewerHost passes none of them.
ACCESS = {
    'LIBRARY': {'details': True, 'back': 'Back to gallery', 'host_actions': True},
    'EXTERNAL': {'details': True, 'back': 'Back', 'host_actions': False},
    'SECURE': {'details': False, 'back': 'Back to camera', 'host_actions': False},
}
ELIG_KEYS = ('access', 'media', 'readable', 'ai_edit', 'swap', 'wallpaper', 'copy')


def menu_items(e):
    """Items the More menu shows for one eligibility case ``e`` (dict with ELIG_KEYS).

    - Video: unchanged from live main, no More item at all.
    - AI Edit / Swap person: existing gating and order; host-wired, so LIBRARY only;
      Swap person only directly under a shown AI Edit.
    - Set as wallpaper / Copy image: LIBRARY only, readable still content URI, and each
      action's own eligibility (MIME the handoff accepts), independently of each other and
      of AI Edit. EXTERNAL and SECURE never offer them, even for a readable still or a
      caller-granted URI: a caller's read grant is not Atlas's to pass on (#573).
    More is shown only when this list is non-empty.
    """
    if e['media'] != 'still':
        return []
    lib = e['access'] == 'LIBRARY' and ACCESS[e['access']]['host_actions']
    out = []
    if lib and e['ai_edit']:
        out.append('ai-edit')
        if e['swap']:
            out.append('swap-person')
    if lib and e['readable'] and e['wallpaper']:
        out.append('set-wallpaper')
    if lib and e['readable'] and e['copy']:
        out.append('copy-image')
    return out


def elig(access='LIBRARY', media='still', readable=True, ai_edit=True, swap=True, wallpaper=True, copy=True):
    return {'access': access, 'media': media, 'readable': readable, 'ai_edit': ai_edit, 'swap': swap,
            'wallpaper': wallpaper, 'copy': copy}


# What happens when an item is activated: everything is re-checked against the live
# state at that moment and fails closed. Verified to exist and to name each case.
ACTIVATION_RULES = [
    {'when': 'access is no longer LIBRARY when a B/C item is activated (viewer re-hosted, secure review)',
     'outcome': 'menu closes; no action, no dialog, nothing written'},
    {'when': 'current item changed since the menu opened (swipe, deletion, refresh)',
     'outcome': 'menu closes; no action; never acts on the new or a neighbouring item'},
    {'when': 'Set as wallpaper: URI unreadable or grant revoked before the handoff',
     'outcome': 'B-unsupported dialog; nothing was changed'},
    {'when': 'Set as wallpaper: no activity resolves ACTION_ATTACH_DATA',
     'outcome': 'B-no-handler dialog; nothing was changed'},
    {'when': 'Copy image: URI unreadable or grant revoked before ClipData is set (clipboard not touched)',
     'outcome': 'C-revoked dialog; the clipboard was not changed (known: no write was attempted)'},
    {'when': 'Copy image: clipboard service failed or the commit state is unknown after the write call',
     'outcome': 'C-unconfirmed dialog; no claim about the clipboard; prior clipboard content is never read '
                'or restored; no automatic retry'},
    {'when': 'any failure',
     'outcome': 'fail closed: no fallback photo (no cached bitmap, thumbnail or other item is used instead)'},
]

ERRORS = {
    # stage: (flow, title, body, case, clipboard)
    'no-handler': ('B', 'No app can set wallpaper', 'Nothing was changed.',
                   'missing handler: no activity resolves ACTION_ATTACH_DATA', 'untouched'),
    'unsupported': ('B', "Can't use this file as wallpaper",
                    'The format is not supported or the photo is no longer available. Nothing was changed.',
                    'unsupported still, or read grant revoked before handoff', 'untouched'),
    'revoked': ('C', "Couldn't copy image",
                "The photo can't be read right now. The clipboard was not changed.",
                'read grant revoked or URI unreadable before ClipData is set (no clipboard write attempted)',
                'unchanged (known: failure before any write)'),
    'unconfirmed': ('C', "Couldn't copy image",
                    "Atlas couldn't confirm the image was copied. Try again.",
                    'clipboard service failure, or commit state unknown after the write call',
                    'unknown (not read, not restored, not retried)'),
}
# Words a dialog may only use when the clipboard state is known.
UNCHANGED_CLAIMS = ('not changed', 'unchanged', 'was not', 'nothing was')
SUCCESS_CLAIMS = ('image copied', 'copied to', 'copied!', 'done')

_fonts = {}


def font(size, weight='normal'):
    key = (round(size), weight)
    if key not in _fonts:
        _fonts[key] = ImageFont.truetype(FONT_FILES[weight], round(size))
    return _fonts[key]


def text_width(value, size, weight='normal'):
    return font(size, weight).getlength(value)


def wrap(value, size, maxw, weight='normal'):
    lines, cur = [], ''
    for word in value.split(' '):
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
            raise ValueError(f'cannot wrap {line!r} into {maxw:.0f}px at {size}px')
    return lines


def esc(s):
    return s.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')


def f(n):
    return ('%.1f' % n).rstrip('0').rstrip('.')


def r1(rect):
    return [round(v, 1) for v in rect]


def inter(a, b):
    x0, y0 = max(a[0], b[0]), max(a[1], b[1])
    x1, y1 = min(a[0] + a[2], b[0] + b[2]), min(a[1] + a[3], b[1] + b[3])
    return [x0, y0, x1 - x0, y1 - y0] if x1 > x0 and y1 > y0 else None


class Frame:
    def __init__(self, name, flow, stage, theme, orientation, scale):
        self.name, self.flow, self.stage = name, flow, stage
        self.theme, self.orientation, self.scale = theme, orientation, scale
        prof = PORTRAIT if orientation == 'portrait' else LANDSCAPE
        self.w, self.h = prof['px']
        self.insets = list(prof['insets'])
        self.t = TOKENS[theme]
        self.body, self.controls, self.inert = [], [], []
        self.modal = self.dismiss = self.focus_ring = self.photo_px = self.scroll = None
        self.status, self.clips = {}, 0
        self.menu_limits = None

    # primitives
    def rect(self, x, y, w, h, fill, rx=0, opacity=None, stroke=None, sw=None):
        extra = f' fill-opacity="{opacity}"' if opacity is not None else ''
        if stroke:
            extra += f' stroke="{stroke}" stroke-width="{f(sw)}"'
        self.body.append(f'<rect x="{f(x)}" y="{f(y)}" width="{f(w)}" height="{f(h)}" '
                         f'rx="{f(rx)}" fill="{fill}"{extra}/>')

    def circle(self, cx, cy, r, fill, stroke=None, sw=None):
        extra = f' stroke="{stroke}" stroke-width="{f(sw)}"' if stroke else ''
        self.body.append(f'<circle cx="{f(cx)}" cy="{f(cy)}" r="{f(r)}" fill="{fill}"{extra}/>')

    def text(self, x, y, value, size, fill, weight='normal', anchor='start', maxw=None):
        w = text_width(value, size, weight)
        maxw = w if maxw is None else maxw
        if w > maxw + 0.01:
            raise ValueError(f'{self.name}: {value!r} is {w:.0f}px > {maxw:.0f}px')
        self.body.append(
            f'<text x="{f(x)}" y="{f(y)}" font-family="{FONT_FAMILY}" font-size="{f(size)}" '
            f'font-weight="{weight}" text-anchor="{anchor}" fill="{fill}" '
            f'data-maxw="{f(maxw)}">{esc(value)}</text>')

    def para(self, x, y, value, size, fill, maxw, weight='normal', lh=1.3):
        lines = wrap(value, size, maxw, weight)
        for i, line in enumerate(lines):
            self.text(x, y + size * 0.95 + i * size * lh, line, size, fill, weight, maxw=maxw)
        return y + size * 1.25 + (len(lines) - 1) * size * lh

    def control(self, cid, role, label, rect, inert=False, **extra):
        item = {'id': cid, 'role': role, 'label': label, 'rect_px': r1(rect) if rect else None}
        item.update(extra)
        (self.inert if inert else self.controls).append(item)
        return item

    def focus(self, rect, pad=6):
        x, y, w, h = rect
        self.focus_ring = [x - pad, y - pad, w + 2 * pad, h + 2 * pad]

    def clip_open(self, rect, dy):
        self.clips += 1
        cid = f'clip{self.clips}'
        x, y, w, h = rect
        self.body.append(f'<clipPath id="{cid}"><rect x="{f(x)}" y="{f(y)}" width="{f(w)}" '
                         f'height="{f(h)}"/></clipPath><g class="scroll" clip-path="url(#{cid})" '
                         f'data-viewport="{f(x)},{f(y)},{f(w)},{f(h)}"><g transform="translate(0 {f(-dy)})">')

    def clip_close(self):
        self.body.append('</g></g>')

    # viewer (identical item in every frame, as in the v3 baseline)
    def art(self, x, y, w, h):
        self.photo_px = [x, y, w, h]
        self.body.append(
            f'<svg class="art" x="{f(x)}" y="{f(y)}" width="{f(w)}" height="{f(h)}" '
            f'viewBox="0 0 900 1300" preserveAspectRatio="xMidYMid slice">'
            '<rect width="900" height="1300" fill="#DDEEFF"/>'
            '<circle cx="666" cy="299" r="86" fill="#FFF1AA"/>'
            '<path d="M0 936 L225 507 L450 910 L657 611 L900 975 L900 1300 L0 1300 Z" fill="#53778E"/>'
            '<path d="M0 988 Q405 780 900 1027 L900 1300 L0 1300 Z" fill="#759BBB"/>'
            '<path d="M0 1144 L252 962 L576 1157 L900 1014 L900 1300 L0 1300 Z" fill="#244F61"/>'
            '<rect x="270" y="429" width="45" height="390" fill="#FFFFFF"/>'
            '<rect x="261" y="416" width="63" height="39" fill="#BDB9D2"/>'
            '<rect x="270" y="520" width="45" height="33" fill="#3448E0"/>'
            '<path d="M252 429 L292 357 L333 429 Z" fill="#7D3E3B"/>'
            '<path d="M738 884 L738 689 L828 884 Z" fill="#FFFFFF"/>'
            '<path d="M684 897 L855 897 L810 962 L729 962 Z" fill="#3448E0"/></svg>')

    def thumb(self, x, y, w, h):
        self.body.append(
            f'<svg class="art" x="{f(x)}" y="{f(y)}" width="{f(w)}" height="{f(h)}" '
            f'viewBox="0 0 900 1300" preserveAspectRatio="xMidYMid meet">'
            '<rect width="900" height="1300" fill="#DDEEFF"/>'
            '<circle cx="666" cy="299" r="86" fill="#FFF1AA"/>'
            '<path d="M0 936 L225 507 L450 910 L657 611 L900 975 L900 1300 L0 1300 Z" fill="#53778E"/>'
            '<path d="M0 988 Q405 780 900 1027 L900 1300 L0 1300 Z" fill="#759BBB"/>'
            '<rect x="270" y="429" width="45" height="390" fill="#FFFFFF"/>'
            '<path d="M738 884 L738 689 L828 884 Z" fill="#FFFFFF"/></svg>')

    def header(self, back, second, inert, back_label='Back', second_id='more'):
        """Back plus one trailing control: ``more`` (library still with a menu item) or
        ``details`` (restricted or video viewer, as host-wired on live main), or none."""
        pairs = [('back', back, back_label)]
        if second_id:
            pairs.append((second_id, second, 'More photo actions' if second_id == 'more'
                          else 'Photo or video details'))
        for cid, rect, label in pairs:
            x, y, w, h = rect
            self.rect(x, y, w, h, VIEWER['pill'], rx=50)
            if cid == 'back':
                self.body.append(f'<path d="M{f(x+w/2+14)} {f(y+h/2-28)} L{f(x+w/2-16)} {f(y+h/2)} '
                                 f'L{f(x+w/2+14)} {f(y+h/2+28)}" fill="none" stroke="#FFFFFF" '
                                 f'stroke-width="10" stroke-linecap="round" stroke-linejoin="round"/>')
            elif cid == 'more':
                for i in (-26, 0, 26):
                    self.circle(x + w / 2 + i, y + h / 2, 8, '#FFFFFF')
            else:  # outlined info glyph
                cx, cy = x + w / 2, y + h / 2
                self.circle(cx, cy, 30, 'none', stroke='#FFFFFF', sw=7)
                self.circle(cx, cy - 14, 5, '#FFFFFF')
                self.rect(cx - 3.5, cy - 4, 7, 24, '#FFFFFF', rx=3)
            self.control(cid, 'button', label, rect, inert=inert)

    def slab(self, x, y, inert, labels=('Favourite', 'Edit', 'Share', 'Delete')):
        slot = 650 / 4
        w, h = slot * len(labels), 145
        x += round((650 - w) / 2)
        self.rect(x, y, w, h, VIEWER['slab'], rx=67)
        for i, label in enumerate(labels):
            cx = x + slot * i + slot / 2
            shown, size = ('♡', 54) if label == 'Favourite' else (label, 31)
            self.text(cx, y + 92, shown, size, VIEWER['on'], 'bold', 'middle', maxw=slot - 12)
            self.control(f'slab-{label.lower()}', 'button', label, [x + slot * i, y, slot, h], inert=inert)

    def filmstrip(self, cx, y, inert):
        for i, tone in enumerate(['#DDEEFF', '#25325F', 'cur', '#25325F', '#DDEEFF']):
            tx = cx - 53 + (i - 2) * 140
            if tone == 'cur':
                self.thumb(tx + 5, y + 5, 96, 130)
                self.rect(tx + 2, y + 2, 102, 136, 'none', rx=18, stroke=VIEWER['keyline'], sw=13)
                self.rect(tx + 2, y + 2, 102, 136, 'none', rx=18, stroke=VIEWER['ring'], sw=5.6)
                self.circle(tx + 53, y - 22, 15, VIEWER['sphere'], stroke=VIEWER['keyline'], sw=6)
            else:
                self.body.append(f'<svg class="art" x="{f(tx+5)}" y="{f(y+5)}" width="96" height="130">'
                                 f'<rect width="96" height="130" rx="17" fill="{tone}"/></svg>')
        self.control('filmstrip-current', 'button', 'Current photo in filmstrip',
                     [cx - 70, y - 7, 140, 154], inert=inert)

    def viewer(self, inert=True, access='LIBRARY', media='still', more=True, filmstrip=None):
        """Viewer chrome as host-wired on live main for ``access``/``media``.

        LIBRARY still: Back, More (when a menu item applies), filmstrip, slab.
        LIBRARY video: Back, Details, filmstrip, slab without Edit (PhotoEditorHandoff is
        still-only). EXTERNAL: Back, Details; no slab (no favourite/edit/share/trash), no
        More; single caller item has no filmstrip. SECURE: Back to camera only.
        Find person / Find similar header icons are host-wired search hooks outside this
        unit and are not drawn in any frame (specified, unchanged)."""
        a = ACCESS[access]
        lib = access == 'LIBRARY'
        second = 'more' if (more and media == 'still') else ('details' if a['details'] else None)
        if filmstrip is None:
            filmstrip = lib
        slab = None
        if lib:
            slab = ('Favourite', 'Edit', 'Share', 'Delete') if media == 'still' else ('Favourite', 'Share', 'Delete')
        self.rect(0, 0, self.w, self.h, VIEWER['ground'])
        if self.orientation == 'portrait':
            self.art(90, 280, 900, 1300)
            self.header([24, 137, 144, 138], [890, 137, 166, 138], inert, a['back'], second)
            self.control('photo', 'image', ITEM['label'] if media == 'still' else 'Synthetic lake video',
                         self.photo_px, inert=inert)
            if media == 'video':
                self.play(540, 930, inert)
            if filmstrip:
                self.filmstrip(540, 1860, inert)
            if slab:
                self.slab(215, 2042, inert, slab)
        else:
            self.art(330, 90, 900, 800)
            self.header([145, 90, 144, 138], [145, 250, 144, 138], inert, a['back'], second)
            self.control('photo', 'image', ITEM['label'] if media == 'still' else 'Synthetic lake video',
                         self.photo_px, inert=inert)
            if media == 'video':
                self.play(780, 490, inert)
            if slab:
                self.slab(455, 915, inert, slab)

    def play(self, cx, cy, inert):
        self.circle(cx, cy, 90, VIEWER['pill'])
        self.body.append(f'<path d="M{f(cx-30)} {f(cy-46)} L{f(cx+48)} {f(cy)} L{f(cx-30)} {f(cy+46)} Z" '
                         f'fill="#FFFFFF"/>')
        self.control('play', 'button', 'Play video', [cx - 90, cy - 90, 180, 180], inert=inert)

    def svg(self):
        ring = ''
        if self.focus_ring:
            x, y, w, h = self.focus_ring
            ring = (f'<rect x="{f(x)}" y="{f(y)}" width="{f(w)}" height="{f(h)}" rx="40" fill="none" '
                    f'stroke="{self.t["ink"]}" stroke-width="6"/>')
        return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{self.w}" height="{self.h}" '
                f'viewBox="0 0 {self.w} {self.h}"><title>{self.name}: synthetic SHARE-1BC-E design '
                f'proposal, not an Android screenshot or Samsung observation</title>'
                + ''.join(self.body) + ring + '</g></svg>\n')

    def photo_visible(self):
        """Fraction of the photo not covered by the opaque modal surface."""
        x, y, w, h = self.photo_px
        cover = inter(self.photo_px, self.modal) if self.modal else None
        return round(1 - (cover[2] * cover[3] if cover else 0) / (w * h), 3)

    def geometry(self):
        return {
            'file': self.name, 'flow': self.flow, 'stage': self.stage, 'theme': self.theme,
            'orientation': self.orientation, 'font_scale': self.scale,
            'px': [self.w, self.h], 'insets_ltrb_px': self.insets, 'density_px_per_dp': DENSITY,
            'item': ITEM, 'photo_px': r1(self.photo_px), 'photo_visible_fraction': self.photo_visible(),
            'modal_px': r1(self.modal) if self.modal else None, 'scroll': self.scroll, 'dismiss': self.dismiss,
            'controls': self.controls, 'focus_order': [c['id'] for c in self.controls],
            'inert_background_controls': [c['id'] for c in self.inert],
            'focus_ring_px': r1(self.focus_ring) if self.focus_ring else None,
            'returns_to': f'same viewer item ({ITEM["id"]}), viewer state unchanged',
            'status': self.status, 'menu_limits': self.menu_limits,
        }


# ---------------------------------------------------------------------------
# More menu
# ---------------------------------------------------------------------------
def menu_layout(items, s):
    size = BODY * s
    widest = max(text_width(label, size) for _, label, _ in items)
    w = min(max(widest + 2 * ITEM_PAD_X, MENU_MIN_W), MENU_MAX_W)
    rows = []
    for cid, label, origin in items:
        lines = wrap(label, size, w - 2 * ITEM_PAD_X)
        rows.append((cid, label, origin, lines, len(lines) * size * 1.3 + 2 * ITEM_PAD_Y))
    return size, w, rows


def frame_menu(name, theme, orientation, scale, e, scroll='top'):
    """More menu for eligibility case ``e``. At font scale >= 1.5 the menu opens with a
    pinned non-interactive row (thumbnail + "This photo") because the larger popup hides
    most of the photo."""
    identity = scale >= 1.5
    ids = menu_items(e)
    if not ids:
        raise ValueError(f'{name}: no item applies, so there is no More menu to draw')
    items = [m for m in MENU_ITEMS if m[0] in ids]
    fr = Frame(name, 'BC', 'menu', theme, orientation, scale)
    fr.args = ('menu', name, theme, orientation, scale, e)
    fr.viewer(inert=True)
    t = fr.t
    size, w, rows = menu_layout(items, scale)
    ident_h = 0
    if identity:
        ident_lines = wrap('This photo', size, w - 2 * ITEM_PAD_X - 135 - 24, 'bold')
        ident_h = max(135, len(ident_lines) * size * 1.3) + 2 * 24
    content_h = 2 * MENU_PAD_Y + ident_h + sum(r[4] for r in rows)
    L, T, R, B = fr.insets
    if orientation == 'portrait':
        x, pref_top = 1056 - w, 290          # under the More button, right aligned
    else:
        x, pref_top = 305, 250               # beside the More button, as in v3
    # horizontal: inside the safe window, >= 8 dp from each safe edge (no 48 dp here)
    safe_l, safe_r = L + MENU_SIDE_GAP, fr.w - R - MENU_SIDE_GAP
    if w > safe_r - safe_l:
        raise ValueError(f'{name}: menu {w:.0f}px cannot fit the safe width; reflow needed')
    x = min(max(x, safe_l), safe_r - w)
    # vertical: 48 dp from the safe window edges (insets counted once, not twice)
    top_limit, bottom_limit = T + MENU_EDGE_MARGIN, fr.h - B - MENU_EDGE_MARGIN
    top = max(pref_top, top_limit)
    if top + content_h > bottom_limit:       # popup shifts up first, then scrolls
        top = max(top_limit, bottom_limit - content_h)
    h = min(content_h, bottom_limit - top)
    fr.menu_limits = {
        'rule': '48 dp vertical margin from the safe window (insets not double counted); '
                'horizontally inside the safe window with >= 8 dp to each safe edge; width 112-280 dp',
        'safe_window_px': [L, T, fr.w - L - R, fr.h - T - B],
        'top_limit_px': round(top_limit, 1), 'bottom_limit_px': round(bottom_limit, 1),
        'left_limit_px': round(safe_l, 1), 'right_limit_px': round(safe_r, 1),
        'gaps_dp': {'top': round((top - T) / DP, 2), 'bottom': round((fr.h - B - top - h) / DP, 2),
                    'left': round((x - L) / DP, 2), 'right': round((fr.w - R - x - w) / DP, 2)},
        'width_dp': round(w / DP, 2),
    }
    overflow = content_h - h
    dy = 0 if scroll == 'top' else overflow if scroll == 'end' else min(float(scroll), overflow)
    position = scroll if scroll in ('top', 'end') else 'middle'
    fr.body.append('<g class="modal">')
    fr.rect(x, top, w, h, t['mist'] if theme == 'dark' else t['ground'], rx=44, stroke=t['ink2'], sw=3)
    fr.modal = [x, top, w, h]
    y = top + MENU_PAD_Y
    if identity:
        # pinned header: drawn outside the scrolling rows, so it stays visible at any scroll position
        tx = x + ITEM_PAD_X
        fr.thumb(tx, y + 24, 135 * 900 / 1300, 135)
        ly = y + 24 + (135 - size * 1.3) / 2
        fr.para(tx + 135 * 900 / 1300 + 24, ly, 'This photo', size, t['ink'], w - 2 * ITEM_PAD_X - 135 - 24, 'bold')
        fr.control('menu-identity', 'heading', f'Actions for {ITEM["label"]}', [x + 8, y, w - 16, ident_h],
                   visibility='full', pinned=True)
        fr.rect(x + 24, y + ident_h - 1.5, w - 48, 3, t['ink2'])
        y += ident_h
    viewport = [x, y, w, top + h - 2 - y]
    if overflow > 0:
        fr.clip_open(viewport, dy)
        fr.scroll = {'container': 'menu rows (identity header pinned)', 'content_px': round(content_h, 1),
                     'viewport_px': r1(viewport), 'offset_px': round(dy, 1), 'position': position,
                     'reveal': 'focus or a swipe scrolls the rows; every item can be fully shown; '
                               'a partly shown row is a scroll cue only: a tap or focus scrolls it fully '
                               'into view and never activates it'}
    for cid, label, origin, lines, ih in rows:
        for i, line in enumerate(lines):
            fr.text(x + ITEM_PAD_X, y + ITEM_PAD_Y + size * 0.95 + i * size * 1.3, line, size, t['ink'],
                    maxw=w - 2 * ITEM_PAD_X)
        vr, extra = _visible([x + 8, y, w - 16, ih], viewport, dy)
        extra['activation'] = 'activate' if extra.get('visibility') == 'full' else (
            'reveal-only' if extra.get('visibility') == 'partial' else 'none')
        fr.control(cid, 'menuitem', label, vr, lines=len(lines), **extra)
        y += ih
    if overflow > 0:
        fr.clip_close()
    fr.dismiss = 'Tap outside the menu or system Back closes it with no action; focus returns to More'
    fr.status = {'menu_items': [{'id': c, 'label': l, 'origin': o} for c, l, o in items],
                 'menu_width_px': round(w, 1), 'menu_width_rule': '112-280 dp; labels wrap at 280 dp',
                 'eligibility': e, 'expected_items': ids}
    first = fr.controls[0]
    if first.get('rect_px'):
        fr.focus(first['rect_px'], pad=0)
    return fr


def _visible(rect, viewport, dy):
    """Return (visible_rect, extra) for a row drawn at ``rect`` inside a scrolled viewport."""
    shown = [rect[0], rect[1] - dy, rect[2], rect[3]]
    vis = inter(shown, viewport)
    if vis is None:
        return None, {'visibility': 'offscreen', 'content_rect_px': r1(rect)}
    if abs(vis[3] - shown[3]) > 0.5:
        return vis, {'visibility': 'partial', 'content_rect_px': r1(rect)}
    return vis, {'visibility': 'full'}


frame_menu_c = frame_menu


# ---------------------------------------------------------------------------
# Viewer without a More menu (restricted access, video): B/C never offered
# ---------------------------------------------------------------------------
def frame_viewer(name, orientation, e):
    fr = Frame(name, 'V', 'viewer', 'dark', orientation, 1)
    fr.args = ('viewer', name, orientation, e)
    if menu_items(e):
        raise ValueError(f'{name}: a menu item applies; draw it with frame_menu')
    fr.viewer(inert=False, access=e['access'], media=e['media'], more=False,
              filmstrip=e['access'] == 'LIBRARY')
    fr.body.append('<g>')  # svg() closes one group
    fr.dismiss = {'LIBRARY': 'Back returns to the gallery grid',
                  'EXTERNAL': 'Back finishes the Activity and returns to the calling app',
                  'SECURE': 'Back finishes the Activity and returns to the camera'}[e['access']]
    fr.status = {'eligibility': e, 'expected_items': [], 'more_shown': False,
                 'theme_note': 'viewer ground is always black; ExternalViewerHost forces the dark theme',
                 'font_scale_note': 'icon-only chrome; no text grows with font scale in this state'}
    return fr

# ---------------------------------------------------------------------------
# Error dialogs (Atlas-owned)
# ---------------------------------------------------------------------------
def frame_error(stage, theme, orientation, scale, scroll='top'):
    flow, title, body, case, clip = ERRORS[stage]
    suffix = '' if scale == 1 else '-200'
    name = f'{flow}-{stage}-{theme}{suffix}' + ('-landscape' if orientation == 'landscape' else '')
    if scroll == 'end':
        name += '-scrolled'
    fr = Frame(name, flow, stage, theme, orientation, scale)
    fr.args = ('error', stage, theme, orientation, scale)
    fr.viewer(inert=True)
    fr.rect(0, 0, fr.w, fr.h, '#000000', opacity=0.55)
    t, s = fr.t, scale
    L, T, R, B = fr.insets
    if orientation == 'portrait':
        x, w, bottom = 52, 976, fr.h - B - 10
    else:
        x, w, bottom = 1260, fr.w - R - 20 - 1260, fr.h - 20
    top_limit = T + 20
    pad, inner = 66, w - 132
    msg_top_pad = 50
    tsize, bsize = TITLE * s, SECONDARY * s
    tl = wrap(title, tsize, inner - 40, 'bold')
    bl = wrap(body, bsize, inner - 40)
    msg_h = tsize * 1.25 + (len(tl) - 1) * tsize * 1.3 + 12 + bsize * 1.25 + (len(bl) - 1) * bsize * 1.3
    btn_h = max(MIN_HIT + 15, LABEL * s * 2.4)
    chrome_h = msg_top_pad + 30 + btn_h + 40
    full_h = chrome_h + msg_h
    h = min(full_h, bottom - top_limit)
    top = bottom - h
    overflow = full_h - h
    fr.body.append('<g class="modal">')
    fr.rect(x, top, w, h, t['ground'] if theme == 'light' else t['mist'], rx=62)
    fr.modal = [x, top, w, h]
    view_top = top + msg_top_pad - 14
    view_h = h - chrome_h + 28 - 8
    viewport = [x + pad - 4, view_top, inner + 8, view_h]
    dy = 0 if scroll == 'top' else overflow
    if overflow > 0:
        fr.clip_open(viewport, dy)
        fr.scroll = {'container': 'message', 'content_px': round(msg_h + 20, 1), 'viewport_px': r1(viewport),
                     'offset_px': round(dy, 1), 'position': scroll,
                     'reveal': 'the message scrolls; OK stays pinned; TalkBack reads the whole message'}
    y = top + msg_top_pad
    y = fr.para(x + pad + 20, y, title, tsize, t['ink'], inner - 40, 'bold') + 12
    fr.para(x + pad + 20, y, body, bsize, t['ink2'], inner - 40)
    if overflow > 0:
        fr.clip_close()
        # scroll position cue on the message's trailing edge (ink-secondary)
        frac = view_h / (msg_h + 20)
        bar_h = max(60, view_h * frac)
        bar_y = view_top + (view_h - bar_h) * (dy / overflow if overflow else 0)
        fr.rect(x + w - pad / 2 - 6, bar_y, 8, bar_h, t['ink2'], rx=4)
    alert = fr.control('error-message', 'alert', f'{title}. {body}', viewport)
    fr.focus(alert['rect_px'], pad=6)
    by = top + h - 40 - btn_h
    bx = [x + pad, by, inner, btn_h]
    fr.rect(*bx, t['brand_fill'], rx=min(btn_h / 2, 60))
    fr.text(x + pad + inner / 2, by + btn_h / 2 + LABEL * s * 0.36, 'OK', LABEL * s, t['on_brand'], 'bold',
            'middle', maxw=inner - 24)
    fr.control('ok', 'button', 'OK', bx, dismiss=True)
    fr.dismiss = 'OK or system Back returns to the same viewer item; nothing is changed'
    fr.status = {'case': case, 'atlas_owned': True, 'clipboard': clip,
                 'changed': ('unknown for the clipboard; wallpaper and file untouched' if clip.startswith('unknown')
                             else 'nothing (wallpaper, clipboard and file untouched)'),
                 'reads_or_restores_prior_clipboard': False, 'automatic_retry': False,
                 'returns_focus_to': 'same viewer item'}
    return fr


# ---------------------------------------------------------------------------
# Library eligibility variants drawn at 200 % (name part -> case).
MENU_VARIANTS = {
    'onlycopy': elig(wallpaper=False),                # one B/C eligible: Copy image, not wallpaper
    'existingonly': elig(wallpaper=False, copy=False),  # B/C both ineligible: existing actions only
}
# Restricted and video viewers: no More, B/C never offered (case -> name).
VIEWER_CASES = {
    'V-external-still': elig(access='EXTERNAL'),      # readable still, caller-granted URI
    'V-secure-still': elig(access='SECURE'),          # readable still from secure camera review
    'V-library-video': elig(media='video'),           # video: More stays hidden as today
}


def eligibility_matrix():
    """Every access x media x readability x per-action combination and its menu."""
    rows = []
    for access in ACCESS:
        for media in ('still', 'video'):
            for readable in (True, False):
                for ai in (True, False):
                    for swap in (True, False):
                        for wall in (True, False):
                            for cp in (True, False):
                                e = elig(access, media, readable, ai, swap, wall, cp)
                                items = menu_items(e)
                                rows.append({**e, 'items': items, 'more': bool(items)})
    return rows


def build():
    frames = []
    add = frames.append
    full = elig()
    # menu: 100 % baselines
    add(frame_menu_c('BC-menu-light', 'light', 'portrait', 1, full))
    add(frame_menu_c('BC-menu-dark', 'dark', 'portrait', 1, full))
    add(frame_menu_c('BC-menu-light-landscape', 'light', 'landscape', 1, full))
    add(frame_menu_c('BC-menu-dark-landscape', 'dark', 'landscape', 1, full))
    # menu: 200 %
    for theme in ('light', 'dark'):
        add(frame_menu_c(f'BC-menu-{theme}-200', theme, 'portrait', 2, full))
        add(frame_menu_c(f'BC-menu-{theme}-200-landscape', theme, 'landscape', 2, full))
    # eligibility variants at 200 %
    add(frame_menu_c('BC-menu-noswap-light-200', 'light', 'portrait', 2, elig(swap=False)))
    add(frame_menu_c('BC-menu-noaiedit-dark-200', 'dark', 'portrait', 2, elig(ai_edit=False)))
    for var, e in MENU_VARIANTS.items():
        for theme in ('light', 'dark'):
            add(frame_menu_c(f'BC-menu-{var}-{theme}-200', theme, 'portrait', 2, e))
            add(frame_menu_c(f'BC-menu-{var}-{theme}-200-landscape', theme, 'landscape', 2, e))
    # restricted access and video: no More, no B/C
    for name, e in VIEWER_CASES.items():
        add(frame_viewer(name, 'portrait', e))
        add(frame_viewer(name + '-landscape', 'landscape', e))
    # errors
    for stage in ERRORS:
        for theme in ('light', 'dark'):
            add(frame_error(stage, theme, 'portrait', 1))
            if stage == 'unconfirmed':
                add(frame_error(stage, theme, 'landscape', 1))
            add(frame_error(stage, theme, 'portrait', 2))
            add(frame_error(stage, theme, 'landscape', 2))
    # every scrolling surface also gets its scrolled-to-end frame
    for fr in list(frames):
        if fr.scroll and fr.scroll['position'] == 'top':
            if fr.flow == 'BC':
                _, name, theme, orientation, scale, e = fr.args
                end = frame_menu_c(name + '-scrolled', theme, orientation, scale, e, 'end')
                add(end)
                # a tall row that neither end shows whole gets a mid-scroll frame where it is whole
                hidden = {c['id'] for c in fr.controls if c.get('visibility') in ('partial', 'offscreen')}
                missed = [c for c in end.controls if c['id'] in hidden and c.get('visibility') != 'full']
                if missed:
                    vp = end.scroll['viewport_px']
                    cx, cy, cw, ch = missed[0]['content_rect_px']
                    add(frame_menu_c(name + '-scrolled-mid', theme, orientation, scale, e,
                                     cy + ch - (vp[1] + vp[3]) + 1))
            else:
                add(frame_error(fr.stage, fr.theme, fr.orientation, fr.scale, 'end'))
    return frames


def write(frames):
    svg_dir = ROOT / 'svg'
    svg_dir.mkdir(exist_ok=True)
    names = {fr.name for fr in frames}
    if len(names) != len(frames):
        raise SystemExit('duplicate frame names')
    for d, ext in ((svg_dir, 'svg'), (ROOT / 'png', 'png'), (ROOT / 'annotated', 'png')):
        for old in d.glob(f'*.{ext}') if d.exists() else []:
            if old.stem not in names:
                old.unlink()
    for fr in frames:
        (svg_dir / f'{fr.name}.svg').write_text(fr.svg(), encoding='utf-8')
    geometry = {
        'schema': 2, 'version': 'SHARE-1BC-E2 (#1128)',
        'source': 'synthetic coordinates from bc_frames.py; not S24+ runtime measurement',
        'density_px_per_dp': DENSITY, 'min_hit_px': MIN_HIT, 'min_photo_visible': MIN_PHOTO_VISIBLE,
        'palette': PALETTE,
        'menu_limits_rule': {'vertical_margin_dp': 48, 'vertical_margin_from': 'safe window (insets counted once)',
                             'horizontal_min_gap_dp': 8, 'horizontal_containment': 'safe window',
                             'width_dp': [112, 280], 'min_row_dp': 48},
        'access_modes': ACCESS,
        'eligibility_matrix': eligibility_matrix(),
        'activation_rules': ACTIVATION_RULES,
        'frames': [fr.geometry() for fr in frames],
    }
    (ROOT / 'geometry.json').write_text(json.dumps(geometry, indent=1, ensure_ascii=False) + '\n',
                                        encoding='utf-8')
    print(f'Wrote {len(frames)} SVG frames and geometry.json', file=sys.stderr)


# ---------------------------------------------------------------------------
# render, annotate, verify
# ---------------------------------------------------------------------------
NS = '{http://www.w3.org/2000/svg}'


def render_png(svg_bytes):
    import cairosvg
    return cairosvg.svg2png(bytestring=svg_bytes)


def annotate(g, png_bytes):
    """Overlay semantic control rectangles and focus order numbers (review aid only)."""
    im = Image.open(BytesIO(png_bytes)).convert('RGB')
    d = ImageDraw.Draw(im)
    lab = ImageFont.truetype(FONT_FILES['bold'], 40)
    if g['modal_px']:
        x, y, w, h = g['modal_px']
        d.rectangle([x, y, x + w, y + h], outline=(255, 140, 0), width=4)
    if g['scroll']:
        x, y, w, h = g['scroll']['viewport_px']
        d.rectangle([x, y, x + w, y + h], outline=(0, 170, 255), width=3)
    for n, c in enumerate(g['controls'], 1):
        if not c['rect_px']:
            continue
        x, y, w, h = c['rect_px']
        colour = (230, 0, 120) if c.get('visibility') == 'partial' else (0, 190, 80)
        d.rectangle([x, y, x + w, y + h], outline=colour, width=5)
        tag = f'{n} {c["id"]} {w / DENSITY:.0f}x{h / DENSITY:.0f}dp'
        if c.get('activation') == 'reveal-only':
            tag += ' reveal-only'
        tw = d.textlength(tag, font=lab)
        d.rectangle([x + 4, y + 4, x + 16 + tw, y + 52], fill=(0, 0, 0))
        d.text((x + 10, y + 6), tag, font=lab, fill=colour)
    out = BytesIO()
    im.save(out, 'PNG', optimize=True)
    return out.getvalue()


def lum(hex_rgb):
    rgb = [int(hex_rgb[i:i + 2], 16) / 255 for i in (1, 3, 5)]
    c = [v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4 for v in rgb]
    return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]  # guard: allow-public (sRGB luminance weights)


def contrast(a, b):
    q = sorted((lum(a), lum(b)))
    return (q[1] + 0.05) / (q[0] + 0.05)


def hexpx(rgb):
    return '#%02X%02X%02X' % rgb[:3]


def walk(node, art=False, modal=False, viewport=None, dy=0.0):
    for child in node:
        cls = (child.get('class') or '').split()
        a, m, v, d = art or 'art' in cls, modal or 'modal' in cls, viewport, dy
        if child.get('data-viewport'):
            v = [float(n) for n in child.get('data-viewport').split(',')]
        tr = child.get('transform') or ''
        if tr.startswith('translate('):
            d += float(tr[len('translate('):-1].split()[1])
        yield child, a, m, v, d
        yield from walk(child, a, m, v, d)


def inside(r, box, tol=0.5):
    return (r[0] >= box[0] - tol and r[1] >= box[1] - tol and r[0] + r[2] <= box[0] + box[2] + tol
            and r[1] + r[3] <= box[1] + box[3] + tol)


def overlap(a, b):
    return not (a[0] + a[2] <= b[0] or b[0] + b[2] <= a[0] or a[1] + a[3] <= b[1] or b[1] + b[3] <= a[1])


def verify_frame(g, svg, png, bg, palette, errors):
    name = g['file']
    W, H = g['px']
    L, T, R, B = g['insets_ltrb_px']
    safe = [L, T, W - L - R, H - T - B]
    checks = 0
    ratios = []
    sizes = set()
    allowed = {p.upper() for p in palette} | {'NONE'}
    for node, art, modal, viewport, dy in walk(svg):
        tg = node.tag.replace(NS, '')
        if art:
            continue
        for attr in ('fill', 'stroke'):
            v = node.get(attr)
            if v and tg != 'clipPath' and v.upper() not in allowed:
                errors.append(f'{name}: {tg} {attr} {v} is not a Quiet Frame v3 token')
        if tg != 'text':
            continue
        value = ''.join(node.itertext()).strip()
        size = float(node.get('font-size'))
        weight = node.get('font-weight')
        if modal:
            sizes.add(round(size, 2))
        width = font(size, weight).getlength(value)
        if width > float(node.get('data-maxw')) + 1:
            errors.append(f'{name}: {value!r} overflows its box')
        x, y = float(node.get('x')), float(node.get('y')) + dy
        anchor = node.get('text-anchor')
        x0 = x if anchor == 'start' else x - width / 2
        box = [x0, y - size, width, size * 1.2]
        if viewport and not inside(box, viewport, tol=2):
            continue  # scrolled out of view or partly clipped: not drawn as readable text here
        if not inside(box, [0, 0, W, H]):
            errors.append(f'{name}: {value!r} leaves the frame')
            continue
        if g['modal_px'] and not modal and overlap(box, g['modal_px']):
            continue  # viewer label under the opaque modal
        worst = min(contrast(node.get('fill'), hexpx(bg.getpixel((round(x0 + width * q), round(y - size * 0.35)))))
                    for q in (0.1, 0.3, 0.5, 0.7, 0.9))
        ratios.append(round(worst, 2))
        if worst < 4.5:
            errors.append(f'{name}: {value!r} contrast {worst:.2f}:1 < 4.5:1')
    controls = g['controls']
    ids = [c['id'] for c in controls]
    if g['focus_order'] != ids or len(set(ids)) != len(ids):
        errors.append(f'{name}: focus order does not list the active controls exactly once')
    if not g['dismiss']:
        errors.append(f'{name}: no declared dismissal')
    if g['flow'] == 'V':
        checks += verify_viewer(g, controls, ids, safe, errors)
        return checks, ratios, sizes
    if not g['inert_background_controls'] or set(g['inert_background_controls']) & set(ids):
        errors.append(f'{name}: viewer behind the surface is not isolated from focus')
    if not inside(g['modal_px'], safe) and g['flow'] != 'BC':
        errors.append(f'{name}: dialog leaves the safe area')
    visible_tap = []
    for c in controls:
        r = c['rect_px']
        vis = c.get('visibility', 'full')
        if vis == 'offscreen':
            continue
        checks += 1
        if not inside(r, g['modal_px']):
            errors.append(f'{name}/{c["id"]}: outside its surface')
        if g['scroll'] and not inside(r, g['scroll']['viewport_px']) and c['id'] != 'ok' and not c.get('pinned'):
            errors.append(f'{name}/{c["id"]}: visible rect outside the scroll viewport')
        if c['role'] in TAPPABLE and c.get('activation', 'activate') == 'activate' and vis != 'full':
            errors.append(f'{name}/{c["id"]}: a {vis} row must not activate its action')
        if c['role'] in TAPPABLE and vis == 'partial' and c.get('activation') != 'reveal-only':
            errors.append(f'{name}/{c["id"]}: partly shown row lacks reveal-only activation')
        if c['role'] in TAPPABLE and c.get('activation', 'activate') == 'activate':
            checks += 2
            if min(r[2], r[3]) < MIN_HIT - 0.01:
                errors.append(f'{name}/{c["id"]}: {r[2]:.0f}x{r[3]:.0f}px < 48 dp')
            if not inside(r, safe):
                errors.append(f'{name}/{c["id"]}: outside safe insets')
            visible_tap.append(c)
    for i, a in enumerate(visible_tap):
        for b in visible_tap[i + 1:]:
            checks += 1
            if overlap(a['rect_px'], b['rect_px']):
                errors.append(f'{name}: {a["id"]} overlaps {b["id"]}')
    if g['flow'] == 'BC':
        labels = [c['id'] for c in controls if c['role'] == 'menuitem']
        order = [m[0] for m in MENU_ITEMS]
        if labels != [i for i in order if i in labels]:
            errors.append(f'{name}: menu order {labels} breaks AI Edit, Swap person, Set as wallpaper, Copy image')
        e = g['status'].get('eligibility') or {}
        if set(e) != set(ELIG_KEYS):
            errors.append(f'{name}: menu frame does not declare its eligibility case')
        elif labels != menu_items(e) or labels != g['status'].get('expected_items'):
            errors.append(f'{name}: menu items {labels} differ from eligibility {menu_items(e)}')
        if not labels:
            errors.append(f'{name}: More menu drawn with no item')
        if e.get('access') != 'LIBRARY' and labels:
            errors.append(f'{name}: menu offered outside LIBRARY access')
        checks += 1 + verify_menu_limits(g, errors)
        if (g['font_scale'] >= 1.5) != ('menu-identity' in ids):
            errors.append(f'{name}: identity row must appear exactly when font scale >= 1.5')
        ident = next((c for c in controls if c['id'] == 'menu-identity'), None)
        if ident and (ident.get('visibility', 'full') != 'full' or not ident['rect_px']
                      or (g['scroll'] and not ident.get('pinned'))):
            errors.append(f'{name}: identity row is not fully visible and pinned')
        if 'swap-person' in labels and 'ai-edit' not in labels:
            errors.append(f'{name}: Swap person without AI Edit')
    else:
        if controls[0]['role'] != 'alert' or not g['focus_ring_px'] or not inside(controls[0]['rect_px'],
                                                                                   g['focus_ring_px'], 1):
            errors.append(f'{name}: error message does not take focus first')
        if [c['id'] for c in controls if c['role'] in TAPPABLE] != ['ok']:
            errors.append(f'{name}: error offers an action other than OK')
        checks += 2 + verify_error_copy(g, controls, errors)
    checks += 1
    shown_ids = {c['id'] for c in controls if c.get('visibility', 'full') == 'full' and c['rect_px']}
    if g['photo_visible_fraction'] < MIN_PHOTO_VISIBLE and 'menu-identity' not in shown_ids:
        errors.append(f'{name}: photo {g["photo_visible_fraction"]:.0%} visible and no explicit item identity')
    if g['scroll']:
        checks += 1
        hidden = [c['id'] for c in controls if c.get('visibility') in ('partial', 'offscreen')]
        if g['scroll']['position'] == 'top' and g['flow'] == 'BC' and not hidden:
            errors.append(f'{name}: declared scroll but every item is fully visible')
    return checks, ratios, sizes


def verify_menu_limits(g, errors):
    """#1128 finding 3: enforce the stated popup limits on the drawn surface."""
    name, (W, H) = g['file'], g['px']
    L, T, R, B = g['insets_ltrb_px']
    x, y, w, h = g['modal_px']
    tol = 0.5
    found = []
    if not (MENU_MIN_W - tol <= w <= MENU_MAX_W + tol):
        found.append(f'width {w / DP:.1f} dp outside 112-280 dp')
    if y < T + MENU_EDGE_MARGIN - tol:
        found.append(f'top gap {(y - T) / DP:.1f} dp < 48 dp from the safe window')
    if y + h > H - B - MENU_EDGE_MARGIN + tol:
        found.append(f'bottom gap {(H - B - y - h) / DP:.1f} dp < 48 dp from the safe window')
    if x < L + MENU_SIDE_GAP - tol:
        found.append(f'left gap {(x - L) / DP:.1f} dp < 8 dp inside the safe window')
    if x + w > W - R - MENU_SIDE_GAP + tol:
        found.append(f'right gap {(W - R - x - w) / DP:.1f} dp < 8 dp inside the safe window')
    lim = g.get('menu_limits') or {}
    gaps = lim.get('gaps_dp') or {}
    want = {'top': (y - T) / DP, 'bottom': (H - B - y - h) / DP, 'left': (x - L) / DP, 'right': (W - R - x - w) / DP}
    if set(gaps) != set(want) or any(abs(gaps[k] - want[k]) > 0.1 for k in want):
        found.append('menu_limits gaps do not match the drawn surface')
    for c in g['controls']:
        if c['role'] == 'menuitem' and c.get('visibility') == 'full' and c['rect_px'][3] < MIN_HIT - 0.01:
            found.append(f'{c["id"]} row {c["rect_px"][3] / DP:.1f} dp < 48 dp')
    errors.extend(f'{name}: {m}' for m in found)
    return 6


def verify_viewer(g, controls, ids, safe, errors):
    """Restricted/video viewer: no More, no menu, host-wired chrome only."""
    name = g['file']
    e = g['status'].get('eligibility') or {}
    found = []
    if set(e) != set(ELIG_KEYS):
        found.append('viewer frame does not declare its eligibility case')
        e = {}
    if g['modal_px'] or g['inert_background_controls']:
        found.append('restricted/video viewer must have no surface over it')
    if 'more' in ids or any(c['role'] == 'menuitem' for c in controls):
        found.append('More or a menu item is offered')
    if e and menu_items(e):
        found.append(f'eligibility {menu_items(e)} says a menu applies')
    if g['status'].get('more_shown') is not False or g['status'].get('expected_items') != []:
        found.append('status does not record More hidden')
    if e:
        a = ACCESS[e['access']]
        if ('details' in ids) != a['details']:
            found.append(f'Details shown={"details" in ids} but {e["access"]} details={a["details"]}')
        back = next((c for c in controls if c['id'] == 'back'), None)
        if not back or back['label'] != a['back']:
            found.append(f'Back label must be {a["back"]!r}')
        slab = [i for i in ids if i.startswith('slab-')]
        if e['access'] != 'LIBRARY' and (slab or 'filmstrip-current' in ids):
            found.append(f'{e["access"]} shows library chrome {slab or ["filmstrip"]}')
        if e['media'] == 'video' and 'slab-edit' in ids:
            found.append('video shows Edit (PhotoEditorHandoff is still-only)')
    tap = [c for c in controls if c['role'] in TAPPABLE]
    for c in tap:
        r = c['rect_px']
        if min(r[2], r[3]) < MIN_HIT - 0.01:
            found.append(f'{c["id"]} {r[2]:.0f}x{r[3]:.0f}px < 48 dp')
        if not inside(r, safe):
            found.append(f'{c["id"]} outside safe insets')
    for i, a in enumerate(tap):
        for b in tap[i + 1:]:
            if overlap(a['rect_px'], b['rect_px']):
                found.append(f'{a["id"]} overlaps {b["id"]}')
    errors.extend(f'{name}: {m}' for m in found)
    return 6 + 2 * len(tap)


def verify_error_copy(g, controls, errors):
    """#1128 finding 2: copy only claims what is known about the clipboard."""
    name, st = g['file'], g['status']
    msg = controls[0]['label'].lower()
    found = []
    clip = st.get('clipboard', '')
    if clip.startswith('unknown'):
        if any(w in msg for w in UNCHANGED_CLAIMS):
            found.append('unknown clipboard state, but the message asserts it was not changed')
        if 'changed' not in st or not st['changed'].startswith('unknown'):
            found.append('unknown clipboard state recorded as unchanged')
    if any(w in msg for w in SUCCESS_CLAIMS):
        found.append('error message claims success')
    if st.get('reads_or_restores_prior_clipboard') is not False or st.get('automatic_retry') is not False:
        found.append('error reads/restores the prior clipboard or retries automatically')
    if g['stage'] == 'unconfirmed' and controls[0]['label'] != (
            "Couldn't copy image. Atlas couldn't confirm the image was copied. Try again."):
        found.append('generic clipboard failure copy differs from the brief')
    if g['stage'] == 'revoked' and not clip.startswith('unchanged (known'):
        found.append('pre-commit unreadable state must record the clipboard as known unchanged')
    errors.extend(f'{name}: {m}' for m in found)
    return 4


def verify_matrix(data, errors):
    """#1128 finding 1: access x media x per-action eligibility, both ways."""
    stored = data.get('eligibility_matrix') or []
    fresh = eligibility_matrix()
    found = []
    if stored != fresh:
        found.append('geometry eligibility_matrix is stale; rebuild')
    for row in fresh:
        items, tag = row['items'], {k: row[k] for k in ELIG_KEYS}
        if row['access'] != 'LIBRARY' and items:
            found.append(f'{tag}: restricted access offers {items}')
        if row['media'] != 'still' and items:
            found.append(f'{tag}: video offers {items}')
        if row['more'] != bool(items):
            found.append(f'{tag}: More shown with no item, or hidden with one')
        if 'swap-person' in items and items[items.index('swap-person') - 1] != 'ai-edit':
            found.append(f'{tag}: Swap person not directly under AI Edit')
        if not row['readable'] and ({'set-wallpaper', 'copy-image'} & set(items)):
            found.append(f'{tag}: B/C offered for an unreadable URI')
    lib = [r for r in fresh if r['access'] == 'LIBRARY' and r['media'] == 'still' and r['readable']]
    # B and C are independent of each other and of AI Edit
    if not any(r['items'] == ['set-wallpaper'] for r in lib) or not any(r['items'] == ['copy-image'] for r in lib):
        found.append('B and C are not independently eligible')
    if not any(r['items'] == ['ai-edit', 'swap-person'] for r in lib):
        found.append('existing-only menu (no B/C) is not reachable')
    rules = ' '.join(r['when'] + ' ' + r['outcome'] for r in data.get('activation_rules') or [])
    for need in ('access is no longer LIBRARY', 'current item changed', 'C-revoked', 'C-unconfirmed',
                 'B-unsupported', 'B-no-handler', 'no fallback photo', 'no automatic retry'):
        if need not in rules:
            found.append(f'activation rules do not cover {need!r}')
    # rendered coverage: restricted still, video, one-B/C, existing-only
    frames = data['frames']
    def has(pred):
        return any(pred(g['status'].get('eligibility') or {}, g) for g in frames)
    for label, pred in (
            ('EXTERNAL still', lambda e, g: e.get('access') == 'EXTERNAL' and e.get('media') == 'still'),
            ('SECURE still', lambda e, g: e.get('access') == 'SECURE' and e.get('media') == 'still'),
            ('video fallback', lambda e, g: e.get('media') == 'video'),
            ('one B/C eligible at 200 %', lambda e, g: g['font_scale'] == 2 and
             len({'set-wallpaper', 'copy-image'} & set(g['status'].get('expected_items', []))) == 1),
            ('existing-only at 200 %', lambda e, g: g['font_scale'] == 2 and
             g['status'].get('expected_items') == ['ai-edit', 'swap-person'])):
        if not has(pred):
            found.append(f'no rendered frame for {label}')
    for stage in ERRORS:
        for theme in ('light', 'dark'):
            for orient in ('portrait', 'landscape'):
                scales = (1, 2) if stage == 'unconfirmed' else ((1, 2) if orient == 'portrait' else (2,))
                for sc in scales:
                    if not any(g['stage'] == stage and g['theme'] == theme and g['orientation'] == orient
                               and g['font_scale'] == sc for g in frames):
                        found.append(f'no rendered {stage} {theme} {orient} {sc}x frame')
    errors.extend(f'matrix: {m}' for m in found)
    return len(fresh) + 20


def verify_cross(frames, sizes, errors):
    by = {g['file']: g for g in frames}
    checks = 0
    for g in frames:
        if g['font_scale'] != 2:
            continue
        base = g['file'].replace('-200', '').replace('-landscape', '').replace('-scrolled', '').replace('-mid', '')
        for var in ('-noswap', '-noaiedit', *(f'-{v}' for v in MENU_VARIANTS)):
            base = base.replace(var, '')
        if base not in by:
            errors.append(f'{g["file"]}: no 100 % counterpart {base}')
            continue
        checks += 1
        want = {round(2 * v, 2) for v in sizes[base]}
        got = sizes[g['file']]
        if not got <= want or (not g['scroll'] and got != want):
            errors.append(f'{g["file"]}: surface type sizes {sorted(got)} are not exactly 2x {sorted(want)}')
        if g['scroll'] and g['scroll']['position'] == 'top':
            end = by.get(g['file'] + '-scrolled')
            if not end:
                errors.append(f'{g["file"]}: scroll declared but no scrolled-to-end frame')
                continue
            checks += 1
            hidden = {c['id'] for c in g['controls'] if c.get('visibility') in ('partial', 'offscreen')}
            shown_end = {c['id'] for fr in (end, by.get(g['file'] + '-scrolled-mid')) if fr
                         for c in fr['controls'] if c.get('visibility', 'full') == 'full'}
            if not hidden <= shown_end:
                errors.append(f'{g["file"]}: {sorted(hidden - shown_end)} never fully visible')
    return checks


def run(build_flag, render_flag, verify_flag):
    if build_flag:
        write(build())
    data = json.loads((ROOT / 'geometry.json').read_text(encoding='utf-8'))
    frames = data['frames']
    if render_flag:
        for d in ('png', 'annotated'):
            (ROOT / d).mkdir(exist_ok=True)
        for g in frames:
            png = render_png((ROOT / 'svg' / f'{g["file"]}.svg').read_bytes())
            (ROOT / 'png' / f'{g["file"]}.png').write_bytes(png)
            if g['font_scale'] == 2:
                (ROOT / 'annotated' / f'{g["file"]}.png').write_bytes(annotate(g, png))
        print(f'Rendered {len(frames)} PNGs', file=sys.stderr)
    if not verify_flag:
        return 0
    errors, ratios, sizes, checks = [], [], {}, 0
    for g in frames:
        name = g['file']
        src, pp = ROOT / 'svg' / f'{name}.svg', ROOT / 'png' / f'{name}.png'
        if not src.is_file() or not pp.is_file():
            errors.append(f'{name}: missing SVG or PNG')
            continue
        if g['font_scale'] == 2 and not (ROOT / 'annotated' / f'{name}.png').is_file():
            errors.append(f'{name}: missing annotated geometry PNG')
        svg = ET.parse(src).getroot()
        fresh = Image.open(BytesIO(render_png(src.read_bytes()))).convert('RGB')
        with Image.open(pp) as im:
            committed = im.convert('RGB')
        if list(committed.size) != g['px']:
            errors.append(f'{name}: PNG size {committed.size} != {g["px"]}')
            continue
        diff = ImageChops.difference(fresh, committed).convert('L').point(lambda v: 255 if v > 24 else 0)
        if sum(diff.histogram()[255:]) / (g['px'][0] * g['px'][1]) > 0.002:
            errors.append(f'{name}: committed PNG differs from a fresh render')
        backdrop = copy.deepcopy(svg)
        for parent in backdrop.iter():
            for node in list(parent):
                if node.tag.replace(NS, '') == 'text':
                    parent.remove(node)
        bg = Image.open(BytesIO(render_png(ET.tostring(backdrop)))).convert('RGB')
        c, r, s = verify_frame(g, svg, fresh, bg, data['palette'], errors)
        checks += c
        ratios += r
        sizes[name] = s
    cross = verify_cross(frames, sizes, errors)
    matrix = verify_matrix(data, errors)
    try:
        commit = subprocess.run(['git', 'rev-parse', 'HEAD'], cwd=ROOT, capture_output=True, text=True,
                                check=True).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        commit = None
    print(json.dumps({
        'commit': commit, 'frames': len(frames),
        'frames_200': sum(1 for g in frames if g['font_scale'] == 2),
        'landscape': sum(1 for g in frames if g['orientation'] == 'landscape'),
        'controls': sum(len(g['controls']) for g in frames), 'control_checks': checks,
        'scale_and_scroll_checks': cross, 'eligibility_checks': matrix,
        'viewer_frames': sum(1 for g in frames if g['flow'] == 'V'),
        'text_runs': len(ratios),
        'minimum_text_contrast': min(ratios) if ratios else None,
        'minimum_photo_visible': min(g['photo_visible_fraction'] for g in frames),
        'errors': errors,
    }, indent=1))
    return 1 if errors else 0


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    p.add_argument('--build', action='store_true', help='write SVG and geometry.json')
    p.add_argument('--render', action='store_true', help='write PNG and annotated PNG')
    p.add_argument('--verify', action='store_true', help='check SVG, PNG and geometry')
    a = p.parse_args()
    if not (a.build or a.render or a.verify):
        p.print_help()
        sys.exit(2)
    sys.exit(run(a.build, a.render, a.verify))
