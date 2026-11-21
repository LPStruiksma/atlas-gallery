# Identity bench: results and first engine choice

Unit [#469](https://github.com/LPStruiksma/atlas-gallery/issues/469) (IDS-2), goal [#467](https://github.com/LPStruiksma/atlas-gallery/issues/467). Tool: [`tools/identity_bench`](../../tools/identity_bench/README.md). Fixtures and their licences: [`FIXTURES.md`](../../tools/identity_bench/FIXTURES.md). Model licences: [LICENCES.md](LICENCES.md). Run of 2026-11-18, `identity-bench/1`, on a 4-core x86 cloud host.

## Short version

- The historical study measured five face-swap networks end to end (detect, align, swap, mask, colour match, paste back, optional restoration) on 70 fixed pairs of synthetic faces, and measures identity, damage outside the edit, seams, sharpness, stability and cost.
- **No pipeline passed every threshold that was written down before the run.** Four of five pass the identity, rank-1, retention and untouched-pixel thresholds. All fail the seam threshold, which turned out to be badly calibrated (see [A5](#a5-the-seam-threshold-failed-for-everything)). SimSwap also fails rank-1 and retention.
- **The independent judge and the eye disagree in places.** By the numbers the swaps are "the reference person". Looking at them, the inner face changes but head shape, hairline, age, skin texture and facial hair stay the original person's, and the result is softer than the photo around it. This is a face-region proof of concept. It is not Retake-class and this document makes no such claim.
- **First engine to port in IDS-9 (#475): GHOST 1 at 256 px**, because it is the only network that passes the identity thresholds and has a permissive licence. It is also the fastest. It is not the best looking: it has the most visible artefacts of the four that pass. InSwapper and HyperSwap cannot ship under their current terms; the AlphaFace [historical: measured without prerequisite author permission; do not re-run] data is retained solely as non-repeatable history, not as a quality endorsement. Details and conditions in [First engine choice](#first-engine-choice-for-ids-9). **Update:** [#505](https://github.com/LPStruiksma/atlas-gallery/issues/505) found that GHOST's identity backbone is InsightFace's own research-only checkpoint ([GHOST_BACKBONE.md](GHOST_BACKBONE.md), in review). Condition 1 below therefore fails: as published, no network measured here can ship, and the engine choice becomes a licence decision for Lukas.
- The mask matters as much as the network. The only mask that may ship (MediaPipe) silently fails on dark, rendered and profile faces as used here, and needs a guard before IDS-9 relies on it.

## What was run

Pipeline, per pair: shipped YuNet detector, five-point alignment, swap network, mask, Lab colour match, paste back at source resolution, optional GFPGAN 1.4 restoration at blend 0.8. The identity fed to every network is the mean embedding of all reference photos.

Fixtures: 8 photoreal synthetic identities with 4 reference photos each, 4 rendered identities with 8 references each, 29 targets (frontal, scene, expression, glasses, beard, headwear, low light, small face at about 110 and 60 px, occlusion, two people, rendered profile), 24 look-alike negatives. 70 pairs: 58 with photoreal identities (the headline) and 12 with rendered identities (reported apart). The selection rules looked only at untouched fixtures.

Order of work, which matters for how much to trust the numbers:

1. Thresholds were committed in `metrics.THRESHOLDS` (commit `607f0ac`) before any final-run swap existed.
2. Nine networks were screened on a separate development split (12 pairs, identities and targets that are not in the benchmark). The best of each family went on: InSwapper 128 (0.753), AlphaFace [historical: measured without prerequisite author permission; do not re-run] 256 (0.722), GHOST 1 (0.700; GHOST 2 0.693, GHOST 3 0.691), HyperSwap 1a (0.699; 1c 0.697, 1b 0.676). SimSwap 256 (0.583) was kept although it screened worst, because its licence is the clearest of the non-commercial ones.
3. The final run: 20 pipelines on 70 pairs. A first attempt was stopped part-way and discarded when the licence register (merged meanwhile) ruled out XSeg; XSeg was removed, the MediaPipe mask and SimSwap were added, thresholds were not touched.
4. Follow-up runs after seeing the results are marked **exploratory** below and decide nothing by themselves.

## Thresholds (written before the run)

| | Criterion | Threshold |
|---|---|---|
| A1 | Mean research-judge similarity to the reference set, and share of swaps over 0.40 | at least 0.50, and at least 0.80 |
| A2 | Rank-1: the judge picks the reference identity from a gallery of 37 (all identities, look-alikes, and the target's original person) | at least 0.90 |
| A3 | Retention: judge similarity as a fraction of what a genuine photo of that identity scores against the rest of its set | at least 0.75 |
| A4 | Pixels changed where the mask is 0 | exactly 0 |
| A5 | Seam gradient, as a fraction of the hard-edge control's | at most 0.25 |
| A6 | Mean judge similarity lost by adding restoration | at most 0.02 |

0.40 is the judge's "same person" line: of 34,980 stranger pairs in the fixture pool, 99 % score under 0.30 and the highest is 0.43.

The judge is `glintr100` (research-only, never shipped). It is a different network trained on different data from `arcface_w600k_r50`, the encoder the swap networks take their identity from. The in-loop encoder and the shipped SFace model are reported next to it and never decide a verdict.

## Results: identity (58 photoreal pairs)

| Pipeline | Judge mean | p10 | min | Over 0.40 | Rank-1 | Retention | Left of original | In-loop encoder | SFace mean | SFace rank-1 |
|---|---|---|---|---|---|---|---|---|---|---|
| InSwapper 128 | 0.753 | 0.594 | 0.531 | 1.00 | 1.00 | 0.92 | 0.244 | 0.736 | 0.616 | 0.81 |
| AlphaFace [historical: measured without prerequisite author permission; do not re-run] 256 | 0.722 | 0.592 | 0.474 | 1.00 | 1.00 | 0.88 | 0.244 | 0.713 | 0.654 | 0.91 |
| HyperSwap 1a 256 | 0.699 | 0.539 | 0.437 | 1.00 | 1.00 | 0.85 | 0.273 | 0.687 | 0.569 | 0.69 |
| GHOST 1 256 | 0.668 | 0.528 | 0.421 | 1.00 | 1.00 | 0.81 | 0.185 | 0.630 | 0.590 | 0.91 |
| SimSwap 256 | 0.552 | 0.422 | 0.315 | 0.91 | 0.88 | 0.67 | 0.337 | 0.510 | 0.523 | 0.60 |
| no swap (control) | 0.089 | -0.030 | -0.076 | 0.00 | 0.00 | 0.11 | 0.982 | 0.081 | 0.162 | 0.00 |

Paired differences on the same 58 pairs (95 % bootstrap interval): AlphaFace [historical: measured without prerequisite author permission; do not re-run] is 0.031 below InSwapper (0.021 to 0.041), HyperSwap 0.054 below (0.043 to 0.065), GHOST 0.085 below (0.062 to 0.108), SimSwap 0.202 below (0.185 to 0.219). These are preserved historical comparisons, not a current ranking or recommendation; whether differences are visible is a separate question.

**The shipped SFace model is a weak witness.** By SFace, only 60 to 91 % of swaps rank the reference person first, and its usual confusion is "still the original person". SFace sees more of the original than the judge does. The app can use SFace as a sanity check on a result, not as proof that a swap worked.

Rendered identities (12 pairs, references are 112 px renders): InSwapper 0.713, AlphaFace [historical: measured without prerequisite author permission; do not re-run] 0.683, GHOST 0.650, HyperSwap 0.632 (rank-1 0.92), SimSwap 0.509 (rank-1 0.67). Same order, a little lower.

By hard case, judge mean for InSwapper / AlphaFace [historical: measured without prerequisite author permission; do not re-run] / HyperSwap / GHOST / SimSwap:

| Case (pairs) | InSwapper | AlphaFace [historical: measured without prerequisite author permission; do not re-run] | HyperSwap | GHOST | SimSwap |
|---|---|---|---|---|---|
| frontal (6) | 0.79 | 0.79 | 0.74 | 0.70 | 0.60 |
| scene (8) | 0.72 | 0.67 | 0.66 | 0.66 | 0.54 |
| expression (8) | 0.80 | 0.76 | 0.74 | 0.69 | 0.56 |
| glasses (6) | 0.67 | 0.64 | 0.59 | 0.64 | 0.50 |
| beard (6) | 0.71 | 0.69 | 0.66 | 0.64 | 0.50 |
| headwear (2) | 0.84 | 0.77 | 0.76 | 0.74 | 0.62 |
| low light (6) | 0.79 | 0.75 | 0.76 | 0.73 | 0.58 |
| small face (4) | 0.80 | 0.78 | 0.72 | 0.69 | 0.59 |
| occlusion (4) | 0.76 | 0.67 | 0.67 | 0.60 | 0.53 |
| two people (4) | 0.82 | 0.80 | 0.78 | 0.69 | 0.63 |
| profile, rendered (4) | 0.64 | 0.69 | 0.65 | 0.56 | 0.47 |

Groups of 2 to 8 pairs show a direction, not a measurement. Glasses, beard and profile are the weakest cases for every network.

## Results: image (58 photoreal pairs)

| Pipeline | Changed px outside mask | Seam gradient | Seam step | Sharpness (median, 1 = source) | Landmark shift (eye distances) | Edit share of crop |
|---|---|---|---|---|---|---|
| InSwapper 128 | 0 | 0.34 | 2.98 | 0.03 | 0.059 | 0.161 |
| AlphaFace [historical: measured without prerequisite author permission; do not re-run] 256 | 0 | 0.52 | 3.42 | 0.20 | 0.055 | 0.156 |
| HyperSwap 1a 256 | 0 | 0.36 | 2.29 | 0.12 | 0.057 | 0.155 |
| GHOST 1 256 | 0 | 0.63 | 4.05 | 0.10 | 0.055 | 0.156 |
| SimSwap 256 | 0 | 0.46 | 1.87 | 0.12 | 0.057 | 0.153 |
| no swap (control, box mask) | 0 | 0.20 | 0.05 | 0.17 | 0.013 | 0.366 |
| hard edge (control, InSwapper, no feather, no colour match) | 0 | 0.59 | 2.07 | 0.03 | 0.063 | 0.287 |

- **A4 holds for all 1,400 outputs:** not one pixel differs from the source where the mask is 0 (PSNR infinite, SSIM 1.0000, max difference 0). On the two-people targets the second face is bit-identical.
- **Sharpness is the weak spot.** The fixtures are 1024 px photos with faces around 500 px across. A 128 px network returns 3 % of the source's fine detail inside the edit; 256 px networks return 10 to 20 %. Even the no-swap control, which only resamples the crop through 256 px and back, keeps 17 %. The swapped face is visibly softer than the hair and background around it on any large face.

### A5: the seam threshold failed for everything

A5 asked for a seam gradient at most a quarter of the hard-edge control's. The hard-edge control scored 0.59, so the line was 0.15. Every pipeline scores 0.31 to 0.63 and fails. So does the no-swap control at 0.20, which has no seam at all in the usual sense: it differs from the source only by resampling blur. The metric counts any gradient change in the feather band, softness included, and the hard-edge control was not much worse than a feathered paste by this metric.

The threshold stays as written and the verdict stays FAIL. The honest reading is that A5 as defined does not separate good seams from bad ones, so this run has **no trustworthy numeric seam verdict**. The seam step column (colour jump across the boundary) does respond to what it should: colour matching lowers it from 4.90 to 2.98 for InSwapper. A future run should define the seam criterion relative to the no-swap control and validate it on the two controls before looking at candidates.

### Restoration (A6)

| Pipeline | Judge change with GFPGAN (95 % interval) | A6 | Sharpness without, with | Extra time on host |
|---|---|---|---|---|
| InSwapper 128 | -0.038 (-0.045 to -0.032) | FAIL | 0.03, 0.18 | about 1 s |
| HyperSwap 1a | -0.026 (-0.032 to -0.021) | FAIL | 0.12, 0.18 | about 1 s |
| AlphaFace [historical: measured without prerequisite author permission; do not re-run] | -0.021 (-0.029 to -0.014) | FAIL (borderline) | 0.20, 0.15 | about 1 s |
| GHOST 1 | -0.010 (-0.016 to -0.003) | pass | 0.10, 0.15 | about 1 s |
| SimSwap | -0.003 (-0.010 to 0.003) | pass | 0.12, 0.19 | about 1 s |

Restoration buys sharpness and pays in identity: it pulls the face toward a generic one. It never changed a rank-1 result for InSwapper, AlphaFace [historical: measured without prerequisite author permission; do not re-run] or GHOST; HyperSwap lost one. GFPGAN's weights are 340 MB and their licence is unverified in the register, so it is a bench stage only.

### Mask and colour (InSwapper as the base)

| Variant | Judge mean | min | Rank-1 | Edit share | Seam step |
|---|---|---|---|---|---|
| face parser on the swapped crop (default) | 0.753 | 0.531 | 1.00 | 0.161 | 2.98 |
| face parser on both crops | 0.728 | 0.499 | 1.00 | 0.155 | 2.69 |
| MediaPipe multiclass segmenter | 0.721 | -0.080 | 0.93 | 0.184 | 2.74 |
| feathered box, no parsing | 0.883 | 0.775 | 1.00 | 0.363 | 2.84 |
| default without colour match | 0.756 | 0.526 | 1.00 | 0.161 | 4.90 |

Two findings.

**The mask moves the identity score more than the choice of network.** Pasting the whole box scores 0.883, which is above what a genuine photo of the person scores (retention 1.07), against 0.753 for the face region alone. The box replaces the forehead, jaw outline, ears and part of the hair as well, with whatever the network drew there. The judge reads that as more of the reference person. It is not a usable result: the box covers more than twice the area, and the network's low-resolution hair and background are pasted over the photo. It does show where the remaining identity sits: in the parts a face-region swap leaves alone. That is the argument for the full-head study (IDS-3, #470).

**The licence-clean mask fails silently on some faces.** With the MediaPipe mask, every network drops to about 0.90 rank-1. The failures are concentrated on five targets: rendered glasses (T25), rendered profile (T23, T24), and low light (T27 rendered, T16 derived). There the segmenter labels only 2 to 7 % of the crop as face skin, the edit is a small patch, and the result is still the original person (judge similarity around -0.08, the same as no swap). The code falls back to the box only when the mask is under 2 %, which is too low. On ordinary photoreal targets the MediaPipe mask is as good as the face parser (median 0.788 against 0.785). It has one advantage: on the occlusion target it leaves the pole alone, where the face-parser mask painted a ghost eye onto it.

## Looking at the results

Numbers from one model family are not a verdict, so the unit asks for blinded contact sheets. All 70 are in the private project files with a rating form; the key is kept apart. **Nobody has rated them yet.** What follows is the author's own unblinded look at 20 pairs, and counts as one opinion.

- InSwapper and HyperSwap looked most natural among candidates whose bench use is allowed; the historical AlphaFace [historical: measured without prerequisite author permission; do not re-run] observation is not an endorsement. They keep the target's gaze and expression. They also keep the target's skin texture and age, so a 30-year-old reference on a 70-year-old target reads as an older relative of the reference, not as the reference.
- GHOST 1 changes the face most, and has the most visible artefacts: blotchy or discoloured skin on several targets, purple lips on one, changed gaze and an opened mouth on the occlusion target, a smear on the cheek in profile.
- When the target has a beard or moustache and the reference has none, every network leaves a patchy remnant. When the target has glasses, frames survive but the eyes behind them are soft.
- Head shape, hairline, hair, ears and neck are always the target's. A person who knows the reference would often not accept the result as them.
- On the dark and sunglasses targets very little visibly changes.

## Stability

Every pipeline is deterministic: 12 of 12 reruns were byte-identical. With the landmarks jittered by 1 % of the eye distance (4 repeats), the judge score moves with a standard deviation of 0.006 to 0.007 for InSwapper, AlphaFace [historical: measured without prerequisite author permission; do not re-run], HyperSwap and SimSwap, and 0.015 for GHOST, whose worst pair ranged over 0.165. GHOST is the most sensitive to alignment.

## Cost on the host

Fresh process per pipeline, 4 threads, x86 cloud CPU, ONNX Runtime CPU. **These are not phone numbers**; they rank the networks and size the download.

| Pipeline | Load swap network | Swap | Mask (face parser) | Colour + paste | Restore | Total per swap | CPU time per swap | Peak memory | Weights it needs |
|---|---|---|---|---|---|---|---|---|---|
| GHOST 1 256 | 1.5 s | 175 ms | 132 ms | 26 ms | | 0.33 s | 1.3 s | 1.6 GB | 805 MB |
| SimSwap 256 | 0.4 s | 274 ms | 130 ms | 31 ms | | 0.44 s | 1.6 s | 1.1 GB | 510 MB |
| HyperSwap 1a 256 | 2.4 s | 435 ms | 168 ms | 30 ms | | 0.63 s | 2.1 s | 1.8 GB | 671 MB |
| InSwapper 128 | 3.2 s | 627 ms | 155 ms | 27 ms | | 0.81 s | 3.0 s | 1.5 GB | 823 MB |
| AlphaFace [historical: measured without prerequisite author permission; do not re-run] 256 | 2.2 s | 2,795 ms | 157 ms | 31 ms | | 2.99 s | 10.8 s | 1.8 GB | 824 MB |
| GHOST 1 256 + GFPGAN | 1.5 s + 0.6 s | 191 ms | 135 ms | 28 ms | 1,129 ms | 1.48 s | 5.6 s | 2.8 GB | 1,145 MB |
| InSwapper 128 + GFPGAN | 2.5 s + 0.6 s | 648 ms | 142 ms | 27 ms | 1,034 ms | 1.85 s | 7.0 s | 2.8 GB | 1,164 MB |

Alignment takes about 1 ms. "Weights it needs" counts the swap network, the identity encoder (174 MB), a converter where used (22 MB), the face parser (94 MB) and the restorer (340 MB). Peak memory is the whole Python process with the detector, encoder and a 1024 px photo loaded; it is an upper bound for the model set, not a phone figure. Detection and the reference embeddings are not in the per-swap time: they run once per photo and once per profile. With the MediaPipe mask (16 MB) in place of the face parser the weight set is 78 MB smaller.

Restoration costs more than any swap network except AlphaFace [historical: measured without prerequisite author permission; do not re-run] and adds about 1.2 GB of peak memory. CPU time over wall time is about 3.7, so the four cores are busy throughout; a phone running on fewer big cores will be several times slower. How much slower is for IDS-9 to measure.

## Exploratory: more pixels (after the results, decides nothing)

"Pixel boost" runs a network several times on interleaved sub-crops, so a 256 px network fills a 512 px crop (`+boost2`) or a 128 px one fills 512 px (`+boost4`). It was added after the final results showed how soft the swaps are, and was run on the same 70 pairs. It is reported for IDS-9 to try, not as a result that changes the recommendation.

| Pipeline | Judge mean | Rank-1 | Sharpness (median) | Seam gradient | Host s per swap |
|---|---|---|---|---|---|
| GHOST 1 256 | 0.668 | 1.00 | 0.10 | 0.63 | 0.37 |
| GHOST 1 256 + boost2 | 0.667 | 1.00 | 0.60 | 0.76 | 0.78 |
| HyperSwap 1a 256 | 0.699 | 1.00 | 0.12 | 0.36 | 0.52 |
| HyperSwap 1a 256 + boost2 | 0.699 | 1.00 | 0.41 | 0.41 | 1.41 |
| InSwapper 128 | 0.753 | 1.00 | 0.03 | 0.34 | 0.85 |
| InSwapper 128 + boost2 | 0.751 | 1.00 | 0.14 | 0.49 | 2.68 |
| InSwapper 128 + boost4 | 0.751 | 1.00 | 0.56 | 0.63 | 9.81 |

Identity does not move (boost4 minus boost2 for InSwapper: 0.000, interval -0.003 to 0.002), and A4 still holds. The sharpness number rises a lot, but looking at eye and mouth crops the visible gain is modest: slightly crisper lashes, brows and skin texture, plus some fine grain the network invents. Part of the metric's jump is that grain, which is also why the seam gradient rises. Unlike restoration, boost costs no identity, so it is the first thing to try against softness in IDS-9; for GHOST it roughly doubles swap time.

## Failures, in one place

- No pipeline passes all pre-registered thresholds; A5 fails for all and is itself miscalibrated.
- SimSwap fails A2 and A3: 7 of 58 swaps are still recognised as the original person (scene, glasses, beard targets).
- Restoration fails A6 for three of five networks.
- With the only shippable mask, about one swap in ten does nothing, without an error.
- Every result is softer than its surroundings on large faces. Pixel boost (exploratory) helps a little and costs no identity.
- GHOST 1, the proposed first engine, shows visible colour and texture artefacts and the highest sensitivity to alignment.
- Beards, glasses and profile are weak for every network.
- The contact sheets have not been rated by anyone blinded.

## What this does not show

- **Not phone performance.** Nothing ran on the S24+. Exynos 2400 has no public NPU path (RESEARCH.md), so IDS-9 starts on ONNX Runtime CPU/XNNPACK and must measure time and memory itself.
- **Not real photos.** All faces are synthetic. The photoreal set's "same person" photos were made with ArcFace-guided generators, so reference sets are tighter and friendlier to an ArcFace-family judge than a real People album. Real results will be lower; by how much is unknown.
- **One judge family.** The judge is independent of the swap networks' encoder but both are ArcFace-style. A human rating is the missing second opinion.
- **GHOST and SimSwap were not run with their own identity encoders.** The bench used FaceFusion's ONNX conversions, which take the `arcface_w600k_r50` embedding through a small converter network (CrossFace). A shipped GHOST would use its own backbone, and its numbers must be measured again. *Done in [GHOST_EXPORT.md](GHOST_EXPORT.md) (#506): exported from the official release with its own backbone, GHOST 1 scores 0.691 (+0.023), and its int8 variant matches FP32 at 130 MB.*
- **Licence correction (IDS-13).** CrossFace is bench only for the FaceFusion GHOST/SimSwap ports. AlphaFace [historical: measured without prerequisite author permission; do not re-run] was measured without the register prerequisite (author permission was never obtained); the original numbers remain as historical evidence, but the model must not be re-run, ranked or recommended. The bench now refuses its selection before any downloads.
- **No full head, no hair, no body.** That is IDS-3 and IDS-12.
- **Fixture licences are non-commercial.** If ATLAS ever became a commercial product, this choice would have to be re-derived on other fixtures.

## First engine choice for IDS-9

**Port GHOST 1 (256 px) first, behind the engine contract ([ENGINE_CONTRACT.md](ENGINE_CONTRACT.md)) so the network can be replaced without touching the rest.**

Why:

| | InSwapper 128 | HyperSwap 1a | GHOST 1 256 | SimSwap 256 |
|---|---|---|---|---|
| Passes A1 to A4 | yes | yes | yes | no (A2, A3) |
| Judge mean | 0.753 | 0.699 | 0.668 | 0.552 |
| Looks (author's view) | natural | natural | visible artefacts | natural, weak identity |
| Host time per swap | 0.85 s | 0.5 s | 0.37 s | 0.45 s |
| Weights | 555 MB | 403 MB | 515 MB | 220 MB |
| May ship (LICENCES.md) | no, research-only | no, research-only | Apache-2.0, backbone origin unconfirmed | private non-commercial only |

**Historical datum excluded from engine choices:** AlphaFace [historical: measured without prerequisite author permission; do not re-run] passed A1–A4, mean 0.722, author's view “natural”, 2.8 s per swap, 556 MB and no licence on weights. These values are not an endorsement and must not be re-run.

GHOST 1 is the only row with "yes" in the first line and a path to "yes" in the last. That is the whole case for it. It is the weakest of the four that pass and the least pleasant to look at.

Conditions and trade-offs IDS-9 inherits:

1. **Backbone provenance.** GHOST's identity backbone (`backbone.pth`) has no stated origin. Until ai-forever confirms it, GHOST 1 is not cleared to ship either. If it turns out to be an InsightFace checkpoint, no open network measured here can ship and the question goes to Lukas as a licence decision. *Resolved by #505: it is InsightFace's `ms1mv3_arcface_r100_fp16`, so this condition fails.*
2. **Size.** About 515 MB plus an identity encoder, far above the 60 MB bundle line. This needs the owner's download decision that the goal already expects.
3. **Re-measure.** The 0.668 was measured through FaceFusion's conversion and converter. IDS-9 must rerun `python3 -m tools.identity_bench one` and the full bench on its exported model before quoting any number.
4. **Mask.** Use the MediaPipe multiclass segmenter (the only shippable one) with a guard: when it covers much less than the area the five landmarks predict, fall back to a landmark oval and say so in the provenance. The guard is not built or measured yet.
5. **No restoration by default.** It costs identity, a second, and 340 MB with an unverified licence. For GHOST it stays inside A6, so it can be an optional step later if a clean restorer is found.
6. **Colour match on.** It costs nothing measurable in identity and removes much of the colour jump.
7. **Keep SFace as a check, not a gate.** A result whose SFace similarity to the profile is lower than to the original person is worth flagging to the user as a weak swap.

If Lukas obtains a licence for InSwapper or HyperSwap, either replaces GHOST on quality: they are ahead by the judge and clearly ahead to the eye. HyperSwap 1a is the better phone candidate of the two (256 px output, 403 MB, 0.5 s on host).

## Reproduce

```bash
python3 -m unittest tools/identity_bench/test_identity_bench.py     # 45 tests, no downloads
python3 -m tools.identity_bench one --target T04 --identity 0213ccb4776a --candidate ghost_1_256
python3 -m tools.identity_bench explore --candidates inswapper_128,ghost_1_256,simswap_256  # allowed examples only; new measurements need a separate decision
```

Every output image has a `.provenance.json` beside it (source and reference files by hash, engine, weights by hash, mask, colour and restoration settings). Outputs, sheets and weights stay in the cache and the private project files; none are in this repository.
