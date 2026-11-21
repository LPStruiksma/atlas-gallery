# Builds the target contact sheets: python3 render/contact_sheet_target.py frames/target <light|dark> contact-sheet-target-<theme>.png 0.25 (needs Pillow).
# Top row 100 % font, bottom row 200 %; the landscape frame T7 follows underneath.
import sys
from PIL import Image,ImageDraw,ImageFont
d,theme,out,scale=sys.argv[1],sys.argv[2],sys.argv[3],float(sys.argv[4])
order=["T1-collapsed","T2-typing-ime","T3-bottom-results","T3b-bottom-results-ime","T4-no-match-ime","T5-no-match-rest","T6-handoff","T8-arrival","T9-back","TS1-no-stories","TS2-planning","TS3-stale-permission","TS4-playback-exit"]
tw=int(1080*scale); th=int(2340*scale); pad=int(24*scale*3); cols=7
bg=(255,255,255) if theme=="light" else (16,16,24); fg=(20,20,40) if theme=="light" else (230,230,240)
try: f=ImageFont.truetype("/usr/share/fonts/opentype/inter/Inter-SemiBold.otf",max(12,int(40*scale)))
except Exception: f=None
rows=[(s,order[:cols]) for s in (100,200)]+[(s,order[cols:]) for s in (100,200)]
lw,lh=int(2340*scale),int(1080*scale)
W=cols*tw+(cols+1)*pad; H=len(rows)*(th+pad*2)+2*(lh+pad*2)+pad
sheet=Image.new("RGB",(W,H),bg); dr=ImageDraw.Draw(sheet)
for r,(s,names) in enumerate(rows):
  for c,n in enumerate(names):
    x=pad+c*(tw+pad); y=pad+r*(th+pad*2)
    dr.text((x,y),f"{n} · {s} %",fill=fg,font=f)
    sheet.paste(Image.open(f"{d}/{n}-{s}-{theme}.png").convert("RGB").resize((tw,th)),(x,y+pad))
y=pad+len(rows)*(th+pad*2)
for i,s in enumerate((100,200)):
  x=pad+i*(lw+pad); dr.text((x,y),f"T7-landscape-ime · {s} %",fill=fg,font=f)
  sheet.paste(Image.open(f"{d}/T7-landscape-ime-{s}-{theme}.png").convert("RGB").resize((lw,lh)),(x,y+pad))
sheet.save(out)
