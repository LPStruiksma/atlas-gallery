# NLS-1B: tokenizer licence and offline goldens

Status: **offline goldens verified (2026-11-22); APK redistribution permission unresolved**. Do not package or redistribute the tokenizer in an APK.

## Approved non-bundled delivery (2026-11-21)

Following the [first-party object audit](https://github.com/LPStruiksma/atlas-gallery/issues/898#issuecomment-7062369113) and [Lukas's decision](https://github.com/LPStruiksma/atlas-gallery/issues/898#issuecomment-7067261537), the Android runtime will fetch the exact pinned Google-hosted tokenizer on first use, verify its SHA-256 before use and cache it locally; this separate implementation belongs to [#238](https://github.com/LPStruiksma/atlas-gallery/issues/238). **Downloading on demand is not evidence of permission to redistribute this object inside an APK.** This NLS-1B unit generates offline token IDs from independently hash-verified bytes without committing the binary. A future `status: verified` fixture means tokenizer provenance and token-ID generation were verified, not that any redistribution licence was granted.

## Exact-byte evidence

The pinned object is `https://storage.googleapis.com/big_vision/gemma_tokenizer.model`, with expected SHA-256 `6969e64047744a44bb3abfb5c50f8de0f7ed8b571d5444426ef931f651d1a0ef` in `tools/visual_search_bench/models.json`.

On 2026-11-20 the evidence environment's proxy rejected the CONNECT request (HTTP 403), so that seat could not hash the bytes; two later ChatGPT/Codex environments also could not resolve the host. On 2026-11-22 a Claude cloud seat downloaded the object to a temporary directory outside Git: `curl -L` returned **HTTP 200**, **4,241,111 bytes**, `sha256sum` = `6969e64047744a44bb3abfb5c50f8de0f7ed8b571d5444426ef931f651d1a0ef`, matching the pin. Tool versions: Python 3.12, SentencePiece 0.2.3. The loaded model reports vocab size 256000, BOS=2, EOS=1, PAD=0, UNK=3. The tokenizer binary remains outside Git.

Three facts are kept separate: (1) **object provenance** is verified by the digest above; (2) **vector parity** is the committed golden file, generated from those bytes; (3) **APK redistribution permission is unresolved**.

## First-party terms and APK conclusion

Google's current [Gemma Terms of Use](https://ai.google.dev/gemma/terms/) allow distribution of the Gemma models named in their appendix, subject to Section 3's downstream-use restrictions, agreement-copy, modification-notice, and `Notice`-file requirements. However, Section 1.1(c) defines “Gemma” as the listed machine-learning language models, trained weights, and parameters; it does **not** identify this separately hosted `big_vision/gemma_tokenizer.model` object, its SHA-256, or tokenizer files generally.

The first-party [big_vision README](https://github.com/google-research/big_vision/blob/0127fb6b337ee2a27bf4e54dea79cff176527356/README.md#license) states that repository contents and models are Apache-2.0 licensed, and the first-party [SigLIP 2 README](https://github.com/google-research/big_vision/blob/0127fb6b337ee2a27bf4e54dea79cff176527356/big_vision/configs/proj/image_text/README_siglip2.md) directs users to a Gemma tokenizer. Neither source attaches terms to the exact separately hosted object or its digest. 

Therefore the general repository licence is insufficient proof for this separate object, and the Gemma terms cannot safely be presumed to cover it. **Inclusion of these tokenizer bytes in an APK is not presently proven permitted.** Obtain a first-party statement that expressly covers this exact object/digest (and comply with its conditions) before packaging it.

## Offline contract regression tests

The generator implements the same **ASCII-only** normalization as NLS-0's `text_tower.ascii_lower` (the default `tf.strings.lower` behavior). Non-ASCII uppercase letters such as `É` are not lowercased. This matters for byte-identical token IDs across the text tower and offline goldens.

Run the dependency-free mock-backed regression tests without downloading the model:

```sh
python3 tools/visual_search_bench/nls1_tokenizer_golden.py --self-test
```

The tests check EN/NL ASCII case folding, Unicode preservation, no automatic BOS/EOS injection, empty input, 64-ID sticky EOS/truncation, padding, and validation rejection of corrupted vectors. These tests alone do **not** establish real SentencePiece token IDs; the committed golden file does.

## Golden vectors

`tools/visual_search_bench/data/nls1_tokenizer_goldens.json` (`status: verified`) holds six cases generated from the hash-verified bytes: EN and NL ASCII-lowercase, EN and NL Unicode, empty text and a long input that truncates with sticky EOS. Each has 64 IDs, right-padded with PAD=0. Verification means tokenizer provenance and token-ID generation, not a redistribution licence.

The offline generator enforces the pinned digest before loading the model. To regenerate or check, with the bytes stored outside the checkout and SentencePiece 0.2.3 installed outside application dependencies:

```sh
python3 tools/visual_search_bench/nls1_tokenizer_golden.py --generate --model /outside/git/gemma_tokenizer.model
python3 tools/visual_search_bench/nls1_tokenizer_golden.py --check --model /outside/git/gemma_tokenizer.model
```
