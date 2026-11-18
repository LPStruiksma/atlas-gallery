# Identity Studio: local face and full-head replacement landscape

Unit [#468](https://github.com/LPStruiksma/atlas-gallery/issues/468) (IDS-1), goal [#467](https://github.com/LPStruiksma/atlas-gallery/issues/467). Licence facts live in [LICENCES.md](LICENCES.md); this page cites it as "register".

**Research snapshot:** 2026-11-18 (UTC). Desk research only: public repositories, model cards, papers and vendor docs. No model was run, no weights were downloaded, no personal image was used, no hosted API was called. Quality statements below are **claims by their authors**, not ATLAS measurements; IDS-2 (#469) and IDS-3 (#470) measure.

Evidence labels: **read** = read at the linked source today; **claim** = author or vendor statement, not reproduced; **hypothesis** = our planning estimate, to be measured; **unverified** = source missing or unreachable.

## 1. Findings that change the plan

1. **No open face swapper has a verified permissive chain today.** The two strongest one-shot swappers, HyperSwap (FaceFusion) and InSwapper-128 (InsightFace), are research-only, and both are conditioned on InsightFace's research-only ArcFace `w600k_r50` embedding (read: FaceFusion feeds `embedding_norm` from `arcface_w600k_r50` straight into `hyperswap`, [`face_swapper/core.py`](https://github.com/facefusion/facefusion/blob/decc6c9d8f8e014878624452c6e8fe208726b4ec/facefusion/processors/modules/face_swapper/core.py), FaceFusion 3.9.2). Benchmarking them is fine (research); shipping them in the app needs a licence from the weight owners. This is a future isolated owner gate for IDS-9, not a blocker for IDS-2.
2. **HyperSwap's licence text is not published.** FaceFusion labels the weights "ResearchRAIL" on its docs and in model metadata, but the hosting repo has no licence file and FaceFusion's own `LICENSE.md` holds only the name "OpenRAIL-AS". The generic research-use RAIL template restricts use to "academic or research purposes only" (register).
3. **The Exynos 2400 has no supported public NPU path.** LiteRT's Samsung NPU support lists only Exynos 2500 (E9955) and 2600 (E9965) ([LiteRT Samsung](https://ai.google.dev/edge/litert/next/samsung), read); ExecuTorch's Samsung backend lists the same two ([ExecuTorch](https://github.com/pytorch/executorch/blob/main/docs/source/backends/samsung/samsung-overview.md), read). QNN is Snapdragon-only. The realistic local paths on the S24+ are CPU (ORT + XNNPACK) and, unproven, the GPU (§5).
4. **The open Android port is Snapdragon-first.** FaceFusion Mobile runs HyperSwap on the Hexagon NPU through QAIRT context binaries that "will not load on anything else"; non-Qualcomm phones get a 604 MB ncnn/Vulkan set that has "only ever run on a Snapdragon with the NPU disabled" (read, [model card](https://huggingface.co/AbrahamPJ/facefusion-mobile-models)). It has no licence file, so it is reference reading only.
5. **Judge bias.** Because HyperSwap and InSwapper are trained to hit the `w600k_r50` embedding, scoring them with that same model flatters them. IDS-2 should judge with at least one model that no candidate was conditioned on (AdaFace or SFace), and report each judge separately.
6. **Full-head replacement has no local open model.** GHOST 2.0 and UniBioTransfer are the open head swappers; both are server-class (PyTorch3D/DECA stack; diffusion needing ≥11 GB VRAM) and carry research or non-commercial dependencies. IDS-3 should measure them as a quality bar on a GPU host and measure a local composite honestly (§7).

## 2. Face-swap candidates

| Candidate | Method class | Native input / output | ONNX / LiteRT | Size | Reported quality (claim) | Maintenance | Full head? | Licence tier (register) |
|---|---|---|---|---|---|---|---|---|
| **InSwapper-128** (InsightFace) | One-shot GAN-style generator conditioned on an ArcFace identity embedding (`buffalo_l` only: "otherwise the result will be not normal") | 128×128 ([README](https://github.com/deepinsight/insightface/blob/master/examples/in_swapper/README.md)); FaceFusion upsamples with "pixel boost" tiling | ONNX official; fp16 conversion exists | 555.3 MB fp32, 277.7 MB fp16 | Widely used baseline (FaceFusion and many wrappers ship it); InsightFace now says "This model and demo code is no longer maintained" and points to its paid product | unmaintained | no | C, research-only |
| **HyperSwap 1a / 1b / 1c** (FaceFusion, 2025) | One-shot swapper on the `w600k_r50` embedding (`arcface_128` template); three training variants | 256×256; FaceFusion offers 512, 768, 1024 by pixel boost | ONNX (FaceFusion); QNN and ncnn conversions in FaceFusion Mobile | 402.7 MB each | FaceFusion 3.9.2's default swapper (`default … 'hyperswap_1a_256'`, read in code). No published ranking: FaceFusion Mobile says "FaceFusion publishes none" | active (FaceFusion 3.9.2, commit 2026-10-26) | no | C, research-only (label) |
| **SimSwap** (2020) | ID-injection module on ArcFace features plus weak feature matching | 224 / 256 official ("SimSwap-HQ" 512 noted in README) | PyTorch official; FaceFusion ONNX `simswap_256` | 220.4 MB (ONNX) | Paper centres on attribute preservation; no comparison against HyperSwap read | stale since 2022 | no | B, non-commercial |
| **GHOST 1** (ai-forever, 2022) | AEI-Net (FaceShifter-style) with U-Net attribute encoder and AAD blocks, iresnet100 identity | 256 | PyTorch official; FaceFusion ONNX `ghost_1/2/3_256` (1, 2, 3 AAD blocks) | 515 / 739 / 856 MB (ONNX) | Trains with an extra eye-landmark loss (README downloads an eye detector for it); no comparison against HyperSwap read | repo last commit 2025-02-25 | no | D (Apache-2.0, identity-backbone origin unstated) |
| **AlphaFace** (2026, arXiv [2601.16429](https://arxiv.org/abs/2601.16429)) | GAN swapper trained with VLM + CLIP semantic contrastive losses, "real-time" | 256 (FaceFusion `alphaface_256`) | PyTorch official; FaceFusion ONNX | 555.6 MB (ONNX) | "surpasses state-of-the-art methods in pose-challenging cases" (abstract) | new, last commit 2026-03-28 | no | D (MIT code, weights unlicensed) |
| **REFace** | Diffusion inpainting (SD 1.4 / Paint-by-Example), 50 DDIM steps | 512 | PyTorch only | multi-GB | Paper reports strong identity (not read in detail); slow | low | partial (face region) | D / NC |
| **UniBioTransfer** (2026, arXiv [2603.19637](https://arxiv.org/abs/2603.19637)) | Unified diffusion model with mixture-of-experts (BioMoE); tasks face, hair, motion, head | 512 (LDM) | PyTorch only | multi-GB; "Minimum 11GB VRAM" | Claims to beat task-specific methods on face, hair and head transfer | new (2026-04 HF update); training code not released | **yes** (head task) | D |
| **FaceFusion Mobile** (Android port) | Runs FaceFusion's HyperSwap path on device | 256 + pixel boost | QAIRT context binaries (Hexagon v68 to v81), ncnn fp16 for others | 355 to 361 MB per Hexagon tier; ncnn set 604 MB | 44 ms per 720p frame on Snapdragon 8 Elite NPU, 325 ms on its GPU + CPU path (claim, Snapdragon only) | active (2026-10-22) | no | E (no licence) |
| Generation-style identity (InstantID, PuLID, Arc2Face, InfiniteYou) | Diffusion generation with identity adapters | 1024 class | PyTorch / Diffusers | multi-GB | Good likeness but regenerates the whole image; not an in-place swap | varied | n/a | needs InsightFace `antelopev2`; InfiniteYou model research-only |

Not shortlisted: BlendSwap (1.66 GB, NC label), UniFace and HifiFace-unofficial (unknown licence), the SimSwap 512 "unofficial" conversion (unknown provenance), and FaceFusion's DeepFaceLive "deep swapper" models (per-identity, trained on celebrity datasets; out of scope).

## 3. Supporting stages

**Detection and alignment.** Keep the shipped YuNet (MIT) five-point landmarks. Swappers expect specific templates: `arcface_128` (InSwapper, HyperSwap), `arcface_112_v1` (GHOST, SimSwap), `ffhq_512` (BlendSwap, UniFace), read in FaceFusion's model table. IDS-2 must reproduce each template exactly; a wrong template silently costs identity.

**Masks.** FaceFusion composes up to four masks (box, occlusion, region, area) with `numpy.minimum` then pastes back with the inverse affine (read, `face_swapper/core.py`). For ATLAS:
- Face region: MediaPipe multiclass selfie segmenter (face skin, hair, body skin, clothes, background; Apache-2.0, 16.4 MB) is the licence-clean choice. BiSeNet face parsers give finer labels (brows, lips, eyes) but their weights are trained on CelebAMask-HQ ("non-commercial research purposes only"), so bench only.
- Hair (full head): MediaPipe hair segmenter (Apache-2.0, 0.78 MB).
- Occlusion (hands, glasses): XSeg is GPL-3.0-labelled and unread; do not use. Use the multiclass "others" and body-skin classes plus the face-parsing bench as the reference.

**Colour and lighting match.** Classical and licence-free: per-channel mean/variance transfer in Lab inside the mask, then feathered (or Poisson) blending at the seam. GHOST 2.0's Blender learns skin-colour transfer and inpaints gaps; that is the head-swap reference.

**Optional restoration.** GFPGAN 1.4 (mixed Apache / NVIDIA / CC BY-NC-SA parts), CodeFormer (S-Lab NC), GPEN (no licence file), RestoreFormer++ (label only). Risk: restorers pull faces toward their own face prior and can **wash out identity**; CodeFormer exposes a fidelity weight for exactly this trade. IDS-2 should run each pipeline with no restorer and with one restorer and report the identity change, not assume restoration helps. FaceFusion Mobile measures GPEN at 8.57 GMAC per face versus the swapper's 31.93 (claim), so restoration is a real cost on CPU.

**Full-resolution output.** The swapped crop is 128 to 1024 px; the photo is up to 50 MP. Paste back only inside the feathered mask so every pixel outside it is bit-identical (goal Done-when 3), as LaMa Erase already does (#459).

## 4. Identity judges

- **Shipped:** SFace int8 (Apache-2.0, 128-d, LFW 0.9932 per upstream) is what ATLAS can use on device, for example to rank candidates or to warn "this does not look like them".
- **Research only, never shipped:** InsightFace ArcFace `w600k_r50` (research-only grant; also the input encoder of InSwapper and HyperSwap, hence biased) and AdaFace (MIT code, R100 WebFace4M weights without separate licence). Score with all three, report each, and pre-register thresholds before the final run (goal Done-when 2).
- Include hard negatives (similar-looking different people) so a judge that rewards "generic face" is caught.

## 5. Acceleration on the Galaxy S24+ (Exynos 2400)

Hardware (read, [Samsung Exynos 2400](https://semiconductor.samsung.com/processor/mobile-processor/exynos-2400/)): deca-core CPU in three clusters ("one large, five mid-size, and four small cores"); Xclipse 940 GPU "built on AMD RDNA 3"; NPU made of GNPU and SNPU blocks with "14.7x improvement in AI performance" over the previous generation. Third-party spec sites report a 17K-MAC NPU and OpenCL 3.0 / Vulkan 1.3 on the GPU (claim, not Samsung documentation).

| Path | Status on Exynos 2400 | Evidence | Verdict for IDS-2 / IDS-9 |
|---|---|---|---|
| **ORT CPU (MLAS)** | Works; already shipped (ORT 1.23.2, LaMa and SFace run here) | in repo | Baseline. Measure fp32 and fp16-weight variants |
| **ORT XNNPACK EP** | In the `onnxruntime-android` Maven package; Arm-optimised float kernels | [ORT XNNPACK](https://onnxruntime.ai/docs/execution-providers/Xnnpack-ExecutionProvider.html) (read). Docs advise setting ORT intra-op threads to 1 and XNNPACK threads to the physical cores | Measure against plain CPU; no new dependency |
| **ORT NNAPI EP** | Present in ORT Android; NNAPI itself "was deprecated in Android 15" | [Android NNAPI](https://developer.android.com/ndk/guides/neuralnetworks) (read). ORT's NNAPI page does not mark the EP deprecated ([ORT NNAPI](https://onnxruntime.ai/docs/execution-providers/NNAPI-ExecutionProvider.html), read) | The only public route to Samsung's NPU driver on this chip. Probe once with an output-equality check; do not build on it |
| **ORT GPU / OpenCL** | ORT's execution-provider list has no OpenCL EP | [ORT EPs](https://onnxruntime.ai/docs/execution-providers/) (read) | Not available |
| **ORT WebGPU EP** | Uses Dawn, which uses Vulkan on Android; shipped as a plugin EP for Python, .NET and C/C++; no Android Maven package listed | [ORT WebGPU](https://onnxruntime.ai/docs/execution-providers/WebGPU-ExecutionProvider.html) (read) | Possible only with our own native build: new native dependency, protected build files, owner gate. Later, if CPU is too slow |
| **LiteRT GPU delegate** | Android backend is "OpenCL + OpenGL"; whether One UI exposes OpenCL to apps on Xclipse 940 is **unverified** (a LiteRT issue reports OpenCL loading blocked on an S24 Ultra, which is the Snapdragon model) | [LiteRT GPU](https://ai.google.dev/edge/litert/next/gpu) (read); [LiteRT-LM #2292](https://github.com/google-ai-edge/LiteRT-LM/issues/2292) (search result) | Needs an on-device probe and a new Gradle dependency (owner gate). LiteRT's GPU benchmark table uses "a Samsung Galaxy S24" without saying which chip, so it is not Exynos evidence |
| **LiteRT NPU (Exynos AI LiteCore)** | Supported SoCs: Exynos 2500, Exynos 2600 only | [LiteRT Samsung](https://ai.google.dev/edge/litert/next/samsung) (read) | Not available on Exynos 2400 |
| **Samsung ENN Delegate** | Proprietary TFLite delegate, shipped as self-extracting archives with a click-through licence; SoC list inside the archive | [ENNDelegate](https://github.com/Samsung/ENNDelegate) (read README only) | Unverified; owner review before anyone accepts the licence |
| **Qualcomm QNN / QAIRT / Hexagon** | "can be used on Android and Windows devices with Qualcomm Snapdragon SOC's" | [ORT QNN](https://onnxruntime.ai/docs/execution-providers/QNN-ExecutionProvider.html) (read) | **Cannot run on Exynos.** Any Snapdragon NPU number (including FaceFusion Mobile's 44 ms) does not transfer to the S24+ |

**What QNN cannot do on Exynos:** everything. QNN's HTP backend targets the Hexagon DSP/NPU, which the Exynos 2400 does not have; QAIRT context binaries are compiled for specific Hexagon architectures and "will not load on anything else". The Exynos NPU is reachable only through Samsung's own stack (NNAPI driver today, ENN delegate, or LiteCore on newer chips).

**Planning hypothesis (to be measured, not a result):** a 256² HyperSwap pass is about 32 GMAC (FaceFusion Mobile's figure). The six large and mid-size cores at realistic sustained fp32 throughput would put one swap in roughly the 0.3 to 1.5 s range on CPU, plus detection, masks and paste-back. Four candidates would then take a few seconds, which fits "3 to 4 candidates" if they are produced one after another with the first shown early. IDS-2 replaces this with measured numbers on the CI emulator and the S24+.

## 6. Ranked shortlist for IDS-2 (#469): local face pipelines

All share: YuNet detection, the swapper's exact alignment template, MediaPipe multiclass face mask (BiSeNet as a bench comparison), Lab colour match, feathered paste-back at full resolution, and an optional restorer ablation. Benchmark all four; the licence column decides what can ship afterwards.

| Rank | Pipeline | Why benchmark it | Ship path if it wins |
|---|---|---|---|
| 1 | **HyperSwap 1a, 1b, 1c** on `w600k_r50` (256, plus 512 pixel boost) | Current open quality ceiling and FaceFusion's default; three variants for free | Research-only today. Needs written terms from FaceFusion **and** a licence for the InsightFace embedding, or a retrained adapter. Isolated owner gate |
| 2 | **GHOST 1, 2-block** on its iresnet100 backbone (256), detector swapped for YuNet | Only swapper whose code and generator weights are Apache-2.0 | Ship candidate once ai-forever confirms the backbone's origin and terms. Size 515 MB fp32 means a download gate (> 60 MB) |
| 3 | **SimSwap 256** official | Non-commercial, not research-restricted, whole project incl. its ArcFace under CC BY-NC 4.0 | Private non-commercial download from the original host with hash pin; download gate |
| 4 | **InSwapper-128** + pixel boost | Industry baseline every reviewer knows; anchors the comparison | Research-only; InsightFace sells licences. Bench reference only |
| watch | **AlphaFace 256** | Newest, pose-robust claim, MIT code | Only after the author states a weights licence |

## 7. Full-head shortlist for IDS-3 (#470)

| Rank | Option | What to measure | Constraint |
|---|---|---|---|
| 1 | **GHOST 2.0** (Aligner + Blender) on a GPU host | Head identity, hair and head-shape transfer, seam quality, failure modes; sets the quality bar | DECA, EMOCA (MPI research-only) and FLAME registration; research probe only; never local |
| 2 | **UniBioTransfer head task** | Same metrics; diffusion quality versus GHOST 2.0 | ≥ 11 GB VRAM; checkpoint licence unverified (likely NC by inheritance). Candidate for an optional self-hosted backend: spend gate |
| 3 | **Local composite**: IDS-2 face swap + MediaPipe hair and multiclass masks + hair-region transfer and LaMa inpainting for gaps | Whether a local approximation is honest about hairline, ears and head shape | All parts tier A except the swapper. Likely weak on head shape; report it as it is, never fold it into "face only" |
| 4 | **Hosted reference-image editors** already in `docs/ai-edit/PROVIDERS.md` (Gemini image models, Qwen-Image-Edit via fal) | Identity retention under a "replace this head with the person in these references" instruction | Upload and spend gates (goal limits); optional, never silent |
| 5 | HeadSwap (LeslieZhoa, MIT code) | Only if 1 to 3 fail | BFM / PIRender dependencies unread |

## 8. Safeguards to carry into the build

- Mark every saved candidate as an AI edit with lineage (meets the research-use RAIL disclosure clause; IDS-10 #476).
- No upstream licence read requires a content checker; ATLAS adds no category filter (goal #467) and keeps the limits it already has: no feature aimed at sexual content or at deceiving people about real persons, no watermark removal.
- An in-app licences screen lists every model that ships or downloads, with its licence and attribution.
- Weights never enter Git or CI caches; downloads come from the original host with a SHA-256 pin, as SFace and YuNet already do.

## Sources

All read 2026-11-18 unless marked.

- InsightFace: [README](https://github.com/deepinsight/insightface/blob/master/README.md), [in_swapper](https://github.com/deepinsight/insightface/blob/master/examples/in_swapper/README.md), [model zoo](https://github.com/deepinsight/insightface/blob/master/python-package/docs/model_zoo.md).
- FaceFusion: [repo at decc6c9](https://github.com/facefusion/facefusion/tree/decc6c9d8f8e014878624452c6e8fe208726b4ec), [licences](https://docs.facefusion.io/introduction/licenses), [disclaimer](https://docs.facefusion.io/introduction/disclaimer), [models-3.3.0](https://huggingface.co/facefusion/models-3.3.0) (file list and sizes via the Hub API).
- FaceFusion Mobile: [repo](https://github.com/AbrahamPaulJ/facefusion-mobile), [model sets](https://huggingface.co/AbrahamPJ/facefusion-mobile-models).
- Responsible AI Licenses: [research-use RAIL-M](https://www.licenses.ai/ai-pubs-researchuse-railm-license-vz1), [naming](https://www.licenses.ai/blog/2022/8/18/naming-convention-of-responsible-ai-licenses).
- SimSwap [repo](https://github.com/neuralchen/SimSwap); GHOST [repo](https://github.com/ai-forever/ghost); GHOST 2.0 [repo](https://github.com/ai-forever/ghost-2.0), [arXiv 2502.18417](https://arxiv.org/abs/2502.18417); AlphaFace [repo](https://github.com/andrewyu90/Alphaface_Official), [arXiv 2601.16429](https://arxiv.org/abs/2601.16429); UniBioTransfer [HF repo](https://huggingface.co/scy639/UniBioTransfer), [arXiv 2603.19637](https://arxiv.org/abs/2603.19637); REFace [repo](https://github.com/Sanoojan/REFace); HeadSwap [repo](https://github.com/LeslieZhoa/HeadSwap); DECA [licence](https://github.com/yfeng95/DECA/blob/master/LICENSE); EMOCA [licence](https://github.com/radekd91/emoca/blob/release/EMOCA_v2/LICENSE).
- Masks and restoration: [MediaPipe image segmenter](https://ai.google.dev/edge/mediapipe/solutions/vision/image_segmenter) and model cards, [face-parsing.PyTorch](https://github.com/zllrunning/face-parsing.PyTorch), [yakhyo/face-parsing](https://github.com/yakhyo/face-parsing), [CelebAMask-HQ](https://github.com/switchablenorms/CelebAMask-HQ), [GFPGAN](https://github.com/TencentARC/GFPGAN), [CodeFormer](https://github.com/sczhou/CodeFormer), [GPEN](https://github.com/yangxy/GPEN), [AdaFace](https://github.com/mk-minchul/AdaFace).
- Acceleration: [Samsung Exynos 2400](https://semiconductor.samsung.com/processor/mobile-processor/exynos-2400/), [ORT execution providers](https://onnxruntime.ai/docs/execution-providers/), [NNAPI EP](https://onnxruntime.ai/docs/execution-providers/NNAPI-ExecutionProvider.html), [XNNPACK EP](https://onnxruntime.ai/docs/execution-providers/Xnnpack-ExecutionProvider.html), [WebGPU EP](https://onnxruntime.ai/docs/execution-providers/WebGPU-ExecutionProvider.html), [QNN EP](https://onnxruntime.ai/docs/execution-providers/QNN-ExecutionProvider.html), [Android NNAPI](https://developer.android.com/ndk/guides/neuralnetworks), [LiteRT NPU](https://ai.google.dev/edge/litert/next/npu), [LiteRT Samsung](https://ai.google.dev/edge/litert/next/samsung), [LiteRT GPU](https://ai.google.dev/edge/litert/next/gpu), [ExecuTorch Samsung backend](https://github.com/pytorch/executorch/blob/main/docs/source/backends/samsung/samsung-overview.md), [Samsung ENNDelegate](https://github.com/Samsung/ENNDelegate).
