# AIE-0 — AI photo-editing providers and first integrations

**Research snapshot:** 2026-11-15 (UTC); parent [#183](https://github.com/LPStruiksma/atlas-gallery/issues/183), unit [#185](https://github.com/LPStruiksma/atlas-gallery/issues/185). **Decision status:** recommendation, not a provider performance validation. No paid API calls, live inference, account creation, personal media, credentials, uploads, or GPU rentals were used. This is a public-document and model-card review only. Re-check service terms, model availability, quotas and price on the exact deployment date.

**Product priority order:** (1) accurate instruction/region editing; (2) original/face/scene fidelity; (3) low-friction UX; (4) freedom for lawful personal edits (measure provider refusals separately); (5) model choice, performance and cost. Background indexing, search and People remain on-device. Only the user-selected photo, plus expressly selected mask/references, may be sent to the chosen provider. No silent fallback or automatic upload. The ordinary Samsung/system Edit pencil is unaffected.

## Evidence and uncertainty

- **Documented** = published API contract/pricing or model-card fact; **claimed** = vendor/model-author quality claim not reproduced; **hypothesis** = proposed benchmark/runtime estimate, **unknown** = insufficient public evidence. A capability is *not* guaranteed to work reliably merely because the API accepts the corresponding field.
- **Resolution:** output size limits are usually model/endpoint limits, not a guarantee that a high-resolution photo is preserved pixel-for-pixel. **Mask:** a provider may accept a mask but treat it as soft guidance rather than a hard unchanged-region fence. **Multiple candidates:** may mean repeating calls, not an API parameter. **Multi-turn:** may be a server-managed conversation or an ATLAS-managed chain of one-shot edits.
- **Costs** are USD list examples excluding tax, input-image tokens when separately metered, text/thinking, retries, moderation, bandwidth, image storage, and idle GPU time unless specifically stated. No automatic spend is authorized.
- **Rate/regions/privacy:** account tier, endpoint and region affect availability. Where not evidenced at a published per-model level, the map explicitly says **verify at onboarding** rather than asserting worldwide coverage, fixed retention or zero training.
- Source codes below refer to the dated, primary URL list in [Sources](#sources). Published evaluation figures are *not* measurements made for ATLAS.

## Hosted capability matrix

Legend: **Y** published interface; **P** partial or application-managed; **?** not verified for this exact endpoint; **N** unsupported by the documented operation. The price column is a *single output* example, not always the all-in cost.

| Provider/model, endpoint | Instruction / mask / references | Multiple candidates / multi-turn | Output resolution; sync, async, streaming | Example unit price and evidence |
|---|---|---|---|---|
| **OpenAI GPT-Image-2.6 Sunburst** (Image Edits; GPT-Image-2.5 Sunburst is a documented alternative) | Y / Y (mask is **soft prompt guidance**, not exact pixel fence) / Y (up to 16 input images on image edits) | Y (n=1–10) / Y via Responses image-generation tool; one-shot Image Edits can chain client-side | 2.5: recommended 1024² or 1536×1024; custom up to 8,294,400 pixels and 3840 px edge; 2.6 endpoint/version options to verify. Synchronous final or partial-image streaming (0–3 partials) | **Token-metered**, not a flat edit fee: GPT-Image-2.5/2.6 published standard image input $8/M, image output $30/M, text input $5/M; compare request usage [O1–O3] |
| **Google Gemini 3.1 Flash Image** (`gemini-3.1-flash-image`); 3 Pro Image for quality comparison | Y / ? (not a pixel-precise mask endpoint) / Y (image inputs, multi-image prompts; count varies) | P (ask for more, or repeat) / Y conversational context | 0.5K, 1K, 2K or 4K; `generateContent` returns final image; Batch API is async; no image-partial streaming contract verified | $0.067/1K, $0.101/2K, $0.151/4K output, plus input/text; 3 Pro Image $0.134/1K–2K, $0.24/4K [G1–G3] |
| **Black Forest Labs FLUX.2 [pro] / FLUX.2 [max]** and **FLUX 3 Image** API | Y / ? (not equivalent to pixel-locked masks) / Y on supported multi-reference endpoints | P / P (repeat with previous output) | FLUX.2 [max] advertises up to 4 MP; FLUX 3 offers size tiers incl. 4K; provider job submission/polling, not assumed token stream | FLUX.2 [pro] card starts $0.03 plus per-MP/input components; [max] starts $0.07; FLUX 3 1K **list** $0.048 after expired Oct promotion (verify live card) [B1–B2] |
| **ByteDance Seedream 5.0 Pro / 5.5** (BytePlus ModelArk) | Y / P (coordinate/box/marker-guided edits; no universal binary mask contract) / Y (up to 10 references on newer endpoints) | P (single call or sequential mode differs by version) / P | 5.x selectable 1K–2K+ sizes, version-specific; 5.0 Pro supports layered outputs. Stream/async depends on API version; 5.0 Pro docs say no streaming output | BytePlus 5.0 Pro: $0.045 output <=1.5K or $0.09 larger, first input free, each further input $0.003. 5.0 Flash: $0.018/output. 5.5 price **not verified** [D1–D3] |
| **Ideogram 4.5 Precise Edit** | Y / Y (optional mask and context window) / Y (optional references) | P (repeat, default sync or async jobs) / P | Whole-image edit up to 4 MP; for 4K/8K originals, *regional* context window edits preserve full source dimensions and copy outside region unchanged; sync, optional async/webhook; no stream confirmed | API Generate/Precise Edit charged by selected quality; e.g. 4.5 medium 1K/2K $0.06; confirm `dry_run` quote [I1–I3] |
| **Recraft** image-to-image, inpaint, background | Y / Y on inpaint / ? depends on operation | P / P | Endpoint-dependent, not a generalized high-resolution fidelity guarantee; API async and batch offered; per-edit preview stream not established | $0.04 image-to-image or inpaint, $0.01 background removal [R1] |
| **Stability AI** Edit (Inpaint, Erase, Search/Replace, Relight) | Y per operation / Y for Inpaint / ? | P / P | Operation-specific size/response and no verified image stream | 5 credits ($0.05) for Inpaint/Search Replace, 8 credits ($0.08) for background relight; 1 credit=$0.01 [S1] |
| **fal.ai / Replicate hosting** (e.g. Qwen-Image-Edit-2511, Seedream) | Underlying model + host schema, not a single cross-model contract | Host queue and polling; batch/candidate support varies | Region, size, masking and streaming depend on exact model ID/version; temporary hosted URLs must be fetched into an app-private candidate | Replicate Qwen-Image-Edit-2511 is advertised $0.03/output. fal Seedream 4 Edit $0.03/output; **do not** treat these as universal host prices [H1–H2] |

**API choice detail:** Start OpenAI via the direct `/v1/images/edits` contract for one selected source and prompt; the Responses tool is an optional *different* integration for conversational state. Image edits accept JPEG/PNG/WebP source files (under 50 MB each for GPT Image models); masks must match dimensions/format and include alpha. Sending refs from ATLAS is a separate, explicit user action. The spec gives no hard guarantee that masked pixels outside the region are bit-identical [O1–O2]. For high-res local edits, Ideogram's scoped region is the strongest *documented pixel-copy* behavior here, but not independently benchmarked [I1–I2].

## Hosted privacy, operating and geographic constraints

| Provider | Rate limit / region | Retention and training | Practical gate |
|---|---|---|---|
| OpenAI API | Image-per-minute or token-per-minute limits depend on model, account tier, and verification; supported countries/territories and model eligibility must be checked before enabling | API inputs/outputs are not used to train models by default. Abuse-monitoring retention is ordinarily up to 30 days; qualifying Zero Data Retention requires approval and may have exceptions, including potential CSAM review [O4] | Do not claim 'nothing stored' from default API terms. Check org verification, current model availability, retention settings and supported region |
| Gemini API | RPM/TPM/RPD and image RPM vary by project/model/tier; not a single fixed global quota [G4] | Paid-tier inputs/outputs are not used for product improvement. Google's abuse-monitoring terms describe 55-day prompt/context/output retention; separate ZDR/Interactions, File API and caching provisions exist. Unpaid service terms differ [G5–G6] | Prefer stateless paid `generateContent` (later, with owner approval); avoid unintentional Files API persistence and search grounding |
| BFL | Model availability and output/job caps depend on API account and geography; verify before shipping | Privacy policy does **not** give a universal fixed deletion window; says retained as needed for service/legal/safety. No blanket no-training guarantee established by these sources [B3–B4] | Verify DPA, subprocessor geography and data deletion before sending private imagery |
| BytePlus/Seedream | Availability/pricing are region/platform specific (ModelArk endpoint shown in AP-Southeast); tier and regional terms require verification | No evidence for a blanket no-training or uniform retention period across ModelArk/fal proxies | Treat direct ModelArk and fal partners as different processors, require separate privacy review |
| Ideogram | Default **10 in-flight** requests; enterprise negotiated [I1] | Full API image retention and training specifics not established in the reviewed endpoint docs | Verify API agreement, deletion, region and EU availability |
| Recraft / Stability | Rate limits and supported regions depend on accounts/operation; not verified here | No fixed per-upload retention / training carve-out established in the reviewed public pricing sources | Require explicit disclosure and provider-specific assessment |
| fal.ai / Replicate | Each hosted model/version has separate limits and geographic processing; async URLs/queue behavior vary | Replicate's Qwen-2511 model page says inputs/outputs are not retained or used for training **for that deployment** [H1], not a guarantee for all models/third-party logs | Verify URL expiry, partner moderation and storage semantics for each deployed model |

All hosted routes are opt-in per edit. Never let SDKs upload the full photo library, bulk previews, EXIF GPS/location by default, address book, or background People data. Image-generation APIs typically return new pixels, not a lossless edit of camera RAW/HEIC metadata: save a *copy*, with lineage stored locally (#189).

## Open-weight capability matrix

Open weights means downloadable parameters; **not** automatic commercial permission, low hardware costs, no refusals, or absence of downstream host filtering. Here “mask” is **native exposed mask** versus optional ComfyUI conditioning/workflow; “multi-turn” is primarily repeated invocation.

| Model | Instruction / native mask / refs / multi-candidate / multi-turn | Resolution; sync/jobs/streaming | Licence for self-hosted personal use; VRAM and latency |
|---|---|---|---|
| **Qwen-Image-Edit-2511** (20B, Apache-2.0) | Y / ? native hard mask / Y (2509 family documented best 1–3 input images) / Y via repeated samples / P chain | Reference Diffusers returns images synchronously; ComfyUI/custom server can make queued jobs and progress; resolution and quality depend on pipeline, not a single fixed service limit | **Apache-2.0** for weights; personal and commercial use possible subject to laws/third-party dependencies [Q1–Q3]. 20B BF16 transformer weights alone roughly 40GB before other modules, activations and overhead: plan an **80GB GPU baseline**, or benchmark quantization/offload on 48GB. **No measured ATLAS latency** |
| **FLUX.1 Kontext [dev]** (12B) | Y / ? dedicated hard mask / Y character/object references / Y samples / Y iterative edits (by chaining) | ComfyUI/Diffusers; ~1MP workflows typical, no publisher-guaranteed max or latency across runtimes | **FLUX.1 [dev] Non-Commercial License**: personal/noncommercial model use; **commercial outputs allowed under terms** does not grant commercial hosting/deployment rights. A commercial integration needs separate legal/license review; plan >=24GB with optimizations, >=48GB comfortable **planning** rather than measured minimum [F1] |
| **FLUX.2 [klein] 4B** | Y / ? / Y multiple references / P / P | ComfyUI/Diffusers inference, async jobs via hosted service, no native stream requirement | **Apache-2.0** weights, publisher advertises ~13GB VRAM, suited to 16–24GB GPU; end-to-end timing **not measured** [F2] |
| **Step1X-Edit** | Y / ? / ? native multi-ref / P / P | Original repo publishes H800 timings at 512/786/1024 with 28 steps; not an end-to-end service SLA | **Apache-2.0** [T1]. Official H800 at 1024: 49.8GB BF16/22s, 34GB FP8/25s, or 18GB FP8+offload/51s; vendor recommends 80GB for efficiency. **These are vendor timings**, not a prediction for rented A100 [T2] |
| **OmniGen2** | Y / ? / Y in-context references / P / P | Diffusers; image output with workflow-dependent size and queue | **Apache-2.0**; publisher says ~17GB VRAM (RTX 3090 class), CPU offload possible; latency depends on GPU/workflow [M1] |
| **HiDream-E1.1** | Y / ? / ? / P / P | 28 steps and dynamic ~1MP output in author repo; original E1 Full 768² | **MIT** on own code/transformer; **not a blanket MIT for the complete pipeline**: includes Llama 3.1 text encoder under its separate community licence, T5 and Apache VAE. VRAM/latency need measured deployment; no verified one-GPU floor in these sources [X1–X2] |

**Other current watchlist, not yet selected:** newer community image editors and FLUX variants may outperform these at specific tasks. They should enter the benchmark only when weights, exact licence, reproducible runtime, masks, and maintenance status are known. Avoid labelling a model “uncensored” simply because the repo offers a safety-checker toggle.

**GPU cost baseline (not inference evidence):** Runpod published rates updated **2026-11-12** include Community RTX 4090 24GB $0.34/h, A40 48GB $0.35–0.59/h by tier, A100 80GB from $1.19–1.39/h, and Secure A100 from $2+/h. Availability, disk, network, startup, memory and billing granularity vary [GPU1]. Benchmark on the *exact* rented GPU with cold-start, source decode, safety/queue, output transfer and P95; the following latency assumptions are merely planning scenarios.

## Content-policy and refusal matrix — separate from quality

**Lawful adult/consensual intimate, political, artistic violent and unconventional requests** are relevant *test strata*, never blanket promises. All providers forbid unlawful abuse and CSAM; policy and actual refusal probabilities differ, including for legal content. Do **not** add ATLAS-side category censorship to the local gallery; honor applicable law and the selected provider's terms. Record a provider refusal or technical failure honestly; **never retry on a different engine without the user's explicit choice**.

| Route | Adult / consensual intimate | Political / public figures | Violence / unconventional art | Documented controls and uncertainty |
|---|---|---|---|---|
| **OpenAI API** | Provider safeguards and usage policies apply; lawful adult content is **not guaranteed** to pass | Deceptive likeness or political manipulation has policy restrictions; benign historical/artistic edits are not universally banned | Harmful violent/illegal content restricted; even lawful stylization may be refused | Image editing can return safety refusals; provider applies model-level guardrails; no ATLAS promise to override. Consult the **in-force** usage policy; a page with future effective date is not current law [P1] |
| **Google Gemini** | Sexually explicit is an adjustable *probability filter* category, **but** prohibited uses/core protections remain | Political misuse/deception restricted by broader prohibited-use policy; ordinary lawful political imagery needs empirical testing | Dangerous/hate/harassment filters adjustable within terms; core harms cannot be disabled | `finishReason=SAFETY` and `promptFeedback.blockReason` give structured signals. Lowering filters ≠ guarantee of acceptance or freedom from review [G7–G8] |
| **BFL FLUX hosted and licensed weights** | Explicit restrictions on nonconsensual real-person intimate images; consent conditions are material | Prohibits realistic disinformation, voter manipulation, campaign misuse | Unlawful harms prohibited; may moderate prompts, uploaded images and outputs | 2026-08 policy says moderation may apply; open-weight licence/use policy also matters [B4–B5] |
| **BytePlus/Seedream, Ideogram, Recraft, Stability, fal, Replicate** | **Unknown per-category refusal boundary** in reviewed technical sources; do not infer permission from an API accepting images | Applicable host/platform/deployment terms may constrain person/political edits | Moderation and reporting vary by underlying model and host | Obtain current policy text and error schema before adapter ships; host-specific moderation can be stricter than model weights |
| **Self-hosted Qwen/Step1X/OmniGen2/FLUX.2 klein/HiDream** | No hosted API policy filter is inherent, but model training can produce refusals/degradation, and licences/laws still apply | Same; do not enable impersonation, nonconsensual or unlawful use | Quality and refusal behavior must be measured on consented synthetic fixtures | Do not claim zero refusals, no bias or legal immunity; a ComfyUI workflow or rented infrastructure may add safety checks |

**Measurement design (AIE-9/#195):** for each lawful, synthetic/licensed stratum record `total`, `attempted`, `accepted`, `policy_refused`, `unknown_refusal`, `capacity_blocked`, `technical_failed`, `timed_out`, `generated`, `quality_accepted`. Keep **refusal rate = policy_refused / attempted** with a separate unknown bucket, and **edit quality conditional on generated outputs**; also report an end-to-end accepted-edit rate on all attempts. Never count a provider refusal as an edit-quality zero or as a network error; preserve error codes with no private prompts/photos in logs. Include ordinary object removal, region changes, typography, faces on fictional figures, non-erotic adult figure studies, political *non-persuasive* artwork, stylized violence, and unconventional fantasy, with explicit legal/consent review before any real data testing. Do not send prohibited content just to probe filters.

## Quality evidence

These published benchmarks ask different questions, at **different dates and versions**; none validates current 2.6 Sunburst/FLUX 3 on Samsung-camera originals. No independent ATLAS quality ranking has been established.

| Evidence | What it shows | Limitation for ATLAS |
|---|---|---|
| **GEdit-Bench**, Step1X paper/repository (2025); 2026 updates on public leaderboards [E1–E2] | Real user edit-instruction categories with semantic/structural/low-level axes. A 2026-05 secondary leaderboard snapshot lists Qwen-Image-Edit-2509 81.74 and -2511 80.21 on a particular aggregate | “Better overall” depends on mix and metric; same table shows different rank per semantic/structure dimension. Not comparable to current models absent from that run |
| **ImgEdit**, public 2026-06 secondary board [E3] | Evaluates extract/add/style/background/adjust/replace/remove/hybrid/action. Board reports Qwen-2511 overall 4.07, GPT Image 1 High 4.20, Seedream 4.0 4.18 | Older GPT/Seedream variants; 5-point categories weighted by this board; does not prove current 2.6 or Gemini ranking |
| **KRIS-Bench**, NeurIPS 2025; updates through 2026-05-06 [E4] | 1,267 annotated tasks, 22 task types on knowledge-based factual, conceptual and procedural reasoning. Historic GPT-4o overall 80.09 | Knowledge edits are not a direct face-identity or untouched-pixel benchmark; scores from older model versions cannot rank modern candidates |
| **Ideogram Precise Edit**, vendor API documentation [I1–I2] | Exact outside-context pixel-copy behavior is an **architectural API claim**, testable as `outside_mask_mismatch == 0` | Does not prove the model faithfully edits *inside* the area; must independently validate output metadata, edges, blend and source dimension preservation |
| **Model cards** Qwen/FLUX/Step1X/OmniGen2/HiDream [Q1,F1,T1,M1,X1] | Qualitative samples, model-owner results, reported VRAM/inference claims | Not blinded cross-provider evidence; should form hypotheses only |

**First no-cost benchmark artifact:** #195 should ship a deterministic offline corpus/scorecard, then gate live paid runs on the owner. Use prompt adherence (task success), localized outside-mask drift (pixel and SSIM/LPIPS), face/character identity where safe/synthetic, text correctness, resolution retained, no halos, P50/P95 end-to-end latency, $/generated and $/accepted, and refusal/capacity taxonomy. Blind model identity during scoring; compare equivalent target sizes and prompts. Evaluate edit preservation after sequential 1, 2 and 3-turn chains; rerun with original vs last candidate to quantify compounding drift.

## 100-edit benchmark budgets

**Scope:** 100 **one-output attempts** on 100 synthetic/licensed photos at ~1K unless noted; not 100 accepted edits. No retries, taxes, upload/download/network, batch discounts, moderation retries, storage or support. Figures are **planning envelopes, not quotations**. A 100-*accepted*-edit trial costs `100 × cost_per_attempt / observed_acceptance_rate` plus retries/overhead. An acceptance rate cannot be assumed.

| Route / example configuration | Public price basis | Arithmetic for 100 attempts | Status |
|---|---|---|---|
| OpenAI GPT-Image-2.6/2.5 Sunburst standard | Image $8/M input tokens, $30/M output; text input $5/M [O3] | **$17.53 hypothetical** if each request uses **3,000 image-input**, **5,000 image-output** and **250 text-input** tokens: 100×(3000×8 + 5000×30 + 250×5)/1e6 = $17.525. Real bill can be much lower or higher; obtain live token usage first | Assumption, **not** published per-image price |
| Gemini 3.1 Flash Image 1K | $0.067 image output + $0.50/M input-image tokens [G2] | **~$6.76** = 100×($0.067 + 1120×$0.50/1e6), plus text/thinking | Published 1K output rate + assumed 1120 input image tokens |
| Gemini 3 Pro Image 1K/2K | $0.134 output, ~$0.0011 per input image [G2] | **~$13.51**, plus text/thinking | Published approximate |
| FLUX.2 [pro] 1MP | BFL $0.03 base and $0.015/MP components as currently advertised [B1] | **At least ~$3** base, extra MP/input components unknown until a quote | Lower bound, **not** a final price |
| BytePlus Seedream 5.0 Pro <=1.5K | $0.045 output; 1st input free [D1] | **$4.50** with exactly one input each | Published version-specific |
| Ideogram 4.5 medium | $0.06/output [I3] | **$6.00** | Published tier; use no-cost `dry_run` quote before spend |
| Recraft inpainting | $0.04/output [R1] | **$4.00** | Published operation |
| Stability inpainting | 5 credits at $0.01/credit [S1] | **$5.00** | Published operation |
| Replicate Qwen-2511 | $0.03/output [H1] | **$3.00** | Deployment-specific, NOT self-hosting price |
| **Self-host FLUX.2 klein** on Community RTX 4090 | $0.34/GPU-hour [GPU1], assume **15–60s per edit**, plus 20 minutes startup | **$0.26–$0.68** = (100×15–60s + 1200s)/3600×$0.34; add storage, retries, idle | Hypothetical latency; **unmeasured** |
| **Self-host Qwen-2511** on Community A100 80GB | $1.39/GPU-hour [GPU1], assume **45–120s/edit** + 20 minutes startup | **$2.20–$5.10** = (100×45–120s + 1200s)/3600×$1.39; add storage and cold model load | Hypothetical latency, may require more GPU/quantization; **unmeasured** |

**Owner spending request later (not now):** propose a **hard $40 maximum** for a first **mixed hosted 100-attempt pilot** only once exact provider, quality, image dimensions and account quote are selected. Stop at cap and log actual per-response tokens. A separate, **$10 maximum GPU pilot** would cover the first Qwen/klein validation if an eligible machine is available and the lease can be stopped; these are *proposed caps*, not authority to spend. Larger, multiple-provider 100-edit runs need **individual** caps and consent. No production calls or personal media in CI.

## Recommendations and adapter contracts

### Primary hosted recommendation: OpenAI GPT-Image-2.6 Sunburst

**Why first:** direct image-edit contract with one source, prompt and optional refs/mask; streaming partials; flexible quality/format; separate optional conversation flow. This matches the unstructured user instruction first and makes outcome mapping clear. **Risk:** provider restrictions/refusals, non-pixel-exact masking, potentially higher per-edit token cost, account verification, sensitive-image upload. **Not proven:** superiority over Gemini, Ideogram or Seedream on edited real photos; only a controlled owner-approved benchmark can establish it. Snapshot-pin `gpt-image-2.6-sunburst` once API access is verified; reject unknown or deprecated model IDs. Do not use retired `gpt-image-1` (Oct 2026 retirement).

**Second hosted evaluation, explicitly user-selected (never silent fallback):** **Ideogram 4.5 Precise Edit** for small-region, high-resolution preservation and optional hard outside-context pixel copying. Benchmark its *local change accuracy* and face edge blending. **Gemini 3.1 Flash Image** is a compelling inexpensive conversational comparison ($0.067 per 1K output), but its weaker published mask semantics and Google's abuse-monitoring terms warrant a separate privacy/quality check. **Seedream 5.0 Pro** is a good region-guidance comparator, not a default without EU/retention verification. BFL, Recraft, Stability remain task-specific candidates.

### First open-weight path: Qwen-Image-Edit-2511 behind an ATLAS protocol / ComfyUI proxy

**Why first:** Apache-2.0 weights; strong documented instruction and multi-image editing; 20B, broad community runtime support. Preserve **provider independence** through #193's versioned ATLAS image-edit protocol rather than exposing ComfyUI node graphs or arbitrary untrusted endpoint URLs to the Android app. For constrained 16–24GB cards, **FLUX.2 [klein] 4B** is the first alternate (Apache, ~13GB published VRAM). **FLUX.1 Kontext [dev]** is comparison-only until deployment licence is cleared; don't mistake commercial output rights for model-service rights.

**Adapter separation (input contract proposal, AIE-2/#187 owns code/types):**
`request { source_blob, prompt, optional_mask, optional_user_selected_refs[], target_width/height, provider_id, model_version, request_id, source_lineage_id }`.
Return `capabilities { supports_mask, supports_references, max_references, max_input/output_pixels, multi_candidate, multi_turn, stream, async_job, pricing_type, retention_disclosure }`; `result { candidate_blob, dimensions, actual_cost?, model_version, job_id?, provenance? }`; typed `outcome { completed | policy_refused | unknown_refusal | unsupported_capability | rate_limited | quota_exhausted | timeout | cancelled | provider_error | transport_error }`.
A capability must reflect *tested endpoint behavior*, not family marketing.

**Protocol and operations:** private HTTPS + explicit allowlisted/custom endpoint settings, pinned per-user provider selection, bounded upload/size/request deadlines, no unsafe redirects, no secrets in logs or backups; one selected source (and only opted-in references/mask); secure response download into app-private temporary storage; strip GPS before network transfer unless specifically required; cleanup on cancellation. A generic ComfyUI HTTP/WS server is **not** safe to expose directly: its `/upload/image`, `/prompt`, `/history/{id}`, `/view`, `/ws`, and `/interrupt` have workflow/server-specific semantics [C1]. Put an authenticated, audited proxy in front that validates hashes, size, graph allowlist, execution timeout, single-user jobs, result ownership, storage TTL and cancellation. Use a fake backend in CI (#193), no rented GPU.

**Release/rollout requirements:** default no network call until a user chooses AI Edit and Generate, no background indexing uploads. Display current provider, whether a remote upload will occur, and estimated cost (when knowable); allow explicit retry or change provider; keep every candidate non-destructive and preserve ordinary Edit pencil. Provider refusal, timeout and policy errors are visible, never auto-switched to a second provider. No unsupported blanket promise of 'uncensored' editing.

## Implemented adapters

### Google Gemini (#192), 2026-11-18

`featureaiedit/provider/hosted/gemini/GeminiImageEditProvider.kt`, on the shared `EditHttpTransport`. Fake-server tested only: no key, account or live request was used.

- **Wire format** (checked 2026-11-18): `POST https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent`, key in the `x-goog-api-key` header, never the URL. Body `contents[0].parts` = instruction `text`, then the source and any references as `inlineData{mimeType,data}`; `generationConfig.responseModalities=["IMAGE"]` and `imageConfig{aspectRatio,imageSize}`. The reply image is the last non-`thought` `inlineData` part of `candidates[0].content.parts`.
- **Sources:** `ai.google.dev` was not reachable from the build session, so the format was taken from Google's own published code on the same day: the official Python SDK `googleapis/python-genai` (`google/genai/_api_client.py` for host, `v1beta` and `x-goog-api-key`; `models.py` for the `generateContent` path and camelCase field mapping; `types.py` for `FinishReason`, `BlockedReason` and `ImageConfig`) and the official cookbook `google-gemini/cookbook` `quickstarts/Get_Started_Nano_Banana.ipynb` (model ids, aspect ratios, sizes, input-image limits). Re-check [G1]–[G3] before the first live edit.
- **Models:** default `gemini-3.1-flash-image` (Nano Banana 2: up to 14 input images, 1K/2K/4K, ratios 1:8 to 8:1). Also `gemini-3-pro-image-preview` (Pro, preview id) and `gemini-3.1-flash-lite-image` (Lite: 3 input images, 1K only).
- **Honest capabilities:** no mask (no documented pixel-mask input), one candidate, no seed, no multi-turn (ATLAS sends one stateless call), output format chosen by Gemini and reported from the bytes. Requested output size maps to the nearest documented aspect ratio and the smallest `imageSize` tier that covers it.
- **Outcomes:** `promptFeedback.blockReason` and policy finish reasons (`SAFETY`, `IMAGE_SAFETY`, `PROHIBITED_CONTENT`, `IMAGE_PROHIBITED_CONTENT`, `BLOCKLIST`, `SPII`, `RECITATION`, `IMAGE_RECITATION` and similar) → `Refused` with the documented code; no image (`NO_IMAGE`, `IMAGE_OTHER`, text-only `STOP`) → `Failed(UNKNOWN)`, never a fake success; 401/403 → `AUTH`; 429 → `QUOTA`; 5xx and timeouts → retryable. The POST is never replayed. Unrecognized codes are reported as `UNRECOGNIZED` so provider text cannot leak into state.
- **Cost:** list prices from [G2] (2026-11-15) rounded up to whole cents: Flash 7/11/16 US cents for 1K/2K/4K, Pro 14/14/25; Lite unknown (null).
- **Large replies (#441):** Gemini returns the image base64-encoded inside the JSON reply (about 2 MB at 1K, about 33 MB at 4K). The adapter spools the reply to a hidden app-private file with a 40 MB cap (`EditHttpTransport.MAX_REPLY_CEILING`), parses it with `HostedJsonFile` (strings over 64 KB stay in the file) and decodes the image straight to its output file, so heap use stays near the 64 KB buffers whatever the size. The image is then verified by its header before it is kept. Still unverified: Google documents an invalid key as HTTP 400 in places, which the transport reports as `INVALID_REQUEST`, not `AUTH`, because it hides reply bodies.

## Owner gates

1. **Now:** docs, provider interface/mock, offline scoring and fake-server contracts may proceed without payment. No account sign-ups or paid calls. This unit does not change permission, credentials, app code, or core UI.
2. **Before any live API/GPU benchmark:** Lukas must approve provider/key handling, test data and each hard spend cap, even if the estimated 100-edit bill is low; no use of real personal media in dev/CI. A by-user key on phone is a *later explicit* configuration, not this work's responsibility.
3. **Before enabled uploads:** scoped privacy-spec amendment distinguishing user-requested edit from background scanning, EU/region/data retention assessment and a decision on direct BYOK vs optional relay; owner confirmation of user-facing upload disclosure and whether GPU endpoints are owner-run.
4. **Protected Android manifest:** #191's INTERNET permission is separately owner-gated; Lukas presses Merge for a protected-path PR. This research PR touches only `docs/ai-edit/**` and `test/additive-regressions/**`.
5. **Acceptance:** owner may prefer Ideogram after quality trials; the recommendation is reversible by switching a provider adapter and its default setting. Actual initial provider is not a mandate to restrict lawful local editing.

## Sources

All links opened/reviewed as public information on **2026-11-15** unless a publication/update date is noted. No user-specific price, quota, DPA or SLA was retrieved. Dates on evaluation boards are board-supplied dataset/snapshot dates.

**Official APIs, pricing and data policies**

- [O1] OpenAI image generation and edits guide (includes 2.6 and 2.5): https://developers.openai.com/api/docs/guides/image-generation
- [O2] OpenAI Image Edits request schema: https://developers.openai.com/api/reference/python/resources/images/methods/edit
- [O3] OpenAI API pricing (standard/batch token rows): https://developers.openai.com/api/docs/pricing
- [O4] OpenAI API data controls, abuse retention and exceptions: https://developers.openai.com/api/docs/guides/your-data
- [P1] OpenAI usage policies (check the **effective date**; public page currently advertises the next policy effective **2026-11-20**): https://openai.com/policies/usage-policies/
- [G1] Gemini 3.1 Flash Image model card: https://ai.google.dev/gemini-api/docs/models/gemini-3.1-flash-image
- [G2] Gemini API pricing, 3.1 Flash Image and 3 Pro Image: https://ai.google.dev/gemini-api/docs/pricing
- [G3] Gemini image-editing guide: https://ai.google.dev/gemini-api/docs/image-generation
- [G4] Gemini API limits by project and tier: https://ai.google.dev/gemini-api/docs/rate-limits
- [G5] Gemini abuse monitoring: https://ai.google.dev/gemini-api/docs/usage-policies
- [G6] Gemini paid-service ZDR and interaction/cache exceptions: https://ai.google.dev/gemini-api/docs/zdr
- [G7] Gemini adjustable vs core safety filtering: https://ai.google.dev/gemini-api/docs/safety-settings
- [G8] Gemini safety guidance: https://ai.google.dev/gemini-api/docs/safety-guidance
- [B1] Black Forest Labs live pricing, FLUX.2/FLUX 3: https://bfl.ai/pricing
- [B2] BFL API docs index: https://docs.bfl.ai/
- [B3] BFL privacy policy (revised 2026): https://bfl.ai/legal/privacy-policy
- [B4] BFL usage policy (last revised 2026-08-04): https://bfl.ai/legal/usage-policy
- [B5] BFL responsible development and moderation policy (2026-08-01): https://bfl.ai/legal/responsible-ai-development-policy
- [D1] BytePlus ModelArk pricing: https://docs.byteplus.com/en/docs/modelark/model-pricing
- [D2] Seedream 5.x ModelArk images API: https://docs.byteplus.com/en/docs/modelark/image-generation-api
- [D3] Seedream 5.0 interactive edits: https://docs.byteplus.com/en/docs/modelark/seedream-5-0-pro
- [I1] Ideogram API overview and concurrency: https://developer.ideogram.ai/ideogram-api/api-overview
- [I2] Ideogram 4.5 Precise Edit, high-res context window: https://developer.ideogram.ai/api-reference/images/precise-edit/ideogram-4-5
- [I3] Ideogram API pricing: https://ideogram.ai/pricing/?pricing_tab=api
- [R1] Recraft API operation pricing: https://www.recraft.ai/pricing?tab=api
- [S1] Stability edit credits and pricing: https://platform.stability.ai/pricing
- [H1] Replicate Qwen-Image-Edit-2511 price/schema and deployment-specific data policy: https://replicate.com/qwen/qwen-image-edit-2511/api/schema
- [H2] fal Seedream 4 Edit API/pricing: https://fal.ai/models/fal-ai/bytedance/seedream/v4/edit

**Open-weight model cards, execution, published quality and compute**

- [Q1] Qwen-Image-Edit-2511 model card (Apache-2.0): https://huggingface.co/Qwen/Qwen-Image-Edit-2511
- [Q2] Qwen-Image-Edit-2509 model card, multi-image capability: https://huggingface.co/Qwen/Qwen-Image-Edit-2509
- [Q3] Qwen official editing examples: https://github.com/QwenLM/Qwen-Image
- [F1] FLUX.1 Kontext [dev] weights and non-commercial licence: https://huggingface.co/black-forest-labs/FLUX.1-Kontext-dev
- [F2] FLUX.2 [klein] 4B weights, 13GB VRAM and Apache: https://huggingface.co/black-forest-labs/FLUX.2-klein-4B
- [T1] Step1X official repository and licence: https://github.com/stepfun-ai/Step1X-Edit
- [T2] Step1X author's H800 VRAM/latency table: https://github.com/stepfun-ai/Step1X-Edit/blob/main/README.md
- [M1] OmniGen2 Apache and 17GB VRAM: https://huggingface.co/OmniGen2/OmniGen2
- [X1] HiDream-E1.1 model card/component licences: https://huggingface.co/HiDream-ai/HiDream-E1-1
- [X2] HiDream E1 repository/1MP dynamic mode: https://github.com/HiDream-ai/HiDream-E1
- [C1] ComfyUI HTTP/WebSocket queue routes: https://github.com/Comfy-Org/docs/blob/main/development/comfyui-server/comms_routes.mdx
- [GPU1] Runpod GPU pricing; publisher updated **2026-11-12**: https://www.runpod.io/pricing
- [E1] Step1X / GEdit-Bench paper and benchmark: https://github.com/stepfun-ai/Step1X-Edit
- [E2] GEdit-Bench aggregate secondary board (2026-05): https://www.sota2.com/research/sota/image-editing-on-gedit-bench-semantic-structure-low-level-metrics
- [E3] ImgEdit secondary board (2026-06): https://www.sota2.com/research/sota/single-image-editing-on-imgedit-benchmark
- [E4] KRIS-Bench original project and 2026-05 update: https://yongliang-wu.github.io/kris_bench_project_page/
