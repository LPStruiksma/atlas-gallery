#!/usr/bin/env python3
"""Aggregate frozen front-to-rear re-ID retention for independently reviewed people.

Research-only: opaque score/provenance/roster rows in memory; no model, media,
network, embeddings, People data or identity writes. Pair selection is declared,
not proven representative. Neither body nor outfit resemblance confirms identity.
"""
from __future__ import annotations

from collections import Counter, defaultdict

from benchmark_reid_scores import (
    TOKEN, _normalize_condition, _parse, _upper_wilson, benchmark_model_scores,
)
from verify_split_provenance import verify_split_provenance

PAIR_FIELDS = frozenset({"front_probe_uid", "rear_probe_uid"})
OUTCOMES = ("correct", "wrong", "abstain")
MAX_REAR_UNKNOWN_CALIBRATION_RISK = .05  # Frozen one-sided Wilson ceiling.
# Fixed before any real-study scores: important back/partial hard Unknown
# intersections must remain visible even when NOT OBSERVED. A qualifying
# probe can support several nested intersections; groups are not independent
# across cells. These are diagnostics, not additional threshold criteria.
CRITICAL_REAR_UNKNOWN_TAG_PAIRS = (
    ("back", "occluded"),
    ("appearance_shift", "back"),
    ("occluded", "partial"),
    ("appearance_shift", "partial"),
    ("back", "lookalike"),
    ("lookalike", "partial"),
)



def _decision(trial, floor, margin):
    _, _, _, truth, score, candidate, tied, gap = trial
    offered = (candidate is not None and not tied and score >= floor
               and (margin == 0 or (gap is not None and gap >= margin)))
    if not offered:
        return "abstain"
    return "correct" if candidate == truth else "wrong"




def _unknown_capture_dependency_components(qualifying):
    """Conservative Unknown correlation across person/event/lineage/session.

    Each tuple contains four opaque reviewed/declaration handles and whether
    this rear-facing Unknown probe produced a false review offer. Treat all
    probes transitively sharing *typed* handles as one correlated unit.
    This is a diagnostic, not a calibration/production acceptance gate.
    """
    parents = {}
    sizes = {}

    def find(key):
        if key not in parents:
            parents[key], sizes[key] = key, 1
        root = key
        while root != parents[root]:
            root = parents[root]
        while key != root:
            next_key = parents[key]
            parents[key] = root
            key = next_key
        return root

    # Count connected qualifying Unknown probes, not independently drawn
    # subjects/events. Different provenance dimensions never alias by token.
    entries = []
    for subject, event, lineage, session, false_offer in qualifying:
        keys = (("subject", subject), ("event", event),
                ("lineage", lineage), ("session", session))
        root = find(keys[0])
        for key in keys[1:]:
            a, b = find(root), find(key)
            if a != b:
                if sizes[a] < sizes[b]:
                    a, b = b, a
                parents[b] = a
                sizes[a] += sizes[b]
                root = a
        entries.append((keys[0], false_offer))

    components = defaultdict(lambda: {"probes": 0, "false": False})
    for key, false_offer in entries:
        group = components[find(key)]
        group["probes"] += 1
        group["false"] |= false_offer

    n = len(components)
    e = sum(group["false"] for group in components.values())
    risk = _upper_wilson(e, n)
    return {
        "qualifying_rear_unknown_probes": len(entries),
        "declared_dependency_nodes": len(parents),
        "connected_unknown_capture_components": n,
        "largest_component_probe_count": max(
            (g["probes"] for g in components.values()), default=0),
        "components_with_any_false_review_offer": e,
        "component_false_offer_wilson95_upper": (
            round(risk, 4) if risk is not None else None),
        "component_risk_exceeds_frozen_5pct_ceiling_diagnostic":
            risk is not None and risk > MAX_REAR_UNKNOWN_CALIBRATION_RISK,
        "unit_of_analysis": "connected_declared_subject_event_lineage_session",
        "declared_capture_independence_physically_verified": False,
        "diagnostic_not_calibration_or_release_gate": True,
        "auto_confirm_allowed": False,
    }



def _rear_unknown_controls(top, meta, split, floor, margin, min_groups, min_stratum,
                           *, strict_component_calibration=False):
    """Protect both calibration and holdout against front-only Unknown controls.

    A burst sharing an event or reviewed anonymous subject counts once.
    Error = any false review offer in that subject/event group. Numbers are
    diagnostics: unknown subjects, declared event IDs, and physical events
    are not proven statistically independent by these manifests.
    """
    def fresh():
        return {"subjects": set(), "events": set(),
                "false_subjects": set(), "false_events": set()}

    overall = fresh()
    by_tag = defaultdict(fresh)
    by_exact_rear_condition = defaultdict(fresh)
    qualifying_dependencies = []
    critical = {"+".join(pair): fresh()
                for pair in CRITICAL_REAR_UNKNOWN_TAG_PAIRS}
    for trial in top:
        probe, _, condition, truth, *_ = trial
        tags = set(condition.split("+"))
        if truth != "UNKNOWN" or "front" in tags or not (tags & {"back", "partial"}):
            continue
        source = meta[(split, probe)]
        subject, event = source["subject_cluster_uid"], source["event_cluster_uid"]
        if subject == "UNREVIEWED" or event == "UNREVIEWED":
            raise ValueError("Unknown rear subject and event must be reviewed")
        false_offer = _decision(trial, floor, margin) == "wrong"
        qualifying_dependencies.append((
            subject, event, source["photo_lineage_uid"], trial[1],
            false_offer))
        # Exact reviewed condition intersections require co-occurrence on
        # ONE qualifying Unknown rear probe, not separate same-subject
        # photos with disjoint tags. Input is already canonicalized by _parse.
        matching_critical = [
            critical["+".join(pair)]
            for pair in CRITICAL_REAR_UNKNOWN_TAG_PAIRS
            if set(pair).issubset(tags)
        ]
        for target in [overall, by_exact_rear_condition[condition],
                       *matching_critical, *(by_tag[tag] for tag in tags)]:
            target["subjects"].add(subject)
            target["events"].add(event)
            if false_offer:
                target["false_subjects"].add(subject)
                target["false_events"].add(event)

    def summarize(item):
        subjects, events = len(item["subjects"]), len(item["events"])
        wrong_subjects = len(item["false_subjects"])
        wrong_events = len(item["false_events"])
        return {
            "reviewed_unknown_subject_groups": subjects,
            "reviewed_unknown_event_groups": events,
            "groups_with_false_offers": wrong_subjects,
            "events_with_false_offers": wrong_events,
            "subject_false_offer_wilson95_upper": round(
                _upper_wilson(wrong_subjects, subjects), 4) if subjects else None,
            "event_false_offer_wilson95_upper": round(
                _upper_wilson(wrong_events, events), 4) if events else None,
        }
    def critical_summary(item):
        # Explicit absence is materially different from observed but
        # unpowered. Never print small-cell group counts or error counts;
        # only publish risk aggregates after BOTH independence denominators
        # meet the frozen descriptive stratum size.
        subject_count, event_count = len(item["subjects"]), len(item["events"])
        if subject_count == 0 and event_count == 0:
            return {"status": "not_observed", "metrics": None}
        if subject_count < min_stratum or event_count < min_stratum:
            return {"status": "observed_underpowered", "metrics": None}
        return {
            "status": "measured_not_safety_qualified",
            "metrics": summarize(item),
        }

    critical_status = {name: critical_summary(item)
                       for name, item in sorted(critical.items())}

    if (len(overall["subjects"]) < min_groups
            or len(overall["events"]) < min_groups):
        raise ValueError("Insufficient back/partial Unknown controls in calibration or holdout")
    # The global calibration floor can pass with plentiful easy front-facing
    # strangers yet be unsafe for rear-facing strangers. This additional
    # protected subgroup gate uses calibration ONLY and exact unrounded
    # subject/event Wilson bounds; heldout scores never select or veto it.
    if split == "calibration" and (
            _upper_wilson(len(overall["false_subjects"]), len(overall["subjects"]))
            > MAX_REAR_UNKNOWN_CALIBRATION_RISK
            or _upper_wilson(len(overall["false_events"]), len(overall["events"]))
            > MAX_REAR_UNKNOWN_CALIBRATION_RISK):
        raise ValueError("Rear Unknown calibration subject/event safety budget exceeded")
    component_diag = _unknown_capture_dependency_components(qualifying_dependencies)
    if split == "calibration" and strict_component_calibration:
        # The optional, separately preregistered v3 study MUST pass the
        # independent-source component risk bound, computed *unrounded*.
        # v1/v2 acceptance rules are immutable and remain unchanged.
        n = component_diag["connected_unknown_capture_components"]
        errors = component_diag["components_with_any_false_review_offer"]
        bound = _upper_wilson(errors, n)
        if bound is None or bound > MAX_REAR_UNKNOWN_CALIBRATION_RISK:
            raise ValueError("Rear Unknown calibration component safety budget exceeded")
    return {
        "overall": summarize(overall),
        "capture_dependency_component_safety": component_diag,
        "component_calibration_risk_gate_enforced":
            bool(strict_component_calibration and split == "calibration"),
        "by_rear_condition_tag": {
            k: summarize(v) for k, v in sorted(by_tag.items())
            if (len(v["subjects"]) >= min_stratum and len(v["events"]) >= min_stratum)
        },
        "by_exact_rear_condition": {
            k: summarize(v) for k, v in sorted(by_exact_rear_condition.items())
            if (len(v["subjects"]) >= min_stratum and len(v["events"]) >= min_stratum)
        },
        "suppressed_exact_rear_conditions": sum(
            len(v["subjects"]) < min_stratum or len(v["events"]) < min_stratum
            for v in by_exact_rear_condition.values()),
        "exact_rear_condition_cooccurrence_declared_on_one_probe": True,
        "critical_rear_unknown_intersection_coverage": critical_status,
        "critical_rear_intersections_measured_count": sum(
            cell["status"] == "measured_not_safety_qualified"
            for cell in critical_status.values()),
        "critical_rear_intersection_statuses_are_not_release_gates": True,
        "suppressed_condition_tags": sum(
            len(v["subjects"]) < min_stratum or len(v["events"]) < min_stratum
            for v in by_tag.values()),
        "declared_groups_independently_verified": False,
    }



def _capture_connected_component_counts(paired_nodes):
    """Count correlated pairs via a bounded, namespaced provenance graph.

    Each item has (capture_source_nodes, front_decision, rear_decision).
    Nodes with distinct provenance kinds cannot collide accidentally.
    Only aggregate counts escape; never serialize the opaque node keys.
    """
    parent, size = {}, {}

    def find(node):
        if node not in parent:
            parent[node] = node
            size[node] = 1
        root = node
        while root != parent[root]:
            root = parent[root]
        while node != root:
            following = parent[node]
            parent[node] = root
            node = following
        return root

    for nodes, _, _ in paired_nodes:
        anchor = find(nodes[0])
        for node in nodes[1:]:
            a, b = find(anchor), find(node)
            if a != b:
                if size[a] < size[b]:
                    a, b = b, a
                parent[b] = a
                size[a] += size[b]
                anchor = a

    components = defaultdict(list)
    for nodes, front, rear in paired_nodes:
        components[find(nodes[0])].append((front, rear))
    total = len(components)
    wrong = sum(any(rear == "wrong" for _, rear in outcomes)
                for outcomes in components.values())
    lost = sum(any(front == "correct" and rear == "wrong"
                   for front, rear in outcomes)
               for outcomes in components.values())
    abstained = sum(any(rear == "abstain" for _, rear in outcomes)
                    for outcomes in components.values())
    return {
        "nodes": len(parent),
        "components": total,
        "largest": max((len(rows) for rows in components.values()), default=0),
        "wrong": wrong,
        "lost": lost,
        "abstained": abstained,
        "wrong_wilson": round(_upper_wilson(wrong, total), 4) if total else None,
        "lost_wilson": round(_upper_wilson(lost, total), 4) if total else None,
    }


def _paired_event_component_diagnostics(paired_events):
    """Conservative declared-event-only connected component diagnostics."""
    counts = _capture_connected_component_counts([
        ((("event", front_event), ("event", rear_event)), front, rear)
        for front_event, rear_event, front, rear in paired_events
    ])
    return {
        "declared_event_nodes": counts["nodes"],
        "connected_capture_event_components": counts["components"],
        "pairs_in_largest_connected_component": counts["largest"],
        "components_with_any_rear_wrong_offer": counts["wrong"],
        "components_with_front_correct_rear_wrong": counts["lost"],
        "components_with_any_rear_abstention": counts["abstained"],
        "component_rear_wrong_offer_wilson95_upper": counts["wrong_wilson"],
        "component_front_correct_rear_wrong_wilson95_upper": counts["lost_wilson"],
        "unit_of_analysis": "connected_declared_capture_event_component",
        "component_independence_physically_verified": False,
        "component_bounds_are_research_diagnostics_not_release_gates": True,
    }


def _paired_multi_axis_capture_diagnostics(capture_dependencies):
    """Connect pairs sharing event, photo lineage OR session (transitively).

    Front/rear may be different events yet share photo families with other
    pairs. Independently typed source-equivalence dimensions give another
    conservative check without assuming all ID namespaces are globally
    unique. This is an audit of metadata, never proof of independence.
    """
    counts = _capture_connected_component_counts(capture_dependencies)
    return {
        "declared_dependency_nodes": counts["nodes"],
        "connected_event_lineage_session_components": counts["components"],
        "pairs_in_largest_connected_component": counts["largest"],
        "components_with_any_rear_wrong_offer": counts["wrong"],
        "components_with_front_correct_rear_wrong": counts["lost"],
        "components_with_any_rear_abstention": counts["abstained"],
        "component_rear_wrong_offer_wilson95_upper": counts["wrong_wilson"],
        "component_front_correct_rear_wrong_wilson95_upper": counts["lost_wilson"],
        "unit_of_analysis": "connected_declared_event_lineage_session_component",
        "axis_namespace_collisions_prevented": True,
        "component_independence_physically_verified": False,
        "component_bounds_are_research_diagnostics_not_release_gates": True,
    }



def audit_paired_pose_retention(score_rows, provenance_rows, roster_rows,
                                pair_rows, *, margin=0.0, min_condition_pairs=10,
                                min_unknown_rear_groups=20,
                                require_component_calibration_safety=False):
    """Summarize one frozen front/rear pair per known person on holdout only.

    All calibration and heldout probes require full opaque lineage, event,
    subject and exact candidate-roster declarations. Calibration alone chooses
    the floor. Within each pair the confirmed truth/subject must match but
    lineage, event and session must differ. No personal IDs enter the output.
    """
    if (type(min_condition_pairs) is not int or not 2 <= min_condition_pairs <= 1000
            or not isinstance(pair_rows, (tuple, list))
            or not 1 <= len(pair_rows) <= 10000):
        raise ValueError("Bounded reviewed pose-pair manifest required")
    if (type(margin) not in (float, int) or not 0 <= margin <= 1):
        raise ValueError("Finite preregistered top-two margin required")
    if type(require_component_calibration_safety) is not bool:
        raise ValueError("Component calibration policy must be explicitly boolean")
    if (type(min_unknown_rear_groups) is not int
            or not 20 <= min_unknown_rear_groups <= 1000):
        raise ValueError("At least 20 reviewed Unknown rear subject/event groups required")
    # Never let a holdout hard-negative influence the similarity floor.
    score_report = benchmark_model_scores(
        score_rows, min_top_two_margin=margin,
        provenance_rows=provenance_rows, require_provenance=True,
        candidate_roster_rows=roster_rows, require_candidate_roster=True,
        require_reviewed_unknown_subjects=True)
    split_scores = _parse(score_rows)
    verify_split_provenance(provenance_rows, split_scores)
    holdout = {row[0]: row for row in split_scores["holdout"]}
    meta = {(row["split"], row["probe_uid"]): row for row in provenance_rows}
    rear_unknown = {
        split: _rear_unknown_controls(split_scores[split], meta, split,
                                      score_report["similarity_floor"], margin,
                                      min_unknown_rear_groups, min_condition_pairs,
                                      strict_component_calibration=
                                      require_component_calibration_safety)
        for split in ("calibration", "holdout")
    }
    seen_probes, seen_people = set(), set()
    transitions = Counter()
    paired_event_outcomes = []
    capture_dependency_outcomes = []
    exact = defaultdict(Counter)
    marginal = defaultdict(Counter)
    for pair in pair_rows:
        if (not isinstance(pair, dict) or set(pair) != PAIR_FIELDS
                or any(type(pair[k]) is not str or not TOKEN.fullmatch(pair[k])
                       for k in PAIR_FIELDS)):
            raise ValueError("Invalid opaque pose-pair fields")
        front_id, rear_id = pair["front_probe_uid"], pair["rear_probe_uid"]
        if (front_id == rear_id or front_id in seen_probes
                or rear_id in seen_probes or front_id not in holdout
                or rear_id not in holdout):
            raise ValueError("Pose pair must use unique heldout probes")
        front, rear = holdout[front_id], holdout[rear_id]
        front_tags = set(_normalize_condition(front[2]).split("+"))
        rear_tags = set(_normalize_condition(rear[2]).split("+"))
        if ("front" not in front_tags or "back" in front_tags
                or "front" in rear_tags
                or not (rear_tags & {"back", "partial"})):
            raise ValueError("Explicit reviewed front versus back/partial conditions required")
        if (front[3] == "UNKNOWN" or front[3] != rear[3]
                or front[3] in seen_people or front[1] == rear[1]):
            raise ValueError("Unique same-known-person, different-session pair required")
        front_source = meta[("holdout", front_id)]
        rear_source = meta[("holdout", rear_id)]
        if (front_source["photo_lineage_uid"] == rear_source["photo_lineage_uid"]
                or front_source["event_cluster_uid"] == rear_source["event_cluster_uid"]
                or front_source["subject_cluster_uid"] == "UNREVIEWED"
                or front_source["subject_cluster_uid"] != rear_source["subject_cluster_uid"]):
            raise ValueError("Distinct reviewed lineage/event, matching subject required")
        # A same-session or duplicated crop cannot masquerade as a pose change.
        seen_probes.update((front_id, rear_id))
        seen_people.add(front[3])
        outcome = (_decision(front, score_report["similarity_floor"], margin),
                   _decision(rear, score_report["similarity_floor"], margin))
        transitions[outcome] += 1
        paired_event_outcomes.append((
            front_source["event_cluster_uid"], rear_source["event_cluster_uid"],
            outcome[0], outcome[1]))
        dependency_nodes = (
            ("event", front_source["event_cluster_uid"]),
            ("event", rear_source["event_cluster_uid"]),
            ("lineage", front_source["photo_lineage_uid"]),
            ("lineage", rear_source["photo_lineage_uid"]),
            ("session", front[1]),
            ("session", rear[1]),
        )
        capture_dependency_outcomes.append(
            (dependency_nodes, outcome[0], outcome[1]))
        exact[rear[2]][outcome] += 1
        for tag in rear_tags:
            marginal[tag][outcome] += 1

    def aggregate(counter):
        total = sum(counter.values())
        return {
            "reviewed_known_person_pairs": total,
            "transitions": {f"front_{a}_rear_{b}": counter[(a, b)]
                            for a in OUTCOMES for b in OUTCOMES},
            "front_correct_rear_wrong": counter[("correct", "wrong")],
            "front_correct_rear_abstained": counter[("correct", "abstain")],
            "rear_wrong_offer_pairs": sum(n for (_, b), n in counter.items() if b == "wrong"),
            "rear_abstention_pairs": sum(n for (_, b), n in counter.items() if b == "abstain"),
            "rear_correct_fraction": round(
                sum(n for (_, b), n in counter.items() if b == "correct") / total, 4
            ) if total else None,
        }

    return {
        "status": "frozen_paired_pose_retention_research_only",
        "calibration_locked_similarity_floor": score_report["similarity_floor"],
        "top_two_margin": margin,
        "overall": aggregate(transitions),
        "paired_event_component_safety": _paired_event_component_diagnostics(
            paired_event_outcomes),
        "paired_multi_axis_capture_dependency_safety":
            _paired_multi_axis_capture_diagnostics(capture_dependency_outcomes),
        "rear_unknown_controls": rear_unknown,
        "rear_unknown_calibration_wilson95_risk_ceiling": MAX_REAR_UNKNOWN_CALIBRATION_RISK,
        "rear_unknown_calibration_subject_event_bounds_passed": True,
        "rear_unknown_component_calibration_wilson95_gate_enforced":
            require_component_calibration_safety,
        "by_rear_condition": {k: aggregate(v) for k, v in sorted(exact.items())
                              if sum(v.values()) >= min_condition_pairs},
        "by_rear_condition_tag": {k: aggregate(v) for k, v in sorted(marginal.items())
                                  if sum(v.values()) >= min_condition_pairs},
        "suppressed_rear_conditions": sum(sum(v.values()) < min_condition_pairs
                                          for v in exact.values()),
        "suppressed_rear_tags": sum(sum(v.values()) < min_condition_pairs
                                    for v in marginal.values()),
        "paired_source_independence_physically_verified": False,
        "pair_selection_representative_or_exhaustive_proven": False,
        "face_visibility_from_pixels_verified": False,
        "calibrated_identity_probability": None,
        "unknown_always_allowed": True,
        "auto_confirm_allowed": False,
        "caveat": "Research on caller-declared disjoint captures and reviewed truth. "
                  "Back/partial person scores can fail through outfit or camera shortcut. "
                  "These are offered-review diagnostics, never identity decisions.",
    }
