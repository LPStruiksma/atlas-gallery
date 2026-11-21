#!/usr/bin/env python3
"""Static mechanical check: synthetic geometry and coverage, not Android validation."""
from pathlib import Path
from xml.etree import ElementTree as ET
root=Path(__file__).resolve().parent
scenes=["album-date","folder-name","person-size","search-relevance","similar-relevance","stories-random","trash-size","search-empty","state-matrix","ime-transition","font200-direction","font200-shuffle"]
standard=["Date created","Date modified","File name","Size","Random"]
ns="{http://www.w3.org/2000/svg}"
errors=[]
for scene in scenes:
 for theme in ("light","dark"):
  path=root/f"{scene}-{theme}.svg"
  try: svg=ET.parse(path).getroot()
  except (OSError,ET.ParseError) as err:
   errors.append(f"{path.name}: {err}")
   continue
  def check(ok,message):
   if not ok: errors.append(f"{path.name}: {message}")
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
  if scene=="state-matrix":
   check({g.get("data-state") for g in svg.iter(ns+"g") if g.get("data-state")}=={"loading","error","revoked","progressive"},"four pictured states")
   check(len(targets.get("retry",[]))==1 and len(targets.get("grant",[]))==1,"retry/grant controls")
  if scene=="ime-transition":
   check([g.get("data-transition") for g in svg.iter(ns+"g") if g.get("data-transition")]==["keyboard","dismissed","sheet"],"IME sequence")
   check(svg.get("data-query")=="canal","IME query")
  for a,b in zip(targets.get("key",[]),targets.get("key",[])[1:]):
   check(float(a.get("y"))+float(a.get("height"))<=float(b.get("y")),"key rows overlap")
if errors: raise SystemExit("\n".join(errors))
print(f"PASS: {len(scenes)*2} SVGs; XML, geometry, states, typography and controls.")
