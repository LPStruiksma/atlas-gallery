# Pinned public camera-condition matrix — first executable actual-matcher evidence

Lane B / issue #83. Optional **offline**, fixed-size, rights-documented public photographic diagnostic. Requires compatible `scikit-image` package with its seven relevant PNGs **physically bundled in the package directory**. Never calls data downloaders, never writes photographs, hashes, derivatives or paths, never tests private galleries or identifies people, and does not make photo-family or deletion decisions.

## Reproduce without downloading camera data

```bash
python -m tools.photo_lineage.public_camera_matrix --algorithm orb
python -m tools.photo_lineage.public_camera_matrix --algorithm sift
python -m tools.photo_lineage.public_camera_matrix --algorithm akaze
```

The runner uses the **unchanged actual `matcher.compare_arrays`** and existing `heldout_eval._derived_edits`, `public_domain_smoke._read_pinned_photo` (SHA-256, non-symlink, format/pixel/byte limits), plus `middlebury_stereo_smoke.STEREO_SOURCES`. Source inputs are five separately documented public-domain/CC0 package images (astronaut, camera, chelsea, clock_motion, coffee) and the two distinct-camera views of Middlebury 2014 Motorcycle, whose use/publication requires attribution to Scharstein et al., GCPR 2014. Rights and exact source SHA-256 registry are in `PUBLIC_DOMAIN_SMOKE.md` and `MIDDLEBURY_STEREO_SMOKE.md`. No new image rights are implied; no image bytes are committed or downloaded.

For each of seven original images the diagnostic tests exactly five *derived same-original* edits: resize, gamma, center crop, 38° rotation+crop and JPEG quality 40. It separately tests the ten cross-scene pairs among the five individually selected public-domain photos, plus the **one different-camera-viewpoint same-scene stereo pair**. Unlike earlier per-edit-only summary reports, it provides **per-named-public-source edit verdicts** so low-texture failure is not obscured. Source labels here refer to the public packaged dataset, never user images. The output has allowlisted aggregate values and public source labels only; no paths, pixels, descriptors, source-byte hashes or EXIF.

## Actual measured local run, 20 October 2026

Host environment: Linux Python 3.13.5, OpenCV 4.13.0, NumPy 2.3.5, Pillow 12.3.0, scikit-image 0.26.0. All seven PNG source bytes were successfully SHA-256 checked against the repository's immutable public registries. The installed repository `matcher.py` was recreated byte-for-byte from GitHub main and independently verified with **Git blob SHA `ee2e4b5d365ca6667a4f340ff45d37e45a148f70`**. The runner used the **actual unchanged matcher module**, with transformations copied from `heldout_eval._derived_edits` (not an official full GitHub checkout or exact official runner). The new `public_camera_matrix` module was independently executed against these local sample bytes for ORB and AKAZE; the same full-matcher standalone run supplied SIFT below. The new CI covers synthetic/mocked data and the dedicated synthetic safety gate, *not* optional scikit-image camera assets.

| Algorithm | Source-derived edits candidate / review / unknown | Ten unrelated-image pairs | Distinct-view stereo pair |
|---|---|---|---|
| ORB | 29 / 4 / 2 | all 10 unknown | review (206 reciprocal matches, 109 affine inliers) |
| SIFT | 29 / 1 / 5 | all 10 unknown | review (314 / 128) |
| AKAZE | 28 / 2 / 5 | all 10 unknown | review (807 / 413) |

**Critical condition-specific failure:** the heavily motion-blurred public `clock_motion.png` source is **not a strong candidate for ANY of its five same-source derived edits** with ORB, SIFT, or AKAZE. SIFT/AKAZE abstain `unknown` on all five; ORB returns `review` on resized, cropped and rotated-crop, `unknown` on gamma/JPEG. `camera.png` JPEG quality 40 is only `review` across all three algorithms; AKAZE also returns `review` on that camera source's gamma edit. The two stereo views each retain five `candidate` derived edits with all three algorithms. Weak candidate/review/unknown outcomes should never be recast as exposure-identity classification.

This observation makes a **low-texture/motion-blur sensitivity and per-source failure** visible that a single averaged synthetic recall number had concealed. **Do not relax matching thresholds based on these seven examples.** A better later approach may include a separate narrowly reviewed fallback for very low-texture images, but only if it does not elevate repeated scenes or independently captured shutters into high-confidence same-original offers.

## Nonnegotiable statistical limitations

There are exactly seven original images, **six distinguishable scene labels**, five correlated edits per source, ten descriptively unrelated public-image pairs, and **one stereo pair**. The stereo pair is not a verified same-camera time-separated shutter pair; even the 35 edits are not 35 independent exposure samples. Accordingly the measured counts above are **diagnostic enumerations, NOT source-family precision/recall, Wilson confidence intervals, real-gallery false-link rates, an S24+ benchmark or production cutoffs**. There are no licensed NIND separate-shutter sample bytes here. No photo originals were rewritten, uploaded or deleted. Existing human-review-only policy and matcher thresholds remain unchanged.

Next genuine blockers: legal individual-file provenance and pinned acquisition of several independently curated **same-camera different-shutter** NIND scenes; run unchanged full matcher, condition-specific failures and scene-separated uncertainty; assess retrieval false omissions and any candidate fallback independently. On-device performance must be tested separately with synthetic-only opt-in artifacts.
