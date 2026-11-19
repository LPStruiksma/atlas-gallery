# PERF-27 — focal identity validation at scale

Issue: https://github.com/LPStruiksma/atlas-gallery/issues/311

## Contract

`currentFocalIndexOrNull` validates the captured index against the current catalogue size and rejects blank keys before accessing a media key. A candidate with a valid index and nonblank captured key makes **exactly one** indexed callback, and succeeds only if the observed key equals the captured key. A stale or reordered focus cancels; no catalogue search or fallback is permitted.

## Deterministic JVM regression

`GridFocalIdentityScaleTest` checks negative, zero, last, and beyond-end indexes at catalogue sizes 0, 1 and 250,000; blank and whitespace keys; zero callback calls for invalid candidates; one call for admissible candidates; null, mismatched and reordered observed keys; and successful parity at the largest boundary. The scale is represented by `currentSize`, without allocating a 250k-item list. The callback asserts that only the captured index is requested.

## Outcome and limits

The existing production function already implements the required constant-number-of-lookups contract, so this unit adds regression coverage only. No production behavior change. No JVM allocation or latency measurements were performed; no device performance gain is claimed. Run the repository's exact-head CI and independent Review before merging. Revert the test and this document to undo the unit.
