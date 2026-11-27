# R1-C: synthetic split confidence and correction-effort diagnostic

Unit #1081 / goal #95. Diagnostic only: the landed `SplitProposer` runs unchanged with its default tuning, crowd weight 1, invented 128-dimensional vectors and logical truth labels. The model label `synthetic-axis-128-v1` identifies this fixture, not a trained face model. No real photos, embeddings, demographics, identity policy or product UI are involved.

## Frozen grid

Seeds 310, 311 and 312 for each of the following seven profiles (21 runs), plus the two-face clean disjoint stress control (seed 0, no random draw; 22 runs total). Per-identity counts:

| Kind | Counts | Purpose |
| --- | --- | --- |
| Clean multi-pose | 72 | Single identity, three poses |
| Two identities | 48, 36 | Mixed profile |
| Three identities | 48, 36, 24 | More than two identities |
| Imbalanced | 96, 6 | Small minority |
| Lookalikes | 40, 40 | Identity axes cosine 0.92 |
| Outlier identities | 64, 1, 1, 1 | Three distinct singleton identities, potential Unsure |
| Hard veto | 36, 36 | Lookalikes, three explicit cannot-links and one cross-identity shared picture |
| Clean disjoint stress | 2 | Same logical identity, orthogonal vectors; deliberate evidence-starved case |

Identity axes are orthogonal except lookalikes; add 0.65 on one of three separate pose axes per identity, plus seeded uniform jitter in [-0.09, 0.09) on axes 64–127, then normalize. Observation IDs are deterministic, order shuffled by seed. Media keys are zero except the hard-veto shared pair; capture times unknown and crowd scores default to zero. This grid is intentionally artificial, not representative of a real gallery.

Defaults at implementation: edge 0.10, kNN 12, join 0.20, gain 0.12, Unsure margin 0.06, outlier 0.18, full-confidence margin 0.25, session weight 0.03 / 12h, name share 2/3, core 1500, references 48, top matches 7, exemplars 6, max faces 50000. The test prints the actual shipped values on each run; no threshold sweep or tuning.

## Scoring and reporting

Build the group-by-truth overlap matrix per profile. Exact dynamic programming over proposed group order and used truth-label bitmask maximizes total retained members with at most one group per truth and one truth per group. Zero-overlap edges are excluded; equal optima keep the first candidate in stable iteration order (unmatched first, then ascending truth labels). Unmatched groups contribute only wrong-person placements; unmatched truth identities and Unsure remain explicit. This differs from majority purity, which can count several groups for one truth as correct.

- Placed accuracy = matched correct / placed; wrong-person placements = placed minus matched correct (including unmatched groups).
- Placement coverage = placed / all faces; matched coverage = matched correct / all faces. Unsure = unassigned faces; no confidence bins for Unsure.
- k accuracy = profiles with proposed group count equal to truth identity count / profiles. Clean false split = clean profiles with k >= 2 / clean profiles.
- Confidence is a heuristic, not a probability. Fixed bins [0,0.2), [0.2,0.4), [0.4,0.6), [0.6,0.8), [0.8,1] report correct and incorrect **placed** counts and correct / total in each bin; zero denominator is N/A.
- Correction-effort proxy (face reassignments/unresolved, not taps or seconds): initial single mixed group = N minus largest truth-group size; proposed = N minus maximum matched members = wrong placements + Unsure. Count worsening profiles rather than censoring them. A clean profile can worsen even if the remaining placements are correct.

Each profile prints its own denominators and k; each kind and the pooled result sum counts **after** matching independently per profile, not by averaging fractions or aligning identities across profiles. Counts, not claimed reliability or improvements, are the evidence. Assertions cover exact partition reconciliation, confidence range, cannot-link/shared-picture safety, no invented name, and valid exemplar and Unsure references. No synthetic performance target is asserted.

Hand-computable regression: g0=A4+B3, g1=A3, g2=B1, Unsure=A1. Optimal matching g0→B, g1→A, g2 unmatched retains 6/11 placed; N=12, wrong=5, Unsure=1; baseline=4 and proposed=6 (worse). With confidence 0.8 on g0, 0.2 on g1 and 1 on g2, bin [0.2,0.4) has 3 correct / 0 wrong and [0.8,1] has 3 correct / 5 wrong. The all-Unsure hand case has N=2, baseline=0, proposed=2, placed accuracy and all bins N/A. Additional checks cover ties, unmatched truth and exact bin boundaries.

## Reproduction / evidence status

```sh
gradle :app:testDebugUnitTest --tests 'nl.atlas.gallery.featurepeople.split.SplitCalibrationDiagnosticTest' --rerun-tasks --info --no-daemon
```

Look for `SPLIT_CALIBRATION` lines (also in the class's Gradle XML test result under `app/build/test-results/testDebugUnitTest/`). Existing hosted build runs the full JVM suite; do not alter CI to publish this diagnostic. **Observed aggregate values: NOT MEASURED at authoring**; use actual exact-head hosted output if available, otherwise retain this status. A green build establishes assertions, not measured aggregate values unless the output is inspectable. Full emulator gate remains separate before merge.

Relation to [SPLIT_BENCH.md](SPLIT_BENCH.md): that prior silver-label benchmark measures purity, k and coverage on reference identities, not confidence reliability or correction-effort proxy. Its prior results are unchanged. Neither diagnostic establishes real-world accuracy, calibration, actual correction time, owner top-20 aggregate (#51), or end-to-end acceptance (#234). No automatic acceptance or production behavior changes.
