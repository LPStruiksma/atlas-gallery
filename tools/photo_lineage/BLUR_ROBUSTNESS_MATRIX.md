# Pinned public-camera blur condition matrix

Lane B / issue #83. **Research-only, no source edits, threshold calibration,
photo-family confirmation or automatic grouping.** Investigates a specific
failure found in the preceding pinned rotation/crop study: a motion-blurred
clock image sometimes failed local-feature matching completely.

## Protocol

Run from the repository root with an **already installed** scikit-image
package containing the exact seven source image byte versions:

```bash
python -m tools.photo_lineage.blur_robustness_matrix --algorithm orb
python -m tools.photo_lineage.blur_robustness_matrix --algorithm sift
python -m tools.photo_lineage.blur_robustness_matrix --algorithm akaze
```

No internet/download fallback. The existing
`public_domain_smoke._read_pinned_photo` verifies each local
PNG against an exact hard-coded SHA-256 before decoding. The seven assets are
five individually documented CC0/public-domain scikit-image photographs
(astronaut, camera, chelsea, clock_motion and coffee) and two differently
positioned Middlebury Motorcycle stereo views. See
`PUBLIC_DOMAIN_SMOKE.md` and `MIDDLEBURY_STEREO_SMOKE.md`
for credit, respective usage terms and source. **No media bytes are
redistributed or committed.**

For every source, apply six intentionally fixed offline filters to grayscale
images: Gaussian sigma 1.5, 4.0 and 8.0, and a horizontal, centered,
normalized uniform motion kernel of length 7, 15 and 31 px. Use OpenCV
reflected borders. Compare the edited result back to **its own unchanged
original** with the existing \`matcher.compare_arrays\` and the explicitly
selected ORB/SIFT/AKAZE algorithm; 7 sources × 6 conditions = **42
within-source edits** per algorithm. Separately compare the Middlebury
left viewpoint to each blurred version of the **different-camera right
viewpoint**, six *correlated* same-scene hard negatives, not independent
same-camera shutters. Fixed 48 calls per algorithm.

Every report contains only finite public source labels, credits, per-condition
verdict counts, one stereo verdict, source counts and explicit unverified
flags. No user filenames/paths, pixels, descriptor vectors, camera EXIF,
source hashes, hidden ID or models are emitted or stored. Output is an
experimental manual-review *offer*, NEVER source-exposure equivalence.

## Host-side pinned-source observations, 20 October 2026

Independently ran all **144 actual matcher calls** against seven verified
package PNGs (exact SHA-256s in the repository), Linux Python 3.13.5,
OpenCV 4.13.0, NumPy 2.3.5, Pillow 12.3.0, scikit-image 0.26.0. Exact
installed \`matcher.py\` Git blob \`cacb77f8d51520d85b5dc8441b2777d91cdd3393\`
matched the integrated Lane B version. These are optional host-local
public-media runs, **not GitHub Actions tests**. Dedicated CI runs only fake,
synthetic source arrays and a mocked matcher.

| Blur condition | ORB: own-photo reviewable | SIFT | AKAZE | Stereo different viewpoint |
|---|---:|---:|---:|---|
| Gaussian σ=1.5 | 6/7 | 6/7 | 6/7 | all 3 return review |
| Gaussian σ=4 | 6/7 | 6/7 | 6/7 | all 3 return review |
| Gaussian σ=8 | 0/7 | 4/7 | 3/7 | AKAZE review, ORB/SIFT unknown |
| Motion length 7 | 7/7 | 6/7 | 6/7 | all 3 return review |
| Motion length 15 | 6/7 | 6/7 | 6/7 | ORB/AKAZE review, SIFT unknown |
| Motion length 31 | 2/7 | 4/7 | 2/7 | all 3 unknown |

"Reviewable" is the sum of \`candidate\` + \`review\`, and is **not
verified recall**. The already-blurred clock source was \`unknown\` for
every SIFT/AKAZE condition and all ORB conditions except motion-length-7
(`review`). Low-texture/motion blur is a concrete diagnosis,
not a reason to let a low-distance pHash or weak geometry confirm exposures.
A stereo \`review\` is a warning: two separate viewpoints of one static
scene can still share enough detail to prompt human comparison.

## Limitations and next study

At most **six independently distinct scene groups** occur here, not 42 or
144 independent examples. All edits reuse the same source pixels and each
stereo hard negative reuses one different-viewpoint pair; the stated
reviewable fractions have **no population CI**. Gaussian and horizontal
convolution aren't a model of all physical motion blur, digital sharpening,
denoising, camera ISP or modern phone night mode. No separately timed
same-camera shutters, real-world false-source rate, retrieval recall@K, EXIF,
location, face/identity linkage, or S24+ latency/RAM/thermals are measured.

A future **rights-cleared and independently curated same-camera shutter
cohort**, using source-revision SHA-256 pins and distinct scene groups, is
needed to quantify false-review risk. Consider alternative *review-only*
low-texture retrieval experiments only after evaluating hard negatives;
never promote pHash to source identity or relax strong candidates from
this exploratory matrix alone.
