# Matched model-free evidence ablations (Lane C)

`evaluate_review_quality.py` continues to use the same in-memory,
independently reviewed synthetic/consented cases and the exact same
candidate/Unknown gate for all four existing evidence variants. An
**additive diagnostic** now tracks *paired changes on the same review
cases* instead of relying only on separately printed accuracy/coverage
rates.

## What the new `matched_ablation` means

For four fixed ordered comparisons (face-only → face+session,
face+session → face+body+session, face-only → face+body+session,
face+body+session → all cues), count:

- newly correct or lost correct *known-person* review offers;
- newly false or resolved false review offers to **Unknown** people;
- newly wrong or resolved wrong offers for independently known people.

A person whose false candidate changes to a different false candidate
does **not** count as a newly false trial. All offers remain suggestions
for a human, and every variant retains Unknown abstention. These are
descriptive **associations**, not proof that a particular cue caused the
change: candidate ranking is re-evaluated jointly with all cues. Two
correlated clothing/context/body observations from the same source are
still governed by the original evidence-store correlation groups.

The new aggregate `by_condition` requires **at least ten** reviewed
observations for each disclosed condition; smaller cells are suppressed.
Totals are over identical observations and must not be added across
overlapping person/session/condition strata.

## Explicit capture correlation (important for repeated crops)

For a future independently prepared study, supply an optional
`capture_component_uid` for **every** truth case, using reviewer-declared,
typed photo-lineage/event/session connected components. A missing
component on even one case refuses the study; a malformed component
refuses the study. If *none* is supplied, aggregate per-observation
transitions remain available but the `capture_component_diagnostics`
field is `null`: there is **no default assumption** that different
observation IDs mean independent captures.

When the complete component inventory is supplied, one component with
ten erroneous Unknown frames counts as **one** source component with any
new false Unknown offer. A component containing both known and Unknown
cases can appear in both category denominators; they are not two
independent physical observations. The output gives counts and one-sided
Wilson95 bounds for these correlated component risks, flagged strictly
as research diagnostics, never calibration/release approval.

Reviewer-declared grouping is still not independently authenticated
physical independence, and source components can remain correlated
across similar cameras, decades, poses and social contexts. No raw
observation, subject, component, model score, source path or person ID
is emitted in the report. **Neither this feature nor the parent
evaluator reads pictures, model weights or private People databases,
edits any identity, or alters Android.**

Synthetic regressions:

```sh
python tools/identity_fusion/test_evaluate_review_quality.py
python -m unittest discover -s tools/identity_fusion -p 'test_*.py' -v
```

The fictional fixture deliberately shows a gain of two correct known
review offers *and* a newly unsafe Unknown lookalike review offer on the
same seven observations. Another synthetic case produces eleven
additional erroneous Unknown offers from one declared photo lineage:
the expanded report records twelve new false observations but only two
affected Unknown source components. All of this is **failure-mode
instrumentation**, not evidence of any actual model's identity quality
or suitability for installation.
