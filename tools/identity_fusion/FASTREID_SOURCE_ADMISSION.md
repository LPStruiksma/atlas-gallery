# FastReID source-object admission — no checkpoint has been cleared

**20 October 2026, Lane C, research-only.** This checkpoint source audit is
fully offline, standard-library-only, and opt-in. It reads exclusively six
small, explicitly staged public **text** documents. It does **not** download,
open, execute or deserialize any FastReID `.pth` weights, does not inspect
private photographs, and never guesses or confirms a person's identity.

## Upstream findings, with immutable source objects

These GitHub source objects were independently fetched from the official
[JDAI-CV/fast-reid](https://github.com/JDAI-CV/fast-reid) repository
at commit `c9bc3ceb2f7a6438b62fb515ea3df6d1e999e95d`.
An object pin proves file-byte agreement with that recorded Git object,
not a signed developer identity, release-asset integrity, data-processing
consent, or model-inference safety.

| Source file | Git object SHA-1 |
| --- | --- |
| `MODEL_ZOO.md` | `d4fc8749c022fe05a5503e8086f4d0db8edbcdb8` |
| `LICENSE` | `f632668fe3fa3d7727567d0324975f3e5c501413` |
| `configs/Market1501/bagtricks_R50.yml` | `4c822ddbf6255029d4937c1c19b0452130f7a899` |
| `configs/Market1501/AGW_R50.yml` | `236803dd5ba5274dc372dc0c99c6c9463fa885c0` |
| `configs/Base-bagtricks.yml` | `9bfb11fd986e911e6ad937acb66d2b1b582cc757` |
| `configs/Base-AGW.yml` | `b737bb8d3d963fc6383672adcfe3ff33c62384fc` |

The exact revision's `MODEL_ZOO.md` publishes the following
**Market-1501** CCTV re-identification baselines:

| Training configuration | Backbone pretraining | Publisher rank-1 | Publisher mAP | Weight filename in `v0.1.1` |
| --- | --- | --- | --- | --- |
| BoT(R50), 256x128 | ImageNet | 94.4% | 86.1% | `market_bot_R50.pth` |
| AGW(R50), 256x128 | ImageNet | 95.3% | 88.2% | `market_agw_R50.pth` |

**Critically, "Pretrained: ImageNet" does not mean the downloaded
`market_*.pth` files are simply ImageNet backbones**: those links represent
the publisher's Market-1501 ReID-trained release artifacts. Conversely,
an arbitrary ImageNet-only backbone is not an evaluated ReID model. The
two distinct release assets are linked by this documentation but are **not
contained in the Git repository**, and neither a source Git SHA-1 nor a
`v0.1.1` asset name supplies a cryptographic SHA-256 for the model bytes.
Do not invent one or confuse documentation attestation with checkpoint
verification.

The upstream repository's `LICENSE` file has an Apache 2.0 header.
This alone **does not independently clear downloadable weight licensing,
training-data provenance/consent, redistribution rights, or deployment in
a personal biometric gallery**. The model-zoo rank-1 scores are published
closed-set results, **not** ATLAS measured accuracy for rear-only/partial
people, different years/clothing, lookalikes, or Unknown-person abstention.
FastReID's official [Getting Started guide](https://github.com/JDAI-CV/fast-reid/blob/c9bc3ceb2f7a6438b62fb515ea3df6d1e999e95d/GETTING_STARTED.md)
also documents automatic backbone downloads when training configurations
are executed; ATLAS must not run that uncontrolled workflow.

## Strong same-revision SHA-256 pins (20 October 2026 supplement)

The original source-audit release had a weakness: Git object identities
were SHA-1 hashes, and the external caller could choose the accompanying
SHA-256 digest. A suitably constructed replacement source that preserved
the Git SHA-1 identity would not be excluded by **caller-chosen** SHA-256.

The stronger offline verifier now additionally checks **six fixed SHA-256
digests**, independently computed from the exact source text fetched from
the same upstream commit at the already recorded Git blob SHA-1 identifiers:

| Source | Fixed SHA-256 |
| --- | --- |
| `MODEL_ZOO.md` | `72c4f5c6bba0e44d1f11f5badee651df592b60c3b6a8de6a0650068f2494d632` |
| `LICENSE` | `14a8a9a670fa989f040bc7a5d964fa7a478ffd7cb44407a9061303240d9e21df` |
| `bagtricks_R50.yml` | `1c8de024f01fdc559cb1925e9ea617bf7c6b30942e587f398f65e68f97c09d08` |
| `AGW_R50.yml` | `007d471f5640989ea33697f622568ea4375cc700c3511bbfd08cc2b0e98cd1ea` |
| `Base-bagtricks.yml` | `2054e2d0e3be7fe7daf6a5814a5ffaf98279c05cf59e805fe81d6c43179327e3` |
| `Base-AGW.yml` | `bd061de5f34af6d943233e09566759f18302bf0ae71edbd1ad5d2ee8de458939` |

The input CLI now requires each `--*-sha256` value to match the **fixed**
registered source checksum before it reads staged files. The underlying
`attest_sources` API independently checks SHA-256 over the actual bytes
in addition to Git SHA-1 and the upstream semantic contracts. The explicit
synthetic test override is still retained for fictional-only regressions:
it returns `synthetic_source_validation_only` and **never** claims an
official SHA-256 verification.

The fixed digests were independently calculated using a pure JavaScript
SHA-256 implementation checked against the standard empty-string, `abc`
and `hello` test vectors, from source text retrieved at the exact
previously pinned Git commit; each source length also matched the upstream
text retrieval. This is **cross-algorithm source-byte corroboration**, not
a signed release, independently authenticated author/publisher, or
separate model-checkpoint byte pin. Git SHA-1's general collision risks
remain historically relevant; SHA-256 provides a stronger additional byte
identity check while the project's trust in the source provider/commit
remains an explicit unverified assumption.

## Precisely what the audit does

`attest_fastreid_sources.py` requires ALL six independently staged source
files and a caller-supplied full lowercase SHA-256 for *each*. It refuses
missing/changed sources, symlinks along the whole path, hardlinked files,
oversize/nonregular files, ambiguous/model-mismatched rows, conflicting
pretraining column, altered output model URLs, missing model-config inheritance,
drift in published 256x128 evaluation input shape, absent license header,
and wrong model zoo/candidate names.

It then verifies the six pinned upstream **Git blob hashes** over the exact
source bytes before interpreting the fixed two model rows. Files have 32 KiB
per-source limits; read-only one-file-at-a-time descriptor operations ensure
no source files are written, and output is aggregate/public metadata only.

This illustrates the invocation on **six user-authorized, independently
acquired, small public text documents** (not training pictures/weights):

```sh
python tools/identity_fusion/attest_fastreid_sources.py \
  --zoo /staged/MODEL_ZOO.md --zoo-sha256 DIGEST_OF_EXACT_ZOO_BYTES \
  --license /staged/LICENSE --license-sha256 DIGEST_OF_EXACT_LICENSE_BYTES \
  --bot /staged/bagtricks_R50.yml --bot-sha256 DIGEST_OF_EXACT_BOT_BYTES \
  --agw /staged/AGW_R50.yml --agw-sha256 DIGEST_OF_EXACT_AGW_BYTES \
  --base-bot /staged/Base-bagtricks.yml --base-bot-sha256 DIGEST_OF_EXACT_BASE_BOT_BYTES \
  --base-agw /staged/Base-AGW.yml --base-agw-sha256 DIGEST_OF_EXACT_BASE_AGW_BYTES
```

No fetched upstream documents are bundled here, so CI cannot accidentally
treat public model or dataset material as an enrolled/private identity set.
Regression tests use fully **invented** tiny text, mock SHA1 source pins,
tampered model links, incorrect config inheritance, metadata collisions and
filesystem hazards. A synthetic pin override is **never** official proof.

```sh
python tools/identity_fusion/test_attest_fastreid_sources.py
python -m unittest discover -s tools/identity_fusion -p 'test_*.py'
```

All successful metadata reports explicitly state **false** for pretrained
release-asset byte/hash verification, independent weight/data-rights checks,
physical-source authentication, complete pixel/color/range preprocessing
parity, execution safety, model inference, Android p95 memory/thermal
measurements, rear/partial open-set identity accuracy and production/automatic
confirmation approval.

## Decision and next gate

**No FastReID checkpoint is approved or even queued for inference by this
source verifier.** Next meaningful work requires an independently
authenticated full SHA-256 for one exact publisher release `.pth` file,
training-data/weight-rights review, a secure no-untrusted-pickle conversion
path, a reference preprocessing contract and a frozen consented, local-only
rear/partial Unknown cohort. A comparatively heavy R50 backbone may exceed
the Galaxy S24+ budget despite good published camera ReID accuracy.
Prefer preserving abstention and comparing against light OSNet and Intel
baseline on identical held-out splits over inferring identity from clothing.
