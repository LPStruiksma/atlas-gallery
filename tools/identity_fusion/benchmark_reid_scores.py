#!/usr/bin/env python3
"""Offline, read-only held-out evaluation of *precomputed* person-reID scores.

There is no model or image loader here. No prediction becomes a confirmed
identity. CSV row identifiers stay in memory, never in reports or errors.
The calibration split alone chooses a similarity floor; the holdout is
evaluated once under that locked floor. This is research, not deployment.
"""
from __future__ import annotations

import argparse
import csv
import json
import math
import re
import sys
from collections import defaultdict
from pathlib import Path

FIELDS = ("probe_uid", "split", "truth_uid", "session_uid", "condition",
          "candidate_uid", "similarity")
CONDITIONS = frozenset({"front", "back", "side", "partial", "occluded",
                        "appearance_shift", "lookalike", "unknown"})
TOKEN = re.compile(r"^[A-Za-z0-9_-]{1,64}$")
Z95 = 1.6448536269514722  # One-sided 95% normal quantile.
MAX_ROWS = 100_000
MAX_CANDIDATES = 1_000
MAX_CONDITION_TAGS = 3


def _normalize_condition(condition: str) -> str:
    """Canonicalize up to three simultaneous conditions, no arbitrary tags.

    'back+occluded' is a legitimate intersection; 'unknown+back' is not,
    because the condition 'unknown' means visibility not reviewed.
    """
    if not isinstance(condition, str):
        raise ValueError("Invalid reviewed condition labels")
    tags = condition.split("+")
    if (not 1 <= len(tags) <= MAX_CONDITION_TAGS
            or len(tags) != len(set(tags))
            or not set(tags) <= CONDITIONS
            or ("unknown" in tags and len(tags) != 1)):
        raise ValueError("Invalid reviewed condition labels")
    return "+".join(sorted(tags))


def _upper_wilson(errors: int, total: int) -> float | None:
    """One-sided 95% upper rate bound; not a model confidence probability."""
    if total == 0:
        return None
    p = errors / total
    z2 = Z95 * Z95
    return min(1.0, (p + z2 / (2 * total) +
                     Z95 * math.sqrt(p * (1 - p) / total +
                                     z2 / (4 * total * total))) /
               (1 + z2 / total))


def _summarize(top, threshold, *, min_stratum=10, min_top_two_margin=0.0):
    counts = defaultdict(int)
    strata = defaultdict(lambda: defaultdict(int))
    tag_strata = defaultdict(lambda: defaultdict(int))
    # A burst of photographs from one event is NOT a set of independent
    # statistical trials. Count errors once per distinct session/class.
    def empty_sessions():
        return {"known": set(), "unknown": set(),
                "wrong_known": set(), "false_unknown": set()}
    all_sessions = empty_sessions()
    # Repeated observations of one confirmed known subject are correlated,
    # even when session/event labels happen to differ.
    known_persons, wrong_known_persons = set(), set()
    strata_known_persons = defaultdict(set)
    strata_wrong_persons = defaultdict(set)
    tag_known_persons = defaultdict(set)
    tag_wrong_persons = defaultdict(set)
    strata_sessions = defaultdict(empty_sessions)
    tag_sessions = defaultdict(empty_sessions)
    for probe in top:
        uid, session, cond, truth, similarity, candidate, tied, gap = probe
        # A missing runner-up is NOT infinite confidence. For an explicit
        # positive ambiguity margin it is impossible to verify separation.
        # The default zero-margin experiment retains historical behavior.
        missing_runner = (candidate is not None and not tied
                          and similarity >= threshold and gap is None
                          and min_top_two_margin > 0)
        near_tie = (candidate is not None and not tied
                    and similarity >= threshold and gap is not None
                    and gap < min_top_two_margin)
        offered = (candidate if not tied and similarity >= threshold
                   and not near_tie and not missing_runner else None)
        truth_kind = "unknown" if truth == "UNKNOWN" else "known"
        if truth != "UNKNOWN":
            known_persons.add(truth)
            strata_known_persons[cond].add(truth)
            for tag in cond.split("+"):
                tag_known_persons[tag].add(truth)
            if offered is not None and offered != truth:
                wrong_known_persons.add(truth)
                strata_wrong_persons[cond].add(truth)
                for tag in cond.split("+"):
                    tag_wrong_persons[tag].add(truth)
        # Exact intersections remain in by_condition, while
        # by_condition_tag is a marginal view across overlapping labels.
        # Its rows intentionally must NOT be summed as disjoint cohorts.
        tag_labels = cond.split("+")
        session_targets = [all_sessions, strata_sessions[cond]]
        count_targets = [counts, strata[cond]]
        for tag in tag_labels:
            session_targets.append(tag_sessions[tag])
            count_targets.append(tag_strata[tag])
        for sessions in session_targets:
            sessions[truth_kind].add(session)
            if offered is not None and offered != truth:
                sessions["false_unknown" if truth == "UNKNOWN"
                         else "wrong_known"].add(session)
        for d in count_targets:
            d["observations"] += 1
            d[truth_kind + "_observations"] += 1
            if near_tie:
                d["near_tie_abstentions"] += 1
            if missing_runner:
                d["runner_up_unavailable_abstentions"] += 1
            if tied and similarity >= threshold:
                d["exact_tie_abstentions"] += 1
            if offered is None:
                d["abstentions"] += 1
                d["correct_unknown_abstentions" if truth == "UNKNOWN"
                  else "missed_known_abstentions"] += 1
            elif offered == truth:
                d["correct_known_offers"] += 1
                d["review_offers"] += 1
            elif truth == "UNKNOWN":
                d["unknown_false_offers"] += 1
                d["review_offers"] += 1
            else:
                d["wrong_known_offers"] += 1
                d["review_offers"] += 1

    def render(c, sessions, people, wrong_people):
        n = c["observations"]
        known_session_count = len(sessions["known"])
        unknown_session_count = len(sessions["unknown"])
        wrong_known_sessions = len(sessions["wrong_known"])
        false_unknown_sessions = len(sessions["false_unknown"])
        known = c["known_observations"]
        unknown = c["unknown_observations"]
        return {
            "observations": n,
            "known_observations": known,
            "unknown_observations": unknown,
            "distinct_known_persons": len(people),
            "wrong_known_error_persons": len(wrong_people),
            "wrong_known_person_wilson95_upper": round(
                _upper_wilson(len(wrong_people), len(people)), 4
            ) if people else None,
            "correct_known_offers": c["correct_known_offers"],
            "wrong_known_offers": c["wrong_known_offers"],
            "unknown_false_offers": c["unknown_false_offers"],
            "missed_known_abstentions": c["missed_known_abstentions"],
            "correct_unknown_abstentions": c["correct_unknown_abstentions"],
            "review_offers": c["review_offers"],
            "abstentions": c["abstentions"],
            "near_tie_abstentions": c["near_tie_abstentions"],
            "runner_up_unavailable_abstentions": c["runner_up_unavailable_abstentions"],
            "exact_tie_abstentions": c["exact_tie_abstentions"],
            "distinct_known_sessions": known_session_count,
            "distinct_unknown_sessions": unknown_session_count,
            "wrong_known_error_sessions": wrong_known_sessions,
            "unknown_false_offer_error_sessions": false_unknown_sessions,
            "wrong_known_session_error_fraction": round(
                wrong_known_sessions / known_session_count, 4)
                if known_session_count else None,
            "unknown_false_offer_session_error_fraction": round(
                false_unknown_sessions / unknown_session_count, 4)
                if unknown_session_count else None,
            "wrong_known_session_wilson95_upper": round(
                _upper_wilson(wrong_known_sessions, known_session_count), 4)
                if known_session_count else None,
            "unknown_false_offer_session_wilson95_upper": round(
                _upper_wilson(false_unknown_sessions, unknown_session_count), 4)
                if unknown_session_count else None,
            "offer_coverage_fraction": round(c["review_offers"] / n, 4) if n else None,
            "unknown_false_offer_fraction": round(c["unknown_false_offers"] / unknown, 4)
                if unknown else None,
            "wrong_known_offer_fraction": round(c["wrong_known_offers"] / known, 4)
                if known else None,
            "unknown_false_offer_wilson95_upper": round(
                _upper_wilson(c["unknown_false_offers"], unknown), 4)
                if unknown else None,
            "wrong_known_offer_wilson95_upper": round(
                _upper_wilson(c["wrong_known_offers"], known), 4)
                if known else None,
        }
    suppressed = sorted(k for k, v in strata.items()
                        if v["observations"] < min_stratum)
    suppressed_tags = sum(v["observations"] < min_stratum
                          for v in tag_strata.values())
    return {"overall": render(
                counts, all_sessions, known_persons, wrong_known_persons),
            "by_condition": {
                k: render(v, strata_sessions[k], strata_known_persons[k],
                          strata_wrong_persons[k])
                for k, v in sorted(strata.items())
                if v["observations"] >= min_stratum},
            "by_condition_tag": {
                k: render(v, tag_sessions[k], tag_known_persons[k],
                          tag_wrong_persons[k])
                for k, v in sorted(tag_strata.items())
                if v["observations"] >= min_stratum},
            "suppressed_condition_count": len(suppressed),
            "suppressed_condition_tag_count": suppressed_tags}



def _event_cluster_safety(top, event_by_probe, threshold, margin):
    """Aggregate any-error-per-event audit; events are not independent people.

    A mixed event is in both denominators when it contains both known and
    Unknown observations. It never creates two *independent* event samples
    inside the same truth category, regardless of number of photographs.
    """
    known, unknown, wrong, false = set(), set(), set(), set()
    for probe, session, condition, truth, score, candidate, tied, gap in top:
        cluster = event_by_probe[probe]
        if truth == "UNKNOWN":
            unknown.add(cluster)
        else:
            known.add(cluster)
        offered = (candidate is not None and not tied and score >= threshold
                   and (margin == 0 or (gap is not None and gap >= margin)))
        if offered and candidate != truth:
            (false if truth == "UNKNOWN" else wrong).add(cluster)
    return {
        "known_event_clusters": len(known),
        "unknown_event_clusters": len(unknown),
        "wrong_known_error_events": len(wrong),
        "unknown_false_offer_error_events": len(false),
        "wrong_known_event_wilson95_upper": round(
            _upper_wilson(len(wrong), len(known)), 4) if known else None,
        "unknown_false_offer_event_wilson95_upper": round(
            _upper_wilson(len(false), len(unknown)), 4) if unknown else None,
        "calibrated_identity_probability": None,
        "events_are_proven_independent": False,
    }


def _unknown_group_metrics(top, mapping, floor, margin):
    groups, errors = set(), set()
    for probe, session, cond, truth, score, candidate, tied, gap in top:
        if truth != "UNKNOWN":
            continue
        group = mapping[probe]
        groups.add(group)
        if (candidate is not None and not tied and score >= floor
                and (margin == 0 or (gap is not None and gap >= margin))):
            errors.add(group)
    return {"groups": len(groups), "groups_with_errors": len(errors),
            "wilson95_upper": round(_upper_wilson(len(errors), len(groups)), 4)
            if groups else None, "independence_proven": False}


def _parse(rows):
    if not isinstance(rows, (list, tuple)) or not rows or len(rows) > MAX_ROWS:
        raise ValueError("Score manifest must have 1..100000 bounded rows")
    groups = {}
    probe_split = {}
    split_persons = {"calibration": set(), "holdout": set()}
    split_sessions = {"calibration": set(), "holdout": set()}
    split_candidates = {"calibration": set(), "holdout": set()}
    for row in rows:
        if not isinstance(row, dict) or set(row) != set(FIELDS):
            raise ValueError("Unexpected or missing model-evaluation columns")
        probe, split, truth, session, condition, candidate, raw = (
            row[k] for k in FIELDS)
        if (not all(isinstance(v, str) for v in
                    (probe, split, truth, session, condition, candidate, raw))
                or not TOKEN.fullmatch(probe)
                or not TOKEN.fullmatch(truth)
                or not TOKEN.fullmatch(session)
                or not TOKEN.fullmatch(candidate)
                or split not in split_persons):
            raise ValueError("Invalid opaque identifier or evaluation condition")
        condition = _normalize_condition(condition)
        if candidate == "UNKNOWN" or truth == "NONE":
            raise ValueError("Reserved candidate or truth identifier")
        if candidate == "NONE":
            if raw != "":
                raise ValueError("No-candidate probes require an empty similarity")
            score = None
        else:
            try:
                score = float(raw)
            except ValueError as exc:
                raise ValueError("Missing or nonnumeric model similarity") from None
            if not math.isfinite(score) or not -1 <= score <= 1:
                raise ValueError("Cosine similarity outside finite [-1,1]")
        # A probe (photograph) may be scored against many candidates, but
        # may NEVER appear in both splits under the same opaque probe ID.
        prior_split = probe_split.setdefault(probe, split)
        if prior_split != split:
            raise ValueError("Exact probe reused across calibration and holdout")
        key = (split, probe)
        entry = groups.setdefault(key, {
            "truth": truth, "session": session, "condition": condition,
            "items": {}
        })
        if (entry["truth"], entry["session"], entry["condition"]) != (
                truth, session, condition):
            raise ValueError("Conflicting metadata for one probe")
        if candidate in entry["items"] or len(entry["items"]) >= MAX_CANDIDATES:
            raise ValueError("Duplicate or excessive candidate rows per probe")
        if candidate == "NONE" and entry["items"]:
            raise ValueError("No-candidate sentinel cannot mix with real candidates")
        if candidate != "NONE" and "NONE" in entry["items"]:
            raise ValueError("No-candidate sentinel cannot mix with real candidates")
        entry["items"][candidate] = score
        if truth != "UNKNOWN":
            split_persons[split].add(truth)
        split_sessions[split].add(session)
        if candidate != "NONE":
            split_candidates[split].add(candidate)
    if (split_persons["calibration"] & split_persons["holdout"]
            or split_sessions["calibration"] & split_sessions["holdout"]
            or split_candidates["calibration"] & split_candidates["holdout"]
            or split_persons["calibration"] & split_candidates["holdout"]
            or split_persons["holdout"] & split_candidates["calibration"]):
        raise ValueError("Identity or session leakage across calibration and holdout")

    top = {"calibration": [], "holdout": []}
    for (split, probe), entry in groups.items():
        ranked = sorted((score, candidate) for candidate, score
                         in entry["items"].items() if score is not None)
        if ranked:
            highest = ranked[-1][0]
            winners = [candidate for score, candidate in ranked if score == highest]
            candidate = min(winners)
            tied = len(winners) > 1
            gap = highest - ranked[-2][0] if len(ranked) > 1 else None
        else:
            highest, candidate, tied, gap = -math.inf, None, False, None
        top[split].append((probe, entry["session"], entry["condition"],
                           entry["truth"], highest, candidate, tied, gap))
    for split in top:
        known = sum(x[3] != "UNKNOWN" for x in top[split])
        unknown = len(top[split]) - known
        need_known, need_unknown = ((50, 80) if split == "calibration"
                                    else (20, 20))
        if known < need_known or unknown < need_unknown:
            raise ValueError("Insufficient independently reviewed known/Unknown trials")
    return top


def benchmark_model_scores(rows, *, max_false_offer_rate: float = .05,
                           min_top_two_margin: float = 0.0,
                           provenance_rows=None, require_provenance: bool = False,
                           candidate_roster_rows=None,
                           require_candidate_roster: bool = False,
                           require_reviewed_unknown_subjects: bool = False,
                           protected_condition_tags=()):
    """Select floor on calibration only; inspect holdout once, in aggregates.

    Rates use independent reviewed cases. Similarity is never a calibrated
    identity probability. Review-only and Unknown remain mandatory.
    """
    if (not isinstance(max_false_offer_rate, (float, int))
            or isinstance(max_false_offer_rate, bool)
            or not math.isfinite(max_false_offer_rate)
            or not 0 < max_false_offer_rate <= .2):
        raise ValueError("Require a conservative finite rate budget")
    if (not isinstance(min_top_two_margin, (float, int))
            or isinstance(min_top_two_margin, bool)
            or not math.isfinite(min_top_two_margin)
            or not 0 <= min_top_two_margin <= 1):
        raise ValueError("Require a finite nonnegative top-two margin")
    if (not isinstance(protected_condition_tags, (tuple, list))
            or len(protected_condition_tags) > len(CONDITIONS)
            or any(type(t) is not str or t not in CONDITIONS
                   for t in protected_condition_tags)
            or len(set(protected_condition_tags)) != len(protected_condition_tags)):
        raise ValueError("Protected conditions must be unique public condition tags")
    protected_condition_tags = tuple(sorted(protected_condition_tags))
    top = _parse(rows)
    if type(require_reviewed_unknown_subjects) is not bool:
        raise ValueError("Unknown-subject review requirement must be boolean")
    if type(require_candidate_roster) is not bool:
        raise ValueError("Candidate roster requirement must be boolean")
    if candidate_roster_rows is None:
        if require_candidate_roster:
            raise ValueError("Frozen candidate roster required")
        roster_audit = {"status": "not_supplied",
                        "gallery_exhaustiveness_verified": False,
                        "roster_independence_verified": False,
                        "auto_confirm_allowed": False}
    else:
        from candidate_list_coverage import audit_candidate_list
        roster_audit = audit_candidate_list(rows, candidate_roster_rows)
    if type(require_provenance) is not bool:
        raise ValueError("Explicit boolean provenance gate required")
    if provenance_rows is None:
        if require_provenance:
            raise ValueError("Audited lineage/event partition manifest required")
        provenance_report = {
            "status": "not_supplied",
            "physical_independence_proven": False,
            "auto_confirm_allowed": False,
        }
    else:
        from verify_split_provenance import verify_split_provenance
        provenance_report = verify_split_provenance(provenance_rows, top)
        # Only caller-supplied, partition-validated event-cluster names are used.
        # The metadata remain in memory and are NEVER exposed in diagnostics.
        event_by_split = {
            split: {row["probe_uid"]: row["event_cluster_uid"]
                    for row in provenance_rows if row["split"] == split}
            for split in ("calibration", "holdout")
        }
    unknown_subject_by_split = None
    if require_reviewed_unknown_subjects:
        if provenance_rows is None:
            raise ValueError("Reviewed Unknown-subject provenance required")
        unknown_subject_by_split = {"calibration": {}, "holdout": {}}
        truth_lookup = {split: {trial[0]: trial[3] for trial in trials}
                        for split, trials in top.items()}
        known_subjects = set()
        for row in provenance_rows:
            split, uid, subject = (row["split"], row["probe_uid"],
                                   row["subject_cluster_uid"])
            truth = truth_lookup[split].get(uid)
            if truth is None:
                raise ValueError("Unknown reviewed probe")
            if truth == "UNKNOWN":
                if subject == "UNREVIEWED":
                    raise ValueError("Incomplete reviewed Unknown-subject clusters")
                unknown_subject_by_split[split][uid] = subject
            elif subject != "UNREVIEWED":
                known_subjects.add(subject)
        if any(set(items.values()) & known_subjects
               for items in unknown_subject_by_split.values()):
            raise ValueError("One subject cannot be known and Unknown")
    calibration = top["calibration"]
    # A declared difficult-condition cohort is separately accountable:
    # overall strong front-facing results must NOT hide back/occluded errors.
    # These groups deliberately overlap for compound condition tags.
    protected_trials = {}
    protected_errors = {}
    for tag in protected_condition_tags:
        matched = [t for t in calibration if tag in t[2].split("+")]
        known_tag = [t for t in matched if t[3] != "UNKNOWN"]
        unknown_tag = [t for t in matched if t[3] == "UNKNOWN"]
        groups = {
            "known_probes": len(known_tag),
            "unknown_probes": len(unknown_tag),
            "known_people": len({t[3] for t in known_tag}),
            "known_sessions": len({t[1] for t in known_tag}),
            "unknown_sessions": len({t[1] for t in unknown_tag}),
        }
        if provenance_rows is not None:
            groups["known_events"] = len({
                event_by_split["calibration"][t[0]] for t in known_tag
            })
            groups["unknown_events"] = len({
                event_by_split["calibration"][t[0]] for t in unknown_tag
            })
        if require_reviewed_unknown_subjects:
            groups["unknown_subjects"] = len({
                unknown_subject_by_split["calibration"][t[0]] for t in unknown_tag
            })
        # Even zero observed errors need enough distinct people/events to
        # support a claimed upper error bound. Do not invent a tiny-stratum
        # exception, or accept an absent known/Unknown truth class.
        if any(_upper_wilson(0, n) is None
               or _upper_wilson(0, n) > max_false_offer_rate
               for n in groups.values()):
            raise ValueError("Protected condition lacks independently reviewed "
                             "known/Unknown diversity for error budget")
        protected_trials[tag] = groups
        protected_errors[tag] = {
            "wrong_probes": 0, "false_probes": 0,
            "wrong_people": set(), "wrong_sessions": set(),
            "false_sessions": set(), "wrong_events": set(),
            "false_events": set(), "false_subjects": set(),
        }
    # One descending sweep, O(N log N) for ordering + O(N) for review
    # updates. Re-scanning all 100k probes for every distinct model score
    # would be quadratic and unsuitable even for an offline pilot.
    known_total = sum(trial[3] != "UNKNOWN" for trial in calibration)
    unknown_total = len(calibration) - known_total
    known_person_total = len({trial[3] for trial in calibration
                              if trial[3] != "UNKNOWN"})
    wrong_known_people = set()
    # Wilson bounds over repeated photos are overconfident when they share
    # an event/camera/time context. Require BOTH probe-level and
    # worst-case any-error-per-session bounds to pass on calibration.
    known_session_total = len({t[1] for t in calibration if t[3] != "UNKNOWN"})
    unknown_session_total = len({t[1] for t in calibration if t[3] == "UNKNOWN"})
    wrong_known_sessions = set()
    false_unknown_sessions = set()
    if require_reviewed_unknown_subjects:
        reviewed_unknowns = unknown_subject_by_split["calibration"]
        unknown_subject_total = len(set(reviewed_unknowns.values()))
        false_unknown_subjects = set()
    if provenance_rows is not None:
        calibration_events = event_by_split["calibration"]
        known_event_total = len({
            calibration_events[t[0]] for t in calibration if t[3] != "UNKNOWN"
        })
        unknown_event_total = len({
            calibration_events[t[0]] for t in calibration if t[3] == "UNKNOWN"
        })
        wrong_known_events = set()
        false_unknown_events = set()
    selectable = sorted(
        (trial for trial in calibration
         if trial[5] is not None and not trial[6]
         and (min_top_two_margin == 0
              or (trial[7] is not None
                  and trial[7] >= min_top_two_margin))),
        key=lambda trial: -trial[4]
    )
    successes = wrong_known = false_unknown = 0
    best = None
    # Apply all top-scored candidates with the SAME score together, so a
    # threshold can never split an exact tie between separate probes.
    index = 0
    while index < len(selectable):
        score = selectable[index][4]
        while index < len(selectable) and selectable[index][4] == score:
            trial = selectable[index]
            affected_tags = protected_condition_tags and (
                set(trial[2].split("+")) & set(protected_condition_tags))
            if trial[3] == "UNKNOWN":
                for tag in affected_tags:
                    err = protected_errors[tag]
                    err["false_probes"] += 1
                    err["false_sessions"].add(trial[1])
                    if provenance_rows is not None:
                        err["false_events"].add(calibration_events[trial[0]])
                    if require_reviewed_unknown_subjects:
                        err["false_subjects"].add(reviewed_unknowns[trial[0]])
                false_unknown += 1
                false_unknown_sessions.add(trial[1])
                if require_reviewed_unknown_subjects:
                    false_unknown_subjects.add(reviewed_unknowns[trial[0]])
                if provenance_rows is not None:
                    false_unknown_events.add(calibration_events[trial[0]])
            elif trial[5] == trial[3]:
                successes += 1
            else:
                for tag in affected_tags:
                    err = protected_errors[tag]
                    err["wrong_probes"] += 1
                    err["wrong_people"].add(trial[3])
                    err["wrong_sessions"].add(trial[1])
                    if provenance_rows is not None:
                        err["wrong_events"].add(calibration_events[trial[0]])
                wrong_known += 1
                wrong_known_sessions.add(trial[1])
                wrong_known_people.add(trial[3])
                if provenance_rows is not None:
                    wrong_known_events.add(calibration_events[trial[0]])
            index += 1
        # CRITICAL: evaluate the *unrounded* Wilson bound. A true upper
        # bound of 0.05004 must not become "safe" by rounding to 0.0500.
        if (_upper_wilson(false_unknown, unknown_total) > max_false_offer_rate
                or _upper_wilson(wrong_known, known_total) > max_false_offer_rate
                or _upper_wilson(len(wrong_known_people),
                                 known_person_total) > max_false_offer_rate
                or _upper_wilson(len(false_unknown_sessions),
                                 unknown_session_total) > max_false_offer_rate
                or _upper_wilson(len(wrong_known_sessions),
                                 known_session_total) > max_false_offer_rate):
            continue
        # A photo burst can span multiple nominal sessions in ONE event.
        # When declared event metadata exist, its any-error bounds also
        # constrain calibration. No event labels ever go to the report.
        if require_reviewed_unknown_subjects and (
                _upper_wilson(len(false_unknown_subjects),
                              unknown_subject_total) > max_false_offer_rate):
            continue
        if provenance_rows is not None and (
                _upper_wilson(len(false_unknown_events),
                              unknown_event_total) > max_false_offer_rate
                or _upper_wilson(len(wrong_known_events),
                                 known_event_total) > max_false_offer_rate):
            continue
        # All protected conditions must pass their OWN independent probe,
        # known-person, session, and (when supplied) event/Unknown-subject
        # bounds. Their trial counts overlap, so never sum denominators.
        protected_bad = False
        for tag in protected_condition_tags:
            g, e = protected_trials[tag], protected_errors[tag]
            tested = [
                (e["wrong_probes"], g["known_probes"]),
                (e["false_probes"], g["unknown_probes"]),
                (len(e["wrong_people"]), g["known_people"]),
                (len(e["wrong_sessions"]), g["known_sessions"]),
                (len(e["false_sessions"]), g["unknown_sessions"]),
            ]
            if provenance_rows is not None:
                tested.extend([
                    (len(e["wrong_events"]), g["known_events"]),
                    (len(e["false_events"]), g["unknown_events"]),
                ])
            if require_reviewed_unknown_subjects:
                tested.append((len(e["false_subjects"]), g["unknown_subjects"]))
            if any(_upper_wilson(errors, n) > max_false_offer_rate
                   for errors, n in tested):
                protected_bad = True
                break
        if protected_bad:
            continue
        key = (successes, -(false_unknown + wrong_known), score)
        if best is None or key > best[0]:
            best = (key, score)
    if best is None or best[0][0] == 0:
        raise ValueError("No nontrivial calibration threshold meets safety budget")
    floor = best[1]
    if provenance_rows is not None:
        provenance_report["event_cluster_constraints_enforced"] = True
        provenance_report["calibration_event_safety"] = _event_cluster_safety(
            calibration, event_by_split["calibration"], floor, min_top_two_margin)
        provenance_report["holdout_event_safety"] = _event_cluster_safety(
            top["holdout"], event_by_split["holdout"], floor, min_top_two_margin)
    if require_reviewed_unknown_subjects:
        provenance_report["reviewed_unknown_constraints_enforced"] = True
        provenance_report["calibration_unknown_group_safety"] = _unknown_group_metrics(
            calibration, unknown_subject_by_split["calibration"],
            floor, min_top_two_margin)
        provenance_report["holdout_unknown_group_safety"] = _unknown_group_metrics(
            top["holdout"], unknown_subject_by_split["holdout"],
            floor, min_top_two_margin)
    calibration_report = _summarize(
        calibration, floor, min_top_two_margin=min_top_two_margin)
    holdout_report = _summarize(
        top["holdout"], floor, min_top_two_margin=min_top_two_margin)
    return {
        "status": "local_research_only",
        "auto_confirm_allowed": False,
        "unknown_always_allowed": True,
        "calibrated_identity_probability": None,
        "threshold_selected_from": "calibration_only",
        "similarity_floor": floor,
        "minimum_top_two_margin": min_top_two_margin,
        "max_false_offer_rate_budget": max_false_offer_rate,
        "protected_condition_tags": list(protected_condition_tags),
        "protected_tag_calibration_safety": {
            tag: calibration_report["by_condition_tag"][tag]
            for tag in protected_condition_tags
        },
        "provenance_partition_audit": provenance_report,
        "candidate_roster_audit": roster_audit,
        "calibration": calibration_report,
        "holdout": holdout_report,
        "caveat": "Protected condition tags, when specified, ALSO must "
                  "independently meet conservative Wilson error bounds. "
                  "Probe, known-person and any-error-per-session Wilson "
                  "bounds must pass calibration; with reviewed provenance "
                  "any-error-per-event "
                  "bounds are ALSO mandatory. An explicit margin refuses offers without "
                  "a measured runner-up; one-candidate lists do not establish "
                  "separation. Sessions may still correlate across events; "
                  "neither bound validates identity inference or deployment.",
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("local_csv", type=Path, help="Local opaque-ID scored CSV; no photo files")
    parser.add_argument("--min-top-two-margin", type=float, default=0.0,
                        help="Research-only score gap required before offering review")
    parser.add_argument("--provenance-csv", type=Path, default=None,
                        help="Optional local opaque probe/lineage/event/subject CSV")
    parser.add_argument("--require-provenance", action="store_true",
                        help="Refuse score evaluation without audited partition sidecar")
    parser.add_argument("--candidate-roster-csv", type=Path, default=None,
                        help="Previously frozen opaque candidate roster")
    parser.add_argument("--require-candidate-roster", action="store_true",
                        help="Refuse evaluations lacking candidate inventory")
    parser.add_argument("--require-reviewed-unknown-subjects", action="store_true",
                        help="Require independently reviewed anonymous Unknown clusters")
    parser.add_argument("--protect-condition-tag", action="append", default=[],
                        help="Require independent known/Unknown error budget for tag; repeatable")
    args = parser.parse_args(argv)
    try:
        # Deliberately no reading from remote URLs and no output file.
        if not args.local_csv.is_file() or args.local_csv.stat().st_size > 32 * 1024 * 1024:
            raise ValueError("Missing or oversized local CSV")
        with args.local_csv.open("r", encoding="utf-8-sig", newline="") as handle:
            reader = csv.DictReader(handle)
            if reader.fieldnames != list(FIELDS):
                raise ValueError("Exact manifest header required")
            score_rows = list(reader)
        sidecar = None
        if args.provenance_csv is not None:
            if (not args.provenance_csv.is_file()
                    or args.provenance_csv.stat().st_size > 16 * 1024 * 1024):
                raise ValueError("Missing or oversized local provenance CSV")
            from verify_split_provenance import FIELDS as PROVENANCE_FIELDS
            with args.provenance_csv.open("r", encoding="utf-8-sig", newline="") as handle:
                provenance_reader = csv.DictReader(handle)
                if provenance_reader.fieldnames != list(PROVENANCE_FIELDS):
                    raise ValueError("Exact provenance header required")
                sidecar = list(provenance_reader)
        roster = None
        if args.candidate_roster_csv is not None:
            if (not args.candidate_roster_csv.is_file()
                    or args.candidate_roster_csv.stat().st_size > 16 * 1024 * 1024):
                raise ValueError("Candidate inventory unavailable or too large")
            from candidate_list_coverage import FIELDS as ROSTER_FIELDS
            with args.candidate_roster_csv.open("r", encoding="utf-8-sig",
                                                newline="") as handle:
                inventory = csv.DictReader(handle)
                if inventory.fieldnames != list(ROSTER_FIELDS):
                    raise ValueError("Invalid candidate roster columns")
                roster = list(inventory)
        result = benchmark_model_scores(
            score_rows, min_top_two_margin=args.min_top_two_margin,
            provenance_rows=sidecar, require_provenance=args.require_provenance,
            candidate_roster_rows=roster,
            require_candidate_roster=args.require_candidate_roster,
            require_reviewed_unknown_subjects=args.require_reviewed_unknown_subjects,
            protected_condition_tags=args.protect_condition_tag)
        print(json.dumps(result, indent=2, sort_keys=True))
        return 0
    except (OSError, ValueError, csv.Error):
        # Never put local paths, row identifiers or candidate IDs in logs.
        print("Refused incomplete or unsafe local score benchmark input", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
