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

    def capabilities(self):
        caps = super().capabilities()
        # Fake passes through JPEG inputs without converting them to PNG.
        caps["features"]["output_formats"] = ["image/png", "image/jpeg"]
        return caps

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
        # Qwen-Image-Edit uses true classifier-free guidance. FLUX Kontext
        # uses its embedded guidance_scale; they must not be conflated.
        guidance = (parameter("true_cfg_scale", "number", minimum=0, maximum=15,
                              default=4.0) if self.model == "qwen" else
                    parameter("guidance_scale", "number", minimum=0, maximum=15,
                              default=2.5))
        return [
            parameter("steps", "integer", minimum=1, maximum=60,
                      default=40 if self.model == "qwen" else 28),
            guidance,
            parameter("seed", "integer", minimum=0, maximum=4_294_967_295),
        ]

    def _load(self):
        with self._load_lock:
            if self._pipeline is None:
                import torch
                from diffusers import FluxKontextPipeline, QwenImageEditPipeline
                if not torch.cuda.is_available():
                    raise BackendError("A CUDA GPU is required for this backend")
                pipeline_class = (QwenImageEditPipeline if self.model == "qwen"
                                  else FluxKontextPipeline)
                self._pipeline = pipeline_class.from_pretrained(
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
            "num_inference_steps": request.options.get(
                "steps", 40 if self.model == "qwen" else 28),
        }
        if self.model == "qwen":
            args["true_cfg_scale"] = request.options.get("true_cfg_scale", 4.0)
            args["negative_prompt"] = " "
        else:
            args["guidance_scale"] = request.options.get("guidance_scale", 2.5)
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
        if timeout_seconds <= 0:
            raise ValueError("ComfyUI timeout must be positive")
        self.timeout_seconds = timeout_seconds
        self.opener = build_opener(_NoRedirect())
        self.supports_mask = "ATLAS_MASK" in self.template
        refs = ["ATLAS_REFERENCE_" + str(n) in self.template for n in range(4)]
        if refs != sorted(refs, reverse=True):
            raise ValueError("Comfy reference placeholders must be contiguous from zero")
        self.max_references = sum(refs)
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

    def _fetch(self, method, path, body=None, content_type=None, *,
               deadline=None, cancelled=None):
        # Deadline is job-scoped rather than shared mutable state; each upload,
        # prompt, history poll and result fetch consumes the same budget.
        if cancelled is not None and cancelled.is_set():
            raise Cancelled()
        remaining = ((deadline - time.monotonic()) if deadline is not None
                     else self.timeout_seconds)
        if remaining <= 0:
            raise BackendError("ComfyUI job exceeded the configured timeout")
        url = self.base + path
        headers = {"Accept": "application/json"}
        if self.token:
            headers["Authorization"] = "Bearer " + self.token
        if content_type:
            headers["Content-Type"] = content_type
        req = Request(url, data=body, headers=headers, method=method)
        try:
            with self.opener.open(req, timeout=min(30, remaining)) as response:
                pieces = []
                total = 0
                while True:
                    if cancelled is not None and cancelled.is_set():
                        raise Cancelled()
                    if deadline is not None and time.monotonic() >= deadline:
                        raise BackendError("ComfyUI job exceeded the configured timeout")
                    # Bound memory even when Content-Length is absent/incorrect.
                    chunk = response.read(min(65_536, 30_000_001 - total))
                    if not chunk:
                        break
                    total += len(chunk)
                    if total > 30_000_000:
                        raise BackendError("ComfyUI response exceeds size limit")
                    pieces.append(chunk)
                if cancelled is not None and cancelled.is_set():
                    raise Cancelled()
                if deadline is not None and time.monotonic() >= deadline:
                    raise BackendError("ComfyUI job exceeded the configured timeout")
                return b"".join(pieces)
        except (HTTPError, URLError, TimeoutError, OSError) as exc:
            if cancelled is not None and cancelled.is_set():
                raise Cancelled() from exc
            raise BackendError("ComfyUI connection or workflow failure") from exc

    def _json(self, method, path, obj=None, *, deadline=None, cancelled=None):
        raw = self._fetch(method, path,
                          json.dumps(obj).encode() if obj is not None else None,
                          "application/json" if obj is not None else None,
                          deadline=deadline, cancelled=cancelled)
        try:
            return json.loads(raw)
        except (ValueError, UnicodeError) as exc:
            raise BackendError("ComfyUI returned invalid JSON") from exc

    def _upload(self, data, filename, *, deadline=None, cancelled=None):
        boundary = "atlas-" + uuid.uuid4().hex
        body = (
            ("--" + boundary + '\r\nContent-Disposition: form-data; name="image"; filename="' +
             filename + '"\r\nContent-Type: image/png\r\n\r\n').encode()
            + data + ("\r\n--" + boundary + "--\r\n").encode()
        )
        reply = self._fetch("POST", "/upload/image", body,
                            "multipart/form-data; boundary=" + boundary,
                            deadline=deadline, cancelled=cancelled)
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

    def _delete_prompt(self, prompt_id):
        # Only delete our queued prompt; never call Comfy's global /interrupt.
        # Use a short, fresh cleanup allowance after cancellation or timeout.
        try:
            self._json("POST", "/queue", {"delete": [prompt_id]},
                       deadline=time.monotonic() + 1.0)
        except BackendError:
            pass

    def generate(self, request, cancelled):
        from PIL import Image, ImageOps  # only needed for Comfy or CUDA, not fake/CI

        def check():
            if cancelled.is_set():
                raise Cancelled()
            if time.monotonic() >= deadline:
                raise BackendError("ComfyUI job exceeded the configured timeout")

        def normalize(data):
            check()
            with Image.open(io.BytesIO(data)) as source:
                output = io.BytesIO()
                ImageOps.exif_transpose(source).convert("RGB").save(output, format="PNG")
                check()
                return output.getvalue()

        deadline = time.monotonic() + self.timeout_seconds
        check()
        values = {
            "ATLAS_INSTRUCTION": request.instruction,
            "ATLAS_IMAGE": self._upload(normalize(request.image),
                                        uuid.uuid4().hex + ".png",
                                        deadline=deadline, cancelled=cancelled),
        }
        if request.mask is not None:
            values["ATLAS_MASK"] = self._upload(normalize(request.mask),
                                                uuid.uuid4().hex + ".png",
                                                deadline=deadline, cancelled=cancelled)
        for index, reference in enumerate(request.references):
            values["ATLAS_REFERENCE_" + str(index)] = self._upload(
                normalize(reference), uuid.uuid4().hex + ".png",
                deadline=deadline, cancelled=cancelled)
        for option in ("seed", "steps", "guidance_scale"):
            if option in request.options:
                values["ATLAS_" + option.upper()] = request.options[option]
        check()
        workflow = self._replace(self.workflow, values)
        submitted = self._json("POST", "/prompt",
                               {"prompt": workflow, "client_id": uuid.uuid4().hex},
                               deadline=deadline, cancelled=cancelled)
        prompt_id = submitted.get("prompt_id") if isinstance(submitted, dict) else None
        if not isinstance(prompt_id, str) or not prompt_id:
            raise BackendError("ComfyUI did not enqueue a prompt")
        try:
            while True:
                check()
                history = self._json("GET", "/history/" + quote(prompt_id, safe=""),
                                     deadline=deadline, cancelled=cancelled)
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
                            data = self._fetch("GET", "/view?" + query,
                                               deadline=deadline, cancelled=cancelled)
                            check()
                            return normalize(data)
                cancelled.wait(min(0.5, max(0, deadline - time.monotonic())))
        except (Cancelled, BackendError):
            self._delete_prompt(prompt_id)
            raise


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
