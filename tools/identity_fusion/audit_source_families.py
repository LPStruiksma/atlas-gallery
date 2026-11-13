#!/usr/bin/env python3
"""Audit claimed source-family equivalence across V5 cue evidence.

A lineage source-family row says which *opaque* source references come from
one original photographic exposure. It is explicitly caller-declared and
NOT an independent image-lineage inference, image read or license decision.
No identities, file paths, faces, hashes or photos leave this module.
"""
from __future__ import annotations

from collections import defaultdict

from region_correspondence import TOKEN

FIELDS = frozenset(("source_reference", "lineage_family_uid"))
RESERVED = frozenset(("NONE", "UNKNOWN", "UNREVIEWED"))
MAX_SOURCES = 5000


def _opaque(v):
    return (isinstance(v, str) and TOKEN.fullmatch(v) is not None
            and v not in RESERVED)


def audit_lineage_source_groups(evidence_rows, source_lineage_rows) -> dict:
    """Fail closed for undeclared sources and same-family multi-group aliases.

    evidence_rows contain (candidate_person_id, source_reference,
    correlation_group) from one *read-only* observation. Different people
    can legitimately be competing hypotheses for the same source family;
    only repeated group claims for the SAME hypothesized candidate are
    treated as fabricated independent corroboration.
    """
    if (not isinstance(evidence_rows, (list, tuple))
            or not isinstance(source_lineage_rows, (list, tuple))
            or len(evidence_rows) > MAX_SOURCES
            or len(source_lineage_rows) > MAX_SOURCES):
        raise ValueError("Bounded opaque evidence and lineage metadata required")
    sources = set()
    for row in evidence_rows:
        if (not isinstance(row, (tuple, list)) or len(row) != 3
                or type(row[0]) is not int or row[0] <= 0
                or not _opaque(row[1]) or not _opaque(row[2])):
            raise ValueError("Invalid opaque evidence source or group metadata")
        sources.add(row[1])
    family_by_source = {}
    for row in source_lineage_rows:
        if not isinstance(row, dict) or set(row) != FIELDS:
            raise ValueError("Invalid source-family metadata columns")
        source, family = (row["source_reference"], row["lineage_family_uid"])
        if not _opaque(source) or not _opaque(family):
            raise ValueError("Invalid opaque source-family token")
        if source in family_by_source:
            raise ValueError("Duplicate or contradictory source-family mapping")
        family_by_source[source] = family
    if set(family_by_source) != sources:
        raise ValueError("Incomplete or extraneous source-family coverage")
    group_by_candidate_family = defaultdict(set)
    for person, source, group in evidence_rows:
        group_by_candidate_family[(person, family_by_source[source])].add(group)
    aliases = sum(len(groups) > 1
                  for groups in group_by_candidate_family.values())
    return {
        "status": ("declared_source_family_aliases_require_review"
                   if aliases else "declared_source_family_groups_consistent"),
        "cue_evidence_rows": len(evidence_rows),
        "declared_source_references": len(sources),
        "declared_source_families": len(set(family_by_source.values())),
        "same_candidate_source_family_group_conflicts": aliases,
        "complete_declared_lineage_coverage": True,
        "candidate_results_suppressed": aliases > 0,
        "actual_photo_lineage_independently_verified": False,
        "physical_source_independence_proven": False,
        "auto_confirm_allowed": False,
        "caveat": "Source families are caller-provided declarations, not "
                  "independently identified photographic lineages. Distinct "
                  "family labels do not prove distinct original exposures.",
    }
