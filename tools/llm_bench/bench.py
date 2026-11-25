"""LLM-0 host benchmark core: filter schema, normaliser and scorer.

Host-only and synthetic. Nothing here runs on Android or downloads a model.
"""
from __future__ import annotations

import json
from pathlib import Path

DATA = Path(__file__).resolve().parent / "data"
FIELDS = ["intent", "people", "exclude_people", "place", "date_from", "date_to",
          "media", "tags", "exclude_tags", "unresolved"]
SET_FIELDS = {"people", "exclude_people", "tags", "exclude_tags", "unresolved"}
DEFAULTS = {"intent": "filter", "people": [], "exclude_people": [], "place": None,
            "date_from": None, "date_to": None, "media": "any", "tags": [],
            "exclude_tags": [], "unresolved": []}


def load_context():
    return json.loads((DATA / "context.json").read_text())


def load_queries():
    return json.loads((DATA / "queries.json").read_text())["queries"]


def load_fewshot():
    return json.loads((DATA / "fewshot.json").read_text())["examples"]


def normalise(obj):
    """Fill defaults, lower-case nothing but sort set-like lists. Bad shapes become None (counted wrong)."""
    if not isinstance(obj, dict):
        return None
    out = dict(DEFAULTS)
    for k in FIELDS:
        if k in obj and obj[k] is not None:
            v = obj[k]
            if k in SET_FIELDS:
                if not isinstance(v, list):
                    return None
                out[k] = sorted({str(x) for x in v})
            else:
                out[k] = str(v)
    return out


def score_one(expected, predicted):
    """Per-field correctness. Non-filter intents are scored on intent alone."""
    exp = normalise(expected)
    pred = normalise(predicted)
    if pred is None:
        return {"exact": False, "valid": False, "fields": {f: False for f in FIELDS}}
    if exp["intent"] != "filter":
        ok = pred["intent"] == exp["intent"]
        return {"exact": ok, "valid": True, "fields": {"intent": ok}}
    fields = {f: exp[f] == pred[f] for f in FIELDS}
    return {"exact": all(fields.values()), "valid": True, "fields": fields}


def summarise(queries, predictions):
    """predictions: id -> dict or None. Returns accuracy tables (never invents numbers for missing ids)."""
    rows = []
    for q in queries:
        if q["id"] not in predictions:
            continue
        s = score_one(q["expected"], predictions[q["id"]])
        rows.append((q, s))
    n = len(rows)

    def acc(sel):
        sel = list(sel)
        return {"n": len(sel), "exact": round(sum(s["exact"] for _, s in sel) / len(sel), 3) if sel else None}

    summary = {"n": n, "valid_json": round(sum(s["valid"] for _, s in rows) / n, 3) if n else None,
               "exact": acc(rows)["exact"],
               "by_lang": {l: acc(r for r in rows if r[0]["lang"] == l) for l in ("en", "nl")},
               "by_kind": {}}
    for kind in sorted({q["kind"] for q, _ in rows}):
        summary["by_kind"][kind] = acc(r for r in rows if r[0]["kind"] == kind)
    field_acc = {}
    for f in FIELDS:
        vals = [s["fields"][f] for q, s in rows if f in s["fields"] and q["expected"].get("intent", "filter") == "filter"]
        field_acc[f] = round(sum(vals) / len(vals), 3) if vals else None
    summary["field_accuracy_filter_queries"] = field_acc
    summary["failures"] = [q["id"] for q, s in rows if not s["exact"]]
    return summary
