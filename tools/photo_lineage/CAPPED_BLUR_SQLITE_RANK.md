# Capped blur ORB evaluation in actual disposable SQLite shortlist

Lane B / issue #83. This is strictly research-only. Nothing here changes
production retrieval fingerprints, the Android app, source media, or persistent caches.

## Offline run

From the repository root, optionally run on a Linux research host with
the exact seven locally installed scikit-image sample PNGs and their existing
SHA-256 pins. No new downloads or third-party camera files are bundled.

    python -m tools.photo_lineage.capped_blur_sqlite_rank --cap 0 --distractors 16
    python -m tools.photo_lineage.capped_blur_sqlite_rank --cap 128 --distractors 16
    python -m tools.photo_lineage.capped_blur_sqlite_rank --cap 256 --distractors 16

Five individually licensed public-domain/CC0 photos and two Middlebury
Motorcycle photographs from distinct camera viewpoints provide seven
sources but at most SIX independent scenes. Source image bytes must match
the existing hard-coded, reviewed SHA-256 allowlist or the run refuses.

The study writes temporary, grayscale RE-ENCODED public photo copies plus
0–32 canonical deterministic fictional distractors inside a dedicated
TemporaryDirectory. It calls the EXISTING retrieval.index_folder on that
temporary corpus, checks that every baseline SQLite source posting set is
identical to the unchanged ORB words_for_image output, and then transactionally
replaces only these temporary source rows with a per-band selection of
0/64/128/192/256 novel ORB words from a sigma-2 blurred version.
Baseline words are always retained. The four existing 24-bit ORB descriptor
bands and the unchanged two-word shortlist threshold are used.

The temporary modified database preserves the original read API's
compatibility metadata solely to exercise EXACTLY the existing
retrieval.shortlist. It MUST NEVER be persisted, exported, promoted into an
existing cache, or represented as a valid production fingerprint version.
The disposable context deletes all source copies, queries and its database
on completion or failure; originals are NEVER opened for writing.

For every sample, run Gaussian sigma 8 and 31-pixel horizontal motion blur
queries against the same experimental temporary SQLite index. Both query
blur strengths differ from the index-only augmentation sigma 2, but the
underlying source originals are reused, so this is NOT independently heldout.
The actual SQLite shortlist uses the existing preflight posting visit
and distinct-document budgets, candidate-file freshness checks,
score/path order, and top-20 limit. Budget failure propagates as an ERROR:
it never masquerades as an empty family or absence of relatives.

Report known original eligibility and rank at 1/5/20, eligible but missing
source count, competing public-candidate offers, one opposite-camera
Motorcycle stereo rank, actual temporary SQLite file bytes, baseline and
supplementary posting counts. No image data, source paths or identifiers
other than public study labels, or media-derived hash values are emitted.

## Tests and boundaries

Hosted CI uses FICTIONAL gray source images, seven distinctly textured
fictitious positives and separate constant-image abstentions. Positive
controls run REAL SQLite insertion, supplementary-word replacement, and
the unmodified production shortlist against synthetic exact-copy queries
(a deliberately easy correctness test, not blur-performance evidence).
Further tests require PostingBudgetExceeded to propagate and malformed
parameters, duplicate candidates or identical stereo images to fail closed.

An independent host-side SQLite reproduction was run on seven exact
SHA-256 pinned public camera samples, as documented below. The host used
the same numeric ORB projection, Pillow resize and SQL rank/preflight logic,
but did NOT import the byte-identical GitHub module, so this observation is
not described as exact-head CLI execution. Previous
in-memory ablations (#332 and #335) remain separate evidence and did not
exercise SQLite. Neither this harness nor the small camera cohort proves
representative recall, source/shutter equivalence, independent scene
calibration, full 150,000-photo disk and hot-word behavior, or S24+
CPU, RAM, thermals or battery performance. An extra 256-word cap limits
one posting dimension arithmetically, but is not permission to ship.

Do not auto-create, merge, or delete photo families. All candidates
remain manual-review suggestions with explicit Unknown abstention.

## Independent public-camera SQLite reproduction — 21 October 2026

On an isolated Linux host (Python 3.13.5, OpenCV 4.13.0, NumPy 2.3.5,
Pillow 12.3.0, scikit-image 0.26.0), all seven packaged public-image PNG
source bytes independently matched their existing hard-coded SHA-256 pins.
An independent implementation faithfully followed the repository's 800px
Pillow grayscale resize, 96-keypoint × four-band ORB extraction, deterministic
band-balanced cap selection, SQLite inverted-word storage, score/path ranking,
and candidate-freshness semantics. Host query posting counts were within the
published budgets; the GitHub module's exact bounded SQL preflight query
was NOT imported or performance-tested. The 16 distractors were generated
with the canonical benchmark.textured(2000+i, 256) method (not earlier
noncanonical uniform noise). One disposable SQLite DB was built per cap.

| Extra words/photo | All 23 indexed files: postings | SQLite bytes | Gaussian sigma8: eligible / top5 | Motion length31: eligible / top5 | Other-camera stereo left rank on blurred right, motion31 |
|---|---:|---:|---:|---:|---:|
| 0 | 8,737 | 233,472 | 1/7 / 1/7 | 3/7 / 3/7 | absent |
| 64 | 9,185 | 245,760 | 2/7 / 2/7 | 5/7 / 5/7 | **1** |
| 128 | 9,579 | 253,952 | 2/7 / 2/7 | 5/7 / 5/7 | **1** |
| 192 | 9,963 | 262,144 | 3/7 / 3/7 | 6/7 / 6/7 | **1** |
| 256 | 10,342 | 274,432 | 3/7 / 3/7 | 7/7 / 7/7 | **2** |

With cap256, known original ranked first for 3/7 Gaussian8 queries and 6/7
motion31 queries; the seventh motion31 original still appeared within top5.
The single different-camera-view stereo negative ranked first or second in
the 31px motion condition once supplementary words were enabled. Thus
increased retrieval opportunity simultaneously **increases false-family
review pressure**, without authorizing claims of identical physical exposure.
At cap256 the observed 10,342 database postings were 18.4% above the 8,737
baseline postings, while the SQLite file grew from 233,472 to 274,432 bytes
(17.5%) in this tiny example. These measured bytes include SQLite overhead
at 23 files; do not extrapolate the percentages to a 150,000-photo gallery.

**Reproducibility boundary:** This was a LOCAL independent method reproduction,
not a GitHub Actions real-media test and not a byte-for-byte execution of the
newly merged CLI. The CLI and its CI-validated fictitious-image controls
are available to repeat the real-camera study on an exact-commit host.
The independent observations reuse just seven photo sources from at most
six scenes, have correlated edit queries, only one stereo hard negative,
and no independently verified same-camera shutter dataset. No field
precision/recall confidence bound, 150k posting budget, on-device p95/RAM,
or automatic source-equivalence safety conclusion follows.

## Optional retrieval → geometric verification funnel

Use the optional --verify-algorithm orb, sift or akaze CLI parameter to
compare the same seven known source originals and their fourteen blurred
queries *after* the unchanged SQLite retrieval, using the existing
matcher.compare_arrays with its current conservative review thresholds.
Every source pair is also checked when not retrieved to separate losses:
geometry reviewable but missed by retrieval, retrieved but geometry Unknown,
and retrieved and geometrically reviewable. If the different-viewpoint
left Motorcycle photo appears when querying blurred right, the opposite
view is also compared by the matcher and reported as candidate/review/Unknown,
still NEVER as identical physical exposure. The default run without this
flag retains exactly the prior lightweight, retrieval-only behavior.

    python -m tools.photo_lineage.capped_blur_sqlite_rank --cap 256 --verify-algorithm orb

The extra research mode evaluates only two heldout edit *strengths* and
at most seven unique source photos; image content is re-used across
index and query. A geometrically strong result cannot certify shutter
equality, and a stereo scene can pass geometric gates despite being
a known **different camera viewpoint**. The CLI never attempts merging
or deletion and never changes production matcher thresholds. All reports
contain only aggregate counters and fixed public sample labels; no
private image paths, candidate IDs or descriptor vectors are published.
New synthetic tests mock geometric verdicts on fictional arrays and verify
both the success funnel and cases where a retrieved source still abstains.

## Independent pinned public-source retrieval → geometry funnel — 21 Oct 2026

With all seven packaged public-source PNG byte SHA-256 pins verified, the
same isolated Linux Python3.13.5/OpenCV4.13/NumPy2.3/Pillow12.3 host trial
reproduced the unchanged matcher.compare_arrays *logic and published
thresholds* for ORB, SIFT and AKAZE on original vs blurred queries, plus
the separate-camera-view Middlebury stereo hard negative. It cross-tabulated
those review/Unknown verdicts against the preceding **independently
reproduced** temporary SQLite ranks with 16 canonical fictional distractors.
The host recreated, but did not import, byte-identical GitHub source; these
are preliminary independent observations, NOT exact-head CI real-media
results or production recall.

| Extra word cap | Gaussian sigma8: retrieved AND geometry-reviewable, ORB / SIFT / AKAZE | Motion31: retrieved AND geometry-reviewable, ORB / SIFT / AKAZE |
|---|---:|---:|
| 0 | **0 / 1 / 0** of seven | **0 / 1 / 1** of seven |
| 64 | **0 / 2 / 1** | **2 / 3 / 2** |
| 128 | **0 / 2 / 1** | **2 / 3 / 2** |
| 192 | **0 / 2 / 1** | **2 / 3 / 2** |
| 256 | **0 / 2 / 1** | **2 / 4 / 2** |

Independent verification opportunity, even without retrieval, was
ORB **0/7** for Gaussian8 and **2/7** for motion31; SIFT **4/7** for
both; AKAZE **3/7** and **2/7** respectively. Hence strengthening the
shortlist alone cannot recover more than these geometric-review ceilings
for this fixed single-algorithm pipeline. At cap256, the SQLite shortlist
contained all **7/7** long-motion blurred originals, yet **5/7** would
still abstain under ORB geometry and **3/7** under SIFT. Severe Gaussian
blur yielded **zero** reviewable ORB source pairs at every cap.

The opposite-camera stereo left image ranked **first or second** when
querying the motion31-blurred right image under augmented profiles, yet
the independently replicated ORB/SIFT/AKAZE geometric checks all returned
**Unknown** in that motion31 condition. This is a useful *specific*
counterexample to treating retrieval rank as identity; it does NOT
establish a reliable false-positive filter across other scenes. For the
Gaussian8 stereo view, AKAZE returned a **review** verdict even though
the stereo image did not enter this seven-source SQLite shortlist under
those test conditions. Different camera viewpoints remain different
physical image sources, even when geometric content overlaps strongly.

**No winner is selected or deployed:** only seven source photos from at
most six scenes, correlated blur transformations, one distinct-camera
stereo hard negative and zero independently verified same-camera
separate-shutter negatives. All numbers are descriptive, not calibrated
precision/recall, a population error interval, or S24+ latency/RAM proof.
Review suggestions still require human confirmation; no auto-grouping,
source-equivalence inference or deletion is allowed.
