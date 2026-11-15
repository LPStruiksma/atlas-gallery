"""Reusable ATLAS v1 protocol conformance cases; synthetic byte fixtures only.

Also collected by pytest: python -m pytest tools/ai_edit_server/tests -q.
"""
import base64
import http.client
import json
import struct
import zlib
import binascii
import io
import os
import tempfile
import sys
from types import ModuleType, SimpleNamespace
from unittest import mock
import threading
import time
import unittest

from tools.ai_edit_server.server import FakeBackend, create_server
from tools.ai_edit_server.backend import (
    BackendError, Cancelled, ComfyBackend, DiffusersBackend, EditRequest,
)


def png_chunk(kind, payload):
    return (struct.pack(">I", len(payload)) + kind + payload +
            struct.pack(">I", binascii.crc32(kind + payload) & 0xFFFFFFFF))


def synthetic_png(pixel=b"\xff\x00\x00\xff", filter_byte=0):
    ihdr = struct.pack(">IIBBBBB", 1, 1, 8, 6, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n" + png_chunk(b"IHDR", ihdr) +
            png_chunk(b"IDAT", zlib.compress(bytes([filter_byte]) + pixel)) +
            png_chunk(b"IEND", b""))


PNG = synthetic_png()
# A synthetic one-pixel JPEG saved offline; no Pillow is imported in CI.
JPEG = base64.b64decode(
    "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/"
    "2wBDAQkJCQwLDBgNDRgyIRwhMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjL/wAARCAABAAEDASIAAhEBAxEB"
    "/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6"
    "/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6"
    "/9oADAMBAAIRAxEAPwDi6KKK+ZP3E//Z"
)

def multipart(fields=None, files=None):
    boundary = "atlas-synthetic-boundary"
    pieces = []
    for name, value in (fields or {}).items():
        pieces.append(
            ('--' + boundary + '\r\nContent-Disposition: form-data; name="' +
             name + '"\r\n\r\n' + str(value) + '\r\n').encode()
        )
    for name, mime, payload in (files or []):
        pieces.append(
            ('--' + boundary + '\r\nContent-Disposition: form-data; name="' +
             name + '"; filename="synthetic.png"\r\nContent-Type: ' +
             mime + '\r\n\r\n').encode() + payload + b'\r\n'
        )
    pieces.append(('--' + boundary + '--\r\n').encode())
    return b''.join(pieces), 'multipart/form-data; boundary=' + boundary


class ProtocolConformance(unittest.TestCase):
    """Reusable against another local backend by overriding make_server."""

    def make_server(self):
        return create_server("127.0.0.1", 0, token="synthetic-test-secret",
                             backend=FakeBackend(delay_seconds=0.01))

    def setUp(self):
        self.server = self.make_server()
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=3)
        self.server.jobs.shutdown()

    def call(self, method, path, body=None, content_type=None, auth=True):
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=4)
        headers = {}
        if auth:
            headers["Authorization"] = "Bearer synthetic-test-secret"
        if content_type:
            headers["Content-Type"] = content_type
        connection.request(method, path, body=body, headers=headers)
        response = connection.getresponse()
        result = (response.status, dict(response.getheaders()), response.read())
        connection.close()
        return result

    def submit(self, fields=None, files=None):
        body, mime = multipart(
            fields or {"instruction": "make the synthetic pixel warmer"},
            files if files is not None else [("image", "image/png", PNG)],
        )
        status, headers, raw = self.call("POST", "/v1/edits", body, mime)
        return status, json.loads(raw)

    def wait_for(self, job_id, target, timeout=3):
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            code, _, raw = self.call("GET", "/v1/edits/" + job_id)
            self.assertEqual(code, 200)
            payload = json.loads(raw)
            if payload["status"] in target:
                return payload
            time.sleep(0.02)
        self.fail("job did not reach " + str(target))

    def test_auth_is_required_on_every_route(self):
        for path in ("/v1/capabilities", "/v1/edits/not-a-job",
                     "/v1/edits/not-a-job/result"):
            code, _, _ = self.call("GET", path, auth=False)
            self.assertEqual(code, 401)
        code, _, _ = self.call("DELETE", "/v1/edits/not-a-job", auth=False)
        self.assertEqual(code, 401)
        code, _, _ = self.call("POST", "/v1/edits", b"", "multipart/form-data",
                               auth=False)
        self.assertEqual(code, 401)
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port)
        connection.request("GET", "/v1/capabilities", headers={"X-Atlas-Token": "synthetic-test-secret"})
        self.assertEqual(connection.getresponse().status, 200)
        connection.close()

    def test_capabilities_are_typed_and_honest(self):
        status, _, raw = self.call("GET", "/v1/capabilities")
        self.assertEqual(status, 200)
        caps = json.loads(raw)
        self.assertEqual(caps["schema_version"], 1)
        self.assertEqual(caps["protocol_version"], "1.0")
        self.assertTrue(caps["features"]["instruction_edit"])
        self.assertFalse(caps["features"]["mask_edit"])
        self.assertEqual(caps["features"]["reference_images"]["max_count"], 0)
        self.assertEqual(caps["features"]["candidates"]["max_count"], 1)
        self.assertIn("advanced_parameters", caps)
        self.assertEqual(len(caps["models"]), 1)

    def test_submit_poll_binary_result_and_access_control(self):
        status, response = self.submit()
        self.assertEqual(status, 202)
        self.assertNotIn("make the synthetic pixel warmer", json.dumps(response))
        self.assertTrue(response["id"])
        job = self.wait_for(response["id"], {"succeeded"})
        self.assertEqual(job["progress"], 100)
        self.assertEqual(job["status_url"], response["status_url"])
        self.assertEqual(job["result_urls"], ["/v1/edits/" + response["id"] + "/result"])
        code, headers, output = self.call("GET", job["result_urls"][0])
        self.assertEqual(code, 200)
        self.assertEqual(headers["Content-Type"], "image/png")
        self.assertEqual(output, PNG)  # fake backend never alters image bytes
        code, _, _ = self.call("GET", job["result_urls"][0], auth=False)
        self.assertEqual(code, 401)

    def test_fake_refused_is_distinct_from_failed(self):
        _, refused = self.submit({"instruction": "synthetic test", "options": '{"fake_outcome":"refused"}'})
        rejected = self.wait_for(refused["id"], {"refused"})
        self.assertEqual(rejected["refusal"]["code"], "FAKE_REFUSAL")
        self.assertNotIn("error", rejected)
        _, failed = self.submit({"instruction": "synthetic test", "options": '{"fake_outcome":"error"}'})
        errored = self.wait_for(failed["id"], {"error"})
        self.assertEqual(errored["error"]["kind"], "server")
        self.assertNotIn("refusal", errored)
        for item in (rejected, errored):
            self.assertEqual(item["result_urls"], [])

    def test_invalid_or_unsupported_input_does_not_submit(self):
        cases = [
            ({"instruction": " "}, [("image", "image/png", PNG)], 422),
            ({"instruction": "ok", "options": "{"}, [("image", "image/png", PNG)], 400),
            ({"instruction": "ok", "options": '{"steps":"many"}'}, [("image", "image/png", PNG)], 422),
            ({"instruction": "ok", "options": '{"unknown":1}'}, [("image", "image/png", PNG)], 422),
            ({"instruction": "ok"}, [("image", "image/gif", b"GIF89a")], 415),
            ({"instruction": "ok"}, [("image", "image/png", b"not an image")], 415),
            ({"instruction": "ok"}, [("image", "image/png", PNG),
                                     ("mask", "image/png", PNG)], 422),
            ({"instruction": "ok"}, [("image", "image/png", PNG),
                                     ("reference", "image/png", PNG)], 422),
        ]
        for fields, files, expected in cases:
            with self.subTest(fields=fields, files=files):
                body, mime = multipart(fields, files)
                status, _, _ = self.call("POST", "/v1/edits", body, mime)
                self.assertEqual(status, expected)

    def test_structurally_corrupt_images_are_not_accepted(self):
        # These retain valid magic bytes and dimensions, but are not images.
        cases = [
            PNG[:-7],  # missing IEND
            PNG[:29] + bytes([PNG[29] ^ 1]) + PNG[30:],  # corrupt IDAT
            synthetic_png(pixel=b"\x00"),  # too-short valid zlib raster
            synthetic_png(filter_byte=5),  # invalid filter with correct CRC
            JPEG[:-2],  # missing EOI
            b"\xff\xd8\xff\xc0\x00\x11\x08\x00\x01\x00\x01" + b"\x00" * 12,
        ]
        for payload in cases:
            with self.subTest(length=len(payload)):
                mime = "image/jpeg" if payload.startswith(b"\xff\xd8") else "image/png"
                status, response = self.submit(files=[("image", mime, payload)])
                self.assertEqual(status, 415)
                self.assertEqual(response["error"]["code"], "invalid_image")


    def test_valid_synthetic_png_and_jpeg_round_trip(self):
        for mime, picture in (("image/png", PNG), ("image/jpeg", JPEG)):
            with self.subTest(mime=mime):
                status, response = self.submit(files=[("image", mime, picture)])
                self.assertEqual(status, 202)
                job = self.wait_for(response["id"], {"succeeded"})
                code, headers, result = self.call("GET", job["result_urls"][0])
                self.assertEqual(code, 200)
                self.assertEqual(headers["Content-Type"], mime)
                self.assertEqual(result, picture)

    def test_content_size_and_unknown_job(self):
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port)
        connection.putrequest("POST", "/v1/edits")
        connection.putheader("Authorization", "Bearer synthetic-test-secret")
        connection.putheader("Content-Length", "21000000")
        connection.putheader("Content-Type", "multipart/form-data; boundary=x")
        connection.endheaders()
        self.assertEqual(connection.getresponse().status, 413)
        connection.close()
        code, _, _ = self.call("GET", "/v1/edits/does-not-exist")
        self.assertEqual(code, 404)

    def test_delete_cancels_without_publishing_result(self):
        self.server.jobs.backend.delay_seconds = 0.5
        status, response = self.submit()
        self.assertEqual(status, 202)
        code, _, raw = self.call("DELETE", "/v1/edits/" + response["id"])
        self.assertEqual(code, 200)
        self.assertEqual(json.loads(raw)["status"], "cancelled")
        job = self.wait_for(response["id"], {"cancelled"})
        self.assertEqual(job["result_urls"], [])
        code, _, _ = self.call("GET", "/v1/edits/" + response["id"] + "/result")
        self.assertEqual(code, 404)


class SyntheticImage:
    """No real Pillow, GPU or diffusion model in adapter conformance tests."""
    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False

    def convert(self, mode):
        return self

    def save(self, output, format):
        output.write(PNG)


def fake_pil():
    module = ModuleType("PIL")
    module.Image = SimpleNamespace(open=lambda stream: SyntheticImage())
    module.ImageOps = SimpleNamespace(exif_transpose=lambda image: image)
    return module


class AdapterConformance(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.workflow_path = os.path.join(self.tmp.name, "workflow.json")
        with open(self.workflow_path, "w", encoding="utf-8") as stream:
            json.dump({"1": {"inputs": {"image": "ATLAS_IMAGE",
                                        "text": "ATLAS_INSTRUCTION"}}}, stream)

    def tearDown(self):
        self.tmp.cleanup()

    def comfy(self, timeout=0.25):
        return ComfyBackend("http://127.0.0.1:8188", self.workflow_path,
                            timeout_seconds=timeout)

    def test_diffusers_model_specific_options_and_pipeline_calls(self):
        fake_torch = ModuleType("torch")
        fake_torch.Generator = lambda device: SimpleNamespace(
            manual_seed=lambda seed: ("seed", device, seed))
        request = EditRequest(image=PNG, mime="image/png", instruction="synthetic edit",
                              options={"steps": 12, "seed": 7})
        for model, option, value in (("qwen", "true_cfg_scale", 4.2),
                                     ("flux", "guidance_scale", 3.1)):
            with self.subTest(model=model):
                backend = DiffusersBackend(model)
                names = {item["name"] for item in backend.parameters()}
                self.assertIn(option, names)
                self.assertNotIn("guidance_scale" if model == "qwen"
                                 else "true_cfg_scale", names)
                seen = []
                pipeline = lambda **kwargs: (
                    seen.append(kwargs) or SimpleNamespace(images=[SyntheticImage()]))
                with mock.patch.dict(sys.modules, {"PIL": fake_pil(), "torch": fake_torch}):
                    with mock.patch.object(backend, "_load", return_value=pipeline):
                        actual = backend.generate(EditRequest(
                            image=request.image, mime=request.mime,
                            instruction=request.instruction,
                            options={**request.options, option: value}),
                            threading.Event())
                self.assertEqual(actual, PNG)
                self.assertEqual(len(seen), 1)
                self.assertEqual(seen[0][option], value)
                self.assertEqual(seen[0]["num_inference_steps"], 12)
                self.assertEqual(seen[0]["generator"], ("seed", "cuda", 7))
                self.assertEqual(seen[0].get("negative_prompt"),
                                 " " if model == "qwen" else None)

    def test_diffusers_loads_distinct_model_classes_without_weights(self):
        selected = []
        def create_pipeline(label):
            class StubPipeline:
                @classmethod
                def from_pretrained(cls, model_id, torch_dtype):
                    selected.append((label, model_id, torch_dtype))
                    return cls()

                def to(self, device):
                    self.assert_device = device
                    return self
            return StubPipeline

        stub_diffusers = ModuleType("diffusers")
        stub_diffusers.QwenImageEditPipeline = create_pipeline("qwen")
        stub_diffusers.FluxKontextPipeline = create_pipeline("flux")
        stub_torch = ModuleType("torch")
        stub_torch.bfloat16 = "bf16"
        stub_torch.cuda = SimpleNamespace(is_available=lambda: True)
        with mock.patch.dict(sys.modules, {"torch": stub_torch,
                                           "diffusers": stub_diffusers}):
            for name in ("qwen", "flux"):
                pipeline = DiffusersBackend(name)._load()
                self.assertEqual(pipeline.assert_device, "cuda")
        self.assertEqual([item[0] for item in selected], ["qwen", "flux"])
        self.assertIn("Qwen-Image-Edit", selected[0][1])
        self.assertIn("FLUX.1-Kontext-dev", selected[1][1])

    def test_comfy_each_request_uses_remaining_deadline_and_cancel(self):
        backend = self.comfy()
        seen = []

        class Response:
            def __enter__(self):
                return self

            def __exit__(self, *args):
                return False

            def read(self, size):
                if not hasattr(self, "read_once"):
                    self.read_once = True
                    return b"{}"
                return b""

        def opener(request, timeout):
            seen.append(timeout)
            return Response()

        backend.opener = SimpleNamespace(open=opener)
        deadline = time.monotonic() + 0.1
        self.assertEqual(backend._fetch("GET", "/history/a", deadline=deadline),
                         b"{}")
        self.assertEqual(backend._fetch("GET", "/history/b", deadline=deadline),
                         b"{}")
        self.assertEqual(len(seen), 2)
        self.assertTrue(all(0 < item <= 0.1 for item in seen))
        self.assertLessEqual(seen[1], seen[0])
        with self.assertRaises(BackendError):
            backend._fetch("GET", "/history/late",
                           deadline=time.monotonic() - 1)
        self.assertEqual(len(seen), 2)  # no network after deadline
        cancelled = threading.Event()
        cancelled.set()
        with self.assertRaises(Cancelled):
            backend._fetch("GET", "/history/cancel", deadline=deadline,
                           cancelled=cancelled)
        self.assertEqual(len(seen), 2)

    def test_comfy_cancel_after_submit_deletes_only_own_prompt(self):
        backend = self.comfy()
        cancelled = threading.Event()
        calls = []

        def json_call(method, path, obj=None, *, deadline=None, cancelled=None):
            calls.append((method, path, obj, deadline))
            if path == "/prompt":
                return {"prompt_id": "our-prompt"}
            if path == "/history/our-prompt":
                event.set()
                return {}
            return {}

        event = cancelled
        with mock.patch.dict(sys.modules, {"PIL": fake_pil()}):
            with mock.patch.object(backend, "_upload", return_value="uploaded.png"):
                with mock.patch.object(backend, "_json", side_effect=json_call):
                    with self.assertRaises(Cancelled):
                        backend.generate(EditRequest(PNG, "image/png", "edit"),
                                         cancelled)
        self.assertEqual([x[1] for x in calls],
                         ["/prompt", "/history/our-prompt", "/queue"])
        self.assertEqual(calls[-1][2], {"delete": ["our-prompt"]})
        self.assertTrue(all(path != "/interrupt" for _, path, _, _ in calls))

    def test_comfy_poll_timeout_is_total_budget_and_cleans_queue(self):
        backend = self.comfy(timeout=0.01)
        calls = []

        def json_call(method, path, obj=None, *, deadline=None, cancelled=None):
            calls.append((path, deadline))
            if path == "/prompt":
                return {"prompt_id": "timed-out-prompt"}
            return {}

        with mock.patch.dict(sys.modules, {"PIL": fake_pil()}):
            with mock.patch.object(backend, "_upload", return_value="uploaded.png"):
                with mock.patch.object(backend, "_json", side_effect=json_call):
                    with self.assertRaisesRegex(BackendError, "timeout"):
                        backend.generate(EditRequest(PNG, "image/png", "edit"),
                                         threading.Event())
        self.assertIn("/queue", [item[0] for item in calls])
        self.assertEqual(calls[0][1], calls[1][1])  # prompt/poll one budget


if __name__ == "__main__":
    unittest.main()
