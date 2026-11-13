# Fictional same-scene time-step / end-to-end SQLite-to-geometry audit

Lane B, issue #83. This is a **research-only hard-negative audit**, not
an Android feature, image dataset, or trained identity model. Its purpose
is to distinguish (a) retrieval of an actual edited original and (b)
a *different time-step frame of the same static scene* that can look
nearly identical in both ORB words and geometric correspondences.

The already merged `same_scene_adversary.simulated_frame` generates each
fictional grayscale time step from one shared procedural background plus
independently re-sampled noise/lighting and optionally changed/stationary/
absent foreground. Each emitted frame is pixel-nonidentical to the
other time steps. **These are not genuinely independent camera shutters.**
The same-scene distinction is labeled by construction only, not by EXIF,
physical camera, rights or curator metadata.

## One bounded, disposable actual retrieval funnel

```bash
python -m tools.photo_lineage.synthetic_exposure_funnel \
  --scenes 2 --frames-per-scene 2 --size 256 \
  --foreground stationary --cap 128 --distractors 4 --algorithm orb
```

Use 1–3 distinct scene seeds, 2–3 synthetic time steps per scene,
256–360px, at most 12 fictional distractors, one approved cap from
0/64/128/192/256, and one existing ORB/SIFT/AKAZE verifier.
Create **only** a fresh `TemporaryDirectory`; write synthetic originals,
distractors, edited query PNGs and SQLite there, never in source folders
or on the user's device. Index exact real `retrieval.index_folder`
baseline source fingerprints, verify them against direct ORB extraction,
then **transactionally replace only temporary source postings** with
`capped_blur_word_tradeoff.select_extra_words` at the requested cap.
All baseline terms remain. The temporary index intentionally retains
the normal fingerprint marker solely to test the unmodified
`retrieval.shortlist`; **never publish or migrate** this research cache.

Compare each source to two deterministic edits: severe Gaussian sigma8
and 38° rotated-and-cropped. For every query run the real SQLite
shortlist with explicit 50,000-posting / 1,000-document work bounds,
top10, including file-freshness gates. A budget failure raises
`PostingBudgetExceeded`: **it never means no relatives**. Count
whether the original returns, whether direct geometry would review it
regardless of retrieval, and how many *other time-step frames from the
same scene* appear and independently pass the selected geometric
verifier as `candidate`, `review`, or `unknown`. Also report
**number of distinct synthetic scene seeds** with any such offers,
rather than treating every correlated query as an independent trial.
Only aggregate counts, fixed conditions, cost metrics and explicit
source-identity uncertainty are output. No paths, IDs, pixel bytes,
descriptor words or original media leave the disposable context.

## Synthetic CI and interpretation

CI uses **actual temporary SQLite** and actual OpenCV for a small
two-frame procedural case; further tests force a hypothetical strong
geometric candidate between *different* fictional time steps, verify
no automatic equivalence is inferred, exercise invalid budget/output/
verdict refusal, and assert posting-budget failure propagates. The
test records no real photos and makes no network calls or model imports.

This is a **pipeline safety investigation**, not a calibrated scientific
false-match rate: the same procedural backdrop is reused, edited queries
are correlated, and separate time steps are simulated rather than
authenticated camera captures. Even if a different step passes both
retrieval and geometry, it must remain a **manual human-review offer**,
not evidence of a common exposure, duplicate identity or permission to
delete. No production matcher, ORB word budget, SQLite schema/fingerprint,
native Android UI, V4/V5-owned code or original photographs change.
Before any rollout: multiple exact-source-revision/hash-pinned,
rights-cleared *physical separately captured* same-camera negatives,
source/scene-heldout analysis, real 150k-index posting budgets and
owner-approved synthetic S24+ p95/RAM/thermal tests.
