#!/usr/bin/env python3
"""VIDEO-V2P #979: proposed frame-thumbnail seek ("seeker") overlays for #961.

One model produces every number, table and SVG in this folder, so diagrams and
tables cannot drift apart. Run from anywhere (Python 3, no dependencies):

    python3 docs/design/video-v2-scrub/generate.py

Base layer: the proposed Lapis Atlas viewer target of DESIGN-8T
(docs/design/viewer-142-target). Its measured insets and cutouts and its proposed
strip band, video row and slab are imported, not copied, so if #142's target
changes this folder regenerates with it.

Everything drawn here is a PROPOSED target for an unbuilt feature, not a capture.
Samsung behaviour is cited only by research finding ID (VIEW:V15-V18) and only
where README.md marks it OBSERVED; all geometry is an ATLAS choice. Synthetic
media only: the "video" is a generated sun/boat scene whose positions encode the
frame time, so a reviewer can read the time-to-frame mapping off each tile.
"""
import importlib.util
import json
import math
import pathlib

HERE = pathlib.Path(__file__).resolve().parent
BASE_DIR = HERE.parent / "viewer-142-target"
SVG_DIR = HERE / "svg"

spec = importlib.util.spec_from_file_location("t142", BASE_DIR / "generate_target.py")
t142 = importlib.util.module_from_spec(spec)
spec.loader.exec_module(t142)
r2, rect, overlap = t142.r2, t142.rect, t142.overlap

PX_PER_DP = 2.8125  # emulator S24DisplayRule density used by the #142 measurements

# ---- OBSERVED (archived parity research; static analysis of Samsung Gallery 15.9, not runtime) ----
OBS_HOLD_MS = 180            # VIEW:V16 press on the centred video before the seeker expands
OBS_TAIL_MS = 100            # VIEW:V16 frame offsets are (d - 100) / n
OBS_LEAD_MS = 200            # VIEW:V16 while playing, strip position leads playback by 200 ms
OBS_DOUBLE_TAP_MS = 10_000   # VIEW:V18 double-tap seeks +/- 10 s, clamped


def obs_frame_count(duration_s):
    """VIEW:V16: max(3, 7 * log10(duration in s)). Rounding is NOT observed; floor is proposed."""
    if duration_s <= 0:
        return 3
    return max(3, math.floor(7 * math.log10(duration_s)))


# ---- PROPOSED (ATLAS choices; every one is a target the preflight may change) ----
FRAME_W, FRAME_H = t142.TILE_W, t142.TILE_H  # seeker frame tile = strip tile size (44 x 56)
FRAME_GAP = 0.0              # frames are contiguous inside the expanded video (one object)
SEEK_MIN_DURATION_MS = 300   # below this the seeker is not offered (frames would repeat)
PREVIEW_SHARP_MS = 60        # parity §1 photo scrub rule reused: sharp after a 60 ms pause
SETTLE_MS = 180              # ATLAS settle rule (#580 / #961): expensive work after 180 ms still
TICK_MIN_GAP_MS = 16         # at most one haptic tick per vsync
FONT200 = 2.0
LAPIS = "#3448E0"
SPHERE = "#8C9BFF"
MIST_DARK = "#14152A"
INK = "#F2F3F8"
INK2 = "#9AA0B8"


# ---- Synthetic media (the frame time is encoded in the scene) ----
def scene(out, x, y, w, h, frac, tone, clip_id=None, rx=0, burn=None):
    """A generated scene: the sun moves left->right and the boat right->left as frac goes 0->1."""
    sky = "#F5F7FF" if tone == "bright" else "#0E1830"
    ridge = "#6D86B5" if tone == "bright" else "#243457"
    water = "#8CC8E8" if tone == "bright" else "#11263F"
    if clip_id:
        out(f'<clipPath id="{clip_id}"><rect x="{r2(x)}" y="{r2(y)}" width="{r2(w)}" height="{r2(h)}" rx="{rx}"/></clipPath>')
        out(f'<g clip-path="url(#{clip_id})">')
    out(f'<rect x="{r2(x)}" y="{r2(y)}" width="{r2(w)}" height="{r2(h)}" fill="{sky}"/>')
    out(f'<path d="M{r2(x)} {r2(y+h*.56)} L{r2(x+w*.2)} {r2(y+h*.32)} L{r2(x+w*.42)} {r2(y+h*.54)} '
        f'L{r2(x+w*.64)} {r2(y+h*.26)} L{r2(x+w)} {r2(y+h*.5)} V{r2(y+h)} H{r2(x)} Z" fill="{ridge}"/>')
    out(f'<rect x="{r2(x)}" y="{r2(y+h*.6)}" width="{r2(w)}" height="{r2(h*.4)}" fill="{water}"/>')
    out(f'<circle cx="{r2(x+w*(.1+.8*frac))}" cy="{r2(y+h*.2)}" r="{r2(min(w,h)*.08)}" fill="#FFD35A"/>')
    bx = x + w * (.85 - .7 * frac)
    out(f'<path d="M{r2(bx)} {r2(y+h*.72)} l0 {r2(-h*.14)} l{r2(w*.08)} {r2(h*.14)} Z" fill="#FFFFFF" opacity=".92"/>')
    if burn is not None:
        # burned-in synthetic counter, as in generated test video (media content, not chrome)
        fsz = max(6.0, min(w, h) * .2)
        out(f'<rect x="{r2(x+2)}" y="{r2(y+h-fsz-4)}" width="{r2(fsz*.62*len(burn)+4)}" height="{r2(fsz+2)}" fill="#000" opacity=".6"/>')
        out(f'<text x="{r2(x+4)}" y="{r2(y+h-4)}" font-size="{r2(fsz)}" fill="#FFF" font-family="monospace">{burn}</text>')
    if clip_id:
        out('</g>')


def fmt_time(ms):
    s = int(ms // 1000)
    h, m, s = s // 3600, (s // 60) % 60, s % 60
    return f"{h}:{m:02d}:{s:02d}" if h else f"{m}:{s:02d}"


# ---- Seeker model ----
def seeker_model(vp, lay, duration_ms, t_ms, state):
    """Geometry of the expanded seeker for one viewport/time. All values dp."""
    band = lay["elements"]["filmstrip"]["band"]
    safe_l, safe_r = vp["safe"][0], vp["W"] - vp["safe"][2]
    cx = r2((safe_l + safe_r) / 2)           # centre of the safe width = playhead x (as #142 current tile)
    n = obs_frame_count(duration_ms / 1000)
    span_w = n * FRAME_W + (n - 1) * FRAME_GAP
    frac = 0.0 if duration_ms <= 0 else min(1.0, max(0.0, t_ms / duration_ms))
    span_l = cx - frac * span_w              # span scrolls under the fixed playhead
    tile_top = band["top"] + 4 + t142.SPHERE_D + t142.SPHERE_GAP
    frames = []
    usable = max(0, duration_ms - OBS_TAIL_MS)
    for i in range(n):
        l = span_l + i * (FRAME_W + FRAME_GAP)
        frames.append({"i": i, "t_ms": int(i * usable / n), "visible": rect(l, tile_top, l + FRAME_W, tile_top + FRAME_H)})
    sphere = rect(cx - t142.SPHERE_D / 2, band["top"] + 4, cx + t142.SPHERE_D / 2, band["top"] + 4 + t142.SPHERE_D)
    ring = rect(span_l - t142.RING, tile_top - t142.RING, span_l + span_w + t142.RING, tile_top + FRAME_H + t142.RING)
    # neighbours keep the #142 strip pitch outside the span
    neigh = []
    pitch = t142.TILE_W + t142.TILE_GAP
    for k in range(1, 12):
        for side in (-1, 1):
            if side < 0:
                r_ = span_l - t142.TILE_GAP - (k - 1) * pitch
                l_ = r_ - t142.TILE_W
            else:
                l_ = span_l + span_w + t142.TILE_GAP + (k - 1) * pitch
                r_ = l_ + t142.TILE_W
            if r_ > band["left"] - 60 and l_ < band["right"] + 60:
                neigh.append({"side": side, "k": k, "visible": rect(l_, tile_top, r_, tile_top + FRAME_H),
                              "hit": rect(l_ - t142.TILE_GAP / 2, band["top"], r_ + t142.TILE_GAP / 2, band["bottom"])})
    span_px = span_w * PX_PER_DP
    return {
        "state": state, "duration_ms": duration_ms, "t_ms": t_ms, "frame_count": n,
        "span_dp": r2(span_w), "span_px": r2(span_px),
        "ms_per_px": r2(duration_ms / span_px) if span_px else None,
        "playhead_x": cx, "band": band, "drag_hit": rect(band["left"], band["top"], band["right"], band["bottom"]),
        "sphere": sphere, "ring": ring, "frames": frames, "neighbours": neigh,
        "exclusion_rect": rect(band["left"], band["top"], band["right"], band["bottom"]),
    }


def checks(vp, lay, sk):
    W, H = vp["W"], vp["H"]
    s = vp["safe"]
    safe = rect(s[0], s[1], W - s[2], H - s[3])
    hits = [("seeker-drag", sk["drag_hit"])] + [(f"neighbour{n['side']*n['k']}", n["hit"]) for n in sk["neighbours"]
                                                 if n["hit"]["right"] > sk["band"]["left"] and n["hit"]["left"] < sk["band"]["right"]]
    el = lay["elements"]
    others = [(k, el[k]["hit"]) for k in el if isinstance(el[k], dict) and "hit" in el[k] and k != "filmstrip"]
    out = {
        "drag_hit_dp": [sk["drag_hit"]["width"], sk["drag_hit"]["height"]],
        "drag_hit_at_least_48": sk["drag_hit"]["width"] >= 48 and sk["drag_hit"]["height"] >= 48,
        "drag_hit_inside_safeDrawing": (sk["drag_hit"]["left"] >= safe["left"] - .01 and sk["drag_hit"]["right"] <= safe["right"] + .01
                                        and sk["drag_hit"]["top"] >= safe["top"] and sk["drag_hit"]["bottom"] <= safe["bottom"]),
        "drag_hit_over_cutout": any(overlap(sk["drag_hit"], c) for c in vp["cutouts"]),
        "collisions_with_other_hits": [k for k, h in others if overlap(sk["drag_hit"], h)],
        "gap_video_row_to_band_dp": r2(sk["band"]["top"] - el["video-row"]["box"]["bottom"]),
        "gap_band_to_slab_dp": r2(el["action-pill"]["visible"]["top"] - sk["band"]["bottom"]),
        "exclusion_height_dp": sk["exclusion_rect"]["height"],
        "exclusion_within_200dp_cap": sk["exclusion_rect"]["height"] <= 200,
        "playhead_inside_safe": safe["left"] <= sk["playhead_x"] <= safe["right"],
        "visible_neighbour_cells_min_dp": min([h["width"] for k, h in hits[1:]] or [48.0]),
    }
    return out


# ---- SVG helpers ----
def svg_open(w, h, title):
    return [f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {r2(w)} {r2(h)}" width="{r2(w*2)}" height="{r2(h*2)}" '
            f'font-family="Funnel Sans, Inter, Arial, sans-serif"><title>{title}</title>']


def draw_seeker(out, sk, tone, *, frame_state=None, ring=True, keyline=True, prefix="s"):
    band = sk["band"]
    out(f'<clipPath id="{prefix}band"><rect x="{r2(band["left"])}" y="{r2(band["top"]-2)}" width="{r2(band["width"])}" height="{r2(band["height"]+4)}"/></clipPath>')
    out(f'<g clip-path="url(#{prefix}band)">')
    for n in sk["neighbours"]:
        v = n["visible"]
        alt = "bright" if (n["k"] + (n["side"] > 0)) % 2 else "dark"
        scene(out, v["left"], v["top"], v["width"], v["height"], .3, alt, f'{prefix}n{n["side"]}{n["k"]}', 8)
    span = sk["ring"]
    # contiguous frames, clipped to one rounded object
    l0 = sk["frames"][0]["visible"]["left"]
    top = sk["frames"][0]["visible"]["top"]
    out(f'<clipPath id="{prefix}span"><rect x="{r2(l0)}" y="{r2(top)}" width="{r2(sk["span_dp"])}" height="{FRAME_H}" rx="8"/></clipPath>')
    out(f'<g clip-path="url(#{prefix}span)">')
    for f in sk["frames"]:
        v = f["visible"]
        st = (frame_state or {}).get(f["i"])
        if st == "unavailable":
            out(f'<rect x="{r2(v["left"])}" y="{r2(v["top"])}" width="{FRAME_W}" height="{FRAME_H}" fill="{MIST_DARK}"/>')
        elif st == "pending":
            scene(out, v["left"], v["top"], v["width"], v["height"], f["t_ms"] / max(1, sk["duration_ms"]), tone)
            out(f'<rect x="{r2(v["left"])}" y="{r2(v["top"])}" width="{FRAME_W}" height="{FRAME_H}" fill="#000" opacity=".45"/>')
        else:
            scene(out, v["left"], v["top"], v["width"], v["height"], f["t_ms"] / max(1, sk["duration_ms"]), tone, burn=str(f["i"]))
        out(f'<line x1="{r2(v["right"])}" y1="{r2(v["top"])}" x2="{r2(v["right"])}" y2="{r2(v["bottom"])}" stroke="#000" stroke-opacity=".35" stroke-width=".5"/>')
    out('</g>')
    if ring:
        if keyline:
            out(f'<rect x="{r2(span["left"]-1)}" y="{r2(span["top"]-1)}" width="{r2(span["width"]+2)}" height="{r2(span["height"]+2)}" rx="11" fill="none" stroke="#000" stroke-width="1"/>')
        out(f'<rect x="{r2(span["left"]+1)}" y="{r2(span["top"]+1)}" width="{r2(span["width"]-2)}" height="{r2(span["height"]-2)}" rx="9" fill="none" stroke="#FFF" stroke-width="2"/>')
    out('</g>')
    sp = sk["sphere"]
    out(f'<circle cx="{r2(sp["left"]+sp["width"]/2)}" cy="{r2(sp["top"]+sp["height"]/2)}" r="{r2(sp["width"]/2+1)}" fill="#000"/>')
    out(f'<circle cx="{r2(sp["left"]+sp["width"]/2)}" cy="{r2(sp["top"]+sp["height"]/2)}" r="{r2(sp["width"]/2)}" fill="{SPHERE}"/>')


def callout(out, x, y, tx, ty, text, colour="#FFB020"):
    out(f'<line x1="{r2(x)}" y1="{r2(y)}" x2="{r2(tx)}" y2="{r2(ty)}" stroke="{colour}" stroke-width=".8"/>')
    out(f'<circle cx="{r2(x)}" cy="{r2(y)}" r="1.6" fill="{colour}"/>')
    for i, line in enumerate(text.split("\n")):
        out(f'<text x="{r2(tx+3)}" y="{r2(ty+3+i*9)}" font-size="7.2" fill="#E8EAF6">{line}</text>')


def board(vp, theme, state, duration_ms, t_ms, font=1.0, frame_state=None):
    lay = t142.layout(vp, video=True, duration=fmt_time(duration_ms), font=font)
    sk = seeker_model(vp, lay, duration_ms, t_ms, state)
    chk = checks(vp, lay, sk)
    W, H = vp["W"], vp["H"]
    PANEL = 300.0
    tone = "bright" if theme == "light" else "dark"
    out_l = svg_open(W + PANEL, max(H, 520) + 40, f"VIDEO-V2 seeker {vp['name']} {theme} {state}")
    out = out_l.append
    out(f'<rect width="{r2(W+PANEL)}" height="{r2(max(H,520)+40)}" fill="#1B1C24"/>')
    out(f'<text x="8" y="14" font-size="9" fill="#FFF" font-weight="600">PROPOSED · VIDEO-V2 seeker · {vp["name"]} · {theme} theme · {state}</text>')
    out(f'<text x="8" y="27" font-size="7" fill="{INK2}">Target concept, not a capture. Insets/cutout: {vp["source"]}. Base layer: #142 proposed target. Synthetic media.</text>')
    out('<g transform="translate(0,36)">')
    # viewer
    out(f'<rect width="{W}" height="{H}" fill="#000"/>')
    s = vp["safe"]
    # media: fitted 16:9 synthetic video at the seeker time, inside the band above the video row
    media_bottom = lay["elements"]["video-row"]["box"]["top"] - 8
    media_top = s[1] + 56
    mw = W - s[0] - s[2]
    mh = mw * 9 / 16
    if mh > media_bottom - media_top:
        mh = media_bottom - media_top
        mw = mh * 16 / 9
    mx = s[0] + (W - s[0] - s[2] - mw) / 2
    my = media_top + (media_bottom - media_top - mh) / 2
    scene(out, mx, my, mw, mh, t_ms / duration_ms, tone, "media", burn=f"t={t_ms/1000:.3f}s")
    if state == "dragging":
        out(f'<rect x="{r2(mx)}" y="{r2(my)}" width="{r2(mw)}" height="{r2(mh)}" fill="none" stroke="#FFB020" stroke-width="1" stroke-dasharray="3 2"/>')
    # status/nav bars + cutout + safe
    if vp.get("status"):
        st = vp["status"]
        if st[1]:
            out(f'<rect width="{W}" height="{st[1]}" fill="#2D6CDF" opacity=".25"/>')
    nav = vp.get("nav") or [0, 0, 0, 0]
    if nav[3]:
        out(f'<rect y="{r2(H-nav[3])}" width="{W}" height="{nav[3]}" fill="#2D6CDF" opacity=".25"/>')
    if nav[2]:
        out(f'<rect x="{r2(W-nav[2])}" width="{nav[2]}" height="{H}" fill="#2D6CDF" opacity=".25"/>')
    for c in vp["cutouts"]:
        out(f'<rect x="{r2(c["left"])}" y="{r2(c["top"])}" width="{r2(c["width"])}" height="{r2(c["height"])}" fill="#FF3B30" opacity=".55"/>')
    out(f'<rect x="{s[0]}" y="{s[1]}" width="{r2(W-s[0]-s[2])}" height="{r2(H-s[1]-s[3])}" fill="none" stroke="#2EC4B6" stroke-dasharray="4 3" stroke-width=".8"/>')
    el = lay["elements"]
    # back + top group (context, from #142)
    b = el["back"]["visible"]
    out(f'<circle cx="{r2(b["left"]+20)}" cy="{r2(b["top"]+20)}" r="20" fill="{MIST_DARK}" opacity=".8"/>')
    tg = el.get("top-group", {}).get("visible")
    if tg:
        out(f'<rect x="{r2(tg["left"])}" y="{r2(tg["top"])}" width="{r2(tg["width"])}" height="{r2(tg["height"])}" rx="20" fill="{MIST_DARK}" opacity=".8"/>')
    # video row with seek bar mirrored to t
    vr = el["video-row"]["box"]
    sb = el["video-seek"]["track"]
    out(f'<rect x="{r2(sb["left"])}" y="{r2(sb["top"])}" width="{r2(sb["width"])}" height="4" rx="2" fill="#FFF" opacity=".35"/>')
    px = sb["left"] + sb["width"] * t_ms / duration_ms
    out(f'<rect x="{r2(sb["left"])}" y="{r2(sb["top"])}" width="{r2(px-sb["left"])}" height="4" rx="2" fill="{SPHERE}"/>')
    out(f'<circle cx="{r2(px)}" cy="{r2(sb["top"]+2)}" r="8" fill="#FFF"/>')
    pl = el["video-play"]["visible"]
    out(f'<circle cx="{r2(pl["left"]+20)}" cy="{r2(pl["top"]+20)}" r="20" fill="{MIST_DARK}" opacity=".8"/>')
    # paused glyph during scrub (playback paused while held)
    out(f'<path d="M{r2(pl["left"]+15)} {r2(pl["top"]+12)} l12 8 l-12 8 Z" fill="#FFF"/>')
    fs = 14 * font
    ee = el["video-elapsed"]["box"]
    out(f'<text x="{r2(ee["left"])}" y="{r2(ee["top"]+fs)}" font-size="{r2(fs)}" fill="{INK}">{fmt_time(t_ms)}</text>')
    dd = el["video-duration"]["box"]
    out(f'<text x="{r2(dd["right"])}" y="{r2(dd["top"]+fs)}" font-size="{r2(fs)}" fill="{INK2}" text-anchor="end">{fmt_time(duration_ms)}</text>')
    # seeker
    draw_seeker(out, sk, tone, frame_state=frame_state, prefix=f"b{state}")
    # slab
    ap = el["action-pill"]["visible"]
    out(f'<rect x="{r2(ap["left"])}" y="{r2(ap["top"])}" width="{r2(ap["width"])}" height="{r2(ap["height"])}" rx="24" fill="{LAPIS}"/>')
    for k in ("action-favourite", "action-edit", "action-share", "action-delete"):
        h = el[k]["hit"]
        out(f'<circle cx="{r2(h["left"]+24)}" cy="{r2(h["top"]+24)}" r="9" fill="none" stroke="#FFF" stroke-width="2"/>')
    # semantic hits
    dh = sk["drag_hit"]
    out(f'<rect x="{r2(dh["left"])}" y="{r2(dh["top"])}" width="{r2(dh["width"])}" height="{r2(dh["height"])}" fill="none" stroke="#FF2D55" stroke-width=".8" stroke-dasharray="3 2"/>')
    for k in ("video-play", "video-seek", "action-favourite", "action-edit", "action-share", "action-delete", "back"):
        h = el[k]["hit"]
        out(f'<rect x="{r2(h["left"])}" y="{r2(h["top"])}" width="{r2(h["width"])}" height="{r2(h["height"])}" fill="none" stroke="#FF2D55" stroke-width=".5" stroke-dasharray="2 2" opacity=".7"/>')
    out(f'<line x1="{r2(sk["playhead_x"])}" y1="{r2(dh["top"]-6)}" x2="{r2(sk["playhead_x"])}" y2="{r2(dh["top"])}" stroke="#FFB020" stroke-width=".6"/>')
    out('</g>')
    # annotation panel
    X = W + 12
    out(f'<g transform="translate({r2(X)},48)">')
    band = sk["band"]
    lines = [
        ("Seeker (expanded video)", "#FFF"),
        (f"duration {fmt_time(duration_ms)} · N = {sk['frame_count']} frames  [OBSERVED formula VIEW:V16]", INK),
        (f"frame tile {r2(FRAME_W)}×{r2(FRAME_H)} dp, contiguous, span {sk['span_dp']} dp  [PROPOSED]", INK),
        (f"frame times t_i = i·(d−100 ms)/N  [OBSERVED VIEW:V16]", INK),
        (f"playhead x = {sk['playhead_x']} dp (safe centre), time {fmt_time(t_ms)}  [PROPOSED]", INK),
        (f"resolution {sk['ms_per_px']} ms per px of drag  [COMPUTED]", INK),
        ("", INK),
        ("Marks (Quiet Frame v3)", "#FFF"),
        ("2 dp white ring + 1 dp black keyline round the whole video", INK),
        ("8 dp #8C9BFF sphere fixed at the playhead, 4 dp above tiles", INK),
        ("no label, no centre line, no extra chrome (removal list)", INK),
        ("", INK),
        ("Touch (48 dp rule)", "#FFF"),
        (f"one drag target = whole band {chk['drag_hit_dp'][0]}×{chk['drag_hit_dp'][1]} dp", INK),
        (f"≥48: {chk['drag_hit_at_least_48']} · inside safeDrawing: {chk['drag_hit_inside_safeDrawing']}", INK),
        (f"over cutout: {chk['drag_hit_over_cutout']} · collisions: {chk['collisions_with_other_hits'] or 'none'}", INK),
        (f"video row → band {chk['gap_video_row_to_band_dp']} dp · band → slab {chk['gap_band_to_slab_dp']} dp", INK),
        (f"gesture exclusion = band ({chk['exclusion_height_dp']} dp tall ≤ 200 cap)", INK),
        ("neighbour items outside the span keep 48×76 tap cells", INK),
        ("", INK),
        ("safeDrawing assumption (MEASURED, three-button nav)", "#FFF"),
        (f"L/T/R/B = {vp['safe'][0]} / {vp['safe'][1]} / {vp['safe'][2]} / {vp['safe'][3]} dp, rotation {vp['rotation']}", INK),
        ("gesture-nav insets: UNKNOWN (see #142 GESTURE_NAV.md)", "#FFB020"),
        ("", INK),
        ("State", "#FFF"),
    ]
    st_lines = {
        "held": ["180 ms hold on the centred video expanded it in place;",
                 "playhead starts at the current position; playback paused.",
                 "Neighbours slide outward; theme does not change the viewer."],
        "dragging": ["finger drag moves the span under the playhead;",
                     "main view (orange dash) shows the nearest cached frame tile",
                     "until a 60 ms pause, then the decoder frame; elapsed text",
                     "redraws only when the second changes (VIEW:V17)."],
        "unavailable": ["frame 3 and 4 could not be extracted: mist tiles, no glyph,",
                        "no text; seeking still works through the player."],
    }
    for t in st_lines.get(state, []):
        lines.append((t, INK))
    if font != 1.0:
        lines.append((f"font scale {font}: only the video row grows (#142 F3); band unchanged", "#FFB020"))
    for i, (t, c) in enumerate(lines):
        out(f'<text x="0" y="{i*11}" font-size="7.6" fill="{c}" font-weight="{600 if c=="#FFF" else 400}">{t}</text>')
    y0 = len(lines) * 11 + 6
    legend = [("#2EC4B6", "measured safeDrawing"), ("#2D6CDF", "measured system bars"), ("#FF3B30", "measured cutout"),
              ("#FF2D55", "proposed semantic hit"), ("#FFB020", "playhead / preview")]
    for i, (c, t) in enumerate(legend):
        out(f'<rect x="0" y="{y0+i*11-7}" width="9" height="7" fill="{c}" opacity=".8"/><text x="13" y="{y0+i*11}" font-size="7.2" fill="{INK2}">{t}</text>')
    out('</g></svg>')
    return "\n".join(out_l), lay, sk, chk


# ---- States board: one strip-band row per state ----
STATES = [
    ("idle", "Strip as #142: video is one 44×56 tile, ring + sphere on it. Photo strip rules apply."),
    ("armed 0–180 ms", "Finger down on centred video, within touch slop. Nothing moves yet. Moving > slop = normal strip drag."),
    ("expanded (180 ms)", "Hold reached 180 ms [OBSERVED V16]: tile expands in place to N frames over 200 ms Lift; tick once."),
    ("dragging", "Span follows finger 1:1. Main view = nearest cached frame. Tick when playhead crosses a frame boundary."),
    ("pause ≥ 60 ms", "Finger still: decoder frame (CLOSEST_SYNC seek) replaces the cached tile in the main view."),
    ("still ≥ 180 ms", "EXACT seek decodes the frame at T; audio/neighbour work allowed. Nothing in the strip moves."),
    ("fling", "Platform spline decay ×1.0 within the span; preview follows; clamps at 0 / end without bounce; no snap."),
    ("interrupted fling", "Finger down stops the span where it is; that time becomes the new drag origin; no seek is committed."),
    ("release", "Commit T at release (or fling end). Offset, sphere, elapsed text unchanged. Resume if it was playing."),
    ("edge 0:00", "Span's left edge sits under the playhead; further drag is clamped (no pager hand-off while expanded)."),
    ("edge end", "Clamped at the last frame time d−1 frame; same rule."),
    ("frame unavailable", "Mist #14152A tile, no glyph or text. Main view keeps last good frame until the decoder frame lands."),
    ("decoder error", "Hold does not expand; strip stays a photo strip; #142 error state on the page. Mid-drag error: freeze, collapse on release."),
    ("short video < 300 ms", "Seeker not offered; hold does nothing (no tick)."),
    ("4 s video", "N = max(3, ⌊7·log10 4⌋) = 4 frames, span 176 dp."),
    ("1:23:45 video", "N = 25 frames, span 1100 dp; span scrolls past both screen edges."),
    ("TalkBack", "Hold is not used. Video tile gets custom action 'Seek through frames'; adjustable steps of one frame tile."),
    ("reduced motion", "Expand/collapse and preview cross-fade cut; finger-driven drag and fling physics unchanged."),
    ("200% font", "No text in the strip; video row reflows above (#142 F3); band geometry identical."),
]


def states_board(vp):
    lay = t142.layout(vp, video=True)
    band = lay["elements"]["filmstrip"]["band"]
    W = vp["W"]
    row_h = band["height"] + 14
    TEXT = 430
    total_h = 40 + len(STATES) * row_h
    out_l = svg_open(W + TEXT, total_h, f"VIDEO-V2 seeker states {vp['name']}")
    out = out_l.append
    out(f'<rect width="{r2(W+TEXT)}" height="{r2(total_h)}" fill="#1B1C24"/>')
    out(f'<text x="8" y="16" font-size="10" fill="#FFF" font-weight="600">PROPOSED seeker state matrix · {vp["name"]} · strip band at true scale (dp)</text>')
    out(f'<text x="8" y="29" font-size="7.4" fill="{INK2}">Each row is the 76 dp band of the #142 target; left column is the band, right column the rule. Synthetic media; dark theme row tones alternate with light.</text>')
    for idx, (name, rule) in enumerate(STATES):
        y = 40 + idx * row_h
        dy = y - band["top"]
        out(f'<g transform="translate(0,{r2(dy)})">')
        out(f'<rect x="0" y="{r2(band["top"]-6)}" width="{W}" height="{r2(band["height"]+12)}" fill="#000"/>')
        dur, t, fstate, expanded = 30_000, 12_000, None, True
        if name.startswith("idle") or name.startswith("armed") or name.startswith("decoder") or name.startswith("short"):
            expanded = False
        if name.startswith("edge 0"):
            t = 0
        if name.startswith("edge end"):
            t = dur - 33
        if name.startswith("frame unav"):
            fstate = {3: "unavailable", 4: "unavailable"}
        if name.startswith("4 s"):
            dur, t = 4_000, 1_500
        if name.startswith("1:23"):
            dur, t = 5_025_000, 2_010_000
        if name.startswith("expanded"):
            fstate = {i: "pending" for i in range(2, 9)}
        tone = "bright" if idx % 2 else "dark"
        if expanded:
            sk = seeker_model(vp, lay, dur, t, name)
            draw_seeker(out, sk, tone, frame_state=fstate, prefix=f"r{idx}")
            if name in ("dragging", "fling"):
                x = sk["playhead_x"]
                d = -1 if name == "dragging" else -1
                out(f'<path d="M{r2(x+60)} {r2(band["bottom"]-3)} h{-40*d*-1}" stroke="#FFB020" stroke-width="1.2" marker-end="url(#a)"/>')
        else:
            fs = lay["elements"]["filmstrip"]
            for i, tile in enumerate(fs["tiles"]):
                v = tile["visible"]
                tt = "bright" if i % 2 else "dark"
                scene(out, v["left"], v["top"], v["width"], v["height"], .4, tt, f"r{idx}t{i}", 8)
                if name.startswith("decoder") and tile.get("current"):
                    out(f'<rect x="{r2(v["left"])}" y="{r2(v["top"])}" width="{r2(v["width"])}" height="{r2(v["height"])}" rx="8" fill="{MIST_DARK}"/>')
                if tile.get("current"):
                    rg, sp = tile["ring"], tile["sphere"]
                    out(f'<rect x="{r2(rg["left"]-1)}" y="{r2(rg["top"]-1)}" width="{r2(rg["width"]+2)}" height="{r2(rg["height"]+2)}" rx="11" fill="none" stroke="#000"/>')
                    out(f'<rect x="{r2(rg["left"]+1)}" y="{r2(rg["top"]+1)}" width="{r2(rg["width"]-2)}" height="{r2(rg["height"]-2)}" rx="9" fill="none" stroke="#FFF" stroke-width="2"/>')
                    out(f'<circle cx="{r2(sp["left"]+4)}" cy="{r2(sp["top"]+4)}" r="5" fill="#000"/><circle cx="{r2(sp["left"]+4)}" cy="{r2(sp["top"]+4)}" r="4" fill="{SPHERE}"/>')
                    if name.startswith("armed"):
                        out(f'<circle cx="{r2(v["left"]+22)}" cy="{r2(v["top"]+28)}" r="10" fill="#FFF" opacity=".35"/>')
        if name == "TalkBack":
            out(f'<rect x="{r2(band["left"]+1)}" y="{r2(band["top"]+1)}" width="{r2(band["width"]-2)}" height="{r2(band["height"]-2)}" fill="none" stroke="#34C759" stroke-width="2"/>')
        out('</g>')
        out(f'<text x="{r2(W+10)}" y="{r2(y+14)}" font-size="8.4" fill="#FFF" font-weight="600">{idx+1}. {name}</text>')
        words, line, lines = rule.split(), "", []
        for w_ in words:
            if len(line) + len(w_) > 88:
                lines.append(line)
                line = w_
            else:
                line = (line + " " + w_).strip()
        lines.append(line)
        for j, ln in enumerate(lines):
            out(f'<text x="{r2(W+10)}" y="{r2(y+27+j*10)}" font-size="7.4" fill="{INK}">{ln}</text>')
    out('<defs><marker id="a" markerWidth="6" markerHeight="6" refX="5" refY="3" orient="auto"><path d="M0 0 L6 3 L0 6 Z" fill="#FFB020"/></marker></defs>')
    out('</svg>')
    return "\n".join(out_l)


# ---- Timing / seek diagram ----
def timing_svg():
    W, H = 900, 455
    out_l = svg_open(W, H, "VIDEO-V2 seeker timing")
    out = out_l.append
    out(f'<rect width="{W}" height="{H}" fill="#1B1C24"/>')
    out('<text x="12" y="18" font-size="11" fill="#FFF" font-weight="600">PROPOSED seeker timing and seek pipeline (ms; one example gesture)</text>')
    out(f'<text x="12" y="32" font-size="8" fill="{INK2}">OBSERVED values carry a finding ID; everything else is an ATLAS proposal. Media3 behaviour must be proven by the Dev JVM/emulator tests named in README.</text>')
    X0, SC = 110, 0.62   # 1 ms = 0.62 px
    def x(ms):
        return X0 + ms * SC
    lanes = ["finger", "seeker state", "main view", "player seek", "frame cache", "haptic", "TalkBack"]
    for i, ln in enumerate(lanes):
        y = 60 + i * 46
        out(f'<text x="12" y="{y+16}" font-size="9" fill="#FFF">{ln}</text>')
        out(f'<line x1="{X0}" y1="{y+24}" x2="{W-10}" y2="{y+24}" stroke="#3A3C4A"/>')
    for ms in range(0, 1300, 100):
        out(f'<line x1="{r2(x(ms))}" y1="50" x2="{r2(x(ms))}" y2="{60+len(lanes)*46}" stroke="#2A2C38" stroke-dasharray="2 3"/>')
        out(f'<text x="{r2(x(ms))}" y="{70+len(lanes)*46}" font-size="7.5" fill="{INK2}" text-anchor="middle">{ms}</text>')
    def seg(lane, a, b, colour, label):
        y = 60 + lane * 46 + 12
        out(f'<rect x="{r2(x(a))}" y="{y}" width="{r2(x(b)-x(a))}" height="22" rx="4" fill="{colour}" opacity=".85"/>')
        out(f'<text x="{r2(x(a)+4)}" y="{y+14}" font-size="7.4" fill="#FFF">{label}</text>')
    def tick(lane, ms, label, colour="#FFB020"):
        y = 60 + lane * 46 + 12
        out(f'<line x1="{r2(x(ms))}" y1="{y-4}" x2="{r2(x(ms))}" y2="{y+26}" stroke="{colour}" stroke-width="1.6"/>')
        out(f'<text x="{r2(x(ms)+3)}" y="{y-6}" font-size="7.2" fill="{colour}">{label}</text>')
    # finger: down 0, hold to 180, drag 180-560, still 560-820, drag 820-960, up 960
    seg(0, 0, 180, "#4A4E69", "down, within slop")
    seg(0, 180, 560, "#5E60CE", "drag")
    seg(0, 560, 820, "#4A4E69", "still")
    seg(0, 820, 960, "#5E60CE", "drag")
    tick(0, 960, "up (v < min fling)")
    seg(1, 0, 180, "#3A3C4A", "armed")
    tick(1, 180, "expand 180 ms [V16]")
    seg(1, 180, 380, "#2639C9", "200 ms Lift")
    seg(1, 380, 960, LAPIS, "seeking")
    seg(1, 960, 1260, "#2639C9", "committed, stays expanded")
    seg(2, 180, 620, "#6D6875", "nearest cached frame tile")
    tick(2, 620, "+60 ms")
    seg(2, 620, 740, "#457B9D", "sync frame")
    tick(2, 740, "+180 ms")
    seg(2, 740, 820, "#2A9D8F", "exact")
    seg(2, 820, 960, "#6D6875", "cached tile")
    seg(2, 960, 1260, "#2A9D8F", "exact at T_release")
    seg(3, 180, 560, "#3A3C4A", "≤1 CLOSEST_SYNC in flight, newest wins")
    tick(3, 620, "CLOSEST_SYNC(T)")
    tick(3, 740, "EXACT(T)")
    tick(3, 820, "cancel: new target", "#FF5A5F")
    tick(3, 960, "EXACT(T_r) commit")
    seg(4, 180, 520, "#6A4C93", "extract N tiles at t_i (off main)")
    tick(4, 1100, "collapse/page → cancel jobs", "#FF5A5F")
    for ms in (260, 340, 450, 880):
        tick(5, ms, "tick")
    tick(5, 180, "expand")
    tick(6, 1140, "announce once")
    out('<g transform="translate(110,428)">')
    out(f'<text x="0" y="0" font-size="7.8" fill="{INK}">Release invariance: at ACTION_UP the strip offset x_r, target T_r = clamp(map(x_r), 0, d − 1 frame) and the elapsed label are frozen; the commit only replaces the preview image with the decoded frame at T_r.</text>')
    out(f'<text x="0" y="12" font-size="7.8" fill="{INK}">A fling ends with the same rule at its rest offset. No snap to a frame tile: the time axis is continuous; frame tiles are only a visual index.</text>')
    out('</g></svg>')
    return "\n".join(out_l)


# ---- Quantization table ----
def quantization(vps):
    rows = []
    for d_ms in (300, 1_000, 4_000, 30_000, 120_000, 600_000, 5_025_000):
        n = obs_frame_count(d_ms / 1000)
        span_dp = n * FRAME_W
        span_px = span_dp * PX_PER_DP
        mpp = d_ms / span_px
        fps30, fps60 = 1000 / 30, 1000 / 60
        rows.append({
            "duration": fmt_time(d_ms) if d_ms >= 1000 else f"{d_ms} ms", "duration_ms": d_ms, "N": n,
            "span_dp": r2(span_dp), "span_px": r2(span_px), "ms_per_px": r2(mpp), "ms_per_dp": r2(d_ms / span_dp),
            "frame_interval_30fps_ms": r2(fps30), "frame_interval_60fps_ms": r2(fps60),
            "drag_limited_by": "decoder frames" if mpp < fps60 else ("strip pixels" if mpp > fps30 else "both"),
            "tile_interval_ms": r2(max(0, d_ms - OBS_TAIL_MS) / n),
        })
    return rows


def render_md_table(rows, cols):
    lines = ["| " + " | ".join(c for c, _ in cols) + " |", "|" + "---|" * len(cols)]
    for r in rows:
        lines.append("| " + " | ".join(str(r[k]) for _, k in cols) + " |")
    return "\n".join(lines)


def main():
    base = t142.load_baseline()
    vps = t142.viewports(base)
    SVG_DIR.mkdir(exist_ok=True)
    out = {"source": "generate.py (VIDEO-V2P #979)", "observed": {
        "hold_ms": OBS_HOLD_MS, "frame_count": "max(3, 7*log10(d_s)) [rounding not observed; floor proposed]",
        "frame_offsets": "i*(d-100)/n", "playing_lead_ms": OBS_LEAD_MS, "double_tap_ms": OBS_DOUBLE_TAP_MS,
        "source": "private parity research VIEW:V15-V18 (static analysis of Samsung Gallery 15.9.00.45; not runtime)"},
        "proposed": {"frame_tile_dp": [FRAME_W, FRAME_H], "frame_gap_dp": FRAME_GAP, "min_duration_ms": SEEK_MIN_DURATION_MS,
                     "sharp_preview_ms": PREVIEW_SHARP_MS, "settle_ms": SETTLE_MS, "tick_min_gap_ms": TICK_MIN_GAP_MS},
        "boards": [], "quantization": quantization(vps)}
    for vp in vps:
        for theme in ("light", "dark"):
            for state, dur, t, font, fst in (("held", 30_000, 12_000, 1.0, None),
                                              ("dragging", 30_000, 19_500, 1.0, None),
                                              ("unavailable", 30_000, 12_000, 1.0, {3: "unavailable", 4: "unavailable"})):
                if state == "unavailable" and theme == "light":
                    continue
                svg, lay, sk, chk = board(vp, theme, state, dur, t, font, fst)
                name = f'{vp["name"]}--{theme}--{state}.svg'
                (SVG_DIR / name).write_text(svg)
                out["boards"].append({"file": f"svg/{name}", "viewport": vp["name"], "theme": theme, "state": state,
                                      "seeker": {k: sk[k] for k in ("frame_count", "span_dp", "ms_per_px", "playhead_x", "drag_hit", "sphere", "exclusion_rect")},
                                      "checks": chk})
        svg, lay, sk, chk = board(vp, "dark", "dragging", 5_025_000, 2_010_000, FONT200)
        name = f'{vp["name"]}--dark--long-font200.svg'
        (SVG_DIR / name).write_text(svg)
        out["boards"].append({"file": f"svg/{name}", "viewport": vp["name"], "theme": "dark", "state": "long-font200",
                              "seeker": {k: sk[k] for k in ("frame_count", "span_dp", "ms_per_px", "playhead_x", "drag_hit", "sphere", "exclusion_rect")},
                              "checks": chk})
        (SVG_DIR / f'{vp["name"]}--states.svg').write_text(states_board(vp))
    (SVG_DIR / "timing.svg").write_text(timing_svg())
    (HERE / "geometry.json").write_text(json.dumps(out, ensure_ascii=False, indent=1) + "\n")
    q = render_md_table(out["quantization"], [("Duration", "duration"), ("N frames", "N"), ("Span dp", "span_dp"),
                                              ("Span px", "span_px"), ("ms per px", "ms_per_px"), ("Tile interval ms", "tile_interval_ms"),
                                              ("Frame interval 30/60 fps", "frame_interval_30fps_ms"), ("Drag precision limited by", "drag_limited_by")])
    c = ["| Board | ≥48 dp | Inside safeDrawing | Over cutout | Hit collisions | Video row → band | Band → slab | N | ms/px |", "|---|---|---|---|---|---|---|---|---|"]
    for b in out["boards"]:
        k = b["checks"]
        c.append(f'| [{b["file"]}]({b["file"]}) | {k["drag_hit_at_least_48"]} | {k["drag_hit_inside_safeDrawing"]} | {k["drag_hit_over_cutout"]} | '
                 f'{", ".join(k["collisions_with_other_hits"]) or "none"} | {k["gap_video_row_to_band_dp"]} | {k["gap_band_to_slab_dp"]} | {b["seeker"]["frame_count"]} | {b["seeker"]["ms_per_px"]} |')
    (HERE / "tables.md").write_text("# Generated tables (do not edit; run generate.py)\n\n## Q1 Timestamp quantization\n\n" + q +
                                    "\n\nFrame-interval column shows 30 fps; 60 fps is half. ‘decoder frames’ = one px of drag moves less than one 60 fps frame, so precision is the decoder's; ‘strip pixels’ = one px skips more than one 30 fps frame.\n\n"
                                    "## G1 Geometry checks per board\n\n" + "\n".join(c) + "\n")
    print("ok:", len(out["boards"]), "boards,", len(vps), "viewports")


if __name__ == "__main__":
    main()
