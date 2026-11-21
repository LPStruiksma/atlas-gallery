"""Rule-based baseline parser (English and Dutch). Not a model: a lexicon, a gazetteer and regexes.

Written by the same seat as the query set, so its score is an optimistic bound for rules on this set,
not evidence about unseen phrasing.
"""
from __future__ import annotations

import calendar
import datetime as dt
import re

TAG_LEX = {
    "beach": ["beach", "strand", "strandfoto"], "sea": ["sea", "zee"], "snow": ["snow", "sneeuw"],
    "dog": ["dog", "dogs", "hond", "honden"], "cat": ["cat", "cats", "kat", "katten"],
    "birthday": ["birthday", "verjaardag"], "food": ["food", "eten"], "sunset": ["sunset", "zonsondergang"],
    "mountains": ["mountains", "mountain", "bergen", "berg"], "car": ["car", "cars", "auto", "auto's"],
    "bike": ["bike", "fiets"], "church": ["church", "kerk"], "wedding": ["wedding", "huwelijk"],
    "flowers": ["flowers", "bloemen"], "boat": ["boat", "boot"], "party": ["party", "feest"],
    "screenshot": ["screenshot", "screenshots"], "people": ["people", "mensen", "nobody", "iemand"],
}
MONTHS = {n.lower(): i for i, n in enumerate(calendar.month_name) if n}
MONTHS.update({"januari": 1, "februari": 2, "maart": 3, "april": 4, "mei": 5, "juni": 6, "juli": 7,
               "augustus": 8, "september": 9, "oktober": 10, "november": 11, "december": 12})
SEASONS = {"spring": (3, 5), "lente": (3, 5), "voorjaar": (3, 5), "summer": (6, 8), "zomer": (6, 8),
           "autumn": (9, 11), "fall": (9, 11), "herfst": (9, 11), "winter": (12, 2)}
NEG = {"without", "no", "not", "except", "zonder", "geen", "behalve", "niet"}
QUESTION = re.compile(r"^(who|what|how many|which|when|where|hoeveel|wie|wat|welke|wanneer|waar)\b", re.I)
ACTION = re.compile(r"\b(delete|remove|share|send|verwijder|deel|stuur)\b", re.I)
STOP_CAP = {"i", "the", "photos", "pics", "foto's", "video's", "videos"}


def _last_day(y, m):
    return calendar.monthrange(y, m)[1]


def _iso(y, m, d):
    return f"{y:04d}-{m:02d}-{d:02d}"


def parse(text, ctx):
    ref = dt.date.fromisoformat(ctx["reference_date"])
    out = {"intent": "filter", "people": [], "exclude_people": [], "place": None, "date_from": None,
           "date_to": None, "media": "any", "tags": [], "exclude_tags": [], "unresolved": []}
    low = text.lower().rstrip("?.! ")
    if ACTION.search(low) or (QUESTION.match(low) and not re.search(r"\b(how many|hoeveel)\b", low)):
        out["intent"] = "unsupported"
        return out
    if re.match(r"^(how many|hoeveel)\b", low):
        out["intent"] = "question"
        return out
    words = re.findall(r"[\w'’]+", low)
    # media
    if re.search(r"\b(videos?|video's)\b", low):
        out["media"] = "video"
    # dates (most specific first)
    m = re.search(r"between (\d{4}) and (\d{4})|tussen (\d{4}) en (\d{4})", low)
    consumed_years = False
    if m:
        a, b = [int(x) for x in m.groups() if x]
        out["date_from"], out["date_to"] = _iso(a, 1, 1), _iso(b, 12, 31)
        consumed_years = True
    if not consumed_years:
        mm = re.search(r"\b(" + "|".join(sorted(MONTHS, key=len, reverse=True)) + r")\b(?:\s+(\d{4}))?", low)
        sm = re.search(r"\b(spring|lente|voorjaar|summer|zomer|autumn|fall|herfst|winter)\b", low)
        ym = re.search(r"\b(19|20)\d{2}\b", low)
        if re.search(r"\b(yesterday|gisteren)\b", low):
            d = ref - dt.timedelta(days=1)
            out["date_from"] = out["date_to"] = d.isoformat()
        elif re.search(r"\b(this month|deze maand)\b", low):
            out["date_from"], out["date_to"] = _iso(ref.year, ref.month, 1), _iso(ref.year, ref.month, _last_day(ref.year, ref.month))
        elif re.search(r"\b(last week|vorige week)\b", low):
            mon = ref - dt.timedelta(days=ref.weekday() + 7)
            out["date_from"], out["date_to"] = mon.isoformat(), (mon + dt.timedelta(days=6)).isoformat()
        elif re.search(r"\b(last year|vorig jaar)\b", low) and not sm:
            out["date_from"], out["date_to"] = _iso(ref.year - 1, 1, 1), _iso(ref.year - 1, 12, 31)
        elif (g := re.search(r"\b(two|three|twee|drie) (years ago|jaar geleden)", low)):
            y = ref.year - {"two": 2, "twee": 2, "three": 3, "drie": 3}[g.group(1)]
            out["date_from"], out["date_to"] = _iso(y, 1, 1), _iso(y, 12, 31)
        elif sm and not mm:
            a, b = SEASONS[sm.group(1)]
            if ym:
                y = int(ym.group(0))
            elif re.search(r"\b(last|vorige)\b", low):
                y = ref.year - 1 if a <= ref.month or a > b else ref.year
                y = ref.year - 1 if (a, b) == (6, 8) else y
            else:  # most recent completed season
                y = ref.year if (b if a <= b else 2) < ref.month and a <= b else ref.year - 1
                if a > b:  # winter: Dec of previous year to Feb
                    y = ref.year - 1 if ref.month > 2 else ref.year - 2
            if a > b:
                out["date_from"], out["date_to"] = _iso(y, a, 1), _iso(y + 1, b, _last_day(y + 1, b))
            else:
                out["date_from"], out["date_to"] = _iso(y, a, 1), _iso(y, b, _last_day(y, b))
            if sm.group(1) in ("summer", "zomer") and re.search(r"\b(last|vorige)\b", low):
                out["date_from"], out["date_to"] = _iso(ref.year - 1, 6, 1), _iso(ref.year - 1, 8, 31)
        elif mm:
            mo = MONTHS[mm.group(1)]
            if mm.group(2):
                y = int(mm.group(2))
            else:
                y = ref.year if mo < ref.month else ref.year - 1
            out["date_from"], out["date_to"] = _iso(y, mo, 1), _iso(y, mo, _last_day(y, mo))
        elif ym:
            y = int(ym.group(0))
            out["date_from"], out["date_to"] = _iso(y, 1, 1), _iso(y, 12, 31)
    # negation scope: a negation marker applies to the next recognised entity
    neg_next = False
    tag_hits = []
    for w in words:
        if w in NEG:
            neg_next = True
            continue
        hit = None
        for name in ctx["people"]:
            if w == name.lower():
                hit = ("p", name)
        if not hit:
            for t, forms in TAG_LEX.items():
                if w in forms or w.replace("’", "'") in forms or (t == "beach" and w.endswith("strandfoto's")):
                    hit = ("t", t)
        if hit:
            kind, val = hit
            key = ("exclude_" if neg_next else "") + ("people" if kind == "p" else "tags")
            out[key].append(val)
            neg_next = False
    for key in ("people", "exclude_people", "tags", "exclude_tags"):
        out[key] = sorted(set(out[key]))
    # place via gazetteer
    for canon, aliases in ctx["places"].items():
        if any(re.search(r"\b" + a + r"\b", low) for a in aliases):
            out["place"] = canon
    # unresolved: capitalised unknown word (not first word) or "of my X" / "van mijn X"
    known = {n.lower() for n in ctx["people"]} | {a for al in ctx["places"].values() for a in al}
    for w in re.findall(r"(?<!^)\b([A-Z][a-z]+)\b", text):
        if w.lower() not in known and w.lower() not in MONTHS and w.lower() not in STOP_CAP:
            out["unresolved"].append(w)
    g = re.search(r"\b(?:of my|van mijn)\s+(\w+)", low)
    if g:
        out["unresolved"].append(g.group(1))
    out["unresolved"] = sorted(set(out["unresolved"]))
    return out
