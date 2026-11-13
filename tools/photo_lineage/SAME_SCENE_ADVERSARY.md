# Photo DNA — synthetic same-scene independent-frame adversary

Lane B / issue #83. **Research-only; no actual camera or private photograph access, device use, cloud, deletions, identity assignments, or native app integration.**

## Why this is different from ordinary near-duplicate testing

An arbitrary rotated or recolored version of a single source is a *positive* same-source-exposure example by construction. Two distinct physical shutters can photograph the exact same wallpaper, building or still-life minutes apart and produce excellent geometric alignment, even though they are **not the same original photograph**. A candidate from geometric matching is an offer for human examination, never proof of same shutter. The earlier benchmark's different-seed negatives have very little overlapping content; those alone cannot expose this risk.

The new `same_scene_adversary.py` takes a deterministic, procedurally textured static background and renders **separate synthetic frame time steps**. Each step has its own deterministic noise/illumination and a moving foreground occluder. It then distinguishes **synthetic edits of each step** (resize, gamma, crop, 38° rotated crop and JPEG recompression) from **pairs of different steps sharing most static background**. The latter are intended difficult *same-source-exposure negatives* in the synthetic model, not actual independent shutter ground truth.

```bash
python -m tools.photo_lineage.same_scene_adversary --scenes 2 --frames-per-scene 3 --size 320 --algorithm orb
python -m unittest tools.photo_lineage.tests.test_same_scene_adversary -v
```

The command returns aggregate-only `candidate`, `review`, `unknown` verdict counts for each of five edits plus the existing *scene-unit* `scene_exposure_risk` summary across separately rendered frame pairs. It ensures the compared frames are pixel-nonidentical, reports when at least one pair of a synthetic scene gets a strong candidate, and deliberately retains `unsafe_to_certify_exposure_identity: true` regardless of outcome. Frames and evidence do not contain real names/paths, and no raw images are saved or exported. Pair counts within one scene are correlated and never counted as independent camera evidence or statistically calibrated confidence.

**Limits:** 1–4 scenes, 2–4 synthetic time steps/scene, 256–480 px, SIFT/ORB/AKAZE only. Default 2 × 3 gives 6 different-time-step pairs and 30 edit comparisons. It runs in memory and is intentionally small; runtime measurements include OpenCV synthetic processing on the host, not S24+. It does not emulate actual sensor/lens optics, 3D viewpoint shift, rolling shutter, reprocessing algorithms, clothing, year-spanning changes, or any verified public-camera ground truth. It provides **regression coverage for the existence of same-scene hard negatives**, not a field-calibrated false positive rate or justification to tune strong-candidate thresholds.

**Interpretation:** a strong candidate across two different simulated time steps demonstrates the *possibility* that stable common background can dominate local dynamic differences; it does not mean the system has identified an actual source-equivalence error in a real corpus. Even a zero-candidate synthetic result would not clear a matcher for deployment. Preserve all raw user photographs and treat every connection as individually reviewable.

## Paired classical-algorithm contrast (19 October 2026)

Run the **same deterministic fictional scene and time-step schedule** through the existing ORB, SIFT and AKAZE geometric verifiers:

```bash
python -m tools.photo_lineage.same_scene_adversary --algorithm all --scenes 1 --frames-per-scene 2 --size 256
```

The `--algorithm all` matrix adds a deliberately tightly bounded cross-algorithm comparison: 1–2 procedural scenes, 2–3 synthetic time steps per scene and 256–360-pixel arrays. It reports **per-edit reviewable vs unknown outcomes** across all five inherited edit types, and counts of **strong candidate offers across separate simulated time steps**, both as correlated raw pairs and affected scene units. Each algorithm sees identical deterministic scene/time-step generation. No individual match evidence, image pixels, filenames or paths are exported.

**This is not a winner table.** There is no auto-selection of the algorithm with the most positive offers or fewest strong scene negatives. A geometric candidate across separate simulated time steps is especially important to examine, because a stable backdrop can dominate despite real changes. Conversely, synthetic hard negatives being labelled `unknown` does not prove independence, calibrated precision or deployability. The entire set is procedurally constructed and orders of magnitude smaller than representative real-camera scene diversity.

Use the matrix only to spot *questions* for rights-cleared camera benchmarks (e.g., which edits cause abstention, which background geometries need review). Confirm any decisions on independently curated real captures, and measure Android CPU/RAM/thermal separately. Never change conservative matcher thresholds based on this synthetic matrix and never auto-merge, overwrite or delete originals.

## Motion-controlled tripod-like synthetic hard negatives (19 October 2026)

Previously every separate synthetic time step moved the prominent foreground object, which could make the synthetic same-scene cases easier than photographs of a still building, tripod setup or nearly stationary subject. The *research-only* generator now supports three deliberately artificial controls, all sharing identical procedural background and seeded time-step lighting/noise:

- `moving` — existing displaced foreground (unchanged default and regression behavior).
- `stationary` — same foreground geometry/pixel painting at every synthetic time step; **independent per-step random noise and lighting still differ**.
- `absent` — remove the foreground object, leaving a predominantly static textured scene with per-step illumination/noise.

Run just one condition, or compare all three on exactly the same deterministic scene seed and time-step schedule:

```bash
python -m tools.photo_lineage.same_scene_adversary --algorithm orb --scenes 1 --frames-per-scene 2 --size 256 --foreground-mode stationary
python -m tools.photo_lineage.same_scene_adversary --algorithm orb --scenes 1 --frames-per-scene 2 --size 256 --foreground-mode sweep
```

The `sweep` deliberately caps work to **1–2 scenes, 2–3 distinct in-memory frames per scene and 256–360 px**, and requires exactly one classical algorithm (ORB/SIFT/AKAZE), not a 3×3 Cartesian product. Output for each motion mode contains its *correlated* different-frame pair count, strong `candidate` suggestions, scenes with strong/reviewable suggestions, unknowns and each of five **derived edit** verdict distributions. No synthetic images or paths are returned, no candidate is a physical-exposure certificate, and no winning mode or threshold is chosen.

**Interpretation caution:** procedural redraw with independent noise is *independent-time-step-like*, not an independently documented camera shutter. Even a stationary synthetic subject is not proof that real separate shutters are indistinguishable from derivative edits. The three controls deliberately **reuse** background images and seed series, so their difference must be treated as a sensitivity experiment, never three independent calibration cohorts. A zero-risk synthetic cell cannot establish a safe real-camera false-link rate, and a strong synthetic candidate cannot justify automatic exposure merges. All decisions remain human review only.

## Finite aggregate matcher-gate diagnostics (19 October 2026)

The same-scene procedural study now retains a **non-identifying, closed set of seven diagnostic reason codes** for each edit condition and its different-frame comparison. These distinguish insufficient distinctive features, insufficient mutual descriptor correspondences, incoherent/weak geometry, evidence below the review gate, plausible review, and stronger candidates still requiring human review. Existing three-way `candidate` / `review` / `unknown` verdicts and thresholds are **unchanged**.

Each reason bin is an aggregate count for a bounded synthetic condition. There are **no image identifiers, individual pair rows, private file paths, image contents, matcher exception strings, probabilities, or new identity assignments**. The mapper fails closed on new or mismatched matcher reason text, forcing an explicit research update rather than silently hiding new abstention cases under an uninformative `other` bin. Code values are diagnostic labels, not trained confidence scores or a prediction of whether an actual exposure matches.

An example interpretation: `features_sparse` in a rotated crop suggests that the synthetic image lacked suitable keypoints, while `geometry_weak` suggests sufficient descriptor matches but inadequate geometric support. These are starting points for future independently licensed real-camera comparisons; they do not justify loosening thresholds or falsely calling a strong static-scene candidate the same original photograph. Comparisons across ORB/SIFT/AKAZE and moving/stationary/absent modes reuse procedural seeds and are **not independent statistical evidence**.
