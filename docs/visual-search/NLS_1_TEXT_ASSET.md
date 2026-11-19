# NLS-1A: the SigLIP 2 text tower as APK assets

Unit NLS-1A ([#216](https://github.com/LPStruiksma/atlas-gallery/issues/216)) of goal [#94](https://github.com/LPStruiksma/atlas-gallery/issues/94). It ships the model that NLS-0 ([`NLS_0_TEXT_FEASIBILITY.md`](NLS_0_TEXT_FEASIBILITY.md)) recommended, and nothing that loads it: no tokenizer, no query path, no UI. Provenance and per-file hashes: [`app/src/main/assets/models/siglip2_text/SOURCE.md`](../../app/src/main/assets/models/siglip2_text/SOURCE.md).

## What was measured (2026-11-19, Claude cloud seat, 4 vCPU x86, Python 3.13)

| | Result |
|---|---|
| Checkpoint | `siglip2_b16_224.npz`, SHA-256 `74abfd24…` = `models.json` pin = the shipped image tower's checkpoint |
| Tokenizer (used for parity texts only) | `gemma_tokenizer.model`, SHA-256 `6969e640…` = pin |
| fp32 text graph | 1,129.3 MB, SHA-256 `c486ffad…`, identical to NLS-0 |
| Shipped graph (`int8_gather`) | **290,153,185 bytes** (290.2 MB), SHA-256 `6fd97706a61981eb1a8d2444777209f0768162d3172c74a73134fbc1f78c0da6`, identical to NLS-0 |
| Quantisation | dynamic per-tensor QInt8 on MatMul, Gemm and the embedding Gather; `/blocks.0/fc2/MatMul` kept fp32 |
| Parity (7 texts incl. Dutch and a truncated one) | cosine to fp32 min 0.9933, mean 0.9964 (NLS-0's values, same bytes) |
| Parts | 6 × 48,000,000 + 2,153,185 bytes; each part and the ordered join verified by SHA-256 |
| APK cost (estimate) | 290.2 MB uncompressed; zlib level 6 over the parts gives 178.9 MB, so expect roughly +180 MB of APK if AAPT compresses the assets as usual. The exact delta is the hosted build's debug APK size against main |

No deviation from NLS-0: the unit's stop condition did not trigger. Host timings are not device latency; NLS-0's 25 ms (1 thread) is a cloud VM figure and says nothing about the S24+.

Reproduce: `SOURCE.md` has the three commands. `nls1_ship_text_assets.py ship` refuses to write parts unless the graph's SHA-256, the fp32 SHA-256, the tokenizer pin and the logit parameters all equal NLS-0's committed report. `nls1_ship_text_assets.py verify` re-checks a checkout's parts with the standard library only, streaming through a 1 MiB buffer.

## Loader contract (for NLS-1B and later)

- **Assets:** `models/siglip2_text/siglip2_b16_224_text_int8.onnx.part0` … `.part6`, opened in numeric order with `AssetManager.open`. `manifest.json` in the same directory carries the whole-file and per-part pins.
- **Materialise once, verify always:** join the parts into app-private no-backup storage, streaming with a fixed buffer (never a model-sized array), check size 290,153,185 and SHA-256 `6fd97706…` before ONNX Runtime opens the file, and on a mismatch delete it and refuse to run. `SiglipModelFile.materialize(dir, parts, sha256, sizeBytes, fileName)` already does exactly this for the image tower and takes the pins as arguments; `SiglipTextAssetsTest` runs it over the real parts (largest single read 64 KiB). Use a directory of its own, not the image tower's.
- **Session:** input `tokens` int64 `[n,64]`; output `embedding` float32 `[n,768]`, already L2-normalised. Score against the stored `siglip2_b16_224.int8` image vectors by dot product; no re-index.
- **Tokens:** ASCII-lowercase only (`tf.strings.lower`), Gemma SentencePiece ids, no BOS, EOS id 1 appended and kept when truncating to 64 (sticky), pad id 0. The tokenizer is not in this unit.
- **Absolute threshold:** `p = sigmoid(112.669 · cos − 16.772)` (`t = 4.724453`, `b = −16.771725` from the checkpoint), for "nothing matches".
- **Version fence:** store text-side artefacts under `siglip2_b16_224.text.int8:6fd97706a619`.
- **Memory:** a 290 MB session; open it only while text search is in use and close it afterwards. Measure on the device before choosing a thread count.

## Not in this unit

Tokenizer bundling and its licence check, the Kotlin tokenizer, the query path, re-ranking, the UI and any on-device latency. Those are the later NLS-1 units.
