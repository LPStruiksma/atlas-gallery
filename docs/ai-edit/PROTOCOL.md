# ATLAS Image Edit Protocol 1.0 (AIE-8a)

This versioned HTTP contract enables ATLAS to edit one explicitly selected photo through a user-configured, self-hosted endpoint. No background photo indexing, People scanning or arbitrary gallery uploads are part of the protocol. AIE-8b consumes this API.

## Transport and authorization

Production deployments MUST terminate TLS at an HTTPS ingress, authenticate every request (including capabilities and binary result retrieval) and keep the backend on a private network. Loopback HTTP is only for development. The reference server takes a strong token from the ATLAS_TOKEN environment variable and accepts either Authorization: Bearer TOKEN or X-Atlas-Token: TOKEN. The token must be provisioned out of band, never bundled with the APK, placed in URLs, logged or committed. The client never chooses server-side model download locations, ComfyUI base URLs or workflows; only the operator may configure these.

Request size limit: 20,000,000 bytes overall and 12 multipart parts. Supported input image file formats: PNG or JPEG. The server checks signatures and dimensions; the inference image decoder also rejects malformed image payloads. Do not send personal media or prompts to CI or public bug reports.

## GET /v1/capabilities

Authenticated, 200 application/json. Version 1 describes the conceptual ProviderCapabilities surface of AIE-2 (#187): accurate instruction-edit, mask, reference, candidate, multi-turn, seed, pixel/format, async/streaming, typed parameter and pricing information. As the AIE-2 Kotlin types are finalized, map these wire keys explicitly rather than assuming wire compatibility with an unmerged Kotlin implementation.

Example fake response:

~~~json
{
  "schema_version": 1,
  "protocol_version": "1.0",
  "provider_id": "atlas-fake",
  "display_name": "ATLAS CPU-only fake",
  "models": [{"id":"fake-echo","display_name":"fake-echo"}],
  "features": {
    "instruction_edit": true,
    "mask_edit": false,
    "reference_images": {"max_count":0},
    "candidates": {"max_count":1},
    "multi_turn": false,
    "seed": false,
    "async_jobs": true,
    "streaming": false,
    "input_formats": ["image/png","image/jpeg"],
    "output_formats": ["image/png","image/jpeg"],
    "max_input_pixels": 16777216,
    "max_output_pixels": 16777216,
    "aspect_constraints": null,
    "cost_estimate": null
  },
  "advanced_parameters": [
    {"name":"fake_outcome","type":"enum",
     "enum":["success","refused","error"],"default":"success"}
  ]
}
~~~

Advanced parameters have a name and type (integer, number, or enum), optional minimum and maximum or enum options, and an optional default. A missing cost estimate is null and MUST NOT be interpreted as free GPU time. Image-edit implementations advertise only options they actually parse. The direct Diffusers adapters support one instruction-driven image, seed and steps; Qwen exposes `true_cfg_scale` whereas FLUX Kontext exposes `guidance_scale`. Neither supports masks, references or multi-turn in this server. ComfyUI flags mask/references/seed only when its configured API-format workflow contains the relevant placeholders. No adapter quietly ignores unsupported input or silently falls back to another provider.

## POST /v1/edits

Authenticated multipart/form-data, HTTP 202. Fields:

| Field | Cardinality | Content |
| --- | --- | --- |
| image | exactly 1 | source PNG/JPEG file |
| instruction | exactly 1 | UTF-8 natural-language instruction, 1..8000 characters |
| mask | 0 or 1 | optional PNG/JPEG; requires advertised mask_edit |
| reference | 0..max_count | optional repeatable PNG/JPEG reference images, in order |
| options | 0 or 1 | JSON object with only schema-advertised, type-checked keys |

The upload contains only these explicit inputs, never a library path or remote image URL. Rejected unsupported features get 422; malformed JSON/multipart gets 400, unsupported media 415, oversized requests 413, absent Content-Length 411, a full in-memory queue 503, and invalid auth 401.

Returns a job with opaque UUID, timestamps, coarse progress, URLs scoped to this server and status queued. Example:

~~~json
{
  "id":"0dd5d48c1044480c97b6c6ac2bde59d3",
  "status":"queued",
  "progress":0,
  "created_at":"2026-11-15T12:00:00Z",
  "updated_at":"2026-11-15T12:00:00Z",
  "status_url":"/v1/edits/0dd5d48c1044480c97b6c6ac2bde59d3",
  "result_urls":[]
}
~~~

## GET /v1/edits/{id}

Authenticated, 200 JSON with the same job envelope. Status is exactly queued, running, succeeded, refused, error, or cancelled. Progress is integer 0..100 and is coarse, not an accurate GPU diffusion-step estimate.

Succeeded jobs advertise one authenticated local binary URL /v1/edits/{id}/result (GET 200 image/png for GPU engines, or echoed PNG/JPEG for fake). Before success, after cancellation or after retention expiry, binary retrieval returns 404. Binary endpoints return Cache-Control: no-store.

Explicit provider/workflow refusal produces status refused with refusal: {code,message}; it is not a server error. A failure to connect, a timeout, a malformed workflow or an exception produces status error with error: {kind:"server",code:"BACKEND_ERROR",retryable:true}, never refused. The fake backend's synthetic fake_outcome option can exercise both cases without content filtering. No silent retry, cloud upload or provider fallback occurs.

## DELETE /v1/edits/{id}

Authenticated, 200 job envelope. Cancels queued or running work and prevents further result publication. CUDA kernels may continue until inference returns and ComfyUI cancellation only best-effort removes queued prompts; cancellation does NOT guarantee immediate cessation of billed GPU work. An already terminal job stays terminal. Unknown or expired IDs return 404.

Jobs exist in memory for one process only; at most 32 retained and one worker per process. Terminal results/status expire after 3600 seconds, process restart loses all jobs. No durable queue, multi-instance load balancing, shared auth, quotas, hardened internet-facing server, GPU preemption or TLS termination is supplied. Use private ingress, reverse-proxy limits and monitoring before any real deployment.

## Compatibility, privacy and licensing

This is version 1.0 with capability schema integer 1. Incompatible changes require a /v2 surface. Every version or unadvertised option must fail explicitly rather than be silently interpreted. AIE-8b must implement explicit version/capability mapping and maintain user choice over selected media, provider, retry, cancellation and non-destructive result saving.

The reference server implements no ATLAS-side content-category filtering. Provider/model restrictions, applicable law and license terms still apply. Notably, FLUX.1 Kontext [dev] uses a non-commercial model license with filtering or manual-review conditions: simply running this server unfiltered does not satisfy those terms; operators must implement required controls or arrange suitable licensing before using it. Qwen-Image-Edit weights are Apache-2.0. No model is promised to accept all prompts.

GPU and Comfy adapters normalize the source into fresh RGB pixels (no EXIF/GPS upload) and save output in fresh PNG without source metadata; fake mode echoes synthetic bytes and is not an image quality benchmark. Client must not silently overwrite the original. The server never inspects the rest of a gallery.

## Reusable conformance tests

Python tests: tools/ai_edit_server/tests/test_protocol.py (pytest-compatible unittest cases) against CPU fake. Run with python3 -m pytest tools/ai_edit_server/tests -q, or dependency-free python3 -m unittest discover -s tools/ai_edit_server/tests -p 'test_*.py'. Existing exact-head CI discovers test/additive-regressions/ai-edit-server-contract.mjs, which invokes the dependency-free Python suite. CI never loads an inference model or sends any paid API requests.
