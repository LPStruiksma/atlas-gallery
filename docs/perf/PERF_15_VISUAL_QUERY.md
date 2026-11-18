# PERF-15: Find Similar vector scan (#222, inventory A11)

**Change.** `VisualQueryEngine.dot` keeps the exact left-to-right single-accumulator float sum but is unrolled by four after one up-front bound on the second array, so HotSpot drops the per-element range checks. The result is bit-identical (same operations, same order), so scores, bands, near-duplicate exclusion, tie order, video moment collapse, cancellation and grant/epoch checks are unchanged. No ANN, quantisation or threshold change.

**Equivalence** (`VisualQueryThroughputTest`):
- `dot` raw float bits equal the plain loop for lengths 1–9, 31, 767–769 and magnitudes from 1e-38 to 3e18, plus a cancellation-heavy sum.
- 6,000 synthetic items (photos, videos with 1–6 moments, identical vectors for tie order, values exactly on the floor and strong edges, near duplicates) give the same hits (including secondary moments), near-duplicate count, scanned count and truncation as a naive reference pipeline at limits 1, 25, 500 and 5,000.
- Model mismatch, revoked grant and stale snapshot still fail closed.

**Numbers.** 250k × 768 synthetic unit vectors (4,096-vector pool, 512-row pages), container JVM (HotSpot), same machine and same test for both arms, n = 9 after warm-up:

| | p50 | p95 |
|---|---|---|
| dot kernel over 250k rows, before | 170–172 ms | 171–186 ms |
| dot kernel over 250k rows, after | 107 ms | 121 ms |
| whole `search`, before (main engine) | 245 ms | 278 ms |
| whole `search`, after | 186 ms | 207 ms |

Allocation per search is unchanged (about 205–210 MB, almost all from the synthetic source's page/row/key objects; the engine allocates nothing per row below the floor). The real store adds SQLite reads and int8 decoding that this benchmark leaves out.

**Limits.** JVM only; ART's JIT/AOT may gain more or less from the hoisted bound. No S24+ gain is claimed. CPU feasibility: at roughly 0.4 µs per 768-d row on this JVM, a 250k library stays a sub-second worker-thread scan; the next lever would be the store's page decoding, not the kernel.
