# IDS-12B — self-hosted whole-head engine deployment gate

Status: **UNAVAILABLE / NOT MEASURED**. This is a deployment and evidence checklist, not a model-quality claim or an admission of a working full-head engine. Scope: [IDS-12B #773](https://github.com/LPStruiksma/atlas-gallery/issues/773), under [goal #467](https://github.com/LPStruiksma/atlas-gallery/issues/467). Quality gate: [HEAD_ACCEPTANCE.md](HEAD_ACCEPTANCE.md).

## Admission prerequisites (operator-side, fail closed)

An operator must separately record and independently verify the **original hosting source** and immutable SHA-256 for every runtime, model, text/image encoder, head-swap adapter and workflow artifact; licence text/terms and permitted use; adapter training-data provenance and usage rights; and required policy/safety safeguards. **Do not** infer these facts from a file name or a repository mirror. The BFS adapter is **tier D / benchmark-only**, not an admitted runtime adapter until its provenance is independently verified. A provenance-clean replacement may be used only once its own evidence passes the same gate. No model binaries, adapters, credentials or personal images belong in this repository.

A FLUX.2 klein 4B head-swap route, if implemented, must be **explicitly configured and off by default**. Do not alias it to `flux`, which denotes FLUX.1 Kontext, and do not describe a generic ComfyUI graph or an image-echo backend as a proven head-replacement engine. Missing or mismatched artifacts, an unverified adapter, insufficient safeguards, a changed workflow or absent real acceptance proof must return **HEAD unavailable**, never advertise `full_head`.

An admitted workflow must consume the target photograph, a *predeclared explicit full-head crop and mask*, reference image(s), seed and the configured adapter. It must produce one size-validated generated head crop with metadata stripped, preserve the unedited area and supply reproducible lineage. The v1 edit service keeps bearer authentication, local HTTP restricted to loopback, TLS on remote transport, no redirects, bounded uploads and cancellation that prevents publishing failed work. Do not choose a remote provider or transmit media without explicit user selection.

## Evidence ledger

| Evidence | Current status |
| --- | --- |
| Original-host model, encoder, runtime, adapter, workflow licence/provenance and exact hashes | NOT MEASURED / NOT VERIFIED |
| Mocked protocol and all fail-closed paths in exact-head hosted Python CI | NOT MEASURED for IDS-12B |
| Real, blinded, licence-cleared/synthetic 20+ pair whole-head acceptance under frozen IDS-12A rubric | NOT MEASURED |
| User-advertised whole-head capability | UNAVAILABLE |

No GPU/model inference, adapter provenance check, paid compute or private-media transfer is authorized or reported by this document. Only exact-head CI results and a separately linked, independently reviewable benchmark may change the above evidence statuses.

## Implemented admission scaffold

The server now recognizes the separately named `flux2-head` backend. It is an
unavailable-by-default admission scaffold, not evidence that an adapter exists
or that a head swap succeeded. Configuration requires independent, affirmative
licence, provenance, safeguards and IDS-12A acceptance gates plus exact hashes
for the original-host model, encoder, runtime, adapter and API workflow. Those
artifacts are absent here, their facts remain **NOT VERIFIED**, and the real 20+
pair benchmark remains **NOT MEASURED**. Consequently no production deployment
may currently set all gates true or advertise `full_head`.

Startup and each attempted edit recheck all artifact bytes. The scaffold requires
a bounded explicit crop, seed, full-size mask and reference(s). It never calls
the provider, imports a model or publishes an image or lineage.
The exact mode is scaffold; full_head and all other modes are rejected even
with affirmative synthetic evidence. The admission-only typed graph contract (#1012) fixes nodes 1–3 (LoadImage:
target, mask, one reference), 4–5 (ImageCrop: target and mask), 6
(AtlasFlux2Head: both crops, reference, instruction, seed, adapter) and 7
(PreviewImage: sole output from node 6 port 0). Extra nodes, wrong classes,
fields and edges are rejected. Both crop nodes receive the same explicit
non-origin coordinates and dimensions. The custom node is an interface contract,
not proof of an existing runtime. The graph is prepared but never submitted.
Actual provider output decoding, exactly one crop-sized PNG, metadata removal,
lineage and execution require a separately reviewed activation and real evidence.
BFS remains tier D / benchmark-only.
