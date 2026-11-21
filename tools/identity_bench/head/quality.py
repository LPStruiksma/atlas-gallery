"""IDS-12A full-head quality evaluator (#772). Standard library only, offline, deterministic.

Implements the pre-registered rubric in docs/identity-studio/HEAD_ACCEPTANCE.md
(thresholds fixed at commit 096f0796). It scores *records*; it never runs a model, never
downloads anything and never claims measured backend quality. Synthetic passing records
prove the evaluator, not a head-swap backend.

    python3 -m unittest tools.identity_bench.head.test_quality
    python3 tools/identity_bench/head/quality.py COHORT.json ROWS.json [--json OUT.json]

Verdicts are PASS, FAIL or INSUFFICIENT_EVIDENCE. Anything missing, malformed, non-finite,
contradictory or unlicensed is never a pass; the overall verdict is PASS only when the
cohort/provenance checks and all five gates are PASS.
"""
from __future__ import annotations

import hashlib
import json
import math
import re
import statistics
from fractions import Fraction
import sys
from collections import Counter

RUBRIC_REVISION = "IDS-12A@096f0796"

# Pre-registered thresholds. Do not change after seeing results (HEAD_ACCEPTANCE.md).
MIN_PAIRS = 20
MIN_PROFILE_PAIRS = 4
MIN_HAIR_SHAPES = 2
WHOLE_HEAD_POSE_MIN = 0.90
SCALE_MEDIAN_MAX = 0.10
_SCALE_CUTOFF = Fraction(1, 10)  # the same frozen cutoff, exact; no tolerance is added
JUDGE_PREFER_MIN = 0.70
EXPECTED_JUDGES = 2

PASS, FAIL, INSUFFICIENT = "PASS", "FAIL", "INSUFFICIENT_EVIDENCE"
CLASSES = ("full_head", "face_only", "target_head_remains", "no_swap", "failed")
PROFILE_TAGS = ("profile", "three_quarter")
VOTES = ("candidate", "baseline", "tie", "abstain")
_SHA = re.compile(r"^[0-9a-f]{64}$")


# ---------------------------------------------------------------- small helpers

def _text(v):
    return isinstance(v, str) and bool(v.strip())


def _num(v):
    """A finite real number. Huge JSON integers (beyond float range) are not finite evidence."""
    if not isinstance(v, (int, float)) or isinstance(v, bool):
        return False
    try:
        return math.isfinite(v)
    except OverflowError:
        return False


def _digest_ok(v):
    """A SHA-256 hex string, or an explicit {'not_applicable': reason}."""
    if isinstance(v, str):
        return bool(_SHA.match(v))
    return isinstance(v, dict) and _text(v.get("not_applicable"))


def measured(row, name):
    """Return (state, value). state: 'ok' | 'not_measured' | 'invalid'.

    A field is {"status": "measured", "value": ..., "method": "..."} or
    {"status": "not_measured", "reason": "..."}. Anything else is invalid.
    """
    f = row.get(name) if isinstance(row, dict) else None
    if not isinstance(f, dict):
        return "invalid", None
    if f.get("status") == "measured" and _text(f.get("method")) and "value" in f:
        return "ok", f["value"]
    if f.get("status") == "not_measured" and _text(f.get("reason")):
        return "not_measured", None
    return "invalid", None


def sha256_hex(data):
    return hashlib.sha256(bytes(data)).hexdigest()


def check_outside_fence(target, output, fence):
    """Exact integer comparison outside the fence. `fence` is 1 inside / 0 outside.

    Returns the value dict a row stores under `outside_fence`. The fence hash is the
    SHA-256 of the fence bytes, so it can be compared with the pre-declared hash.
    All-frame, empty-outside, shape-mismatched or non-integer input is never equal.
    """
    base = {"equal": False, "outside_pixels": 0, "fence_hash": None}
    try:
        t, o, m = list(target), list(output), list(fence)
        if not (len(t) == len(o) == len(m)) or not t:
            return base
        if any(isinstance(x, bool) or not isinstance(x, int) for x in t + o):
            return base
        if any(x not in (0, 1) for x in m):
            return base
        base["fence_hash"] = sha256_hex(m)
    except (TypeError, ValueError):
        return base
    outside = [i for i, x in enumerate(m) if x == 0]
    base["outside_pixels"] = len(outside)
    base["equal"] = bool(outside) and all(t[i] == o[i] for i in outside)
    return base


# ---------------------------------------------------------------- cohort checks

def check_cohort(cohort):
    """Pre-registration checks. Returns (status, problems)."""
    problems = []
    if not isinstance(cohort, dict) or not isinstance(cohort.get("pairs"), list):
        return INSUFFICIENT, ["cohort missing or has no pairs list"]
    if cohort.get("rubric_revision") != RUBRIC_REVISION:
        problems.append(f"rubric revision {cohort.get('rubric_revision')!r} is not {RUBRIC_REVISION!r}")
    pairs = cohort["pairs"]
    if len(pairs) < MIN_PAIRS:
        problems.append(f"N={len(pairs)} < {MIN_PAIRS} predeclared pairs")
    ids = [p.get("pair_id") if isinstance(p, dict) else None for p in pairs]
    if not all(_text(i) for i in ids):
        problems.append("a pair has no pair_id")
    dup = [i for i, n in Counter(i for i in ids if _text(i)).items() if n > 1]  # hash only validated strings
    if dup:
        problems.append(f"duplicate pair_id: {sorted(map(str, dup))}")
    combos = Counter((p["target_id"], p["reference_id"]) for p in pairs
                     if isinstance(p, dict) and _text(p.get("target_id")) and _text(p.get("reference_id")))
    if any(n > 1 for n in combos.values()):
        problems.append("the same target/reference combination is repeated; repeats are not new pairs")
    engines = set()
    for p in pairs:
        if not isinstance(p, dict):
            problems.append("a pair is not an object")
            continue
        pid = p.get("pair_id")
        for k in ("target_id", "reference_id", "baseline_id", "engine", "pose_tag"):
            if not _text(p.get(k)):
                problems.append(f"{pid}: missing {k}")
        if not _num(p.get("seed")) and not _text(p.get("seed")):
            problems.append(f"{pid}: missing seed")
        for k in ("model_sha256", "adapter_sha256"):
            if not _digest_ok(p.get(k)):
                problems.append(f"{pid}: {k} must be a SHA-256 or an explicit not_applicable reason")
        if not (isinstance(p.get("fence_hash"), str) and _SHA.match(p["fence_hash"])):
            problems.append(f"{pid}: missing predeclared fence_hash")
        lic = p.get("licence")
        if not (isinstance(lic, dict) and lic.get("cleared") is True and _text(lic.get("source"))
                and _text(lic.get("licence")) and _text(lic.get("permitted_use"))):
            problems.append(f"{pid}: licence evidence missing, incomplete or not cleared")
        engines.add((json.dumps(p.get("engine"), sort_keys=True), json.dumps(p.get("model_sha256"), sort_keys=True),
                     json.dumps(p.get("adapter_sha256"), sort_keys=True)))
    if len(engines) > 1:
        problems.append("candidate engine/model/adapter is not one locked value across the cohort")
    ok_pairs = [p for p in pairs if isinstance(p, dict)]
    if sum(1 for p in ok_pairs if p.get("pose_tag") in PROFILE_TAGS) < MIN_PROFILE_PAIRS:
        problems.append(f"fewer than {MIN_PROFILE_PAIRS} profile/three-quarter targets")
    if len({p.get("hair_shape") for p in ok_pairs if _text(p.get("hair_shape"))}) < MIN_HAIR_SHAPES:
        problems.append(f"fewer than {MIN_HAIR_SHAPES} distinct reference hair shapes")
    if not any(p.get("hairline_change") is True for p in ok_pairs):
        problems.append("no pair where the hairline must change")
    if not any(p.get("hard_boundary") is True for p in ok_pairs):
        problems.append("no pair with a difficult neck/background boundary")
    return (INSUFFICIENT if problems else PASS), problems


# ---------------------------------------------------------------- per-gate evaluation

def _gate(name, status, **detail):
    return {"gate": name, "status": status, **detail}


def check_row_provenance(pair, row):
    """Row-level mandatory evidence: licence proof bound to the frozen cohort record, and an
    output-mask digest with its method. Absent, malformed or mismatching evidence fails closed."""
    pid, probs = pair["pair_id"], []
    s, v = measured(row, "licence_evidence")
    lic = pair.get("licence")
    if s != "ok" or not isinstance(v, dict) or v.get("cleared") is not True or not isinstance(lic, dict) \
            or any(not _text(v.get(k)) or v.get(k) != lic.get(k) for k in ("source", "licence", "permitted_use")):
        probs.append(f"{pid}: row licence_evidence missing, not cleared, or not matching the frozen cohort licence")
    s, v = measured(row, "output_mask_hash")
    if s != "ok" or not (isinstance(v, str) and _SHA.match(v)):
        probs.append(f"{pid}: output_mask_hash missing or not a SHA-256 with a method")
    return probs


def _index_rows(cohort_pairs, rows):
    by_id, extra = {}, []
    for r in rows if isinstance(rows, list) else []:
        pid = r.get("pair_id") if isinstance(r, dict) else None
        planned = {p.get("pair_id") for p in cohort_pairs if isinstance(p, dict) and _text(p.get("pair_id"))}
        if _text(pid) and pid in planned and pid not in by_id:
            by_id[pid] = r
        else:
            extra.append(pid if _text(pid) else repr(pid))  # unplanned, duplicate or id-less row: contradicts the frozen cohort
    return by_id, extra


def gate_whole_head_and_pose(pairs, by_id):
    n = len(pairs)
    passed, incomplete, contradictions, details = [], [], [], {}
    for p in pairs:
        pid = p["pair_id"]
        r = by_id.get(pid)
        if r is None:
            incomplete.append(pid)
            details[pid] = "no row"
            continue
        cs, cv = measured(r, "classification")
        ps, pv = measured(r, "target_pose_preserved")
        cls_ok = cs == "ok" and cv in CLASSES
        pose_ok = ps == "ok" and isinstance(pv, bool)
        if not cls_ok or not pose_ok:
            incomplete.append(pid)
            details[pid] = "classification or target-pose not measured/valid"
        ss, sv = measured(r, "claimed_success")
        if ss != "ok" or not isinstance(sv, bool):
            # The zero-false-success condition cannot be verified without an explicit per-row state.
            incomplete.append(pid)
            details[pid] = "claimed_success (explicit success/publication state) not measured or not boolean"
        elif sv and not (cls_ok and cv == "full_head" and pose_ok and pv):
            contradictions.append(pid)
            details[pid] = f"labelled success but classification={cv!r}, target_pose_preserved={pv!r}"
        if cls_ok and pose_ok and cv == "full_head" and pv and pid not in contradictions and pid not in incomplete:
            passed.append(pid)
    frac = len(passed) / n if n else 0.0
    if contradictions:
        status = FAIL
    elif frac >= WHOLE_HEAD_POSE_MIN and not incomplete:
        status = PASS
    else:
        status = FAIL if not incomplete else INSUFFICIENT
    return _gate("whole_head_and_pose", status, passed=len(passed), denominator=n, fraction=frac,
                 threshold=WHOLE_HEAD_POSE_MIN, not_passed=sorted(set(map(str, details)) | set(incomplete)),
                 contradictions=contradictions, incomplete=incomplete, notes=details)


def gate_edit_fence(pairs, by_id):
    bad, incomplete = [], []
    for p in pairs:
        r = by_id.get(p["pair_id"])
        if r is None:
            incomplete.append(p["pair_id"])
            continue
        s, v = measured(r, "outside_fence")
        if s != "ok" or not isinstance(v, dict) or not isinstance(v.get("equal"), bool) \
                or not isinstance(v.get("outside_pixels"), int) or isinstance(v.get("outside_pixels"), bool):
            incomplete.append(p["pair_id"])
        elif not v["equal"] or v["outside_pixels"] <= 0 or v.get("fence_hash") != p.get("fence_hash"):
            bad.append(p["pair_id"])  # changed pixels, empty outside, or fence differs from the predeclared one
    status = FAIL if bad else (INSUFFICIENT if incomplete else PASS)
    return _gate("edit_fence", status, failing=bad, incomplete=incomplete, denominator=len(pairs))


def gate_scale(pairs, by_id):
    errs, incomplete = [], []
    for p in pairs:
        r = by_id.get(p["pair_id"])
        if r is None:
            incomplete.append(p["pair_id"])
            continue
        ts, tv = measured(r, "target_head_span")
        rs, rv = measured(r, "result_head_span")
        if ts == "ok" and rs == "ok" and _num(tv) and _num(rv) and tv > 0 and rv >= 0:
            errs.append(abs(Fraction(rv) - Fraction(tv)) / Fraction(tv))  # exact: no float rounding at the cutoff
        else:
            incomplete.append(p["pair_id"])
    median = statistics.median(errs) if errs else None  # exact Fraction; compared with the frozen cutoff as-is
    if incomplete or median is None:
        status = INSUFFICIENT
    else:
        status = PASS if median <= _SCALE_CUTOFF else FAIL
    return _gate("scale", status, median_abs_rel_error=None if median is None else float(median), threshold=SCALE_MEDIAN_MAX,
                 measured=len(errs), denominator=len(pairs), incomplete=incomplete)


def _vote_wins(row, field):
    """Return (wins by judge, valid vote count, problems) for one row and one criterion."""
    s, v = measured(row, field) if row is not None else ("invalid", None)
    if s != "ok" or not isinstance(v, list):
        return {}, 0, ["votes missing"]
    judges = [x.get("judge") if isinstance(x, dict) else None for x in v]
    if len(v) != EXPECTED_JUDGES or not all(_text(j) for j in judges) or len(set(judges)) != EXPECTED_JUDGES:
        return {}, 0, ["need two distinct judges"]
    wins, valid, probs = {}, 0, []
    for x in v:
        vote = x.get("vote")
        if vote not in VOTES:
            probs.append(f"{x.get('judge')}: bad vote")
            continue
        if vote == "abstain":
            if not _text(x.get("reason")):
                probs.append(f"{x.get('judge')}: abstain without reason")
            continue  # abstentions stay in the denominator, never a win
        if x.get("presented_first") not in ("candidate", "baseline"):
            probs.append(f"{x.get('judge')}: blinded presentation order not recorded")
            continue
        valid += 1
        if vote == "candidate":
            wins[x["judge"]] = 1
    return wins, valid, probs


def gate_judges(pairs, by_id, field, name):
    n = len(pairs)
    total_wins, valid_votes, per_judge, incomplete, conflicts = 0, 0, Counter(), [], []
    for p in pairs:
        r = by_id.get(p["pair_id"])
        if r is None or not _text(r.get("baseline_id")) or r.get("baseline_id") != p.get("baseline_id"):
            incomplete.append(p["pair_id"])  # missing row or baseline not the frozen face-only baseline
            continue
        wins, valid, probs = _vote_wins(r, field)
        if probs:  # malformed or missing; a reasoned abstention or a tie is complete but never a win
            incomplete.append(p["pair_id"])
        total_wins += sum(wins.values())
        valid_votes += valid
        per_judge.update(wins)
        if len(wins) == 1:
            conflicts.append(p["pair_id"])
    expected = EXPECTED_JUDGES * n
    frac = total_wins / expected if expected else 0.0
    met = frac >= JUDGE_PREFER_MIN
    status = PASS if met and not incomplete else (FAIL if not incomplete else INSUFFICIENT)
    return _gate(name, status, candidate_wins=total_wins, expected_votes=expected, valid_votes=valid_votes,
                 fraction=frac, threshold=JUDGE_PREFER_MIN, per_judge_wins=dict(per_judge),
                 split_decisions=conflicts, incomplete=incomplete)


def gate_identity(pairs, by_id):
    scored, incomplete, disagree, outcomes = 0, [], [], Counter()
    for p in pairs:
        r = by_id.get(p["pair_id"])
        s, v = measured(r, "identity") if r is not None else ("invalid", None)
        if s != "ok" or not isinstance(v, list):
            incomplete.append(p["pair_id"])
            continue
        sources = [x.get("source") if isinstance(x, dict) else None for x in v]
        good = [x for x in v if isinstance(x, dict) and _text(x.get("source")) and x.get("profile_capable") is True
                and x.get("outcome") in ("preserved", "not_preserved")]
        if len({x["source"] for x in good}) < 2 or len({x for x in sources if _text(x)}) != len(sources):
            incomplete.append(p["pair_id"])  # unscorable profile, abstention, or not two independent sources
            continue
        if len({x["outcome"] for x in good}) > 1:
            disagree.append(p["pair_id"])
            continue
        scored += 1
        outcomes[good[0]["outcome"]] += 1
    # No numeric identity threshold is pre-registered: the gate demands complete, agreeing,
    # profile-capable evidence for every pair and reports the counts.
    status = PASS if not incomplete and not disagree else INSUFFICIENT
    return _gate("profile_identity", status, scored=scored, denominator=len(pairs),
                 outcomes=dict(outcomes), incomplete=incomplete, disagreements=disagree)


# ---------------------------------------------------------------- top level

MAX_DEPTH = 32


def _plain(v, depth=0):
    """True if v is plain JSON-shaped data: dict with string keys, list, str, bool, int, float, None."""
    if depth > MAX_DEPTH:
        return False
    if v is None or isinstance(v, (str, bool, int, float)):
        return True
    if isinstance(v, list):
        return all(_plain(x, depth + 1) for x in v)
    if isinstance(v, dict):
        return all(isinstance(k, str) and _plain(x, depth + 1) for k, x in v.items())
    return False


def normalize_input(cohort, rows):
    """The one shape boundary. Everything after it sees only these guaranteed shapes.

    Returns (pairs, row_list, problems): `pairs` is a list of dicts with a non-empty string
    pair_id, `row_list` a list of dicts, `problems` plain strings explaining what was dropped.
    Dropped items are never turned into passes: any problem makes the verdict INSUFFICIENT, and
    planned pairs without usable rows stay in the denominators.
    """
    problems = []
    if not _plain(cohort):
        problems.append("cohort is not plain JSON data (or nested too deeply)")
        cohort = None
    if not _plain(rows):
        problems.append("rows are not plain JSON data (or nested too deeply)")
        rows = None
    raw_pairs = None
    if isinstance(cohort, dict):
        raw_pairs = cohort.get("pairs")
    elif cohort is not None:
        problems.append("cohort is not an object")
    if raw_pairs is None and isinstance(cohort, dict):
        problems.append("cohort has no pairs list")
    elif raw_pairs is not None and not isinstance(raw_pairs, list):
        problems.append("cohort pairs is not a list")
        raw_pairs = None
    pairs = []
    for i, p in enumerate(raw_pairs or []):
        if isinstance(p, dict) and _text(p.get("pair_id")):
            pairs.append(p)
        else:
            problems.append(f"pair #{i} is not an object with a non-empty string pair_id")
    if rows is not None and not isinstance(rows, list):
        problems.append("rows is not a list")
        rows = None
    row_list = []
    for i, r in enumerate(rows or []):
        if isinstance(r, dict):
            row_list.append(r)
        else:
            problems.append(f"row #{i} is not an object")
    return pairs, row_list, problems


def _insufficient_report(cause, n=0):
    return {"rubric_revision": RUBRIC_REVISION, "verdict": INSUFFICIENT, "n_planned": n, "n_declared": n,
            "cohort_status": INSUFFICIENT, "cohort_problems": [cause], "row_problems": [], "gates": [],
            "note": "Evaluator output over supplied records only; not a measured model-quality claim."}


def evaluate(cohort, rows):
    """Return the machine-readable report. Verdict PASS only when everything passes.

    Never raises on untrusted data: input shape is normalised once (`normalize_input`) and any
    unexpected internal error still yields an INSUFFICIENT_EVIDENCE report that names its cause.
    """
    try:
        return _evaluate(cohort, rows)
    except Exception as exc:  # fail closed, never a traceback instead of a verdict
        return _insufficient_report(f"evaluation aborted on malformed evidence: {type(exc).__name__}")


def _evaluate(cohort, rows):
    pairs, row_list, shape_problems = normalize_input(cohort, rows)
    declared = len(cohort["pairs"]) if isinstance(cohort, dict) and isinstance(cohort.get("pairs"), list) else 0
    if shape_problems:
        cstatus, cproblems = INSUFFICIENT, []
        if isinstance(cohort, dict) and _plain(cohort):
            cstatus, cproblems = check_cohort(cohort)
        cproblems = shape_problems + [x for x in cproblems if x not in shape_problems]
    else:
        cstatus, cproblems = check_cohort(cohort)
    by_id, extra = _index_rows(pairs, row_list)
    row_problems = []
    if extra:
        row_problems.append(f"rows not matching a unique planned pair: {[str(e) for e in extra]}")
    for p in pairs:
        r = by_id.get(p["pair_id"])
        if r is None:
            row_problems.append(f"{p['pair_id']}: no row (stays in N as a non-pass)")
            continue
        for k in ("target_id", "reference_id", "engine", "seed", "model_sha256", "adapter_sha256", "pose_tag"):
            if r.get(k) != p.get(k):
                row_problems.append(f"{p['pair_id']}: {k} differs from the locked cohort")
        row_problems += check_row_provenance(p, r)
    gates = [gate_whole_head_and_pose(pairs, by_id), gate_edit_fence(pairs, by_id), gate_scale(pairs, by_id),
             gate_judges(pairs, by_id, "hair_votes", "new_hair_hairline"),
             gate_judges(pairs, by_id, "seam_votes", "neck_seam"),
             gate_identity(pairs, by_id)]
    if cstatus != PASS or row_problems or shape_problems:
        verdict = INSUFFICIENT
    elif all(g["status"] == PASS for g in gates):
        verdict = PASS
    elif any(g["status"] == FAIL for g in gates):
        verdict = FAIL
    else:
        verdict = INSUFFICIENT
    return {"rubric_revision": RUBRIC_REVISION, "verdict": verdict, "n_planned": len(pairs), "n_declared": declared,
            "cohort_status": cstatus, "cohort_problems": cproblems, "row_problems": row_problems,
            "gates": gates,
            "note": "Evaluator output over supplied records only; not a measured model-quality claim."}


def render_text(report):
    out = [f"IDS-12A verdict: {report['verdict']}  (N={report['n_planned']}, rubric {report['rubric_revision']})",
           f"cohort: {report['cohort_status']}"]
    out += [f"  - {x}" for x in report["cohort_problems"] + report["row_problems"]]
    for g in report["gates"]:
        extra = {k: v for k, v in g.items() if k not in ("gate", "status", "notes") and v not in ([], {}, None)}
        out.append(f"{g['gate']}: {g['status']}  {json.dumps(extra, sort_keys=True)}")
    out.append(report["note"])
    return "\n".join(out)


_UNREADABLE = object()


def main(argv):
    args = [a for a in argv if not a.startswith("--")]
    out_path = argv[argv.index("--json") + 1] if "--json" in argv else None
    if out_path in args:
        args.remove(out_path)
    if len(args) != 2:
        print("usage: quality.py COHORT.json ROWS.json [--json OUT.json]", file=sys.stderr)
        return 2
    loaded = []
    for path in args:
        try:
            with open(path, encoding="utf-8") as f:
                loaded.append(json.load(f))
        except (OSError, ValueError, RecursionError) as exc:  # unreadable or malformed JSON is evidence too
            print(f"cannot read {path}: {type(exc).__name__}", file=sys.stderr)
            loaded.append(_UNREADABLE)
    if any(x is _UNREADABLE for x in loaded):
        report = _insufficient_report("cohort or rows file is missing or not valid JSON")
    else:
        report = evaluate(loaded[0], loaded[1])
    print(render_text(report))
    if out_path:
        with open(out_path, "w", encoding="utf-8") as f:
            json.dump(report, f, indent=2, sort_keys=True)
    return 0 if report["verdict"] == PASS else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
