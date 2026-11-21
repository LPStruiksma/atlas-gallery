# NLS-1B: tokenizer licence and offline goldens

Status: **offline goldens pending; APK redistribution permission unresolved**. Do not package or redistribute the tokenizer in an APK.

## Approved non-bundled delivery (2026-11-21)

Following the [first-party object audit](https://github.com/LPStruiksma/atlas-gallery/issues/898#issuecomment-7062369113) and [Lukas's decision](https://github.com/LPStruiksma/atlas-gallery/issues/898#issuecomment-7067261537), the Android runtime will fetch the exact pinned Google-hosted tokenizer on first use, verify its SHA-256 before use and cache it locally; this separate implementation belongs to [#238](https://github.com/LPStruiksma/atlas-gallery/issues/238). **Downloading on demand is not evidence of permission to redistribute this object inside an APK.** This NLS-1B unit generates offline token IDs from independently hash-verified bytes without committing the binary. A future `status: verified` fixture means tokenizer provenance and token-ID generation were verified, not that any redistribution licence was granted.

## Exact-byte evidence

The pinned object is `https://storage.googleapis.com/big_vision/gemma_tokenizer.model`, with expected SHA-256 `6969e64047744a44bb3abfb5c50f8de0f7ed8b571d5444426ef931f651d1a0ef` in `tools/visual_search_bench/models.json`.

On 2026-11-20, `curl -fL --proto '=https' --tlsv1.2` through the evidence environment's configured network proxy failed before any bytes were downloaded: the proxy rejected the CONNECT request with HTTP 403. Consequently, this seat could not independently hash the upstream bytes. The tokenizer binary remains outside Git.

## First-party terms and APK conclusion

Google's current [Gemma Terms of Use](https://ai.google.dev/gemma/terms/) allow distribution of the Gemma models named in their appendix, subject to Section 3's downstream-use restrictions, agreement-copy, modification-notice, and `Notice`-file requirements. However, Section 1.1(c) defines “Gemma” as the listed machine-learning language models, trained weights, and parameters; it does **not** identify this separately hosted `big_vision/gemma_tokenizer.model` object, its SHA-256, or tokenizer files generally.

The first-party [big_vision README](https://github.com/google-research/big_vision/blob/0127fb6b337ee2a27bf4e54dea79cff176527356/README.md#license) states that repository contents and models are Apache-2.0 licensed, and the first-party [SigLIP 2 README](https://github.com/google-research/big_vision/blob/0127fb6b337ee2a27bf4e54dea79cff176527356/big_vision/configs/proj/image_text/README_siglip2.md) directs users to a Gemma tokenizer. Neither source attaches terms to the exact separately hosted object or its digest. The storage response could not be inspected because the proxy rejected the connection.

Therefore the general repository licence is insufficient proof for this separate object, and the Gemma terms cannot safely be presumed to cover it. **Inclusion of these tokenizer bytes in an APK is not presently proven permitted.** Obtain a first-party statement that expressly covers this exact object/digest (and comply with its conditions) before packaging it.

## Offline contract regression tests

The generator implements the same **ASCII-only** normalization as NLS-0's `text_tower.ascii_lower` (the default `tf.strings.lower` behavior). Non-ASCII uppercase letters such as `É` are not lowercased. This matters for byte-identical token IDs across the text tower and offline goldens.

Run the dependency-free mock-backed regression tests without downloading the model:

```sh
python3 tools/visual_search_bench/nls1_tokenizer_golden.py --self-test
```

The tests check EN/NL ASCII case folding, Unicode preservation, no automatic BOS/EOS injection, empty input, 64-ID sticky EOS/truncation, padding, and validation rejection of corrupted vectors. These tests do **not** establish real SentencePiece token IDs, a verified model digest, or APK redistribution rights. The checked-in golden file remains BLOCKED until the real binary is independently verified and used.

## Golden-vector evidence gap

Actual regeneration was also impossible: the exact bytes could not be obtained or verified, and the evidence environment did not have the tooling-only `sentencepiece` module installed. No IDs have been invented. `tools/visual_search_bench/data/nls1_tokenizer_goldens.json` records the blocked state and intentionally contains no cases.

The offline generator enforces the pinned digest before loading the model. With independently obtained verified bytes and SentencePiece installed outside application/runtime dependencies, run:

```sh
python3 tools/visual_search_bench/nls1_tokenizer_golden.py --generate --model /outside/git/gemma_tokenizer.model
python3 tools/visual_search_bench/nls1_tokenizer_golden.py --check --model /outside/git/gemma_tokenizer.model
```

Generation covers English and Dutch ASCII-lowercase inputs, Unicode inputs, empty text, truncation with sticky EOS, and fixed length-64 right-padding. `--check` deliberately fails while the committed artifact is blocked; it will only pass after genuine vectors from the hash-verified tokenizer replace the blocked record.
