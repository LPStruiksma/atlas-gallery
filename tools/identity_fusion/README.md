# V5 identity evidence — local v1 schema, not a re-identification model

**First isolated architectural milestone for [issue #10](https://github.com/LPStruiksma/atlas-native/issues/10), October 14, 2026.**

ATLAS will eventually recognize likely people in sparse, older, back-facing
and partially visible photographs by *combining* independent observations.
The difficult part is not storing cosine scores; it is keeping uncertain
evidence from silently becoming a false, permanent People relationship.
This first milestone supplies the versioned local evidence contract needed
to evaluate models rigorously. **No body/face model is integrated, and the
code does not yet extract, compare or infer identity from photographs.**

## Provenance and isolation

`tools/identity_fusion/evidence_store.py` has no networking, image
processing, MediaStore, Android, ML or V4 imports. It uses only Python's
standard library and a **separate local SQLite evidence database**.
`initialize()` detects and refuses existing non-ATLAS schemas, including
the separate V4 database and native People assignments DB. It refuses
unsupported future schema versions. There is no general-purpose
"attach a V4 database" path, no candidate confirmation write and no
automatic face/person reassignment operation.

The caller must explicitly provide:

- An opaque *asset UID* and exact device-local media path, optionally
  bound to a separately computed SHA-256 fingerprint. Never infer asset
  identity across moves, crops, rotation or file renames just from filename.
- A normalized person-region rectangle, front/back/side/partial/unknown
  visibility, occlusion fraction and optional timestamp. Reject NaN,
  out-of-bounds or geometrically impossible regions.
- For each candidate link: a candidate **person ID** (not name), evidence
  family `face/body/clothing/session/context`, positive/contradictory
  polarity, model version, source-reference and a *correlation group*.

No actual face, body, pose or clothing embeddings are stored in v1.
There is **no numerical identity confidence** masquerading as a probability.

## Avoid false confidence from correlated cues

A photo of someone from behind wearing a red coat, next to a friend,
at a particular event contains clothing, contextual and temporal clues.
Those may all be the *same event evidence*, not three independent reasons
to call this the same person. Assign them one correlation group
(e.g. `event-2020-12-03-session-2`); the review report counts that whole
group once, while still showing which families contributed.

`review_candidates` returns **proposed person IDs only**, independent
support-group counts, contradiction-group counts, contributing cue families,
`confidence_probability=None`, and `unknown_always_allowed=True`.
It has no confirm/merge/export mutator. Ranking is a deterministic
review-order heuristic, not an accuracy or safety guarantee; it must
never be used to auto-file 25,000 faces or infer who appears in an image.
Contradictions are prominently surfaced rather than averaged away.

If the correlation group metadata is wrong, multiple dependent pieces
may still be miscounted as independent; controlled real-device evaluation
is essential before a suggestion engine is deployed.

## Synthetic test coverage and limitations

Run without personal material, ML packages, device access or credentials:

```sh
python tools/identity_fusion/test_evidence_store.py
```

The fast GitHub job checks isolated schema creation/re-open/future-version
refusal, foreign-key and duplicate-ID protection, exact path validation,
bounded observation geometry, single-session correlated evidence,
contradictions, always-valid Unknown and no accidental writes to an
existing synthetic V4/Native SQLite database.

**Not yet implemented:** adapters for consistent V4 backup snapshots
and path-move evidence reconciliation; detector/body features;
face+body embedding inference; verified photo-session partitioning;
multiple decades/appearance changes; uncertainty calibration; user
review/undo UI; durable audit export; actual S24+ performance. V1 is a
testable foundation, **not** a solved V5 capability. No photographed
person or private path is included in source or CI artifacts.

## First read-only integration: V4 confirmed faces → V5 evidence references

`import_v4_snapshot.py` now exposes
`load_confirmed_v4_snapshot(snapshot_path)`, which reads only an **offline,
consistent standalone copy** of the private V4 SQLite database. The offline source is opened through no-symlink directory/file descriptors;
symlink and hardlink aliases, files over **512 MiB**, SQLite header
mismatches and source-file mutations during the bounded read are grounds
for rejection. The pinned bytes are deserialized into a **separate in-memory
SQLite database** with `query_only=ON`, so SQLite never reopens an attacker-
replaceable source pathname for any query. A Python/SQLite runtime without
`Connection.deserialize` fails closed; it does **not** fall back to URI
`mode=ro&immutable=1`. `-wal/-shm/-journal` sidecars are also grounds for
rejection. This still does **not** prove a separately copied input was
transactionally consistent: prepare it using SQLite's backup API while
source writers are coordinated or stopped. It checks schema
and `PRAGMA integrity_check`; this is NOT a command to copy the live
Termux V4 file while it is being written. Make a consistent local snapshot
with SQLite's backup API first, separately, before using this adapter.

Only `faces.review='confirmed'`, attached to real V4 person IDs, not ignored,
and with successfully indexed `images.state='done'` are considered. If
face geometry is invalid/outside known dimensions, or the source path
is not a supported exact Android local path, that record is **skipped**,
with aggregate count diagnostics only. File bytes, images and source
face embeddings are never opened by the adapter, and no filename
matching, moved-photo linking, age/appearance inference or new-person
guessing occurs.

For every valid confirmed face, the adapter constructs a separate
**in-memory V5 evidence store**, with the source `v4-face:<id>`,
`v4-photo:<id>` correlation group and a confirmed-*source* method
reference. It **does not copy native/V4 confirmation authority into V5**:
V5 still reports a review-only candidate with `Unknown` available and
no API to assign a person. The source V4 SQLite bytes remain unchanged.

V4 stores face location but not a trustworthy body occlusion estimate.
Accordingly, the V5 schema has an explicit `occlusion_known` flag:
`0` means no measurement, even though the numeric placeholder field is
zero; `1` means a measured numeric fraction. This is an additive,
backward-compatible migration of existing V5 v1 evidence DBs.
The adapter uses visibility `unknown` and unknown occlusion rather
than pretending it knows the camera angle or whether the person is
obscured. This distinction is essential before any body-model pilot.

Run the offline-only synthetic tests:

```sh
python tools/identity_fusion/test_evidence_store.py
python tools/identity_fusion/test_import_v4_snapshot.py
```

**Important:** No real V4 snapshot, phone or biometric data has been read,
created, installed or uploaded by these tests. A full source backup,
photo-path/move reconciliation, versioned observation lineage, body
detection and recognition-quality calibration remain future stages.

## Synthetic V5 evidence ablations and conservative Unknown gate

The separate offline `evaluate_review_quality.py` module now compares the
same reviewed observations under four evidence subsets: **face only**,
**face + session**, **face + body + session**, and **all available cues**.
It does not load media, infer body features, score a real person, create a
probability, or have an identity-confirmation function. It only re-ranks
the previously stored *review-only* candidate metadata using
`review_candidates(..., allowed_families=frozenset(...))`.

A hypothetical proposal is **offered for human review only** when it has
at least **two independent positive correlation groups** and no
contradicting groups. Otherwise the evaluator records an abstention:
**Unknown is always permitted**. This gate is experimental, not a
validated threshold or permission to auto-tag; if correlations between
days/contexts are wrong, the groups can still be dependent.

The independent case reference includes only an observation identifier,
a human-confirmed person ID (or None for Unknown), and one permitted
condition: front, back, side, partial, occluded, appearance shift,
lookalike, or unknown. It is supplied *in-memory by the evaluator*;
no ground-truth labels are written to the V5 evidence store. The output
is **only aggregates** by condition and evidence variant:

- Total trials, manual-review offers, correct/false person suggestions,
  abstentions, and correctly abstained Unknown observations.
- Offer coverage, false suggestions **per observation**, and false
  suggestions **per offered review**. These have explicit denominators
  and are not calibrated probabilities.
- The report also separates **false offers to Unknown people** from
  **wrong-known-person suggestions**, and identifies **known people missed
  by abstention**. Their denominators are explicit: unknown false-offer
  fraction per independently labeled Unknown trial, and known missed-
  abstention fraction per labeled known-person trial. A missing denominator
  produces `None`, not a misleading zero or probability.
- No observation IDs, person IDs, faces, private paths or scores appear
  in the report, even on error.
- The evaluator refuses a record with **more than 100 candidate identities**:
  the underlying review API deliberately returns at most 100. Silently
  truncating evaluation proposals could otherwise turn an omitted candidate
  into a fake success for Unknown abstention. This is a fail-closed
  research-data limit, **not** a person-recognition threshold.

Run the model-free synthetic test without photo access:

```sh
python tools/identity_fusion/test_evaluate_review_quality.py
```

The fictional fixture deliberately contains a back-facing correct
body+session identification, a lookalike Unknown person incorrectly
suggested by body+session, contradictory face evidence that forces
abstention, and multiple clothing/context/body cues correlated within
one night that correctly count as **one**, not three, supporting groups.
This makes a false suggestion a visible evaluation failure, rather than
a hidden side effect of a potentially impressive top-1 score. The extended
tests explicitly inject a **wrong known identity** as distinct from an
Unknown lookalike and force a 101-candidate case to fail closed.

This is **benchmark infrastructure** for a future consented and local
pilot, not a demonstrated ability to recognize a photographed person
from behind. Real held-out, diverse, reviewed examples are essential
before any quality claims or product integration.

## Held-out person-reID model-score benchmark — 15 October 2026

A new **model-agnostic, standard-library-only**, offline entrypoint
`benchmark_reid_scores.py` accepts precomputed **cosine similarity scores**
from an eventual local face/body model. It does **not load a photograph**,
download weights, produce embeddings, assign a person, or publish private
review data. All candidate/probe/person/session IDs in its input are opaque
pseudonyms; **do not supply names, filenames, paths, timestamps or GPS data**.
The CSV is used solely in memory, and the command emits JSON **aggregate
counts, rates, a chosen similarity floor and coarse condition strata**;
no row identifiers, individual scores or raw embeddings are emitted.

Required CSV header, in exact order:

```text
probe_uid,split,truth_uid,session_uid,condition,candidate_uid,similarity
```

- `split` is `calibration` or `holdout`. Each probe is represented by
  one or more candidate rows with the **same reviewed ground-truth person**,
  session and condition. Use the explicit literal `UNKNOWN` as the
  independently reviewed truth for a person not in the known-person gallery;
  blank truth is **rejected**, never interpreted as Unknown. If the model
  returns no candidates for a probe, add one `candidate_uid=NONE` row with
  an empty similarity.
- `similarity` is a finite cosine-like float in [-1, 1]; more positive
  means more similar. Only the top candidate can be offered, and an **exact
  top-score tie abstains**. This is not a calibrated identity probability.
- Identity and session keys **must not overlap across the two partitions**,
  including candidate identities; the benchmark refuses leakage. The
  held-out set is never used to select the similarity floor.
- At least **50 reviewed known + 80 reviewed Unknown** calibration probes,
  and **20 known + 20 Unknown** held-out probes are required. These are
  minimum software-safety sample sizes, **not sufficient to validate a
  production model**. The score manifest is capped at 100,000 rows,
  1,000 candidates per probe and 32 MiB on disk.
- The calibration-only threshold is chosen to maximize correct-known
  review offers **subject to both** unknown-person false-offer and
  wrong-known-person false-offer one-sided 95% Wilson upper bounds
  staying at or below 5% by default. If there is no meaningful candidate
  threshold meeting both constraints, the evaluation refuses a report.
  This is a deliberately conservative *research* rule, **not approval
  for automatic tagging**. The gate uses **unrounded** Wilson bounds;
  four-decimal rate formatting is output-only and never relaxes safety.
  The calibration selector sorts candidates once and sweeps distinct
  score levels in O(N log N) time, rather than scanning the whole
  calibration set for every possible floor. Held-out failures are shown
  as failures, never used to retune the floor after the fact.
- Output reports correct-known offers, wrong-known offers, Unknown-person
  false offers, missed known-person abstentions, correctly abstained
  Unknown cases, and their denominators. Conditions with fewer than 10
  probes are suppressed to avoid misleading tiny-cell evidence. Numeric
  bounds are uncertainty measures of aggregate rates, not ID confidence.

Run locally with **your own deliberately prepared scored manifest**:

```sh
python tools/identity_fusion/benchmark_reid_scores.py /local/path/to/scored-pairs.csv
```

Synthetic, private-data-free regression:

```sh
python tools/identity_fusion/test_benchmark_reid_scores.py
```

The fixture simulates genuinely Unknown lookalikes and wrong-known-person
reidentifications that slip through calibration and are correctly counted
as held-out failures. It also verifies stable threshold selection regardless
of held-out scores, partition contamination rejection, ID/token hygiene,
candidate-score ties, incomplete truth, nonfinite scores, statistical
minimums, tiny-cell suppression, and no writes to local input.

**Candidates for a separately consented, ON-DEVICE pilot** (research
shortlist, not selected components or actual S24+ measurements):

- [Torchreid / OSNet](https://github.com/KaiyangZhou/deep-person-reid),
  which publishes pretrained person-reID variants and documents
  ONNX/TFLite export. Check the **particular weight and training dataset
  terms** before use; the upstream code/weights being accessible does not
  grant rights to unrelated identity benchmarks or private collections.
- [FastReID](https://github.com/JDAI-CV/fast-reid), an Apache-2.0
  research toolbox with a published model zoo and ONNX deployment examples.
  Its full PyTorch training pipeline is **not** directly an Android gallery
  integration; size, operators, inference timing, license and cross-domain
  failure modes need independent evaluation.

A body-only model must be compared with a face-only baseline **on identical
held-out, session-disjoint trials** and an Unknown-open-set cohort;
colourful clothing, similar physiques, and same-event context can be
misleading correlated cues. Real image ingestion, face/body detection,
crop provenance, network-isolated ONNX/TFLite inference, and S24+ p95
latency/RAM/battery are **not implemented here**. Nobody is identified,
tracked or automatically tagged by this benchmark. A human always retains
the choice to reject suggestions or mark Unknown.

## Next safe measurable experiment

1. Implement a read-only adapter *against a verified consistent copy* of
   V4 (never a live WAL/main DB), restricted to already confirmed profile
   IDs and precise face regions. Record crop/orientation and provenance
   from the original source, and reject ambiguous path lineage.
2. Benchmark a freely licensed person/body detector + re-ID feature
   extractor **locally**, not on the private GitHub runner. Use
   intentionally diverse reviewed pairs: front/back, winter/summer,
   occlusion, changing age, same clothes for different people, plus
   Unknown identities.
3. Compare face-only baseline to face+session, then face+body rankings,
   measuring false-person suggestions per condition and p95 latency,
   RAM/thermal limits on the S24+. A few zero-error trials do not justify
   unattended automatic linking.
4. Only after defensible evaluation: present transparent, reversible
   review suggestions inside the *same* People/Discover experience.
   Confirmed identity remains V4's reviewed record; native ATLAS only
   receives checksum-sealed confirmed memberships.

## Joint visibility and hard-negative condition audits — 16 October 2026

The same held-out probe can be **back-facing and occluded**, or
**back-facing and a lookalike**. A single mutually exclusive condition
hid those error intersections. The precomputed-score benchmark now accepts
between one and three distinct labels joined with `+`, for example
`back+occluded`, `back+appearance_shift` or `back+lookalike`.
Labels are normalized in sorted order; invented labels, duplicates,
more than three labels, and `unknown` combined with another label are
refused. The plain single-label input remains fully compatible.

The report now includes both `by_condition` (exact joint intersections)
and `by_condition_tag` (marginal, **overlapping** condition totals).
A probe with `back+occluded` contributes once to the exact intersection,
once to the `back` marginal and once to the `occluded` marginal; it
contributes **only once** to the overall totals. Do not add marginal
subtotals as though they were independent observations. Each breakdown
includes the same session-cluster error audit. Exact or marginal groups
with fewer than ten trials are suppressed, with suppression counts
reported, rather than a fabricated small-cell error rate. No
per-case identifiers or scores appear in output.

The new regression cases create back-facing, occluded known-person wrong
matches and back-facing Unknown lookalikes; assert exact versus marginal
denominators, session-level counts, canonicalization order stability,
tiny intersection suppression, and strict rejection of malformed tags.
This is **model-free synthetic behavior testing**, not evidence of any
recognition accuracy. Use identical frozen held-out probe conditions for
each proposed model; never redefine the cohort after seeing its errors.

## Top-two ambiguity abstention experiment — 16 October 2026

A similarity **floor alone** can offer a wrong top-1 person when the runner-up
is almost indistinguishable. The held-out score benchmark now supports an
**optional, model-specific** research-only gate on the top two returned
candidate scores:

```sh
python tools/identity_fusion/benchmark_reid_scores.py \
  --min-top-two-margin 0.05 /local/path/to/opaque-scores.csv
```

For a given probe, a top-ranked candidate is offered for review only when
the top score is at least the floor, the top-1 score is **not tied**, and the
top-minus-runner-up gap is at least the requested margin. This margin is
**not** an identity probability and is not a universally sensible `0.05`
for all models. Research comparisons should report both the ungated
(`0.0`, backward-compatible default) and deliberately frozen, separately
calibrated margin variants. The similarity floor is still selected using
**calibration-only** independent people/sessions and both existing
probe- and session-level Unknown/wrong-known Wilson constraints. A margin
must never be tuned after seeing holdout outcomes.

The aggregate-only report exposes `minimum_top_two_margin`,
`near_tie_abstentions` and `exact_tie_abstentions`, both overall and by
condition. A probe returning only one candidate has no observable
runner-up, so its margin is unbounded by construction: **do not interpret
that as confidence** or bypass the Unknown gate. For a meaningful
ambiguity test the feature extractor must return at least two genuinely
competing gallery identities, including hard negatives. Missing candidates
must not be fabricated by copying the top similarity.

Fictional tests demonstrate a wrong-known-person and an Unknown lookalike
whose respective winning scores differ only 0.02 from the runner-up;
a 0.05 margin correctly turns both into **manual-review abstentions**,
without claiming real-world accuracy, modifying the manifest or using
biometrics. Validating this gate against held-out gallery density and
long-term appearance changes remains **mandatory** before deployment.


## Reproducible synthetic-only ONNX CPU profiling (16 October 2026)

`benchmark_onnx_runtime.py` is a **research harness**, not an identity
model. It benchmarks a caller-provided, locally available standalone
ONNX model using a deterministic generated float32 tensor; it NEVER
loads user photographs, extracts a person's identity, downloads weights
or writes output artifacts. It requires a full, independently pinned
SHA-256 digest to detect accidental model substitution. A matching
hash **does not prove licensing, security, accuracy or ethical suitability**.

Install NumPy + ONNX Runtime locally (not in GitHub Actions or the
Android app), use a legally cleared ONNX model with embedded weights,
then run for example:

```sh
python tools/identity_fusion/benchmark_onnx_runtime.py /local/model.onnx \
  --sha256 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef \
  --threads 2 --warmups 20 --repeats 200
```

**Replace the illustrative digest with the real independently verified
SHA-256**. The runner refuses symlinked files, oversized models (>64 MiB),
hash mismatches, dynamic shapes, multi-input models, non-float32
inputs, or non-fixed 1x3xHxW shapes (each side 64..512). It loads verified
bytes into ONNX Runtime rather than passing a path, and supplies only
`CPUExecutionProvider`; no optional custom ops, remote API, Android
permissions or automatic downloading are used. An unsupported model is
a test **refusal**, not an incorrect architecture or proof of low quality.

Outputs are aggregate wall-clock session-creation, first-inference,
steady-state p50/p95/p99/max milliseconds, declared tensor shape,
model bytes, hash, runtime version and thread budget. **No model outputs,
embeddings, input tensors, private paths or image identifiers are emitted**.
The first-inference metric is NOT true cold OS-cache latency, memory or
battery measurement. The random tensor does not meaningfully measure
matching quality; use the separate `benchmark_reid_scores.py` on
independently reviewed, privacy-preserving precomputed scores for
quality evaluation. A desktop CPU result is NOT S24+ performance.

The synthetic regression suite uses an injected fake runtime and no ML
packages or real model bytes, ensuring the runner stays cheap and
private-data-free in CI:

```sh
python tools/identity_fusion/test_benchmark_onnx_runtime.py
```

**Admission gates before any actual on-device use:** checkpoint-specific
weights/data redistribution rights; fixed, independently reviewed
Unknown/known back-facing and appearance-change cohorts; quantization
score drift and cross-device operator compatibility; repeated and
thermally stressed real S24+ p95, RAM and energy measurement; and a
reversible UI in which every body/face/session suggestion can be rejected.
No body, clothing, silhouette or context cue alone proves identity.


## Artifact lineage and checkpoint-rights register — 16 October 2026

`candidate_model_artifacts.json` now pins three exact upstream research
candidates: Intel Open Model Zoo **0287 ONNX**, the official Torchreid
**OSNet x0.25 ImageNet-only initializer**, and the separately trained
**OSNet x0.25 MSMT17 person-reID** weight. Their published upstream
SHA-384/SHA-256 digests and source pages are stored as inspectable text.
The Intel 0287 manifest oddly calls its ONNX artifact
`person-reidentification-retail-0267.onnx`; do not silently rename
or assume graph identity without inspection.

This is **NOT a model download, approval or license certification**.
Every entry explicitly has `weight_rights_status: unverified` and
`production_approved: false`. Open-source code licenses (Torchreid MIT;
Open Model Zoo Apache-2.0) do not automatically establish rights for the
specific pretrained weights or their source datasets. MSMT17 and ImageNet
pretraining also have **different feature-extraction purposes**; do not
present ImageNet-only initialization as a pretrained person-reID model.
`.pth` files can use Python pickle: never load untrusted checkpoints via
unrestricted `torch.load`.

The new **offline, model-free** integrity checker can verify that a
separately acquired file matches an upstream pin *without opening it as
a neural network or executing any checkpoint content*:

```sh
python tools/identity_fusion/verify_candidate_artifact.py \
  intel_omz_reid_0287_onnx /path/to/local/model.onnx
```

The verifier reads only bounded bytes and fails on digest/size mismatch,
unexpected paths/symlinks, malformed provenance fields or attempts to mark
a research candidate production-approved. JSON output is limited to
aggregate integrity and research status; it never includes a personal path
or any photo/face feature. Only candidates with separately cleared
checkpoint rights and independently benchmarked accuracy may even be
considered for local model integration; the register has **no workflow to
auto-upgrade a checkpoint's legal status**.

Reference upstream provenance (captured 16 October 2026):
- https://github.com/openvinotoolkit/open_model_zoo/blob/master/models/intel/person-reidentification-retail-0287/model.yml
- https://huggingface.co/kaiyangzhou/osnet/blob/main/osnet_x0_25_imagenet.pth
- https://huggingface.co/kaiyangzhou/osnet/blob/main/osnet_x0_25_msmt17_combineall_256x128_amsgrad_ep150_stp60_lr0.0015_b64_fb10_softmax_labelsmooth_flip_jitter.pth

Four pure-stdlib synthetic checks cover registry tampering, classification
of ImageNet-only vs re-ID weights, byte integrity, symlink refusal and
private-path non-disclosure. Run
`python tools/identity_fusion/test_verify_candidate_artifact.py`; the
existing synthetic V5 workflow imports these tests too.


## Synthetic preprocessing contract audit — 16 October 2026

`model_preprocessing.py` formalizes two **different** documented
256x128/NCHW conventions (neither is an identity-recognition model):

- **Torchreid OSNet evaluation:** RGB `ToTensor()` scales 0..255 bytes to
  0..1 and normalizes channels by ImageNet means
  (0.485,0.456,0.406) and stds (0.229,0.224,0.225). Source:
  https://github.com/KaiyangZhou/deep-person-reid/blob/master/torchreid/data/transforms.py
- **Intel OMZ person-reidentification-retail-0287:** BGR input and fixed
  1x3x256x128 tensor are published, but the model README does **not**
  unambiguously specify numerical input scaling/standardization. We
  deliberately mark numeric preprocessing **UNRESOLVED**, refuse to
  construct that tensor and require inference/export validation before
  supplying a value. Source:
  https://github.com/openvinotoolkit/open_model_zoo/blob/master/models/intel/person-reidentification-retail-0287/README.md

The stdlib-only reference builds deterministic **non-photographic RGB
stripes** and applies Torchreid's per-channel mapping into NCHW float32,
without reading any media. It deliberately does **not resize**, align,
rotate, preserve EXIF, or claim pixel-perfect PIL/torchvision/OpenCV
interpolation parity. Source tensor is already fixed-size. Both contracts'
`spatial_resize_parity_proven` remain false.

To profile a local, separately verified ONNX graph using representative
Torchreid pixel normalization (rather than uniform random floats):

```sh
python tools/identity_fusion/benchmark_onnx_runtime.py /local/model.onnx \
  --sha256 YOUR_FULL_MODEL_SHA256 --input-contract torchreid_osnet_eval
```

The old `--input-contract random` (default) remains available. This
**does not** prove OSNet export/embedding parity or recognition quality.
Do not use the Torchreid transform for Intel 0287 or automatically assign
Unknown people. The four synthetic `test_model_preprocessing.py` checks
test input-channel ordering, fixed dimensions, normalization, deterministic
output, refusal of Intel's unresolved scale, and no invented resize parity.
They are included in existing V5 synthetic CI via the score suite; all
changes remain in Lane C.


## Adversarial correlation-collapse sensitivity audit — 16 October 2026

The existing V5 evidence store separates `correlation_group`s by explicit
caller annotations, but **two group IDs may still refer to one physical
photo or event**. The separate `audit_correlation_sensitivity.py`
stress-tests that exact failure mode *without changing any stored group*
or reading images, models, embeddings or private photo paths.

`audit_correlation_sensitivity(db, reviewed_cases)` evaluates the same
independently reviewed known/Unknown cases under four what-if worlds:

1. `declared_groups`: the existing V5 evidence grouping and candidate
   ranking, as an audit baseline.
2. `shared_source_joined`: merge evidence groups referring to exactly
   the same `source_reference` for one candidate, catching incorrect
   distinct group names for one underlying image.
3. `nonface_collapsed`: pessimistically count **all non-face evidence**
   for a candidate as one potentially dependent event. This can suppress
   both genuine back-facing suggestions and false matches: it is a
   sensitivity floor, **not** a production rule.
4. `shared_source_and_nonface_collapsed`: combine the two stress policies
   (transitive group unions), testing the most pessimistic dependence.
   Contradictory observations remain contradictions in every scenario.

Reports contain **aggregate-only** offered/correct/wrong-known/Unknown
false offers, abstentions, and differences in offers versus the declared
baseline. Exact and overlapping condition-tag strata with fewer than ten
trials are suppressed; never add marginal counts as independent cases.
Neither observations, candidate identifiers, paths, evidence texts nor
scores appear in the output. No identity-probability or write/confirm
API exists. More than 100 candidate identities or 5,000 evidence items
per observation causes a fail-closed refusal instead of a misleading
truncated review.

Synthetic fixtures intentionally include a plausible person photographed
from behind whose body+session proposal vanishes in pessimistic collapse,
an **Unknown lookalike** wrongly proposed from two separate `group` IDs
sharing one original source, same-photo face+body cues on a partial
subject, wrong-known-person body+session suggestions, and contradictory
face evidence. This reveals the **precision-versus-coverage trade-off**
rather than hiding it. A source reference is only an annotation; even
this audit cannot prove that two physical scenes on different dates are
independent.

Run offline without ML, images or network:

```sh
python tools/identity_fusion/test_audit_correlation_sensitivity.py
```

The synthetic tests are also imported by the existing V5 CI score suite.
This research must **never** automatically merge People or turn clothing,
body shape, pose, or session association alone into a confirmed identity.


## Synthetic resize-kernel parity — 16 October 2026

The Torchreid test transform uses `torchvision.transforms.Resize((256,128))`
on a PIL RGB input before tensor normalization. **Pillow bilinear** is not
generally byte-equivalent to OpenCV `INTER_LINEAR`, especially when
downscaling gradients/textures with different antialias behavior. Never
assume the Android preprocessing pipeline can switch resize libraries
while keeping identical embeddings.

`benchmark_resize_parity.py` is an **optional local** synthetic diagnostic.
It generates four artificial patterns at distinct input geometries,
resizes each with Pillow and OpenCV, then reports *only aggregate* absolute
RGB channel differences (mean, p95, max, mismatched fraction). Both inputs
are RGB so it isolates **spatial interpolation differences**, not
RGB-vs-BGR ordering. The CLI never opens, copies, stores or exports a
photograph, and never loads weights or emits pixels/embeddings.

```sh
# Optional local-only dependencies: Pillow, NumPy and OpenCV
python tools/identity_fusion/benchmark_resize_parity.py
# No packages needed for deterministic error-metric regression tests:
python tools/identity_fusion/test_benchmark_resize_parity.py
```

For example, a preliminary local run using Pillow 12.3, OpenCV 4.13,
NumPy 2.3.5 and a 341x173 synthetic gradient gave an approximate **mean
absolute difference of 7.0/255 per channel**, p95 **48/255**, and maximum
**82/255**. These are reproducible environment-dependent *input*
differences, not predicted embedding drift or real-photo accuracy. The
no-resize 128x256 identity fixture has exact parity; other test textures
produce varying differences.

The four stdlib synthetic tests validate exact parity, channel-order
mistakes, deterministic probes, meaningful pixel-drift denominators,
invalid image buffers and bounded size. They are included in the
existing V5 CI score suite, so **no app or CI workflow files** need
editing. A future on-device adapter must match a reviewed resize
contract and separately evaluate the resulting embedding-score drift
and held-out Unknown/wrong-known false offers; no unattended identity
merge is ever permitted.


## Cross-split photo-lineage, event and subject provenance — 16 October 2026

**Problem:** a cropped, rotated, recolored or enhanced copy of a photo can
have a different filename, opaque probe ID, or session label yet leak
into calibration **and** holdout. The score benchmark already enforces
disjoint known IDs and session labels; it now also rejects the **same
probe UID** reused across splits, even if identities and sessions differ.

`verify_split_provenance.py` adds an **optional, read-only** exact-coverage
sidecar. Format: one CSV row per scored probe, with *exactly* these columns:

```csv
probe_uid,split,photo_lineage_uid,event_cluster_uid,subject_cluster_uid
CalProbeK_0,calibration,LineageCal01,EventCal01,SubjectCal01
HoldProbeK_0,holdout,LineageHold01,EventHold01,SubjectHold01
```

All values must be small, opaque alphanumeric/underscore/dash tokens, **not
paths, names, biometrics or media**. Every scored probe must appear once.
`photo_lineage_uid` groups confirmed variants/crops of the same photo;
`event_cluster_uid` groups confidently recognized event/burst provenance.
These two groupings are mandatory when supplying a sidecar: do not
fabricate event identifiers to claim independence. For uncertain identity
association, set `subject_cluster_uid` to the literal `UNREVIEWED` rather
than infer a person. Shared lineage, event or *reviewed* subject clusters
across calibration and holdout cause a fail-closed refusal.

```sh
python tools/identity_fusion/benchmark_reid_scores.py scores.csv \
  --provenance-csv local-opaque-provenance.csv --require-provenance
```

The old CSV and CLI remain supported for backwards compatibility, but now
report `provenance_partition_audit.status: not_supplied` with
`physical_independence_proven: false`. Use `--require-provenance` for
audited research runs; it refuses any missing/incomplete sidecar. A
successful sidecar **does not prove** no undetected near-duplicate photos,
alias event clusters or Unknown people spanning splits, because all
groupings depend on reviewed caller annotations. Report only aggregate
group/probe counts with an explicit unknown-subject count, never the
underlying identifying labels.

The tests use **only fictional** 250-probe score data and verify
cross-split exact-ID leakage, same lineage across different probe IDs,
shared event and known subject groups, malformed/private token rejection,
missing or duplicate rows, refusal of an absent required sidecar, and
CLI redaction. No images, models, V4/Android files or on-device data are
processed. CI imports tests via the existing V5 score suite.


## Missing runner-up is abstention under positive top-two margin

The `--min-top-two-margin` research-only gate now **requires an observed
second candidate score**. With a nonzero requested margin, a high-scoring
probe with just one returned candidate abstains rather than treating its
unknown score gap as infinite. The aggregate report separately counts
`runner_up_unavailable_abstentions` alongside actual
`near_tie_abstentions` and `exact_tie_abstentions` (only above the
selected similarity floor). A genuine no-candidate sentinel still abstains
without claiming to have failed a comparison. A margin of `0` remains
backward-compatible for score-only research comparisons.

This is a **censored candidate-list safeguard**, not a guarantee that
the actual second-best identity was returned, nor a calibrated
probability. Incomplete candidate retrieval remains a separate research
risk: the benchmark cannot know which possible rivals were omitted
entirely. Unknown and explicit unconfirmed review always remain valid.


## Additional event-cluster calibration gate (provenance-aware)

The scored CSV already requires person- and session-disjoint calibration
and holdout. With a complete reviewed provenance CSV, the calibration
threshold is **also** constrained by one-sided 95% Wilson bounds for
any-wrong-known and any-Unknown-false-offer **per declared event cluster**.
A hundred highly correlated photos split into twenty nominal sessions
cannot count as twenty independent independent events when the manifest
labels the same source event. No such claim is made when the optional
sidecar is not provided, and `--require-provenance` remains available to
refuse running without it.

The report includes only `calibration_event_safety` and
`holdout_event_safety` **aggregate counts and upper bounds**, under
`provenance_partition_audit`, and an explicit
`event_cluster_constraints_enforced: true` only after the check is
applied. A shared event containing both a known and Unknown person
counts once *in each applicable error-class denominator*, not once
per photograph, and no event/subject/photo IDs are printed.

**Limits:** Events can be correlated across different dates and unknown
people can recur under different subject handles. Manually supplied
provenance labels are **not independent ground-truth proof**. No model
threshold, source family, physical identity or app feature is ever
automatically deployed. Synthetic regression tests collapse 200 apparently
independent session trials into only two event clusters and correctly
refuse a false 5% statistical safety claim.


## Frozen candidate-roster completeness check (2026-10-16)

A confident top-ranked match and a good top-two score gap are misleading
if the retrieval stage omitted a legitimate competing gallery candidate.
A new **optional**, read-only `candidate_list_coverage.py` compares
scored candidates against a separately prepared, **frozen** inventory of
eligible candidates *for each probe*. File format (all opaque IDs):

```csv
split,probe_uid,candidate_uid
calibration,SampleCal01,PersonCandidate01
calibration,SampleCal01,PersonCandidate02
holdout,SampleHold01,NONE
```

The reference inventory must be produced independently of a given
model's top-k ranking, ideally from a versioned, reviewed eligible-gallery
snapshot before running that model. A `NONE` row indicates no candidate
was eligible; it is never mixed with other IDs. The check rejects missing,
extra, duplicate, or malformed candidates/probes and additional private
columns. The scored CSV retains the complete existing reviewed Unknown
cohort and never discards known-person observations whose correct target
is absent from the gallery.

```sh
python tools/identity_fusion/benchmark_reid_scores.py scores.csv \
  --candidate-roster-csv frozen-opaque-roster.csv --require-candidate-roster
```

This can be combined with the separate `--require-provenance` split
lineage/event audit. Strict mode refuses an absent roster; old score-only
runs remain backward compatible but are clearly `not_supplied`.
Output contains only split-level counts, including no-candidate probes,
single-candidate probes, and known-person targets absent from the
declared eligible gallery. No IDs, scores, paths, embeddings or pixels are
returned by the candidate audit.

**Limitation:** exact equality between two CSVs is NOT independent
evidence that the gallery inventory was complete or frozen in advance.
An independently documented gallery build procedure and retrieval-recall
benchmark are still required. The report therefore always marks
`roster_independence_verified=false` and
`gallery_exhaustiveness_verified=false`. Five model-free synthetic
tests stress an unreturned rival, an absent known target, invalid/mixed
sentinels, malformed private data, deterministic order and CLI redaction.
No People assignments or app changes occur.


## Known-person cluster calibration gate (2026-10-16)

Even if photo/session/event labels are distinct, calibration can repeatedly
observe **one already-known person** without measuring generalization to
different people. The score harness now groups reviewed known trials by
their existing opaque `truth_uid` and requires the one-sided 95% Wilson
upper bound for **any wrong known-person offer per distinct person** to
meet the same conservative calibration error budget, *in addition* to its
probe, session and optional event-cluster bounds. This uses calibration
truth only; no holdout scores select the threshold.

Reports expose only `distinct_known_persons`,
`wrong_known_error_persons` and
`wrong_known_person_wilson95_upper` in the overall and sufficiently
populated condition views. A person represented by ten photos but one
wrong match is one affected *person*, not ten independent error trials.
Conversely, observing one person in 100 independently labeled sessions
cannot support a false claim of under-5% error over diverse known people.
The synthetic regressions deliberately collapse 100 nominally separate
known observations to a single confirmed identity and require fail-closed
calibration; they also check that 25 holdout photographs of one person
report one affected identity.

For **Unknown** people, the existing sentinel `UNKNOWN` does not
represent one person; it may contain many distinct unlabeled people, so
we do NOT group all Unknown examples into one identity. Unknown error
safety continues to depend on reviewed sessions, source-event metadata
and true Unknown-person diversity in later consented validation. Code
does not infer new identities or change the user's People database.


## Distinct anonymously reviewed Unknown subjects (2026-10-16)

The literal truth sentinel `UNKNOWN` means *not a confirmed member of
the known People gallery*, **not** one new individual per photograph.
Reusing the same unnamed person in 100 sessions can artificially inflate
the apparent independence of Unknown false-link trials.

For higher-assurance, consented evaluation with an independently reviewed
provenance CSV, enable:

```sh
python tools/identity_fusion/benchmark_reid_scores.py scores.csv \
  --provenance-csv reviewed-opaque-provenance.csv \
  --require-provenance --require-reviewed-unknown-subjects
```

When this explicit mode is enabled, EVERY reviewed Unknown trial in both
calibration and holdout requires a non-`UNREVIEWED` opaque
`subject_cluster_uid`. The cluster IDs are merely equivalence judgments
that two **unknown-to-the-gallery** observations involve the *same unnamed
individual*. They are **not** names, confirmed People identities or
permissions to link faces automatically. A cluster cannot be both a known
subject and an Unknown subject; any such contradiction is refused.

Threshold calibration then ALSO enforces the existing one-sided 95% error
budget against **any false offer per distinct reviewed Unknown-subject
cluster**. Probe/session/event safeguards remain in force. The locked
holdout reports only aggregate Unknown clusters, clusters with errors and
their Wilson upper bound. No cluster identifiers, paths, photos or
embeddings are displayed. In default legacy mode (`--require-reviewed-
unknown-subjects` omitted), `UNREVIEWED` remains an honest permitted
uncertainty, and the new stricter claim is never made.

A synthetic regression with 100 nominally independent Unknown session
trials of a **single unnamed subject** must now refuse an unjustified
5% statistical guarantee. Even fully reviewed clusters cannot prove
statistical independence across people, or generalize from synthetic
fixtures to real-world facial/body recognition. No app integration,
automatic confirmation or private photo access is involved.


## Reproducible frozen-eligible-gallery roster builder (2026-10-16)

`build_frozen_roster.py` solves the first practical prerequisite for
candidate-list completeness checks: prepare the **eligible candidate
inventory BEFORE model inference**, rather than copying its top-k scores.
It takes two independently assembled, versioned local CSV inputs:

**Probes** (`split,probe_uid`, one opaque ID per reviewed observation):
```csv
split,probe_uid
calibration,CalProbe01
holdout,HoldProbe01
```

**Eligible gallery** (`split,candidate_uid`, one opaque ID per gallery
person in each partition):
```csv
split,candidate_uid
calibration,CalKnown01
holdout,HoldKnown01
```

The files contain **no photographs, face embeddings, names, file paths,
EXIF, scores or unreviewed predicted identities**. Split-specific
gallery IDs must be disjoint; probes must be unique globally. Both
splits need probes. A candidate's presence is determined solely by the
frozen gallery manifest, never by its similarity score. The builder
constructs every eligible probe × candidate pair in stable lexical order,
using a lone `NONE` row for probes in a split with an empty gallery.
An unsupported per-probe gallery exclusion/filter is **not** silently
assumed: this version implements only a *single shared eligible gallery
per split*.

For tamper-evident reproducibility, supply independently recorded
**full SHA-256 checksums of both exact input CSV files**. An optional
output filename creates a restricted mode-0600 local roster without
overwriting anything. With `--out` omitted, the default is a safe
**dry run**: JSON containing aggregate counts and the canonical output
SHA-256, not the raw roster or individual identifiers.

```sh
python tools/identity_fusion/build_frozen_roster.py \
  --probes reviewed_probes.csv --probes-sha256 FULL_PROBE_SHA256 \
  --gallery frozen_gallery.csv --gallery-sha256 FULL_GALLERY_SHA256
# After verifying dry-run counts, add:
# --out local_frozen_roster.csv
```

Use the roster with the previously implemented research gate:

```sh
python tools/identity_fusion/benchmark_reid_scores.py scores.csv \
  --candidate-roster-csv local_frozen_roster.csv --require-candidate-roster
```

The input parser enforces exact opaque-only headers, checksum pins, no
symlink inputs, bounded file sizes and counts; generated rosters have at
most 100,000 rows, matching the score benchmark's input limit. Every
local export is a fresh exclusive file creation with permissions 0600,
never an overwrite, to reduce accidental exposure of private opaque
identity associations. Errors never print file locations or user IDs.

**Evidence limits:** hashes prove only input *byte integrity*. They do
NOT certify that an eligible gallery was independently collected,
complete, correctly licensed, consistently labeled across time, or
unaffected by earlier model suggestions. The code explicitly reports
`source_manifests_are_independently_verified=false`,
`gallery_exhaustiveness_proven=false`, and
`per_probe_eligibility_verified=false`. Real-world gallery eligibility
and probe provenance must be independently reviewed before using these
results as a deployment gate. The builder never touches private images,
V4/Android files, or People records.

Five pure-stdlib synthetic tests cover stable ordering, empty eligible
gallery, malformed/duplicate/cross-split IDs, row-budget overflow,
tampered/symlinked input, safe dry-run/restricted output, and an
end-to-end test using the score evaluator to catch an unscored rival.
They are imported by the existing V5 synthetic CI test runner.


## Frozen-gallery top-k retrieval recall (2026-10-16)

**Model retrieval is not person identification.** The gallery may contain
the correct subject even when a fast search fails to return them, and a
retrieved match for a genuinely Unknown person is NOT yet a false identity
suggestion. To measure these stages independently, the offline
`benchmark_retrieval_recall.py` accepts three checksum-pinned CSVs:

1. `truth.csv`: `split,probe_uid,truth_uid,session_uid`; known truth uses
   a confirmed opaque positive subject ID; independent Unknown subjects
   use the existing `UNKNOWN` sentinel. No names, paths or embeddings.
2. `roster.csv`: `split,probe_uid,candidate_uid`; produce this **before
   model inference** with `build_frozen_roster.py` using reviewed eligible
   gallery/probe manifests. No hidden per-probe eligibility filters allowed.
3. `retrieved.csv`: `split,probe_uid,candidate_uid,rank`; rank is an
   integer from 1 to the caller-selected `--top-k`, contiguous with no
   duplicate candidates. If retrieval yields *nothing*, record exactly
   one `NONE` candidate with rank `0`. This is allowed even when an
   eligible gallery exists: it indicates retrieval abstention, **not**
   that all gallery subjects were ruled out.

All files must be locally available, bounded, regular (not symlinks),
strict opaque-token-only and exactly pinned by a full SHA-256 of their
**raw bytes**. The CLI writes only an aggregate JSON report to stdout:

```sh
python tools/identity_fusion/benchmark_retrieval_recall.py \
  --truth frozen-reviewed-truth.csv --truth-sha256 SHA256_OF_TRUTH \
  --roster frozen-eligible-roster.csv --roster-sha256 SHA256_OF_ROSTER \
  --retrieved exported-topk.csv --retrieved-sha256 SHA256_OF_RESULTS \
  --top-k 5
```

The evaluator demands complete matching probe coverage in both
calibration and holdout, disjoint known identities/sessions and gallery
identities across the partitions, and at least ten independently reviewed
known and ten Unknown probes per split. Every returned candidate must
exist in the frozen eligible roster. It reports:

- known target eligible versus **known target absent from gallery**:
  the latter are never quietly counted as retrieval misses or relabeled
  Unknown; both remain visible in the total known cohort;
- among known probes with an eligible true gallery subject, retrieval
  recall at rank 1 and at the fixed reported k (with denominators);
- conservative per-known-person all-observation retrieval count, which
  prevents repeated easy photos of the same person masquerading as broad
  recognition;
- Unknown probes with *any* retrieved eligible candidate, **never
  misreported as false identity offers**;
- empty retrievals and query lists that reached the k-result cap.

The same fixed k and frozen eligible gallery must be used for every
candidate model, **without choosing k after seeing held-out results**.
Neither the score-calibration threshold nor the existing top-two margin
is selected by this retrieval-only report. This test does **not** prove
complete real-gallery eligibility, independent reference truth,
true Unknown-person diversity or actual model feature quality; the
`gallery_exhaustiveness_proven` field stays `false`, and every output
has `auto_confirm_allowed=false`.

Six synthetic regression tests challenge missing probes, leaked subjects,
incomplete/duplicate/out-of-gallery rankings, absent known gallery
targets, Unknown retrieval semantics, distorted denominators, and
private-path redaction. They run in existing V5 CI without changing
shared Android/workflow files.


## Paired calibration-locked re-identification model comparison (2026-10-16)

An attractive rank-1 benchmark number does not establish whether one
architecture is better for *ATLAS's exact people and Unknown-person
review cases*. Moreover, comparing models on separate or partly leaked
holdout cohorts is scientifically misleading.

`compare_paired_models.py` provides an offline, no-image, no-weight
paired synthetic-first comparison of **two score manifests over the
SAME reviewed observations, truth labels, session IDs, condition tags
and complete candidate roster**. Requires independently supplied full
SHA-256 pins for each of four local opaque-only CSVs:

- `a` and `b`: two model-specific scored manifests with exactly the
  existing `benchmark_reid_scores.py` header. Candidates per probe
  must match each other *and* the frozen eligible-gallery roster.
- `roster`: separately frozen from the reviewed eligible gallery with
  `build_frozen_roster.py`, **not** copied from model top-k outputs.
- `provenance`: split-disjoint, independently reviewed photo-lineage,
  event and anonymous Unknown-subject group annotations. Must be
  complete; `UNREVIEWED` Unknown subjects are refused in this strict
  paired experiment.

```sh
python tools/identity_fusion/compare_paired_models.py \
  --a model-a-scores.csv --a-sha256 FULL_SHA256_A \
  --b model-b-scores.csv --b-sha256 FULL_SHA256_B \
  --roster frozen-roster.csv --roster-sha256 FULL_ROSTER_SHA256 \
  --provenance reviewed-groups.csv --provenance-sha256 FULL_GROUP_SHA256 \
  --margin-a 0 --margin-b 0
```

Each model chooses its own similarity floor using **calibration only**
with all prior conservative probes, known-persons, Unknown-persons,
session and event-level Wilson guards. The holdout does **not** tune the
floor, margins, roster or eligibility. Model scores are never pooled or
interpreted as cross-model calibrated probabilities.

Reports contain only aggregate calibration floors and heldout counts,
paired transitions (correct/wrong/abstention for known, false-offer/
abstention for Unknown), and the numbers of affected known people and
anonymously reviewed Unknown groups which improved, worsened or remained
affected. Example: five back-facing wrong-known suggestions fixed by B,
while four Unknown lookalikes are no longer falsely offered. These are
**fictional synthetic scenarios**, not measured OSNet/FastReID quality.
The tool never prints input paths, person/observation/group IDs or any
embeddings and does not assign a person or modify the V4 People database.

It deliberately does NOT declare an A/B winner or statistical
significance: `source_gallery_exhaustiveness_proven=false` and
`model_comparison_pre_registered_proven=false` always. A real
experiment must freeze cohort/gallery/metrics/margins and model choices
**before** examining heldout results, independently review weights and
source rights, and measure resource performance on the target S24+.
Six adversarial synthetic regression tests cover strict cohort parity,
head-to-head paired gains/regressions, Unknown safety, heldout-order
invariance, missing reference groups, unsafe calibration and CLI
input integrity. No Android UI, photo or ML inference is involved.


## Frozen preregistration for paired re-ID research (2026-10-16)

`preregistered_comparison.py` strengthens the earlier model A/B tool by
requiring an **independent reviewed truth snapshot**, alongside the same
frozen eligible gallery, event/lineage/subject provenance and both scored
model manifests. Critically, every score row must reproduce the
*separately pinned* `truth_uid`, `session_uid` and canonical condition.
The two models must use identical candidates and calibration-only
thresholding. Truth/condition edits made while producing the model's
scores are refused.

First create independently reviewed, **opaque-only** truth:
```csv
split,probe_uid,truth_uid,session_uid,condition
calibration,CalProbe01,KnownCal01,CalSession01,front
holdout,HoldProbe01,UNKNOWN,HoldSession01,back+occluded
```

Before looking at holdout model scores, commit a small JSON experiment
protocol to a separately reviewed immutable repository revision or
append-only evidence ledger. It MUST define **exactly** these fields
and use actual SHA-256 values for the three frozen inputs and each
candidate's *intended* checkpoint:

```json
{
  "schema_version": 1,
  "study_uid": "Study_001",
  "frozen_truth_sha256": "REPLACE_WITH_FULL_SHA256",
  "frozen_roster_sha256": "REPLACE_WITH_FULL_SHA256",
  "frozen_provenance_sha256": "REPLACE_WITH_FULL_SHA256",
  "model_a_uid": "Candidate_A",
  "model_b_uid": "Candidate_B",
  "model_a_checkpoint_sha256": "REPLACE_WITH_FULL_SHA256",
  "model_b_checkpoint_sha256": "REPLACE_WITH_FULL_SHA256",
  "model_a_margin": 0.0,
  "model_b_margin": 0.0,
  "max_false_offer_rate": 0.05,
  "primary_comparison": "paired_known_wrong_and_unknown_false_offers_v1",
  "calibration_policy": "calibration_only_wilson_known_unknown_event_subject_v1"
}
```

The example SHA-256 placeholders are intentionally **not** accepted.
Save the *actual* JSON as `locked_protocol.json` and independently
record its full **raw byte SHA-256**. Never change its declared model
choices, margins, error budget, population or primary endpoint after
examining holdout scores. Then run the locked evaluation:

```sh
python tools/identity_fusion/preregistered_comparison.py \
  --protocol locked_protocol.json --protocol-sha256 PROTOCOL_SHA256 \
  --truth reviewed_truth.csv --truth-sha256 FROZEN_TRUTH_SHA256 \
  --a candidate_a_scores.csv --a-sha256 SCORES_A_SHA256 \
  --b candidate_b_scores.csv --b-sha256 SCORES_B_SHA256 \
  --roster frozen_roster.csv --roster-sha256 FROZEN_ROSTER_SHA256 \
  --provenance frozen_provenance.csv --provenance-sha256 FROZEN_PROVENANCE_SHA256
```

Every exact input hash is verified. The protocol fixes the sources,
model identities, intended checkpoint digests, margin choices, false
offer budget and **single primary comparison definition**. Alternative
metrics or calibration policies are refused. Expected model-score hashes
are **not** preregistered because the inference outputs need not exist
before the protocol is frozen; both scored artifacts are pinned when
evaluated. Outputs contain only aggregate paired results, not names,
paths, embeddings, IDs or weights. There is no heldout-based threshold
selection, automatic identity match, deletion or database mutation.

**Evidence limits:** A local hash check cannot prove that a protocol
was externally committed **before** examining holdout data; the user
must independently verify the parent revision and timestamp. It cannot
prove the purported checkpoint was actually run or that the gallery,
Unknown groups and source labels were independently reviewed.
Accordingly, `external_pre_holdout_commitment_verified`,
`checkpoint_execution_verified`, and
`holdout_inspection_count_verified` remain **false** on success.
The optional research-only protocol is not permission to process
private photos or deploy recognition. Five model-free synthetic
regressions audit tampering, truth drift, metric shopping, randomized
input order, immutable-file pins and log privacy. Existing scoring and
paired-comparison CLIs remain backward compatible.


## Evidence-rank ambiguity must abstain (2026-10-16)

The V5 review-quality evaluator historically broke ties between equally
supported candidate people by integer `candidate_person_id`. This was a
stable ordering convenience, **not identity evidence**. For a person seen
from behind, two equally convincing body+session matches must not silently
favor whichever happened to have a smaller internal numeric ID.

`evaluate_review_quality.py` now abstains whenever the top two eligible
review candidates are tied on **both independently declared positive
support-group count and strongest evidence-family priority**. It counts
`ambiguous_evidence_abstentions` in aggregate overall/condition metrics,
separate from wrong-known and Unknown false offers. A more strongly
corroborated unique candidate may still be **offered for review only**;
no confidence probability or confirmation is generated.

`audit_correlation_sensitivity.py` applies the same abstention policy
in all four declared/shared-source/non-face-collapse what-if scenarios,
so a source-dependency stress audit cannot report fictitious success
from an arbitrary ID-based tiebreak.

Synthetic regression cases use two equally ranked fictitious identities
for an explicitly Unknown observation, reverse their numeric order,
check same-source cues cannot resolve the tie, then add a genuinely
separate supporting group to show the expected precision/coverage
trade-off. No actual faces, photographs, embeddings, models, private
database records, People assignments or Android code are accessed.

This is a **conservative research-evaluation rule**, not evidence that
existing `correlation_group` strings are truly independent. The user
always retains Unknown/skip as an option. The underlying review list
can still display several hypotheses, but evaluation must **not** make
a single correctness claim when their evidentiary rank is tied.


## Explicit top-candidate ambiguity for future UI adapters (2026-10-16)

The dedicated V5 `review_candidates()` evidence prototype returns
hypotheses in deterministic order for convenient rendering, but the
numeric People ID is **only the last sort tiebreak**, never identity
evidence. A future review adapter must not accidentally treat the first
item of `proposals` as a uniquely supported identity.

The response therefore now includes:

- `top_rank_tied_on_evidence`: `true` when the two strongest
  non-contradicted candidates have **equal positive support-group counts
  and equal strongest-family priorities**. A human may inspect both,
  or select Unknown/skip; there is no automatic selection.
- `numeric_person_id_is_presentation_tiebreak_only: true`.
- `auto_confirm_allowed: false`, explicitly.
- The existing `total_candidates` and `proposals[:limit]` retain their
  former behavior and **no stored schema is changed**.

Crucially, `top_rank_tied_on_evidence` is computed over **all ranked
candidates before UI list truncation**. Even when a UI requests just
`limit=1`, it still learns that an equally supported second identity
exists outside its visible list. Two synthetic tests cover truncated
ties, source-correlated extra clothing cues, a truly independent
support group and a justified family-priority distinction. This metadata
does not assert a match, calibrated probability or independent
physical provenance and never touches an actual user's People data.


## Optional protected-condition calibration (2026-10-17)

**Why:** passing aggregate known/Unknown false-offer budgets can conceal a
systematically unsafe minority cohort. This especially matters for V5
recognition from behind, under occlusion, and after long appearance
changes. The score benchmark now provides an **opt-in fail-closed**
`--protect-condition-tag` (repeatable) covering existing public
`front/back/side/partial/occluded/appearance_shift/lookalike/unknown`
condition tags. Compound reviewed labels such as `back+occluded`
belong to **both** overlapping tag strata; never add their denominators.

```sh
python tools/identity_fusion/benchmark_reid_scores.py scores.csv \
  --protect-condition-tag back --protect-condition-tag occluded
```

Within **each** protected calibration tag, BOTH independently reviewed
known and Unknown cohorts must be large and varied enough that even a
*zero-error* one-sided Wilson 95% upper bound meets the configured
false-offer budget. The chosen similarity floor must then separately
pass all existing probe, known-person, session, and (when provenance
exists) source-event/anonymous Unknown-subject error bounds *for every
protected tag*, in addition to the original overall safety gate.
The threshold remains **calibration-only**. Insufficient evidence,
missing known/Unknown groups, or a persistently unsafe condition causes
refusal, **not** a fictitious low error rate or a quiet subgroup skip.

`protected_condition_tags` and
`protected_tag_calibration_safety` show only public condition labels
and aggregate counts/upper bounds at the selected threshold. The
holdout's existing `by_condition_tag` metrics remain separately
available for locked evaluation and cannot tune the threshold.
All other benchmark callers retain the original behavior unless
the strict option is explicitly enabled. Multiple protected tags
overlap and do not represent independently additive tests.
The synthetic stress suite constructs 60 back-facing known and
60 Unknown trials among many easy frontal photos: overall error
control passes, while a single wrong-known back-face offer fails
the protected subgroup guard. A second fixture proves sufficient
100+100 known/Unknown back/occluded review can pass without
fabricating certainty.

**Evidence limitations:** reported source groupings are caller
annotations, not a proof of physical independence; no correlation
bound establishes identification accuracy on private photos.
Model comparison protocols must preregister which protected tags
are selected *before* evaluating holdout cohorts. This is research
policy only, not a new Android setting, person assignment or
deletion mechanism.


## Protected conditions locked in preregistered A/B studies (2026-10-17)

The paired model comparison now optionally accepts repeatable
`--protect-condition-tag back` / `occluded`, applying the **same
protected-tag calibration policy to both models** and refusing an
underpowered or unsafe known/Unknown condition rather than hiding it
inside a favorable overall benchmark.

For a **genuinely locked** comparison, use
`preregistered_comparison.py` with a new exact-schema **version 2**
protocol. It has all the original version 1 fields plus this mandatory
nonempty, **sorted unique** public-label list:

```json
"schema_version": 2,
"protected_condition_tags": ["back", "occluded"]
```

These are *field fragments*, not a complete JSON document; add them
to the existing version 1 JSON study specification, replacing its
`schema_version: 1`. Recompute the exact protocol-file SHA-256 and
record it in an externally reviewable commit **before inspecting
holdout results**. The v2 protocol fixes the **identity of every protected
condition** as well as the checkpoints, input manifests, margins,
risk budget and primary metric. The runner reuses each model's
independently calibrated threshold, verifies the frozen source truth,
and refuses a model that cannot meet protected known/Unknown strata
bounds at any nontrivial threshold. Its
`protocol_audit.protected_condition_tags_explicitly_registered` field
is `true` only for v2. Schema v1 remains accepted for historical
comparisons but is explicitly **unprotected**: it cannot silently
inherit after-the-fact subgroup requirements.

A wholly synthetic red-team test constructs 60 known back+occluded
and 60 Unknown back+occluded probes among easy frontal observations,
with **one** wrong known back match. The older overall-only comparator
can pass, but preregistered v2 with a protected `back` tag correctly
refuses the apparent improvement. Four new synthetic regression cases
exercise old-vs-new semantics, deterministic paired counts, real
SHA-locking of the public protected-tag list and invalid/duplicated
condition labels. This is not proof of biometric efficacy, legal
checkpoint rights or independently verified provenance; those are
separate prerequisites.


## Competing face evidence versus non-face identity hypotheses (2026-10-17)

V5 is explicitly intended to find people photographed from behind and
with only partial appearance evidence. That makes it especially
important **not to overwhelm one visible-face clue with multiple body,
outfit and session hints from a different proposed person**.

The dedicated read-only `review_candidates()` interface now returns
`positive_face_support_groups` for each candidate and a top-level
`top_nonface_competing_face_evidence` diagnostic. The latter is true
when the highest body/session-only, non-contradicted candidate has
at least two declared independent support groups, but a **different**
non-contradicted candidate has positive face evidence. This flag is
computed across the **complete ranked list**, before `limit=1` can
hide an alternative. The list remains review-only and never auto-tags.

The synthetic `evaluate_review_quality()` research evaluator
**abstains** on the conflicting face-vs-nonface pattern rather than
blindly favoring more non-face correlation groups; it counts
`competing_face_evidence_abstentions` separately. The parallel
`audit_correlation_sensitivity()` what-if tests apply the same
pessimistic abstention. A *contradictory* face candidate does not
silently veto another candidate. Two non-face groups should not be
treated as more conclusive than a face clue just because their labels
are different. Human review or Unknown/skip remains available.

These rules are conservative sensitivity policies, **not** proof that
all face observations and body regions correspond to the same visible
subject. Distinct people in one photograph and incorrectly associated
face crops can also cause this signal. A future real adapter must align
face/body regions before any explanation is presented. Synthetic
tests explicitly challenge body/session rank dominance, a competing
partial face, source conflicts, contradicted alternatives and
truncated one-item lists. No actual photos, model checkpoints,
People assignments or Android UI changes are involved.


## Multi-person face/body region correspondence (2026-10-17)

V5 aims to recognize people from behind and in partially visible photos,
but visual cues from **different people sharing one frame** must never be
mistaken for independent evidence about a single person. The synthetic-only
`region_correspondence.py` prototypes an explicit geometry contract:

- Caller supplies only opaque `asset_uid` / region IDs and normalized
  `x,y,w,h` rectangles (no images, paths, embeddings, names or GPS).
  Bodies and faces have independent IDs. The audit is in-memory and
  refuses duplicates, NaN/infinity, invalid extents, overflow or private
  extra columns.
- A face qualifies as `geometry_candidate_only` when its center is
  compatible with exactly one **same-asset** person region, its entire
  bounding box is safely inset, and it lies within the upper 55% of that
  person's region. This is a **conservative heuristic**, NOT anatomical
  proof of identity or a calibrated confidence score.
- Overlapping/nested people, two faces assigned to one person box,
  overlapping duplicate face detections, cross-frame features and
  uncertain/cropped boxes are refused as `ambiguous`, `unmatched` or
  `uncertain`. Absent visible faces cannot prove there is no person.
- Only the `SpatialAudit.aggregate` summary is suitable for
  non-identifying experiment reports. `SpatialAudit.decisions` contains
  transient per-face/region opaque IDs for local review adapters;
  **never log or upload them**. Even uniquely associated geometry
  produces `identity_confirmed=false` and
  `auto_confirm_allowed=false`.

The function deliberately does **not** automatically rewrite existing
V5 `candidate_evidence` rows. Those rows currently lack a reviewed
face-detection-to-person-region source mapping; trusting that a row's
`observation_uid` proves physical ownership would be circular. Before
any real integration, require a reviewed region-to-evidence source join,
test multiple people crossing/overlapping, evaluate rear-view and
occlusion failures separately, and always retain manual Unknown/skip.

Nine pure-stdlib synthetic regression tests cover two people in one
frame, overlapping and nested boxes, duplicates from separate detectors,
two faces in one person box, frame mismatch, cropped/headless ambiguity,
invalid coordinates and aggregate-only reporting. No private photos,
Android/V4 schema, real People records or model inference are accessed.


## Strict same-photo face source audit (2026-10-17)

The dedicated, **opt-in read-only** `spatial_evidence_review.py`
bridges the pure geometric prototype with V5's local evidence store.
It avoids an insidious problem: a face clue can be attached to the
wrong body observation, especially when people overlap or pose for
group photographs. A same-photo `face` evidence row is accepted in
this strict experiment **only if all these checks pass**:

1. The caller supplies the current, consistently oriented/cropped
   body and face rectangles; the target body's asset UID and
   normalized rectangle must match the stored observation exactly
   (within 1e-9). Relabeling another body's rectangle is refused.
2. Each existing `candidate_evidence` row with `family='face'`
   must have its **opaque, face-detection-specific**
   `source_reference` equal to a supplied `face_uid`.
   Old generic asset-path/session source references **do not qualify**
   and must be independently remapped/reviewed, not guessed.
3. Each referenced face detection must be a unique
   `geometry_candidate_only` association to the **same asset and
   exact target person region**. Missing faces, a face from another
   person/asset, duplicate face detections, overlapping person
   regions or uncertain geometry cause a **full review abstention**.
   A mix of one valid and one invalid face source does not partially
   authorize suggestions.

On failure, `review_with_spatial_sources()` returns
`status=manual_region_review_required`, an empty `proposals` list,
a separate `candidate_results_suppressed=true`, aggregate face
source counts and `unknown_always_allowed=true`. It does not
pretend there were zero candidates, delete evidence, or mutate
the database. On success it exposes the original **still-unconfirmed**
`review_candidates` proposals plus explicit source audit metadata,
including `physical_face_body_identity_proven=false` and
`auto_confirm_allowed=false`. Rear-view photos with **no current
face evidence** may retain body/session **review hypotheses**; absence
of a visible face never means no human is pictured.

The face detector's boxes and source associations must be independently
reviewed against the *actual* same-frame media orientation before
treating a successful audit as even reliable geometry. Geometry cannot
prove actual person identity, and a historical face-exemplar source
from another photo is **not automatically a same-frame match**. Such
cross-photo links require a separate versioned provenance protocol.
This prototype makes no V4/native DB migration, private photo scan,
face model inference, name assignment, or confirmed People change.

Eight entirely fictional SQLite and rectangle tests exercise correct
source-to-face routing, swapping faces of two people, duplicate and
overlapping detectors, stale or forged ROI geometry, cross-frame
sources, mixed valid/invalid face evidence, body-only rear views,
bounds, redacted output and read-only transactions. Imported by the
existing V5 synthetic evidence-store CI.


## Strict cross-family source independence in spatial review (2026-10-17)

A review can be misleading even with correctly associated geometry
when the **same physical source** is given multiple
`correlation_group` labels. One photo of a shirt, a silhouette and a
partial face does not become independent multi-day evidence simply
because its source was entered under three labels.

In the opt-in `review_with_spatial_sources()` adapter, an additional
**read-only, whole-observation source-reuse check** now inspects all
face/body/clothing/session/context cue records. For each
`(candidate_person_id, source_reference)` pair, exactly one declared
`correlation_group` is allowed. If one source is assigned to more
than one group for the **same hypothesized person**, no candidates are
offered; `status=manual_region_review_required`,
`candidate_results_suppressed=true` and aggregate-only
`source_alias_conflicts` are returned. Input sources/group names
must be opaque tokens, with a strict 5,000-evidence-row bound; unsafe
paths and malformed references are rejected without printing them.

The same face crop can legitimately support **multiple competing
candidate people** as separate *unconfirmed hypotheses*. Such a case
is not conflated into an automatic single identity or counted as a
same-person source alias. Existing body-only rear-view review
hypotheses are likewise unchanged when their sources do not conflict.

This is stricter than the legacy read-only
`review_candidates()` prototype, which remains intentionally
backward-compatible. Equal text labels and source tuples cannot prove
the absence of *undetected* same-photo aliases with different IDs.
The caller must independently review source lineage and join identities
across crops/transformations; this tool does not infer or verify them.
Four more synthetic SQLite regressions cover two group names assigned
to one body source, a rebranded duplicate face crop, competing people
using one face source, and redaction of unsafe source-path text.
No app/People confirmation, private media or model inference is added.


## Orthogonal orientation, mirror, crop and rear-view spatial parity (2026-10-17)

The strict V5 same-frame source adapter previously expected detector
face/body bounding boxes already in **original normalized asset
coordinates**. The experimental `region_view_reprojection.py` now
provides explicit, image-free reprojection when a caller instead
supplies boxes in a cropped, mirrored or EXIF-right-angle display view.

`reproject_regions()` accepts **only** reviewed opaque same-asset
region tokens and normalized `x,y,w,h` rectangles. The required
declaration is the original-image normalized crop
`source_crop=(x,y,w,h)`, rotation in `0/90/180/270` **clockwise**
and a boolean horizontal `mirror` applied **after** rotation.
The transformation order is precisely:
original source → source crop → clockwise rotation → mirror → view.
`direction="view_to_source"` inverts that sequence. A complete
`source_to_view` transform refuses any source rectangle clipped by
the crop, rather than silently truncating a person to a false body
region. Exact right-angle/mirror transforms preserve axis-aligned
rectangles; arbitrary 15°/45° rotations and perspective warps do not,
so this version refuses them rather than approximating them as
rectangles and claiming confidence.

A read-only `review_with_view_coordinates(...)` wrapper converts
view coordinates back into the original stored asset frame **before**
running `review_with_spatial_sources()` and its already established
face-source/region and correlation-group safeguards. If the crop
truncates a body, or the caller supplies the wrong rotation/mirror,
the reprojected body no longer matches the stored source person
region and strict review refuses the stale/cropped geometry. It
does not infer rotation from pixel content or EXIF: the returned
`orientation_and_crop_metadata_independently_verified=false` field
makes that unresolved provenance explicit, along with the existing
`physical_face_body_identity_proven=false` and
`auto_confirm_allowed=false`.

Eight synthetic tests cover all eight right-angle/mirror
orientations, valid internal crops, wrong metadata, a person body
clipped by cropping, valid rear-view photos with **no visible
face**, competing wrong-person face sources under rotation, seeded
multi-box reversible geometry, malformed/private columns, unsafe
crop parameters and unsupported arbitrary rotations. These tests do
not run face inference, access actual phone photographs, claim
orientation provenance, confirm identities, or touch the installed
Android/V4 app. A later independent photo-lineage mapping for resized
or arbitrary-angle derivative photos still requires coordination
with Lane B and owner-approved sample review.


## Cross-derivative photo-lineage source independence (2026-10-17)

A single photographic exposure may appear in a gallery as the original,
crop, rotated copy, recolored enhancement or heavily recompressed JPEG.
Different filenames and source IDs **do not make these independent
observations of a person's identity**. The strict V5 research review
can now accept an additional, separately prepared, fully opaque
`source_lineage_rows` manifest, containing exactly:

```csv
source_reference,lineage_family_uid
BodySourceFromOriginal,ExposureFamilyA
BodySourceFromCrop,ExposureFamilyA
AnotherEveningSource,ExposureFamilyB
```

In the read-only
`review_with_spatial_sources(..., source_lineage_rows=rows,
require_lineage_audit=True)` flow, the manifest must cover **every**
referenced source in the reviewed observation, with no extra, missing,
duplicate, conflicting, malformed, path-like or `UNREVIEWED` entries.
The audit groups the declared photo family by
`(candidate_person_id, lineage_family_uid)` across **all** cue
families. If one declared photographic family is used as two different
`correlation_group` claims for the **same candidate**, the entire
review is suppressed with `manual_region_review_required` and a
non-identifying `same_candidate_source_family_group_conflicts`
aggregate. It preserves Unknown/skip and never silently relabels or
deletes stored cues.

This also handles the intentional ambiguity where the same photograph
supports two different *hypothetical* people: these are competing,
**unconfirmed** candidate identities, not automatic person merges.
A well-reviewed family used repeatedly **within one original correlation
group** remains a single supporting group, not multiple trials.

Default older `review_with_spatial_sources` calls still work, but now
report `source_lineage_audit.status=not_supplied`. Strict mode refuses
to run without its full manifest. Successful strict runs still return
`actual_photo_lineage_independently_verified=false`,
`physical_source_independence_proven=false` and
`auto_confirm_allowed=false`: the map is a **caller declaration**,
not proof that two image variants really share an origin. To establish
real-photo lineages, benchmark Lane B's independent rotation/crop/image
fingerprinting on owner-approved references and freeze its reviewed
grouping before using it to test identity fusion. Never infer or emit
names, phone paths or original photographs from the map.

Six pure-synthetic regressions exercise original/crop/enhancement aliases,
same-family/same-group validity, competing person hypotheses, missing or
contradictory manifests, safe backward compatibility, and the explicit
limitation that mislabeled distinct families can fool the declaration.
No private V4/Android/People data or phone photos are accessed.


## Sampled bounding-box jitter sensitivity (2026-10-17)

An individual face/body rectangle can pass spatial association when
detector positions are treated as exact, yet become ambiguous after
a tiny detection shift. The opt-in, **synthetic-only**
`spatial_jitter_safety.py` lets researchers inspect that failure
mode without accessing photos or running any detector.

`audit_spatial_jitter(body_rows, face_rows, epsilon=.01)` takes the
same bounded opaque/same-asset normalized boxes as the existing
geometry audit. For each initially eligible face, it perturbs each
coordinate `x,y,w,h` of each body/face in the *same source frame*
by `±epsilon` in full-image normalized units, one coordinate at a
time, then requires **all** valid scenarios to preserve the same
unique person-region association. A perturbed box falling outside
valid image bounds is **uncertain**, not quietly omitted as success.
A 24-box total cap and `0<epsilon<=0.05` bound prevent unbounded
combinatorial searches.

The optional `review_with_jitter_safety()` uses the already
established same-frame face-source and (if enabled) source-family
lineage checks; if even **one** existing face evidence source lacks
stable sampled ownership, it returns
`manual_region_review_required` with zero proposals and an honest
unchanged candidate count. Back-facing observations without visible
faces still permit body/session *unconfirmed* review hypotheses.
The returned `spatial_jitter_audit` contains only aggregate
stable/unstable/uncertain counts; face IDs and candidate-region IDs
remain in-memory only. No database writes, inference or identity
confirmations are implemented.

**Strict limitations:** this is *not* a certified detector-error
bound, p-value, proof of anatomical ownership, or a measurement of
actual cropping/occlusion stability. Each coordinate varies
**individually**, not as a joint correlated perturbation; true
detection errors may be different. The report explicitly says
`actual_detector_error_distribution_measured=false`,
`multi_axis_joint_perturbations_tested=false` and
`auto_confirm_allowed=false`. A successful sampled audit merely
indicates this particular small set of synthetic changes did not
dislodge geometric ownership. A future real-world study must
independently label crowded head/torso source correspondences and
measure error distributions without tuning on holdout.

Six pure-stdlib synthetic tests cover stable interior boxes,
near-boundary uncertainty, a neighboring person's body drifting
under a detected face, rear-only no-face cases, invalid/edge
perturbations and independence from the optional crop/enhancement
source-lineage guard.


## Predeclared multi-axis detector uncertainty stress (2026-10-17)

The first `audit_spatial_jitter()` checked bounding-box perturbations
one coordinate at a time. That is necessary but not sufficient: a face
may stay inside a person's upper body region when its `y` shifts,
its height expands, OR the body's head zone shrinks individually,
yet leave the region when those errors happen **together**.

The optional `joint_cases=8..64` parameter extends the previous
single-axis protocol without changing its default `joint_cases=0`.
The first eight scenarios are permanently defined, simultaneous
adversarial changes (face down/taller with body up/shorter, edge
contractions, and other combinations); further scenarios use
deterministically seeded ±epsilon/zero changes to **every** face and
body box in the current asset. Inputs are ordered canonically by
opaque region ID, so insertion order does not influence results.
Scenarios at invalid image boundaries become uncertainty, not
dropped successes. Any scenario changing owner/status suppresses
the face-supported unconfirmed review hypothesis when
`review_with_jitter_safety(..., joint_cases=N)` is selected.

The output separately reports
`joint_scenarios_predeclared`, `joint_perturbations_checked`,
`invalid_joint_perturbations` and
`changed_joint_owner_or_status`, while retaining the earlier
per-coordinate counts and complete source-lineage requirements.
The boolean `multi_axis_joint_perturbations_tested` now reflects
actual trials, not whether the experimenter merely asked for them.
A person photographed from behind with **no face detection** still
has no face evidence to veto; non-face manual hypotheses and Unknown
remain available.

**Limits:** these are finite deterministic *synthetic* disturbances,
not a statistically calibrated distribution of errors from any face or
person detector. The chosen scenario count and epsilon must be locked
before comparing models, not tuned using held-out subjects.
An adversarial test proves a box can pass *every* single-coordinate
check while failing a simultaneous face/body head-zone perturbation.
Five additional fictional tests check that, actual review abstention,
seeded repeatability, rejection of invalid/cherry-picked small
scenario sets, and face-free rear-view behavior. No real photo, model,
body/face crop, private storage or app code is touched.


### v2 exhaustive rear-pose coverage: visible omissions, not just selected pairs

The strict `schema_version=2` frozen pose protocol chooses **exactly one
score-independent, first-lexical, distinct-source front/rear pair for
every eligible known person**. That neutralizes selecting only the
easiest 20 known people but cannot ensure that this single pair
represents the person's most difficult available back-facing views.
For example, one person may have an eligible easy back-view pair and
also a separately photographed **occluded + appearance-shift** rear
view; if the latter sorts later, aggregate selected-pair accuracy
will omit it.

`preregistered_pose_study.py` now reports
`pose_selection_audit.rear_condition_person_coverage_for_deterministic_pair`.
For each overlapping reviewed rear-view tag (`back`, `partial`,
`occluded`, `appearance_shift`, `side`, `lookalike`) it exposes
**aggregate known-person counts**: `eligible_people`,
`selected_people`, and
`eligible_people_not_selected_for_this_tag`. A rear view counts as
eligible only if some reviewed known front view exists in a **different
declared session, event and photo lineage**. All eligible rear captures
are examined under the existing finite 200,000 comparison budget:
oversized material fails closed instead of silently sampling. The
original deterministic frozen-pair selection and strict validation are
unchanged; outcome scores never influence selection or coverage.

This is a transparent **coverage diagnostic, not new accuracy evidence**.
Counts overlap: one `back+occluded+appearance_shift` person appears in
three tag counts. Source cluster/event/photo-lineage claims must still
be independently checked, and a condition with one selected pair may
remain badly underpowered. There is no automatic identity merging or
threshold adjustment. Research reviewers should decide *before*
examining model outcomes whether additional independently frozen
difficulty-specific cohort endpoints are needed. A coverage warning
cannot be cured by cherry-picking replacement pairs after holdout
scores are known.


### Reviewed subject truth-label coherence (2026-10-19)

The general offline split-provenance gate checks **every reviewed
`subject_cluster_uid` against the independently supplied score-truth
labels**, not against the model's guesses. A single reviewed subject
cluster can contain repeated captures of the *same* known truth UID
or repeated `UNKNOWN` probes, but now fails closed if it mixes a
known and Unknown identity or contains two different known truth IDs.
This check applies before later calibration/holdout analysis, even
when optional strict rear-Unknown evaluation is disabled.

This closes a within-split label-integrity loophole where two separate
known truth identities could be treated as independent calibration
subjects despite sharing one reviewed group. The existing
calibration-versus-holdout anti-leakage check is unchanged and still
requires no shared reviewed source/subject/event across the split.
Reports include only `reviewed_subject_truth_coherence_enforced=true`,
not subject IDs, and never authorize identity confirmation.

It checks consistency of **declared metadata**, not whether physical
photos were correctly clustered. An unreviewed group remains explicitly
unreviewed; two aliases of the *same physical person* under different
cluster IDs cannot be detected from opaque group metadata alone.
Externally reviewed identity truth and consented independent cohorts
remain necessary.


### Reverse known-truth-to-subject mapping guard

The generic reviewed-provenance gate now additionally rejects **one
known truth UID assigned to two different reviewed subject-cluster
UIDs in the same split**. Combined with the previous guard against
multiple truth UIDs claiming one reviewed subject, this gives a
one-to-one mapping **among declared and reviewed known subjects**.
Otherwise, the same known person appearing in several supposedly
distinct reviewed groups could inflate independent denominators.

The check is deliberately conditional on reviewed subject groups:
an `UNREVIEWED` additional capture remains unreviewed rather than
being forced into a guessed cluster, and the aggregate count of
`unreviewed_subject_probes` stays visible. It reports
`known_truth_to_reviewed_subject_mapping_one_to_one_enforced=true`
without exposing subject IDs; `physical_independence_proven=false`
and `auto_confirm_allowed=false` remain in force. Physical identity
can still be split across different *truth UIDs*, a metadata mistake
that cannot be repaired without independent human ground-truth review.


### Exact reviewed rear-condition intersections (2026-10-19)

The strict v2 preregistered pose audit additionally publishes
`rear_exact_condition_person_coverage_for_deterministic_pair`:
per-person `eligible_people`, `selected_people`, and
`eligible_people_not_selected_for_this_exact_condition` for
each **observed exact, canonical rear-probe condition tag set**.
Unlike the existing marginal per-tag coverage, a key such as
`appearance_shift+back+occluded` appears only when **one eligible rear
probe** is independently reviewed as satisfying all three tags.
Separate `back+occluded` and `back+appearance_shift` images cannot
fabricate that intersection. The field
`exact_condition_cooccurrence_verified_on_one_eligible_rear_probe=true`
refers to **declared annotations** only, not photographic verification.

The exhaustive finite comparison budget, cross-capture event/session/
photo-lineage gate, score-independent first-lexical chosen pair and
frozen manifests are unchanged. Condition cells overlap across known
people, though each exact tag combination counts each person at most
once. An absent exact key means **not observed as eligible**, not
proof that the condition does not occur in the wider collection.
Sample-size, annotation representativeness, underlying model accuracy,
physical independence, and automatic identification are not validated.
This is transparency for planning *future preregistered*
difficulty-specific endpoints, not a second chance to optimize holdout
scores or choose preferred photos post hoc.


### Exact-condition Unknown rear-view control groups (2026-10-19)

Unknown-person safety is now inspected along **two complementary
dimensions**. The existing `rear_unknown_controls[split].by_rear_condition_tag`
reports per-tag subject/event Wilson bounds. A new
`by_exact_rear_condition` groups **the complete, canonical set of
reviewed condition tags occurring together on one qualifying Unknown
rear-facing probe**. An `appearance_shift+back+occluded` cell is never
conjured from separate occluded and appearance-shifted photographs.
Each subject and event cluster counts only once per exact condition,
even if there are multiple photographs; false offers within the same
cluster count as one error. Cells require the existing
`min_condition_pairs` distinct reviewed subjects **and** events,
otherwise they are excluded from the published subgroup table and
`suppressed_exact_rear_conditions` increments. The report never
publishes subject IDs, raw photos, embeddings or sample identifiers.

This is deliberately an **additional descriptive safety audit**,
not permission to move the calibration threshold, change the frozen
protocol, auto-confirm a person or claim deployment-level Unknown
risk. A good overall rear-Unknown Wilson bound does not imply good
risk on an underpowered exact-condition intersection. Suppression is
*not* evidence of zero errors. Cells may share physical persons/events
across condition combinations, declared cluster labels remain
unverified externally, and unknown unseen combinations are not
measured. Clinical/release-like confidence or new accuracy claims
would require preregistered, independently reviewed, consented cohort
captures with enough independent hard-condition controls and a
separate on-device evaluation.


### Registered critical rear-Unknown intersection coverage matrix (2026-10-19)

Beyond exact observed condition tags, the synthetic safety report now
**always** lists six predeclared high-risk condition intersections:
`back+occluded`, `appearance_shift+back`, `occluded+partial`,
`appearance_shift+partial`, `back+lookalike`, and
`lookalike+partial`. For each, the
`critical_rear_unknown_intersection_coverage` report distinguishes:

* `not_observed`: no qualifying Unknown rear-probe simultaneously carried
  the required pair of reviewed tags;
* `observed_underpowered`: observed, but fewer than the already locked
  independent *declared subject or event* groups needed for even the
  descriptive `min_condition_pairs` threshold;
* `measured_not_safety_qualified`: both declared group counts meet
  that minimum, so aggregate errors and one-sided Wilson95 upper bounds
  are presented for diagnostic inspection, **not** used as a new
  calibration/identity/deployment approval criterion.

For the first two statuses, all small-cell metrics are `null`, rather
than exposing rare group-level counts or interpreting them as a perfect
zero-error cell. Intersections require the relevant tags to co-occur
**on the same qualifying probe**; a probe containing a third additional
tag counts for each applicable pairwise intersection, but two separate
probes with disjoint conditions cannot fabricate co-occurrence.
The six intersecting cells share subjects/events and are not independent.
No test claims that `min_condition_pairs=10` can establish a 5% error
ceiling: even **zero** observed errors in ten independent groups leaves
a much higher 95% Wilson upper bound. The fixed calibration threshold,
global Unknown safety gate, primary endpoint and refusal to auto-confirm
remain unchanged; `critical_rear_intersection_statuses_are_not_release_gates`
is explicit.

Only declared reviewer-supplied provenance is inspected, not actual
photographic likeness; true independence, legal pretrained weight
rights, face/body recognition performance on personally consented
years-apart rear-view media and S24+ runtime health still require
separate authorization and validation.


### Correlated known-person front/rear pairs: capture-event graph components (2026-10-19)

A known-person pose study may include many independently named subjects
photographed at just **one shared front event and one shared rear event**.
Those subjects remain individually interesting observations, but counting
all their pair outcomes as statistically independent can severely
understate uncertainty. The retained `overall` / rear-condition
transitions are **per known person**, not an event-aware risk bound.

The separate **`paired_event_component_safety`** diagnostic constructs
an undirected graph on each frozen pair's two **reviewer-declared event
cluster IDs** (one front and one rear). All pairs in a connected
component count as **one correlated sampling unit**: sharing either
capture event directly *or transitively* joins those pairs. For each
component, any rear wrong offer is counted once, and any
front-correct → rear-wrong degradation is counted once. Aggregate
outputs include the number of connected components, distinct declared
event nodes, largest number of pairs per component, component-level
event-group errors, and a one-sided 95% Wilson upper error bound.
Event IDs, subject IDs, photograph handles and scores never appear in
the report.

This calculation is deliberately conservative compared with an
independent-person assumption: a 25-person paired cohort from one
front event and one rear event produces **one** declared capture-event
component, not 25 independent events. A graph chain A–B, B–C, C–D
also forms **one** component even though the first and last pair do not
directly overlap. Pair selection, subject-count denominators, calibration
floor, UNKNOWN controls and frozen thresholds do **not** change.
`component_bounds_are_research_diagnostics_not_release_gates=true`
and `component_independence_physically_verified=false` are explicit.
Even 25 distinct reviewer-declared components may still share
unrecognized photo lineages, sessions, contexts, people, or selection
biases; the Wilson calculation does not magically verify statistical
independence or assert model safety.

All tests use invented capture event IDs and precomputed fictional
scores; no real photos, pretrained weights, person confirmations,
device processes or phone storage are involved. Actual error estimates
require consented independent physical-event review, protocol
preregistration, checkpoint legality, representative years-apart hard
poses and Android performance evidence.


### Shared photo families and sessions are also capture dependencies (2026-10-19)

An event-only known-person paired-pose audit misses two other concrete
sources of repeated evidence: the **same photo lineage** may contain
several different people even if an event cluster was annotated
differently; and two people may share the **same session** despite
different photo and event group labels. A second independent-person
denominator cannot be justified solely by distinct event IDs.

New `paired_multi_axis_capture_dependency_safety` conservatively
joins each frozen front/rear pair's **six typed provenance nodes**:
front and rear event clusters, photo lineages and sessions. The
undirected graph then merges all pairs that share any node, *including
transitive links across different kinds of provenance*. For example,
one pair may share a photo family with the next, which in turn shares
a session with a third pair: all three are treated as **one correlated
capture-dependency component**. Provenance keys are namespaced as
`event`, `lineage` and `session` so a coincidental identical
opaque token in two different columns never connects unrelated
captures.

The report includes counts of connected dependency components,
largest number of person pairs per component, components containing a
rear wrong offer / front-correct→rear-wrong degradation / rear
abstention, and one-sided Wilson95 *diagnostic* upper bounds. It never
reveals raw lineage, event, session, person or probe IDs. The older
`paired_event_component_safety` and person-level `overall` views
remain stable for transparent comparison. This analysis **cannot
increase** the nominal independent capture groups relative to the
event-only grouping: adding evidence of shared lineage/session can
only merge components, not split them.

Declaring different event, photo-lineage and session IDs is still
**not proof** that images come from independent physical captures or
that condition sampling is representative. Artificially merged
components are conservative, but Wilson calculations do not establish
independence of the remaining components. Both reports explicitly
retain `component_independence_physically_verified=false` and
`component_bounds_are_research_diagnostics_not_release_gates=true`.
No scores, model priors, selection, review thresholds, private photos or
actual identity decisions are changed.


### Rear-Unknown capture-dependency components beyond separate subject/event counts (2026-10-20)

The calibration/holdout rear-facing Unknown benchmark previously tracked
**reviewed subjects** and **declared events** as separate denominators.
That misses a photo-source correlation: 100 nominally distinct Unknown
subjects and 100 nominally distinct events may all be crops from *one
photo lineage*, or be linked transitively through shared sessions.
Neither of the old subject/event Wilson bounds detects every such
dependency.

New `rear_unknown_controls[split].capture_dependency_component_safety`
constructs a read-only, **typed** graph on the *qualifying rear-facing
Unknown probes*, connecting each probe's declared reviewed subject,
event, photo lineage and session nodes. A shared node links captures;
indirect overlaps connect entire correlated components. Count any
false review offer **once per connected component** and report the
aggregate components, largest component size, and a one-sided Wilson95
upper bound. Different columns have separate namespaces so coincidentally
identical opaque tokens do not manufacture a link.

In a fully artificial stress test, all 100 calibration Unknown probes
have different reviewed subjects and events but share one declared
photo lineage. The existing subject/event calibration summaries
remain 100/100; the new diagnostic correctly reports **one connected
capture-dependency component** with a false offer, and an upper
95% bound of 1.0. It explicitly marks the component's
`component_risk_exceeds_frozen_5pct_ceiling_diagnostic=true`.
The bound is only a **warning**, not a newly approved calibration
criterion. The already frozen calibration-only threshold, historical
subject/event 5% Wilson acceptance policy, comparison endpoint, and
all identity/People assignments remain unchanged. The report says
`diagnostic_not_calibration_or_release_gate=true` and
`declared_capture_independence_physically_verified=false` even when
every declared node is different.

This model-free tool does not determine whether two photographs
actually share physical origin or depict the same person. Its
correlation links are only as accurate as separately reviewed source
annotations. A risk estimate from one connected component does not
establish a valid frequentist safety rate, regardless of how many
photographs it contains. The diagnostic helps identify why an apparently
strong overall rear-Unknown calibration may not justify external or
production confidence. No photos, embeddings, model execution or
device processes are accessed.


### Opt-in preregistered v3: require rear-Unknown connected-component calibration safety (2026-10-20)

The conservative `capture_dependency_component_safety` diagnostic
introduced in #260 can reveal that hundreds of separately annotated
Unknown people/events are connected through shared photo lineages and
sessions. **The existing v1 and v2 frozen calibration policies remain
unchanged**, including their 5%-upper Wilson subject/event gates.
Researchers must not silently retrofit different acceptance criteria
after seeing holdout scores.

A new **schema version 3** is available for an independently
preregistered study. It retains the schema-v2 **first lexical
outcome-blind front/rear pair inventory**, immutable truth, roster,
provenance, pairs, model-checkpoint digest and all previous constraints.
The v3 study must explicitly register
`calibration_policy =
"calibration_only_5pct_wilson_rear_unknown_subject_event_and_capture_component_v1"`.
This cannot be replaced with the weaker v1/v2 policy: protocol
validation rejects version/policy mismatches and all attempts to
relax `rear_unknown_wilson95_budget=0.05`.

**Additional v3 rejection rule (calibration only):** after the
existing calibrated similarity floor and reviewed rear-Unknown
subject/event safety checks, collapse qualifying Unknown probes by
connected **typed subject/event/photo-lineage/session** components.
At least the declared component data must exist, and the *unrounded*
one-sided 95% Wilson upper probability of any false review offer per
component must be at most **0.05**. Otherwise reject the study
without inspecting/tuning the holdout threshold. A synthetic 100-subject,
100-event study with one shared image lineage is rejected under v3,
even though v2's older subject/event criteria still accept it.
The original v1/v2 reports and calibrations are tested to remain
unchanged.

A new `strict_rear_unknown_capture_component_calibration_enforced`
protocol-audit field and
`rear_unknown_component_calibration_wilson95_gate_enforced`
paired-study field explicitly say whether this **preregistered**
criterion was actually enforced. An existing successful v3 result
does **not** assert physical capture independence, true model accuracy,
or license/production approval. SHA-pinning likewise does not prove
that the protocol was published before first holdout inspection:
an independently attested pre-holdout commitment and consented,
representative real captures would still be necessary. No People
assignment is ever auto-confirmed.


### Opt-in preregistered v4: minimum independent declared known-pose capture groups (2026-10-20)

The v3 research protocol protects rear-facing Unknown calibration with
one-sided Wilson95 risk bounds over connected source-dependency
components. However, its known-person primary front/rear endpoint still
requires only **20 distinct known-person pairs**: 25 subjects could be
photographed during just two shared events and thus comprise only
**one independent declared capture-dependency component**. The
event/lineage/session graph identifies this risk but a v3 study reports
it without rejecting the sample.

A new, expressly opt-in **schema v4** retains *all* v3 safeguards:
preregistered first-lexical exhaustive known-person front/rear pairs,
immutable truth/roster/provenance/pair manifest digests, the unchanged
`front_rear_open_set_transitions_v1` endpoint, calibration-only
threshold and ≤5% subject/event/**connected-component** Wilson95
rear-Unknown bound. Additionally, schema v4 **must** include
`known_capture_dependency_policy =
"minimum_20_declared_event_lineage_session_components_for_known_pose_pairs_v1"`.
After reading the frozen heldout pose pair inventory, it requires
at least the locked `min_known_person_pairs=20` distinct connected
known-pair capture groups under the already established **typed
event+photo-lineage+session** dependency graph. Fewer than twenty
declared components fails closed even if hundreds of people have
scores. This minimum is based on **predeclared source provenance**,
not score values; the holdout must never be searched for a more
flattering set of pairs.

The output explicitly marks
`protocol_audit.strict_known_pose_capture_component_minimum_enforced`
true only for v4. Version 3 is still available **without** this extra
frozen known-cohort rule, and versions 1/2 preserve their older
acceptance criteria; silently backporting a new policy to an old
committed protocol is forbidden. Tests create a fictional 25-person
cohort with 25 distinct capture components, then replace its
source annotations with **one shared front event and one shared rear
event**. The v4 study rejects that cohort, while v3 still reports
its one component without rewriting the historical decision.

Twenty connected declared groups is an **eligibility minimum**, not
a statistically justified 5% known-person misidentification guarantee.
Even 20 truly independent events could all share a photographic
style, clothing, decade, camera or social context. Group annotations
are not independent proof of physical captures. All study protocols
require an externally attested pre-holdout commitment, consented
representative data, verified checkpoint rights and actual Android
runtime evaluation before any trustworthy real-world recognition
claim. Automatic identity confirmation remains disabled.
