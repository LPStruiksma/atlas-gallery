# Intel OMZ 0287 stronger source-admission v3 (20 October 2026)

**Research only; no model execution or photo/People access.** This amendment
adds a separately selected **schema v3** to the existing
`attest_upstream_source.py` evaluator. Previous v1/v2 documents remain
parseable with their **original weaker checks and unchanged claim status**.

## Why v3 exists

The prior source-register v2 pinned exact `open_model_zoo` **Git SHA-1
object IDs** for `model.yml`, `LICENSE`, and optional
`accuracy-check.yml`, but permitted the person invoking the CLI to supply
a newly computed SHA-256 over those staged local bytes. If a malicious or
substituted source maintained the old SHA-1 identity, a caller-selected
SHA-256 would not independently secure the intended source version.

The stronger register requires all **three** source files with fixed
SHA-256 digests independently computed from bytes returned by the official
GitHub repository at the previously fixed commit
`af810de42bcf460a18610481f639dcdc86249e69`.

| Upstream source file | Git SHA-1 blob | Independently computed SHA-256 |
| --- | --- | --- |
| `models/intel/person-reidentification-retail-0287/model.yml` | `66bd141ab025c72eac575c0491a32a83b546e60f` | `d424d78044d5da414d540514d28da01ee0e26e834965a51c0a6661c05ee190c7` |
| `LICENSE` | `261eeb9e9f8b2b4b0d119366dda99c6fd7d35c64` | `c71d239df91726fc519c6eb72d318ec65820627232b2f796219e87dcf35d0ab4` |
| `models/intel/person-reidentification-retail-0287/accuracy-check.yml` | `7e4aed04f03f88540533e8ac74c281589d8b18ae` | `6b69d25a54ecc8ce7dcdb304392d464351f1dc4e847feb6e3179b6a4843fb960` |

The SHA-256 calculation was independently cross-checked against SHA-256
test vectors (`abc`, empty string), and the returned source text lengths
matched the exact pinned source fetches. These are **source-file bytes**,
not the separately registered 3,571,437-byte ONNX weight artifact.

Schema v3 requires (a) the precise existing repository/commit/paths,
(b) exactly three fixed SHA-256 fields whose values **cannot be overridden**
by the manifest or CLI caller, (c) all three local public source documents
with matching SHA-256, (d) the prior Git SHA-1 object identities, and
(e) the same model-artifact-size/license pointer and Market-1501 bilinear
evaluation semantics. Omitting the evaluation file is no longer allowed
under v3; older v1/v2 evaluations retain their original optionality and
must report `strong_three_source_sha256_pins_verified=false`.

Run after independently, lawfully obtaining **only public source text**:

```sh
python tools/identity_fusion/attest_upstream_source.py \
 --manifest /staged/model.yml \
 --manifest-sha256 d424d78044d5da414d540514d28da01ee0e26e834965a51c0a6661c05ee190c7 \
 --license /staged/LICENSE \
 --license-sha256 c71d239df91726fc519c6eb72d318ec65820627232b2f796219e87dcf35d0ab4 \
 --accuracy-check /staged/accuracy-check.yml \
 --accuracy-check-sha256 6b69d25a54ecc8ce7dcdb304392d464351f1dc4e847feb6e3179b6a4843fb960
```

No files are downloaded or staged by this repository tool, and none are
committed as real samples. CI tests use **invented synthetic** source bytes,
mocked Git SHA-1 collision success and a v3 SHA-256 rejection, stale/missing
metadata, CLI refusal before file reads, and legacy v1/v2 status preservation.

The stronger pin is **not** a developer signature, proof of genuine GitHub
publisher identity/commit ancestry, clearance of model-checkpoint
redistribution or training data, a full ONNX semantic/security validation,
preprocessing equivalence with Android image decoding, nor evidence of
person reidentification from behind. It does not evaluate any actual model,
run on an S24+, open private images or approve automatic identity tagging.
