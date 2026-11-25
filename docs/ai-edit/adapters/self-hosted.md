# Self-hosted AI Edit adapters (AIE-8b / #194)

Two Android-side `ImageEditProvider` implementations are available as source-level adapters:
`AtlasProtocolProvider` for the versioned [ATLAS protocol](../PROTOCOL.md) and
`ComfyUiProvider` for a ComfyUI **API-format workflow**. They do not introduce a provider
fallback or a new background upload route. Existing production Custom server profiles
currently use the process-resumable `EditJobRunner` path; these new providers are
adapter building blocks, **not a new Settings service kind or UI affordance**. A future
registry hookup can select them without changing the editor's provider contract.

## ATLAS protocol

Use a user-entered, authenticated `https://host/prefix/v1/` endpoint as
`EditHttpConfig(baseUrl, setOf(host))` and a Keystore-backed
`EditHttpAuth.Bearer` or `CustomHeader` supplier. The adapter reads
`GET capabilities` before every admission, accepts only
`schema_version=1` / `protocol_version=1.0`, and maps supported capability
flags, image formats, limits and typed parameters into AIE-2.
An offline server, invalid version or malformed capability schema fails closed.
It submits only the caller's selected image, instruction, optional selected
mask/references and validated options in one non-replayed multipart POST,
polls the returned opaque job id, and downloads the result through the same
pinned host. It distinguishes `refused` from technical `error` and cancellation.
The server's cost quote is **unknown**, not zero, in this adapter.

The code requires `SourceImageLoader` to produce a correctly resized,
metadata-stripped PNG/JPEG. Handles and URIs are never sent as form fields.
The adapter verifies image headers and bounded sizes, and writes a complete
output into the caller-provided app-private directory.

## ComfyUI

Provide a TLS-terminated, authenticated ComfyUI API endpoint and a JSON
**API-format** graph. The graph must include **whole string slots**
`"{{image}}"` and `"{{prompt}}"`. The optional `"{{mask}}"` and
`"{{seed}}"` slots enable their matching capabilities; an unrequested
reference or unknown slot is never silently replaced. A seed slot uses a
validated integer option or defaults to 0. No arbitrary inline string
replacement occurs; the graph is parsed and only exact string values are bound.

The adapter uploads only the selected image (and an explicitly supplied mask)
through `POST upload/image`, submits a **single** `POST prompt`, polls
`GET history/{prompt_id}`, retrieves one `type=output` image from Comfy's
`GET view?filename=…&subfolder=…&type=output`, and can delete its own queued
prompt through `POST queue`. It never uses global `/interrupt`. A response
can be reported as an explicit refusal only via `atlas_status=refused`;
otherwise technical failures are not called moderation. A cancellation may
not halt an already executing GPU operation.

The Comfy view URL has the original configured host, HTTPS scheme and port,
a fixed `view` path and encoded/validated query values. No redirect is
followed; results are bounded to 32 MB and verified before saving. This
narrow GET helper exists because the main transport intentionally rejects
query-string paths, and does not loosen its general restrictions.

### Bundled example API graphs

- `app/src/main/assets/ai_edit/workflows/qwen-image-edit.json` uses
  Qwen Image Edit's `TextEncodeQwenImageEdit` and model/CLIP/VAE loaders.
- `app/src/main/assets/ai_edit/workflows/flux-kontext.json` uses
  FLUX Kontext's `FluxKontextImageScale`, `ReferenceLatent` and
  diffusion/VAE loaders.

These are **operator-adjustable templates**, not preloaded weights or a
guarantee that every ComfyUI installation has the required node classes or
model filenames. Verify the node versions and paths in the target ComfyUI
instance before any live call. They are derived from the [Qwen sample](https://comfy.org/hub/workflows/qwen-image-edit-be81dd6a1a51/)
and [FLUX Kontext sample](https://comfy.org/hub/workflows/flux-kontext-dev-image-edit-6f75cc57d5d6/)
but simplified to standard API graphs with the explicit ATLAS slots.
The output size of real model workflows may not match a requested
`OutputSpec`; this adapter rejects such an output instead of misreporting
its dimensions.

## Verification and boundaries

`AtlasProtocolProviderContractTest` and `ComfyUiProviderContractTest`
use the existing JDK-only `FakeProviderServer`, synthetic 8×8 PNGs,
a local substituted-HTTPS connector and no real credentials, personal
media, GPU, paid inference or external HTTP. They cover success, refusal,
network failure, slow asynchronous polling, idempotent cancellation,
time bounds and one terminal streaming outcome.

No JVM or Android tests were run in the ChatGPT authoring seat: require
exact-head hosted CI plus independent Review. The real endpoint graph
execution and runtime registry integration remain unverified.
A plain revert removes the adapters, bundled samples and fake tests.
