# Photo DNA — first rights-cleared real-camera *same-scene* diagnostic

Lane B / issue #83. **One real stereo pair is not a representative heldout.** This is a narrow, **offline, read-only** test of a possible failure mode: two different camera viewpoints looking at the same scene may produce strong geometrical similarities without being versions of the same original photograph.

## Source, provenance and legal reuse

- Dataset: [Middlebury 2014 Stereo — Motorcycle](https://vision.middlebury.edu/stereo/data/scenes2014/). Middlebury's [dataset page](https://vision.middlebury.edu/stereo/data/) grants permission to use and publish its image/disparity datasets and requests citations of the corresponding 2014 research paper.
- Citation: D. Scharstein, H. Hirschmueller, Y. Kitajima, G. Krathwohl, N. Nesic, X. Wang, and P. Westling, *High-resolution stereo datasets with subpixel-accurate ground truth*, GCPR (2014).
- Packaging evidence: [skimage.data.stereo_motorcycle](https://scikit-image.org/docs/stable/api/skimage.data), which states that `motorcycle_left.png` and `motorcycle_right.png` are rectified, downsampled stereo viewpoints (factor 4; original physical camera baseline 193.001 mm in the downsampled calibration). They are *different camera views* of the same real motorcycle scene, **not identical source images**.
- **Actual fixed packaged bytes** in scikit-image 0.26.0: left `db18e9c4157617403c3537a6ba355dfeafe9a7eabb6b9b94cb33f6525dd49179`; right `5fc913ae870e42a4b662314bc904d1786bcad8e2f0b9b67dba5a229406357797`. Both are 741×500 RGB PNG images locally present in the research environment. The benchmark validates these hashes before decoding and refuses any different version.

Different stereo viewpoints correspond to different camera sensors/captures, so they are a useful **real same-scene nonidentical-source hard negative**. But stereo pairs have parallax and the images have been rectified/downsampled. They are *not a verified two-time-step sequence from one camera*, and do not represent bursts, exposure bracketing, generational edits, or separate photo filenames within a user gallery. Rights permission to use or publish is not a claim of CC0 or of no attribution requirements.

## Run locally, without fetching anything

```bash
python -m tools.photo_lineage.middlebury_stereo_smoke --algorithm orb
python -m tools.photo_lineage.middlebury_stereo_smoke --algorithm sift
python -m tools.photo_lineage.middlebury_stereo_smoke --algorithm akaze
```

Requires an **optional** compatible scikit-image installation with both assets physically bundled in its package directory. This code uses the existing `public_domain_smoke._read_pinned_photo` file integrity/read-only guard; it makes **no network calls**, never invokes scikit-image downloaders, never writes photos or media, and never scans the Gallery/Termux/People databases. No optional dependency has been added to Android or to `requirements.txt` for synthetic CI.

The report gives the **one** cross-view verdict (`candidate`, `review`, or `unknown`), expressly marks any strong geometric hit as **requiring human review**, and evaluates five derived in-memory edits from *each* viewpoint (10 correlated edit comparisons). Source bytes are pinned; exact edit protocol is shared with `heldout_eval.py`. Public attribution/source citations are included, but no image arrays, raw photo bytes or private paths are returned.

## Strict interpretation

Even if both stereo images geometrically align strongly, that only proves they share a scene; it does not prove a single identical source exposure. Even if this single negative is labelled `unknown`, that cannot certify a safe same-scene false-link rate. There is exactly **one scene**, **one different-viewpoint pair**, and **zero independently documented same-camera captures at different times**. No camera/photo population CI or recall percentage should be reported, and this is not an S24+ performance test.

The bigger heldout still needs *several independently curated scenes with two or more same-camera shutter captures per scene*, plus a mix of exposures, crops, rotations and edits with source identity verified by provenance rather than visual similarity. Keep the NIND/Wikimedia data candidate open, if and when image bytes are accessible; do not substitute multiple crops of one photograph for independent shutters.

CI tests exclusively use synthetic numpy arrays and mocked asset readers—no public image bytes committed, no new remote downloads, no explicit model selection, no automatic family/identity merges or deletion.

## Exact final-matcher evidence transparency (20 October 2026)

Once the rights-pinned pair is present locally, the smoke now reports a `same_scene_pair_matcher_evidence_numeric_only` object taken **directly from the existing unchanged `matcher.compare_arrays` result**, not from a second detector or an approximate implementation. It exposes an explicitly reviewed finite list of numeric diagnostics: keypoints in each view, reciprocal descriptor correspondences, RANSAC inliers/fraction, inlier spread in each view, aligned grayscale/gradient correlation, approximate overlap, local tile consistency, eligible tile count, geometric rotation and scale. Missing evidence stages remain zero/null as returned by the actual matcher; do **not** confuse zero at an early-abstention stage with a measured bad photometric score.

Fields are allowlisted individually and must be finite/nonnegative when applicable. The tool does **not** serialize the complete matcher Evidence object; original media paths, source content, visual-word descriptors, image hashes, uncapped debug logs or other future fields can never accidentally leak through this report. The numerical readout is explanatory only: an apparent large number of ORB/SIFT/AKAZE matches between the two **different camera viewpoints** cannot prove identical original exposure. The final `candidate`/`review`/`unknown` decision stays unchanged and always requires human review.

A separate local Linux-only diagnostic using the two byte-verified skimage 0.26.0 source files and the same descriptor/mutual-ratio/RANSAC settings observed: ORB 206 reciprocal matches, 109 affine inliers; SIFT 314 and 128; AKAZE 807 and 413. Environment Python 3.13.5, OpenCV 4.13.0, NumPy 2.3.5, Pillow 12.3.0. **These observed counts were measured in a separate feature-geometry diagnostic, not the complete `compare_arrays` implementation or full final verdicts.** The new allowlisted report permits future direct comparison against that full matcher on an approved optional installed-sample environment. There is still **one real scene / one stereo pair**; no representative false-link precision estimate, same-camera time-separated burst study or S24+ latency.

## Full geometric-classifier reproduction on pinned public bytes (20 Oct 2026)

A **local Python 3.13.5 reproduction transcribed from the current repository
`matcher.compare_arrays` source** was executed against the identical SHA-256
pinned stereo PNGs. The full matching logic (ORB/SIFT/AKAZE detection, mutual
ratio descriptors, affine RANSAC, spread, overlap, photometry and final verdict
gates) was transcribed, with an independent script reproducing the existing
five `heldout_eval._derived_edits`. This was **not** an invocation of the
repository's official Python module, so treat these observations as a
reproducible local diagnostic rather than official exact-module CI results.
Environment: Linux, OpenCV 4.13.0, NumPy 2.3.5, Pillow 12.3.0,
scikit-image 0.26.0. No network access, source modification or photo upload.

| Model | Cross-view verdict | Mutual matches | Inliers | Inlier fraction | Local tile consistency | Gray / gradient corr. |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| ORB | **review** | 206 | 109 | .5291 | .1250 | .6037 / .3410 |
| SIFT | **review** | 314 | 128 | .4076 | .2857 | .6448 / .3751 |
| AKAZE | **review** | 807 | 413 | .5118 | .2857 | .6558 / .3848 |

The **different-viewpoint same-scene** pair was offered for review in all
three algorithms; none crossed the stronger `candidate` gate, primarily
because spatially distributed local image agreement was too low. For **each**
algorithm, all five identical-source derived edits (resizing, gamma,
central crop, 38° rotation+crop and JPEG quality 40) of **both** base views
received `candidate`: 10/10 correlated source-edit pairs per algorithm,
30/30 comparisons in total. These are not independent samples; there are
only **two original views and one physical scene**. This is an explanatory
stress smoke, **not a recall estimate or production false-review guarantee**.
The cross-view result shows why `review` must never be treated as permission
to infer one source exposure.

The numeric-only diagnostic now rejects malformed but finite evidence
(negative or impossible feature counts, more inliers than matches, fractions
inconsistent with rounded counts, normalized quantities outside [0,1],
invalid correlations, more than 64 spatial tiles, and mismatched/bad affine
parameters). This validates **only the safety of exported explanations**;
matcher thresholds, source lineage decisions and the mandatory human-review
rule are unchanged. Synthetic regression tests do not require scikit-image.
