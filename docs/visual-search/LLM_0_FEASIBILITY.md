# LLM-0: can a small on-device language model help ATLAS?

Unit LLM-0 ([#671](https://github.com/LPStruiksma/atlas-gallery/issues/671)) of goal [#94](https://github.com/LPStruiksma/atlas-gallery/issues/94), vision [#101](https://github.com/LPStruiksma/atlas-gallery/issues/101). Host-only research: no Android code, dependency, model weight or network call at runtime is added. Tools: [`tools/llm_bench/`](../../tools/llm_bench/README.md). Raw results: [`baseline.json`](../../tools/llm_bench/results/baseline.json), [`model_qwen3-1.7b-q8_0.json`](../../tools/llm_bench/results/model_qwen3-1.7b-q8_0.json).

**Measured vs estimated.** Everything marked *measured* ran on a 4-vCPU Xeon cloud VM, never on a handset. Everything marked *estimated* is arithmetic from stated assumptions. **No S24+ number exists yet.** Licence facts were read from each model's Hugging Face metadata on 2026-11-21 (not from the full licence text); they are a screen, not legal clearance.

## Answer

1. **Query understanding (text to structured filter): conditional go for a spike, no go to ship.** A deterministic rule parser already gets 93% exact match on the synthetic EN/NL set (measured, but see the bias note), so a model must beat that. One untuned prompt on Qwen3 1.7B (Q8_0) got 25% exact match (measured), with valid JSON every time but poor judgement of intent, dates and unknown names. A model is only worth its APK and RAM if it clearly beats rules on phrasing rules never saw. This set cannot show that yet.
2. **Story titles: defer.** Not measured. Titles need captions or tags as input (CAP-0, TAGS-0) and a human-quality judgement this unit has no benchmark for. Sound as a later optional feature, never required for Stories.
3. **Metadata Q&A ("how many photos in Rome?"): no go for a language model.** It is a count over the existing index. Parse the question to a filter plus an aggregate and run the database query. A model that answers from its own text would be wrong by construction.
4. **Dutch is not clearly worse than English for the model** (28% vs 22% exact, n=36 each, within noise). The rule parser is weaker in Dutch (89% vs 97%).

## What was measured

**Task.** Parse a library search request into a filter: `intent`, `people`, `exclude_people`, `place`, `date_from`, `date_to`, `media`, `tags`, `exclude_tags`, `unresolved`. The parser is handed the library's known people, places and tag vocabulary (all synthetic) and today's date (2026-06-15). Anything not in the context must go to `unresolved`. Out-of-scope requests must return `unsupported` or `question`.

**Set.** 36 request pairs, each in English and Dutch (72 queries): tags, people, places, absolute and relative dates, seasons, ranges, negation, media type, colloquial phrasing, unknown names, questions and actions. Names and places are invented; no real library content. The Dutch was written by the Dev seat and wants a native check. Few-shot examples are separate and a test checks they do not appear in the set.

**Scoring.** Exact match over all fields (non-filter intents score on intent alone), plus per-field accuracy. Missing predictions are never scored, and a model that is not run is "not measured".

| Method | Exact | EN | NL | Valid JSON | Source |
|---|---|---|---|---|---|
| Rule parser (`heuristic.py`) | 0.931 | 0.972 | 0.889 | 1.00 | measured, host |
| Qwen3 1.7B Q8_0, 5-shot, grammar-constrained JSON | 0.250 | 0.222 | 0.278 | 1.00 | measured, host |
| Gemma 3 1B / 3n, Llama 3.2 1B, SmolLM3 | | | | | **not measured** |

Qwen3 1.7B per-field accuracy on filter queries: intent 0.79, people 0.80, place 0.86, date_from 0.73, date_to 0.68, tags 0.71, exclude_tags 0.89, unresolved 0.56. The common failures are labelling plain searches as `question` (it did this even for "sunset over the sea"), putting unknown names in `people`, and off-by-one date ranges ("yesterday" ended on today). Host latency: median 2.0 s and max 5.8 s per query with about 420 prompt tokens and 26 generated tokens (measured, 4 threads, Xeon).

**Caveats that matter.**
- **The rule parser's score is optimistic.** The same seat wrote the set and the parser. It shows rules cover this vocabulary, not that they cover real phrasing.
- **The model got one untuned prompt on purpose.** Tuning against this set would overfit it. A stronger prompt, a bigger model or a fine-tune could score much higher; this run proves only that a naive small-model prompt is not enough.
- One model run only (n=72, greedy decoding), so differences of a few points are noise.
- Q8_0 on a desktop CPU is not the quantisation or runtime a phone would use.

## Candidates (screen)

| Model | Params | Licence (HF metadata) | Fit to ATLAS | Status |
|---|---|---|---|---|
| Gemma 3 1B IT | 1.0B | `gemma`, Gemma Terms of Use, gated | LiteRT `.task`/`.litertlm` builds exist (litert-community/Gemma3-1B-IT, int4 and int8), so the on-device runtime is mature. Multilingual claim to be checked for Dutch | Not measured |
| Gemma 3n E2B IT | 5.4B raw, smaller effective | `gemma`, gated | Larger and slower; multimodal capability that ATLAS does not need for filters | Not measured |
| Qwen3 1.7B | 2.0B incl. embeddings | `apache-2.0`, not gated | Clearest licence. Multilingual per its card (Dutch not verified here) | **Measured** (above) |
| Llama 3.2 1B | 1.2B | `llama3.2` community licence, gated | Licence has an acceptable-use policy and attribution terms; Dutch is not among its officially supported languages | Not measured |
| SmolLM3 3B | 3.1B | `apache-2.0`, not gated | Larger than the others; card lists no Dutch | Not measured |

## Runtimes (screen, nothing integrated)

ATLAS already ships `com.microsoft.onnxruntime:onnxruntime-android:1.23.2` (arm64-v8a and x86_64, minSdk 29; `app/build.gradle.kts`). Any new inference runtime is a dependency change in that **protected** file, so Lukas must merge it. Facts below come from the runtimes' public documentation as I know it and were **not** re-verified or run in this unit; unknowns are labelled.

| Runtime | What it needs on Android | Models and formats | Viability for ATLAS | Open unknowns |
|---|---|---|---|---|
| **ONNX Runtime GenAI** (`onnxruntime-genai`) | A new AAR beside the ONNX Runtime ATLAS already ships, and the two must be version-compatible. Protected Gradle change | ONNX exports of Qwen, Llama, Gemma and Phi families | Best fit for tooling reuse: same runtime family, same asset pipeline as the SigLIP towers. Constrained JSON output is not assumed | AAR size and ABI set, whether it coexists with ORT 1.23.2, which candidate exports exist for Dutch-capable models, speed on the S24+ |
| **llama.cpp** (JNI) | A native build (NDK, CMake) or a community AAR; new native code for arm64. Protected Gradle change | GGUF, including Q4 and Q8; the widest model coverage; grammar-constrained JSON (used in this host run) | Most flexible and the only runtime measured here (host). Grammar output matches the filter schema well. Heaviest integration: native build and JNI wrapper to maintain | NDK build cost in CI, APK size, thermal behaviour, GPU or NPU use on the handset |
| **LiteRT-LM / MediaPipe LLM Inference** | A Google AAR (MediaPipe tasks genai or LiteRT-LM). Protected Gradle change | `.task` / `.litertlm` bundles; Gemma 3 1B bundles exist in int4 and int8 (litert-community) | Easiest path for Gemma, with vendor-tuned kernels. Narrower model choice; JSON constraint support not assumed | Whether the AAR conflicts with ORT, API stability of the LiteRT-LM successor, Dutch quality of Gemma 3 1B, Gemma terms for bundling |

No runtime was installed on a handset, and none is recommended yet: the spike in the next steps picks one only if the model earns it. If a model clears the bar, prefer reusing ONNX Runtime GenAI if an adequate export exists, because it avoids a second inference stack; otherwise decide between llama.cpp and LiteRT-LM on the S24+ measurements.

**Redistribution and weights.** The Gemma and Llama licences are custom terms with use restrictions, not Apache-2.0, and both models are gated. Qwen3 and SmolLM3 are Apache-2.0 per their metadata (full terms and any bundled-data notices still to be read). **The existing weights decision does not cover any of these.** The OD-1 owner decision ([#467 comment](https://github.com/LPStruiksma/atlas-gallery/issues/467#issuecomment-6990241396)) allows a post-install download only for HyperSwap 1a 256 and InsightFace w600k_r50, and explicitly not for other restricted models without a new decision. A post-install download of any language model is therefore only a **proposal**. Permissive weights (Apache-2.0) still need the delivery and approval decision for a new download path; gated or custom-licence weights (Gemma, Llama) additionally need that model's terms read and a candidate-specific owner decision. Nothing is downloaded or bundled by this unit.

**Estimated S24+ cost (not measured).** Assumptions: decode on CPU is memory-bandwidth-bound; the handset sustains roughly 10 to 20 GB/s effective for LLM decode (below the chip's peak); int4 weights; the fixed prompt is cached so only the query is prefilled. Tokens per second is then bandwidth divided by weight bytes.

| Model (int4 estimate) | Weights | RAM incl. KV cache and runtime | Decode tok/s | 30 generated tokens |
|---|---|---|---|---|
| Gemma 3 1B | about 0.6 GB | about 1.0 GB | 15 to 30 | 1 to 2 s |
| Llama 3.2 1B | about 0.8 GB | about 1.2 GB | 12 to 25 | 1.2 to 2.5 s |
| Qwen3 1.7B | about 1.1 GB | about 1.6 GB | 9 to 18 | 1.7 to 3.3 s |
| SmolLM3 3B | about 1.8 GB | about 2.4 GB | 5 to 11 | 2.7 to 6 s |

Cold start (loading weights) is likely several seconds and should happen lazily, never on app start. Which S24+ chip the handset has (Exynos or Snapdragon in different regions) changes the numbers. ATLAS already holds roughly 0.4 GB of vision models plus the planned 290 MB text tower (NLS-1), so memory must be budgeted against the existing indexer; Lukas has said APK size is not a constraint, so speed, RAM and heat decide.

## Go / no-go per use

| Use | Verdict | Why and prerequisite |
|---|---|---|
| Query to filter | Conditional: run an S24+ spike | Rules cover the common cases at near-zero cost. Spike only if a larger real-phrasing set shows rules failing where a model wins. Needs a native-checked Dutch set, TAGS-0 vocabulary as grammar and a handset measurement. Fall back to rules whenever the model output fails validation |
| Story titles | Defer | Needs caption or tag inputs (CAP-0, TAGS-0) and a quality rubric; low risk because a template title is always available |
| Metadata Q&A | No go for a model | Answer from the index; parse to filter plus aggregate |
| Caption-based photo Q&A | Defer | Depends on CAP-0 |

## Privacy and offline rules (unchanged)

Inference would run only on the device. No query, title, caption or metadata leaves the phone; no cloud call at runtime. If a model is ever delivered after install, that is a separate owner decision (above), and the download would be explicit, never silent. Model output is never executed: it is parsed, validated against the schema and vocabulary, and discarded if invalid.

## Next steps (not done here)

1. A larger, native-checked EN/NL set from real phrasing (owner-supplied, kept off GitHub), scored on the host with the same harness.
2. Run the remaining candidates through `tools/llm_bench/run_model.py` (Gemma 3 1B needs Lukas's gated-licence acceptance; Llama likewise).
3. Only if a model clearly beats rules: an S24+ spike measuring RAM, decode speed, heat and cold start, then independent review.

Reproduce: `python3 tools/llm_bench/run_baseline.py`; model: `python tools/llm_bench/run_model.py --gguf <Qwen3-1.7B-Q8_0.gguf> --name qwen3-1.7b-q8_0` (GGUF SHA-256 `061b54daade076b5d3362dac252678d17da8c68f07560be70818cace6590cb1a`, from Qwen/Qwen3-1.7B-GGUF, Apache-2.0; llama-cpp-python 0.3.37).
