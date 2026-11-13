#!/usr/bin/env python3
"""Offline renderer/heap summary from synthetic or owner-provided gfxinfo files.

No adb, app access, private media, logging of raw inputs or network activity.
FrameCompleted - IntendedVsync is end-to-end timing, NOT GPU-only work.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
from pathlib import Path
import re

LIMIT = 6 * 1024 * 1024
MAX_NS = 10_000_000_000
MIN_FRAMES_PER_TRIAL = 60  # Reject tiny or stale dumpsys windows as benchmark evidence
MEM_PSS = re.compile(r'^\s*TOTAL PSS:\s*([0-9][0-9,]*)\b', re.M)
MEM_TOTAL = re.compile(r'^\s*TOTAL\s+([0-9][0-9,]*)\s+', re.M)


def _text(path: Path) -> str:
    if path.is_symlink() or not path.is_file() or path.stat().st_size > LIMIT:
        raise ValueError('Missing or oversized evidence file')
    return path.read_text(encoding='utf-8', errors='replace')


def _frame_records(content: str) -> list[tuple[int, int, float]]:
    # Android dumpsys gfxinfo <package> framestats; ignore prose and old summaries.
    rows = csv.reader(content.splitlines())
    columns = None
    numbers = []
    seen_frame_ids = set()
    seen_headers = 0
    previous_start = None
    for cells in rows:
        cells = [c.strip() for c in cells]
        if len(cells) == 1 and cells[0].startswith('---PROFILEDATA---'):
            columns = None
            continue
        if {'Flags', 'IntendedVsync', 'FrameCompleted'}.issubset(cells):
            seen_headers += 1
            if seen_headers != 1:
                raise ValueError('Concatenated or multi-window framestats cannot be one trial')
            columns = {name: i for i, name in enumerate(cells)}
            continue
        if columns is None or len(cells) <= max(columns.values()):
            continue
        try:
            if int(cells[columns['Flags']]) != 0:
                continue
            start = int(cells[columns['IntendedVsync']])
            end = int(cells[columns['FrameCompleted']])
        except (IndexError, ValueError):
            continue
        if start > 0 and 0 < end-start <= MAX_NS:
            frame_id = (start, end)
            if frame_id in seen_frame_ids:
                raise ValueError('Duplicate Android frame record inside one trial')
            if previous_start is not None and start < previous_start:
                raise ValueError('Out-of-order Android frame records inside one trial')
            seen_frame_ids.add(frame_id)
            previous_start = start
            numbers.append((start, end, (end-start)/1_000_000))
    if not numbers:
        raise ValueError('No valid completed Android frame records')
    return numbers


def durations_ms(content: str) -> list[float]:
    """Public compatibility wrapper: capture durations without frame IDs."""
    return [duration for _start, _end, duration in _frame_records(content)]


def pctl(data: list[float], fraction: float) -> float:
    ordered = sorted(data)
    return round(ordered[max(0, math.ceil(fraction*len(ordered))-1)], 3)


def pss_kib(content: str) -> int:
    # TOTAL PSS available in modern dumpsys meminfo; legacy table TOTAL row.
    found = MEM_PSS.findall(content) or MEM_TOTAL.findall(content)
    if len(found) != 1:
        raise ValueError('No unambiguous TOTAL PSS value')
    size = int(found[0].replace(',', ''))
    if size <= 0 or size > 100_000_000:
        raise ValueError('Invalid memory value')
    return size


def summarize(trials: list[tuple[str, Path, Path]], *, refresh_hz: float,
              catalogue_size: int) -> dict:
    if (isinstance(refresh_hz, bool) or not isinstance(refresh_hz, (int, float))
            or not math.isfinite(refresh_hz) or not 30 <= refresh_hz <= 240
            or type(catalogue_size) is not int or not 1 <= catalogue_size <= 1_000_000):
        raise ValueError('Invalid refresh rate or synthetic catalogue size')
    budget_ms = 1000/refresh_hz
    observed_frame_files: set[Path] = set()
    observed_memory_files: set[Path] = set()
    observed_frame_digests: set[str] = set()
    observed_frame_ids: set[tuple[int, int]] = set()
    result = {'data_source': 'offline Android framestats + meminfo; no real-device claim',
              'refresh_hz': refresh_hz, 'frame_budget_ms': round(budget_ms, 3),
              'catalogue_size': catalogue_size,
              'minimum_frames_per_trial': MIN_FRAMES_PER_TRIAL, 'phases': {}}
    for phase in ('cold', 'warm'):
        subset = [(f,m) for p,f,m in trials if p == phase]
        if not subset:
            raise ValueError('Both cold and warm captures required')
        timing = []
        mem = []
        per_trial = []
        for frames_file, memory_file in subset:
            frame_resolved, memory_resolved = frames_file.resolve(), memory_file.resolve()
            if frame_resolved in observed_frame_files or memory_resolved in observed_memory_files:
                raise ValueError('A frame or memory capture was reused as another trial')
            observed_frame_files.add(frame_resolved)
            observed_memory_files.add(memory_resolved)
            frame_text = _text(frames_file)
            # Distinct filenames alone do not prove distinct captures.
            digest = hashlib.sha256(frame_text.encode('utf-8')).hexdigest()
            if digest in observed_frame_digests:
                raise ValueError('Identical frame capture duplicated across trials')
            observed_frame_digests.add(digest)
            # Different *files* can contain the same Android frames with an
            # added/removed header, tail, or partial overlap. Such captures
            # are NOT independent trials even if their SHA-256 differs.
            frame_records = _frame_records(frame_text)
            frame_ids = {(start, end) for start, end, _duration in frame_records}
            if not observed_frame_ids.isdisjoint(frame_ids):
                raise ValueError('Overlapping Android frames reused across trial files')
            observed_frame_ids.update(frame_ids)
            samples = [duration for _start, _end, duration in frame_records]
            if len(samples) < MIN_FRAMES_PER_TRIAL:
                raise ValueError('Insufficient completed frames in trial')
            pss = pss_kib(_text(memory_file))
            timing.extend(samples)
            mem.append(pss)
            per_trial.append({'frames': len(samples), 'p95_ms': pctl(samples, .95),
                              'over_budget_pct': round(100*sum(t > budget_ms for t in samples)/len(samples), 2),
                              'total_pss_kib': pss})
        result['phases'][phase] = {
            'trials': len(subset), 'frames': len(timing),
            'p50_ms': pctl(timing,.50), 'p95_ms': pctl(timing,.95),
            'p99_ms': pctl(timing,.99),
            'over_budget_pct': round(100*sum(t > budget_ms for t in timing)/len(timing),2),
            'max_observed_pss_kib': max(mem), 'per_trial': per_trial}
    result['repeatable_evidence'] = all(result['phases'][p]['trials'] >= 3 for p in ('cold','warm'))
    result['caveats'] = ('Over-budget is NOT Android JankStats jank classification; '
                         'PSS snapshots are NOT peak heap; GPU frame timeline/Perfetto and on-device '
                         'warm/cold A/B remain required for release.')
    return result


def main() -> int:
    ap = argparse.ArgumentParser(description='Summarize offline synthetic viewer frame/memory evidence')
    ap.add_argument('--trial', nargs=3, action='append', metavar=('PHASE','FRAMESTATS','MEMINFO'), required=True)
    ap.add_argument('--refresh-hz', type=float, required=True)
    ap.add_argument('--catalogue-size', type=int, required=True)
    args = ap.parse_args()
    try:
        trials = []
        for phase, frame, mem in args.trial:
            if phase not in ('cold','warm'):
                raise ValueError('Only cold/warm phases accepted')
            trials.append((phase, Path(frame), Path(mem)))
        report = summarize(trials, refresh_hz=args.refresh_hz, catalogue_size=args.catalogue_size)
    except (OSError, ValueError):
        ap.exit(2, 'NO EVIDENCE: invalid/missing offline timings or memory snapshots.\n')
    print(json.dumps(report, indent=2))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
