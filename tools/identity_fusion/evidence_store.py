#!/usr/bin/env python3
"""ATLAS V5: local, versioned, review-only identity evidence (prototype).

No face/body model, biometric inference, photo access, private V4 DB access,
confirmed People writes, automatic merges or network calls are performed.
Use only a *dedicated* local SQLite database; a live V4 or native DB is refused.
"""
from __future__ import annotations

import re
import sqlite3
from pathlib import PurePosixPath

SCHEMA_VERSION = 1
VISIBILITY = frozenset({"front", "back", "side", "partial", "unknown"})
FAMILY_PRIORITY = {"face": 5, "body": 4, "session": 3, "clothing": 2, "context": 1}
POLARITY = frozenset({"supports", "contradicts"})
HASH = re.compile(r"^[0-9a-fA-F]{64}$")
ALLOWED_PATH = "/storage/emulated/0/"


def initialize(connection: sqlite3.Connection) -> None:
    """Create or reopen ONLY the dedicated ATLAS V5 evidence schema, fail closed.

    Never attach another SQLite DB or modify an existing V4/native database.
    """
    connection.execute("PRAGMA foreign_keys=ON")
    names = {r[0] for r in connection.execute(
        "SELECT name FROM sqlite_master WHERE type='table' "
        "AND name NOT LIKE 'sqlite_%'"
    )}
    version = connection.execute("PRAGMA user_version").fetchone()[0]
    if names and ("atlas_identity_evidence_metadata" not in names
                  or version != SCHEMA_VERSION):
        raise ValueError("Not a dedicated compatible V5 evidence database")
    if not names and version != 0:
        raise ValueError("Unexpected SQLite user_version")
    if names:
        marker = connection.execute(
            "SELECT value FROM atlas_identity_evidence_metadata WHERE key='format'"
        ).fetchone()
        if marker != ("atlas-v5-evidence-v1",):
            raise ValueError("Wrong evidence-store identity")
        required = {"atlas_identity_evidence_metadata", "assets",
                    "observations", "candidate_evidence"}
        if not required.issubset(names):
            raise ValueError("Incomplete evidence-store schema")
        columns = {r[1] for r in connection.execute(
            "PRAGMA table_info(observations)"
        )}
        if "occlusion_known" not in columns:
            # Safe, additive migration of original v1 local evidence files:
            # all historical v1 callers supplied a measured fraction.
            # Never guess unknown values for pre-existing observations.
            with connection:
                connection.execute(
                    "ALTER TABLE observations ADD COLUMN occlusion_known "
                    "INTEGER NOT NULL DEFAULT 1 "
                    "CHECK(occlusion_known IN (0,1))"
                )
        return

    with connection:
        connection.executescript("""
            CREATE TABLE atlas_identity_evidence_metadata(
                key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL
            );
            INSERT INTO atlas_identity_evidence_metadata(key,value)
                VALUES ('format','atlas-v5-evidence-v1');
            CREATE TABLE assets(
                asset_uid TEXT PRIMARY KEY NOT NULL,
                exact_path TEXT NOT NULL,
                fingerprint_sha256 TEXT,
                observed_at_ms INTEGER,
                CHECK (length(asset_uid) >= 1)
            );
            CREATE TABLE observations(
                observation_uid TEXT PRIMARY KEY NOT NULL,
                asset_uid TEXT NOT NULL REFERENCES assets(asset_uid),
                region_x REAL NOT NULL CHECK(region_x >= 0 AND region_x <= 1),
                region_y REAL NOT NULL CHECK(region_y >= 0 AND region_y <= 1),
                region_w REAL NOT NULL CHECK(region_w > 0 AND region_w <= 1),
                region_h REAL NOT NULL CHECK(region_h > 0 AND region_h <= 1),
                visibility TEXT NOT NULL CHECK(
                    visibility IN ('front','back','side','partial','unknown')),
                occlusion_fraction REAL NOT NULL CHECK(
                    occlusion_fraction >= 0 AND occlusion_fraction <= 1),
                occlusion_known INTEGER NOT NULL DEFAULT 1 CHECK(
                    occlusion_known IN (0,1)),
                observed_at_ms INTEGER
            );
            CREATE TABLE candidate_evidence(
                evidence_uid TEXT PRIMARY KEY NOT NULL,
                observation_uid TEXT NOT NULL
                    REFERENCES observations(observation_uid),
                candidate_person_id INTEGER NOT NULL
                    CHECK(candidate_person_id > 0),
                family TEXT NOT NULL CHECK(
                    family IN ('face','body','clothing','session','context')),
                polarity TEXT NOT NULL CHECK(
                    polarity IN ('supports','contradicts')),
                correlation_group TEXT NOT NULL CHECK(length(correlation_group) > 0),
                method_version TEXT NOT NULL CHECK(length(method_version) > 0),
                source_reference TEXT NOT NULL CHECK(length(source_reference) > 0),
                -- IMPORTANT: no probability, no inferred confirmation state,
                -- and no embeddings stored in this schema prototype.
                created_at_ms INTEGER
            );
            CREATE INDEX candidate_review_lookup
                ON candidate_evidence(observation_uid, candidate_person_id);
            PRAGMA user_version=1;
        """)


def add_asset(connection: sqlite3.Connection, *,
              uid: str, exact_path: str, fingerprint_sha256: str | None = None,
              observed_at_ms: int | None = None) -> None:
    if not uid or not isinstance(uid, str):
        raise ValueError("Stable caller-supplied asset ID required")
    if (not isinstance(exact_path, str)
            or not exact_path.startswith(ALLOWED_PATH)
            or ".." in PurePosixPath(exact_path).parts
            or exact_path.endswith("/")):
        raise ValueError("Exact local absolute media path required")
    if fingerprint_sha256 is not None and not HASH.fullmatch(fingerprint_sha256):
        raise ValueError("Content hash must be SHA-256")
    # Duplicate asset IDs are NOT silently remapped after a move/crop/rotation.
    # A changed path requires an explicit reconciled source identity.
    with connection:
        connection.execute(
            "INSERT INTO assets(asset_uid,exact_path,fingerprint_sha256,observed_at_ms) "
            "VALUES(?,?,?,?)",
            (uid, exact_path, fingerprint_sha256, observed_at_ms)
        )


def add_observation(connection: sqlite3.Connection, *,
                    uid: str, asset_uid: str, region: tuple[float, float, float, float],
                    visibility: str, occlusion_fraction: float | None,
                    observed_at_ms: int | None = None) -> None:
    if not uid or not asset_uid or len(region) != 4:
        raise ValueError("Observation identity and 4D region are required")
    x, y, w, h = region
    # None means *not measured*, never "unoccluded". The numeric column
    # is a sentinel in that case and must not be used as observational proof.
    occlusion_known = 0 if occlusion_fraction is None else 1
    safe_occlusion = 0.0 if occlusion_fraction is None else occlusion_fraction
    # Reject NaN/infinity, overhanging crops, meaningless region extents.
    if (any(not isinstance(n, (int, float)) or not (0 <= n <= 1)
            for n in (x, y, w, h, safe_occlusion))
            or w <= 0 or h <= 0 or x + w > 1.00000001 or y + h > 1.00000001
            or visibility not in VISIBILITY):
        raise ValueError("Invalid normalized person-region or visibility")
    with connection:
        connection.execute(
            "INSERT INTO observations("
            "observation_uid,asset_uid,region_x,region_y,region_w,region_h,"
            "visibility,occlusion_fraction,occlusion_known,observed_at_ms) "
            "VALUES(?,?,?,?,?,?,?,?,?,?)",
            (uid, asset_uid, x, y, w, h, visibility, safe_occlusion,
             occlusion_known, observed_at_ms)
        )


def add_candidate_evidence(connection: sqlite3.Connection, *,
                           uid: str, observation_uid: str, person_id: int,
                           family: str, polarity: str, correlation_group: str,
                           method_version: str, source_reference: str,
                           created_at_ms: int | None = None) -> None:
    """Store an unconfirmed hypothesis only. Caller cannot set 'confirmed'."""
    if (not uid or not observation_uid or not isinstance(person_id, int)
            or isinstance(person_id, bool) or person_id <= 0
            or family not in FAMILY_PRIORITY or polarity not in POLARITY
            or not correlation_group or not method_version or not source_reference):
        raise ValueError("Invalid review-only evidence metadata")
    with connection:
        connection.execute(
            "INSERT INTO candidate_evidence("
            "evidence_uid,observation_uid,candidate_person_id,family,polarity,"
            "correlation_group,method_version,source_reference,created_at_ms) "
            "VALUES(?,?,?,?,?,?,?,?,?)",
            (uid, observation_uid, person_id, family, polarity,
             correlation_group, method_version, source_reference, created_at_ms)
        )


def review_candidates(connection: sqlite3.Connection,
                      observation_uid: str, limit: int = 12,
                      allowed_families: frozenset[str] | None = None) -> dict:
    """Explain evidence *groups*, not identity probabilities or confirmations.

    Several cues from one photo session count as ONE correlated group. Supports
    and contradictions are surfaced together. Caller may choose Unknown; this
    module deliberately has no confirm, assign, export or delete API.
    """
    if not 1 <= limit <= 100:
        raise ValueError("Bound the review candidate list")
    if allowed_families is not None:
        if (not isinstance(allowed_families, frozenset)
                or not allowed_families
                or not allowed_families <= FAMILY_PRIORITY.keys()):
            raise ValueError("Allowed evidence families must be a nonempty known frozenset")
    exists = connection.execute(
        "SELECT 1 FROM observations WHERE observation_uid=?", (observation_uid,)
    ).fetchone()
    if not exists:
        raise ValueError("Unknown observation")
    rows = connection.execute(
        "SELECT candidate_person_id,family,polarity,correlation_group "
        "FROM candidate_evidence WHERE observation_uid=? "
        "ORDER BY evidence_uid",
        (observation_uid,)
    ).fetchall()
    if allowed_families is not None:
        rows = [row for row in rows if row[1] in allowed_families]
    groups: dict[int, dict[str, dict]] = {}
    for person_id, family, polarity, group in rows:
        by_group = groups.setdefault(person_id, {})
        entry = by_group.setdefault(group, {
            "families": set(), "supports": False, "contradicts": False
        })
        entry["families"].add(family)
        entry[polarity] = True
    proposals = []
    for pid, by_group in groups.items():
        positives = [g for g in by_group.values() if g["supports"]]
        contradictions = [g for g in by_group.values() if g["contradicts"]]
        families = sorted(
            set().union(*(g["families"] for g in by_group.values())),
            key=lambda f: (-FAMILY_PRIORITY[f], f)
        )
        proposals.append({
            "candidate_person_id": pid,
            "independent_support_groups": len(positives),
            "positive_face_support_groups": sum(
                g["supports"] and "face" in g["families"]
                for g in by_group.values()),
            "contradicting_groups": len(contradictions),
            "evidence_families": families,
            "review_only": True,
            "confidence_probability": None
        })
    proposals.sort(key=lambda p: (
        p["contradicting_groups"] > 0,
        -p["independent_support_groups"],
        -max((FAMILY_PRIORITY[f] for f in p["evidence_families"]), default=0),
        p["candidate_person_id"]
    ))
    # The last numeric-ID sort key is ONLY to keep presentation stable.
    # Even a UI requesting limit=1 must learn that its top displayed
    # candidate may be tied with another eligible person outside the slice.
    top, runner = ((proposals + [None, None])[:2])
    def evidence_rank(p):
        return (p["independent_support_groups"],
                max((FAMILY_PRIORITY[f] for f in p["evidence_families"]),
                    default=0))
    top_rank_tied = bool(
        top and runner and not top["contradicting_groups"]
        and not runner["contradicting_groups"]
        and evidence_rank(top) == evidence_rank(runner)
    )
    # Even a limit=1 adapter must see when the best body/session proposal
    # competes with non-contradicted face support for a *different* person.
    # Do not decide that one modality proves identity: request human review
    # of both or defer as Unknown.
    competing_face = bool(
        top and top["independent_support_groups"] >= 2
        and not top["contradicting_groups"]
        and top["positive_face_support_groups"] == 0
        and any(p["candidate_person_id"] != top["candidate_person_id"]
                and p["positive_face_support_groups"] > 0
                and p["contradicting_groups"] == 0
                for p in proposals[1:])
    )
    return {
        "observation_uid": observation_uid,
        "status": "unconfirmed_suggestions_only",
        "unknown_always_allowed": True,
        "top_nonface_competing_face_evidence": competing_face,
        "top_rank_tied_on_evidence": top_rank_tied,
        "numeric_person_id_is_presentation_tiebreak_only": True,
        "auto_confirm_allowed": False,
        "proposals": proposals[:limit],
        "total_candidates": len(proposals),
        "caveat": "Correlated evidence groups are NOT calibrated probabilities. "
                  "Never auto-confirm or merge People."
    }
