# Exact-prefix gallery enrollment stress study — V5 research

## The misleading final-snapshot comparison

A gallery-growth comparison performed at only the starting and ending
library sizes can miss a period of unsafe review offers. New people
may initially become the highest-scoring mistaken match, then a later
identity with the **same high score** forces a tie and proper abstention.
The final gallery has **zero** newly unsafe offers, but the user may have
already encountered unsafe review suggestions while the gallery was
smaller. Similarly, a top-two ambiguity margin may temporarily remove
or reintroduce an offer.

A final-state Unknown false-offer count must **not** be interpreted as
the maximum historical risk during incremental enrollment.

This is especially relevant to ATLAS's local-first People gallery,
which may be built up one labeled person at a time. Any proposed V5
rear-facing / partial-person model must remain review-only and preserve
explicit Unknown abstention even as its candidate roster changes.

## What the new audit actually measures

`audit_progressive_gallery.py` extends the existing checked
`audit_gallery_growth.py` full-matrix paired score study:

- Baseline and final scored CSVs retain the strict seven-column opaque
  score schema, exactly the same reviewed probes, known/Unknown truths,
  sessions, pose/visibility condition tags, and unchanged baseline
  score **strings** for every original candidate. Both snapshots must
  contain **complete** candidate scores per probe, not top-k retrieval.
- A separately supplied exact **enrollment rank manifest** has three
  columns: `split,candidate_uid,enrollment_rank`. Each split
  contains each *added* gallery identity exactly once, with dense
  canonical ranks `1..N`. Both calibration and holdout must have the
  same number of added identities for a comparable step count; their
  identities remain disjoint and their ordering is independently given.
  Sorting by holdout score or post-hoc individual false links is NOT an
  acceptable prospective enrollment order.
- To bound time and report size, accept **1–64 additions per split**
  in one study. Larger enrollment waves require separate prospectively
  identified, independently calibrated/counted analyses; do not
  combine their resulting false-offer frequencies as if independent.
- Lock the similarity floor using the **original baseline calibration
  only** and never reselect it for another gallery size, observation or
  holdout. An optional top-two margin is likewise fixed.
- Replay every rank, retaining the exact baseline highest score,
  runner-up, tie and alphabetical score-tie abstention semantics of
  `benchmark_reid_scores._parse`. The incremental procedure ends
  with a **full independent paired re-evaluation** of the final
  gallery, and refuses a mismatch between replay and full snapshot.
- At each rank report known/Unknown review-offer counts, new/lost
  correct offers, new/resolved wrong-known and new/resolved Unknown
  false offers, separate nominal session counts, and optional
  transitive connected-source component diagnostics inherited from
  the existing audited provenance sidecar.
- Track first unsafe exposure at each addition, unique observations
  ever newly exposed, **transient exposure** (new false offer at an
  intermediate rank but not the final roster) and maximum
  simultaneous new unsafe offers. These are all *descriptive*.
- Never expose any candidate/subject/session/source/camera/photo
  identifier or individual score. Neither this tool nor a report
  can rename, attach, merge or auto-confirm anybody in ATLAS.

Example invocation from repo root using *authorized, already-scored,
opaque-only local inputs*:

```sh
python tools/identity_fusion/audit_progressive_gallery.py \
    initial_scores.csv final_scores.csv enrollment_order.csv \
    --provenance-csv reviewed_equivalence_groups.csv --require-provenance
```

Three mandatory files; optional `--min-top-two-margin 0.05` and
optional but recommended five-column strict reviewed provenance CSV.
Input files are read-only, outputs are JSON aggregates, error
diagnostics do not echo filenames/identifiers. This does **not**
obtain, download or run models, or inspect photographs.

## Key synthetic counterexample

The synthetic input has 60 known and 100 Unknown calibration probes,
25 known plus 25 Unknown holdout probes, baseline score 0.90 for a
correct known identity, and two new gallery candidates in each split.
The first new holdout candidate scores **0.96** against four
independently reviewed fictional Unknown backs/lookalikes and two
fictional known backs. The original model, score pool and calibrated
floor are untouched.

If the second candidate has 0.10 scores, the unsafe offers persist.
If that second candidate *also scores 0.96* against those same probes,
it creates an **exact top-score tie**, which forces abstention.
The endpoint **looks safe (zero new false offers)** but four Unknown
and two known observations were newly unsafe at the intermediate
rank. Reporting only the endpoint is therefore scientifically
misleading. Reversing the candidate enrollment order also changes
the stage of first risk even though the final score matrix is identical.

One admission-order experiment **does not** establish a causal safety
rate for real photographs or the true peak across all possible
permutations. The rank sidecar is a caller's declaration; this tool
cannot independently prove that it was prospectively frozen before
examining holdout outcomes or that the full candidate roster mirrors
the actual ATLAS People database.

## Validation and explicit blocked gates

Run the synthetic-only regressions:

```sh
python tools/identity_fusion/test_audit_progressive_gallery.py
python -m unittest discover -s tools/identity_fusion -p 'test_*.py'
```

Unresolved real-world needs: exact weights with authenticated
checkpoint-specific redistribution/data rights, consented and genuinely
independent event/lineage/Unknown subject truth cohorts across years and
front/back/partial views, independently captured and preregistered
candidate enrollment chronology, fixed decoding/resizing/color
semantics, and real S24+ p95/peak RSS/thermal measurement. Declared
event or portrait-lineage groups are not proof of physical
independence. There is no calibration of an identity probability.
All evaluation/pilot/production and automatic-person-confirmation
authorizations remain **false**.
