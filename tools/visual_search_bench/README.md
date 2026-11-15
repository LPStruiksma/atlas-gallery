# Visual search benchmark (VS-S0)

Host-side tooling for the semantic-search-by-image model spike of [#468](https://github.com/LPStruiksma/atlas-native/issues/468). Findings: [`docs/visual-search/VS_S0_MODEL_SPIKE.md`](../../docs/visual-search/VS_S0_MODEL_SPIKE.md); text search feasibility (NLS-0, [#104](https://github.com/LPStruiksma/atlas-gallery/issues/104)): [`docs/visual-search/NLS_0_TEXT_FEASIBILITY.md`](../../docs/visual-search/NLS_0_TEXT_FEASIBILITY.md). None of this code ships in the APK or runs in CI, apart from the offline unit tests; the APK ships only the SigLIP 2 model file that `export_onnx.py` produces (see `app/src/main/assets/models/siglip2/SOURCE.md`) and the two pose models pinned in `models.json` (see `app/src/main/assets/models/blazepose/SOURCE.md`).

| File | What |
|---|---|
| `models.json` | Pinned weight URLs, SHA-256, licences, preprocessing, int8 exclusions |
| `build_dataset.py` | Picks the Open Images action/pose set from human-verified labels; writes `data/open_images_actions_v1.csv` (IDs + labels only) |
| `fetch_images.py` | Downloads that set into a local cache after re-checking each image's CC BY 2.0 licence |
| `export_onnx.py` | Exports image towers to ONNX fp32 and int8, L2-normalised output; for VS-S1a also splits the shipped SigLIP 2 int8 file into APK asset parts (`--ship-dir`) and writes the androidTest golden vectors (`--golden-kotlin`) |
| `quant_probe.py` | Finds the layer whose int8 quantisation breaks an export |
| `blazepose.py`, `pose_descriptor.py` | Person detector + BlazePose on ONNX Runtime; mirror- and yaw-tolerant pose descriptor |
| `pose_golden.py` | Golden values for the Kotlin pose port (VS-S2p): JVM parity data and the androidTest host descriptors for formula-drawn figures |
| `bench.py` | Size, latency and retrieval metrics; writes the results JSON |
| `run.sh` | End-to-end reproduction into `$VS_CACHE` (default `~/.cache/atlas-vs`) |
| `text_tower.py` | NLS-0: SigLIP 2 B/16 text tower ported from the pinned checkpoint to PyTorch, checked against a numpy reference, exported to ONNX fp32/int8; Gemma tokenizer with the demo colab's preprocessing |
| `build_relational.py` | NLS-0: builds the relational text-to-image benchmark from Open Images human annotations; writes `data/nls0_manifest.csv` and `data/nls0_judgments.csv` from `data/nls0_queries.json` |
| `nls_bench.py` | NLS-0: text-to-image P@10, R@50, nDCG@10 and hard-negative error, plain cosine vs structured top-100 rerank |
| `run_nls0.sh` | NLS-0 end-to-end reproduction |

Offline tests (numpy only): `python3 -m unittest discover -s tools/visual_search_bench -p 'test_*.py'`.

Privacy: the tools never read the owner's library. Images, weights, ONNX files and embeddings stay in the cache outside the repository; only aggregate metrics are committed. The on-device private benchmark is unit VS-B.
