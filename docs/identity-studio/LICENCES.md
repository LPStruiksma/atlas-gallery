# Identity Studio licence register

Unit [#468](https://github.com/LPStruiksma/atlas-gallery/issues/468) (IDS-1), goal [#467](https://github.com/LPStruiksma/atlas-gallery/issues/467). Companion: [RESEARCH.md](RESEARCH.md).

**Rule (goal #467):** every model, weight and code dependency that Identity Studio uses, benchmarks or ports gets a row here before it is used. Each row was read at the linked source on the date given. Where the text could not be reached or does not exist, the row says **unverified**; nothing here is guessed. A downloadable file is not treated as unrestricted. Weights whose licence forbids redistribution are never committed to this public repository.

This register records what the published texts say and what ATLAS will do about it. It is an engineering record, not legal advice.

## How to read a row

- **Public repo:** may the file be committed to this public repository? `yes`, `no`, or `n/a` (code we do not copy).
- **Purpose limit:** `none`, `non-commercial` (NC), or `research-only` (academic or research purposes only; narrower than NC, because personal gallery editing is not research).
- **Use restrictions:** behavioural clauses (RAIL Attachment A style) that travel with the file.
- **Content checker:** whether any licence text read *requires* an NSFW or content gate. `app choice` means an upstream application ships one, but no licence text read requires it.
- **Consequence:** `bundle` (commit or ship in the APK), `download` (fetch on device from the original host, SHA-256 pinned, after a separate owner size/download gate), `bench only` (host-side research benchmark in `tools/identity_bench`, weights fetched at run time from the original host, never committed, never shipped), `do not use`.

## Licence tiers at a glance

| Tier | Meaning for ATLAS | Members |
|---|---|---|
| A. Permissive, chain read | May ship (bundle or download) | YuNet, SFace, MediaPipe hair and multiclass segmenters, ONNX Runtime, LiteRT, LaMa (#459) |
| B. Non-commercial only | Lukas's private, unpaid use fits the text; never in a paid product; download, do not bundle large files | SimSwap (CC BY-NC 4.0), CodeFormer (S-Lab 1.0) |
| C. Research-only | Benchmark only. Shipping needs a licence from the owner of the weights | InSwapper-128, InsightFace ArcFace (w600k_r50, buffalo_l, antelopev2, and GHOST 1's `backbone.pth`, which is InsightFace's `ms1mv3_arcface_r100_fp16`), HyperSwap 1a/1b/1c (ResearchRAIL label), DECA, EMOCA, InfiniteYou weights |
| D. Permissive label, provenance gap | Benchmark now; resolve the gap before shipping | GHOST 1 generators (their identity backbone is tier C, see [GHOST_BACKBONE.md](GHOST_BACKBONE.md)), AlphaFace (weights carry no licence), UniBioTransfer and REFace (checkpoint inherits RAIL + NC data), BiSeNet face parsers (NC training data) |
| E. No grant found | Do not use | FaceFusion code (OpenRAIL-AS named, text unpublished), FaceFusion Mobile code (no licence file), GPEN weights (no licence file), XSeg (GPL-3.0 label, unread) |

**The headline:** no open face swapper read today has a verified permissive chain from weights to identity encoder. The best-quality swappers (HyperSwap, InSwapper) are research-only and both depend on InsightFace's research-only ArcFace embedding. That is a shipping question for IDS-9 (#475), not a benchmark question: IDS-2 (#469) may benchmark tier C as research.

## Register

### Face swappers

| Item | Kind | Licence | Text (URL) | Read | Public repo | Purpose limit | Use restrictions | Content checker | Consequence |
|---|---|---|---|---|---|---|---|---|---|
| InSwapper-128 `inswapper_128.onnx` (InsightFace) | weights | InsightFace model terms: "non-commercial research purposes only"; inswapper series "contact … for licensing" (2025-11-24 update) | [README License](https://github.com/deepinsight/insightface/blob/master/README.md#license), [in_swapper README](https://github.com/deepinsight/insightface/blob/master/examples/in_swapper/README.md) | 2026-11-18 | no | research-only | none stated | none required | **bench only**; shipping needs a written InsightFace licence |
| InsightFace Python library | code | MIT | [README License](https://github.com/deepinsight/insightface/blob/master/README.md#license) | 2026-11-18 | n/a | none | none | none | not needed (we reimplement in Kotlin) |
| HyperSwap 1a / 1b / 1c `hyperswap_1x_256.onnx` (FaceFusion, 402.7 MB each) | weights | **ResearchRAIL** (label only) | Label: [FaceFusion licences page](https://docs.facefusion.io/introduction/licenses) and model metadata in [`face_swapper/core.py`](https://github.com/facefusion/facefusion/blob/decc6c9d8f8e014878624452c6e8fe208726b4ec/facefusion/processors/modules/face_swapper/core.py). The hosting repo [facefusion/models-3.3.0](https://huggingface.co/facefusion/models-3.3.0) has no README or licence file. Template family: [AI Pubs Research-use RAIL-M](https://www.licenses.ai/ai-pubs-researchuse-railm-license-vz1) | 2026-11-18 | no | research-only (template "Permitted Purpose … academic or research purposes only") | Template Attachment A: no use outside permitted purpose; no harm to minors; no harmful false information; no harassment; **must disclose machine-generated content**; no impersonation for deception | none in the template; FaceFusion's checker is an app choice | **bench only**. Exact instance text **unverified** (FaceFusion publishes only the label). Shipping needs FaceFusion's written terms |
| FaceFusion application | code | OpenRAIL-AS (name only) | [LICENSE.md](https://github.com/facefusion/facefusion/blob/master/LICENSE.md) contains only "OpenRAIL-AS license" and a copyright line | 2026-11-18 | n/a | **unverified** | **unverified** (FaceFusion Mobile asserts it forbids non-consensual use; no text found) | app choice: [disclaimer](https://docs.facefusion.io/introduction/disclaimer) says the software is "engineered to abstain from processing inappropriate content" | **do not use** its code. IDS-2 writes its own harness from published model input/output shapes |
| SimSwap (official 224/256, code and weights incl. its ArcFace checkpoint) | code + weights | CC BY-NC 4.0 ("The whole project is under the CC-BY-NC 4.0 license") | [LICENSE](https://github.com/neuralchen/SimSwap/blob/main/LICENSE), [README License](https://github.com/neuralchen/SimSwap/blob/main/README.md#license) | 2026-11-18 | yes under CC BY-NC terms (attribution, NC); we still do not commit large weights | non-commercial ("for academic and non-commercial use only") | none beyond NC and attribution | none | **bench**; possible **download** for private NC use |
| SimSwap 512 "unofficial" (FaceFusion conversion) | weights | label "Non-Commercial" | FaceFusion metadata only | 2026-11-18 | no | **unverified** provenance | unverified | none | **do not use** |
| GHOST 1 (AEI-Net generators `G_unet_*blocks.pth`) | code + weights | Apache-2.0 | [LICENSE](https://github.com/ai-forever/ghost/blob/main/LICENSE); weights are release assets of the same project ([download_models.sh](https://github.com/ai-forever/ghost/blob/main/download_models.sh)) with no separate terms | 2026-11-18 | yes (Apache) | none | none | none | **bench**; not shippable alone: the generators need the research-only backbone below (resolved by #505) at run time |
| GHOST 1 identity backbone `arcface_model/backbone.pth` (iresnet100) | weights | **InsightFace arcface_torch model zoo `ms1mv3_arcface_r100_fp16`** (trained by InsightFace, March 2021); InsightFace model terms: "available for non-commercial research purposes only". GHOST's Apache-2.0 does not cover it | [GHOST_BACKBONE.md](GHOST_BACKBONE.md) (#505): byte-identical to an arcface_torch training folder, step counter 124,550 equals InsightFace's [training log](https://raw.githubusercontent.com/anxiangsir/insightface_arcface_log/master/ms1mv3_arcface_r100_fp16/training.log); [InsightFace README License](https://github.com/deepinsight/insightface/blob/master/README.md#license) | 2026-11-18 | no | research-only | none | none | **bench only** (tier C); shipping needs an InsightFace licence |
| GHOST 1 detector `glintr100.onnx`, `scrfd_10g_bnkps.onnx` (InsightFace "antelope") | weights | InsightFace model terms | [README License](https://github.com/deepinsight/insightface/blob/master/README.md#license) | 2026-11-18 | no | research-only | none | none | **do not use**; replace with YuNet (tier A) |
| AlphaFace (2026, arXiv 2601.16429) | code | MIT | [LICENSE](https://github.com/andrewyu90/Alphaface_Official/blob/main/LICENSE) | 2026-11-18 | yes | none | none | none | code usable |
| AlphaFace checkpoint and its ArcFace file (Google Drive links in the README) | weights | **none stated**; FaceFusion labels its conversion "Non-Commercial" | [Readme](https://github.com/andrewyu90/Alphaface_Official/blob/main/Readme.md) | 2026-11-18 | no | **unverified** | unverified | none | **bench only** after asking the author; not shipped |
| BlendSwap, UniFace, HifiFace-unofficial (FaceFusion conversions) | weights | labels NC / Unknown / Unknown | FaceFusion metadata only | 2026-11-18 | no | **unverified** | unverified | none | **do not use** |
| CrossFace embedding converters (FaceFusion) | weights | label OpenRAIL-M | FaceFusion licences page only | 2026-11-18 | no | **unverified** | unverified | none | not needed |
| FaceFusion Mobile (Android port) | code | **no licence file** in the repo tree at `afe1467` (2026-10-22) | [repo](https://github.com/AbrahamPaulJ/facefusion-mobile) | 2026-11-18 | n/a | no grant | n/a | app choice ("Mandatory" gate in its own README) | **do not use** code; reference reading only |
| FaceFusion Mobile model sets (Hexagon QNN context binaries, ncnn set) | weights | "license: other / mixed-per-model", derived works of the rows above | [model card](https://huggingface.co/AbrahamPJ/facefusion-mobile-models) | 2026-11-18 | no | inherits (ResearchRAIL, NC, GPL-3.0 per its own table) | inherits | app choice | **do not use**; Hexagon binaries cannot load on Exynos anyway |

### Full-head and diffusion swappers

| Item | Kind | Licence | Text (URL) | Read | Public repo | Purpose limit | Use restrictions | Content checker | Consequence |
|---|---|---|---|---|---|---|---|---|---|
| GHOST 2.0 (Aligner, Blender) | code + checkpoints | Apache-2.0 | [LICENSE](https://github.com/ai-forever/ghost-2.0/blob/main/LICENSE) | 2026-11-18 | yes for its own files | none for its own files | README "Ethics" statement (not a licence term) | none | **bench only** because of the dependencies below |
| DECA (GHOST 2.0 dependency) | code + model | MPI "non-commercial scientific research" licence | [LICENSE](https://github.com/yfeng95/DECA/blob/master/LICENSE) | 2026-11-18 | no (single-user, non-transferable) | research-only; also bans "pornographic, military, or surveillance" use | as stated | none | **bench only** (IDS-3 probe host) |
| EMOCA (GHOST 2.0 dependency) | code + model | MPI non-commercial scientific research licence | [LICENSE](https://github.com/radekd91/emoca/blob/release/EMOCA_v2/LICENSE) | 2026-11-18 | no | research-only | as DECA | none | **bench only** |
| FLAME head model (via DECA/EMOCA) | model | registration-gated MPI licence | not read | — | no | **unverified** | unverified | — | **bench only**, owner must register if IDS-3 needs it |
| StyleMatte (GHOST 2.0 dependency) | code | Apache-2.0 (`stylematte/LICENSE`); repo also holds `license/en_us.pdf` | [repo](https://github.com/chroneus/stylematte) | 2026-11-18 | yes (code) | weights **unverified** (PDF not read) | unverified | none | **bench only** |
| GHOST 2.0 `segformer_B5_ce.onnx`, BlazeFace_PyTorch | weights / code | not read | [GHOST 2.0 README](https://github.com/ai-forever/ghost-2.0/blob/main/README.md) | — | no | **unverified** | unverified | — | **bench only** |
| UniBioTransfer (2026, arXiv 2603.19637) | code | MIT (file text: "Copyright (c) 2024 Sanoojan", i.e. REFace's licence carried over) | [LICENSE](https://huggingface.co/scy639/UniBioTransfer/blob/main/LICENSE) | 2026-11-18 | yes (code) | none on code | none | none | code usable for a server probe |
| UniBioTransfer `checkpoints/pretrained.ckpt` | weights | **none stated** separately; the code "borrows heavily from REFace", whose README says its model carries CreativeML Open RAIL-M restrictions and a CelebAMask-HQ non-commercial limit | [README](https://huggingface.co/scy639/UniBioTransfer/blob/main/README.md), [REFace README License](https://github.com/Sanoojan/REFace/blob/main/README.md#license) | 2026-11-18 | no | **unverified**; likely NC by inheritance | likely Open RAIL-M Attachment A | none required | **bench only**; needs ≥11 GB GPU ("Minimum 11GB VRAM") |
| REFace | code + weights | MIT code; model under CreativeML Open RAIL-M (via Paint-by-Example) and CelebAMask-HQ NC | [LICENSE](https://github.com/Sanoojan/REFace/blob/main/LICENSE), [README License](https://github.com/Sanoojan/REFace/blob/main/README.md#license) | 2026-11-18 | no (weights) | non-commercial (README: "Use of this model for commercial purposes is strictly prohibited") | Open RAIL-M Attachment A | none | **bench only** |
| Paint-by-Example (REFace base) | weights | CreativeML Open RAIL-M (2022-11-30) | [LICENSE](https://github.com/Fantasy-Studio/Paint-by-Example/blob/main/LICENSE) | 2026-11-18 | yes, if Attachment A is passed on | none | Attachment A (no unlawful use, harm to minors, harmful false information, harassment, etc.); restrictions must be imposed on downstream users | none | inherited by REFace / UniBioTransfer |
| HeadSwap (LeslieZhoa) | code | MIT | [LICENSE](https://github.com/LeslieZhoa/HeadSwap/blob/main/LICENSE) | 2026-11-18 | yes (code) | weights and BFM / PIRender dependencies **unverified** | unverified | none | low priority; **bench only** if used |
| InfiniteYou (ByteDance) | code / weights | Apache-2.0 code; model CC BY-NC 4.0 "for academic research purposes only" | [README](https://github.com/bytedance/InfiniteYou/blob/main/README.md) | 2026-11-18 | no (weights) | research-only (by README) | as README | none | **do not use** (also needs FLUX.1-dev and InsightFace) |
| InstantID, PuLID, Arc2Face | code | Apache-2.0, Apache-2.0, MIT | [InstantID](https://github.com/instantX-research/InstantID/blob/main/LICENSE), [PuLID](https://github.com/ToTheBeginning/PuLID/blob/main/LICENSE), [Arc2Face](https://github.com/foivospar/Arc2Face/blob/main/LICENSE) | 2026-11-18 | n/a | weights **unverified**; all three need InsightFace `antelopev2` (research-only) | unverified | none | **do not use** locally (server-class diffusion) |

### Supporting stages

| Item | Kind | Licence | Text (URL) | Read | Public repo | Purpose limit | Use restrictions | Content checker | Consequence |
|---|---|---|---|---|---|---|---|---|---|
| YuNet face detector (already shipped) | weights | MIT | [`models/yunet/SOURCE.md`](../../app/src/main/assets/models/yunet/SOURCE.md) | 2026-11-03 (at vendoring, per SOURCE.md) | yes | none | none | none | **bundle** (already in APK) |
| SFace embedder (already shipped) | weights | Apache-2.0 | [`models/sface/SOURCE.md`](../../app/src/main/assets/models/sface/SOURCE.md) | 2026-11-03 (at vendoring, per SOURCE.md) | yes | none | none | none | **bundle**; shipped identity check |
| MediaPipe hair segmenter `hair_segmenter.tflite` (0.78 MB) | weights | Apache-2.0 | [model card](https://storage.googleapis.com/mediapipe-assets/Model%20Card%20-%20Hair%20Segmentation.pdf) | 2026-11-18 | yes | none | none | none | **bundle** candidate (hair mask) |
| MediaPipe multiclass selfie segmenter `selfie_multiclass_256x256.tflite` (16.4 MB) | weights | Apache-2.0 | [model card](https://storage.googleapis.com/mediapipe-assets/Model%20Card%20Multiclass%20Segmentation.pdf) | 2026-11-18 | yes | none; card puts "surveillance or identity recognition" out of scope | none | none | **bundle** candidate (hair, face skin, body skin, clothes, background); IDS-9 face mask via `FaceSegmenter`, delivery still open ([LOCAL_ENGINE.md](LOCAL_ENGINE.md#open-points)) |
| MediaPipe framework | code | Apache-2.0 | [LICENSE](https://github.com/google-ai-edge/mediapipe/blob/master/LICENSE) | 2026-11-18 | yes | none | none | none | allowed; not needed if the `.tflite` runs on LiteRT or is converted to ONNX |
| BiSeNet face parsing (zllrunning `79999_iter.pth`) | code + weights | MIT code; weights trained on CelebAMask-HQ | [LICENSE](https://github.com/zllrunning/face-parsing.PyTorch/blob/master/LICENSE), [CelebAMask-HQ terms](https://github.com/switchablenorms/CelebAMask-HQ/blob/master/README.md) | 2026-11-18 | code yes; weights **unverified** | dataset: "non-commercial research purposes only" | dataset terms | none | **bench only** |
| BiSeNet face parsing (yakhyo ResNet18/34, FaceFusion's `bisenet_resnet_*`) | code + weights | MIT; trained on CelebAMask-HQ | [LICENSE](https://github.com/yakhyo/face-parsing/blob/main/LICENSE), [README](https://github.com/yakhyo/face-parsing/blob/main/README.md) | 2026-11-18 | code yes; weights **unverified** for the same dataset reason | dataset NC research | dataset terms | none | **bench only**; MediaPipe is the shipping mask |
| XSeg (DeepFaceLab) | weights | label GPL-3.0 | FaceFusion metadata only | 2026-11-18 | **unverified** | unverified | GPL copyleft if confirmed | none | **do not use** |
| GFPGAN v1.4 | code + weights | Apache-2.0, "except for the third-party components" (StyleGAN2 under NVIDIA licence, DFDNet under CC BY-NC-SA 4.0) | [LICENSE](https://github.com/TencentARC/GFPGAN/blob/master/LICENSE) | 2026-11-18 | **unverified** for the weights | mixed; NVIDIA and NC parts | as listed | none | **bench only** (restoration ablation) |
| CodeFormer | code + weights | S-Lab License 1.0 | [LICENSE](https://github.com/sczhou/CodeFormer/blob/master/LICENSE) | 2026-11-18 | yes for non-commercial redistribution with notice; we do not commit (377 MB) | non-commercial ("Redistribution and use for non-commercial purpose") | none | none | **bench**; private NC **download** possible |
| GPEN (BFR-256/512) | weights | **no licence file** (repo `2c73670`); README: "not our best model due to commercial issues" | [README](https://github.com/yangxy/GPEN/blob/main/README.md) | 2026-11-18 | no | no grant | n/a | none | **do not use** |
| RestoreFormer++ | weights | label Apache-2.0 | FaceFusion licences page only | 2026-11-18 | **unverified** | unverified | unverified | none | read the source before any use |
| LaMa big-lama (Erase, PR #459) | weights | Apache-2.0 | see PR #459 `models/lama/LICENSE` | (#459) | yes | none | none | none | reuse for head/hair inpainting probes |

### Identity judges (research only, never shipped)

| Item | Kind | Licence | Text (URL) | Read | Public repo | Purpose limit | Use restrictions | Content checker | Consequence |
|---|---|---|---|---|---|---|---|---|---|
| ArcFace `w600k_r50` (InsightFace `buffalo_l`) | weights | InsightFace model terms; signed `MODEL.LICENSE` with `"grant": "non-commercial"` | [buffalo_l MODEL.LICENSE](https://github.com/deepinsight/insightface/blob/master/server/backend/insightface_server/licensing/defaults/buffalo_l/MODEL.LICENSE), [model zoo licences](https://github.com/deepinsight/insightface/blob/master/python-package/docs/model_zoo.md#model-licenses) | 2026-11-18 | no | research-only | none | none | **bench only** (judge). It is also the input encoder of InSwapper and HyperSwap, so see the bias note in RESEARCH.md |
| AdaFace (R50/R100, WebFace4M) | code + weights | MIT code; weights carry no separate licence (Google Drive) | [LICENSE](https://github.com/mk-minchul/AdaFace/blob/master/LICENSE), [README](https://github.com/mk-minchul/AdaFace/blob/master/README.md) | 2026-11-18 | no | weights **unverified** | unverified | none | **bench only** (second, independent judge) |

### Runtimes and accelerators

| Item | Kind | Licence | Text (URL) | Read | Public repo | Purpose limit | Use restrictions | Content checker | Consequence |
|---|---|---|---|---|---|---|---|---|---|
| ONNX Runtime Android 1.23.2 (already shipped) | runtime | MIT | [Maven POM `<license>`](https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/1.23.2/onnxruntime-android-1.23.2.pom) | 2026-11-18 | n/a | none | none | none | in use |
| LiteRT | runtime | Apache-2.0 | [LICENSE](https://github.com/google-ai-edge/LiteRT/blob/main/LICENSE) | 2026-11-18 | n/a | none | none | none | would be a **new Gradle dependency** (protected path, owner gate) |
| Samsung ENN Delegate / Exynos AI LiteCore SDK | runtime | proprietary, accepted by running a self-extracting archive | [ENNDelegate README](https://github.com/Samsung/ENNDelegate/blob/main/README.md) | 2026-11-18 | no | **unverified** (licence not accepted, not read) | unverified | — | **do not use** without owner review; see RESEARCH.md for SoC support |
| Qualcomm QNN / QAIRT | runtime | Qualcomm SDK terms | not read | — | no | — | — | — | not applicable: Snapdragon only, cannot run on Exynos 2400 |

## Content checkers: obligation or choice?

No licence text read for this unit requires an NSFW or content gate. The research-use RAIL template, CreativeML Open RAIL-M, CC BY-NC 4.0, S-Lab 1.0, MIT and Apache-2.0 contain none. The gates seen upstream are application choices: FaceFusion's content analyser (three NSFW models, Apache-2.0 / MIT labels) and FaceFusion Mobile's "Mandatory" checker. FaceFusion's own OpenRAIL-AS and ResearchRAIL instance texts are unpublished, so this conclusion is **unverified for HyperSwap and FaceFusion code specifically**; neither is cleared for shipping anyway.

What the read texts **do** require, and how ATLAS meets it:

- Disclose machine-generated content (research-use RAIL Attachment A 3(a)): every saved candidate is marked as an AI edit with a lineage record (`docs/ai-edit/SAVE_POLICY.md`, IDS-10 #476).
- No impersonation for deception, no harm to minors, no harassment or harmful false information (RAIL and Open RAIL-M Attachment A): ATLAS builds no sharing-for-realism, watermark-removal or impersonation feature (goal #467 limits).
- Pass use restrictions on to downstream users (Open RAIL-M §5; RAIL §4(a)): only relevant if ATLAS ever distributes such weights; today it does not.
- Attribution (CC BY-NC 4.0, Apache-2.0 NOTICE, S-Lab §2): an in-app licences screen must list any shipped or downloaded model.

## Maintenance

Re-read a row before the unit that ships or downloads its file, and update the date. A row moves tiers only with a source link.
