"""Read-only, offline, scene-separated Photo DNA exposure audit.

The manifest is manually curated and hash-pinned; no downloads, caching, or
writes are performed. This script NEVER certifies two exposures as duplicates.
Only condition-level aggregates are printed (no paths/photos/EXIF).
"""
from __future__ import annotations

import argparse
from collections import defaultdict
import hashlib
from itertools import combinations
import json
import os
from pathlib import Path
import re
import stat
import statistics
import time
from urllib.parse import urlparse

import cv2
import numpy as np

from .matcher import compare_arrays, read_grayscale, _open_nofollow_components
from .scene_risk import summarize_scene_exposure_risk
from .edit_retention import EDITS, summarize_edit_retention
from .provenance_guard import validate_provenance_groups
from .license_reference_guard import consistent_license_reference

MAX_MANIFEST_BYTES = 1_000_000
MAX_SCENES = 20
MAX_CAPTURE_PER_SCENE = 8
SUPPORTED_LICENSES = {'CC0-1.0', 'CC-BY-4.0', 'CC-BY-SA-4.0', 'PUBLIC-DOMAIN'}
SHA256 = re.compile(r'[0-9a-f]{64}\Z')
EDIT_NAMES = EDITS


def _url(value: object) -> bool:
    return isinstance(value, str) and urlparse(value).scheme == 'https' and bool(urlparse(value).netloc)


def _sha256(path: Path) -> str:
    """Hash the opened regular-file descriptor, never a newly followed symlink.

    A previously validated path is not a capability: every input read must
    independently refuse ancestor/leaf symlink swaps. This also detects
    in-place mutation during the hash pass before evaluation proceeds.
    """
    digest = hashlib.sha256()
    try:
        fd = _open_nofollow_components(path)
    except OSError as exc:
        raise ValueError('Capture path became unsafe before checksum') from exc
    with os.fdopen(fd, 'rb') as stream:
        before = os.fstat(stream.fileno())
        if not stat.S_ISREG(before.st_mode):
            raise ValueError('Capture must remain a regular file')
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
        after = os.fstat(stream.fileno())
        key = lambda st: (st.st_dev, st.st_ino, st.st_size, st.st_mtime_ns, st.st_ctime_ns)
        if key(before) != key(after):
            raise ValueError('Capture changed during checksum')
    return digest.hexdigest()


def _safe_path(root: Path, relative: object) -> Path:
    if not isinstance(relative, str) or not relative or '\x00' in relative:
        raise ValueError('Invalid relative file name')
    p = Path(relative)
    if p.is_absolute() or any(x in ('.', '..') for x in p.parts):
        raise ValueError('Only nontraversing relative files are permitted')
    current = root
    for name in p.parts:
        current = current / name
        if current.is_symlink():
            raise ValueError('Symlink encountered under evaluation root')
    if not current.is_file() or not current.resolve().is_relative_to(root):
        raise ValueError('Image must be a regular file inside evaluation root')
    return current


def _read_strict_manifest(manifest: Path) -> dict:
    """Descriptor-bound, size-capped JSON with no ambiguous duplicate keys.

    Duplicate keys are last-wins under ordinary json.loads. In a provenance
    manifest, duplicated version, file, SHA, scene or license metadata can
    hide contradictory curator declarations and silently bias the holdout.
    """
    try:
        fd = _open_nofollow_components(manifest)
    except OSError as exc:
        raise ValueError('Manifest path unsafe or changed') from exc
    with os.fdopen(fd, 'rb') as stream:
        before = os.fstat(stream.fileno())
        if not stat.S_ISREG(before.st_mode) or before.st_size > MAX_MANIFEST_BYTES:
            raise ValueError('Expected a bounded regular manifest')
        raw = stream.read(MAX_MANIFEST_BYTES + 1)
        after = os.fstat(stream.fileno())
        key = lambda st: (st.st_dev, st.st_ino, st.st_size, st.st_mtime_ns, st.st_ctime_ns)
        if len(raw) > MAX_MANIFEST_BYTES or len(raw) != before.st_size or key(before) != key(after):
            raise ValueError('Manifest changed during bounded read')

    def reject_duplicate_properties(pairs):
        obj = {}
        for key, value in pairs:
            if key in obj:
                raise ValueError('Duplicate provenance JSON property')
            obj[key] = value
        return obj

    def reject_nonfinite(number):
        raise ValueError('Nonfinite provenance JSON number')

    try:
        return json.loads(raw.decode('utf-8'),
                          object_pairs_hook=reject_duplicate_properties,
                          parse_constant=reject_nonfinite)
    except (UnicodeError, json.JSONDecodeError) as exc:
        raise ValueError('Malformed UTF-8 JSON manifest') from exc


def _validate_manifest(manifest: Path, root: Path, split: str | None):
    if root.is_symlink() or not root.is_dir():
        raise ValueError('Expected an existing non-symlink image root')
    root = root.resolve()
    data = _read_strict_manifest(manifest)
    if not isinstance(data, dict):
        raise ValueError('Manifest must be a JSON object')
    validate_provenance_groups(data.get('version'), data.get('scenes'))
    if not 1 <= len(data['scenes']) <= MAX_SCENES:
        raise ValueError('Expected 1..20 scenes')
    scenes = []
    seen_scenes, seen_ids, seen_hashes = set(), set(), set()
    for scene in data['scenes']:
        if not isinstance(scene, dict):
            raise ValueError('Invalid scene record')
        name, partition = scene.get('id'), scene.get('split')
        if not isinstance(name, str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,64}', name) or name in seen_scenes:
            raise ValueError('Scene IDs must be nonempty, safe and unique')
        if partition not in ('development', 'holdout'):
            raise ValueError('Scene split must be development or holdout')
        seen_scenes.add(name)
        captures = scene.get('captures')
        if not isinstance(captures, list) or not 2 <= len(captures) <= MAX_CAPTURE_PER_SCENE:
            raise ValueError('Every scene must contain 2..8 independent captures')
        accepted = []
        for item in captures:
            if not isinstance(item, dict):
                raise ValueError('Invalid capture record')
            cid = item.get('id')
            if not isinstance(cid, str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,64}', cid) or cid in seen_ids:
                raise ValueError('Capture IDs must be safe and globally unique')
            seen_ids.add(cid)
            if item.get('provenance') != 'independent_shutter' or len(str(item.get('provenance_note', ''))) < 24:
                raise ValueError('Each independent shutter claim needs a provenance note (>=24 chars)')
            if not isinstance(item.get('author'), str) or not item['author'].strip():
                raise ValueError('Author attribution is required')
            if (item.get('license') not in SUPPORTED_LICENSES
                    or not consistent_license_reference(item.get('license'), item.get('license_url'))
                    or not _url(item.get('source_url'))):
                raise ValueError('Auditable, label-consistent license reference and HTTPS source URL required')
            expected = item.get('sha256')
            if not isinstance(expected, str) or not SHA256.fullmatch(expected):
                raise ValueError('SHA256 digest required for each independent capture')
            if expected in seen_hashes:
                raise ValueError('Duplicate bytes incorrectly labeled as independent exposures')
            seen_hashes.add(expected)
            accepted.append((cid, item))
        if split is None or partition == split:
            scenes.append((name, accepted))
    if not scenes:
        raise ValueError('No scenes in requested evaluation split')
    return root, scenes


def _derived_edits(gray: np.ndarray):
    h, w = gray.shape
    gamma = np.uint8(np.clip(255 * np.power(gray.astype(np.float32) / 255.0, .7), 0, 255))
    resized = cv2.resize(gray, (max(128, int(w * .66)), max(128, int(h * .66))))
    crop = gray[h // 4:3 * h // 4, w // 4:3 * w // 4].copy()
    angle = cv2.getRotationMatrix2D((w / 2, h / 2), 38, 1)
    rotated = cv2.warpAffine(gray, angle, (w, h), flags=cv2.INTER_LINEAR, borderValue=127)
    rotated = rotated[h // 7:6 * h // 7, w // 7:6 * w // 7].copy()
    success, jpeg = cv2.imencode('.jpg', gray, [cv2.IMWRITE_JPEG_QUALITY, 40])
    if not success:
        raise ValueError('Synthetic JPEG encoding failed')
    reencoded = cv2.imdecode(jpeg, cv2.IMREAD_GRAYSCALE)
    if reencoded is None:
        raise ValueError('Synthetic JPEG decoding failed')
    return dict(zip(EDIT_NAMES, (resized, gamma, crop, rotated, reencoded)))


def evaluate(manifest: Path | str, root: Path | str, *, algorithm: str = 'orb', split: str = 'holdout', pixel_audit: bool = False) -> dict:
    if algorithm not in ('orb', 'sift', 'akaze') or split not in ('holdout', 'development'):
        raise ValueError('Invalid algorithm/split')
    if type(pixel_audit) is not bool:
        raise ValueError('pixel_audit must be boolean')
    # Optional full-resolution RGBA preflight validates all declared captures
    # across development AND holdout, even if only one split is evaluated.
    if pixel_audit:
        from .decoded_alias_audit import audit_staged_sources
        audit = audit_staged_sources(manifest, root)
        if not audit['no_exact_pixel_collisions_observed']:
            raise ValueError('Ambiguous exact decoded-pixel aliases; curate the source manifest')
    root, scenes = _validate_manifest(Path(manifest), Path(root), split)
    # Validate every input and cryptographic provenance BEFORE evaluating any pair.
    imgs = []
    for scene, items in scenes:
        decoded = []
        for cid, meta in items:
            path = _safe_path(root, meta['file'])
            if _sha256(path) != meta['sha256']:
                raise ValueError('Media integrity mismatch; refuse evaluation')
            pixels = read_grayscale(path, max_side=960)
            if _sha256(path) != meta['sha256']:
                raise ValueError('Source changed while decoding; refuse evaluation')
            decoded.append((cid, pixels))
        imgs.append((scene, decoded))
    stats = defaultdict(lambda: {'candidate': 0, 'review': 0, 'unknown': 0, 'times': []})

    def record(condition, a, b):
        began = time.perf_counter()
        result = compare_arrays(a, b, algorithm=algorithm)
        duration = (time.perf_counter() - began) * 1000
        bucket = stats[condition]
        bucket[result.verdict] += 1
        bucket['times'].append(duration)
        return result.verdict

    per_scene_verdicts = []
    per_scene_edit_verdicts = []
    for _, members in imgs:
        scene_verdicts = []
        capture_edit_verdicts = []
        for _, base in members:
            edit_verdicts = {}
            for condition, edited in _derived_edits(base).items():
                edit_verdicts[condition] = record('edit_' + condition, base, edited)
            capture_edit_verdicts.append(edit_verdicts)
        for (_, first), (_, second) in combinations(members, 2):
            scene_verdicts.append(record('different_exposures_same_scene', first, second))
        per_scene_verdicts.append(scene_verdicts)
        per_scene_edit_verdicts.append(capture_edit_verdicts)
    for (_, first), (_, second) in combinations(imgs, 2):
        record('different_scenes', first[0][1], second[0][1])
    summary = {}
    for condition, row in sorted(stats.items()):
        n = len(row['times'])
        summary[condition] = {key: row[key] for key in ('candidate', 'review', 'unknown')}
        summary[condition]['pairs'] = n
        summary[condition]['candidate_rate'] = round(row['candidate'] / n, 4)
        summary[condition]['median_pair_ms'] = round(statistics.median(row['times']), 2)
    suspicious = summary.get('different_exposures_same_scene', {}).get('candidate', 0)
    scene_risk = summarize_scene_exposure_risk(per_scene_verdicts)
    edit_retention = summarize_edit_retention(per_scene_edit_verdicts)
    return {
        'policy': 'Local evidence only. NO automatic deduplication, merges or deletion.',
        'interpretation': 'Independent shutter claims are curator assertions, not machine-verifiable truths.',
        'scene_heldout_by_manifest': split == 'holdout',
        'all_splits_pixel_audit': 'passed_exact_only' if pixel_audit else 'not_run',
        'split': split, 'algorithm': algorithm,
        'scenes': len(imgs), 'captures': sum(len(items) for _, items in imgs),
        'same_scene_strong_candidates_needing_review': suspicious,
        'scene_exposure_risk': scene_risk,
        'edit_retention': edit_retention,
        'unsafe_to_certify_exposure_identity': True,
        'conditions': summary,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', required=True, type=Path)
    parser.add_argument('--root', required=True, type=Path)
    parser.add_argument('--algorithm', choices=('orb', 'sift', 'akaze'), default='orb')
    parser.add_argument('--split', choices=('development', 'holdout'), default='holdout')
    parser.add_argument('--pixel-audit', action='store_true', help='Optional full-decode exact-source audit across both splits; higher CPU/RAM/IO')
    args = parser.parse_args()
    print(json.dumps(evaluate(args.manifest, args.root, algorithm=args.algorithm, split=args.split, pixel_audit=args.pixel_audit), indent=2))


if __name__ == '__main__':
    main()
