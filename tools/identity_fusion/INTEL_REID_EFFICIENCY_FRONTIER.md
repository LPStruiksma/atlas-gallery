# Intel Open Model Zoo person-ReID efficiency frontier — Lane C, 21 Oct 2026

**Source-only, no checkpoints or images retrieved.** Pinning public source
metadata does NOT approve weight licenses, model execution, inference,
model packaging, or use of anyone's photograph.

## Newly examined sibling baselines

The following source files were independently retrieved from the
**official Open Model Zoo** repository
`openvinotoolkit/open_model_zoo` at the same previously recorded immutable
commit `af810de42bcf460a18610481f639dcdc86249e69`:

| Source/graph family | Upstream README Git blob SHA-1 | Publisher Market-1501 rank-1 / mAP | Publisher compute / size | Position in research |
| --- | --- | --- | --- | --- |
| [Intel person-reidentification-retail-0288](https://github.com/openvinotoolkit/open_model_zoo/blob/af810de42bcf460a18610481f639dcdc86249e69/models/intel/person-reidentification-retail-0288/README.md) | `d3dc497d11b43f272765b22327bb85f6e9630028` | 86.1% / 58.857% | **0.174 GFLOPs; 0.183 Mparams** | **Lightweight Intel first-pass pilot candidate** (not approved). ReID publisher evidence at much lower advertised arithmetic than 0287. |
| [Intel person-reidentification-retail-0287](https://github.com/openvinotoolkit/open_model_zoo/blob/af810de42bcf460a18610481f639dcdc86249e69/models/intel/person-reidentification-retail-0287/README.md) | `37c48a05f9aa95f12c872d263207da4c607c08a9` | 92.9% / 76.6% | 0.564 GFLOPs; 0.595 Mparams | **Balanced comparison baseline**, already subject to detailed source, ONNX/rights and preprocessing admission in V5. |
| [Intel person-reidentification-retail-0286](https://github.com/openvinotoolkit/open_model_zoo/blob/af810de42bcf460a18610481f639dcdc86249e69/models/intel/person-reidentification-retail-0286/README.md) | `8a18bf6ac97aa2ed861d741533fcc71ffc710fbd` | 94.8% / 83.7% | **1.170 GFLOPs; 1.234 Mparams** | Higher-compute **reference ceiling**, not yet a compelling Android default. |

All three source descriptions specify a **BGR**, NCHW,
`1×3×256×128` input and a **256-dimensional** person-appearance embedding
output. They claim occlusion support **under 50%** for upright
pedestrians with a pose parallel to the image plane. This is
**not evidence** of rear-only identity recognition, partial heads,
severe occlusions, decades-apart family portraits or garment-change
robustness.

For 0288 versus 0287, the publisher's complexity is approximately
**3.24× lower** in FLOPs and **3.25× lower** in parameters, at the cost
of substantially lower **same-domain** Market-1501 accuracy
(86.1 vs 92.9 rank-1; 58.857 vs 76.6 mAP).
No model performance, speed, battery, accuracy or RAM was measured
by ATLAS here. The reported paper/model-zoo protocols, weight sources
and validation datasets may differ, so these numbers are *screening
inputs*, not a trustworthy head-to-head benchmark.

## Critical format distinction: 0288 is **not** a published ONNX asset

An independent look at the exact official
[`person-reidentification-retail-0288/model.yml`](https://github.com/openvinotoolkit/open_model_zoo/blob/af810de42bcf460a18610481f639dcdc86249e69/models/intel/person-reidentification-retail-0288/model.yml)
found **four** entries, with **no ONNX file**:

| Precision | OpenVINO XML size / SHA-384 | OpenVINO BIN size / SHA-384 |
| --- | --- | --- |
| FP32 | **450,664 bytes**, `25082c8e783ad358396039d6c3969f9d862512de0cd3dd87b23c2e5790fabc64e288336e5d42369fcc4c347be15c19f2` | **728,052 bytes**, `ffc5956253b60f9fac52189976a2ceba2b69288715b35caeff05238840b6e87dcc314d777b612c54d55d976988d6b9d9` |
| FP16 | **614,533 bytes**, `18814c6445b35224987bc44bcbdb47da6c3826e5da0b6aa00a88c06bf71491a8c4f42e1f62424c077cce46e76a41f7a2` | **364,098 bytes**, `0a5ca9d000ce63b078149a3aa3993cd6e92d52fdecf1ff58fbe717481c2043db348938c32fed964846817dc321825a4c` |

Official commit:
`openvinotoolkit/open_model_zoo@af810de42bcf460a18610481f639dcdc86249e69`,
source `model.yml` Git blob
`4f8409e88de4399b2f9471b1962ea18a3242a1e2`.
Official 0288 `accuracy-check.yml` additionally reports bilinear/Pillow
128×256 resize and Market-1501 rank-1 `0.861`, mAP `0.58857`;
source blob `28d6981f6d9e0f055e1c7f6107e5b1b0208516d0`.

Unlike previously registered 0287's published ONNX asset,
**an OpenVINO IR XML+BIN checkpoint cannot be loaded directly by
ONNX Runtime**. It would need a separately proven compatible
conversion/export pipeline or an authorized suitable device runtime.
Do not conflate an IR pair with an ONNX graph or infer availability of
a 0288 ONNX checkpoint from the 0287 manifest. A source-reported
parameter count does not resolve this backend integration cost.

The optional `verify_intel_0288_ir_pair.py` attestor verifies BOTH
staged members of one precision against exact source-declared
**size and SHA-384** using symlink-free, hardlink-free, stable file
descriptors and rejects changed bytes or cross-member races. It
**does not parse XML, deserialize BIN, load a model, convert graphs or
establish publisher identity/rights**. All tests use invented
synthetic bytes and cannot report official model byte admission.
Only after independently legally obtaining the exact publisher
files, run:

```sh
python tools/identity_fusion/verify_intel_0288_ir_pair.py FP32 \
  /authorized/staged/person-reidentification-retail-0288/FP32
```

Successful SHA matching would establish only source-manifest
*byte consistency*, never training/dataset rights, graph safety,
model identity semantics, conversion equivalence, Unknown rejection,
or real S24+ performance. **Current status remains NO actual files
obtained, NO license clearance, NO conversion and NO inference.**

## Recommended S24+ candidate sequencing (not deployment approval)

1. **Check license and source first.** For any one exact 0288/0287/0286
   checkpoint: obtain independently authenticated weight SHA-256/384,
   verified weight redistribution and data provenance, unique
   architecture/graph identity, export attribution and safe ONNX graph
   parsing. Do not infer rights from the repository's Apache-2.0
   license or the model README. This is a hard gate.
2. **Give 0288 an efficiency slot** *beside* Torchreid OSNet x0.25,
   not instead of it. It represents a very small officially
   documented 256-D OSNet-style person-ReID model. Its reduced
   arithmetic is promising for local CPU and memory constraints but
   published rank-1 is weaker. Do not auto-select it as best.
3. **Retain 0287 as balanced Intel baseline** with its separately
   pinned ONNX manifest, color/resize rules and provenance audit.
   Consider **0286** only as a larger same-family research
   performance ceiling and if actual device measurements justify it.
4. **Run genuine apples-to-apples testing only after source and
   dataset rights are cleared:** identical independently
   reviewed character/person-connected *Unknown* and known
   calibration/holdout splits, face-hidden backs, occluded/partial
   views, clothing/year shifts and lookalike controls.
   Calibrate similarity threshold on calibration only; compare
   open-set Unknown false-review offers, correct review coverage,
   condition-specific source-connected groups and gallery-size
   sensitivity. No source overlap with training. Published
   Market-1501 results are not a prediction of ATLAS error rates.
5. **Measure on physical Galaxy S24+** with actual same image decoding,
   orientation, crop/interpolation, BGR/range normalization and
   output L2 policy: independent cold/warm p50/p95/p99 per crop,
   peak resident RAM, model/store bytes, energy and sustained
   thermal effects. Compare CPU/NNAPI only where Android graph
   support and fallback decomposition are independently measured;
   FLOPs are not latency. All inference remains local and
   cancellable, with no silent bulk identity confirmation.
6. **Publish the failure modes, not just performance winners:**
   disaggregate partial body, back-only, out-of-focus, uniform
   clothing, family lookalikes, off-center boxes, no visible person,
   ambiguous top-two, false Unknown offers and abstentions. A
   cheaper inference engine that makes more confidently wrong
   suggestions may be strictly worse for a People gallery.

## Source-admission status

| Gate | 0288 | 0287 | 0286 |
| --- | --- | --- | --- |
| Immutable README source-object metadata examined | Yes | Yes | Yes |
| Exact runnable checkpoint bytes authenticated in this study | **No** | **No** | **No** |
| Weight/derived-artifact & training-data usage rights cleared | **No** | **No** | **No** |
| Complete ONNX pre/postprocessing parity | **No** | **No** | **No** |
| Consent/rights-cleared rear/occluded/Unknown evaluation | **No** | **No** | **No** |
| Actual Galaxy S24+ p95, RAM, thermals | **No** | **No** | **No** |
| Production release / automatic Person linking | **No** | **No** | **No** |

Specific 0287 manifest/source SHA-1 and SHA-256 checks are available in
`upstream_model_source_pins.json`,
`INTEL_STRONG_SOURCE_V3.md` and related offline validators. They
do NOT automatically transfer checkpoint-specific byte/rights
verification to 0288 or 0286. The source blobs listed above are
upstream README *documentation*, not model or remote weight hashes.

**Decision:** add 0288 to the first independently permitted candidate
efficiency comparison *after* authentic checkpoint/rights admission;
retain 0287 as the balanced baseline. Do not download or run any
third-party weights or cloud inference on private media without
separate source and privacy gates. Human approval remains mandatory
for every future People identity proposal.
