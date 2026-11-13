# Photo DNA — offline image-lineage evidence (Lane B, issue #83)

**Status: experimental candidate generator, not an image-deletion, identity, or production deduplication service.** All operations are local/offline. Photo inputs are read-only; no images or sidecars are rewritten, moved, deleted, uploaded, or copied. JSON evidence is printed to stdout. The tool never assigns people, performs transitive identity/lineage merges, or deletes media.

## Method and rationale

Crops, arbitrary rotation, retouching, colorization, and resized copies invalidate filename/EXIF matching and frequently invalidate global perceptual hashing. An 8×8 DCT hash is used **only as diagnostic information**, never as sufficient proof. Two unrelated patterned grids even produced Hamming distance **0** in the deterministic synthetic test while the geometric matcher abstained.

Default **SIFT** (robust, slower), **ORB** (faster), or **AKAZE** locate grayscale local features; mutually consistent Lowe ratio matches are fed to similarity-transform RANSAC (translation, scale, arbitrary rotation), followed by distributed-inlier checks and registered luminance/gradient correlation. These avoid unsupported probabilistic confidence claims. Even `candidate` always means *review required*, not confirmed shared original exposure. `unknown` is an abstention, not a negative determination.

References: [OpenCV matching / Lowe ratio](https://docs.opencv.org/4.x/d5/d6f/tutorial_feature_flann_matcher.html), [OpenCV ORB/AKAZE tracking and geometric verification](https://docs.opencv.org/4.x/dc/d16/tutorial_akaze_tracking.html), [OpenCV license](https://opencv.org/license/) (OpenCV 4.5+ Apache-2.0; SIFT patent expired 2020). No model weights, cloud APIs, or downloads of private images are used.

## Reproducibility

From repository root in an isolated Python virtualenv:

```bash
python -m pip install -r tools/photo_lineage/requirements.txt
python -m unittest discover -s tools/photo_lineage/tests -v
python -m tools.photo_lineage.benchmark
python -m tools.photo_lineage.cli pair /path/to/original.jpg /path/to/edited.jpg
python -m tools.photo_lineage.cli scan /path/to/small/folder --max-images 20
```

`pair` reports image paths, algorithm, number of keypoints, descriptor matches, RANSAC inliers, normalized inlier spread, geometric rotation/scale, photometric correlation, diagnostic pHash distance, and a `candidate` / `review` / `unknown` verdict. `scan` examines a **single non-recursive directory** and returns pairwise candidate edges; it never automatically merges groups. The command refuses more than 20 photos/190 pairs by default (explicit hard cap 100 images), because pairwise work scales O(N²). **Do not run this on a 150,000-photo library.**

## Deterministic synthetic baseline (2026-10-16)

Tested with Python / OpenCV 4.13 / NumPy 2.3 / Pillow 12.3 on a Linux CPU, **not Android timings**. Eight synthetic comparisons per algorithm: four edited versions (resize, crop, arbitrary 33° rotation plus crop, gamma/brightness edit) and four negative controls (distinct photo, misleading shared text, repetitive grid, blank). Additional exploratory sweep of 9 rotations from −157° to 178° yielded all `candidate` for both ORB and SIFT, and 6 unrelated synthetic pairings yielded `unknown` for both; not a held-out statistically representative study.

| Algorithm | Four edited variants | Four negative controls | Observed pair latency |
|---|---|---|---|
| SIFT | 4/4 candidate | 0/4 false candidate | ~149–219 ms |
| ORB | 4/4 candidate | 0/4 false candidate | ~11–50 ms |
| AKAZE | 4/4 candidate | 0/4 false candidate | ~65–92 ms |

Hard negative: separate synthetic photos sharing identical `ATLAS` lettering yielded 32 SIFT / 35 ORB / 73 AKAZE geometric inliers but still correctly abstained after distributed inlier/structural checks. No statistical precision/recall claim is warranted from this tiny synthetic sample.

## Safety and known gaps

- This cannot prove identical camera exposures; different burst photos or the same static view may register well. A candidate is merely a recommended manual review.
- Severe recoloring that changes image luminance, tiny crops, strong perspective changes, untextured backgrounds, overlays or large edits may be missed. HEIC, RAW and video decoding are **not** provided in this Python tool.
- EXIF orientation is honored; EXIF GPS/person metadata isn't inspected. Symlinks and >95-megapixel images are rejected. Nothing is indexed or persisted by default.
- Direct `compare_arrays` calls now reject non-NumPy, non-2D, non-uint8, undersized (<48px side), or oversized (>4096px side) arrays **before** pHash/OpenCV detection. `np.rot90` negative-stride views remain valid. This is a coarse finite host-side work bound, not an Android RAM/latency guarantee; the default 1100px file reader already operates well under it.
- Thresholds are uncalibrated research heuristics, not probabilities. No facial identity model, native UI, V4/V5 code, signing state, phone database, or images were modified.
- **CI (October 2026):** `photo-lineage-safety.yml` now runs Python synthetic unittests and an available `ci_gate.py` on exact PR heads changing `tools/photo_lineage/**`. Only successful exact-head Photo DNA checks count as integration evidence; Android checks do not substitute.

## Next evidence-driven steps

1. Scalable on-device *retrieval* (bounded disk-backed manifest/cache; rotation+crop-sensitive high-recall shortlist) separated from the geometric *verification* stage, with explicit recall@K vs exhaustive pairs on larger synthetic libraries.
2. Hard held-out negatives (near-identical burst exposures, shared backgrounds, repeating architecture, logo-only overlap) and positives (small crop, complex arbitrary rotations, severe recoloring); calibrate thresholds with abstention.
3. Native Android/Termux latency, battery, privacy and decoder feasibility; hand off Android integration to Lane A rather than editing another lane's files.


## Optional *experimental* large-library shortlist prototype (stacked Lane B follow-up)

A second stage uses **96 strong ORB keypoints per photo**, projected into four 24-bit words each. A local SQLite inverted index maps visual-word matches to a *shortlist only*, which is then separately vetted by the geometric matcher. Unlike full-library pair comparisons, a query only visits postings that share selected descriptor words. This approach replaced an unsuccessful 64-register MinHash pilot: in synthetic tests MinHash overlap for rotated crops was too close to unrelated-image overlap, so it is **not used**.

```bash
# Keep SQLite OUTSIDE the source photo folder; no images are modified.
python -m tools.photo_lineage.cli index /path/to/photos --db /path/to/cache/atlas-lineage.sqlite --limit 200
# Rerun to incrementally resume: unchanged files are skipped (size + mtime).
python -m tools.photo_lineage.cli index /path/to/photos --db /path/to/cache/atlas-lineage.sqlite --limit 200
python -m tools.photo_lineage.cli find /path/to/query-photo.jpg --db /path/to/cache/atlas-lineage.sqlite --top-k 15
# Explicitly verify shortlisted image pairs, returning only reviewable evidence.
python -m tools.photo_lineage.cli find /path/to/query-photo.jpg --db /path/to/cache/atlas-lineage.sqlite --top-k 15 --verify --algorithm orb
python -m tools.photo_lineage.benchmark_retrieval
```

**2026-10-16 exploratory evidence:** 81 entirely synthetic images (one seeded source plus 80 independently seeded distractors) indexed in **~1.4 s**, **31,046** visual-word postings, **~0.77 MB** SQLite on one Linux CPU. Seven rotated/cropped query variants (−150°, −95°, −44°, 26°, 68°, 111°, 163°) all retrieved source at **rank 1/81**, ~9–11 ms/query. This is a **very small, same-generator synthetic retrieval benchmark**, *not* a real-world retrieval-accuracy guarantee or a mobile performance projection. Given this synthetic footprint, the cache could become sizable for large galleries; storage and seek latency must be tested on hardware before rollout. The retrieval shortlist requires at least two common words and can miss severely edited images; `unknown` and missing shortlist entries are **not negative evidence**.

Index contents are local metadata **including original file paths**, sizes, modification timestamps and nonreversible descriptor projections. Do not publish/share the database as if it contained no private information. The opt-in cache is the only file written, with batched resumable commits (every 25 images). Images are loaded in grayscale for extraction; HEIC/RAW/video are not supported. Inputs above 95 MP, photo symlinks, and media-root database locations are refused. Stale or missing paths do not return candidates. No cloud calls, deletions, identity joins, or Android UI integration.

**Unresolved quality gate:** substantial held-out mixed-photo benchmarks, production crop-coverage recall@K, near-identical independent burst-image negatives, on-device resource usage, safe SQLite schema migration and green exact-head Python CI verification. Remain on a lane-scoped branch until verified.


### Additional adversarial validation (2026-10-16)

The first pair verifier could give an overly strong `candidate` to distinct exposures with a largely identical static background and changed central foreground. The stacked branch now checks **local warped-gradient correlation in 8×8 tiles**, and requires >=88% locally consistent eligible tiles for a strong candidate (otherwise at most `review` if global geometry is plausible). A deterministic central-foreground replacement test reproduces and guards this failure. The full synthetic pair-verification suite remains green. This still does **not** prove identical camera exposure; small moved objects can evade the check, and strong recoloring can reduce recall.

The retrieval benchmark now supports multiple distinct synthetic anchors:

```bash
python -m tools.photo_lineage.benchmark_retrieval --anchors 8 --distractors 80
python -m tools.photo_lineage.benchmark_retrieval --anchors 8 --distractors 250
```

- 8 anchor images + 80 distractors (88 indexed): **55/56 recall@1, 56/56 recall@5**, median query ~10.15 ms, ~0.824 MB cache, ~1.56 s indexing (synthetic local CPU).
- 8 anchor images + 250 distractors (258 indexed): **55/56 recall@1, 56/56 recall@5**, median query ~10.2 ms, ~2.47 MB cache, ~4.7 s indexing.
- Separate 1 anchor + 500 distractors (501 indexed): **6/7 recall@1, 7/7 recall@5**, ~10.9 ms median, ~4.86 MB cache, ~9.55 s indexing.

These are from the same procedural synthetic image generator (not independent camera scenes), and are **not** real-world calibration or evidence of 150k-photo performance. The more conservative local-consistency gate intentionally trades some sensitivity for fewer strong false-family classifications.


## Resilience of local metadata cache (stacked Lane B cache-hardening PR)

Cached paths become stale after photos are **externally** moved, renamed, deleted or edited. An adversarial synthetic test exposed that the original index query stopped at the first five scored rows for `top-k=1`; after those files disappeared, the query returned **no result** even though two matching photographs remained indexed. Ranking now uses stable score/path ordering and fetches successive batches until enough **valid** files are found. For pathological stale/ambiguous caches it fails visibly after 4,096 inspected rows instead of silently returning false absence. Retrieval is still approximate and can miss extreme crops.

The database open path also fails closed on an existing nonempty, unrecognized SQLite file or mismatching fingerprint/version metadata. In particular, it will **not** add ATLAS tables to a user's existing unrelated database.

An explicit **local cache audit** (safe by default, no photo deletions) helps rebuild indexes after substantial reorganization:

```bash
python -m tools.photo_lineage.cli audit-cache --db /path/to/cache/atlas-lineage.sqlite
# Explicit, optional action: remove outdated entries from *cache only*.
python -m tools.photo_lineage.cli audit-cache --db /path/to/cache/atlas-lineage.sqlite --apply
# Reindex current photo paths after cache maintenance:
python -m tools.photo_lineage.cli index /path/to/photos --db /path/to/cache/atlas-lineage.sqlite --limit 200
```

The audit checks the **entire** cache or refuses to proceed if it exceeds the default 50,000-row bound; explicitly pass `--max-entries 200000` for larger galleries. Dry-run prints counts and up to ten local file paths/reasons. `--apply` only removes fingerprints and records inside the optional, regenerable **SQLite index**, never touches originals, never deletes source images, and never infers image relationships. Permission/I/O failures do not count as safe evidence of missing images and are left uninspected. The audit does not rewrite the source or search recursively.

**Measured synthetic safety evidence:** 22/22 local Python unittest methods PASS, including the formerly failing stale-ranking case, unrelated SQLite database untouched byte-for-byte, invalid cache version blocked on both query and update, audit dry-run vs opt-in apply, preserved source bytes and prevention of partially executed over-limit audits. CI workflow approval remains with the workflow-owning lane under #83; none of these figures establish on-device benchmark accuracy.

### Larger synthetic retrieval check (additional evidence)

With `--anchors 8 --distractors 500` (508 index records, 194,782 postings), indexing took **9.484 s**, the SQLite cache occupied **4.918 MiB**, and median query latency was **10.19 ms** on the local Linux CPU. Across 56 33°–163° arbitrary rotated/cropped queries, recall was **52/56 at rank 1**, **55/56 in top 5**, **56/56 in top 20**. Worst observed query took **24.6 ms**. The failure at rank 9 is a concrete reason to avoid limiting verification to top 5. These are synthetic generated scenes, not measured Android or real-photo retrieval quality; projected full-library cache size may exceed a gigabyte before compression/sharding, and remains a scalability risk.

The expanded local suite includes **18 passing unit tests**, including the audit refusing to create a database when the supplied path does not exist. The optional `--apply` action edits the disposable SQLite cache only.

### Storage/recall ablation — preserve features rather than blindly reducing them

```bash
python -m tools.photo_lineage.benchmark_storage_tradeoff --anchors 8 --distractors 250
```

This is a **separate in-memory synthetic experiment** (not a production index format change). It reuses OpenCV ORB keypoint extraction and current visual-word projections while varying how many strong keypoints and independent descriptor bands are retained. Among 258 procedural images and 56 rotation/crop queries:

| Keypoints × descriptor bands | Relative posting records | Top-5 source recall |
|---|---:|---:|
| 32 × 4 | 0.334 | 0.643 |
| 48 × 4 | 0.500 | 0.786 |
| 64 × 4 | 0.667 | 0.946 |
| 96 × 3 | 0.750 | 0.964 |
| 96 × 4 (baseline) | 1.000 | 1.000 |

**Engineering decision:** do **not** lower the existing 96×4 feature budget yet: this synthetic set exhibits meaningful recall loss. Instead investigate more compact posting/storage representations, caching/sharding, optional tiered or on-demand indexing and Android quota checks. The 508-image baseline's 4.918 MiB SQLite footprint suggests roughly **1.45 GiB for 150,000 similarly textured images by linear extrapolation**, which is not a real-device measurement or capacity guarantee. Some image corpora will have fewer features, some more.

**Resource hygiene:** SQLite's connection context manager commits/rolls back but does **not** close the connection automatically. All three production index/search/audit call sites now use explicit `contextlib.closing`; test fixtures do likewise. The **22/22** local unit tests and synthetic CI gate pass with `python -W error::ResourceWarning -m unittest discover -s tools/photo_lineage/tests -q` and no reported resource warnings. No new model weights or remote dependencies.

### Compact immutable posting-stream research (not yet a production index)

```bash
python -m tools.photo_lineage.benchmark_packed_index --anchors 8 --distractors 250
```

A separate synthetic-only prototype encodes each ORB descriptor word and photo ID into **one 64-bit integer**, sorts these pairs, then binary-searches a **read-only memory-mapped posting stream**. Importantly, it uses the **same full 96×4 visual features** as the existing SQLite index; no feature budget is sacrificed.

At 258 synthetic photos / 98,933 postings / 56 transformed queries:
- Packed posting stream: **0.755 MiB** versus the existing **~2.47 MiB SQLite complete cache** in the comparable 258-photo synthetic benchmark.
- Recall: **56/56 top 5**, 56/56 top 20. Top-1 55/56, affected by stable tie ordering.
- Read-only posting lookup **median 2.08 ms**, p95 **2.71 ms**. **Not comparable** to full SQLite lookup timings: feature extraction, filename manifest, file existence/stat validation and image decoding are excluded. Packed construction **0.035 seconds excludes feature extraction**, too.
- As a *rough lower-bound illustration*, 150,000 images at ~383 postings each × 8 bytes/posting implies **~438 MiB just for postings**, plus manifest, incremental segments, space for atomic compaction, and index bookkeeping; this is NOT measured on Android.

**Decision:** this is valuable evidence for reducing storage without dropping descriptors, but **do not replace SQLite** or run it on private images. The prototype rebuilds a whole immutable snapshot in RAM before writing; that is unsuitable for a 150k-photo mobile library. Next research should examine bounded-memory external merge sorting or chunked sorted segments, checksummed crash-safe atomic manifest replacement, stale-entry invalidation, quota management and real device metrics. A compact index with no safe incremental update or recovery would be a regression, not an improvement.

Latest local gate: **22/22 unittests PASS** with `-W error::ResourceWarning` and the original `ci_gate.py` PASS. These checks are local, not green exact-head GitHub Actions.


### Direct visual-word input boundary

Although indexing and query decoding normally downsize images to 800 px on the
longest side, research callers could invoke `retrieval.words_for_image`
with unbounded ndarray views, bypassing that input gate. Direct calls now
require an **ordinary 2D uint8** grayscale ndarray, **48–2048 pixels per side**
and at most **4 million pixels**, before creating ORB. Oversized broadcast
views, RGB, smaller/incorrect dtype and masked arrays fail closed with a
`ValueError`, not a misleading empty shortlist. Ordinary rotated
negative-stride NumPy views remain supported and preserve fingerprint parity.
The production file-entrypoint, 800px resize, visual-word projections and
SQLite fingerprint version are unchanged. These caps reduce research
CPU/memory hazards but do not certify native Android p95/thermals or hostile
library safety. Synthetic tests assert malformed inputs never reach OpenCV.
