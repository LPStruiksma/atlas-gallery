#!/usr/bin/env python3
"""Offline preflight of a fictional/authorized virtual-viewpoint *metadata* CSV.

No image paths, pixels, pretrained weights, embedding values, filenames,
external downloads or People records are accepted or accessed. This validates
reported enrollment/capture dependencies, not media licenses or independence.
"""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
import csv
import json
import os
import re
from pathlib import Path
import stat
import sys

from benchmark_reid_scores import CONDITIONS, TOKEN, _normalize_condition
from verify_candidate_artifact import _open_regular_no_alias

FIELDS = (
    "virtual_character_uid", "split", "camera_uid", "view_index",
    "role", "reviewed_visibility", "capture_component_uid",
)
SPLITS = ("calibration", "holdout")
KNOWN_ROLES = frozenset(("gallery_known", "query_known"))
ROLES = KNOWN_ROLES | frozenset(("query_unknown",))
MIN_KNOWN_REAR_PAIRS = 20
MIN_UNKNOWN_REAR_CHARACTERS = 52
MAX_ROWS = 100_000
MAX_BYTES = 8 * 1024 * 1024
VISIBLE_TAGS = CONDITIONS - frozenset(("unknown",))
DATASET_PROFILES = ("opaque", "personx_v1")
PERSONX_CHARACTER = re.compile(r"[0-9]{4}\Z")
PERSONX_MAX_CHARACTER_ID = 1266


def _has_independent_view_pair(fronts, backs):
    """Linear-time existence of camera- and capture-disjoint front/rear.

    For each front, count backs with a *different* camera AND source,
    by inclusion/exclusion. A quadratic cartesian search can become
    unexpectedly costly on an authorized many-view image manifest.
    """
    if not fronts or not backs:
        return False
    by_camera = Counter(cam for cam, _ in backs)
    by_source = Counter(source for _, source in backs)
    by_both = Counter(backs)
    return any(len(backs) - by_camera[cam] - by_source[source]
               + by_both[(cam, source)] > 0
               for cam, source in fronts)


def _tags(value):
    label = _normalize_condition(value)
    if label != value:
        raise ValueError("Viewpoint visibility labels must be canonical")
    tags = frozenset(label.split("+")) if label != "unknown" else frozenset()
    if "front" in tags and "back" in tags:
        raise ValueError("Contradictory front and rear visibility review")
    return tags


def audit_virtual_viewpoint_manifest(rows, *, require_unknown_tags=(),
                                     dataset_profile="opaque"):
    """Verify declared source-character partition and rear evidence structure.

    A virtual character is one dependency unit despite many views, scenes,
    crops and transformations. Two different camera tokens may STILL be
    dependent renders of one mesh; nothing proves physical independence.
    """
    if type(rows) not in (list, tuple) or not 1 <= len(rows) <= MAX_ROWS:
        raise ValueError("Bounded viewpoint-metadata inventory required")
    if type(dataset_profile) is not str or dataset_profile not in DATASET_PROFILES:
        raise ValueError("Unsupported virtual dataset profile")
    if (type(require_unknown_tags) not in (tuple, list)
            or len(require_unknown_tags) > len(VISIBLE_TAGS)
            or any(type(tag) is not str or tag not in VISIBLE_TAGS
                   for tag in require_unknown_tags)
            or len(set(require_unknown_tags)) != len(require_unknown_tags)):
        raise ValueError("Invalid requested reviewed Unknown visibility tags")
    protected = tuple(sorted(require_unknown_tags))
    persons = {}
    frames = set()
    captures = {}
    by_person = defaultdict(list)
    for row in rows:
        if type(row) is not dict or set(row) != set(FIELDS):
            raise ValueError("Unexpected or private viewpoint metadata")
        if any(type(row[field]) is not str for field in FIELDS):
            raise ValueError("Non-text viewpoint evidence")
        who, split, camera, view, role, condition, capture = (
            row[field] for field in FIELDS)
        if (not TOKEN.fullmatch(who) or who in ("UNKNOWN", "NONE", "UNREVIEWED")
                or not TOKEN.fullmatch(camera)
                or not TOKEN.fullmatch(capture)
                or capture in ("NONE", "UNREVIEWED")
                or split not in SPLITS or role not in ROLES
                or view not in tuple(str(i) for i in range(1, 37))):
            raise ValueError("Missing or invalid virtual character/capture metadata")
        if who.lstrip("0") == "":
            # PersonX's ID 0 is explicitly a duplicate of ID 1 used
            # to initialize the Unity capture controller.
            raise ValueError("A reserved duplicate initialization ID cannot count")
        if dataset_profile == "personx_v1" and (
            PERSONX_CHARACTER.fullmatch(who) is None
            or not 1 <= int(who) <= PERSONX_MAX_CHARACTER_ID
        ):
            raise ValueError("Noncanonical or out-of-range PersonX character ID")
        tags = _tags(condition)
        if who in persons and persons[who] != split:
            raise ValueError("Virtual identity reused between calibration and holdout")
        persons[who] = split
        frame = (who, camera, view)
        if frame in frames:
            raise ValueError("One virtual render view assigned multiple trial roles")
        frames.add(frame)
        prior = captures.setdefault(capture, (who, split))
        if prior != (who, split):
            raise ValueError("Capture source reused between people or partitions")
        by_person[who].append((role, camera, capture, tags))

    report = {}
    for split in SPLITS:
        known = 0
        eligible_pairs = 0
        unknown = 0
        unknown_back = 0
        unknown_tag_subjects = defaultdict(int)
        known_front_galleries = 0
        known_back_queries = 0
        reviewed_unknown_capture_components = set()
        unknown_unreviewed = 0
        split_cameras = set()
        for who, person_split in persons.items():
            if split != person_split:
                continue
            events = by_person[who]
            split_cameras.update(camera for _, camera, _, _ in events)
            roles = {row[0] for row in events}
            if "query_unknown" in roles:
                if roles != {"query_unknown"}:
                    raise ValueError("Unknown virtual identity mixed with enrolled known")
                unknown += 1
                present = {t for _, _, _, tags in events for t in tags}
                if "back" in present:
                    unknown_back += 1
                unknown_unreviewed += sum(not tags for _, _, _, tags in events)
                for tag in present:
                    unknown_tag_subjects[tag] += 1
                for _, _, capture, tags in events:
                    if "back" in tags:
                        reviewed_unknown_capture_components.add(capture)
            else:
                if roles != KNOWN_ROLES:
                    raise ValueError("Known person requires both gallery and query roles")
                # The old existential pairing check could pass a known
                # identity with an independent pair while OTHER gallery/
                # query entries silently reused the very same render source.
                gallery_sources = {src for role, _, src, _ in events
                                   if role == "gallery_known"}
                query_sources = {src for role, _, src, _ in events
                                 if role == "query_known"}
                if gallery_sources & query_sources:
                    raise ValueError("One known gallery and query reuse a capture")
                known += 1
                fronts = [(cam, cap) for role, cam, cap, tags in events
                          if role == "gallery_known" and "front" in tags
                          and "back" not in tags]
                backs = [(cam, cap) for role, cam, cap, tags in events
                         if role == "query_known" and "back" in tags
                         and "front" not in tags]
                known_front_galleries += bool(fronts)
                known_back_queries += bool(backs)
                if _has_independent_view_pair(fronts, backs):
                    eligible_pairs += 1
        if (known < MIN_KNOWN_REAR_PAIRS
                or eligible_pairs < MIN_KNOWN_REAR_PAIRS
                or unknown < MIN_UNKNOWN_REAR_CHARACTERS
                or unknown_back < MIN_UNKNOWN_REAR_CHARACTERS):
            raise ValueError("Too few virtual subjects with reviewed rear-view evidence")
        for tag in protected:
            if unknown_tag_subjects[tag] < MIN_UNKNOWN_REAR_CHARACTERS:
                raise ValueError("Missing preregistered Unknown viewpoint coverage")
        report[split] = {
            "distinct_virtual_known_characters": known,
            "distinct_virtual_unknown_characters": unknown,
            "known_with_front_gallery": known_front_galleries,
            "known_with_rear_query": known_back_queries,
            "known_with_cross_camera_and_source_front_rear_pair": eligible_pairs,
            "unknown_with_reviewed_back_view": unknown_back,
            "unknown_back_declared_capture_components":
                len(reviewed_unknown_capture_components),
            "unknown_with_unreviewed_visibility_rows": unknown_unreviewed,
            "unknown_characters_by_marginal_visibility_tag": {
                k: unknown_tag_subjects[k] for k in sorted(unknown_tag_subjects)
            },
            "distinct_declared_camera_tokens": len(split_cameras),
            "camera_distinction_proves_independent_image_generation": False,
            "subject_character_independence_proven": False,
        }
    return {
        "status": "virtual_metadata_structure_only_NOT_DATASET_ADMISSION",
        "dataset_profile_declared_not_authenticated": dataset_profile,
        "personx_duplicate_zero_and_noncanonical_ids_refused":
            dataset_profile == "personx_v1",
        "all_known_gallery_query_capture_role_overlap_refused": True,
        "dataset_rights_or_assets_verified": False,
        "remote_archive_bytes_or_origin_authenticated": False,
        "viewpoint_index_to_physical_front_back_verified": False,
        "pose_labels_independently_reviewed": False,
        "model_training_overlap_excluded_by_external_evidence": False,
        "genuine_holdout_chronology_authenticated": False,
        "required_unknown_visibility_tags": list(protected),
        "same_virtual_character_cross_partition_leakage_refused": True,
        "known_gallery_query_same_capture_refused": True,
        "nominal_min_known_characters_per_split": MIN_KNOWN_REAR_PAIRS,
        "nominal_min_unknown_back_characters_per_split":
            MIN_UNKNOWN_REAR_CHARACTERS,
        "nominal_minima_prove_statistical_error_budget": False,
        "partitions": report,
        "human_review_required": True,
        "auto_confirm_allowed": False,
        "production_approved": False,
        "caveat": (
            "Unverified declared virtual IDs, view labels, camera/source "
            "equivalence and consent cannot establish real image identity "
            "or scene/source independence. All appearance benchmarks and "
            "training/asset rights are blocked pending independent evidence."
        ),
    }


def _read_metadata(path):
    """Bounded descriptor-anchored CSV read; refuse symlinked ancestors."""
    fd = _open_regular_no_alias(path)
    with os.fdopen(fd, "rb") as handle:
        before = os.fstat(handle.fileno())
        if (not stat.S_ISREG(before.st_mode) or before.st_nlink != 1
                or not 0 < before.st_size <= MAX_BYTES):
            raise ValueError("Unsafe source manifest size or alias")
        data = handle.read(MAX_BYTES + 1)
        after = os.fstat(handle.fileno())
        if (len(data) != before.st_size
                or (before.st_dev, before.st_ino, before.st_size,
                    before.st_mtime_ns, before.st_ctime_ns)
                != (after.st_dev, after.st_ino, after.st_size,
                    after.st_mtime_ns, after.st_ctime_ns)):
            raise ValueError("Source manifest changed while reading")
    import io
    with io.StringIO(data.decode("utf-8"), newline="") as stream:
        reader = csv.DictReader(stream, restkey="_overflow", restval=None)
        if reader.fieldnames != list(FIELDS):
            raise ValueError("Incomplete or unexpected viewpoint metadata header")
        rows = []
        for row in reader:
            if set(row) != set(FIELDS) or any(v is None for v in row.values()):
                raise ValueError("Malformed viewpoint-metadata row")
            rows.append(row)
            if len(rows) > MAX_ROWS:
                raise ValueError("Too many viewpoint-metadata rows")
        return rows


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("metadata_csv", type=Path)
    parser.add_argument("--require-unknown-tag", action="append", default=[])
    parser.add_argument("--dataset-profile", choices=DATASET_PROFILES,
                        default="opaque")
    args = parser.parse_args(argv)
    try:
        report = audit_virtual_viewpoint_manifest(
            _read_metadata(args.metadata_csv),
            require_unknown_tags=args.require_unknown_tag,
            dataset_profile=args.dataset_profile)
        print(json.dumps(report, indent=2, sort_keys=True))
        return 0
    except (OSError, UnicodeError, ValueError, TypeError, KeyError):
        print("Refused incomplete, unsafe or unlicensed-viewpoint metadata evidence",
              file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
