# Fixed arbitrary-angle rotation/crop public-camera diagnostic

Lane B / issue #83. A research-only, reproducible *actual matcher* test for
the user-visible goal of recognizing arbitrary 2-D photo rotations, cropping,
resizing and edits **without conflating same-scene with same shutter source**.
Does not change thresholds, automatic family assignment or originals.

## Offline, source-pinned protocol

```bash
python -m tools.photo_lineage.pinned_rotation_matrix --algorithm orb
python -m tools.photo_lineage.pinned_rotation_matrix --algorithm sift
python -m tools.photo_lineage.pinned_rotation_matrix --algorithm akaze
```

The runner requires five *physically installed* (not downloaded) scikit-image
package PNGs: astronaut, camera, chelsea, clock_motion, coffee, **each
independently checked against the exact SHA-256 + per-image license provenance
registry in public_domain_smoke.py**. NASA/Lav Varshney/Stefan van der Walt/
Rachel Michetti credits are emitted. Refuse unavailable or altered bytes.
No image export, network request, mutable cache or private Gallery paths.

Each original is converted to uint8 grayscale and downscaled with area
averaging if its maximum side exceeds 520. Ten deterministic rotations
(0°,15°,45°,75°,90°,135°,180°,225°,270°,315°) are followed by center
75%-width/height cropping, affine interpolation and gray corner padding.
The 0° row is therefore **crop-only**, not exact identity. The total is
**50 correlated edit comparisons per algorithm**, not 50 independent source
photographs. Run the original unchanged matcher.compare_arrays with one
selected ORB/SIFT/AKAZE algorithm, produce per-source and per-angle finite
verdict counts, and fail closed on new/incompatible verdicts. Output is
allowlisted diagnostic counts and public data labels, not source paths,
pixels, descriptors, hashes, EXIF or real private media.

## Independently measured host diagnostic, 20 October 2026

Local Linux Python3.13.5, OpenCV4.13.0, NumPy2.3.5,
scikit-image0.26.0, Pillow12.3.0. All **five exact original PNG SHA-256s**
matched the repository pins; imported matcher.py matched exact Git blob
`ee2e4b5d365ca6667a4f340ff45d37e45a148f70`. The original
standalone experiment used equivalent interpolation/crop/resize settings,
before this PR's optional runner was added. Thus these are **equivalent
source-module local observations**, not a claim that exact PR-head GitHub
Actions ran the public media.

| Matcher | Candidate | Review | Unknown |
| --- | ---: | ---: | ---: |
| ORB | 41 | 9 | 0 |
| SIFT | 40 | 0 | 10 |
| AKAZE | 40 | 0 | 10 |

All 40 edits of the four normally textured photographs were `candidate`
across all three algorithms; the **motion-blurred clock** was `unknown`
for every angle under SIFT and AKAZE, and received one `candidate`
(0° crop-only) plus nine weaker `review` offers under ORB. These are
**model-dependent review opportunities**, not proof of source exposure.
This isolates a likely **blur/texture abstention** bottleneck without
suggesting threshold relaxation or a hash shortcut. The previous
pHash fallback ablation showed a different-camera stereo scene hash
collision, so even a low pHash distance cannot safely certify the source.

## Scientific boundaries and follow-up

Five curated originals are a microscopic, nonrepresentative sample;
the 50 affine edits are correlated. Rotations use a fixed crop centered
on the image with neutral-gray fill, not arbitrary perspective, mirrored
photos, aggressive occlusion, panorama projection, recoloring, or real
different-shutter camera negatives. The tests are not calibration, a
statistical interval, source identity evidence or an Android performance
result. Strong-candidate verdicts still demand human review.

Run a **rights-cleared, source-revision/hash-pinned same-camera multi-shutter
cohort** before claiming safe photo-family precision or fallback behavior;
split heldout by original source and independent scene, not edit count.
Prioritize a *separate* narrowly scoped blur/texture investigation and
closed non-source exposure negatives rather than weakening matcher gates.
No auto-grouping, deletion, face/People inference or phone access.
