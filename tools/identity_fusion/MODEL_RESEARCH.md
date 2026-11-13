# Lane C: locally runnable person-reID research and admission gates (2026-10-16)

**Scope:** model-selection research, synthetic score benchmarking, and a future
*consented local-only* evaluation plan. **No weights, images, embeddings,
private People IDs, network inference, or app integration are added here.**
Published camera-reID results are **not** evidence of recognition from behind
in a personal photo archive. Do not auto-confirm identity from ANY body,
clothing, face, or contextual score. Unknown/Reject remains a first-class
answer; suggestions are for human review only.

## Verified candidate comparison

| Candidate | Upstream reported size/compute | Upstream evidence | Licensing/provenance status | Practical position |
|---|---|---|---|---|
| **Torchreid OSNet x0.25** | 0.2M parameters; 0.08 GFLOPs, 256x128 | Market-1501 *closed-set* rank-1 91.2%, mAP 75.0% (model-zoo protocol) | Torchreid code MIT; official OSNet weights are published under a MIT-marked Hugging Face model repository; verify each exact checkpoint, training-data permissions and notices before packaging | **First lightweight architecture to examine** for CPU/ORT conversion. Check feature dimensionality, embedding normalization and ONNX operator support. |
| **Torchreid OSNet x0.5** | 0.6M parameters; 0.27 GFLOPs, 256x128 | Market-1501 rank-1 92.5%, mAP 79.8%; cross-dataset tests degrade markedly | Same checkpoint-by-checkpoint due diligence | Accuracy/latency comparison to x0.25. |
| **Intel Open Model Zoo person-reidentification-retail-0287** | 0.595M parameters; 0.564 GFLOPs; 256-D embedding; BGR/NCHW 1x3x256x128 | Market-1501 rank-1 92.9%, mAP 76.6%; reported occlusion coverage **below 50%** for upright pedestrians | Open Model Zoo repository is Apache-2.0; its precise model manifest points to the Apache-2.0 license. The manifest provides SHA-384s for ONNX/FP32/FP16/INT8 artifacts. Check model-file metadata before redistribution. | **Second reproducible offline baseline.** Repository manifest lists an ONNX file named `person-reidentification-retail-0267.onnx` inside the 0287 directory: verify *actual graph identity, input/output, checksum* rather than inferring from filename. |
| **FastReID (R50 BoT/AGW/SBS)** | A ResNet-50-based research family; not directly comparable to tiny OSNet | Official model zoo reports e.g. BoT(R50) Market-1501 rank-1 94.4%, mAP 86.1%, with PyTorch/CUDA training settings | Framework Apache-2.0; each model-zoo weight, its ImageNet initialization, training dataset, export and redistribution terms require separate approval | **Research quality ceiling only** until a reproducible compact exported checkpoint meets Android constraints. Do not ship the Python/CUDA stack. |

Sources, checked 16 October 2026:
- Torchreid source/license/export: https://github.com/KaiyangZhou/deep-person-reid ,
  https://github.com/KaiyangZhou/deep-person-reid/blob/master/LICENSE ,
  https://github.com/KaiyangZhou/deep-person-reid/blob/master/tools/export.py
- Torchreid original measurement tables (including **cross-domain losses**):
  https://github.com/KaiyangZhou/deep-person-reid/blob/master/docs/MODEL_ZOO.md
- MIT-labelled author weights (NOT a blanket grant for its training data):
  https://huggingface.co/kaiyangzhou/osnet
- FastReID project/model zoo/deployment:
  https://github.com/JDAI-CV/fast-reid ,
  https://github.com/JDAI-CV/fast-reid/blob/master/MODEL_ZOO.md
- Open Model Zoo specification and versioned manifest:
  https://github.com/openvinotoolkit/open_model_zoo/blob/master/models/intel/person-reidentification-retail-0287/README.md ,
  https://github.com/openvinotoolkit/open_model_zoo/blob/master/models/intel/person-reidentification-retail-0287/model.yml
- Android ORT and accelerator caveats:
  https://onnxruntime.ai/docs/tutorials/mobile/ ,
  https://onnxruntime.ai/docs/execution-providers/NNAPI-ExecutionProvider.html

**Comparability warning:** Reported rank-1 and mAP are drawn from *different
published gallery protocols and checkpoints*. They cannot be ranked as a
head-to-head ATLAS accuracy result. Market-1501 is not a decades-long family
archive, an Unknown-open-set test, or a privacy/license clearance. The model
zoo itself shows sharp cross-dataset declines. A high same-clothes score
can be *actively misleading* in long-term identity recognition.

## First-stage no-private-data experiments

1. Verify code and **each specific checkpoint**: immutable upstream URL,
   version/commit, license, notices, source training datasets, redistribution
   rights and SHA-256. Treat unknown/withdrawn terms as a hard stop.
2. Compare OSNet x0.25/x0.5, Intel 0287, and one FastReID compact export
   using the exact SAME independently reviewed held-out probe/gallery manifest
   with person-and-session-disjoint calibration/holdout partitions.
   Do not download proprietary benchmark images or any user's photos.
3. For every model generate ONLY opaque-ID cosine-score CSVs locally.
   Run `benchmark_reid_scores.py` unmodified for every model; scores
   from different networks are **not** pooled or treated as probabilities.
   Report threshold-locked coverage, mistaken-known and unknown false offers,
   session-cluster Wilson bounds, and condition-specific error rates.
4. Hard negatives: same clothing worn by different people, uniforms,
   lookalike physiques, occluded subjects, back-only/cropped bodies, old versus
   recent photos, summer versus winter clothing, lighting/colorization, family
   resemblance, missing detector crops and multiple people in one scene.
   Record absent-body/face extraction as *abstention*, not a silently dropped
   difficult trial. Keep repeated burst shots in one provenance/session group.
5. Compare in ablations: face-only; body-only **as a retrieval diagnostic**;
   face+session; face+body+session; and all with clothing/context. Group
   sources that arise from one image, event or tracklet in ONE correlation
   group, not independent votes. Contradictory cues trigger review or Unknown.
   Never make body, clothing, silhouette or event-context alone an identity
   confirmation, even when top-1 is high.
6. Hold out unrelated **Unknown people** and unrelated **known but different
   people**. Never tune the similarity threshold on held-out examples.
   Separate session-disjoint from identity-disjoint evaluations explicitly.
   If a cohort is too sparse, suppress its empirical claim; synthetic
   fixtures validate code paths only, not accuracy.

## Why the session-cluster gate matters

The previous benchmark counted 100 photos as 100 Bernoulli trials, even if
21 came from one event. With **one** calibration false offer, the one-sided
95% Wilson bound is **4.36%** for 1/100, but **5.41%** for 1/80
independent session clusters, exceeding the 5% research budget. The new
threshold sweep requires BOTH *per-observation* and *any-error-per-session*
upper bounds to pass. It also reports per-session error rates in overall
and eligible condition strata. This is deliberately conservative for
burst-photo correlations, yet still an approximation: sessions on adjacent
days, duplicated image crops and same-wardrobe shoots can remain dependent.
These numbers **cannot authorize unattended identity merges**.

All synthetic tests are offline and standard-library-only. No sample names,
photos, paths, scores, opaque IDs or session identifiers are printed by the
aggregate benchmark report.

## Android design constraints before even proposing integration

- One model/crop at a time on device; no background crawl without clear local
  user opt-in. No export/upload of user embeddings. Keep originals unchanged.
- Detector bbox/orientation/crop provenance must be precise; missing person
  crop, low-resolution back-only torso or very high occlusion = Unknown, not a
  hallucinated embedding. Do not conflate detector *confidence* with re-ID.
- Start with ONNX Runtime **CPU/XNNPACK** and compare *exact same graph* with
  Android NNAPI only after operator coverage is verified. NNAPI partitioning,
  CPU fallbacks, fp16/INT8 output drift and memory copies can make it slower.
  Mobile runtime size can be reduced via model-specific operators if needed.
- Measure on a real S24+ (not inferred from FLOPs): cold-start and steady
  median/p95/p99 single-crop latency, resident/peak RAM, cold package/model
  bytes, energy/battery per 1,000 items, thermal throttling after sustained
  load, CPU/GPU/NNAPI fallback behavior, and score drift from FP32 to FP16/
  INT8. Repeat with airplane mode/network denied and a fully cold cache.
- Allow cancellable incremental processing under Android lifecycle/storage
  constraints, low-memory eviction and on-device model version auditing.
  No embedded Python, private material in CI, silent face uploads, or
  model weights in the app until legal + statistical + performance gates pass.

## Decision and blockers

**Current decision:** build trustworthy score/evaluation and model-provenance
infrastructure first. OSNet x0.25 and Intel 0287 are highest-value first
benchmarks; FastReID provides an accuracy comparison after a legal lightweight
export is identified. **No actual inference benchmark has been run** here;
benchmark table numbers are upstream-published research data only.

**Not cleared:** exact pretrained artifact rights; private consented
evaluation cohort; representative long-term, back-facing Unknown negatives;
real Android p95/resource profile; cross-domain/appearance-change calibration.
None of these gaps can be compensated by high CCTV rank-1 accuracy.


## Full synthetic admission gate (2026-10-18)

The dedicated V5 workflow historically compiled and executed only four named
test modules (with duplicate commands), leaving newer model-provenance,
calibration-strata, spatial multi-person, paired-model, Unknown-control and
retrieval tests outside its automatic CI evidence. The Lane C workflow now
compiles **every** module and uses standard-library `unittest discover` with
`test_*.py` to include all future dedicated synthetic regressions without
maintaining a handwritten allowlist. To reproduce locally:

```sh
python -m compileall -q tools/identity_fusion
python -m unittest discover -s tools/identity_fusion -p 'test_*.py' -v
```

These checks measure **implementation invariants**, not OSNet, FastReID,
Intel-0287 or S24+ recognition quality. No network, private photo sample,
pretrained artifact, phone connection or Android UI is required. The workflow
remains bounded to its original short synthetic-only runner and does not
trigger a native Android build. A successful run is a **necessary but
insufficient** gate for future model-candidate acceptance.

The subsequent evidence gap remains a consented, independently annotated,
model-specific comparison on a frozen gallery/roster and **open-set** split:
same person in different clothes years apart; a rear-facing/partial image
with visible hair or posture but no face; lookalike strangers in matching
outfits; people at a shared event; mislocalized boxes; and unknown subjects
for whom the correct answer is abstention. Evaluate whole-person clusters
and session/exposure families, not images as independent trials. Any new
model must pass independent legal artifact, group-safety and device-resource
reviews before **even an unconfirmed People suggestion** is considered.


## Paired front-to-rear retention audit (2026-10-18)

The optional `audit_paired_pose_retention.py` examines **condition change
within the same independently reviewed known person**, using existing frozen
score/provenance/roster manifests. It requires a separate in-memory opaque
`pair_rows` manifest with exactly `front_probe_uid,rear_probe_uid`.
Each held-out subject appears in **at most one pair**; both probes must have
the same reviewed person and non-UNREVIEWED subject group but **different
sessions, exposure-lineage groups and event clusters**. The first view must
be tagged `front`, the other `back` or `partial`. Calibration and holdout
scores, all independent Unknown controls, and the exact candidate roster are
passed through the existing conservative model-score safety gate. Calibration
alone sets the similarity floor; a caller's top-two margin is not fitted on
heldout images. No unlabeled or Unknown probe can be claimed to be a
known-person pose pair.

The aggregate output includes the complete 3×3 transition counts
`front_correct/wrong/abstain` by `rear_correct/wrong/abstain`, a protected
minimum-size condition breakdown, and the fraction of reviewed rear probes
correctly offered. This is **not** an accuracy claim for an unselected
population: the pair manifest could be biased, and distinct opaque event
labels can still conceal photos from one real-world occasion. Valid
declarations do **not** independently prove capture separation, face
visibility, garments changed, or a causal effect of rear pose; all of
these limitations are explicit output fields.

On a deliberately **fictional** 25-person stress fixture with 25 correct
front offers, the held-out rear views produce 20 correct and **5 wrong**
offers; the resulting 80% rear-review correctness is a synthetic expected
assertion, **not** an OSNet, FastReID, Intel or ATLAS measurement. Missing
records, identity disagreement, replicated exposure/event, identical
sessions, stale/alien probes, wrong-pose labels, repeated subjects,
unknown-to-known substitutions, raw private columns and NaN thresholds
are rejected. No models/images/embeddings or production People writes are
used. A pre-registered, complete, independently annotated real-photo
paired cohort and frozen digest of its pair list remain prerequisites for
any comparative generalization.


### Rear-facing Unknown-control coverage gate (2026-10-18 follow-up)

A known-person paired-retention result can be severely misleading if its
Unknown-person trials are all easy, front-facing examples. The paired
pose audit now additionally **requires at least 20 independently declared
anonymous subjects AND 20 event clusters with back/partial observations
in each of calibration and holdout**, before computing its known-person
pose-transition matrix. The minimum cannot be lowered below 20 by callers.
A burst or multiple image variants of one anonymous subject or event
counts **once** regardless of photo count. The reported calibration
and holdout Unknown rates count any false review offer per subject/event
and include one-sided 95% Wilson upper bounds, as well as minimum-size
overlapping rear-condition tag diagnostics. These are uncertainty measures,
**not** model identity probabilities, and 20 groups are not sufficient to
establish a 5% false-offer risk ceiling: zero errors in 20 independent
groups still gives a much wider upper bound.

The strict gate deliberately rejects a perfectly paired set of known
subjects when the rear Unknown controls are absent or repeated under
new filenames but one declared subject/event group. It does not infer
unrecognized unknown people or prove declared groups correspond to
independent real captures. Actual cohort licenses, annotations,
sampling bias, cross-camera/clothing changes, consent, and Android
inference validation remain unresolved.


### Reproducible offline four-manifest pose audit CLI (2026-10-18)

`run_paired_pose_benchmark.py` invokes the research-only paired-pose
audit on four **separately prepared, frozen and SHA-256-pinned** UTF-8
CSVs: `--scores`, `--roster`, `--provenance`, and `--pairs`. Each
requires its corresponding `--<name>-sha256` full lowercase hex digest.
The pair CSV header must be exactly
`front_probe_uid,rear_probe_uid`; other manifests use their existing
strict declared schemas. A reproducible local invocation is:

```sh
python tools/identity_fusion/run_paired_pose_benchmark.py \
  --scores scores.csv --scores-sha256 <64-hex-digest> \
  --roster roster.csv --roster-sha256 <64-hex-digest> \
  --provenance provenance.csv --provenance-sha256 <64-hex-digest> \
  --pairs frozen_pairs.csv --pairs-sha256 <64-hex-digest>
```

All CSVs must be bounded, ordinary local files (not symlinks) with exact
headers and no extra identity-bearing/private columns. Checksum mismatches,
invalid Unknown controls, aliasing or malformed rows produce a generic
failure without leaking the subject IDs or supplied local paths. The only
successful output is aggregate JSON with explicit `auto_confirm_allowed:
false`. This does not write, move, upload or delete media. File integrity
checks can reproduce what was evaluated, **not** prove that the chosen
cohort, candidate roster, top-two margin or pair-selection rules were
independently committed before inspecting holdout outcomes. That
external preregistration is still an unfulfilled admission gate.


### Protected rear-facing Unknown calibration safety — 18 October 2026

The paired-pose evaluator now **refuses** a front-biased calibration
cohort that passes the *global* false-review-offer threshold yet exceeds
a separately enforced **5% one-sided 95% Wilson upper ceiling** among
rear/partial-view Unknown people. Only the calibration split selects the
similarity floor and supplies this admission gate. At the chosen frozen
floor, both the anonymous **subject-level** and **event-level**
any-false-offer bounds must pass using unrounded numbers. Heldout outcomes
are reported, never inspected to optimize or reject the calibration
threshold; downstream model comparison must examine them only once.

This is deliberately stronger than requiring 20 declared rear Unknown
groups: with **zero errors**, 51 independent groups still have a Wilson
upper bound above 5%, whereas **52 groups** fall below it. One error
among 20 separately identified rear Unknown events/people is also unsafe
even when 80 additional easy front-facing Unknowns make the overall
100-trial calibration statistic look acceptable. Synthetic tests cover
both traps, with separate verified condition labels and no real
identity or photo inputs.

The result carries
`rear_unknown_calibration_subject_event_bounds_passed=true` and a
separate explicit 0.05 risk ceiling only after both gates pass. This is
not a certified model probability, a cross-archive generalization
claim, or proof that declared event/subject groups are physically
independent. A rejected calibration cohort must **not** be rescued by
looking at holdout scores; preregistered additional independent
calibration data or an alternative independently fixed model/margin
is needed. The benchmark never automatically confirms identity.


### Independently declared paired-pose study protocol (2026-10-18)

`preregistered_pose_study.py` adds a stricter, **optional** research
entrypoint than the exploratory four-CSV pose CLI. Before inspecting a
model's holdout outcomes, the researcher must independently prepare and
commit a small versioned JSON study protocol and record its exact
SHA-256 outside this tool. The protocol freezes:

- opaque study/model IDs and a **declared** model checkpoint SHA-256;
- independently reviewed truth, eligible candidate roster, session/
  photo-lineage/event/subject provenance and front/rear-pair CSV SHA-256s;
- the model's single top-two margin, fixed 20-person minimum paired
  holdout cohort, fixed 10-pair condition breakdown minimum, the 5%
  rear Unknown calibration subject/event Wilson ceiling, the
  front→rear correct/wrong/abstain endpoint and calibration-only rule.

Invocation additionally requires the score CSV and its observed SHA-256
(the score file is produced later by the evaluated model). Provide exactly
`--protocol /path/protocol.json --protocol-sha256 <digest>` and
`--truth/--scores/--roster/--provenance/--pairs` with their corresponding
`--<name>-sha256` flags. The model's checkpoint SHA-256 is **declared**;
the tool **does not read or verify model weights or inference execution**.
A separately frozen truth manifest has exactly
`split,probe_uid,truth_uid,session_uid,condition` and is checked
against *every* scored probe. A rehashed truth file with altered subject,
condition or session labels cannot substitute for the pinned truth.
Even after regenerating a valid `--pairs-sha256` flag, an edited pair
file cannot pass the independently frozen protocol hash check.
Unexpected protocol keys, duplicate JSON keys, booleans in numeric fields,
NaNs, non-opaque IDs, symlinks, omitted/altered files and safety-budget
relaxations fail closed with redacted errors. No names, paths or identities
appear in successful reports.

**Crucial limitation:** a recorded hash guarantees bytes, **not
chronology**. Researchers must publicly or independently timestamp
the frozen study protocol *before* viewing holdout scores, record
the first-and-only holdout evaluation, verify actual checkpoint bytes
and preprocessing/inference identity, and audit image/subject/event
annotation quality. A freshly written protocol after examining results
can trivially pass local checks, so the output always declares
`external_pre_holdout_protocol_commitment_verified=false`,
`model_checkpoint_bytes_or_execution_verified=false` and
`auto_confirm_allowed=false`. This is a reproducibility harness,
not an empirical endorsement of OSNet, FastReID or other re-ID models.


### Version 2: all eligible front/rear known people, not the easiest 20

A frozen `pairs.csv` hash alone does not stop a researcher from picking
the easiest 20 rear-facing people **before** freezing it and simply omitting
the rest. Optional preregistered pose protocol `schema_version: 2`
therefore requires an additional exact
`"pair_selection_policy":
"every_eligible_known_subject_first_lexical_independent_pair_v1"`.
Version 1 remains readable for backwards compatibility, but **does
not guarantee** full eligible-subject inclusion.

The version-2 analyzer examines the **entire scored holdout truth and
independently frozen provenance inventory**, **not similarity values**.
For each known identity it requires one internally consistent reviewed
subject-cluster identifier. It enumerates reviewed front-only and
back/partial probe categories, then selects the first lexicographically
ordered opaque front/rear pair with **different session, event and
photo-lineage IDs**. Every known identity with at least one such pair
must be present **exactly once** in the supplied pair list with the
same two selected probe IDs; neither a subset nor a convenient
higher-scoring alternative is permitted. Anonymous Unknown controls
remain separately required by the calibration and heldout safety gates.
The returned aggregate inventory records how many reviewed known
identities have any eligible pair and how many lack one, without names,
source IDs or private paths.

To bound pathological all-to-all scans, a study requiring more than
200,000 pairwise front/rear comparisons **fails closed**, rather than
silently sampling. Synthetic adversarial tests reject one omitted
hard-known subject despite correctly updated CSV/protocol hashes,
wrong alternate captures, contradictory or unreviewed subject groups
and an excessively large same-event all-pairs candidate set. Changing
the model's similarity scores alone cannot change the chosen pair.
Compound back/partial/occluded/appearance-shift conditions and the
separate rear Unknown calibration bound remain unchanged.

**Still not established:** genuinely complete camera/gallery coverage;
unbiased collection of the *photos themselves*; scientific
independence of declared events/lineages; independence of opaque-ID
assignment; or actual re-ID accuracy. Model-independent lexical
sampling is a deterministic protocol, not a representative estimator
of all photos, poses or people in the real archive. All identity
suggestions remain unconfirmed and research-only.


## Offline checkpoint-source corroboration — 2026-10-19

An independent review of the primary model sources confirmed why a general
framework license does **not** clear every pretrained artifact:

| Candidate | Checkable source evidence | Outstanding checkpoint admission blocker |
| --- | --- | --- |
| Intel Open Model Zoo \`person-reidentification-retail-0287\` | Its [model-specific \`model.yml\`](https://github.com/openvinotoolkit/open_model_zoo/blob/master/models/intel/person-reidentification-retail-0287/model.yml) declares **3,571,437 bytes**, **SHA-384** \`c650c97722d597efd65fe041d67e17658d776c45d56736b817264ec544d8a9b5a5e8cdc1a0920cc2aada61151c1a9191\`, NCHW \`1×3×256×128\`, and the unexpectedly named \`person-reidentification-retail-0267.onnx\`. Its license field points at the Open Model Zoo repository's Apache-2.0 license. The [model specification](https://github.com/openvinotoolkit/open_model_zoo/blob/master/models/intel/person-reidentification-retail-0287/README.md) states 256-dimensional BGR body embeddings and **under-50% upright-person occlusion support**. | No model bytes/ONNX graph executed; training datasets, checkpoint rights and external license notices need explicit independent review. Current upstream links are on mutable \`master\`, not automatically an immutable legal attestation. |
| Torchreid OSNet x0.25 | [Official architecture/model zoo](https://github.com/KaiyangZhou/deep-person-reid/blob/master/docs/MODEL_ZOO.md) and [MIT-labelled model repository](https://huggingface.co/kaiyangzhou/osnet/tree/main) distinguish **ImageNet-only** initialization from an MSMT17-trained re-ID checkpoint. | ImageNet initialization does **not** count as a pretrained person re-ID benchmark; training-data/weight and derivative-export terms remain unresolved. A PyTorch pickle checkpoint must never be loaded from unverified bytes. |
| FastReID | The [Apache-2.0 framework](https://github.com/JDAI-CV/fast-reid) links a [model zoo](https://github.com/JDAI-CV/fast-reid/blob/master/MODEL_ZOO.md) and [separate benchmark-dataset setup](https://github.com/JDAI-CV/fast-reid/blob/master/datasets/README.md) for Market-1501, DukeMTMC-reID and MSMT17. | An Apache-2.0 *framework* license is not an individual checkpoint, training-data, initial backbone or redistribution audit. No FastReID checkpoint has been pinned, loaded, licensed or deployed. |

The new pure-stdlib research script
\`python tools/identity_fusion/corroborate_intel_manifest.py
--manifest ./model.yml --manifest-sha256 <sha256>\`
reads one **independently obtained, exact-byte-pinned, ordinary** local
Intel upstream YAML file. It does not connect to the network or use a
general-purpose YAML interpreter. It accepts the deliberately narrow
2023.0 Intel manifest shape and corroborates the official registry's
specific ONNX filename, byte count, SHA-384, source URL prefix, NCHW
dimension and repository license **pointer**. An incorrect checksum,
tampered/truncated manifest, repeated file, malicious URL, unexpected
fields, false license URL, symlink or size mismatch fails closed with
a redacted generic message. This checks *only manifest metadata*:
even a perfect result does **not** prove the file was fetched from the
legitimate upstream source, that the model graph is really 0287 instead
of 0267, that any checkpoint bytes are intact, or that weights/data
are legally redistributable. A self-chosen manifest SHA-256 without an
independent immutable source cannot prove provenance.

**Decision:** Intel's inspectable source manifest makes it a useful
candidate for an eventual, separately authorized byte/graph audit,
but is NOT an accuracy benchmark or a grant of permission. OSNet
x0.25 and Intel remain the practical light-network comparison;
FastReID is an unconfirmed research comparator until a suitable,
legally evidenced exported checkpoint exists. Do not package any
model, inspect private photos or emit unconfirmed identity proposals
until separate legal, consented open-set performance and S24+ device
gates pass.


### Known-subject cluster alias guard for pose-protocol v2 (2026-10-19)

The v2 exhaustive pair list previously guaranteed that all trials
belonging to **one known truth person ID** used **one reviewed subject
cluster**, but did not enforce the reverse relationship. Two different
known person truth IDs could accidentally point to the **same reviewed
subject cluster**. Their apparently separate front/rear trials would
then count as multiple independent identities, distorting the
person-level false-offer denominator and hiding duplicate-label errors.
The strict v2 protocol now verifies a **one-to-one mapping between
known truth identities and reviewed subject cluster IDs** for its
whole heldout known-person inventory. It refuses the entire study if
one subject cluster is claimed by competing known truth IDs, even if
each ID has internally consistent source provenance, a complete
front/rear pair, independently recomputed provenance SHA256 and an
updated protocol SHA256. A successful v2 output explicitly reports
\`known_truth_to_reviewed_subject_mapping_one_to_one=true\` and still
declares physical cluster correctness **unverified**.

This is a *metadata consistency* safeguard, not a biometric identity
proof. External independent reviewers must still verify whether a
cluster truly represents the same person across time. No automatic
person merging or People database update occurs. Version-1 exploratory
reports remain readable but do not assert this stronger v2 invariant.


### Intel model-specific rights correspondence — checked 19 October 2026

The upstream project's maintainer conversations materially refine (but do
**not eliminate**) the blocker for redistributing an Intel person-reID
checkpoint:

* [Open Model Zoo issue #4045](https://github.com/openvinotoolkit/open_model_zoo/issues/4045)
  asks **specifically about \`person-reidentification-retail-0287\` and
  \`0288\`**, including redistribution of XML/BIN in commercial offline
  products, whether their underlying training datasets permit this, and
  royalty/authorization requirements. A project contributor replies that
  **the repository is Apache-2.0 licensed** and recommends seeking legal
  advice for the particular product. A follow-up specifically requests
  clarification of model-specific weight and training-data restrictions.
  **No model-specific training dataset chain or unconditional 0287
  checkpoint redistribution approval is established by that exchange.**
* [Issue #4059](https://github.com/openvinotoolkit/open_model_zoo/issues/4059)
  concerns the **different** \`person-reidentification-retail-0286\`.
  A project contributor explicitly says **0286 pretrained weights** are
  under Apache-2.0, but cannot reconstruct the precise training-data
  history; another contributor says the data were mainly internal.
  **Do not inherit an 0286-specific licensing or training statement for
  0287.** Those models have distinct manifests and artifacts.
* [Issue #4060](https://github.com/openvinotoolkit/open_model_zoo/issues/4060)
  says the Apache-2.0 license also applies to pretrained weights
  generally while again reporting that some source training datasets
  may be unrecoverable. That statement strengthens the plausible
  *license* position but **does not verify 0287-specific data provenance**.
* [OpenVINO's own model-zoo documentation](https://docs.openvino.ai/2024/documentation/legacy-features/model-zoo.html)
  describes pretrained Open Model Zoo models as Apache-2.0 licensed.
  This is useful official umbrella-license evidence, not independent
  identification of the individuals/data or all obligations attached to
  this exact 0287 checkpoint.

**Evidence classification (not a legal opinion):**
\`repository_apache2_source=documented\`;
\`broad_pretrained_weight_license_position=documented\`;
\`0287_exact_graph_vs_0267_filename=unverified\`;
\`0287_training_dataset_and_rights=not_established\`;
\`application_specific_distribution_notices=not_prepared\`;
\`production_approved=false\`. The existing machine-readable
\`candidate_model_artifacts.json\` correctly retains
\`weight_rights_status=unverified\`; do **not** change this to
\`approved\` just because the project has an Apache-2.0 repository
license. A future clearance review must associate a concrete checkpoint
hash and legally sufficient specific evidence, record license/notice
obligations and decide distribution rights explicitly.

**Prioritization:** Intel remains the most promising *inspectable
first benchmark candidate* because its manifest provides a precise
ONNX artifact, source digest and compact network, while OSNet x0.25 is
a strong small-network comparator and FastReID an optional heavier
ceiling. Until exact provenance/rights and preprocessing are resolved,
the next *safe* experiment is entirely synthetic verification of
conversion/runtime contracts, not packaging weights or predicting
people from private photographs.


## Checksum-pinned, no-execution ONNX container preflight — 2026-10-19

New **optional** standard-library-only `inspect_onnx_graph.py` can
inspect a previously acquired standalone local ONNX blob without
importing ONNX, ONNX Runtime, Torch, model weights as executable Python,
or any photograph. It uses a bounded subset of the official [ONNX
protobuf schema](https://github.com/onnx/onnx/blob/main/onnx/onnx.proto)
(the wire-format, not the ONNX runtime) and the [Intel 0287 model
specification](https://github.com/openvinotoolkit/open_model_zoo/blob/master/models/intel/person-reidentification-retail-0287/README.md):
one static Float32 NCHW input `[1,3,256,128]` and one Float32
embedding output `[1,256]`. The inspector verifies a separately
provided SHA-256 over the exact local file, one top-level graph and
standard-domain opset, limits IR version, graph size and protobuf field
counts, counts standard-domain operators and initializers, and refuses
custom operator domains, embedded subgraphs, locally defined
functions/training graphs, tensor external-data references,
sparse/exotic tensor metadata, symbolic/ambiguous shapes and
malformed/truncated protobuf fields. It never returns tensor values,
graph node names, private file paths or identifiers.

Use **only when checkpoint acquisition and review are separately
authorized**:

```sh
python tools/identity_fusion/inspect_onnx_graph.py \
  /path/to/already-obtained-model.onnx \
  --sha256 YOUR_INDEPENDENT_64_CHAR_LOWERCASE_SHA256
```

Add `--require-registered-intel-0287` to compare the **same file
bytes** additionally against the versioned Intel 0287 candidate
register's 3,571,437-byte SHA-384 pin (the upstream model manifest calls
its ONNX file `person-reidentification-retail-0267.onnx`). This
additional match establishes agreement with a locally recorded
upstream byte fingerprint, **not** that the source was independently
authenticated, that its graph implements the claimed trained model,
or that its rights are cleared. Without that flag the report always
records `registered_intel_0287_sha384_matches=false` and
`upstream_checkpoint_identity_verified=false` even if static
shapes are correct. A synthetic fake graph with the right dimensions
can pass generic structural inspection, as the tests demonstrate.

**Not a full ONNX validator:** this deliberately constrained preflight
does *not* implement ONNX operator type/shape inference, validate all
tensor semantics, authenticate graph provenance, guarantee that a future
runtime safely executes malformed or adversarial models, establish
all preprocessing details (Intel's BGR pixel **scale/resize still
unresolved**), legal training-data/weight redistribution rights, nor
measure real recognition accuracy or S24+ latency/energy. Invalid
or unsupported ONNX schemas **fail closed**, including some legitimate
but unreviewed graphs. The tool outputs explicit
`model_semantics_or_operators_validated_by_onnx_checker=false`,
`full_execution_safety_guaranteed=false`,
`weights_training_rights_verified=false`,
`open_set_identity_accuracy_verified=false`,
`android_runtime_measured=false`,
`auto_confirm_allowed=false` and `production_approved=false`.
Actual graph identity still needs an *independently authenticated*
byte pin, trusted ONNX schema validation and a safe offline inference
environment **before** any on-device candidate. The CI suite uses
made-up protobuf bytes only, with no actual model downloads.


### Additional untrusted-protobuf regression hardening (2026-10-19)

The standalone static inspector now refuses **any explicitly unrecognized or UNDEFINED ONNX attribute-type enum**, not only the known graph/sparse cases. This prevents a syntactically valid protobuf discriminator such as `type=99` from being mislabeled as inside the deliberately inspected subset. New deterministic, seed-fixed mutations of entirely invented protobuf bytes check that malformed inputs either fail closed with `ValueError`, or return only non-authorizing aggregate fields if a mutation still leaves a structurally allowed graph. A separate large repeated-metadata fixture verifies the existing 100,000-fields-per-message cap. These are regression/stress tests, **not exhaustive fuzzing or execution-safety certification**: a proper ONNX checker, independent source-byte integrity, legal rights, preprocessing parity and real person-reID testing remain mandatory before inference or Android integration.


## Immutable source-object checkpoint: Intel OMZ 0287 (2026-10-19)

Earlier offline source corroboration accepted caller-selected SHA-256 and an upstream page ending in mutable `master`. This is useful for integrity but does **not** distinguish an actual published revision from an invented manifest. A complementary, separately pinned **Git source-object** record now identifies precisely which source bytes were observed at an upstream historical revision:

| Evidence | Independently observed immutable identifier |
| --- | --- |
| Repository | [openvinotoolkit/open_model_zoo](https://github.com/openvinotoolkit/open_model_zoo) |
| Commit | [`af810de42bcf460a18610481f639dcdc86249e69`](https://github.com/openvinotoolkit/open_model_zoo/commit/af810de42bcf460a18610481f639dcdc86249e69), author timestamp 2024-02-12T18:10:35Z |
| Model manifest | [`models/intel/person-reidentification-retail-0287/model.yml` at the commit](https://github.com/openvinotoolkit/open_model_zoo/blob/af810de42bcf460a18610481f639dcdc86249e69/models/intel/person-reidentification-retail-0287/model.yml), Git blob SHA-1 `66bd141ab025c72eac575c0491a32a83b546e60f` |
| Repository license | [`LICENSE` at the same commit](https://github.com/openvinotoolkit/open_model_zoo/blob/af810de42bcf460a18610481f639dcdc86249e69/LICENSE), Git blob SHA-1 `261eeb9e9f8b2b4b0d119366dda99c6fd7d35c64` |

The two Git blob identifiers are recorded in
`tools/identity_fusion/upstream_model_source_pins.json`; they were
retrieved directly for the specified revision from the source
repository. New `attest_upstream_source.py` is an **offline** pure-stdlib,
read-only corroboration stage. It accepts independently obtained local
copies of *both* documents and their caller-supplied SHA-256 digests.
It computes the actual Git object SHA-1 for each byte sequence
(`sha1(b"blob " + str(len(data)).encode("ascii") + b"\0" + data)`)
and compares to the immutable pinned objects. It also re-runs the
0287 artifact-size/SHA-384, fixed NCHW and Apache-license-pointer checks
against the existing candidate register. No arbitrary repository URLs,
paths, checkpoint bytes, images, models or GPU jobs are read by the
program.

Usage on **separately acquired, authorized local source text**:

```sh
python tools/identity_fusion/attest_upstream_source.py \
  --manifest /path/to/revision-pinned-model.yml \
  --manifest-sha256 VERIFIED_LOWERCASE_SHA256 \
  --license /path/to/revision-pinned-LICENSE \
  --license-sha256 VERIFIED_LOWERCASE_SHA256
```

The program rejects symlinks, oversized files, malformed registration
records, incorrect SHA-256, either wrong upstream Git blob, malformed
source metadata and missing/wrong license header; successful reports
have no private paths or IDs and explicitly set all model inference,
weight-rights, training-data and production checks to `false`.
Tests use invented manifest/license text and synthetic Git object pins,
not downloaded models or private gallery media.

**Evidence boundaries:** Git uses SHA-1 for these historical blob names,
whose collision resistance is weaker than SHA-256. A digest by itself
does not authenticate GitHub as the source, cryptographically verify the
commit's signature/tree ancestry, authorize distribution of any model
weights, establish provenance/rights for the data used to train 0287,
or prove the actual downloaded `0267.onnx` is the promised 0287 graph.
Record the source commit and compare it independently when doing a
real study. The stage does **not** download anything, read private photos,
execute ONNX, approve or bundle an artifact. This removes the
*mutable-master source ambiguity*, **not** the separate
legal, preprocessing, accuracy or S24+ performance release gates.


### Same-revision Intel 0287 *evaluation* resize reference (2026-10-19)

The upstream revision pinned above also includes an unambiguous
[model-specific `accuracy-check.yml`](https://github.com/openvinotoolkit/open_model_zoo/blob/af810de42bcf460a18610481f639dcdc86249e69/models/intel/person-reidentification-retail-0287/accuracy-check.yml),
Git object SHA-1 `7e4aed04f03f88540533e8ac74c281589d8b18ae`.
Its **Market-1501 evaluation harness** selects exactly
`type: resize`, `dst_width: 128`, `dst_height: 256`,
`use_pillow: True`, `interpolation: BILINEAR`. This grounds the
published metric's **spatial evaluation-resize recipe**. It does **not**
prove a particular Android image decoder, OpenCV crop/resize operation,
RGB/BGR channel conversion, or float range `[0,1]` versus
`[0,255]` reproduces the model's original pretraining/inference
contract. In particular, Intel's native input **scale and full
preprocessing parity remain unresolved**; no production identity
inference is enabled.

The additive, backward-compatible **v2** source register
`upstream_model_source_pins.json` now records the third source-object
pin at that same immutable upstream commit. Existing manifest+license
checks still work unchanged. Researchers can optionally provide both
of these flags to `attest_upstream_source.py`:

```sh
  --accuracy-check /path/to/accuracy-check.yml \
  --accuracy-check-sha256 INDEPENDENT_64_CHARACTER_LOWERCASE_SHA256
```

The tool refuses a missing half of the two-flag pair, stale/altered
config bytes even if the supplied SHA-256 is recomputed, unsafe
symlinks, wrong Git object hash and settings different from the
pinned market1501/Pillow-bilinear reference. Successful aggregate output
reports `validation_source_object_verified=true` with explicit
`native_intel_pixel_scale_and_resize_parity_verified=false`.
Without these flags, the field remains `false` and no preprocessing
claim is made. This stage still downloads/executes **no model bytes**.

ATLAS already has a synthetic-only `benchmark_resize_parity.py`
comparing the Pillow bilinear and OpenCV linear resizing algorithms.
The model-specific provenance now allows Pillow bilinear to be an
**evidence-backed reference** for Intel's published Market-1501
validation pipeline, not a universal guarantee that any other resize
algorithm or mobile implementation is equivalent. The next experimental
step is a reproducible synthetic byte-level resize comparison on an
independently specified color/channel contract; conclusions must never
be upgraded to real identity accuracy without a consented real-data
holdout and device validation.


## Intel 0287 published Market-1501 input *step* trace (19 Oct 2026)

Lane C now has direct source evidence for the **specific published
Open Model Zoo evaluation procedure**, beyond the model's BGR/shape
README claim. All of the following files are at immutable upstream
commit [`af810de42bcf460a18610481f639dcdc86249e69`](https://github.com/openvinotoolkit/open_model_zoo/commit/af810de42bcf460a18610481f639dcdc86249e69):

| Stage | Source and finding |
| --- | --- |
| Evaluate 0287 | [`models/intel/person-reidentification-retail-0287/accuracy-check.yml`](https://github.com/openvinotoolkit/open_model_zoo/blob/af810de42bcf460a18610481f639dcdc86249e69/models/intel/person-reidentification-retail-0287/accuracy-check.yml) (now Git-blob pinned in `upstream_model_source_pins.json`): `market1501`, width 128, height 256, `use_pillow: True`, `interpolation: BILINEAR`. |
| Read colors | [`data_readers/data_reader.py`](https://github.com/openvinotoolkit/open_model_zoo/blob/af810de42bcf460a18610481f639dcdc86249e69/tools/accuracy_checker/accuracy_checker/data_readers/data_reader.py) defaults to `opencv_imread`; [`data_readers/image_readers.py`](https://github.com/openvinotoolkit/open_model_zoo/blob/af810de42bcf460a18610481f639dcdc86249e69/tools/accuracy_checker/accuracy_checker/data_readers/image_readers.py) uses `cv2.imread(..., cv2.IMREAD_COLOR)`, which supplies BGR channel order by default. |
| Resize | [`preprocessor/resize.py`](https://github.com/openvinotoolkit/open_model_zoo/blob/af810de42bcf460a18610481f639dcdc86249e69/tools/accuracy_checker/accuracy_checker/preprocessor/resize.py) uses `Image.fromarray(data).resize((new_width, new_height), BILINEAR)`, then `np.array(data)`; this spatially resizes channels **without** reinterpreting input BGR bytes as semantic RGB labels or reordering channels in the array. |
| Wire evaluator | [`evaluators/model_evaluator.py`](https://github.com/openvinotoolkit/open_model_zoo/blob/af810de42bcf460a18610481f639dcdc86249e69/tools/accuracy_checker/accuracy_checker/evaluators/model_evaluator.py) supplies `launcher.fit_to_input` to `InputFeeder`. |
| Pack model input | [`launcher/openvino_launcher.py`](https://github.com/openvinotoolkit/open_model_zoo/blob/af810de42bcf460a18610481f639dcdc86249e69/tools/accuracy_checker/accuracy_checker/launcher/openvino_launcher.py) `fit_to_input` calls `_data_to_blob` (transpose to NCHW for four-dimensional input) and casts to the network tensor's element datatype with `astype(precision)`. Neither shown function divides by 255 or subtracts a mean. |

**Bounded conclusion:** for that published *OpenVINO evaluation
step* the expected **array sent to inference** is plausibly BGR
channels in raw 0–255 intensity units, cast to float32 for a float32
input, rather than Torchreid's normalized RGB tensors. This is
source-based reasoning for **that code path**, not proof of any
actual ONNX graph's internal normalization, the OpenVINO compilation
preprocessing stage, unexamined caller overrides, a particular mobile
image decoder, or training-time preprocessing. In particular:
the existing `get_contract("intel_omz_0287")` still has
`pixel_scale=None` and **refuses** any production-style tensor
construction; `spatial_resize_parity_proven=false` stays unchanged.

For independent, entirely fictional no-file regression tests, new
`synthetic_intel_0287_published_eval_tensor()` starts **only** from
the built-in pre-sized red/green/blue/gray stripes and constructs the
described BGR NCHW float32 0–255 *evaluation-stage* tensor. A synthetic
red RGB stripe becomes BGR `[0,0,255]`; blue becomes `[255,0,0]`.
The separate `synthetic_intel_0287_eval_sentinel_report()` outputs
those two synthetic examples together with explicit false values for
model weight/hash validation, actual graph semantics, full Pillow
resize byte parity, native preprocessing parity, real reidentification
accuracy, production approval and auto confirmation. It is
intentionally not a general-purpose photo preprocessing function:
no arbitrary photo/image bytes, file paths, model weights, networks,
or real subject data enter it.

**Next validation gates:** compare exact Pillow bilinear BGR image
resize against Android implementation on invented synthetic pixels,
inspect exact licensed checkpoint ONNX graph for internal arithmetic
preprocessors, separately document mean/std/range and any OpenVINO
postprocessing. Validate phone resource budgets and preregistered
consented front/rear/partial multi-year open-set identity performance
before allowing any body-based identity proposals. Never auto-confirm
a person's identity from appearance cues.
