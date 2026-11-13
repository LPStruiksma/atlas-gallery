# V5 gallery-growth open-set robustness — synthetic-only paired audit

## Why this is a release-relevant scientific failure mode

A gallery of twenty named people and a gallery of hundreds of people
do not present the same false-suggestion risk. For each **Unknown** person,
adding candidate identities creates more opportunities for an
accidental high-scoring impostor. A fixed similarity cutoff that looks
conservative in a smaller gallery can create false manual review
offers after the candidate set grows.

This is not solved by citing a paper's Market1501 rank-1 result:
closed-set CMC/rank-1 is not unknown-person rejection, does not
establish provenance over years/clothing and does not account for
partially visible backs/heads under realistic family-photo lookalikes.

`audit_gallery_growth.py` is an intentionally **read-only, offline,
non-biometric** stress test for this specific issue.

## Exact paired experiment contract

Supply two complete scored-roster CSV snapshots (`baseline` and
`expanded`). Both use the existing
`benchmark_reid_scores.FIELDS` header and its existing strict
opaque-ID and cosine score schema:

```
probe_uid,split,truth_uid,session_uid,condition,candidate_uid,similarity
```

The scorer/curator must establish **before scoring** that:

1. Both snapshots contain exactly the same independently reviewed
   calibration/holdout probes, truth identities, session names and pose
   conditions, including genuine **Unknown** people.
2. Each snapshot contains every candidate's score for every probe,
   not a retrieved top-k shortlist. Within each split, every probe has
   exactly the same *complete* candidate set.
3. The expanded candidate set is a **strict superset** of the baseline
   within each split. Every previously recorded score string remains
   exactly unchanged. A re-ranked old score or re-extracted embedding
   is a different experiment and must be refused.
4. Both baseline and expanded manifests pass the existing no-leakage,
   bounded score, calibration minimum-diversity and unique-split checks
   from `benchmark_reid_scores._parse`.
5. Select the similarity floor using **baseline calibration only**
   through the existing conservative one-sided-Wilson budget, including
   known-person, Unknown-person and session-level checks. Keep this
   floor and an optional top-two margin **frozen** when scoring both
   baseline and expanded holdout (and calibration).
6. Report, separately for known-person truth and Unknown truth:
   new/resolved false review offers, new/lost correct offers,
   session-level counts of new false offers, and original versus
   expanded holdout diagnostics. Suppress all subject/candidate/probe
   identifiers and raw scores from output. A newly introduced
   high-scoring impostor cannot be quietly dismissed as a
   previously existing false offer.
7. Count the displayed session errors **descriptively**, never
   equate repeated photo/crop observations with physically independent
   subjects or connected event components. There is no inference
   of calibration validity from the holdout and **no** automatic Person
   confirmation or production approval.

### Fictional example

In synthetic regression, there are 60 known and 100 Unknown
calibration probes with 60 eligible known gallery identities, and
25 known + 25 Unknown holdout probes with 25 eligible known identities.
A baseline-only conservative calibration chooses a similarity
floor of `0.90`. We then add two new gallery identities to each
split *without modifying any old probe/score/label*.

One added holdout impostor scores `0.96` against **four Unknown**
rear-facing lookalikes and **two known** rear-facing probes that
previously had correct `0.90` matches. The expanded holdout now
has four newly unsafe Unknown review offers and two newly wrong-known
offers, despite **zero** changes in model weights, cutoff or original
candidate scores. All six would require explicit human review, not
automatic tagging. This is a **constructed counterexample** showing
the mechanism, not an empirical estimated probability.

## Run only on authorized, already-computed anonymous score CSVs

```sh
python tools/identity_fusion/audit_gallery_growth.py baseline_scores.csv expanded_scores.csv
python tools/identity_fusion/audit_gallery_growth.py baseline_scores.csv expanded_scores.csv --min-top-two-margin 0.05
python tools/identity_fusion/test_audit_gallery_growth.py
```

The executable checks two local CSV inputs (no photos or image paths)
and prints aggregate JSON only. Invalid CSV, changed scores,
incomplete/unequal rosters, cross-split identity leaks, newly added
trials, unreviewed Unknown, missing candidates or trivial baseline
calibration fail closed with generic path-free errors. Files are
never modified.

## Optional transitive source-dependence audit

The original three-file experiment counts newly unsafe Unknown/known
*observations* and distinct *sessions* separately; nevertheless multiple
different session IDs can still represent **one** original portrait,
recolor/crop family, event burst, or manually reviewed unknown subject.

A second model-free audit may be enabled using the existing
`verify_split_provenance.FIELDS` five-column **opaque-only** CSV:

```
probe_uid,split,photo_lineage_uid,event_cluster_uid,subject_cluster_uid
```

Supply exactly one row for every reviewed probe. The existing upstream
`verify_split_provenance` refuses duplicate/missing probes, crossed
calibration-vs-holdout lineages/events/subjects, inconsistent known-person
truth mappings, and one reviewed subject simultaneously marked
known/Unknown. Missing Unknown subject IDs may be `UNREVIEWED`; event
and lineage groups cannot be unreviewed. All IDs are private in-memory
tokens, never serialized into the report.

Within each split, we form one **transitive connected component** across
shared event groups, photo-lineage groups, reviewed subject groups and
repeated known-truth identities. A chain through distinct cues is ONE
potentially dependent source set, even if none of its images share the
same event ID directly. A mixed known+Unknown event can belong to both
truth-class denominators; it does not become two physically independent
samples. Output adds **counts only**:

- Distinct declared connected known and Unknown source components.
- Components containing at least one *new* unsafe Unknown review offer.
- Components containing at least one *new* wrong-known review offer.

When the sidecar is absent, the component diagnostic is **null**, not a
fake independence assertion. Both source and subject independence remain
**false**, regardless of whether a sidecar passes structural validation.
The existing calibration evaluator additionally enforces conservative
event-level error constraints when the source sidecar is provided. The
same sidecar covers the identical baseline and expanded trial roster; it
cannot selectively omit hard negatives.

```sh
python tools/identity_fusion/audit_gallery_growth.py \
  baseline_scores.csv expanded_scores.csv \
  --provenance-csv reviewed_equivalence_groups.csv --require-provenance
```

**Fictional counterexample:** Four newly unsafe heldout Unknown portrait
variants retain four separate nominal sessions. Their evidence is
transitively linked (portrait lineage A→B, event B→C, reviewed anonymous
subject C→D). They therefore represent **four** newly unsafe
observations, **four** sessions, but **one** connected source component.
This collapse is an audit over declared edges, **not** proof that the
remaining twenty-one photo sources are truly independent.

## Scientific and security limits

**A complete manifest is not proof that the actual gallery has been
fully indexed.** Existing individual-probe and session quotas do not
establish physically independent event/source lineage/subject groups.
Neither holdout partition authenticity, chronology of pre-registration
nor physical shutter separation can be inferred from opaque strings.
The tool deliberately reports corresponding provenance statuses
**false**, and offers a *descriptive paired stress test*, not a
deployment-ready open-set error-confidence certificate. For real data,
use consented independently reviewed Unknown people, independently
verified partition manifests with subject and capture-component
connections, a fixed prospective study protocol, identical legal
candidate enrollment and checkpoint/preprocessing rights,
and on-device S24+ p95/RAM/thermal before considering any pilot.
No face, silhouette, height, gait, clothing or event cue by itself
constitutes a verified identity. Human approval is mandatory.
