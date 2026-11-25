#!/usr/bin/env python3
"""Re-render committed synthetic design SVG sources and verify geometry plus PNG exports."""
import argparse
import copy
from io import BytesIO
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

def rendered_text_contrast(svg, name, errors):
    """Check every SVG text fill against pixels rendered *without* the text.

    This samples the actual background geometry, not a guessed design-token
    pairing. It covers deterministic synthetic SVGs with explicit x/y/fill
    (not transformed text, CSS styles or runtime Android typography).
    """
    import cairosvg
    from PIL import Image
    texts = [node for node in svg.iter() if node.tag.endswith('}text') or node.tag == 'text']
    backdrop = copy.deepcopy(svg)
    for parent in backdrop.iter():
        for node in list(parent):
            if node.tag.endswith('}text') or node.tag == 'text':
                parent.remove(node)
    data = cairosvg.svg2png(bytestring=ET.tostring(backdrop, encoding='utf-8'))
    with Image.open(BytesIO(data)) as image:
        bg = image.convert('RGB')
        ratios = []
        for label in texts:
            value = ''.join(label.itertext()).strip()
            fg = label.get('fill', '')
            if not value:
                continue
            if len(fg) != 7 or not fg.startswith('#'):
                errors.append(f"{name}: unsupported text fill {fg!r} for {value!r}")
                continue
            try:
                x = float(label.attrib['x'])
                y = float(label.attrib['y'])
                size = float(label.attrib['font-size'])
                anchor = label.get('text-anchor', 'start')
                # Sample within a glyph's background, not at the unpainted anchor.
                delta = min(0.75 * size, 48.0)
                if anchor == 'start':
                    x += delta
                elif anchor == 'end':
                    x -= delta
                elif anchor != 'middle':
                    raise ValueError(f"unsupported text-anchor {anchor!r}")
                y -= size * 0.4
                px, py = round(x), round(y)
                if not (0 <= px < bg.width and 0 <= py < bg.height):
                    raise ValueError('sample outside SVG viewport')
                rgb = bg.getpixel((px, py))
                bg_color = '#%02X%02X%02X' % rgb
                ratio = contrast(fg, bg_color)
                ratios.append(round(ratio, 2))
                if ratio < 4.5:
                    errors.append(f"{name}: text {value!r} at ({px},{py}) {fg} on sampled {bg_color} = {ratio:.2f}:1 <4.5:1")
            except (ValueError, KeyError, TypeError) as ex:
                errors.append(f"{name}: text {value!r}: {ex}")
        return ratios


def check_200_percent(frames, errors):
    """Guard the *visible share sheet* text size ratio and reflow hit bounds."""
    def sizes(filename):
        svg = ET.parse(ROOT/'svg'/(filename+'.svg')).getroot()
        result = {}
        for el in svg.iter():
            if el.tag.endswith('}text') or el.tag == 'text':
                value = ''.join(el.itertext()).strip()
                if value:
                    result[value] = max(result.get(value, 0), float(el.get('font-size','0')))
        return result
    base, scaled = sizes('F1-share-light'), sizes('F1-share-200')
    labels = ('Share photo', 'Remove location', 'Off by default', 'Share', 'Back')
    for label in labels:
        if label not in base or label not in scaled or abs(scaled[label] - 2 * base[label]) > 0.01:
            errors.append(f"F1-share-200: {label!r} must have exactly 2x sheet font size")
    frame = next((g for g in frames if g['file'] == 'F1-share-200'), None)
    if not frame:
        errors.append("F1-share-200: geometry frame missing")
        return
    sheet = frame['sheet_px']
    for name in ('switch','share','back'):
        rect = frame.get('share200_hit_px',{}).get(name)
        if rect is None:
            errors.append(f"F1-share-200: {name} hit rectangle missing")
            continue
        x,y,w,h = rect
        sx,sy,sw,sh = sheet
        if min(w,h) < 48*DENSITY - .01:
            errors.append(f"F1-share-200: {name} target <48dp")
        if x < sx or y < sy or x+w > sx+sw or y+h > sy+sh:
            errors.append(f"F1-share-200: {name} target outside sheet")


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
    contrast_checks=0
    contrast_min=100.0
    for g in frames:
        name=g['file']
        source=ROOT/'svg'/(name+'.svg')
        target=ROOT/'png'/(name+'.png')
        if not source.is_file() or not target.is_file():
            errors.append(f"{name}: missing committed SVG or PNG");continue
        try:
            svg=ET.parse(source).getroot()
            ratios=rendered_text_contrast(svg, name, errors)
            contrast_checks+=len(ratios)
            if ratios: contrast_min=min(contrast_min,*ratios)
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
    check_200_percent(frames,errors)
    print(json.dumps({'frames_expected':len(frames),'geometry_checks':count,
                      'svg_text_background_samples':contrast_checks,
                      'minimum_sampled_text_contrast':round(contrast_min,2) if contrast_checks else None,
                      'errors':errors},indent=2))
    return 1 if errors else 0

if __name__=='__main__':
    p=argparse.ArgumentParser()
    p.add_argument('--render',action='store_true',help='Write fresh PNGs from editable SVGs')
    p.add_argument('--verify',action='store_true',help='Check SVG, PNG and geometry')
    args=p.parse_args()
    if not (args.render or args.verify):p.print_help();sys.exit(2)
    sys.exit(run(render=args.render))
