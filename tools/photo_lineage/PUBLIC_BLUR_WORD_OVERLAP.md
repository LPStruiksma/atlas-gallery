# Photo DNA — pinned public camera blur / ORB retrieval preflight

Lane B / issue #83. **Diagnostic only; no SQLite index writes,
automatic exposure relationships, camera identifiers or production changes.**

## Why verification experiments alone are insufficient

The standard SQLite shortlist requires at least TWO shared ORB visual words
to return an indexed photo. If an edited query has zero or one words in
common with its original, that original cannot enter the shortlist even if
the geometric matcher could have verified it perfectly. This is a necessary
**eligibility** condition, NOT proof of successful rank@K, full-gallery
retrieval or independent camera exposures.

Use the exact source-byte SHA-256-pinned public photographs and blur
stimuli from `public_domain_smoke`, `middlebury_stereo_smoke`, and
`blur_robustness_matrix` (five individually licensed CC0/public-domain
scenes + two separate-camera-view Middlebury Motorcycle photographs).
No files are downloaded or written; the study reuses the unchanged ORB
`words_for_image` under the pinned fingerprint
`orb-96-4x24-v1-800px`. Any fingerprint version drift, missing bytes,
unexpected raw visual word, equal stereo views or changed source registry
fails closed. No source paths, pixels, source hashes or descriptor bytes
are published.

```bash
python -m tools.photo_lineage.public_blur_word_overlap
```

Exactly **42 same-source blur edits** (7 sources × 6 conditions) and
**six correlated different-camera stereo viewpoint contrasts**.
These are not independently captured camera shutter negatives.

## Independent public-source observations (21 Oct 2026)

On Linux Python 3.13.5, OpenCV 4.13.0 and NumPy 2.3.5, with all seven
scikit-image 0.26.0 PNG source SHA-256 pins validated, the unchanged
96-keypoint / four-band 24-bit ORB visual-word algorithm was applied
to the pinned sources and six deterministic blur conditions:

| Condition | Same-source pairs sharing ≥2 words | Distinct-view stereo shared words |
|---|---:|---:|
| Gaussian σ=1.5 | 7/7 | 0 |
| Gaussian σ=4 | 7/7 | 0 |
| Gaussian σ=8 | **1/7** | 0 |
| Horizontal motion 7 px | 7/7 | 2 |
| Horizontal motion 15 px | 7/7 | 1 |
| Horizontal motion 31 px | **3/7** | 1 |

These are locally reproduced public-byte observations with an equivalent
standalone extraction implementation, **not** exact PR-head GitHub CI
execution of real photographs. CI uses only fake source arrays and
mocked extractors; it does not read real public data. The source
collection represents **at most six distinct scene groups**, reused
across the 42 correlated edits. The stereo contrast is another camera
viewpoint of the same scene, not verified independent shots at different
times from one camera. No population recall/confidence bound follows.

## Interpretation and next research

**The actual pre-verification retrieval bottleneck is more severe than
it may appear from geometric verification alone.** In the severe
Gaussian condition, six known source photographs cannot satisfy the
basic two-word SQLite filter, regardless of geometric matcher quality.
This makes a low-texture/blur-sensitive *review-only retrieval* strategy
worth investigating—but not a pHash equivalence shortcut. Prior
stereo-scene pHash collisions show that visually similar photographs
may be different physical exposures.

Unknown and absent shortlist results remain uncertainty, **not** evidence
that an original has no relatives. A full heldout test must run the
actual SQLite index with source/scene-separated real camera photos,
crowded visual-word postings, rank@K, candidate budget exhaustion and
multiple independently verified same-camera shutter negatives.
Neither this experiment nor the prior blur verifier warrants
automatic grouping, deleting, false-positive claims or native S24+
performance extrapolation.
