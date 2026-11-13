# Render the committed Android vector XML to SVG (supports the subset used: paths, stroke, linear/radial gradients).
import sys, xml.etree.ElementTree as ET
A='{http://schemas.android.com/apk/res/android}'
def col(c):
    c=c.lstrip('#'); a=int(c[:2],16)/255 if len(c)==8 else 1; return '#'+c[-6:], a
def conv(path, prefix):
    root=ET.parse(path).getroot(); defs=[]; body=[]
    for i,p in enumerate(root.iter('path')):
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
        body.append('<path '+' '.join(f'{k}="{v}"' for k,v in attrs.items())+'/>')
    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108"><defs>{"".join(defs)}</defs>{"".join(body)}</svg>'
import os; R=os.path.join(os.path.dirname(__file__),'../../app/src/main/res/drawable/')
open('repo-bg.svg','w').write(conv(R+'ic_launcher_background.xml','b'))
open('repo-color-fg.svg','w').write(conv(R+'ic_launcher_foreground.xml','f'))
open('repo-mono-fg.svg','w').write(conv(R+'ic_launcher_monochrome.xml','m'))
