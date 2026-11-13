# Public camera blur: actual disposable SQLite shortlist rank benchmark

Lane B / #83, optional research only. This advances the preceding
`public_blur_word_overlap` study beyond the necessary two-shared-word
condition to **actual existing SQLite shortlist ranking**. The default
study uses the same seven SHA-256 pinned public photographs (five CC0/public
domain examples plus a separately documented Middlebury Motorcycle stereo
pair) and up to 64 deterministic **fictional** textured distractor images.
No private photo, phone, People data, cloud or native Android integration.

```bash
python -m tools.photo_lineage.public_blur_sqlite_rank --distractors 32
```

First verify the exact public source-byte hashes via the repository's
existing `_read_pinned_photo` allowlists. Create a **new temporary directory**
outside source roots, place re-encoded grayscale **copies** of those seven
public samples and deterministic 256px synthetic distractors inside a
temporary corpus, build a brand-new disposable SQLite index using unchanged
`index_folder`, then query each of the 42 blurred derivative stimuli
(seven sources × six conditions) against the **unchanged** existing
`shortlist` with `top_k=20`, posting budget 50,000 and distinct-document
budget 1,000. Report ranks for the known source (public label only),
condition-wise counts rank1/rank≤5/rank≤20, valid two-word precondition
and whether the *different-camera viewpoint* left stereo appears during
queries of the right stereo image. Source originals are never opened
for writing; all temporary JPEG-free PNG derivatives, the SQL cache and
queries are removed automatically when the disposable context exits.
No source files, pixel dumps, SQLite paths, hashes, or non-public IDs
appear in output; a budget failure or an incomplete index ABORTS rather
than implying missing relatives.

### Evidence and interpretation boundary

Synthetic regression tests cover **both negative and positive retrieval paths**:
seven fictional constant grayscale arrays should produce empty indexes
and no false relatives; seven uniquely textured fictional arrays tested
against their exact synthetic copies must each rank first with eight
fictional distractors in the **real temporary SQLite index and shortlist**.
An additional mocked ranking control checks that an eligible source
can rank below first place or even fall outside the shortlist. Other tests
validate input limits, failure to load, duplicates and output privacy.

The independent host-side real public-camera observations below demonstrate
the operational protocol but are **not CI test executions of public imagery**.
The local experiment faithfully recreated the repository's ORB word
extraction, Pillow resize, SQLite index and shortlist scoring methods, but
did not import the exact byte-identical GitHub module into that host environment.
Treat the numbers as preliminary independent reproduction, not exact-commit
production results. The seven original PNG bytes were checked against
their versioned SHA-256 pins.

Even if full shortlisted rank appears excellent on seven sources and
synthetic distractors, this is NOT representative 150,000-image
performance or independent-shutter source-equivalence validation.
Five public scenes plus one stereo scene provide **at most six**
distinct scene groups, repeated across six correlated blur conditions.
Stereo images differ by physical viewpoint, not verified separately
timed exposures of one camera. Actual different-shutter NIND/HDR+
negatives, independent curated scene diversity, cache staleness and
posting-budget adversaries, Android p95/memory/power, and precision
for manual review all remain separate gates. No automatic family
grouping/merging/deletion, no threshold relaxation and no pHash promotion.

### Independent real-public-image host observations — 21 Oct 2026

Environment: local Linux Python 3.13.5, scikit-image 0.26.0 bundled public
PNGs, OpenCV 4.13.0, NumPy 2.3.5 and Pillow 12.3.0. The two historical
test controls were compared: an initial run with *noncanonical uniform-noise*
distractors made every eligible original rank first; this proved overly
optimistic. Results below use a faithful local implementation of the
repository's **canonical `benchmark.textured(seed=2000+i, size=256)`**
generator: 3×3 Gaussian-filtered noise, deterministic circles, lines,
rectangles and labels. The corpus contains seven individually SHA-256-pinned
public photos plus 0/8/32/64 synthetic generated distractors. The
disposable SQLite rank logic was reproduced locally; exact imported
GitHub module execution, however, is not independently attested.

| Blur condition | 0 distractors: rank 1 / top 5 | 8 distractors | 32 distractors | 64 distractors |
|---|---:|---:|---:|---:|
| Gaussian sigma 1.5 | 7/7 · 7/7 | 7/7 · 7/7 | 7/7 · 7/7 | 7/7 · 7/7 |
| Gaussian sigma 4.0 | 7/7 · 7/7 | 7/7 · 7/7 | **6/7** · 7/7 | **6/7** · 7/7 |
| Gaussian sigma 8.0 | 1/7 · 1/7 | 1/7 · 1/7 | **0/7** · 1/7 | **0/7** · 1/7 |
| Motion blur 7 px | 7/7 · 7/7 | 7/7 · 7/7 | 7/7 · 7/7 | 7/7 · 7/7 |
| Motion blur 15 px | 7/7 · 7/7 | 7/7 · 7/7 | 7/7 · 7/7 | 7/7 · 7/7 |
| Motion blur 31 px | 3/7 · 3/7 | 3/7 · 3/7 | **2/7** · 3/7 | **2/7** · 3/7 |

All known originals with at least two shared ORB words appeared within the
first five hits in this small sample, including Gaussian sigma 8's sole
eligible source and motion blur 31's three eligible sources. With 32 or 64
distractors, ranking also became nontrivial: for Gaussian sigma 4, the right
stereo source ranked **second**; for Gaussian sigma 8, the camera source
ranked **second**; and for motion length 31, coffee ranked **third**.
A separate-viewpoint left Motorcycle frame appeared at **rank 2** during
a blurred-right query in the 7px motion condition across all distractor
counts. This is a **same-scene different-camera-view caution**, not evidence
of common camera exposure.

The crucial bottleneck remains **pre-ranking visual-word loss**: Gaussian
sigma8 has only 1/7 sources with the necessary >=2 shared words, and motion
length31 has 3/7. The report cannot establish the performance of a
150k-image gallery, that 32/64 synthetic distractors represent natural
libraries, a population confidence interval or true independently captured
same-camera shutter false-positive rate. Never treat `candidate`,
`review`, or rank@1 as an automatic merge or deletion authorization.
