#!/usr/bin/env python3
"""Re-render committed synthetic design SVG sources and verify geometry plus PNG exports."""
import argparse
import json
import sys
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent
DENSITY = 2.8125
def contrast(a, b):
    def lum(s):
        rgb=[int(s[i:i+2],16)/255 for i in (1,3,5)]
        c=[v/12.92 if v<=0.04045 else ((v+0.055)/1.055)**2.4 for v in rgb]
        return sum(x*y for x,y in zip(c,(0.2126,0.7152,0.0722)))  # guard: allow-public (synthetic sRGB luminance weights, not coordinates)
    q=sorted((lum(a),lum(b)))
    return (q[1]+0.05)/(q[0]+0.05)

def run(render=False):
    frames=json.loads((ROOT/'geometry.json').read_text())['frames']
    errors=[]
    if render:
        try:
            import cairosvg
        except ImportError as ex:
            raise SystemExit(f"CairoSVG needed for --render: {ex}")
        (ROOT/'png').mkdir(exist_ok=True)
        for g in frames:
            source=ROOT/'svg'/(g['file']+'.svg')
            cairosvg.svg2png(url=str(source),write_to=str(ROOT/'png'/(g['file']+'.png')))
        print(f"Exported {len(frames)} PNG files from committed SVG sources")
    try:
        from PIL import Image
    except ImportError as ex:
        raise SystemExit(f"Pillow needed for --verify: {ex}")
    count=0
    for g in frames:
        name=g['file']
        source=ROOT/'svg'/(name+'.svg')
        target=ROOT/'png'/(name+'.png')
        if not source.is_file() or not target.is_file():
            errors.append(f"{name}: missing committed SVG or PNG");continue
        try:
            svg=ET.parse(source).getroot()
            with Image.open(target) as im:
                got=im.size
                im.verify()
            expected=g['px']
            if list(got)!=expected or [int(svg.attrib['width']),int(svg.attrib['height'])]!=expected:
                errors.append(f"{name}: dimensions {got} not {expected}")
            for key in ('back_hit_px','more_hit_px','filmstrip_hit_px'):
                x,y,w,h=g[key]
                if min(w,h)<48*DENSITY-0.01: errors.append(f"{name}/{key}: <48dp")
                if x<0 or y<0 or x+w>expected[0]+.01 or y+h>expected[1]+.01: errors.append(f"{name}/{key}: frame overflow")
                count+=1
            x,y,w,h=g['photo_px']
            if w<250 or h<250: errors.append(f"{name}: photo not identifiable")
            if g['action_hit_width_px']<48*DENSITY: errors.append(f"{name}: slab slot <48dp")
            count+=2
        except Exception as ex: errors.append(f"{name}: {ex}")
    pairs={'light body':('#0E1030','#FFFFFF'),'light secondary':('#5A5F7A','#FFFFFF'),
        'dark body':('#F2F3F8','#14152A'),'dark secondary':('#9AA0B8','#14152A'),
        'on brand':('#FFFFFF','#3448E0'),'light brand':('#2639C9','#FFFFFF'),
        'dark brand':('#8C9BFF','#14152A')}
    ratios={name:round(contrast(*colors),2) for name,colors in pairs.items()}
    for name,ratio in ratios.items():
        if ratio<4.5: errors.append(f"{name}: contrast {ratio}:1 <4.5:1")
    print(json.dumps({'frames_expected':len(frames),'geometry_checks':count,'contrast_ratios':ratios,'errors':errors},indent=2))
    return 1 if errors else 0

if __name__=='__main__':
    p=argparse.ArgumentParser()
    p.add_argument('--render',action='store_true',help='Write fresh PNGs from editable SVGs')
    p.add_argument('--verify',action='store_true',help='Check SVG, PNG and geometry')
    args=p.parse_args()
    if not (args.render or args.verify):p.print_help();sys.exit(2)
    sys.exit(run(render=args.render))
