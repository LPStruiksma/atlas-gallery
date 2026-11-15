"""Pluggable ATLAS edit engines. Heavy GPU imports happen only inside GPU execution."""
from __future__ import annotations

from dataclasses import dataclass, field
import io
import json
import os
import threading
import time
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlencode, urlparse
from urllib.request import HTTPRedirectHandler, Request, build_opener
import uuid


class BackendError(Exception):
    """A processing failure, not a refusal."""


class Refused(Exception):
    """An explicit provider/workflow refusal, not a transport or engine error."""
    def __init__(self, code="PROVIDER_REFUSED", message="The backend refused the edit"):
        super().__init__(message)
        self.code = code
        self.message = message


class Cancelled(Exception):
    pass


@dataclass(frozen=True)
class EditRequest:
    image: bytes
    mime: str
    instruction: str
    options: dict = field(default_factory=dict)
    mask: bytes | None = None
    references: tuple[bytes, ...] = ()


def parameter(name, kind, *, minimum=None, maximum=None, choices=None, default=None):
    obj = {"name": name, "type": kind}
    if minimum is not None:
        obj["minimum"] = minimum
    if maximum is not None:
        obj["maximum"] = maximum
    if choices is not None:
        obj["enum"] = choices
    if default is not None:
        obj["default"] = default
    return obj


class Backend:
    provider_id = "unknown"
    display_name = "Unconfigured image editor"
    model_id = "unknown"
    supports_mask = False
    max_references = 0
    supports_seed = False
    max_input_pixels = 16_777_216
    max_output_pixels = 16_777_216

    def parameters(self):
        return []

    def capabilities(self):
        return {
            "schema_version": 1,
            "protocol_version": "1.0",
            "provider_id": self.provider_id,
            "display_name": self.display_name,
            "models": [{"id": self.model_id, "display_name": self.model_id}],
            "features": {
                "instruction_edit": True,
                "mask_edit": self.supports_mask,
                "reference_images": {"max_count": self.max_references},
                "candidates": {"max_count": 1},
                "multi_turn": False,
                "seed": self.supports_seed,
                "async_jobs": True,
                "streaming": False,
                "input_formats": ["image/png", "image/jpeg"],
                "output_formats": ["image/png"],
                "max_input_pixels": self.max_input_pixels,
                "max_output_pixels": self.max_output_pixels,
                "aspect_constraints": None,
                "cost_estimate": None,
            },
            "advanced_parameters": self.parameters(),
        }

    def generate(self, request: EditRequest, cancelled: threading.Event) -> bytes:
        raise NotImplementedError


class FakeBackend(Backend):
    """Echoes synthetic image bytes. Not an image editor or a fidelity benchmark."""
    provider_id = "atlas-fake"
    display_name = "ATLAS CPU-only fake"
    model_id = "fake-echo"

    def __init__(self, delay_seconds=0):
        self.delay_seconds = delay_seconds

    def parameters(self):
        return [parameter("fake_outcome", "enum",
                          choices=["success", "refused", "error"], default="success")]

    def generate(self, request, cancelled):
        until = time.monotonic() + self.delay_seconds
        while time.monotonic() < until:
            if cancelled.wait(min(0.01, max(0, until - time.monotonic()))):
                raise Cancelled()
        if cancelled.is_set():
            raise Cancelled()
        outcome = request.options.get("fake_outcome", "success")
        if outcome == "refused":
            raise Refused("FAKE_REFUSAL", "Synthetic provider refusal (test only)")
        if outcome == "error":
            raise BackendError("synthetic test error")
        return request.image


class DiffusersBackend(Backend):
    """Direct single-image Qwen-Image-Edit or FLUX.1 Kontext [dev] inference.

    Model weights are never fetched in fake mode. Operator accepts model license.
    """
    supports_seed = True
    max_input_pixels = 4_194_304
    max_output_pixels = 4_194_304

    def __init__(self, model):
        if model not in ("qwen", "flux"):
            raise ValueError("model must be qwen or flux")
        self.model = model
        self.model_id = ("Qwen/Qwen-Image-Edit" if model == "qwen"
                         else "black-forest-labs/FLUX.1-Kontext-dev")
        self.provider_id = "diffusers-" + model
        self.display_name = "Diffusers " + model.upper()
        self._pipeline = None
        self._load_lock = threading.Lock()

    def parameters(self):
        return [
            parameter("steps", "integer", minimum=1, maximum=60, default=28),
            parameter("guidance_scale", "number", minimum=0, maximum=15, default=2.5),
            parameter("seed", "integer", minimum=0, maximum=4_294_967_295),
        ]

    def _load(self):
        with self._load_lock:
            if self._pipeline is None:
                import torch
                from diffusers import DiffusionPipeline
                if not torch.cuda.is_available():
                    raise BackendError("A CUDA GPU is required for this backend")
                self._pipeline = DiffusionPipeline.from_pretrained(
                    self.model_id, torch_dtype=torch.bfloat16
                ).to("cuda")
            return self._pipeline

    def generate(self, request, cancelled):
        if cancelled.is_set():
            raise Cancelled()
        from PIL import Image, ImageOps
        import torch
        pipeline = self._load()
        with Image.open(io.BytesIO(request.image)) as source:
            # Never send EXIF/GPS metadata to a model. The edited output is fresh.
            image = ImageOps.exif_transpose(source).convert("RGB")
        args = {
            "image": image,
            "prompt": request.instruction,
            "num_inference_steps": request.options.get("steps", 28),
            "guidance_scale": request.options.get("guidance_scale", 2.5),
        }
        if "seed" in request.options:
            args["generator"] = torch.Generator(device="cuda").manual_seed(
                request.options["seed"]
            )
        if cancelled.is_set():
            raise Cancelled()
        result = pipeline(**args).images[0]
        if cancelled.is_set():
            raise Cancelled()
        output = io.BytesIO()
        result.convert("RGB").save(output, format="PNG")
        return output.getvalue()


class _NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise BackendError("ComfyUI redirected a request; redirects are forbidden")


class ComfyBackend(Backend):
    """Proxy a configured, API-exported ComfyUI workflow; no arbitrary client URLs."""
    provider_id = "comfyui"
    display_name = "ComfyUI workflow"
    max_input_pixels = 16_777_216
    max_output_pixels = 16_777_216

    def __init__(self, url, workflow_path, model_id="configured-workflow", token=None,
                 timeout_seconds=300):
        parsed = urlparse(url)
        if (parsed.scheme not in ("http", "https") or not parsed.hostname or
            parsed.username or parsed.password or parsed.query or parsed.fragment or
            (parsed.scheme == "http" and parsed.hostname not in
             ("127.0.0.1", "localhost", "::1"))):
            raise ValueError("ComfyUI must use HTTPS, or local HTTP on loopback")
        if not workflow_path:
            raise ValueError("ATLAS_COMFY_WORKFLOW is required")
        with open(workflow_path, encoding="utf-8") as handle:
            self.workflow = json.load(handle)
        if not isinstance(self.workflow, dict):
            raise ValueError("ComfyUI API workflow must be a JSON object")
        self.template = json.dumps(self.workflow)
        if "ATLAS_IMAGE" not in self.template or "ATLAS_INSTRUCTION" not in self.template:
            raise ValueError("Workflow must use ATLAS_IMAGE and ATLAS_INSTRUCTION placeholders")
        self.base = url.rstrip("/")
        self.model_id = model_id
        self.token = token
        self.timeout_seconds = timeout_seconds
        self.opener = build_opener(_NoRedirect())
        self.supports_mask = "ATLAS_MASK" in self.template
        self.max_references = sum("ATLAS_REFERENCE_" + str(n) in self.template
                                  for n in range(4))
        self.supports_seed = "ATLAS_SEED" in self.template

    def parameters(self):
        params = []
        for key, kind, minimum, maximum in [
            ("seed", "integer", 0, 4_294_967_295),
            ("steps", "integer", 1, 60),
            ("guidance_scale", "number", 0, 15),
        ]:
            if "ATLAS_" + key.upper() in self.template:
                params.append(parameter(key, kind, minimum=minimum, maximum=maximum))
        return params

    def _fetch(self, method, path, body=None, content_type=None):
        url = self.base + path
        headers = {"Accept": "application/json"}
        if self.token:
            headers["Authorization"] = "Bearer " + self.token
        if content_type:
            headers["Content-Type"] = content_type
        req = Request(url, data=body, headers=headers, method=method)
        try:
            with self.opener.open(req, timeout=30) as response:
                return response.read(30_000_001)
        except (HTTPError, URLError, TimeoutError) as exc:
            raise BackendError("ComfyUI connection or workflow failure") from exc

    def _json(self, method, path, obj=None):
        raw = self._fetch(method, path,
                          json.dumps(obj).encode() if obj is not None else None,
                          "application/json" if obj is not None else None)
        try:
            return json.loads(raw)
        except (ValueError, UnicodeError) as exc:
            raise BackendError("ComfyUI returned invalid JSON") from exc

    def _upload(self, data, filename):
        boundary = "atlas-" + uuid.uuid4().hex
        body = (
            ("--" + boundary + '\r\nContent-Disposition: form-data; name="image"; filename="' +
             filename + '"\r\nContent-Type: image/png\r\n\r\n').encode()
            + data + ("\r\n--" + boundary + "--\r\n").encode()
        )
        reply = self._fetch("POST", "/upload/image", body,
                            "multipart/form-data; boundary=" + boundary)
        try:
            name = json.loads(reply)["name"]
        except (ValueError, KeyError, TypeError) as exc:
            raise BackendError("ComfyUI upload did not return a filename") from exc
        if not isinstance(name, str) or not name:
            raise BackendError("ComfyUI supplied an invalid filename")
        return name

    @staticmethod
    def _replace(node, values):
        if isinstance(node, str):
            return values.get(node, node)
        if isinstance(node, list):
            return [ComfyBackend._replace(item, values) for item in node]
        if isinstance(node, dict):
            return {key: ComfyBackend._replace(value, values)
                    for key, value in node.items()}
        return node

    def generate(self, request, cancelled):
        from PIL import Image, ImageOps  # only needed for Comfy or CUDA, not fake/CI

        def normalize(data):
            with Image.open(io.BytesIO(data)) as source:
                output = io.BytesIO()
                ImageOps.exif_transpose(source).convert("RGB").save(output, format="PNG")
                return output.getvalue()

        if cancelled.is_set():
            raise Cancelled()
        values = {
            "ATLAS_INSTRUCTION": request.instruction,
            "ATLAS_IMAGE": self._upload(normalize(request.image),
                                        uuid.uuid4().hex + ".png"),
        }
        if request.mask is not None:
            values["ATLAS_MASK"] = self._upload(normalize(request.mask),
                                                uuid.uuid4().hex + ".png")
        for index, reference in enumerate(request.references):
            values["ATLAS_REFERENCE_" + str(index)] = self._upload(
                normalize(reference), uuid.uuid4().hex + ".png")
        for option in ("seed", "steps", "guidance_scale"):
            if option in request.options:
                values["ATLAS_" + option.upper()] = request.options[option]
        if cancelled.is_set():
            raise Cancelled()
        workflow = self._replace(self.workflow, values)
        submitted = self._json("POST", "/prompt",
                               {"prompt": workflow, "client_id": uuid.uuid4().hex})
        prompt_id = submitted.get("prompt_id")
        if not isinstance(prompt_id, str) or not prompt_id:
            raise BackendError("ComfyUI did not enqueue a prompt")
        deadline = time.monotonic() + self.timeout_seconds
        while time.monotonic() < deadline:
            if cancelled.is_set():
                # A queued prompt may be removed; never interrupt another user's
                # currently running workflow with ComfyUI's global /interrupt.
                try:
                    self._json("POST", "/queue", {"delete": [prompt_id]})
                except BackendError:
                    pass
                raise Cancelled()
            history = self._json("GET", "/history/" + quote(prompt_id, safe=""))
            item = history.get(prompt_id, {}) if isinstance(history, dict) else {}
            if item:
                if item.get("status", {}).get("status_str") == "error":
                    raise BackendError("ComfyUI workflow execution failed")
                if item.get("atlas_status") == "refused":
                    raise Refused(str(item.get("atlas_code", "WORKFLOW_REFUSED")),
                                  "The ComfyUI workflow explicitly refused the edit")
                for output in item.get("outputs", {}).values():
                    for picture in output.get("images", []):
                        name = picture.get("filename")
                        if not isinstance(name, str) or not name:
                            continue
                        query = urlencode({
                            "filename": name,
                            "subfolder": picture.get("subfolder", ""),
                            "type": picture.get("type", "output"),
                        })
                        data = self._fetch("GET", "/view?" + query)
                        if cancelled.is_set():
                            raise Cancelled()
                        return normalize(data)
            cancelled.wait(0.5)
        raise BackendError("ComfyUI job exceeded the configured timeout")


def select_backend(name=None):
    backend = name or os.environ.get("ATLAS_BACKEND", "fake")
    if backend == "fake":
        return FakeBackend()
    if backend in ("qwen", "flux"):
        return DiffusersBackend(backend)
    if backend == "comfy":
        return ComfyBackend(
            os.environ.get("ATLAS_COMFY_URL", "http://127.0.0.1:8188"),
            os.environ.get("ATLAS_COMFY_WORKFLOW", ""),
            model_id=os.environ.get("ATLAS_COMFY_MODEL_ID", "configured-workflow"),
            token=os.environ.get("ATLAS_COMFY_TOKEN"),
        )
    raise ValueError("Unsupported backend: use fake, qwen, flux or comfy")
