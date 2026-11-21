# SigLIP 2 B/16 224 text embedder (vendored, exported model)

Unit NLS-1A ([#216](https://github.com/LPStruiksma/atlas-gallery/issues/216)) of goal [#94](https://github.com/LPStruiksma/atlas-gallery/issues/94). Contract and integration notes: [`docs/visual-search/NLS_1_TEXT_ASSET.md`](../../../../../../docs/visual-search/NLS_1_TEXT_ASSET.md).

- Upstream weights: the same checkpoint as the shipped image tower (`../siglip2/SOURCE.md`): `gs://big_vision/siglip2/siglip2_b16_224.npz`, fetched over HTTPS from https://storage.googleapis.com/big_vision/siglip2/siglip2_b16_224.npz on 2026-11-19. SHA-256 `74abfd2488a01f78c1cce5a38861dc929ea42aeabe8f622eaaa60ddfffeaad4e` (1,500,772,264 bytes), the pin in `tools/visual_search_bench/models.json`.
- Licence: Apache License 2.0. google-research/big_vision README, section "License", at commit `0127fb6b337ee2a27bf4e54dea79cff176527356`: "Unless explicitly noted otherwise, everything in the big_vision codebase (including models and colabs) is released under the Apache2 license." `LICENSE` here is a byte-for-byte copy of `../siglip2/LICENSE`, that commit's `LICENSE` (SHA-256 `43070e2d4e532684de521b885f385d0841030efa2b1a20bafb76133a5e1379c1`). Upstream ships no NOTICE file. Model: SigLIP 2 (Tschannen et al., 2025), Google DeepMind.
- What ships: the text tower only (`params/txt/*` of the checkpoint), ported by `tools/visual_search_bench/text_tower.py`, wrapped so the output is L2-normalised, exported to ONNX (opset 17) and quantised with ONNX Runtime dynamic int8 (MatMul, Gemm and the token-embedding Gather, per-tensor), except `/blocks.0/fc2/MatMul`, which stays fp32 (`text_int8_keep_fp32` in `models.json`). This is NLS-0's `int8_gather` variant, unchanged.
- Not shipped here: the Gemma SentencePiece tokenizer. It is pinned in `models.json`; bundling it is separate work (its redistribution licence is an open item from the NLS-0 review).
- Export (from the repository root, with `tools/visual_search_bench/requirements.txt` installed; torch 2.15.0, onnx 1.23.2, onnxruntime 1.31.1, onnxscript 0.7.2, numpy 2.5.4, sentencepiece 0.2.3; the checkpoint and tokenizer in `$C/models`):

  ```
  python -I tools/visual_search_bench/text_tower.py --weights-dir $C/models --out-dir $C/onnx
  python -I tools/visual_search_bench/nls1_ship_text_assets.py ship --onnx-dir $C/onnx
  python -I tools/visual_search_bench/nls1_ship_text_assets.py verify
  ```

  The export is reproducible: on 2026-11-19 (Python 3.13, 4 vCPU cloud VM) it gave the same fp32 (`c486ffad…`), `int8` and `int8_gather` SHA-256 as NLS-0's report of 2026-11-14 (`docs/visual-search/results/nls0_text_export_20261114.json`). `ship` refuses to write parts if the graph's hash differs from that report.

## Files

GitHub warns above 50 MB and this project uses no Git LFS, so the graph is split into ordered parts of at most 48,000,000 bytes. `manifest.json` holds the same pins in machine-readable form. Join the parts in order, verify the whole-file SHA-256 and size before ONNX Runtime sees the bytes, and fail closed on a mismatch (as `SiglipModelFile` does for the image tower).

| File | Bytes | SHA-256 |
|---|---|---|
| `siglip2_b16_224_text_int8.onnx.part0` | 48,000,000 | `ef9509fbe1722c01a3e7aef4e837cfa3ca02e591a6af8f4c826a7656aed5fa4f` |
| `siglip2_b16_224_text_int8.onnx.part1` | 48,000,000 | `549f64a949ea1ee4b3acf9177f504100a89028ffdd0e45cc1e70a9dfaf3dfc0c` |
| `siglip2_b16_224_text_int8.onnx.part2` | 48,000,000 | `132b3978c41226002abe413a33ba05d25ce551d74d7e7f947593bd27b892a72a` |
| `siglip2_b16_224_text_int8.onnx.part3` | 48,000,000 | `3701502fbfc98555170f0f31651ae2d8749b187115da49a78dc1342905342ed2` |
| `siglip2_b16_224_text_int8.onnx.part4` | 48,000,000 | `dd53c8b19b88290be1ac35364fc1e5914dedbf0f7156e928ddfe6eea58fe198f` |
| `siglip2_b16_224_text_int8.onnx.part5` | 48,000,000 | `bc20098961ba5f63b201e1543cb19bdc6b5efbfcb74fbe3cad575f254c0f3a82` |
| `siglip2_b16_224_text_int8.onnx.part6` | 2,153,185 | `a5530a5c7eb67c4ebccf8b32677ad27197e8e6acbe1885787e234d7b7619428e` |
| joined `siglip2_b16_224_text_int8.onnx` | 290,153,185 | `6fd97706a61981eb1a8d2444777209f0768162d3172c74a73134fbc1f78c0da6` |

Suggested version fence for text vectors: `siglip2_b16_224.text.int8:6fd97706a619` (the image fence is `siglip2_b16_224.int8:74da3ac2b46e`).

Interface: input `tokens` int64 `[n,64]`, output `embedding` float32 `[n,768]`, L2-normalised, in the same space as the shipped image vectors. Tokens: ASCII-lowercase the text, Gemma SentencePiece ids, no BOS, append EOS (id 1) and keep it when truncating to 64, pad with id 0. Everything runs on device; no text or vector leaves the phone.
