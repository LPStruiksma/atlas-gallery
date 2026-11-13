# SigLIP 2 B/16 224 image embedder (vendored, exported model)

- Upstream weights: `gs://big_vision/siglip2/siglip2_b16_224.npz`, fetched over HTTPS from https://storage.googleapis.com/big_vision/siglip2/siglip2_b16_224.npz on 2026-11-07. SHA-256 `74abfd2488a01f78c1cce5a38861dc929ea42aeabe8f622eaaa60ddfffeaad4e` (1,500,772,264 bytes), the pin in `tools/visual_search_bench/models.json`.
- Licence: Apache License 2.0. google-research/big_vision README, section "License", at commit `0127fb6b337ee2a27bf4e54dea79cff176527356`: "Unless explicitly noted otherwise, everything in the big_vision codebase (including models and colabs) is released under the Apache2 license." The `LICENSE` file here is that commit's `LICENSE`, byte for byte (SHA-256 `43070e2d4e532684de521b885f385d0841030efa2b1a20bafb76133a5e1379c1`). Upstream ships no NOTICE file. Model: SigLIP 2 (Tschannen et al., 2025), Google DeepMind.
- What ships: the image tower only (ViT-B/16, 224 px, MAP head), wrapped so the output is L2-normalised, exported to ONNX (opset 17) and quantised with ONNX Runtime dynamic int8 (MatMul/Gemm weights), except `/tower/blocks/blocks.5/mlp/fc2/MatMul`, which stays fp32 (without that exception the int8 graph collapses to cosine 0.78 against fp32; see `docs/visual-search/VS_S0_MODEL_SPIKE.md`). Input `pixels` `[n,3,224,224]` float32, output `embedding` `[n,768]`.
- Export command (from the repository root, with `tools/visual_search_bench/requirements.txt` installed; torch 2.15.0, timm 1.0.31, onnx 1.23.2, onnxruntime 1.31.1):

  ```
  python -I tools/visual_search_bench/export_onnx.py --weights-dir <dir with the .npz> --out-dir <cache> \
      --only siglip2_b16_224 --ship-dir app/src/main/assets/models/siglip2 \
      --golden-kotlin app/src/androidTest/java/nl/atlas/gallery/featurevisual/SiglipGolden.kt
  ```

  The export is reproducible: on 2026-11-07 it gave the same fp32 (`d74808f0…`) and int8 SHA-256 as the VS-S0 export report (`docs/visual-search/results/vs_s0_export_20261107.json`).

## Files

GitHub refuses files over 100 MB and this project uses no Git LFS, so the int8 file is split into ordered parts of at most 60,000,000 bytes. `SiglipModelFile` joins them in order, once, into app-private no-backup storage and checks the whole-file SHA-256 before ONNX Runtime ever sees the bytes (and again on every open); on a mismatch it deletes the file and refuses to run.

| File | Bytes | SHA-256 |
|---|---|---|
| `siglip2_b16_224_int8.onnx.part0` | 60,000,000 | `a0ecdc500c102f4d2bf412bfb1680d8fc391c23c03d7fcbb4e2adcfe95b5072a` |
| `siglip2_b16_224_int8.onnx.part1` | 42,869,345 | `d55af6f86ba6723bd0a4a37388fa4b3101b83a74972b80394def6ba92872e337` |
| joined `siglip2_b16_224_int8.onnx` | 102,869,345 | `74da3ac2b46e4292c4639affc3cca623c2d5551d012ead113b1486c22e0f5336` |

Version fence stored with every vector: `siglip2_b16_224.int8:74da3ac2b46e` (`SiglipModel.MODEL_VERSION`).

Preprocessing (VS-S0 contract, `SiglipPreprocess.kt`): decode with EXIF orientation applied → RGB → 224×224 Pillow-exact bilinear resize without cropping → `(x/255 − 0.5) / 0.5` → NCHW float32. The model runs fully on device through ONNX Runtime; no image or vector leaves the phone.
