# WHOLE-1 — bounded same-session attire/body evidence

**Decision: NO-GO for a model and CONDITIONAL for private evaluation.** No
checkpoint has passed the complete licence, provenance, conversion, ONNX
Runtime 1.23.2 CPU, same-event accuracy and device-budget gates. This work adds
an evaluator and admission probe, not a model, measured result, identity signal
or production integration. All real-data quality and real-model timing values
below are **NOT MEASURED**.

## Product boundary

Whole-person evidence may only rerank or veto a `Same person?` question already
proposed by the face layer. It cannot originate a pair, assign a People ID,
confirm a hypothesis, or merge identities. Positive use is limited to captures
on the same calendar day, no more than three hours apart, in the same declared
event and camera cohort. Missing/ambiguous timestamps, different events or
cameras, known outfit changes, uniforms, co-occurrence and failed detections
abstain. Repeated same-frame detections are a veto, not positive evidence.

This follows the existing `EvidenceSource` contract: a future scanner could
return a versioned `EvidenceReading(ATTIRE|BODY, support)` only while evaluating
an existing face-proposed `GroupPair`. Deletion or consent withdrawal must
delete cached vectors; model/preprocessing version changes must invalidate them.
Content boundaries must be checked before crop decoding, and timestamps must
retain provenance rather than silently falling back to file time. No score may
bypass human confirmation. False confidence is expected for uniforms, family
wardrobes and repeated clothing.

## Candidate admission audit

The table separates repository code, released weights and training/evaluation
data rights. A repository licence does not license a checkpoint or its data.

| Candidate | Pinned public source and published contract | Code / weights / data | Conversion and ORT 1.23.2 CPU | Admission |
| --- | --- | --- | --- | --- |
| Torchreid OSNet x0.25 MSMT17 | `kaiyangzhou/osnet` release repository revision `01af85e82a9db4f3a4f6ed3a72ed9150bd416d04`; recorded checkpoint SHA-256 `cf55163d78fc44c62c82f85ab62d39f10438679b5abe8c698ae08cfa84aa6e18`; RGB 256×128, ImageNet mean/std, 256-D embedding | Code: MIT. Weight grant: not independently established. MSMT17 rights/consent for this gallery purpose: not established. The exact bytes may be staged privately only after approval; never deserialize an unverified `.pth`. | Trusted conversion must occur in an isolated, pinned toolchain after byte and rights approval, export a static `1×3×256×128` float ONNX graph, and compare reference embeddings. No approved conversion or ORT 1.23.2 run exists. | **NOT ADMITTED** |
| Intel `person-reidentification-retail-0287` | `openvinotoolkit/open_model_zoo@af810de42bcf460a18610481f639dcdc86249e69`; manifest blob `66bd141ab025c72eac575c0491a32a83b546e60f`; advertised BGR/NCHW `1×3×256×128`, 256-D output. The manifest records a 3,571,437-byte ONNX artifact named `person-reidentification-retail-0267.onnx` with SHA-384 `c650c97722d597efd65fe041d67e17658d776c45d56736b817264ec544d8a9b5a5e8cdc1a0920cc2aada61151c1a9191`. | Repository/model manifest: Apache-2.0. Independent checkpoint-training dataset, consent and downstream gallery-purpose review: unresolved. Stage only from the manifest route after legal approval and verify SHA-384 before locally deriving the required SHA-256. | An ONNX route is published, but the filename mismatch requires graph identity/tensor inspection. Actual ORT 1.23.2 CPU load, numerical parity and timing: **NOT MEASURED**. | **NOT ADMITTED** |
| FastReID BoT(R50), Market-1501 | `JDAI-CV/fast-reid@c9bc3ceb2f7a6438b62fb515ea3df6d1e999e95d`; official model-zoo/config source objects are pinned in `FASTREID_SOURCE_ADMISSION.md`; published input 256×128. Release asset `market_bot_R50.pth` has no independently authenticated immutable digest in the audit. | Code: Apache-2.0. Weight licence, ImageNet initialization terms, Market-1501 data rights/consent and redistribution: unresolved. | No trusted checkpoint, safe conversion, static tensor/output contract, parity result or ORT 1.23.2 CPU measurement. R50 is retained only as a research ceiling. | **NOT ADMITTED** |

No tool in this change downloads a checkpoint. The optional audit accepts only
a user-staged, regular, non-linked file of at most 128 MiB, checks an exact
caller-provided SHA-256 and a separate local approval text containing both the
digest and `APPROVED FOR LOCAL EVALUATION`, requires exactly ONNX Runtime
1.23.2 with the CPU provider, then inspects a single float NCHW input/output.
Timing is opt-in and uses an all-zero synthetic tensor; it is not accuracy or
media preprocessing evidence.

## Private same-event evaluation manifest

The JSON manifest stays outside the repository. It must be assembled from
consented or specifically licensed material and human-verified labels; a face
model prediction is not ground truth. Use opaque pair/event/camera tokens only:
no names, People IDs, file names, media paths, images or embeddings. Maintain a
separate private provenance ledger covering consent, retention and deletion.

Freeze thresholds on validation events, then evaluate once on disjoint test
events. The private curator must attest that persons, events and cameras do not
cross those splits. Include different-person similar-clothing pairs (especially
co-occurring people and uniforms), rear/small/occluded true pairs, outfit
changes, failed person detections and explicit abstentions. Camera mismatch is
excluded in WHOLE-1 rather than interpreted as appearance evidence.

Minimal evaluation document (`pairs` may contain more records with the same
fields):

```json
{
  "schema": "atlas-whole-person-l1-eval-v1",
  "rights": "consented",
  "human_verified": true,
  "pairs": [{
    "pair_id": "opaque-001", "label": "match", "face_proposed": true,
    "score": 0.81, "captured_a": "2026-01-02T10:00:00Z",
    "captured_b": "2026-01-02T12:00:00Z", "event_a": "event-a",
    "event_b": "event-a", "camera_a": "camera-a", "camera_b": "camera-a",
    "same_frame": false, "shared_uniform": false,
    "outfit_change": false, "detection_ok": true
  }]
}
```

Run with a threshold already frozen on the disjoint validation split:

```sh
python tools/identity_fusion/attire/evaluate_whole1.py \
  --manifest /authorized/private/test-manifest.json --threshold 0.73
```

The aggregate-only report gives positive, negative, question and abstention
counts; face-only and augmented question-candidate recall; wrong-person question
rate (`wrong questions / all eligible nonmatches`); question precision; and 95%
Wilson intervals. A no-face-proposal positive remains in the recall denominator
but attire cannot turn it into a candidate. `actual_identity_false_merges` is
zero by policy because the evaluator cannot merge; that is not a measured model
false-match claim. Synthetic tests validate software invariants only.

Optional local graph admission (the rights file is private and is not committed):

```sh
python tools/identity_fusion/attire/audit_ort_admission.py \
  --model /authorized/staging/model.onnx --sha256 "$SHA256" \
  --rights-file /authorized/staging/approval.txt --runs 100
```

## Results and decision gates

| Evidence | Result |
| --- | --- |
| Licensed same-event evaluation cohort and split sizes | **NOT MEASURED** |
| Face-only recall; augmented rear/small/occluded question recall | **NOT MEASURED** |
| Wrong-person question rate, precision and uncertainty | **NOT MEASURED** |
| Candidate ranking lift | **NOT MEASURED** |
| Model size and host/phone CPU crop p50/p95 | **NOT MEASURED** |
| Preprocessing wall time and phone thermal/memory behavior | **NOT MEASURED** |

Remain **NO-GO** until one exact model has separately approved weights and data
rights, a safe reproducible ONNX conversion (or publisher ONNX) with numerical
parity, successful 1.23.2 CPU admission, and disjoint-event private evaluation.
A later **CONDITIONAL** app unit may implement only the question-scoped evidence
adapter after thresholds and vetoes are frozen. Any scanner/cache integration,
UI explanation and deletion/invalidation behavior should be separate reviewed
units. Automatic merging remains out of scope.

## Reproducibility

```sh
python tools/identity_fusion/attire/test_whole1.py
python -m unittest discover -s tools/identity_fusion/attire -p 'test_*.py'
```

These commands require only Python's standard library. Actual graph admission
additionally requires locally installed `numpy` and exactly
`onnxruntime==1.23.2`; absent approved model/rights inputs, it is intentionally
not run and real inference remains **NOT MEASURED**.
