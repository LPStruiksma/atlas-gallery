"""Photo DNA *investigation neighborhoods*, never inferred duplicate families.

Consume only verified-pair review JSON. Connectivity aids navigation, but
A--B + B--C never proves A--C. No photo reads, network, index writes or edits.
"""
from __future__ import annotations

import argparse
from collections import defaultdict
import json
from pathlib import Path

from .review_queue import MAX_EDGES, _read_json, normalize


def _bridge_edges(adjacency: dict[str, set[str]]) -> set[tuple[str, str]]:
    """Iterative Tarjan bridges: O(vertices + direct edges), no recursion."""
    entered: dict[str, int] = {}
    low: dict[str, int] = {}
    parent: dict[str, str | None] = {}
    bridges: set[tuple[str, str]] = set()
    clock = 0
    for source in sorted(adjacency):
        if source in entered:
            continue
        parent[source] = None
        entered[source] = low[source] = clock
        clock += 1
        stack = [(source, iter(sorted(adjacency[source])))]
        while stack:
            current, neighbors = stack[-1]
            try:
                neighbor = next(neighbors)
            except StopIteration:
                stack.pop()
                ancestor = parent[current]
                if ancestor is not None:
                    low[ancestor] = min(low[ancestor], low[current])
                    if low[current] > entered[ancestor]:
                        bridges.add(tuple(sorted((ancestor, current))))
                continue
            if neighbor == parent[current]:
                continue
            if neighbor not in entered:
                parent[neighbor] = current
                entered[neighbor] = low[neighbor] = clock
                clock += 1
                stack.append((neighbor, iter(sorted(adjacency[neighbor]))))
            else:
                low[current] = min(low[current], entered[neighbor])
    return bridges


def investigation_plan(payload: object, *, show_names: bool = False,
                       max_edges: int = MAX_EDGES) -> dict:
    """A bounded, privacy-first graph of *explicit* review edges only.

    The output keeps all direct verdicts visible. Components denote which
    photos need to be inspected together, NOT certified exposure families.
    """
    if type(show_names) is not bool:
        raise ValueError("show_names must be a boolean")
    rows, stats = normalize(payload, limit=max_edges)
    adjacency: dict[str, set[str]] = defaultdict(set)
    labels: dict[str, str] = {}
    explicitly_reviewed = []
    ignored_unknown = 0
    unknown_nodes = set()
    unknown_pair_edges = []
    for edge in rows:
        a, b = edge["a"], edge["b"]
        labels[a], labels[b] = edge["a_token"], edge["b_token"]
        if edge["verdict"] == "unknown":
            ignored_unknown += 1
            unknown_nodes.update((a, b))
            unknown_pair_edges.append((a, b))
            continue
        adjacency[a].add(b)
        adjacency[b].add(a)
        explicitly_reviewed.append(edge)
    visited = set()
    components = []
    component_by_source = {}
    for root in sorted(adjacency, key=lambda x: labels[x]):
        if root in visited:
            continue
        pending = [root]
        group = set()
        visited.add(root)
        while pending:
            current = pending.pop()
            group.add(current)
            for neighbor in adjacency[current]:
                if neighbor not in visited:
                    visited.add(neighbor)
                    pending.append(neighbor)
        cid = len(components)
        for source in group:
            component_by_source[source] = cid
        components.append({"sources": group, "edges": []})
    for edge in explicitly_reviewed:
        components[component_by_source[edge["a"]]]["edges"].append(edge)

    unknown_by_component = [0] * len(components)
    for a, b in unknown_pair_edges:
        left, right = component_by_source.get(a), component_by_source.get(b)
        if left is not None and left == right:
            unknown_by_component[left] += 1

    investigations = []
    for component_index, comp in enumerate(components):
        sources = comp["sources"]
        edges = comp["edges"]
        subgraph = {v: adjacency[v] for v in sources}
        bridge_pairs = _bridge_edges(subgraph)
        labels_sorted = sorted((labels[s] for s in sources))
        number = len(sources)
        observed = len(edges)
        total_pairs = number * (number - 1) // 2
        candidate_suggestions = sum(e["verdict"] == "candidate" for e in edges)
        review_links = observed - candidate_suggestions
        data = {
            "status": "investigation_only_not_a_confirmed_photo_family",
            "members": labels_sorted,
            "member_count": number,
            "direct_reviewable_pairs": observed,
            "direct_candidate_suggestions": candidate_suggestions,
            "direct_review_suggestions": review_links,
            "direct_unknown_pairs_within_neighborhood": unknown_by_component[component_index],
            "pairs_never_directly_assessed": total_pairs - observed - unknown_by_component[component_index],
            "bridge_edges_without_alternate_direct_path": len(bridge_pairs),
            "weak_bridge_examples": [
                sorted((labels[a], labels[b]))
                for a, b in sorted(bridge_pairs)
            ][:12],
            "evidence_edges": [
                {"pair": sorted((e["a_token"], e["b_token"])),
                 "verdict": e["verdict"],
                 "bridge": tuple(sorted((e["a"], e["b"]))) in bridge_pairs}
                for e in sorted(edges, key=lambda e: (e["a_token"], e["b_token"]))
            ],
        }
        if show_names:
            data["explicitly_private_source_paths"] = {
                labels[source]: source for source in sorted(sources, key=lambda x: labels[x])
            }
        investigations.append(data)
    investigations.sort(key=lambda x: (-x["member_count"], x["members"]))
    return {
        "policy": "Review neighborhood only; connected photos are NOT certified same exposure or deletable duplicates. A-B and B-C provide NO evidence for A-C. Confirm each relation independently.",
        "all_original_photos_untouched": True,
        "source_paths_included": show_names,
        "input_edges": stats["edges"],
        "reviewable_direct_edges": len(explicitly_reviewed),
        "unknown_edges_not_used_for_connectivity": ignored_unknown,
        "photos_with_only_unknown_edges": len(unknown_nodes - set(adjacency)),
        "investigation_neighborhoods": investigations,
        "component_count": len(investigations),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("evidence_json", type=Path)
    parser.add_argument("--show-names", action="store_true",
                        help="Explicitly disclose source paths in output JSON; keep it private")
    parser.add_argument("--max-edges", type=int, default=MAX_EDGES)
    args = parser.parse_args()
    try:
        report = investigation_plan(_read_json(args.evidence_json),
                                    show_names=args.show_names,
                                    max_edges=args.max_edges)
    except (ValueError, OSError) as exc:
        parser.error(str(exc))
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
