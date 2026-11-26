# ATLAS self-hosted image editor (AIE-8a)

Reference server for the [versioned ATLAS protocol](../../docs/ai-edit/PROTOCOL.md). It is independent of the Android app. Modes: fake (CPU-only, Python standard library), qwen (Diffusers/Qwen-Image-Edit), flux (Diffusers/FLUX.1 Kontext [dev]), and comfy (proxy to one operator-configured ComfyUI API workflow). The separately named `flux2-head` is a non-executing admission scaffold; see [HEAD_DEPLOYMENT.md](../../docs/identity-studio/HEAD_DEPLOYMENT.md). No model weights, cloud resources, real photos or paid endpoints are needed for CI.

## Run the fake locally (no Python dependencies)

~~~sh
export ATLAS_TOKEN="$(python3 -c 'import secrets;print(secrets.token_urlsafe(32))')"
python3 -m tools.ai_edit_server.server --backend fake --host 127.0.0.1 --port 8080
# In another terminal, export the SAME token and run:
curl -H "Authorization: Bearer $ATLAS_TOKEN" http://127.0.0.1:8080/v1/capabilities
# Use a synthetic local PNG fixture, never personal media:
curl -H "Authorization: Bearer $ATLAS_TOKEN" -F image=@synthetic.png \
  -F instruction="change this synthetic pixel" http://127.0.0.1:8080/v1/edits
~~~

Poll the returned status_url with the same token; fetch result_urls[0] when succeeded. Fake echoes the image bytes. Only fake accepts the test-only options field with fake_outcome=refused or error to exercise terminal outcomes.

~~~sh
python3 -m pytest tools/ai_edit_server/tests -q
# No third-party dependencies, exactly what the existing CI additive runner invokes:
python3 -m unittest discover -s tools/ai_edit_server/tests -p 'test_*.py' -v
~~~

## Run an open-weight Diffusers engine (operator/GPU)

The Qwen-Image-Edit model is Apache-2.0 and ~20B parameters. Budget an 80 GB VRAM GPU for a comfortable unquantized BF16 deployment; 48 GB may require CPU offload or quantization not implemented here. [Official model card](https://huggingface.co/Qwen/Qwen-Image-Edit).

FLUX.1 Kontext [dev] is ~12B parameters and a 48 GB+ GPU is a sensible starting plan (80 GB for extra margin), but its [non-commercial license](https://huggingface.co/black-forest-labs/FLUX.1-Kontext-dev) **requires filters or manual review**. No ATLAS-side category filter is added; running this service alone is not license compliance. Operators must ensure lawful/licensed use, implement required controls separately or get a different license. Backend limitations and restrictions are not hidden.

On a GPU machine with CUDA drivers/PyTorch compatibility:

~~~sh
python3 -m venv .venv && . .venv/bin/activate
pip install -r tools/ai_edit_server/requirements-gpu.txt
# Set ATLAS_TOKEN from a secret manager; never commit it
ATLAS_BACKEND=qwen python3 -m tools.ai_edit_server.server --host 127.0.0.1 --port 8080
# Or ATLAS_BACKEND=flux, subject to its license
~~~

The initial real edit causes a potentially large Hugging Face model download; this NEVER occurs for fake or CI. Direct Diffusers adapters implement single-image instructions, steps and seed. Qwen-Image-Edit exposes `true_cfg_scale` (default 4.0, with a negative prompt), while FLUX.1 Kontext exposes embedded `guidance_scale` (default 2.5); these options are deliberately distinct. They explicitly reject masks, references or unknown parameters. They serialize generation on a single worker. An in-progress CUDA call may be impossible to abort without stopping the process; HTTP cancellation suppresses result publication and is not a GPU refund.

### Indicative GPU rental prices in USD per GPU-hour (checked 2026-11-15)

| Provider | GPU | Published price | Fit note |
| --- | --- | --- | --- |
| [Runpod Secure](https://www.runpod.io/pricing) | L40S 48 GB | $1.09/hr | FLUX baseline, uncertain for unquantized Qwen |
| [Runpod Secure](https://www.runpod.io/pricing) | A100 PCIe 80 GB | $2.09/hr | Preferred initial Qwen BF16 trial |
| [Runpod Secure](https://www.runpod.io/pricing) | H100 PCIe 80 GB | $2.89/hr | Faster, costs more |
| [Runpod Secure](https://www.runpod.io/pricing) | RTX 4090 24 GB | $0.89/hr | Not enough for default BF16 Qwen |
| [Vast.ai marketplace](https://vast.ai/article/how-much-does-it-cost-to-rent-a-gpu-in-the-cloud-live-pricing-guide) | RTX 4090 24 GB | From ~$0.27/hr | Spot/marketplace, not a BF16 Qwen recommendation |
| [Modal](https://modal.com/pricing) | L40S 48 GB / A100 80 GB / H100 80 GB | ~$1.95 / $2.50 / $3.95 per GPU-hour | GPU rate derived from published per-second rate times 3600; requires Modal deployment wrapper |

Estimates are not a measured benchmark or price guarantee; add storage, CPU/memory, egress and idle time. Check provider availability and rates when renting. NO GPU rental, image-edit API spend, personal-image upload or model download was performed in this unit.

## Docker

Build fake mode without pip installs (only downloads the public Python base image if not cached):

~~~sh
docker build -f tools/ai_edit_server/Dockerfile -t atlas-edit .
docker run --rm -p 127.0.0.1:8080:8080 \
  -e ATLAS_TOKEN="$ATLAS_TOKEN" -e ATLAS_BACKEND=fake atlas-edit
~~~

For an explicitly approved GPU run on a host with NVIDIA Container Toolkit:

~~~sh
docker build -f tools/ai_edit_server/Dockerfile --build-arg INSTALL_EXTRA=gpu -t atlas-edit-gpu .
docker run --rm --gpus all -p 127.0.0.1:8080:8080 \
  -e ATLAS_TOKEN="$ATLAS_TOKEN" -e ATLAS_BACKEND=qwen \
  -v atlas-hf-cache:/root/.cache/huggingface atlas-edit-gpu
~~~

For ComfyUI proxy, use INSTALL_EXTRA=comfy to install Pillow. Provide ATLAS_COMFY_WORKFLOW as a read-only mounted API-format JSON file and ATLAS_COMFY_URL as the configured private HTTPS URL or loopback HTTP, plus optional ATLAS_COMFY_TOKEN and ATLAS_COMFY_MODEL_ID. Default server bind on host is loopback; container opens its own 8080 listener with host-side loopback mapping. Put a TLS/authenticated reverse proxy in front for remotely connected phones. Runpod/Vast support containers; Modal needs provider-specific app wrapping. No public ingress is supplied here.

## ComfyUI workflow adapter

Export a functioning image-edit workflow using ComfyUI's API format (not its visual-editor format). In its LoadImage node, set inputs.image to the literal string ATLAS_IMAGE; in the edit-text node, set its text input to ATLAS_INSTRUCTION. The adapter replaces those exact string values, not arbitrary script fragments. Optional placeholders: ATLAS_MASK (LoadImage input), contiguous ATLAS_REFERENCE_0 to ATLAS_REFERENCE_3 (only if the workflow really consumes them), and ATLAS_SEED, ATLAS_STEPS, ATLAS_GUIDANCE_SCALE in the appropriate sampler numeric inputs. The capability response reflects which placeholders are present; validate that the workflow actually honors them before relying on the advertised flags. Custom ComfyUI workflows can vary widely in prompt adherence and fidelity.

The proxy normalizes the selected source/mask/references through Pillow to fresh PNG (source metadata stripped), uploads only these to ComfyUI /upload/image, submits /prompt, polls /history/{id}, downloads the first available result via /view, and normalizes its pixels to metadata-free PNG. A workflow must explicitly report atlas_status=refused to map to refused; transport or ordinary graph failure always maps to error. Cancellation tries to remove the prompt from the ComfyUI queue but never uses the global interrupt endpoint that could affect someone else's work. No other model is silently selected.

## Security/operational limits

- Bearer or X-Atlas-Token authentication on ALL endpoints. Strong ATLAS_TOKEN (12+ chars), supplied outside source control, not logged or echoed. TLS is mandatory for remote access; never publicly expose an unauthenticated ComfyUI process.
- No user-selectable network backend URL, no redirects on the ComfyUI proxy, no background library scan, no ATLAS-side category filters. Follow all applicable laws and model licenses separately.
- One process has 1 worker, 32 job slots, and 1-hour terminal retention. In-memory queue/results are lost on restart, not multi-replica safe; no durable storage, per-user quotas, accurate GPU progress, hard GPU cancellation, TLS termination or production hardening.
- Use only synthetic/licensed imagery for tests. AIE-8b must bind this protocol to explicit user choices and implement client-side non-destructive save and failure handling.

## Provenance-gated FLUX.2 whole-head route (IDS-12B)

`flux2-head` is a distinct route; it never aliases the existing `flux` (FLUX.1
Kontext) mode. It is disabled by default and startup fails closed unless the mode is exactly `scaffold` (the default),
`ATLAS_FLUX2_HEAD_ENABLED=true` and `ATLAS_FLUX2_HEAD_CONFIG` names an admission
JSON file. The admission file is operator-controlled—not accepted on `/v1/edits`—and
must contain `schema_version: 1`, a fixed `comfy_url`, and independent true evidence
gates named `licences_verified`, `adapter_provenance_verified`,
`operator_safeguards_verified`, and `ids12a_acceptance_passed`. Each of `model`,
`encoder`, `runtime`, `adapter`, and `workflow` must have an absolute local `path`,
lowercase immutable `sha256`, and non-empty `original_host`, `licence`, and
`provenance` records. Every hash is checked at startup and again before each job.
A file name, placeholder, mirror, or BFS adapter alone is not evidence.

The admission check requires the predecessor's target, mask, reference, instruction,
seed and adapter placeholder contract. This is not typed graph validation.
Every attempted edit requires a bounded crop, seed, full-size mask and at least
one reference. The scaffold does not execute the workflow or publish output or
lineage; `full_head` is never advertised. The typed graph, actual crop binding
and output validation are deferred to #1012 and a separately reviewed activation.

Example shape only (the values below are deliberately not deployable evidence):

~~~json
{
  "schema_version": 1,
  "comfy_url": "http://127.0.0.1:8188",
  "artifacts": {
    "model": {"path": "/absolute/model", "sha256": "<64 lowercase hex>", "original_host": "<verified>", "licence": "<verified>", "provenance": "<verified>"},
    "encoder": {"path": "/absolute/encoder", "sha256": "<64 lowercase hex>", "original_host": "<verified>", "licence": "<verified>", "provenance": "<verified>"},
    "runtime": {"path": "/absolute/runtime", "sha256": "<64 lowercase hex>", "original_host": "<verified>", "licence": "<verified>", "provenance": "<verified>"},
    "adapter": {"path": "/absolute/adapter", "sha256": "<64 lowercase hex>", "original_host": "<verified>", "licence": "<verified>", "provenance": "<verified>"},
    "workflow": {"path": "/absolute/workflow.json", "sha256": "<64 lowercase hex>", "original_host": "<verified>", "licence": "<verified>", "provenance": "<verified>"}
  },
  "evidence": {"licences_verified": false, "adapter_provenance_verified": false, "operator_safeguards_verified": false, "ids12a_acceptance_passed": false}
}
~~~

The checked-in state has no verified values and therefore remains **HEAD
unavailable / NOT MEASURED**. Do not change a gate to true without independently
reviewable evidence. Transport inherits the server's bearer authentication,
loopback-only HTTP/remote TLS rule, redirect ban, size/deadline bounds, and
cancellation-without-publication behavior.
