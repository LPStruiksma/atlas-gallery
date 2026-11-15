"""Standard-library HTTP/1.1 implementation of ATLAS Image Edit Protocol v1.

No third-party packages, network inference or real images are needed for fake mode.
"""
from __future__ import annotations

import argparse
import binascii
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from email import policy
from email.parser import BytesParser
import hmac
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import math
import os
import struct
import zlib
import threading
import time
from datetime import datetime, timezone
from urllib.parse import urlsplit
import uuid

from .backend import (BackendError, Cancelled, EditRequest, FakeBackend,
                      Refused, select_backend)

MAX_UPLOAD_BYTES = 20_000_000
MAX_JOBS = 32
MAX_JOB_AGE_SECONDS = 3600
TERMINAL = {"succeeded", "refused", "error", "cancelled"}


class Problem(Exception):
    def __init__(self, status, code, message):
        self.status = status
        self.code = code
        self.message = message


def now_iso():
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def image_info(data):
    """Check complete PNG/JPEG structure before accepting image bytes.

    Fake mode stays dependency-free; GPU/Comfy backends still decode pixels via
    Pillow before inference. Reject truncated and corrupt synthetic uploads.
    """
    if data.startswith(b"\x89PNG\r\n\x1a\n"):
        mime = "image/png"
        offset = 8
        width = height = 0
        idat = bytearray()
        saw_ihdr = saw_iend = False
        while offset + 12 <= len(data):
            length = struct.unpack(">I", data[offset:offset + 4])[0]
            kind = data[offset + 4:offset + 8]
            end = offset + 12 + length
            if end > len(data):
                break
            chunk = data[offset + 8:offset + 8 + length]
            expected = int.from_bytes(data[offset + 8 + length:end], "big")
            actual = binascii.crc32(kind + chunk) & 0xFFFFFFFF
            if expected != actual:
                break
            if not saw_ihdr:
                if kind != b"IHDR" or length != 13:
                    break
                width, height = struct.unpack(">II", chunk[:8])
                if not width or not height or chunk[10] != 0 or chunk[11] != 0:
                    break
                saw_ihdr = True
            elif kind == b"IDAT":
                idat.extend(chunk)
            elif kind == b"IEND":
                saw_iend = length == 0 and end == len(data)
                break
            offset = end
        if not (saw_ihdr and saw_iend and idat):
            raise Problem(415, "invalid_image", "Malformed PNG image")
        try:
            decoder = zlib.decompressobj()
            raw = decoder.decompress(bytes(idat), MAX_UPLOAD_BYTES + 1)
            raw += decoder.flush(MAX_UPLOAD_BYTES + 1 - len(raw))
            if not decoder.eof or decoder.unused_data or len(raw) > MAX_UPLOAD_BYTES:
                raise ValueError("Invalid or oversized PNG pixel stream")
        except (ValueError, zlib.error) as exc:
            raise Problem(415, "invalid_image", "Malformed PNG image") from exc
    elif data.startswith(b"\xff\xd8"):
        mime = "image/jpeg"
        offset = 2
        width = height = 0
        saw_scan = False
        saw_eoi = False
        while offset < len(data):
            if data[offset] != 0xFF:
                break
            while offset < len(data) and data[offset] == 0xFF:
                offset += 1
            if offset >= len(data):
                break
            marker = data[offset]
            offset += 1
            if marker == 0xD9:
                saw_eoi = offset == len(data)
                break
            if marker == 0xD8 or marker == 0x01 or 0xD0 <= marker <= 0xD7:
                continue
            if offset + 2 > len(data):
                break
            length = int.from_bytes(data[offset:offset + 2], "big")
            if length < 2 or offset + length > len(data):
                break
            if marker in (0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7,
                          0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF):
                if length < 8:
                    break
                height = int.from_bytes(data[offset + 3:offset + 5], "big")
                width = int.from_bytes(data[offset + 5:offset + 7], "big")
            if marker == 0xDA:
                # Entropy-coded bytes have escaped FF00 and restart markers.
                offset += length
                saw_scan = True
                while offset < len(data):
                    if data[offset] != 0xFF:
                        offset += 1
                        continue
                    marker_offset = offset
                    while offset < len(data) and data[offset] == 0xFF:
                        offset += 1
                    if offset >= len(data):
                        break
                    next_marker = data[offset]
                    if next_marker == 0 or 0xD0 <= next_marker <= 0xD7:
                        offset += 1
                        continue
                    offset = marker_offset
                    break
                continue
            offset += length
        if not (width and height and saw_scan and saw_eoi):
            raise Problem(415, "invalid_image", "Malformed JPEG image")
    else:
        raise Problem(415, "unsupported_image", "Use a PNG or JPEG image")
    if width <= 0 or height <= 0:
        raise Problem(415, "invalid_image", "Invalid image dimensions")
    return mime, width, height


def read_form(content_type, body, backend):
    if not content_type.lower().startswith("multipart/form-data;"):
        raise Problem(415, "content_type", "Expected multipart/form-data")
    message = BytesParser(policy=policy.default).parsebytes(
        b"Content-Type: " + content_type.encode("ascii", errors="replace") +
        b"\r\nMIME-Version: 1.0\r\n\r\n" + body
    )
    if not message.is_multipart():
        raise Problem(400, "multipart", "Malformed multipart request")
    fields = {}
    references = []
    image = mask = None
    part_count = 0
    for part in message.iter_parts():
        part_count += 1
        if part_count > 12:
            raise Problem(413, "part_count", "Too many multipart fields")
        if part.get_content_disposition() != "form-data":
            raise Problem(400, "multipart", "Part is not form-data")
        name = part.get_param("name", header="content-disposition")
        data = part.get_payload(decode=True)
        if not isinstance(data, bytes):
            raise Problem(400, "multipart", "Invalid part payload")
        if name in ("image", "mask", "reference"):
            declared = part.get_content_type()
            actual, width, height = image_info(data)
            if declared != actual:
                raise Problem(415, "content_type", "Image content type does not match bytes")
            if width * height > backend.max_input_pixels:
                raise Problem(422, "image_size", "Image exceeds backend input pixel limit")
            if name == "reference":
                references.append(data)
            elif name == "image":
                if image is not None:
                    raise Problem(400, "duplicate_field", "Duplicate image")
                image = data
            else:
                if mask is not None:
                    raise Problem(400, "duplicate_field", "Duplicate mask")
                mask = data
        elif name in ("instruction", "options"):
            if name in fields:
                raise Problem(400, "duplicate_field", "Duplicate field")
            try:
                fields[name] = data.decode("utf-8")
            except UnicodeDecodeError as exc:
                raise Problem(400, "utf8", "Field is not UTF-8") from exc
        else:
            raise Problem(422, "unknown_field", "Unsupported multipart field")
    if image is None:
        raise Problem(422, "missing_image", "An image part is required")
    instruction = fields.get("instruction", "").strip()
    if not instruction or len(instruction) > 8_000:
        raise Problem(422, "instruction", "Instruction must be 1..8000 characters")
    try:
        options = json.loads(fields.get("options", "{}"))
    except ValueError as exc:
        raise Problem(400, "options_json", "Options must be valid JSON") from exc
    if not isinstance(options, dict):
        raise Problem(422, "options_type", "Options must be a JSON object")
    capabilities = backend.capabilities()
    features = capabilities["features"]
    if mask is not None and not features["mask_edit"]:
        raise Problem(422, "unsupported_mask", "Backend does not support masks")
    if len(references) > features["reference_images"]["max_count"]:
        raise Problem(422, "unsupported_references", "Too many reference images")
    spec_by_name = {item["name"]: item for item in backend.parameters()}
    for key, value in options.items():
        if key not in spec_by_name:
            raise Problem(422, "unsupported_option", "Unsupported option: " + key)
        spec = spec_by_name[key]
        kind = spec["type"]
        if kind == "integer":
            valid = type(value) is int
        elif kind == "number":
            valid = type(value) in (int, float) and math.isfinite(value)
        elif kind == "enum":
            valid = type(value) is str and value in spec["enum"]
        else:
            valid = False
        if not valid or (kind in ("integer", "number") and (
            value < spec.get("minimum", float("-inf")) or
            value > spec.get("maximum", float("inf"))
        )):
            raise Problem(422, "option_value", "Invalid option: " + key)
    # No prompt text or source images are ever logged or returned in job JSON.
    mime, _, _ = image_info(image)
    return EditRequest(image=image, mime=mime, instruction=instruction,
                       options=options, mask=mask, references=tuple(references))


@dataclass
class Job:
    id: str
    created_at: str
    updated_at: str
    status: str = "queued"
    progress: int = 0
    cancel: threading.Event = field(default_factory=threading.Event)
    result: bytes | None = None
    refusal: dict | None = None
    error: dict | None = None
    completed_at: float | None = None


class JobStore:
    def __init__(self, backend):
        self.backend = backend
        self.lock = threading.Lock()
        # One GPU inference at a time; queued work stays cancellable.
        self.executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="atlas-edit")
        self.jobs = {}

    def _prune(self):
        expired = [key for key, job in self.jobs.items()
                   if job.completed_at is not None and
                   time.monotonic() - job.completed_at > MAX_JOB_AGE_SECONDS]
        for key in expired:
            del self.jobs[key]

    @staticmethod
    def snapshot(job):
        obj = {
            "id": job.id,
            "status": job.status,
            "progress": job.progress,
            "created_at": job.created_at,
            "updated_at": job.updated_at,
            "status_url": "/v1/edits/" + job.id,
            "result_urls": (["/v1/edits/" + job.id + "/result"]
                            if job.status == "succeeded" else []),
        }
        if job.refusal:
            obj["refusal"] = job.refusal.copy()
        if job.error:
            obj["error"] = job.error.copy()
        return obj

    def submit(self, request):
        with self.lock:
            self._prune()
            if len(self.jobs) >= MAX_JOBS:
                raise Problem(503, "queue_full", "Queue is full; retry later")
            key = uuid.uuid4().hex
            stamp = now_iso()
            job = Job(id=key, created_at=stamp, updated_at=stamp)
            self.jobs[key] = job
            self.executor.submit(self._run, job, request)
            return self.snapshot(job)

    def _run(self, job, request):
        with self.lock:
            if job.cancel.is_set():
                return
            job.status = "running"
            job.progress = 10
            job.updated_at = now_iso()
        try:
            image = self.backend.generate(request, job.cancel)
            if not isinstance(image, bytes):
                raise BackendError("Backend did not return bytes")
            mime, width, height = image_info(image)
            if width * height > self.backend.max_output_pixels:
                raise BackendError("Backend result exceeds output pixel limit")
            if mime != "image/png":
                # A PNG result is the v1 invariant. The fake backend may echo a
                # JPEG input and must not pretend that it converted it.
                if not isinstance(self.backend, FakeBackend):
                    raise BackendError("Backend must produce PNG output")
            with self.lock:
                if not job.cancel.is_set():
                    job.result = image
                    job.status = "succeeded"
                    job.progress = 100
        except Cancelled:
            with self.lock:
                job.cancel.set()
                job.status = "cancelled"
        except Refused as exc:
            with self.lock:
                if not job.cancel.is_set():
                    job.status = "refused"
                    job.refusal = {"code": exc.code, "message": exc.message}
        except Exception:
            with self.lock:
                if not job.cancel.is_set():
                    job.status = "error"
                    # Never leak provider stack traces, source paths, tokens or prompts.
                    job.error = {"kind": "server", "code": "BACKEND_ERROR", "retryable": True}
        finally:
            with self.lock:
                if job.cancel.is_set():
                    job.status = "cancelled"
                    job.result = None
                    job.refusal = None
                    job.error = None
                job.updated_at = now_iso()
                if job.status in TERMINAL:
                    job.completed_at = time.monotonic()

    def get(self, key):
        with self.lock:
            self._prune()
            job = self.jobs.get(key)
            if job is None:
                raise Problem(404, "not_found", "Unknown or expired edit job")
            return self.snapshot(job)

    def result(self, key):
        with self.lock:
            self._prune()
            job = self.jobs.get(key)
            if job is None or job.status != "succeeded" or job.result is None:
                raise Problem(404, "result_missing", "Edit result is not available")
            mime, _, _ = image_info(job.result)
            return mime, job.result

    def cancel(self, key):
        with self.lock:
            self._prune()
            job = self.jobs.get(key)
            if job is None:
                raise Problem(404, "not_found", "Unknown or expired edit job")
            if job.status not in TERMINAL:
                job.cancel.set()
                job.status = "cancelled"
                job.progress = 0
                job.updated_at = now_iso()
                job.completed_at = time.monotonic()
                job.result = None
            return self.snapshot(job)

    def shutdown(self):
        self.executor.shutdown(wait=True, cancel_futures=True)


class Handler(BaseHTTPRequestHandler):
    server_version = "AtlasEdit/1.0"

    def log_message(self, format, *args):
        # Disable default request logging; URLs, headers and prompts stay private.
        pass

    def _json(self, code, body):
        data = json.dumps(body, separators=(",", ":")).encode("utf-8")
        self._send(code, "application/json", data)

    def _send(self, status, mime, data):
        self.send_response(status)
        self.send_header("Content-Type", mime)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(data)

    def _authorized(self):
        bearer = self.headers.get("Authorization", "")
        token = (bearer[7:] if bearer.startswith("Bearer ")
                 else self.headers.get("X-Atlas-Token", ""))
        try:
            return bool(token) and hmac.compare_digest(token, self.server.token)
        except (TypeError, ValueError):
            return False

    def _handle(self):
        try:
            if not self._authorized():
                raise Problem(401, "unauthorized", "A valid token is required")
            path = urlsplit(self.path)
            if path.query or path.fragment:
                raise Problem(400, "url", "Unexpected query string")
            if self.command == "GET" and path.path == "/v1/capabilities":
                return self._json(200, self.server.jobs.backend.capabilities())
            if self.command == "POST" and path.path == "/v1/edits":
                raw_length = self.headers.get("Content-Length")
                if raw_length is None or not raw_length.isascii() or not raw_length.isdigit():
                    raise Problem(411, "length", "Content-Length is required")
                size = int(raw_length)
                if size > MAX_UPLOAD_BYTES:
                    raise Problem(413, "too_large", "Upload is too large")
                request = read_form(self.headers.get("Content-Type", ""),
                                    self.rfile.read(size), self.server.jobs.backend)
                return self._json(202, self.server.jobs.submit(request))
            parts = path.path.strip("/").split("/")
            if len(parts) not in (3, 4) or parts[:2] != ["v1", "edits"] or not parts[2]:
                raise Problem(404, "not_found", "Unknown endpoint")
            job_id = parts[2]
            if len(job_id) > 64:
                raise Problem(404, "not_found", "Unknown endpoint")
            if self.command == "GET" and len(parts) == 3:
                return self._json(200, self.server.jobs.get(job_id))
            if self.command == "DELETE" and len(parts) == 3:
                return self._json(200, self.server.jobs.cancel(job_id))
            if (self.command == "GET" and len(parts) == 4 and parts[3] == "result"):
                mime, data = self.server.jobs.result(job_id)
                return self._send(200, mime, data)
            raise Problem(404, "not_found", "Unknown endpoint")
        except Problem as exc:
            return self._json(exc.status, {"error": {"code": exc.code, "message": exc.message}})

    do_GET = _handle
    do_POST = _handle
    do_DELETE = _handle


def create_server(host, port, token, backend=None):
    if not token:
        raise ValueError("ATLAS_TOKEN must be set; unauthenticated mode is forbidden")
    if len(token) < 12:
        raise ValueError("ATLAS_TOKEN must have at least 12 characters")
    server = ThreadingHTTPServer((host, port), Handler)
    server.token = token
    server.jobs = JobStore(backend or FakeBackend())
    return server


def main():
    parser = argparse.ArgumentParser(description="ATLAS self-hosted edit protocol v1")
    parser.add_argument("--backend", choices=["fake", "qwen", "flux", "comfy"],
                        default=os.environ.get("ATLAS_BACKEND", "fake"))
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8080)
    args = parser.parse_args()
    server = create_server(args.host, args.port, os.environ.get("ATLAS_TOKEN"),
                           backend=select_backend(args.backend))
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.shutdown()
        server.server_close()
        server.jobs.shutdown()


if __name__ == "__main__":
    main()
