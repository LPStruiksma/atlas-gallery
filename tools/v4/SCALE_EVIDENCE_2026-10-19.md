# V4 People — 150,000-image synthetic query scale (19 October 2026)

**Scope:** independent, disposable Linux/SQLite experiment with the V4
`list_groups`, `group_list_existing_people_hints`, `get_faces` and
`list_people` query bodies extracted from
`ecdd339f37200654e9f7e12325a53373e8736315`, a reduced compatible
V4 SQLite schema, and 150,000 entirely **fictional** one-face images.
**Not a complete module import, GitHub-runner A/B, S24+ measurement, image
decode, real similarity/recognition benchmark, or authorization to update
private People data.** The repository's actual full-module benchmark is
`python3 tools/v4/benchmark_review_scale.py --faces 150000 --groups 2500
--people 500 --samples 3`. That exact full-module run was *not* executed
in this local environment.

## Fixture and environment

- 150,000 synthetic images/faces, 2,500 clusters, 500 named fictional
  People with four confirmed face rows each; no originals/models/files
  beyond a temporary synthetic SQLite DB
- Linux x86-64, Python 3.13.5, SQLite 3.46.1, NumPy 2.3.5,
  `OPENBLAS_NUM_THREADS=1`; three repeated warm queries; temporary
  DB roughly 107.2 MiB; setup separately roughly 1.5 seconds
- No V4 input images opened. SQLite foreign keys checked. Browser UI,
  memory allocated by SQLite's native engine, Android decoder caches,
  TalkBack and battery/thermal measurements **not included**

## Preliminary local isolated-extraction evidence

| Read-only operation | Earlier implementation median | Fast stable-page prototype median |
| --- | ---: | ---: |
| Stable Discover page 1 (40 visible) | 219.74 ms | 143.46 ms |
| Stable Discover page 2 (40 visible) | 206.04 ms | 126.52 ms |
| Dynamic Largest-first page | 212.41 ms | Not changed |
| Unsorted first page (60 visible) | 1.31 ms | Not changed |
| Metadata-only 500 People | 4.82 ms | Not changed |

These are independent local runs on the same Linux environment, not
counterbalanced paired measurements under controlled thread scheduling.
A further diagnostic run found the old stable-page full aggregate SQL
alone expensive; the proposed two-stage candidate-selection SQL
typically executed in roughly **5–11 ms** on this fixture. The
confirmed-reference / hint component still contributes significant
latency, especially at 500 named profiles; do not interpret 5–11 ms as
an end-to-end page time.

## Why this fast path preserves review semantics

Under **Stable review pass** alone, the original query aggregates
every unassigned face across all clusters, then sorts the historical
cluster size to choose 41 candidates. The optimized query first chooses
at most 41 eligible clusters by *historical count descending, then
immutable cluster ID ascending*, preserving the strictly-after cursor
and optional pending-only `EXISTS` semantics. Only then does it
count remaining faces and choose the best representative per selected
cluster. The unchanged `group_list_existing_people_hints` supplies
the same read-only, unconfirmed already-named Person suggestions.
Original **Largest first**, **Smallest first** and **Shuffle**
implementations are deliberately unchanged.

Dedicated synthetic parity regression:
`python3 tools/v4/test_stable_query_fastpath.py` compares the live V4
optimized implementation to a frozen independent copy of the old full
aggregation, across 2,001 fictional clusters, tied sizes, pending/all
filters, cursor navigation, per-face ignored/assigned mutations, and
representative choice. It rejects any changed card content or identity
write; latency is *diagnostic only*, never a CI pass/fail criterion.
The existing 2,001-group 51-page navigation, browser stale-response and
Undo suites remain mandatory.

## Next acceptance gates

1. Repeat the *actual full-module* 150,000-row
   `benchmark_review_scale.py` in a known host checkout, comparing
   pinned commits under one fixed CPU and SQLite version.
2. Reproduce synthetic frame-time/decode/touch latency on an owner-approved
   S24+, with independent private People backup/restore recovery first.
3. Never treat a synthetic speedup or a possible-name hint as permission
   for an unreviewed identity assignment or native APK upgrade.
