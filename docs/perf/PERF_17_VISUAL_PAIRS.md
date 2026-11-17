# PERF-17 — bounded Photo DNA visual-pair search

Issue: https://github.com/LPStruiksma/atlas-gallery/issues/224

This file starts the draft Codex task. No benchmark or production optimization has been performed as part of this scaffold.

## Scope and invariants

Optimize `findCurationVisualPairs` in `app/src/main/java/nl/atlas/gallery/featurecuration/CurationVisualHash.kt` without changing `curationDHash`, keeper decisions, deletion actions, grants, or People persistence.

Candidate enumeration must preserve all pairs at Hamming distance `<= maxDistance`, and the existing cap, `skip`, `checkpoint` cancellation, deterministic closest-first/index tie-break order, `comparisons`, and `truncated` semantics. A candidate is charged a comparison before its exact Hamming calculation; only the first shared band retains a pair. Measure and explicitly characterize any candidate-order differences, especially under a truncated comparison budget.

## Reproduction and evidence to complete

Add synthetic adversarial equivalence tests against brute force for small fixtures and compare behavior against the unchanged incumbent across 250,000 hashes on random, near-duplicate clusters, all-equal collision flood and zero-match distributions. Include non-default distance/budget/cap, a skip predicate and cancellation checks.

On one machine and runtime, record BEFORE and AFTER sample counts, p50/p95 elapsed CPU time in explicit units, and peak allocated heap or a clearly identified allocation proxy, including measurement method, warmup, GC considerations and limitations. If the run does not establish an improvement, say so. Do not extrapolate JVM evidence to Galaxy S24+ performance.

## Dispatch

Codex Cloud (Legacy) will return one parent-bound format patch on this PR. The ChatGPT Dev driver validates and applies it to the same branch; hosted exact-head CI and independent Review are required before any merge.
