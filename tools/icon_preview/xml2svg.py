# Render the committed Android vector XML to SVG (supports the subset used: paths, stroke, linear/radial gradients).
import sys, xml.etree.ElementTree as ET
A='{http://schemas.android.com/apk/res/android}'
import os
_COLORS={}
def _load_colors():
    v=os.path.join(os.path.dirname(os.path.abspath(__file__)),'../../app/src/main/res/values/colors.xml')
    if os.path.exists(v):
        for c in ET.parse(v).getroot().iter('color'): _COLORS[c.get('name')]=c.text.strip()
def col(c):
    if c.startswith('@color/'):  # #656: resolve colour resources from res/values/colors.xml
        if not _COLORS: _load_colors()
        c=_COLORS[c[len('@color/'):]]
    c=c.lstrip('#'); a=int(c[:2],16)/255 if len(c)==8 else 1; return '#'+c[-6:], a
def conv(path, prefix):
    root=ET.parse(path).getroot(); defs=[]; body=[]
    # #656: <group> pivot/scale/translate/rotation becomes an SVG transform (nested groups compose).
    def gt(g):
        px,py=float(g.get(A+'pivotX','0')),float(g.get(A+'pivotY','0'))
        sx,sy=g.get(A+'scaleX','1'),g.get(A+'scaleY','1'); tx,ty=g.get(A+'translateX','0'),g.get(A+'translateY','0')
        rot=g.get(A+'rotation','0')
        return f'translate({tx} {ty}) translate({px} {py}) rotate({rot}) scale({sx} {sy}) translate({-px} {-py})'
    paths=[]
    def walk(el, tf):
        for ch in el:
            if ch.tag=='group': walk(ch, tf+[gt(ch)])
            elif ch.tag=='path': paths.append((ch, ' '.join(tf)))
    walk(root, [])
    for i,(p,tf) in enumerate(paths):
        attrs={'d':p.get(A+'pathData')}
        g=p.find('{http://schemas.android.com/aapt}attr/gradient')
        if g is not None:
            gid=f'{prefix}g{i}'; stops=''.join(f'<stop offset="{it.get(A+"offset")}" stop-color="{col(it.get(A+"color"))[0]}" stop-opacity="{col(it.get(A+"color"))[1]:.3f}"/>' for it in g)
            if g.get(A+'type')=='radial': defs.append(f'<radialGradient id="{gid}" gradientUnits="userSpaceOnUse" cx="{g.get(A+"centerX")}" cy="{g.get(A+"centerY")}" r="{g.get(A+"gradientRadius")}">{stops}</radialGradient>')
            else: defs.append(f'<linearGradient id="{gid}" gradientUnits="userSpaceOnUse" x1="{g.get(A+"startX")}" y1="{g.get(A+"startY")}" x2="{g.get(A+"endX")}" y2="{g.get(A+"endY")}">{stops}</linearGradient>')
            attrs['fill']=f'url(#{gid})'
        elif p.get(A+'fillColor'): attrs['fill']=col(p.get(A+'fillColor'))[0]
        else: attrs['fill']='none'
        if p.get(A+'strokeColor'):
            attrs.update({'stroke':col(p.get(A+'strokeColor'))[0],'stroke-width':p.get(A+'strokeWidth'),'stroke-linecap':p.get(A+'strokeLineCap','butt'),'stroke-linejoin':p.get(A+'strokeLineJoin','miter')})
        if tf: attrs['transform']=tf
        body.append('<path '+' '.join(f'{k}="{v}"' for k,v in attrs.items())+'/>')
    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108"><defs>{"".join(defs)}</defs>{"".join(body)}</svg>'
R=os.path.join(os.path.dirname(__file__),'../../app/src/main/res/drawable/')
open('repo-bg.svg','w').write(conv(R+'ic_launcher_background.xml','b'))
open('repo-color-fg.svg','w').write(conv(R+'ic_launcher_foreground.xml','f'))
open('repo-mono-fg.svg','w').write(conv(R+'ic_launcher_monochrome.xml','m'))
