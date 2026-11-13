#!/usr/bin/env python3
"""Synthetic-only validation of Android frame log parsing, percentiles and bounds."""
from pathlib import Path
import sys
import tempfile
sys.path.insert(0, str(Path(__file__).parent))
from summarize_viewer_perf import durations_ms, pss_kib, pctl, summarize, _text


def fake_frames(durations, offset=0):
    header = '---PROFILEDATA---\nFlags,IntendedVsync,Vsync,DrawStart,FrameCompleted\n'
    rows = []
    for i, ms in enumerate(durations):
        start = (i+1+offset)*200_000_000
        rows.append(f'0,{start},{start},{start},{start+int(ms*1_000_000)}')
    rows.append('1,200,200,200,80000000')  # rejected flags
    return header + '\n'.join(rows) + '\n---PROFILEDATA---\n'


def reject(fn):
    try: fn()
    except ValueError: return
    raise AssertionError('Invalid performance evidence was accepted')

assert durations_ms(fake_frames([5,10,20])) == [5,10,20]
assert pctl([5,10,20], .95) == 20
assert pss_kib('TOTAL PSS:  12,345\n') == 12345
assert pss_kib(' TOTAL  54321  0 0\n') == 54321
reject(lambda: durations_ms(fake_frames([5, 10]) + fake_frames([5, 10], offset=100)))
example = fake_frames([5, 10, 15]).splitlines()
# A header followed by a repeated byte-identical frame was previously counted twice.
repeated = "\n".join(example[:3] + [example[2]] + example[3:]) + "\n"
reject(lambda: durations_ms(repeated))
# Valid individually, but non-monotonic IntendedVsync within one capture.
out_of_order = "\n".join(example[:2] + [example[3], example[2]] + example[4:]) + "\n"
reject(lambda: durations_ms(out_of_order))
reject(lambda: durations_ms('old info only'))
reject(lambda: pss_kib('TOTAL PSS: 10\nTOTAL PSS: 20\n'))
reject(lambda: pss_kib('TOTAL PSS: -50\n'))
with tempfile.TemporaryDirectory() as tmp:
    folder = Path(tmp)
    trials = []
    for phase, pattern in [('cold', [5,15,25,30]), ('warm', [4,6,8,10])]:
        for i in range(3):
            a = folder / f'{phase}-{i}.frames'
            m = folder / f'{phase}-{i}.mem'
            a.write_text(fake_frames(pattern * 20, offset=(i + (0 if phase == 'cold' else 3))*1000))
            m.write_text(f'Applications Memory Usage (in Kilobytes):\nTOTAL PSS: {225000+i}\n')
            trials.append((phase,a,m))
    report = summarize(trials, refresh_hz=120, catalogue_size=150000)
    assert report['repeatable_evidence'] and report['phases']['cold']['frames'] == 240
    assert report['minimum_frames_per_trial'] == 60
    assert report['phases']['cold']['over_budget_pct'] == 75.0
    assert report['phases']['warm']['over_budget_pct'] == 25.0
    assert report['phases']['warm']['p95_ms'] == 10
    assert report['phases']['warm']['max_observed_pss_kib'] == 225002
    assert not summarize(trials[:1]+trials[3:4], refresh_hz=60, catalogue_size=30)['repeatable_evidence']
    reject(lambda: summarize(trials, refresh_hz=0, catalogue_size=30))
    reject(lambda: summarize(trials, refresh_hz=float("nan"), catalogue_size=30))
    reject(lambda: summarize(trials, refresh_hz=float("inf"), catalogue_size=30))
    reject(lambda: summarize(trials, refresh_hz=True, catalogue_size=30))
    reject(lambda: summarize(trials, refresh_hz=120, catalogue_size=True))
    reject(lambda: summarize(trials, refresh_hz=120, catalogue_size=0))
    reject(lambda: summarize([trials[3]], refresh_hz=120, catalogue_size=10))
    reject(lambda: summarize([trials[0], trials[0], *trials[3:]], refresh_hz=120, catalogue_size=10))
    # Byte-different files can still replay identical frames. Prefixing
    # arbitrary prose changes whole-file SHA but cannot create a new trial.
    overlapping = folder/'overlapping-but-different.frames'
    overlapping.write_text('Synthetic harmless wrapper line\n' + trials[0][1].read_text())
    assert overlapping.read_bytes() != trials[0][1].read_bytes()
    reject(lambda: summarize([trials[0], ('cold', overlapping, trials[1][2]),
                              *trials[3:]], refresh_hz=120, catalogue_size=10))
    # Two captures may share only a 70-frame prefix (not byte-identical).
    # The repeated records still invalidate independent-trial claims.
    partial = folder/'partial-overlap.frames'
    partial.write_text(fake_frames([5,15,25,30]*17 + [5,15], offset=0))
    reject(lambda: summarize([trials[0], ('cold', partial, trials[1][2]),
                              *trials[3:]], refresh_hz=120, catalogue_size=10))
    # Different files containing the same log are still the same evidence.
    duplicate = folder/'forged-duplicate.frames'
    duplicate.write_text(trials[0][1].read_text())
    reject(lambda: summarize([trials[0], ('cold', duplicate, trials[1][2]), *trials[3:]],
                             refresh_hz=120, catalogue_size=10))
    insufficient = folder/'one-frame.frames'
    insufficient.write_text(fake_frames([5]))
    reject(lambda: summarize([('cold', insufficient, trials[0][2]), *trials[3:]],
                             refresh_hz=120, catalogue_size=10))
    trials[0][1].write_text('broken')
    reject(lambda: summarize(trials, refresh_hz=120, catalogue_size=10))
    capture_link = folder/'linked-frames.txt'
    capture_link.symlink_to(trials[1][1])
    reject(lambda: _text(capture_link))
print('PASS: unique >=60-frame cold/warm trials, duplicate/sparse/in-file-frame rejection, budget and PSS checks')
