# Protected rear, partial and occluded cohort audit — V5

The original locked-threshold gallery-growth audits measure error risks
for the complete scored known/Unknown cohort. That is essential but not
sufficient: the aggregate can conceal a high-risk **rear-only,
partially visible, occluded or lookalike** subgroup. This additional
read-only, model-free research tool evaluates the exact simultaneously
attainable wrong-review-offer risk for each reviewed condition stratum
without refitting thresholds from the holdout.

## What is actually checked

`audit_joint_visibility.py` reuses the *full* existing model-free
`audit_joint_enrollment_peak` validation first: identical reviewed
probes/truth IDs/Unknown labels/sessions/conditions, strict full
scored-gallery rosters in calibration and holdout, no modified original
candidate score strings, baseline-only conservative Wilson-calibrated
similarity floor, optional fixed top-two margin, and exact full-gallery
subset extrema with independent endpoint and existential envelope
cross-checks. The input's existing `condition` column contains up to
three distinct tags from `front`, `back`, `side`, `partial`,
`occluded`, `appearance_shift`, `lookalike` and the single
*unreviewed visibility* label `unknown`.

Two separate breakdowns are produced for EACH of calibration and
holdout:

1. **Exact conditions / intersections:** only identical complete
   canonical labels, e.g. `back` and `back+lookalike` are different
   strata.
2. **Overlapping condition tags:** the marginal `back` category
   includes `back`, `back+occluded`, `back+lookalike` and any
   other multi-tag combination containing `back`. Marginal categories
   overlap: **do not sum them or treat them as independent subjects.**

Within each eligible stratum, enumerate all reachable candidate
subsets (max **10** added candidates per split and **200,000**
baseline full-score probe×subset states per split). Report class
denominators, exact joint peak of **new** Unknown false offers plus
wrong-known offers under one same gallery state, separately attainable
class maxima, endpoint unsafe observations and per-gallery-size peaks.
Optional, strictly reviewed and partition-safe photo lineage/event/
subject provenance gives descriptive connected-source-component
maxima; physical independence remains **unverified**. Stop if
extra stratum breakdown work exceeds 650,000 probe×subset states
per split.

**Protect against small-n false confidence:** Strata with fewer than
**10 independently reviewed observations for *any present* truth
class** are entirely suppressed (including their labels) and
counted as underpowered. The rule does **not** imply 10 is enough
for a statistically meaningful open-set error bound. It merely
prevents exposing misleadingly precise tiny-cohort breakdowns. The
overall audit remains available even if a stratum is suppressed.

### Fail-closed prospective coverage options

Use `--require-unknown-tag back`, `--require-unknown-tag partial`
and/or `--require-unknown-tag occluded` to insist that the source
actually includes at least ten reviewed **Unknown** probes with
that marginal condition in **both calibration and holdout**.
The same check is available for a canonical exact intersection,
such as `--require-unknown-intersection back+occluded`.
These are optional **admission/coverage constraints**, not a claim
of sufficient statistical power. A missing, unreviewed or suppressed
requested group causes a generic nonzero refusal, not an apparent
zero-error pass. Do not add requirements retrospectively after
seeing the heldout data; preregister them.

```sh
python tools/identity_fusion/audit_joint_visibility.py \
  baseline_scores.csv expanded_scores.csv \
  --min-top-two-margin 0.05 \
  --provenance-csv reviewed_lineage_event_subject.csv --require-provenance \
  --require-unknown-tag back \
  --require-unknown-tag occluded \
  --require-unknown-intersection back+occluded
```

Unless the evidence actually includes the required rear/occluded
Unknown trials in **both splits**, the above example intentionally
refuses to print a success report.

The output contains only categorical *condition names* and aggregate
counts, never opaque person/photo/candidate/session IDs, paths,
individual cosine scores, or inferred identity tags. Input
files are never modified; no photo or ML model is loaded.

## Synthetic counterexample, not measured S24+ accuracy

The fictional test retains the former 60 known and 100 Unknown
calibration probes / 25 known and 25 Unknown holdout probes. All
known holdout photographs have reviewed condition `back`, and
Unknown lookalikes have `back+lookalike`. One added gallery candidate
introduces **two wrong-known** and **four false Unknown** offers at
the same gallery subset. Exact `back` reports known=2/Unknown=0;
exact `back+lookalike` reports Unknown=4/known=0. The marginal
`back` category includes **all six** jointly unsafe offers.
This is expected overlap rather than double counting.

Re-label one Unknown probe to `back+occluded`, and its one-person
condition is **suppressed**, never reported as safe. Requiring the
occluded Unknown subgroup then fails because it is underpowered.
Likewise, one known photograph inside a large Unknown lookalike
intersection causes the whole intersection to be suppressed until
both truth-class denominators are adequate. Optional provenance
collapses four correlated Unknown capture variants to **one**
declared connected source component without claiming physical
independence.

## Research/permission boundaries

The new checks strengthen the completeness of a synthetic or future
consented study but do NOT prove model efficacy, distributional
transfer to family albums, consent, legal model/dataset permissions,
genuine source independence, calibrated identity probabilities, or
Android latency/RAM/thermal feasibility. A required cohort count of
ten is **not** a green release gate; statistical risk claims demand a
separately preregistered adequate independent Unknown subject/event
sample and multiple-comparison correction.

The tool does not access the Galaxy S24+, running Termux, originals,
private People databases, model weights or network resources. All
identity suggestions remain unconfirmed until the person explicitly
approves.

```sh
python tools/identity_fusion/test_audit_joint_visibility.py
python -m unittest discover -s tools/identity_fusion -p 'test_*.py'
```
