# Builds the contact sheets: python3 render/contact_sheet.py frames <light|dark> contact-sheet-<theme>.png 0.3 (needs Pillow).
import sys,glob,os
from PIL import Image,ImageDraw,ImageFont
d,theme,out,scale=sys.argv[1],sys.argv[2],sys.argv[3],float(sys.argv[4])
order=["A-collapsed","B-expanded","C1-keyboard-typing","C2-no-match","D1-whole-library","D2-search-tab","D3-back","S1-no-stories","S2-planning","S3-unavailable-items","S4-large-text","S5-one-handed","S6-playback"]
tw=int(1080*scale); th=int(2340*scale); cols=7; pad=int(24*scale*3)
ims=[Image.open(f"{d}/{n}-{theme}.png").convert("RGB").resize((tw,th)) for n in order]
L=Image.open(f"{d}/L-landscape-keyboard-{theme}.png").convert("RGB"); L=L.resize((int(L.width*scale),int(L.height*scale)))
rows=2; W=cols*tw+(cols+1)*pad; H=rows*(th+pad*2)+L.height+pad*3
bg=(255,255,255) if theme=="light" else (16,16,24)
sheet=Image.new("RGB",(W,H),bg); dr=ImageDraw.Draw(sheet)
try: f=ImageFont.truetype("/usr/share/fonts/opentype/inter/Inter-SemiBold.otf",max(12,int(40*scale)))
except: f=None
fg=(20,20,40) if theme=="light" else (230,230,240)
for i,(n,im) in enumerate(zip(order,ims)):
  r,c=divmod(i,cols); x=pad+c*(tw+pad); y=pad+r*(th+pad*2)
  dr.text((x,y),n,fill=fg,font=f); sheet.paste(im,(x,y+pad))
y=pad+rows*(th+pad*2); dr.text((pad,y),"L-landscape-keyboard",fill=fg,font=f); sheet.paste(L,(pad,y+pad))
sheet.save(out)
