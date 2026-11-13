# Photo DNA — bounded immutable posting-query research

Lane B / issue #83. **Research-only, no native Android integration.** Reads numeric immutable uint64 descriptors, not photographs, EXIF, gallery paths, people or private phone content. A match is a retrieval shortlist entry, **never proof of identical camera exposure** or deletion authority.

## The 150,000-image bottleneck

The early `benchmark_packed_index.packed_query` scores *all* postings for query terms with an unbounded Python `Counter`. A repeated texture or screenshot may generate words shared by much of a 150k-photo library. Several common query words may force millions of visits and large memory spikes. Truncating and returning an empty shortlist risks disguising missing relatives as confirmed negatives.

The new `bounded_posting_query.open_pinned_queries(path, trusted_sha256)` holds a single read-only descriptor, streams integrity checks for sorted/unique/in-range uint64 postings, compares SHA-256 to an **independently supplied trusted pin**, and memory-maps the same file. Verification is once per open session, **not** once per query. The caller must obtain a trusted pin independently from a validated receipt/selection ticket; a hash merely calculated from the target file proves no provenance. Anti-rollback, ancestor-directory hostility and malicious same-stat in-place file mutation remain out of scope.

`index.query(words, max_posting_visits=250000, max_distinct_documents=100000, top_k=20)` first binary-searches exact posting ranges for every query visual word. If summed visits exceed the budget, it raises `PostingBudgetExceeded` **before** scoring. If unique candidate IDs exhaust the second budget, it also raises. Callers must treat this as **Unknown / incomplete search**, not an empty shortlist. There is no silent common-word skipping, partial result or approximate false confidence. Invalid/bool/nonfinite terms, repeated-term generators and unsupported byte streams fail closed.

Responses return anonymous numeric photo IDs and exact shared-word counts, not paths or photos. Feature extraction, file-stat freshness, photo ID mappings, geometric verification and Android UI remain separate stages. A snapshot can be verified while still being semantically stale relative to updated source media.

## Synthetic reproducibility

```bash
python -m unittest tools.photo_lineage.tests.test_bounded_posting_query -v
python -m tools.photo_lineage.benchmark_bounded_query --photos 150000 --queries 20
```

This deliberately uses **150,000 numeric photo IDs but only THREE synthetic words per ID** (one hot word and two anchors), generating no actual images. It builds an immutable disk snapshot, validates its independent SHA and ordering, shows that the hot word explicitly abstains at a 10,000-posting budget, and checks deterministic top-1 on 20 synthetic queries. Outputs include numeric index MiB, verification/build duration, query p50/p95 and Linux process peak RSS.

**Crucial limitation:** three synthetic terms are ~128× fewer than the ~384 words/photo of the earlier 96×4 ORB experiment, and deliberately controlled numeric frequencies are not real visual-word distributions. Do NOT extrapolate these timings, memory or recall to 150k actual photographs or a Galaxy S24+. A hypothetical 150k × 384 index is ~57.6 million postings / ~439 MiB of packed u64 data alone, excluding manifests, old generations, delta overlays, transient disk runs and decode/ORB work. The external-sort conservative scratch preflight reserves up to ~3× posting data plus run overhead and 64 MiB safety headroom, implying potentially over 1.3 GiB additional disk needs. These are arithmetic examples, not measured free-space or Android resource guarantees.

This is not integrated into SQLite, the native app, a running Termux server or actual phone photos. Existing snapshots remain untouched; any new numeric research snapshots are confined to disposable temporary directories.

## Acceptance and gaps

Merge only after entire exact-head dedicated synthetic unittest/`ci_gate.py` success, clean GitHub mergeability, and Lane B-only diff. Tests include 150k-ID adversarial hot words, exact score/tie parity, one-word abstention, duplicate terms, generator bounds, symlink/corrupt/unpinned bytes, altered open snapshots, candidate-cardinality limits and a small benchmark smoke. For on-device acceptance, owner-approved synthetic-only S24+ p50/p95, memory, power/heat and full ~384-word workload are still required; no private gallery scans or automatic photo-family grouping are authorized.

## Preflight disk-space envelope without starting work

Use the independent **no-execution** planner when considering a full 150k × 384 numeric posting snapshot:

```bash
python -m tools.photo_lineage.capacity_plan --photos 150000 --max-words-per-photo 384
python -m tools.photo_lineage.capacity_plan --photos 150000 --max-words-per-photo 384 --available-free-bytes 1073741824
```

This prints conservative arithmetic only; it reads **no** photographs, app data, current filesystem space or Android state and allocates no index storage. Unless same-destination free bytes are provided explicitly, status is `unknown_free_space_do_not_start`. If supplied free bytes fall below the conservative builder preflight plus the protected reserve, status is `no_go_insufficient_reported_space`. Even when the requirement is met, status says `advisory_headroom_only_not_deployment_go`, not authorized or device-tested.

The calculation reuses the **actual builder's** `_maximum_scratch_reservation` (3× maximum packed posting bytes, filesystem run overhead and 1 MiB fixed slack) plus its default protected 64 MiB free-space reserve. For 150k photos × 384 projected descriptors, the bare u64 stream is **460,800,000 bytes (~439.45 MiB)**. Conservative scratch/free requirement is **more than 1.4 billion bytes** including run overhead and reserve, even before retaining earlier generations/caches, an ID-to-path manifest, deltas, compaction and original photographs. Don't treat all of this as a guaranteed actual peak: the builder's checks are advisory, not atomic reservations; concurrent writers, journal overhead, filesystem format and failures can still cause ENOSPC.

**Same volume matters.** If free space is entered, it must be the free byte count of the *actual destination volume after existing snapshots and user data are already counted*. Do not add or subtract prior snapshots a second time. Never run on the S24+ without separate owner-approved synthetic-only device resource evidence, complete private backup/recovery gates and Lane A integration. The numerical planner does not auto-reduce descriptor coverage to fit storage: earlier synthetic comparisons showed losing 96×4 features can lose cropped/rotated recall.

## 384-word feature-prevalence sensitivity and actual query-cost introspection

To explore why a **full** 384-distinct-word query may exceed the existing bounded reader's 250,000-posting budget *without* allocating a ~439 MiB snapshot or touching photos, run:

```bash
python -m tools.photo_lineage.query_load_envelope --photos 150000 --query-words 384 --max-posting-visits 250000
```

This is a **closed-form what-if calculation, not a photograph benchmark**. Four mutually exclusive word-prevalence classes model query word counts: globally shared (frequency = all 150k images), same-scene (scenario-specific frequency), burst/session (smaller cohort), and rare (frequency 1). The estimated visits are exactly the sum of `query_word_count × assumed_photos_per_word` over the four classes. The five archetypes are **deliberately invented sensitivity cases**, with no inference that camera-derived ORB distributions follow any particular case:

| Deliberately hypothetical 384-word query | Estimated posting visits | 250k visit budget |
|---|---:|---|
| All 384 rare / diverse terms | 384 | Within visit budget (not a recall pass) |
| 16 scene-shared at 1k photos, 64 burst-shared at 60 photos, remaining rare | 20,144 | Within visit budget |
| 1 globally shared, 32 scene at 5k, 64 burst at 50, remaining rare | 313,487 | **Abstain / incomplete** |
| 8 global, 64 scene at 3k, 96 burst at 100, remaining rare | 1,401,816 | **Abstain / incomplete** |
| All 384 words global (formal high-overlap extreme) | 57,600,000 | **Abstain / incomplete** |

Passing the visit budget does not imply passing the **separate distinct-document memory budget** (100,000 IDs by default), finding a real source exposure, useful recall, or performance on the S24+. Failing the budget does **not** mean no relatives exist: the verified search is *incomplete*, and no matches should be treated as exhaustively absent. The profiles illustrate how visual-word concentration can affect feasibility but say nothing about actual photo frequency or descriptor independence.

**New exact-index diagnostic:** after opening a separately SHA-256-pinned numeric snapshot in the existing `bounded_posting_query.open_pinned_queries(...)` context manager, call `index.inspect_query_load(words, max_posting_visits=250000)`. It checks the same input bounds and binary-search posting windows used by `index.query`, then emits anonymous aggregate counts: distinct query words, total posting visits, largest single-word posting list, how many words occur, and `abstain_incomplete` or `within_posting_budget_only`. It **does not visit candidate document IDs, rank anything, reveal feature-word IDs, or run a photo matcher**. The shared cost preparation prevents an instrumentation-vs-search discrepancy and preserves the original strict refusal to run over-budget queries. A synthetic 384-word indexed regression exercises parity between the inspector and actual score-window limits.

**Product follow-up:** if genuine opt-in samples become legally and technically available, measure frequency distributions, p50/p95 query posting visits, *abstention fraction*, distinct photo-ID cardinality and independent verified-family recall@K by conditions. Do not optimize by silently discarding frequent words: it can change ranking/recall and especially harm weak crops, architecture, duplicates and backgrounds. Investigate privacy-preserving stronger feature selection or bounded two-stage retrieval separately. Until then, the scenarios are mechanisms for *risk prioritization*, not reported field measurements.

## Explicit complete vs two kinds of Unknown in a synthetic batch (19 October 2026)

Passing an exact posting-visit budget does **not** guarantee that a query can fit its **distinct candidate-ID** allocation budget. The bounded reader now attaches a stable `PostingBudgetExceeded.reason` of `posting_visits` or `distinct_documents`; callers do **not** have to parse changing human-readable error text. Both reasons mean **incomplete / Unknown**, not a verified negative. Malformed query input or changed/corrupt snapshot is an error, never a metric-worthy abstention.

The companion standard-library `query_batch_audit.audit_query_batch(index, requests, ...)` accepts at most 1,000 small synthetic `(visual_word_iterable, optional_expected_numeric_photo_id)` requests against an *already SHA-pinned read-only* numeric index. Its aggregate-only report distinguishes completed queries with candidate shortlist, completed queries genuinely with no >=2-word candidates, posting-visit budget abstentions, distinct-document budget abstentions, and unlabeled/labelled synthetic test requests. No query words, paths, photo IDs, names, or individual results are exported.

**Selection-bias safety:** synthetic top-1 and top-K hit fractions are calculated only among **completed requests carrying a deliberately assigned synthetic expected ID**, with their denominators reported. When all labelled requests abstain, the conditional hit rate is `null`, **not zero**. Always report completion/abstention fractions side-by-side; high conditional hit rate among easy completed requests cannot demonstrate success across a full archive. Related bursts/scenes also make per-query Wilson confidence intervals inappropriate without group-level heldout testing.

Minimal disposable numeric example (not a phone or photo-library command):

```python
from pathlib import Path
from tempfile import TemporaryDirectory
from tools.photo_lineage.external_index import build_snapshot
from tools.photo_lineage.bounded_posting_query import open_pinned_queries
from tools.photo_lineage.query_batch_audit import audit_query_batch

with TemporaryDirectory() as scratch:
    file = Path(scratch) / 'synthetic.u64'
    receipt = build_snapshot([(0, (1, 2)), (1, (1, 3)), (2, (2, 3))],
                             file, reserve_free_bytes=0)
    with open_pinned_queries(file, receipt['sha256']) as index:
        result = audit_query_batch(index, [([1, 2], 0), ([1], None)],
                                   max_posting_visits=20)
        print(result['aggregate'])
```

This is explicitly **not** a source-exposure correctness metric: IDs are hand-assigned synthetic fixture targets, and a geometric pair suggestion is always provisional. No automatic photo-family grouping, deletion, real image ingestion, cloud transfer or native app integration is introduced.

## Full 384-descriptor index vs model cross-check (19 October 2026)

The sparse 150,000-ID numeric stress test and zero-I/O frequency envelope exercise **different risks**. A new optional full-density **small numeric index** experiment closes the test-harness gap between the closed-form 384-word arithmetic and the actual pinned u64 posting-query code:

```bash
python -m tools.photo_lineage.benchmark_dense_numeric --photos 1200 --scene-size 200 --burst-size 20 --queries 8
```

The disposable benchmark builds a real sorted immutable u64 index with exactly **384 distinct numeric words per synthetic ID**: 4 globally shared, 32 shared by the assigned synthetic scene cohort, 64 shared by its synthetic burst cohort, and 284 unique to that ID. At the default 1,200 documents, this means **460,800 numeric postings**, ~3.52 MiB index data, **never 1,200 decoded photographs**. The designated cohorts and prevalence classes are invented controls, not inferred from real phone photos or an ORB model. To prevent unexpectedly huge experimental disk use, the CLI strictly limits corpus size to **200–5,000 numeric IDs**, validates group divisibility, and uses a temporary directory cleaned after the run.

For every sampled 384-word query, the experiment compares the analytical `query_load_envelope.estimate_footprint` visit total against `index.inspect_query_load()` on the independently checksum-verified real posting index; it verifies actual top-1 numeric self-ID ranking, observes visit-budget abstention one posting below the exact requirement, and reports Linux-only build/verify/query p50 and p95 and process RSS. A successful parity test means only the **numeric posting format/query arithmetic is consistent**. Self-ID top-1 is intentionally trivial, and provides **zero evidence of photographic-family retrieval precision, independent-shutter abstention, crop/rotation recall, or same-person recognition**.

This test complements, and does **not replace**, the 150k-ID sparse numeric stress and 150k×384 arithmetic disk planner. Running 150,000 IDs at 384 postings each would require ~439 MiB final packed stream and substantially more temporary storage, CPU and filesystem cache; no such full-density physical benchmark is claimed here. Benchmark outcomes remain synthetic Linux measurements, not Samsung Galaxy S24+ app performance. Do not run against private Gallery/Termux databases or originals without explicit approved integration/backup planning.

## Honest all-labelled top-K bounds under abstention (19 October 2026)

**Reporting only a perfect top-K score among completed queries is misleading when difficult queries abstain.** The aggregate-only `query_batch_audit` now retains its conditional top-1/top-K fractions but also reports **deterministic censoring bounds over ALL synthetic labelled queries**. Completed labelled misses remain proven misses for their preassigned numeric oracle. Abstentions are unresolved; the lower bound assumes every abstention misses, while the upper bound assumes every abstention would hit:

```text
all_labelled_lower = completed_known_hits / total_labelled
all_labelled_upper = (completed_known_hits + labelled_abstentions) / total_labelled
```

Every report includes exact numerator/denominator counts, separately for top-1 and top-K. If no labelled requests exist, bounds are `null`; if all labelled requests abstain, conditional accuracy is `null`, while the all-labelled possibility interval is [0, 1]. **These are deterministic accounting bounds, NOT statistical 95% confidence intervals, real dataset coverage, or actual gallery precision.** Their value is to prevent selective-query completion from being glossed over.

A newly added **full 384-word-per-ID synthetic adversarial fixture** builds a real checksum-verified disposable 200-ID posting index with deliberately invented global/scene/burst/unique feature frequencies. Seven labelled requests are made: four easy 348-word subqueries correctly recover their assigned numeric IDs, one finishes with an empty shortlist, one exceeds the 1,000-posting visit budget, and one exceeds the 20-distinct-ID budget even though its visits are within quota. The completed-only top-1 fraction is **4/5 = 80%**, yet with the two unresolved queries the all-labelled correct fraction could be only **4/7 = 57.14%** or as high as **6/7 = 85.71%**. A second fixture shows an apparently perfect 100% among completed requests when half the requests abstain (all-labelled interval 50%–100%). This is **not camera-photo retrieval recall**: easy queries include their indexed synthetic source ID on purpose, and all ground-truth IDs are manually assigned fictional records.

The evaluation makes no inferences about whether `candidate` edges denote the same physical exposure, and no photos, metadata paths or unredacted IDs are in reports. The existing `query_batch_audit` has **no automatic class-balanced or scene-independent evaluation**—samples here are an artificial teaching/test corpus. Real heldout performance needs representative actual images, actual target provenance, condition-level error breakdowns, scene-aware splitting and a separately measured abstention rate. Never auto-merge or delete any photographs based on numeric indexing scores.

## Condition-stratified synthetic completeness audit

The aggregate `query_batch_audit` correctly separates completed rankings and typed posting/ID budget abstentions, and the newer censoring bounds report a possible all-labelled hit interval. Those totals can still hide a scenario where simple resizes work but challenging crops and arbitrary-angle rotations never complete. `condition_query_audit.audit_conditions(...)` stratifies a bounded numeric query batch into the exact six supported categories: `unmodified`, `resized`, `gamma`, `cropped`, `rotated_crop`, and `jpeg_reencode`.

Each synthetic record is `(condition, bounded_visual_words, optional_expected_numeric_id)`. A single verified pinned u64 index is supplied by the caller; the helper invokes the existing bounded query-batch audit for **each populated category** without photo reads or network access. For every category it returns completed nonempty, completed empty, **posting-budget Unknown**, **distinct-ID-budget Unknown**, conditional completed-only top-1/top-K, and deterministic **all-labelled censoring bounds**. It additionally provides a whole-batch aggregate derived exactly from the six category counters. Conditions not sampled are explicitly `null` and listed as **not measured**, never shown as zero error. Record quota is 1..1000; all categories/budgets are strictly validated. The output contains only fixed condition labels and aggregate counts—no numeric photo IDs, query words, original paths, raw feature vectors, or image data.

Disposable example requiring no photos:

```python
from pathlib import Path
from tempfile import TemporaryDirectory
from tools.photo_lineage.external_index import build_snapshot
from tools.photo_lineage.bounded_posting_query import open_pinned_queries
from tools.photo_lineage.condition_query_audit import audit_conditions

with TemporaryDirectory() as folder:
    snapshot = Path(folder) / 'numeric.u64'
    built = build_snapshot([(0, (1, 2)), (1, (1, 3)), (2, (2, 3))],
                           snapshot, reserve_free_bytes=0)
    with open_pinned_queries(snapshot, built['sha256']) as index:
        rows = [('resized', [1, 2], 0), ('rotated_crop', [1, 3], 1),
                ('cropped', [1], 0)]
        print(audit_conditions(index, rows)['per_condition'])
```

**Scientific warning:** condition names and expected labels in this experiment are **assigned synthetic fixtures**. Neither the names nor any matching scores prove that two real photographs were derived from the same original exposure. No real crop/rotation/recoloring performance, independent-camera negatives, classifier precision, aggregate population error rate or S24+ latency can be inferred. Multiple query edits of one underlying scene are statistically dependent; independently curated scene-level grouping is required for credible heldout uncertainty. A high overall completed-only top-K accuracy accompanied by null/poor crop coverage must never authorize automatic lineage grouping, deleting, renaming or identity merges.

## SQLite shortlist now enforces the same posting budget (20 October 2026)

The original executable `retrieval.shortlist()` (SQLite ORB path) previously computed and ranked candidate groups with **no limit on matching inverted-word postings**. On a gallery full of repeated patterns this could consume substantial time and temporary sorting memory, despite the newer research-only packed-u64 reader already abstaining on expensive terms.

The SQLite path now defaults to the same **250,000-posting visit ceiling** (`max_posting_visits`, configurable between 1 and 10,000,000 for a controlled experiment). A read-only SQL transaction snapshots the existing cache consistently; before any `GROUP BY` or ranking, a counted subquery scans **at most budget + 1 matching postings** using an INNER `LIMIT`. This distinguishes a safe query whose complete candidate window fits the ceiling from an over-budget query that raises the existing typed `PostingBudgetExceeded(reason='posting_visits')`. The scoring query and safety preflight are executed within the **same SQLite read snapshot**, preventing a concurrent writer from silently increasing posting cardinality between checks. This change reads only the source query photo and local index, not the whole gallery; its only effect is to fail closed rather than silently continue an excessively broad retrieval.

`review_shortlist` accepts the same optional budget and deliberately propagates the incomplete-search exception; it must **never** convert it to a normal empty shortlist, nor treat a common background feature as proof of no photograph relatives. Zero matching word postings still yields an actual completed empty shortlist. An unchanged-sized cache may still be semantically stale relative to external media changes; the older candidate-file stat checks and 4,096-stale-entry scan bound remain, as before.

```python
from tools.photo_lineage.retrieval import shortlist
from tools.photo_lineage.bounded_posting_query import PostingBudgetExceeded

try:
    hits = shortlist('query.jpg', 'photo_index.sqlite', max_posting_visits=250_000)
except PostingBudgetExceeded as exc:
    assert exc.reason == 'posting_visits'
    # Show an honest Unknown/incomplete search, NEVER a confirmed no-match.
```

**Not a performance certification:** SQLite may still need to read up to the chosen bounded posting limit, and the actual grouping/sorting phase can consume significant CPU and RAM for 250k moderately distributed hits. This policy also does **not** guarantee completeness beyond candidate ranking `top_k` or prove photo exposure identity. No background indexing, originals modification, production Android wiring, or on-device timing was added. The safe next step is synthetic p50/p95/RSS benchmarking of the **actual SQLite path** under repeated-descriptor workloads, followed by separate owner-approved device evidence.

## Separate SQLite candidate-ID cardinality budget (20 October 2026)

The first SQLite posting budget protects against expensive **posting visits**, but a query can still match fewer than 250,000 postings spanning more than 100,000 different photo IDs. Grouping and sorting that many independent candidates can create significant transient memory pressure even without an over-budget visit count.

The real `retrieval.shortlist` and `review_shortlist` now accept `max_distinct_documents` (default **100,000**, 1–1,000,000 allowed), matching the research-only packed-index reader. After the bounded posting-visit scan succeeds, the code counts distinct `photo_id` groups using a **LIMIT cap + 1** inside the aggregate subquery. The two checks and the eventual shortlist all use one consistent SQLite read snapshot. If the distinct-ID cap is exceeded, it raises `PostingBudgetExceeded(reason='distinct_documents')`; a posting-volume failure separately uses `reason='posting_visits'`. **Neither failure is an empty hit list, a true no-match or permission to delete photos**.

These are safety bounds, not absolute time or RSS guarantees. A successful query near both caps can still allocate SQLite temporary grouping buffers and incur substantial CPU or I/O latency. In particular, distinct counting itself requires scanning/grouping the already-limited postings. A future practical implementation may need a tighter device-specific budget and on-device synthetic p95/RSS measurement, preferably with independent immutable snapshot provenance and photo-stat freshness gates. No real S24+ memory/thermal results, private Gallery scan, auto-grouping, or UI changes are supplied by this test.

## Reproducible real-SQLite budget stress without gallery data (20 October 2026)

Run this **tiny, entirely disposable** synthetic numeric row benchmark to measure the *actual* SQLite shortlist safeguards rather than only the packed-u64 research code:

```bash
python -m tools.photo_lineage.benchmark_sqlite_budget --docs 2000 --repetitions 3
python -m unittest tools.photo_lineage.tests.test_benchmark_sqlite_budget -v
```

The harness creates a private temporary directory, **two procedural 256px PNGs** (query and one identical synthetic hit), then a new SQLite ORB inverted index containing N *fabricated numeric document records* with the same two query visual words. N-1 paths are **fictional and nonexistent** and no files are created at those paths. It checks that the posting-visit budget at `2N-1` throws typed `posting_visits`, a complete visit quota plus an ID budget of `N-1` throws typed `distinct_documents`, and inclusive thresholds `2N` and `N` permit the genuine synthetic hit to rank first. The temporary directory is removed upon completion; no user's gallery, SQLite database, People records, public copyrighted media or phone filesystem is inspected or changed.

The executable limits **N to 10–10,000 fabricated IDs** and 1–8 repeats, reporting actual temporary SQLite MiB, construction duration, completed query p50/p95, each explicit abstention latency, and Linux process peak RSS. This is **not a simulated 150,000-photo workload**, and each artificial ID has only two deliberately high-frequency numeric words rather than up to 384 camera-derived descriptors. One toy synthetic self-hit says nothing about photo provenance or recall; N-1 fake paths are deliberately stale and never loaded. Runtime metrics are host-/cache-dependent and not a promise of S24+ latency, memory, battery or temperature. The smoke CI fixture uses only 60 IDs. On-device analysis would require separately authorized synthetic-only measurements and no private gallery scans.
