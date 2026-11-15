# ATLAS AI Edit benchmark

**SIMULATED — NOT PROVIDER EVIDENCE**

Refusals, degraded generations and errors are separate from quality; only completed, non-degraded outputs enter quality means. Missing metrics stay N/A. Cost/accepted includes all priced attempts.

## Quality scorecard

| Provider | Clean / tried | Adherence (n) | Identity (n) | Outside SSIM (n) | LPIPS ↓ (n) | Detail (n) | Artifact rate (n) | Resolution W×H | Latency ms | $/accepted |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| mock-alpha | 128/156 | 0.870 (128) | 0.930 (28) | 0.970 (128) | 0.030 (128) | 0.800 (128) | 0.000 (128) | 128×96 | 25.5 | 0.0000 |
| mock-beta | 128/156 | 0.870 (128) | 0.930 (28) | 0.970 (128) | 0.030 (128) | 0.800 (128) | 0.000 (128) | 128×96 | 25.5 | 0.0000 |

## Restriction scorecard (not quality)

| Provider | Category | Attempts | Refused | Refusal % | Degraded | Degradation % | Errors |
|---|---|---:|---:|---:|---:|---:|---:|
| mock-alpha | background_change | 12 | 1 | 8.3% | 1 | 8.3% | 0 |
| mock-alpha | high_resolution_detail | 12 | 1 | 8.3% | 1 | 8.3% | 0 |
| mock-alpha | lawful_sensitive | 12 | 3 | 25.0% | 1 | 8.3% | 0 |
| mock-alpha | lighting_time | 12 | 1 | 8.3% | 1 | 8.3% | 0 |
| mock-alpha | multi_person | 12 | 1 | 8.3% | 1 | 8.3% | 0 |
| mock-alpha | object_add | 12 | 1 | 8.3% | 1 | 8.3% | 0 |
| mock-alpha | object_remove | 12 | 1 | 8.3% | 1 | 8.3% | 0 |
| mock-alpha | object_replace | 12 | 1 | 8.3% | 1 | 8.3% | 0 |
| mock-alpha | portrait_identity | 12 | 1 | 8.3% | 1 | 8.3% | 0 |
| mock-alpha | restoration_repair | 12 | 1 | 8.3% | 1 | 8.3% | 0 |
| mock-alpha | spatial_relations | 12 | 1 | 8.3% | 1 | 8.3% | 0 |
| mock-alpha | style | 12 | 1 | 8.3% | 1 | 8.3% | 0 |
| mock-alpha | text_in_image | 12 | 1 | 8.3% | 1 | 8.3% | 0 |
| mock-beta | background_change | 12 | 1 | 8.3% | 1 | 8.3% | 0 |
| mock-beta | high_resolution_detail | 12 | 1 | 8.3% | 1 | 8.3% | 0 |
| mock-beta | lawful_sensitive | 12 | 3 | 25.0% | 1 | 8.3% | 0 |
| mock-beta | lighting_time | 12 | 1 | 8.3% | 1 | 8.3% | 0 |
| mock-beta | multi_person | 12 | 1 | 8.3% | 1 | 8.3% | 0 |
| mock-beta | object_add | 12 | 1 | 8.3% | 1 | 8.3% | 0 |
| mock-beta | object_remove | 12 | 1 | 8.3% | 1 | 8.3% | 0 |
| mock-beta | object_replace | 12 | 1 | 8.3% | 1 | 8.3% | 0 |
| mock-beta | portrait_identity | 12 | 1 | 8.3% | 1 | 8.3% | 0 |
| mock-beta | restoration_repair | 12 | 1 | 8.3% | 1 | 8.3% | 0 |
| mock-beta | spatial_relations | 12 | 1 | 8.3% | 1 | 8.3% | 0 |
| mock-beta | style | 12 | 1 | 8.3% | 1 | 8.3% | 0 |
| mock-beta | text_in_image | 12 | 1 | 8.3% | 1 | 8.3% | 0 |

## Interpretation

- A missing face detector/embedding or region judge produces N/A, not a perfect score.
- Human blinded pairwise preferences and artifact tags are required before making a model-selection decision.
- The sensitive/unconventional set is lawful and wholly synthetic. Provider-side refusals are recorded, never quietly normalized.
- Synthetic mock scores, times and costs have no predictive validity.

> Example matches the generated Python report format. Regenerate with:
> python3 -m tools.ai_edit_bench --dry-run --out /tmp/atlas-ai-edit-bench
> Open /tmp/atlas-ai-edit-bench/report.md for the fresh copy.
