# Adversarial photo-lineage evidence — 2026-10-16

## Why this benchmark exists

**Sharing recognizable visual content is not equivalent to having the same source exposure.** ATLAS must preserve this distinction when a user wants to identify an enhanced photo, a crop of it, or a recolored variant without accidentally conflating neighboring burst shots, people moving against a static background, or multiple shots of architecture.

OpenCV keypoint matching with RANSAC and 8×8 local gradient consistency tests whether large areas align well. It **does not demonstrate** that two images originated from the same shutter exposure. A matcher returning \`candidate\` means only “potential related content worth reviewing,” never “safe duplicate,” and not “safe to remove.”

The earlier Lane B synthetic benchmark included entirely different random scenes and shared captions. It did not adequately stress *different captures of the same nearly static scene*. This module adds a deliberately challenging, repeatable family of **synthetic scene-sibling proxies**, where most of the scene is held constant but a central patch changes. These are generated composites, **not actual camera burst exposures**. Real photography additionally changes illumination, perspective, sensor noise, subject motion and compression, so these measurements cannot establish real-world rates.

## Exact reproduction

From repository root with \`tools/photo_lineage/requirements.txt\` installed in an isolated virtualenv:

\`\`\`bash
python -m unittest discover -s tools/photo_lineage/tests -v
python -m tools.photo_lineage.benchmark_adversarial --seeds 4 --algorithm orb
python -m tools.photo_lineage.benchmark_adversarial --seeds 4 --algorithm sift
python -m tools.photo_lineage.benchmark_adversarial --seeds 4 --algorithm akaze
# Optional, verbose explanation of each generated pair:
python -m tools.photo_lineage.benchmark_adversarial --seeds 4 --algorithm orb --include-cases
\`\`\`

No image files, remote APIs, private photographs or model downloads are involved. Every image is created in memory using seeded NumPy/OpenCV operations. A local benchmark prints JSON to stdout; stdout can be redirected into a user-selected *new* report, but the benchmark itself does not write any file.

The 12 deterministic test conditions per seed include seven edited-copy positives (resizing, gamma, low-quality JPEG, 25% central crop, arbitrary 41° rotation plus crop, sharpening, date overlay) and five negative proxies (completely different scene, different scene with identical caption, same background with a small/medium/large replacement patch). **The labels refer to synthetic construction**, not verified photographic ground truth.

## Results from four held-out-by-construction synthetic seeds

**Local CPU environment:** Python 3.13, OpenCV 4.13.0, NumPy 2.3.5, Pillow 12.3.0; these are NOT Samsung S24+ timings. The three algorithms each produced the same **categorical** results on this tiny synthetic corpus.

| Test condition | Same-exposure assumption | SIFT strong candidate | ORB strong candidate | AKAZE strong candidate |
|---|---|---:|---:|---:|
| Seven edited variants × four seeds | Same generated source | 28/28 | 28/28 | 28/28 |
| Four independent scenes | Not related | 0/4 | 0/4 | 0/4 |
| Four different scenes with same caption | Not related | 0/4 | 0/4 | 0/4 |
| Four same-background, small changed foregrounds | Distinct-exposure proxy | 4/4 | 4/4 | 4/4 |
| Four same-background, medium changed foregrounds | Distinct-exposure proxy | 4/4 | 4/4 | 4/4 |
| Four same-background, large changed foregrounds | Distinct-exposure proxy | 0/4 (review) | 0/4 (review) | 0/4 (review) |

Consequently **8/20 negative proxies received the strongest candidate verdict** for each algorithm. This is not a population false-positive rate; it is a deliberately adversarial test mix with shared synthetic backgrounds. In particular, synthetic medium foreground changes occupied a square of 160×160 pixels in a 600×600 image and still passed the existing 88% local-tile consistency gate. Smaller edited overlays were correctly retained as strong positives, which explains why a naive stricter global threshold would introduce competing errors.

The new four regression tests are instrumentation/report-contract tests: input bounds, condition labeling, candidate/review/unknown accounting, and reproducibility of evidence excluding wall-clock measurements. **We intentionally do not assert zero false positives on the adversarial group**, which would mean hiding the actual failure or optimizing for these few samples.

## Safe implications for the actual product

1. **Candidate edges are not certified identical exposures.** Never use strong-candidate edges as an instruction to replace/delete files, consolidate photo ownership or automatically merge chains of A~B and B~C.
2. User-facing Photo DNA should offer distinct review reasons: *matching visual content*, *likely transformed source*, and *same scene/possible burst sibling*. These are design intentions, **not calibrated model classifications yet**.
3. Before promoting any matcher threshold, benchmark **genuinely independent** scene/burst exposures alongside transformation positives, with holdout split by original photograph and physical scene (not randomly by variant), explicit unknown abstention, top-K retrieval recall, and false-strong-candidate rate by condition.
4. Research object-local difference maps, registration residuals, illumination-robust structural agreement, and repeatability with different crop coverage. Apply masking cautiously: timestamps, watermark overlays and creative edits are legitimate same-exposure variants.
5. Preserve every original and restrict future integrations to review-only candidate relationships. There is no image deletion, cloud upload, native app change, or photo-library scan in this branch.

**CI dependency:** Current GitHub Actions do not provide an exact-head \`tools/photo_lineage/**\` Python gate. PRs remain unmerged under coordination issue #83 until the workflow-owning lane adds and validates it. A simple future CI command is \`python -m unittest discover -s tools/photo_lineage/tests -v\`; the adversarial benchmark should also run as an evidence-producing job, but must **not** fail merely because it reveals an unresolved safety limitation.


## Rejected exploratory shortcut: fixed gradient-difference thresholds

To look for an immediate low-cost remedy, we also compared registered-in-place (no geometric warp) synthetic images using Sobel gradient-magnitude residuals normalized by the 95th percentile. This was an ephemeral in-memory probe, **not** a production implementation or formal matcher feature. Across four seeds:

- A simulated **small moving foreground** produced 0.27–0.39% active difference pixels, with largest active component 269–464 pixels.
- A **legitimate date-stamp overlay** produced 0.97–1.00% active differences, with largest component 552–968 pixels.
- Maximum 64×64 change-pixel density also overlapped: small changed foreground 0.217–0.285, date stamp 0.269–0.287.
- JPEG and sharpening usually produced near-zero active gradient differences, whereas independent scenes produced roughly 16–18% differences.

**Decision: reject a simple difference-area, connected-component-size, or peak local-density threshold as a same-exposure certification gate.** At plausible threshold settings it can penalize valid edits more heavily than a changed subject. This probe has no affine registration, no realistic sensor change, and only four procedural seeds; it is hypothesis rejection, not an accuracy study. The matcher remains unchanged. Future work needs richer connected spatial structure and held-out independent exposures with an explicit abstention mechanism.


**Integration check:** The dedicated Photo DNA workflow must pass at the exact PR head before any merge. Synthetic regression success does not calibrate real independent-exposure precision.
