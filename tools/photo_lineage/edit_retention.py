"""Scene-unit edit-retention audit for Photo DNA: no photos, inference or I/O.

Multiple edited variants of the same shutter exposure and several exposures in
one scene are correlated; don't infer recall confidence from pair count alone.
"""
from __future__ import annotations

from collections.abc import Iterable, Mapping

from .scene_risk import wilson_upper95

EDITS = ('resized', 'gamma', 'cropped', 'rotated_crop', 'jpeg_reencode')


def summarize_edit_retention(scenes: Iterable[Iterable[Mapping[str, str]]]) -> dict:
    """Every capture supplies one candidate/review/unknown verdict per edit.

    Unknown denotes a *missed review opportunity* here, not a confirmed
    unrelated scene or shutter exposure. Strict full edit coverage is required.
    """
    n_scenes = n_captures = capture_complete = scene_complete = 0
    by_edit = {name: {'candidate': 0, 'review': 0, 'unknown': 0,
                      'scenes_with_unknown': 0} for name in EDITS}
    for captures in scenes:
        local_count = 0
        scene_unknowns = set()
        for decisions in captures:
            if not isinstance(decisions, Mapping) or set(decisions) != set(EDITS):
                raise ValueError('Every capture requires exactly five declared edits')
            local_count += 1
            if local_count > 8:
                raise ValueError('Too many captures in one scene')
            capture_missed = False
            for edit in EDITS:
                outcome = decisions[edit]
                if outcome not in ('candidate', 'review', 'unknown'):
                    raise ValueError('Unrecognized edit verdict')
                by_edit[edit][outcome] += 1
                if outcome == 'unknown':
                    capture_missed = True
                    scene_unknowns.add(edit)
            capture_complete += not capture_missed
        if not 1 <= local_count <= 8:
            raise ValueError('Scene needs 1..8 complete captures')
        n_captures += local_count
        n_scenes += 1
        if n_scenes > 20:
            raise ValueError('Too many independent curated scenes')
        scene_complete += not scene_unknowns
        for edit in scene_unknowns:
            by_edit[edit]['scenes_with_unknown'] += 1
    if n_scenes == 0:
        raise ValueError('No scenes evaluated')
    any_failures = n_scenes - scene_complete
    return {
        'unit': 'scene_as_curated',
        'scenes': n_scenes,
        'captures_correlated': n_captures,
        'captures_all_five_edits_reviewable': capture_complete,
        'scenes_all_captures_all_edits_reviewable': scene_complete,
        'scenes_any_edit_unknown': any_failures,
        'scene_any_edit_unknown_wilson95_upper': round(wilson_upper95(any_failures, n_scenes), 4),
        'scene_complete_edit_recall_wilson95_lower': round(max(0, 1 - wilson_upper95(any_failures, n_scenes)), 4),
        'per_edit': {edit: {**row, 'scene_unknown_wilson95_upper':
                            round(wilson_upper95(row['scenes_with_unknown'], n_scenes), 4)}
                     for edit, row in by_edit.items()},
        'policy': 'Unknown is a missed *review opportunity*. Edits/captures in one scene are correlated. Wilson intervals assume representative independent scenes; NOT deployment guarantees.',
    }
