#!/usr/bin/env python3
"""Deterministically draw the SAVED-1 design-evidence board (fictional media only)."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont, __version__ as PILLOW_VERSION

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "docs/design/saved-1/saved-1-search-albums.png"
MANIFEST = ROOT / "docs/design/saved-1/manifest.json"
W, H = 2520, 6900
PHONE = (1080, 2340)
FONT = Path("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf")
FONT_BOLD = Path("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf")


def font(size: int, bold: bool = False):
    return ImageFont.truetype(str(FONT_BOLD if bold else FONT), size)


def text(d, xy, value, size=28, fill="#0E1030", bold=False, anchor=None):
    d.text(xy, value, font=font(size, bold), fill=fill, anchor=anchor)


def media(d, box, seed):
    """Fictional, unequal-aspect silhouette; intentionally not a photograph."""
    x0, y0, x1, y1 = box
    colors = ["#B9C6E8", "#9AC9C3", "#D7B6CE", "#D8C59A"]
    d.rounded_rectangle(box, 16, fill=colors[seed % 4])
    d.polygon([(x0, y1), (x0+(x1-x0)*.36, y0+(y1-y0)*.42),
               (x0+(x1-x0)*.6, y0+(y1-y0)*.7), (x1, y0+(y1-y0)*.25), (x1, y1)],
              fill="#53658F")
    d.ellipse((x0+18, y0+18, x0+54, y0+54), fill="#F4E8B2")


def phone(d, origin, dark=False, large=False):
    x, y = origin; ink = "#F2F3F8" if dark else "#0E1030"; ground = "#000000" if dark else "#FFFFFF"
    surf = "#14152A" if dark else "#F2F3F8"; brand = "#3448E0" if dark else "#2639C9"
    d.rectangle((x, y, x+1080, y+2340), fill=ground, outline="#FF4F81", width=4)
    text(d, (x+54,y+42), "9:41", 34 if not large else 52, ink, True)
    text(d, (x+54,y+126), "Search", 72 if not large else 112, "#8C9BFF" if dark else brand, True)
    text(d, (x+54,y+250), "November · fictional shape media", 30 if not large else 52, ink)
    ratios=[(300,250),(420,250),(250,330),(470,330),(340,280),(380,280)]
    cy=y+330; i=0
    for row in range(3):
        cx=x+42
        for _ in range(2):
            ww,hh=ratios[i]; media(d,(cx,cy,cx+ww,cy+hh),i+(4 if dark else 0)); cx+=ww+8; i+=1
        cy += max(ratios[i-1][1], ratios[i-2][1])+8
    # Expanded search and chips, all annotated target rectangles are >=135 px = 48 dp.
    sy=y+1420
    d.rounded_rectangle((x+42,sy,x+1038,sy+650),55,fill=surf)
    text(d,(x+78,sy+40),"Search library",44 if not large else 66,ink,True)
    chips=["Mountains","2026","Not: Screenshots","Whole library"]
    yy=sy+130
    for i,label in enumerate(chips):
        bx=x+78+(i%2)*465; by=yy+(i//2)*150
        d.rounded_rectangle((bx,by,bx+430,by+135),68,fill=brand)
        text(d,(bx+215,by+67),label,28 if not large else 40,"#FFFFFF",True,anchor="mm")
    text(d,(x+78,sy+450),"⋮  Save as album",38 if not large else 52,ink,True)
    # slab
    d.rounded_rectangle((x+120,y+2170,x+960,y+2310),68,fill=brand)
    d.rounded_rectangle((x+145,y+2182,x+440,y+2298),58,fill="#FFFFFF")
    text(d,(x+292,y+2240),"Pictures",30,brand,True,anchor="mm")
    text(d,(x+610,y+2240),"Collections",30,"#FFFFFF",True,anchor="mm")
    d.ellipse((x+810,y+2182,x+926,y+2298),fill="#FFFFFF")
    text(d,(x+868,y+2240),"⌕",48,brand,True,anchor="mm")
    text(d,(x+12,y+2320),"1080 × 2340 px · 384 × 832 dp · 2.8125 px/dp",22,"#FF4F81")


PANELS = {
 "A": "Keyboard / Not chip / scope / Save as album\nBack dismisses IME; query remains",
 "B": "Save sheet · query summary · editable name\nDuplicate name / failed save / Cancel / Save",
 "C": "Albums: real-shape strips · Saved searches\n0 assets / 1–2 assets / persistent Unsorted",
 "D": "Saved album grid · Sort / shared Search\nEdit / rename / remove without deleting media",
 "E": "Include / Exclude → Not chip · zero matches\nClear exclusion · OR family / AND families / subtract exclusions",
 "F": "Unsorted: no user-managed album\nAdd to album removes membership; file does not move",
 "G": "Indexing held sphere / unavailable + Retry / 10k+\n200% long name / TalkBack order / RTL / reduced motion",
}


def panel(d, box, pid, dark):
    x0,y0,x1,y1=box; ground="#000000" if dark else "#FFFFFF"; ink="#F2F3F8" if dark else "#0E1030"
    d.rounded_rectangle(box,28,fill=ground,outline="#19A7A0",width=5)
    text(d,(x0+24,y0+18),f"{pid} · {'dark' if dark else 'light'} · 42% scale",30,"#19A7A0",True)
    lines=PANELS[pid].split("\n")
    for n,line in enumerate(lines): text(d,(x0+28,y0+72+n*42),line,25,ink,n==0)
    media(d,(x0+28,y0+170,x0+235,y1-28),ord(pid))
    media(d,(x0+244,y0+190,x0+420,y1-28),ord(pid)+1)
    d.rounded_rectangle((x1-170,y1-164,x1-30,y1-24),70,outline="#FF4F81",width=5)
    text(d,(x1-100,y1-94),"≥48dp",23,"#FF4F81",True,anchor="mm")


def build(path=OUT):
    im=Image.new("RGB",(W,H),"#E9EBF4"); d=ImageDraw.Draw(im)
    text(d,(60,35),"SAVED-1 · annotated synthetic save / exclude / Unsorted board",52,"#2639C9",True)
    text(d,(60,105),"DESIGN EVIDENCE — not a runtime or device screenshot",30,"#8A2447",True)
    phone(d,(60,170),False,False); phone(d,(1380,170),True,True)
    text(d,(60,2535),"Assumptions: S24+ canvas; status 29dp; gesture 16dp; IME 324dp; verify on device.",27,"#8A2447",True)
    text(d,(60,2580),"Pink = geometry / ≥48dp target annotation. Teal = panel boundary. Media are fictional real-ratio shapes.",27,"#8A2447")
    y=2640
    for pid in "ABCDEFG":
        panel(d,(60,y,1230,y+540),pid,False); panel(d,(1290,y,2460,y+540),pid,True); y+=590
    text(d,(60,6800),"Reference decision: Quiet Frame v3 placement and palette; SAVED-1 rev-1 behavior; no copied third-party UI.",25,"#0E1030")
    path.parent.mkdir(parents=True,exist_ok=True)
    im.save(path,format="PNG",optimize=False,compress_level=9)
    return im


def main():
    p=argparse.ArgumentParser(); p.add_argument("--output",type=Path,default=OUT); args=p.parse_args()
    im=build(args.output); digest=hashlib.sha256(args.output.read_bytes()).hexdigest()
    print(f"{args.output}: {im.width}x{im.height} sha256={digest} bytes={args.output.stat().st_size} Pillow={PILLOW_VERSION}")

if __name__ == "__main__": main()
