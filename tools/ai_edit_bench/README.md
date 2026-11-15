# ATLAS AI Edit benchmark (AIE-9, issue #195)

An **offline-first, model-agnostic** benchmark harness. It separates **quality on successful non-degraded edits** from **refusals, degraded attempts, and errors**. Provider scores must never blend refused prompts into a low quality average. The benchmark is **not** production/provider evidence until real, licensed or synthetic inputs are evaluated with calibrated judges and human blinded ratings.

## Quick start (no spending or network)

From the repository root, Python 3.11+; no pip dependencies for the default path:

    python3 -m tools.ai_edit_bench --dry-run --out /tmp/atlas-ai-edit-bench
    python3 -m unittest discover -s tools/ai_edit_bench -p 'test_*.py'
    python3 -m pytest -q tools/ai_edit_bench   # optional if pytest is installed

Open the local /tmp/atlas-ai-edit-bench/review.html to judge blinded pairs. The CI additive regression invokes the same mock-only runner and stdlib-compatible pytest test cases; the new test lives in test/additive-regressions/ai-edit-bench-195.mjs. No workflows or protected files were edited.

The dry run creates:
- corpus_manifest.json: **156** deterministic cases: 12 for each of 12 editing categories (object add/remove/replace, background, lighting/time, style, repair/colourisation, portrait identity, text, spatial relations, multi-person, high-res detail), plus 12 lawful sensitive/unconventional cases.
- results.json: mock output records for two fictional providers, with intentionally simulated outcomes, latency, costs and scores.
- scorecards.json and report.md: per-provider quality plus separate **per-category** refusal/degradation/error counts and rates.
- review.html and pair-assets/: local provider-blind A/B comparisons. Results download in-browser as blind-votes.json.
- pairwise-key.json: **keep away from reviewers**. It maps opaque pair IDs to provider labels for later unblinding.
- mock-assets/: synthesized generated PNGs; only in the output folder, never committed.

Cases use deterministic synthetic:// URLs and SHA-256 of source/mask PNG bytes. Source and mask are generated and hash-verified **on demand**, not stored as bulk media in git. Difficulty-labelled hard negatives (nearby distractors, mask boundaries and fine geometry) and synthetic lawful unconventional examples exercise the reporting pipeline. **The geometric generated fixtures are NOT a photographic fidelity benchmark**: before a real provider comparison, replace or augment the seed with validated photoreal synthetic or appropriately licensed CC0/CC-BY photos and record URL, SHA-256, actual licence and attribution. Never upload the owner's gallery or real people's private images.

## Metric specification and denominators

| Metric | Source / rules |
|---|---|
| Instruction adherence (0..1) | Local VLM or CLIP-direction scorer via judge callback; use a calibrated, documented model and fixed prompt/version. Human blinded pairwise remains independent. No judge means N/A. |
| Face identity (0..1) | Detect faces in input and output, embed with a fixed face model; match by spatial correspondence and compare normalized face embeddings. Missing/ambiguous detections are N/A, not 1.0. No biometric vectors should enter reports. |
| Unchanged-region SSIM (0..1) | Pixels **outside** the case mask; align source/output first, fail or set N/A on incompatible geometries. |
| Unchanged-region LPIPS (0..1) | Perceptual difference **outside** the case mask, reported as lower-is-better and never conflated with SSIM. Requires an offline local model dependency. |
| Resolution and fine detail | Actual output width/height; fine-detail score from a local, calibrated judge (0..1), not pixel count alone. |
| Artifacts | Human 'yes'/'no' tagging per completed output; unknown stays N/A. |
| Latency | Adapter-measured elapsed milliseconds per attempt, including refusals/errors; report mean and sample size. Dry-run timings are simulated, **not measured**. |
| Cost per accepted | Sum of all attempt costs (including refused, degraded, errored/retried) divided by confirmed accepted edits. Unknown price anywhere makes cost N/A; zero accepted makes it N/A. |

Quality means include **completed and non-degraded** edits only; missing metric values are excluded from each specific metric denominator and show N/A for n=0. Accepted edits require explicit human or clearly marked mock acceptance, not just an automatic high judge score. Restriction rates use **all attempts in each provider/category**, with refusal, degraded and error statuses in distinct columns. There is no ATLAS-side censorship filter; provider refusal is an observed event and is recorded separately, including for lawful sensitive cases.

## Connecting an adapter / judge

There is no API client or credential handling in this repo. The only automatically selected mode is --dry-run. To import a **local** adapter with separately approved owner spend:

    python3 -m tools.ai_edit_bench --adapter my_package:edit \
      --judge my_local_judges:score --authorize-external \
      --out /tmp/atlas-real-benchmark

Owner approval of spend, device/media exposure and provider policy is **not** granted by the flag; do not run paid/cloud workloads without it. An adapter is a callable:

    edit(case, source_path, mask_path, output_dir) -> dict

It returns provider (str), outcome ('completed'|'refused'|'error'), degraded (bool), accepted (human confirmed bool), latency_ms, cost_usd, optional output_path (a path **within** output_dir), output_width and output_height. Use explicit null for unmeasured fields. Case has id, category, difficulty, sensitive, instruction, source and edit_mask (url, sha256, license). Each invocation processes only this one synthetic fixture/mask, not the gallery. The runner does not infer provider capabilities or silently fall back.

A local judge is an independent callable:

    score(case, record, source_path, mask_path) -> dict

It can return adherence, identity_similarity, unchanged_ssim, unchanged_lpips, detail_score and artifact_tag. Implement with a local VLM/CLIP, face detection + embedding, and image comparison restricted to the **inverted mask** (source/output alignment required). All floats are bounded to [0,1] and validated. Annotate provenance of judge/model, versions, thresholds and image alignment in the experiment log. A future licensed dataset and production judge plugin can be added as a separate reviewed unit; no hosted judge is called here.

An existing provider result batch can be scored without executing code from an adapter:

    python3 -m tools.ai_edit_bench --results /path/to/records.json \
      --out /tmp/atlas-offline-report

Records are a JSON array using the above adapter schema, with case_id added. Pairwise assets must be relative to the output directory. Duplicate provider+case, unknown case IDs, malformed scores and attempts with quality metrics on refusals fail closed.

## Blind human review

Do not send pairwise-key.json with the review HTML. A/B positions are deterministic per case and secret seed, opaque image filenames give no provider clue, and the local browser exports only pair ID + preference ('A', 'B', 'tie', 'both_bad'). Unblind after annotations:

    python3 -m tools.ai_edit_bench.review \
      --key /tmp/atlas-ai-edit-bench/pairwise-key.json \
      --votes /path/to/blind-votes.json

The decoder refuses unrecognized pair IDs/votes. Different reviewers, counterbalancing and adjudication should be used for a real ranking. Pairwise preferences are **not** silently substituted for judge adherence. Do not publish a filled unblinding key with proprietary provider or personal input assets.

## Caveats

Synthetic mocks do not establish real accuracy, latency, fidelity, pricing, or restrictions. The generated corpus/fixtures and mock scores only demonstrate **pipeline integrity**. No private data, external uploads, paid APIs, network fallback, hosted judge, new Android permissions or protected-path changes are included. See sample_report.md for the labelled dry-run example.
