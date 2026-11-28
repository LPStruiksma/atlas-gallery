#!/usr/bin/env python3
"""Static mechanical check: synthetic geometry and coverage, not Android validation."""
from pathlib import Path
from xml.etree import ElementTree as ET
root=Path(__file__).resolve().parent
scenes=["album-date","folder-name","person-size","search-relevance","similar-relevance","stories-random","trash-size","search-empty","state-matrix","ime-transition","font200-direction","font200-shuffle"]
standard=["Date created","Date modified","File name","Size","Random"]
ns="{http://www.w3.org/2000/svg}"
errors=[]
forbidden=("Relevance has no direction", "\u00b7")
def check_copy(text,location):
 for pattern in forbidden:
  if pattern in text:
   errors.append(f"{location}: forbidden UI copy {pattern!r}")
check_copy((root/"generate.mjs").read_text(encoding="utf-8"),"generate.mjs")
for scene in scenes:
 for theme in ("light","dark"):
  path=root/f"{scene}-{theme}.svg"
  try: svg=ET.parse(path).getroot()
  except (OSError,ET.ParseError) as err:
   errors.append(f"{path.name}: {err}")
   continue
  def check(ok,message):
   if not ok: errors.append(f"{path.name}: {message}")
  check_copy(path.read_text(encoding="utf-8"),path.name)
  for t in svg.iter(ns+"text"):
   check_copy("".join(t.itertext()),path.name)
  check(svg.get("width")=="1080" and svg.get("height")=="2340" and svg.get("viewBox")=="0 0 360 780","viewport")
  check(svg.get("data-synthetic")=="true" and svg.get("data-density-illustrative")=="3","synthetic declaration")
  check(svg.get("data-scene")==scene and svg.get("data-theme")==theme,"identity")
  titles=[t for t in svg.iter(ns+"text") if t.get("data-role")=="screen-title"]
  check(len(titles)==1,"single title")
  if titles:
   t=titles[0]
   check("Funnel Display" in t.get("font-family","") and t.get("font-weight")=="700" and t.get("letter-spacing")=="-0.03em","Quiet Frame typography")
   check(t.get("font-size")==("76" if scene.startswith("font200") else "38"),"38sp title at correct scale")
  targets={}
  for r in svg.iter(ns+"rect"):
   k=r.get("data-target")
   if not k:continue
   targets.setdefault(k,[]).append(r)
   x,y,w,h=[float(r.get(z,"0")) for z in ("x","y","width","height")]
   check(w>=48 and h>=48,f"{k} target below 48dp")
   check(x>=0 and y>=24 and x+w<=360 and y+h<=757,f"{k} outside safe area")
   if k in ("sort","key","direction","shuffle"):check(w>=52 and h>=52,f"{k} below 52dp")
  check(len(targets.get("sort",[]))==1,"sort entry")
  keys=[r.get("data-key") for r in targets.get("key",[])]
  if scene in ("album-date","folder-name","person-size","stories-random","trash-size"):
   check(keys==standard,"standard five keys")
  if scene in ("search-relevance","similar-relevance","search-empty"):
   check(keys==["Relevance"]+standard,"results six keys")
   check(svg.get("data-query"),"query retained")
   check([r.get("data-key") for r in targets.get("key",[]) if r.get("data-selected")=="true"]==["Relevance"],"Relevance selected")
   check(not targets.get("direction") and not targets.get("shuffle"),"Relevance extra controls")
  if scene in ("album-date","folder-name","person-size","trash-size","font200-direction"):
   d=targets.get("direction",[])
   check([r.get("data-direction") for r in d]==["Ascending","Descending"],"two stacked directions")
   if len(d)==2:check(float(d[0].get("y"))+float(d[0].get("height"))<=float(d[1].get("y")),"direction overlap")
  if scene in ("stories-random","font200-shuffle"):
   check(len(targets.get("shuffle",[]))==1 and not targets.get("direction"),"Random shuffle only")
  if scene.startswith("font200"):
   check(svg.get("data-font-scale")=="2" and float(svg.get("data-scroll-offset-dp","0"))>0,"scrolled 200%")
   check(all(float(r.get("height"))>=72 for k in ("key","direction","shuffle") for r in targets.get(k,[])),"72dp enlarged targets")
   # Inspect every text element, not merely the display title: no hidden 100% or 125% labels.
   texts=list(svg.iter(ns+"text"))
   required={"screen-title":1,"header-subtitle":1,"sort-entry-label":1,"sheet-title":1,
             "scroll-hint":1,"option-label":4 if scene=="font200-direction" else 1}
   required["direction-heading" if scene=="font200-direction" else "shuffle-label"]=1
   roles=[t.get("data-role") for t in texts]
   check(len(roles)==sum(required.values()) and all(roles.count(role)==count for role,count in required.items()),
         "200% text roles/inventory")
   for t in texts:
    base=t.get("data-base-sp")
    try: scaled=base is not None and float(t.get("font-size","0"))==2*float(base)
    except ValueError: scaled=False
    check(scaled,f"200% font scale: {t.text}")
    try: x,y=float(t.get("x","-1")),float(t.get("y","-1"))
    except ValueError: x,y=-1,-1
    check(0<=x<=360 and 24<=y<=757,f"text baseline outside safe frame: {t.text}")
   def text_in_target(role,target):
    labels=[t for t in texts if t.get("data-role")==role]
    rects=targets.get(target,[])
    if len(labels)!=1 or len(rects)!=1:return False
    t,r=labels[0],rects[0]
    return (float(r.get("x"))<=float(t.get("x"))<=float(r.get("x"))+float(r.get("width")) and
            float(r.get("y"))<=float(t.get("y"))<=float(r.get("y"))+float(r.get("height")))
   check(text_in_target("sort-entry-label","sort"),"enlarged sort label inside its enlarged target")
   if scene=="font200-shuffle":
    check(text_in_target("shuffle-label","shuffle"),"enlarged shuffle label inside its target")
   option_rows=sorted(targets.get("key",[])+targets.get("direction",[]),key=lambda r:float(r.get("y")))
   option_labels=[t for t in texts if t.get("data-role")=="option-label"]
   check(len(option_rows)==len(option_labels),"option text/row count")
   for t,r in zip(option_labels,option_rows):
    check(float(r.get("y"))<=float(t.get("y"))<=float(r.get("y"))+float(r.get("height")),
          f"200% option baseline outside row: {t.text}")
   first_key=min((float(r.get("y")) for r in targets.get("key",[])),default=0)
   scroll_hints=[t for t in texts if t.get("data-role")=="scroll-hint"]
   check(len(scroll_hints)==1 and first_key>=float(scroll_hints[0].get("y"))+12,
         "scrolled hint overlaps first option")
   terminal=(targets.get("direction") or targets.get("shuffle"))[-1]
   bottom=float(terminal.get("y"))+float(terminal.get("height"))
   check(700<=bottom<=757,"last direction/shuffle target visible above gesture inset")
  if scene=="state-matrix":
   check({g.get("data-state") for g in svg.iter(ns+"g") if g.get("data-state")}=={"loading","error","revoked","progressive"},"four pictured states")
   check(len(targets.get("retry",[]))==1 and len(targets.get("grant",[]))==1,"retry/grant controls")
  if scene=="ime-transition":
   check([g.get("data-transition") for g in svg.iter(ns+"g") if g.get("data-transition")]==["keyboard","dismissed","sheet"],"IME sequence")
   check(svg.get("data-query")=="canal","IME query")
  for a,b in zip(targets.get("key",[]),targets.get("key",[])[1:]):
   check(float(a.get("y"))+float(a.get("height"))<=float(b.get("y")),"key rows overlap")
if errors: raise SystemExit("\n".join(errors))
print(f"PASS: {len(scenes)*2} SVGs; XML, geometry, states, typography, controls and UI copy.")
