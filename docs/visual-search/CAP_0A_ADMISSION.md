# CAP-0A — caption artefact and evaluation admission

Unit [#1106](https://github.com/LPStruiksma/atlas-gallery/issues/1106) · Parent [#666](https://github.com/LPStruiksma/atlas-gallery/issues/666) · Source-only evidence, 2026-11-27.
Extends [CAP-0 feasibility](CAP_0_FEASIBILITY.md) (merged [#673](https://github.com/LPStruiksma/atlas-gallery/pull/673)); does not replace its measurement or privacy gates.

**Decision: NO-GO for execution or shipment at present.** Three upstream snapshots and nine publisher-reported binary SHA-256 values are now identified. No binary, image or annotation corpus was acquired; no model, conversion, remote code or test was executed. A revision and publisher checksum identify a candidate, not an independently verified file, a complete executable bundle or legal approval. Research status unchanged.

## 1. Immutable upstream candidates

These are deliberately chosen reproducible snapshots, not claims about the newest release. The file links below are metadata pages, not download links.

### SmolVLM2 256M

- Repository revision: [`e0e642f8993210665ceb7e072994ccbfde72e85d`](https://huggingface.co/HuggingFaceTB/SmolVLM2-256M-Video-Instruct/tree/e0e642f8993210665ceb7e072994ccbfde72e85d); [pinned card](https://huggingface.co/HuggingFaceTB/SmolVLM2-256M-Video-Instruct/blob/e0e642f8993210665ceb7e072994ccbfde72e85d/README.md).
- Publisher-reported SHA-256 (from the file page's pointer metadata, not the distinct Xet hash):

| File | Expected SHA-256 |
|---|---|
| [model.safetensors](https://huggingface.co/HuggingFaceTB/SmolVLM2-256M-Video-Instruct/blob/e0e642f8993210665ceb7e072994ccbfde72e85d/model.safetensors) | `dcc8f4c00d30d70bb3028a1bfc373e1b1972e25919f8788183cae4ae72431ce2` |
| [onnx/decoder_model_merged_int8.onnx](https://huggingface.co/HuggingFaceTB/SmolVLM2-256M-Video-Instruct/blob/e0e642f8993210665ceb7e072994ccbfde72e85d/onnx/decoder_model_merged_int8.onnx) | `03304ea9501d2d72c3858d668a56f54fc42fc54132759c389e54ff706f3d37c1` |
| [onnx/embed_tokens_int8.onnx](https://huggingface.co/HuggingFaceTB/SmolVLM2-256M-Video-Instruct/blob/e0e642f8993210665ceb7e072994ccbfde72e85d/onnx/embed_tokens_int8.onnx) | `e2cdc3690af5719c7463254f96c3b665a9e24add2a1fa6f594347d67a422710a` |
| [onnx/vision_encoder_int8.onnx](https://huggingface.co/HuggingFaceTB/SmolVLM2-256M-Video-Instruct/blob/e0e642f8993210665ceb7e072994ccbfde72e85d/onnx/vision_encoder_int8.onnx) | `39f0efae9614b1679404961efb970bf307adb76b4d9fb4e25f1e3c564611963d` |

### SmolVLM2 500M

- Repository revision: [`155b0bd93b0c48e156afe5fa74c55ffb45a2a845`](https://huggingface.co/HuggingFaceTB/SmolVLM2-500M-Video-Instruct/tree/155b0bd93b0c48e156afe5fa74c55ffb45a2a845); [pinned card](https://huggingface.co/HuggingFaceTB/SmolVLM2-500M-Video-Instruct/blob/155b0bd93b0c48e156afe5fa74c55ffb45a2a845/README.md).
- Publisher-reported SHA-256 (from the file page's pointer metadata, not the distinct Xet hash):

| File | Expected SHA-256 |
|---|---|
| [model.safetensors](https://huggingface.co/HuggingFaceTB/SmolVLM2-500M-Video-Instruct/blob/155b0bd93b0c48e156afe5fa74c55ffb45a2a845/model.safetensors) | `b9bfd456c9472c0acd5719d6e514c4b859891af205ee1a736552fd3497b8b0c3` |
| [onnx/decoder_model_merged_int8.onnx](https://huggingface.co/HuggingFaceTB/SmolVLM2-500M-Video-Instruct/blob/155b0bd93b0c48e156afe5fa74c55ffb45a2a845/onnx/decoder_model_merged_int8.onnx) | `7d0a356562c7e474012c5e383de58d44d487a517c04190d26e5891db86cb2b1f` |
| [onnx/embed_tokens_int8.onnx](https://huggingface.co/HuggingFaceTB/SmolVLM2-500M-Video-Instruct/blob/155b0bd93b0c48e156afe5fa74c55ffb45a2a845/onnx/embed_tokens_int8.onnx) | `0e6d275190cd1cdf3339225b98b42116871ddc4e0c3210801f02bf2a1d2e164a` |
| [onnx/vision_encoder_int8.onnx](https://huggingface.co/HuggingFaceTB/SmolVLM2-500M-Video-Instruct/blob/155b0bd93b0c48e156afe5fa74c55ffb45a2a845/onnx/vision_encoder_int8.onnx) | `d931eb204e21ab4d905d62976aeaefe20360076f52760ba55b84a5b35088284f` |

### Florence-2 base

- Repository revision: [`fe9219b07e218d2a787a1ef276ad0830ab7b31c4`](https://huggingface.co/microsoft/Florence-2-base/tree/fe9219b07e218d2a787a1ef276ad0830ab7b31c4); [pinned card](https://huggingface.co/microsoft/Florence-2-base/blob/fe9219b07e218d2a787a1ef276ad0830ab7b31c4/README.md).
- Publisher-reported SHA-256 (from the file page's pointer metadata, not the distinct Xet hash):

| File | Expected SHA-256 |
|---|---|
| [model.safetensors](https://huggingface.co/microsoft/Florence-2-base/blob/fe9219b07e218d2a787a1ef276ad0830ab7b31c4/model.safetensors) | `03075d2d2d2bbd3e180b9ba0afae4aa8563226e2d32911656966e05b2f2ee060` |


### SmolVLM2: code, configuration and conversion

Both pinned trees contain `config.json`, `generation_config.json`, `preprocessor_config.json`, `processor_config.json`, `chat_template.json`, `tokenizer.json`, `tokenizer_config.json`, `vocab.json`, `merges.txt`, `added_tokens.json`, and `special_tokens_map.json`. All their individual SHA-256 values are **UNVERIFIED**. Retain all of these until a reviewed minimal closure is demonstrated. The cards use Transformers AutoProcessor and image-text generation; the model family uses SmolVLM processing and a vision encoder/text decoder architecture. Freeze the exact installed Transformers source (including SmolVLM modeling, configuration, image processing, processor and tokenization), PyTorch, tokenizers, safetensors, Pillow, NumPy and transitive dependencies before any reference run. No video decoder is required by the proposed still-image-only scope if dependency closure confirms that; no video experiment.

The [256M ONNX directory](https://huggingface.co/HuggingFaceTB/SmolVLM2-256M-Video-Instruct/tree/e0e642f8993210665ceb7e072994ccbfde72e85d/onnx) and [500M ONNX directory](https://huggingface.co/HuggingFaceTB/SmolVLM2-500M-Video-Instruct/tree/155b0bd93b0c48e156afe5fa74c55ffb45a2a845/onnx) are in the upstream publisher repositories, not unrelated community repacks. The int8 file pages record uploads (256M #1, 500M #2); directory history also shows input-embedding cast fixes (#2 and #3 respectively). The three int8 filenames alone do not prove their quantization recipe, input/output ABI, external data closure, opset, calibration provenance, numerical parity, or CPU/Android compatibility. Full-precision ONNX alternatives exist in the same trees; their hashes are **UNVERIFIED**, as are converter commit, command, package lock, graph internals and any external tensor data. Do not mix quantization variants or revisions. The card's English label is not evidence for Dutch.

### Florence-2 base: code, configuration and conversion

The Microsoft snapshot includes `configuration_florence2.py`, `modeling_florence2.py`, `processing_florence2.py`, `config.json`, `preprocessor_config.json`, `tokenizer.json`, `tokenizer_config.json`, `vocab.json`. All individual non-weight SHA-256 values are **UNVERIFIED**. The card's legacy loading path asks for remote custom code; this is not authorised. Review the three Python files as source, resolve every import (including Transformers/PyTorch, vision processing and any timm/einops dependencies), then select a pinned audited native implementation or vendored reviewed local equivalent with remote code disabled. Preserve task token mapping, image resize/normalisation and postprocessing. Prefer safetensors; do not load the alternate pickle-based `pytorch_model.bin`.

No Microsoft-published ONNX graph is listed in this snapshot. Any external conversion or community checkpoint (including florence-community) is a separate artefact with independent revision, hashes, mapping, licence and parity obligations, not automatically covered by Microsoft's repository. Prefer a reproducible local conversion after admission. Converter/graph/quantization/calibration closure is **UNVERIFIED**. Do not assume a caption task token supports NL; score that independently and do not silently substitute a translation model.

## 2. Primary licence evidence and obligations

| Artefact | Source and stated grant | Private evaluation vs redistribution |
|---|---|---|
| SmolVLM2 checkpoints (both revisions) | Pinned cards above label Apache-2.0; [Apache authoritative licence text](https://www.apache.org/licenses/LICENSE-2.0). | The grant generally permits private use and modification and commercial redistribution of covered works. For redistribution provide licence, mark changed files, retain applicable notices, reproduce applicable NOTICE attribution; patent/trademark and warranty provisions apply. Separate repo LICENSE/NOTICE at these revisions not established; exact applicability to each ONNX derivative, upstream base-component notices and training-data chain **UNVERIFIED**. |
| Florence-2 base | [Pinned Microsoft MIT text](https://huggingface.co/microsoft/Florence-2-base/blob/fe9219b07e218d2a787a1ef276ad0830ab7b31c4/LICENSE); Microsoft copyright. | MIT permits private use, modification and distribution subject to keeping copyright and permission notice in copies/substantial portions. No warranty. Verify scope of weight/processor grant, embedded third-party notices and conversion chain; no assertion that training corpus rights transfer to us. |
| Transformers / conversion tooling | [Transformers LICENSE](https://github.com/huggingface/transformers/blob/main/LICENSE), [Optimum LICENSE](https://github.com/huggingface/optimum/blob/main/LICENSE), [Optimum ONNX LICENSE](https://github.com/huggingface/optimum-onnx/blob/main/LICENSE) (Apache-2.0 project texts, discovery only). | Retain licence and notices for exact chosen releases; no converter used or chosen here. |
| ONNX Runtime | [MIT text](https://github.com/microsoft/onnxruntime/blob/main/LICENSE), [third-party notices](https://github.com/microsoft/onnxruntime/blob/main/ThirdPartyNotices.txt). | MIT core does not clear all embedded components. Pin exact host and eventual Android distributions and retain their actual third-party notices. |

Dependency links on main are discovery pointers, NOT frozen evidence. Exact dependency commits, package and licence-text hashes, applicable notices (including PyTorch, tokenizers, safetensors, image libraries, exporter, ONNX and ORT wheels), source-to-binary mapping and any base-model terms remain **UNVERIFIED**. Produce an SBOM with name/version/commit, package SHA-256, license URL/text SHA-256, copyright/NOTICE and private-use/redistribution disposition per component. Unknown or conflicting grant blocks that component. Private evaluation does not waive image rights, consent, contractual restrictions or security checks; redistribution requires a separate complete notice and grant review. No legal approval is asserted.

| Candidate | Source identity | Execution admission | Redistribution |
|---|---|---|---|
| SmolVLM2 256M | pinned + four publisher hashes | BLOCKED: closure, verified bytes, notices, parity data | NOT APPROVED |
| SmolVLM2 500M | pinned + four publisher hashes | BLOCKED: same independent checks | NOT APPROVED |
| Florence-2 base | pinned + one publisher hash + MIT text | BLOCKED: local code audit/closure, verified bytes, conversion and parity data | NOT APPROVED |

## 3. Evaluation data — no natural-photo corpus admitted

| Candidate source/version | Image grant | Annotation grant and coverage | Status |
|---|---|---|---|
| [COCO 2017 validation](https://cocodataset.org/#download), [consortium terms](https://github.com/cocodataset/cocodataset.github.io/blob/master/dataset/termsofuse.htm) | Consortium does not own images; per-image original grant must be established. | CC BY 4.0 consortium annotations; English captions and object instances useful for entity/count screening, but incomplete negative evidence, relations, OCR and Dutch. | NOT ADMITTED: no frozen row list, individual original grants or hashes. |
| [Open Images V7 (October 2022)](https://storage.googleapis.com/openimages/web/factsfigures_v7.html#licenses) | Images listed as CC BY 2.0, publisher explicitly disclaims per-image licence guarantees. Verify each original source. | Google CC BY 4.0 annotations; boxes, relationships and localized narratives support count/relation checks, not exhaustive negatives or Dutch/OCR gold. | NOT ADMITTED: no individual rights checks or frozen selection. |
| [scikit-image 0.25.2 sample data](https://scikit-image.org/docs/0.25.x/api/skimage.data.html) | Per-image provenance varies; package licence is not a blanket grant. Candidate identifiers only: `coffee`, `coins`, `cat`, `page`, `text`, `rocket`. | API descriptions are not a bilingual caption benchmark; independently authored factual annotations needed. | NOT ADMITTED: asset-specific grant, immutable bytes and annotation coverage not closed; insufficient diversity alone. |
| Repository-generated CAP-0 fixtures | Original geometric PPMs generated by existing protocol; no imported images. | Generator-defined geometry, no external annotations; deterministic factual expectations can be authored for EN/NL. | Synthetic plumbing only; not natural-photo quality evidence. |

[CC BY 2.0 legal text](https://creativecommons.org/licenses/by/2.0/legalcode) and [CC BY 4.0 legal text](https://creativecommons.org/licenses/by/4.0/legalcode) generally permit sharing and adaptation including commercial use with appropriate attribution, licence reference, change indication as applicable and no incompatible restrictions; neither grants unrelated privacy/personality rights. Other image licences may prohibit redistribution/adaptation or commercial use; exclude unclear cases rather than assume private research resolves them. No image bytes, captions, photographer identities or personal-photo information are included here.

### Proposed small frozen evaluation design (not an admitted manifest)

Select 100 unique natural images only after per-asset approval, 10 each in mutually exclusive primary strata: non-identifying people/action (only with documented consent/right), indoor objects, outdoor scenes, food, animals, multiple countable objects, spatial relations, legible non-personal text, low light/occlusion, and negative/ambiguous scenes. Secondary tags may overlap. If consented people cannot be sourced, report the gap rather than substitute identifiable people; use synthetic figures for plumbing. This is a feasibility sample, not population accuracy. Hold out evaluation IDs from conversion/calibration, and disclose possible pretraining overlap.

An immutable asset manifest must contain dataset release + image ID, annotation ID/revision, original source/licence URI and text digest, source file SHA-256, attribution obligations, consent/privacy/other-rights disposition, allowed evaluation and redistribution separately, reviewer/approval and split. Freeze manifest hash before model evaluation; keep rights evidence locally and publish only allowed IDs, aggregates and notices. No natural-photo IDs are approved in this turn. If original grant/annotation grant cannot be established, exclude. For references commission independent EN/NL factual descriptions, countable object labels, spatial relations, non-personal OCR transcript and explicit unsupported-claim checks from two Dutch-fluent raters; record their annotation grant and disagreement resolution. Do not copy model outputs into gold labels.

Fallback: use only existing CAP-0 `make-fixtures` IDs `synthetic-0`, `synthetic-1` for manifest/adapter plumbing. Additional future generated scenes can cover integer counts, left/right, occlusion, blank backgrounds and invented short text without real identities or copyrighted material. Mark their results SYNTHETIC ONLY; not natural-photo quality, Dutch fluency or a shipment gate. No fallback model execution is authorised by this report.

## 4. Handoff: pinned offline host experiment

1. Independent Review checks the revision/file links, published SHA-256 vs Xet distinction, licence scope and no private media. Before any acquisition or execution, obtain the unresolved licence/notice/SBOM and individual dataset grants described above; choose approved artefact and dataset manifests, recording their digests. If none pass, report NO-GO and the missing evidence, not integration. Routine evidence gaps go to the next evidence unit; escalate only genuinely unresolved rights/owner decisions.
2. In a separate authorised host unit, stage only allowlisted files at exact revisions; independently compute SHA-256 and compare publisher expectations. Freeze host CPU/OS, Python, wheel hashes, exact dependency commits, licence hashes, exporter version and command/container digest. Deny network during inference; local paths only, no remote code, no pickle, no private media. Inspect all ONNX external data references, opset, operators, shape and cache contracts before loading.
3. Use a fixed still-image preprocessing and deterministic decoding contract (fixed EN/NL prompts, greedy decoding, max 64 generated tokens, fixed seed where applicable; Florence task mapping separately documented). Compare pinned safetensors reference with its float graph, then int8 graph on the same approved non-calibration inputs. Log preprocessing tensor/shape and token IDs, next-token/logit deviations and caption differences; report exact-match plus meaningful quality deltas by stratum and language. Any failure or unsupported operator is recorded, never silently replaced by cloud inference. Pre-register numerical and caption acceptance thresholds before seeing results; reject material hallucination regression.
4. Follow [CAP-0](CAP_0_FEASIBILITY.md) and [caption bench README](../../tools/caption_bench/README.md): manifest validation with hashes and rights metadata, cold subprocess measurements separately from a persistent resident adapter for warm timing. At least 30 recorded requests per candidate/condition after warmup for warm timings; fixed thread count, hardware, prompt/input set, decoding and timeout. Report successful and failed counts, p50/p95 cold and warm, tokens/s, first-token latency, per-process peak RSS from validated external measurement, and model bytes. No host-to-S24+ extrapolation. Report EN/NL factual scores, hallucination counts, Wilson intervals and inter-rater agreement per CAP-0; publish aggregate metrics only when allowed.
5. Keep S24+ Snapdragon/Exynos latency, PSS, energy, thermal stability, delegation, offline proof and 150k-library measured capacity as separate later gates. No capability promotion, app change, model shipment, cloud inference or spending in this unit.

**Current evidence boundary:** metadata only; hashes for all remaining configuration, code, alternative graphs, licence text and dependency files unverified; no public natural-photo manifest approved; all quality, conversion, parity and timing results NOT MEASURED. Plain revert removes this document alone.
