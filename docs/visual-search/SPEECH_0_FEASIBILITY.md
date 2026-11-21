# SPEECH-0: local spoken-word search feasibility

Issue: [atlas-gallery#669](https://github.com/LPStruiksma/atlas-gallery/issues/669) · Goals: [Vision D #101](https://github.com/LPStruiksma/atlas-gallery/issues/101) and [search everywhere #175](https://github.com/LPStruiksma/atlas-gallery/issues/175). Tool and raw results: [`tools/speech_bench/`](../../tools/speech_bench/README.md).

## Verdict

**Conditional yes for a bounded SPEECH-1 spike with Whisper small (multilingual, int8). No for tiny and base on Dutch.** On a 4 vCPU cloud host, with the same ONNX Runtime version ATLAS ships (1.23.2) and no custom ops, Whisper small reaches **5.8 % English and 23.6 % Dutch WER at an RTF of 0.19 and 0.29**. That is good enough to *find* spoken words, not good enough for captions anyone reads without seeing the video. Nothing here was run on a phone, so the S24+ numbers below are estimates with stated assumptions, and speed on the device is the first thing SPEECH-1 must measure before it commits to indexing.

## What was actually measured

Host: Intel Xeon @ 2.1 GHz, 4 vCPU, 15.7 GB, Linux x86_64, Python 3.13, ONNX Runtime **1.23.2** (CPU provider, 4 intra-op threads), greedy decode, language forced per clip. Timing covers log-mel, encoder and the decoder loop on a warmed session; WAV decode and model load are excluded. Every figure traces to a committed result file in [`tools/speech_bench/results/`](../../tools/speech_bench/results).

Corpus: Google FLEURS `test` split, `en_us` and `nl_nl`, dataset revision `70bb2e84b976b7e960aa89f1c648e09c59f894dd`, CC-BY-4.0. Sample: **30 clips per language** chosen by a seeded hash (`speech-0-v1`) before any model ran, about 5 minutes of audio per language (653 English and 696 Dutch reference words). FLEURS is read speech from a fixed sentence list, so clean and read-aloud, unlike phone video.

| Model (HF revision) | EN WER | NL WER | EN RTF | NL RTF | Peak RSS | Int8 size (enc + dec) | Status |
|---|---:|---:|---:|---:|---:|---:|---|
| Whisper tiny multilingual (`onnx-community/whisper-tiny@ff41770`) | 45.2 % | 72.6 % | 0.080 | 0.098 | 2.7 GB | 10 + 31 MB | measured |
| Whisper base multilingual (`onnx-community/whisper-base@27a07b4`) | 10.3 % | 40.5 % | 0.096 | 0.151 | 2.9 GB | 23 + 54 MB | measured |
| Whisper small multilingual (`onnx-community/whisper-small@36050c4`) | 5.8 % | 23.6 % | 0.189 | 0.290 | 3.3 GB | 92 + 157 MB | measured |
| Distilled Whisper | | | | | | | not measured: `distil-whisper/distil-small.en` is English-only |
| Moonshine | | | | | | | not measured: `moonshine-ai/moonshine-tiny` card lists `en` only |

WER is micro (total errors over total reference words) with one normaliser for both languages: NFC, case-fold, drop `[ ] '`, other punctuation to spaces, diacritics kept. Macro (per-clip mean) WER: tiny 59.8 / 74.6, base 11.4 / 42.4, small 6.4 / 25.1 percent (EN / NL). RTF is total wall time over total audio; per-clip median and p95 are in the result files. Peak RSS is the whole process and includes the 200 to 400 MB FLEURS parquet held in memory, so treat it as an upper bound of about 0.4 GB too high.

How much to trust this: 30 clips per language is a screening sample. Expect a few points of WER noise (at 650 words, one error is 0.15 point, but clip difficulty varies much more). The ranking tiny, base, small is large enough to be real; the exact Dutch figure is not. The decoder has a loop guard (stop after the same four tokens repeat three times), which fired on 4 tiny clips and none for base or small. Without it tiny English read 98 % WER on an 8-clip smoke run because of runaway repetition, which is a real greedy-decoding failure for the smallest model. Numbers, dates and spelling variants ("twenties" vs "20s") count as errors under this normaliser, so WER is pessimistic for search use.

Reproduce: commands in the [tool README](../../tools/speech_bench/README.md). Weights and audio are never committed. Reports hold counts and timings only, never transcript text.

## Licence and provenance

| Item | Source and pin | Licence evidence | Status |
|---|---|---|---|
| Whisper weights, tiny/base/small | `openai/whisper-{tiny,base,small}` at `169d4a4`, `e37978b`, `973afd2` | `apache-2.0` on each model card | Verified (card metadata) |
| ONNX conversions used | `onnx-community/whisper-*` at the revisions above; SHA-256 of encoder, decoder and tokenizer are in each result file | `base` card says `apache-2.0`; the `tiny` and `small` cards carry **no licence field** and name the openai model as base | Base verified. Tiny and small are derivatives of Apache-2.0 weights, but the conversion repo's own licence is **UNVERIFIED**; SPEECH-1 should export from the openai checkpoint itself, or confirm the licence, before bundling |
| Tokenizer | `tokenizer.json` inside the same repos | No separate licence text found | UNVERIFIED as its own artefact; shipped under the model repo's terms |
| Silero VAD | `onnx-community/silero-vad@e71cae9` (`onnx/model.onnx`, SHA-256 `a4a068cd…`) | `mit` in card and a `LICENSE` file in the repo | Verified |
| FLEURS | `google/fleurs@70bb2e8` | `cc-by-4.0` in the dataset card | Verified (card). Attribution required if results are published |
| Moonshine | `moonshine-ai/moonshine-tiny@390624e` | `mit`, language `en` only | Licence verified, rejected for Dutch |

No weights, tokenizer files or audio were committed. A deployment right for the shipped asset still needs a human read of the licence texts (Apache-2.0 requires the notice to travel with the weights), which this unit did not do beyond the card metadata above.

## ONNX Runtime compatibility

ATLAS ships `com.microsoft.onnxruntime:onnxruntime-android:1.23.2` (`app/build.gradle.kts`), the full package, with CPU execution and `setIntraOpNumThreads` in every existing session. The benchmark used the **same version, 1.23.2, on x86**.

| Component | Result | Evidence |
|---|---|---|
| Encoder and merged decoder (with KV cache branch) load and run | Yes, host | All three sizes decode correctly; both graphs are ONNX opset 14, standard `ai.onnx` domain only, no custom or contrib-domain nodes |
| Quantised ops (`DynamicQuantizeLinear`, `MatMulInteger`, `ConvInteger`) | Conditional | Run on host 1.23.2. The full Android package includes them; not run on Android |
| Decoder loop, KV-cache feed, token suppression, loop guard | Needs app code | About 60 lines of Python here; SPEECH-1 ports them to Kotlin |
| Log-mel frontend (n_fft 400, hop 160, 80 bands, 30 s pad) | Needs app code | NumPy here; no library needed, but the Kotlin port must match to float tolerance |
| Tokenizer (byte-level BPE, `tokenizer.json`) | Needs a Kotlin implementation | Existing SigLIP text work already ships a SentencePiece tokenizer, not this one |
| Silero VAD | Conditional | Loads and runs on host 1.23.2; inputs `input`, `state`, `sr` |
| New Gradle dependency | **None expected** | Everything above is plain ORT plus Kotlin |

Host success proves the graphs are valid for this ORT version, not that they run on the APK. "Uses existing ORT" is established; "works on the S24+" is not.

## Silero VAD

Loaded and run over the same 60 clips. VAD cost is negligible: RTF 0.004 (EN) and 0.0045 (NL). Speech fraction at p > 0.5 was 72 % on FLEURS, which is dense read speech. **VAD's effect on WER was not measured**, and the speech fraction of real phone video is unknown, so no saving from skipping silence is claimed.

## S24+ estimate (not a measurement)

No phone was used. VS-S0 earlier judged that a Snapdragon 8 Gen 3 big core runs int8 matrix multiplies in the same range as this host; VS-B was to measure it. I take a **slowdown of 1x (low), 2x (nominal), 3x (high)** against this host's RTF for Whisper small as an assumption, not a finding. The regional variant (Snapdragon or Exynos), thermal throttling, 12 GB of RAM shared with the app, and the memory the phone can give (host peak RSS was 3.3 GB including the parquet) are all unknown. ATLAS uses CPU only, with no NNAPI or XNNPACK provider configured.

Indexing cost for Whisper small, host Dutch RTF 0.29 as the conservative case, spoken content in the video taken as 100 % (no VAD saving):

| Video hours | Host CPU time | S24+ low (1x) | S24+ nominal (2x) | S24+ high (3x) | 1-hour charging windows (nominal) |
|---:|---:|---:|---:|---:|---:|
| 1 | 17 min | 17 min | 35 min | 52 min | 1 |
| 10 | 2.9 h | 2.9 h | 5.8 h | 8.7 h | 6 |
| 100 | 29 h | 29 h | 58 h | 87 h | 58 |

These exclude video decode, audio extraction, scheduling gaps and thermal pauses, so real time is longer. A 100-hour library is a multi-week charging-only job at the nominal case, which argues for incremental, newest-first indexing. Battery use in mAh or percent is **not estimated**: it needs device power measurement. Transcript storage is small: at an assumed 150 spoken words per minute and about 7 bytes per word, 100 hours of speech is about 6 MB before timestamps and index overhead.

## Recommendation and gates

1. **Model:** Whisper small multilingual, int8, for SPEECH-1. Tiny is unusable for Dutch (73 % WER). Base is usable for English (10 %) but Dutch at 41 % loses too many words. Small's 24 % Dutch WER is search-grade: many queried words will still be found, but captions would show visible mistakes. Not measured: whether `large` or distilled Dutch models close that gap.
2. **Before any app work,** SPEECH-1 measures real speed on the S24+ and checks the encoder's fixed 30-second window cost, because short clips pay for a full window.
3. **Limits of this study:** read speech, 30 clips per language, one host, greedy decoding, forced language (no language identification measured), no VAD-on versus VAD-off WER, no phone-video noise, music or overlapping speakers, no word timestamps, no battery.
4. **Licence:** resolve the conversion-repo and tokenizer licences, or export from the openai checkpoint, before any weights are bundled or downloaded by the app.

## Proposed downstream units (not built here)

**SPEECH-1 — device spike and scanner, no UI.** Kotlin port of mel, tokenizer and decoder loop checked against this tool's outputs; S24+ speed, memory and thermal measurement on synthetic or licensed audio only; then, if it passes, a feature-gated, off-by-default, charging-and-idle-only scanner with durable resume and per-video invalidation. Transcript segments store `mediaId`, `startMs`, `endMs`, `language`, `text`, `modelVersion` on device only, with deletion. Skips silent, DRM, inaccessible or undecodable media.

**SPEECH-2 — search and subtitles, UI.** Needs its own design brief and independent design preflight first. Word and phrase search beside visual search, jump to timestamp, captions toggle, TalkBack semantics, generated captions clearly labelled and deletable. No cloud endpoint and no automatic translation.

## Evidence register

- Constraints: [#669](https://github.com/LPStruiksma/atlas-gallery/issues/669); related spikes [VS-S0](VS_S0_MODEL_SPIKE.md), [NLS-0](NLS_0_TEXT_FEASIBILITY.md), [LLM-0](LLM_0_FEASIBILITY.md).
- Results: `tools/speech_bench/results/host_whisper-{tiny,base,small}.json`, `host_silero_vad.json` (per-clip counts and timings, SHA-256 of every model file, environment, sample IDs).
- Tests: `python3 -m unittest test_speech_bench` in `tools/speech_bench` (synthetic, offline). Local evidence only: no workflow runs them yet (adding one touches `.github/**`).
- Exact-head CI: see the PR.
