"""IDS-12B activation tests against a mocked ComfyUI provider only.

No network, model, GPU, Pillow or real photo is used. The repository verdict
ledger is patched to PASS inside each test; the checked-in value stays
NOT_MEASURED and a dedicated test pins that.
"""
import binascii
import hashlib
import json
import os
from pathlib import Path
import struct
import tempfile
import threading
import unittest
from unittest import mock
from urllib.parse import parse_qs, urlparse
import zlib

from tools.ai_edit_server import head_workflow
from tools.ai_edit_server.backend import (BackendError, Cancelled, EditRequest,
                                           Flux2HeadBackend, GeneratedEdit, Refused)
from tools.ai_edit_server.server import JobStore

WORKFLOW = {
    "1": {"class_type": "LoadImage", "inputs": {"image": "ATLAS_IMAGE"}},
    "2": {"class_type": "LoadImage", "inputs": {"image": "ATLAS_MASK"}},
    "3": {"class_type": "LoadImage", "inputs": {"image": "ATLAS_REFERENCE_0"}},
    "4": {"class_type": "ImageCrop", "inputs": {"image": ["1", 0],
          "x": "ATLAS_HEAD_X", "y": "ATLAS_HEAD_Y",
          "width": "ATLAS_HEAD_WIDTH", "height": "ATLAS_HEAD_HEIGHT"}},
    "5": {"class_type": "ImageCrop", "inputs": {"image": ["2", 0],
          "x": "ATLAS_HEAD_X", "y": "ATLAS_HEAD_Y",
          "width": "ATLAS_HEAD_WIDTH", "height": "ATLAS_HEAD_HEIGHT"}},
    "6": {"class_type": "AtlasFlux2Head", "inputs": {
          "target": ["4", 0], "mask": ["5", 0], "reference": ["3", 0],
          "instruction": "ATLAS_INSTRUCTION", "seed": "ATLAS_SEED",
          "adapter": "ATLAS_ADAPTER"}},
    "7": {"class_type": "PreviewImage", "inputs": {"images": ["6", 0]}},
}


def chunk(kind, payload):
    return (struct.pack(">I", len(payload)) + kind + payload +
            struct.pack(">I", binascii.crc32(kind + payload) & 0xFFFFFFFF))


def png(width, height, extra=(), colour=2):
    channels = {0: 1, 2: 3, 4: 2, 6: 4}[colour]
    rows = b"".join(b"\x00" + bytes([(x * 7 + y) % 256 for x in range(width * channels)])
                    for y in range(height))
    ihdr = struct.pack(">IIBBBBB", width, height, 8, colour, 0, 0, 0)
    pieces = [chunk(b"IHDR", ihdr)]
    pieces += [chunk(kind, payload) for kind, payload in extra]
    pieces.append(chunk(b"IDAT", zlib.compress(rows)))
    pieces.append(chunk(b"IEND", b""))
    return b"\x89PNG\r\n\x1a\n" + b"".join(pieces)


def chunk_types(data):
    kinds, offset = [], 8
    while offset < len(data):
        length = struct.unpack(">I", data[offset:offset + 4])[0]
        kinds.append(data[offset + 4:offset + 8])
        offset += 12 + length
    return kinds


METADATA = ((b"tEXt", b"prompt\x00{\"private\": \"workflow\"}"),
            (b"eXIf", b"MM\x00*\x00\x00\x00\x08\x00\x00"),
            (b"tIME", b"\x07\xea\x0b\x1b\x14\x00\x00"),
            (b"iTXt", b"workflow\x00\x00\x00\x00\x00{}"))


class MockComfy:
    """Records every provider call; returns scripted history and view bytes."""

    def __init__(self, view, history=None, polls_before_done=0):
        self.view = view
        self.history = history
        self.polls_before_done = polls_before_done
        self.calls = []
        self.prompts = []
        self.deleted = []
        self.on_poll = None

    def history_item(self):
        if self.history is not None:
            return self.history
        return {"status": {"status_str": "success", "completed": True},
                "outputs": {"7": {"images": [{"filename": "head.png",
                                              "subfolder": "", "type": "temp"}]}}}

    def fetch(self, method, path, body=None, content_type=None, *, deadline=None,
              cancelled=None):
        if cancelled is not None and cancelled.is_set():
            raise Cancelled()
        self.calls.append((method, path.split("?")[0]))
        if path == "/upload/image":
            return json.dumps({"name": "upload-%d.png" % len(self.calls)}).encode()
        if path == "/prompt":
            self.prompts.append(json.loads(body))
            return b'{"prompt_id": "p-1"}'
        if path == "/queue":
            self.deleted.append(json.loads(body))
            return b"{}"
        if path.startswith("/history/"):
            if self.on_poll:
                self.on_poll()
            if self.polls_before_done > 0:
                self.polls_before_done -= 1
                return b"{}"
            return json.dumps({"p-1": self.history_item()}).encode()
        if path.startswith("/view?"):
            query = parse_qs(urlparse(path).query)
            assert query["filename"] == ["head.png"], query
            return self.view
        raise AssertionError("unexpected provider call " + path)

    def provider_paths(self):
        return [path for _, path in self.calls]


class HeadActivationTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        root = Path(self.tmp.name)
        self.paths = {}
        for name in ("model", "encoder", "runtime", "adapter"):
            path = root / name
            path.write_bytes((name + "-synthetic").encode())
            self.paths[name] = path
        self.paths["workflow"] = root / "workflow.json"
        self.paths["workflow"].write_text(json.dumps(WORKFLOW), encoding="utf-8")
        artifacts = {name: {"path": str(path),
                            "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                            "original_host": "https://original.invalid/" + name,
                            "licence": "independently-recorded-test-licence",
                            "provenance": "synthetic offline fixture"}
                     for name, path in self.paths.items()}
        self.config = root / "admission.json"
        self.data = {
            "schema_version": 1,
            "comfy_url": "http://127.0.0.1:8188",
            "timeout_seconds": 5,
            "artifacts": artifacts,
            "evidence": {gate: True for gate in (
                "licences_verified", "adapter_provenance_verified",
                "operator_safeguards_verified", "ids12a_acceptance_passed",
                "adapter_dossier_passed", "head_verdict_passed")},
        }
        self.write_config()
        self.env = mock.patch.dict(os.environ, {
            "ATLAS_FLUX2_HEAD_ENABLED": "true",
            "ATLAS_FLUX2_HEAD_MODE": "activate",
            "ATLAS_FLUX2_HEAD_CONFIG": str(self.config),
        }, clear=True)
        self.env.start()
        self.ledger = mock.patch.object(head_workflow, "HEAD_VERDICT_LEDGER", "PASS")
        self.ledger.start()

    def tearDown(self):
        self.ledger.stop()
        self.env.stop()
        self.tmp.cleanup()

    def write_config(self):
        self.config.write_text(json.dumps(self.data), encoding="utf-8")

    def request(self, **changes):
        values = dict(image=b"target", mime="image/png", instruction="ATLAS_IMAGE",
                      options={"seed": 7, "head_x": 2, "head_y": 3,
                               "head_width": 32, "head_height": 40},
                      mask=b"mask", references=(b"reference",),
                      image_width=100, image_height=100)
        values.update(changes)
        return EditRequest(**values)

    def backend(self, comfy):
        backend = Flux2HeadBackend(str(self.config))
        backend._fetch = comfy.fetch
        backend._prepare_upload = lambda data: b"normalized:" + data
        backend._validate_mask = lambda *args: None
        return backend

    def run_job(self, comfy, request=None, cancelled=None):
        backend = self.backend(comfy)
        return backend.generate(request or self.request(), cancelled or threading.Event())

    # -- all gates true ---------------------------------------------------

    def test_all_gates_true_returns_clean_crop_with_lineage(self):
        comfy = MockComfy(png(32, 40, METADATA), polls_before_done=1)
        backend = self.backend(comfy)
        caps = backend.capabilities()["features"]
        self.assertTrue(caps["full_head"])
        self.assertTrue(caps["lineage"])
        result = backend.generate(self.request(), threading.Event())
        self.assertIsInstance(result, GeneratedEdit)
        self.assertEqual(chunk_types(result.image), [b"IHDR", b"IDAT", b"IEND"])
        self.assertEqual(result.image, png(32, 40))
        self.assertEqual(result.lineage, {
            "engine": "self-hosted-flux2-head",
            "version": head_workflow.ENGINE_VERSION,
            "mode": "activate",
            "seed": 7,
            **{kind + "_sha256": self.data["artifacts"][kind]["sha256"]
               for kind in ("model", "encoder", "runtime", "adapter", "workflow")},
        })
        self.assertEqual(comfy.provider_paths()[:4],
                         ["/upload/image"] * 3 + ["/prompt"])
        self.assertEqual(len(comfy.prompts), 1)
        graph = comfy.prompts[0]["prompt"]
        self.assertEqual(graph["1"]["inputs"]["image"], "upload-1.png")
        self.assertEqual(graph["2"]["inputs"]["image"], "upload-2.png")
        self.assertEqual(graph["3"]["inputs"]["image"], "upload-3.png")
        for node in ("4", "5"):
            self.assertEqual({k: graph[node]["inputs"][k] for k in ("x", "y", "width", "height")},
                             {"x": 2, "y": 3, "width": 32, "height": 40})
        # Instruction text equal to a placeholder is not re-substituted.
        self.assertEqual(graph["6"]["inputs"]["instruction"], "ATLAS_IMAGE")
        self.assertEqual(graph["6"]["inputs"]["seed"], 7)
        self.assertEqual(graph["6"]["inputs"]["adapter"], str(self.paths["adapter"]))
        self.assertEqual(comfy.deleted, [])

    def test_job_store_publishes_crop_and_lineage(self):
        comfy = MockComfy(png(32, 40, METADATA))
        store = JobStore(self.backend(comfy))
        try:
            job = store.submit(self.request())
            store.executor.shutdown(wait=True)
            snapshot = store.get(job["id"])
            self.assertEqual(snapshot["status"], "succeeded")
            self.assertEqual(snapshot["lineage"]["seed"], 7)
            mime, data = store.result(job["id"])
            self.assertEqual((mime, data), ("image/png", png(32, 40)))
        finally:
            store.executor.shutdown(wait=True)

    # -- provider output -------------------------------------------------

    def test_metadata_is_stripped_and_pixels_kept(self):
        for colour in (0, 2, 4, 6):
            with self.subTest(colour=colour):
                result = self.run_job(MockComfy(png(32, 40, METADATA, colour=colour)))
                self.assertEqual(result.image, png(32, 40, colour=colour))
                for secret in (b"private", b"workflow", b"eXIf", b"tEXt"):
                    self.assertNotIn(secret, result.image)

    def test_wrong_size_publishes_nothing(self):
        for size in ((40, 32), (32, 41), (100, 100)):
            with self.subTest(size=size):
                comfy = MockComfy(png(*size))
                with self.assertRaisesRegex(BackendError, "declared size"):
                    self.run_job(comfy)
                self.assertEqual(comfy.deleted, [{"delete": ["p-1"]}])

    def test_several_or_missing_outputs_publish_nothing(self):
        one = {"filename": "head.png", "subfolder": "", "type": "temp"}
        cases = {
            "two in node 7": {"7": {"images": [one, dict(one, filename="b.png")]}},
            "extra node": {"7": {"images": [one]}, "4": {"images": [one]}},
            "wrong node": {"6": {"images": [one]}},
            "none": {},
            "no images": {"7": {"images": []}},
        }
        for label, outputs in cases.items():
            with self.subTest(label):
                comfy = MockComfy(png(32, 40), history={
                    "status": {"status_str": "success"}, "outputs": outputs})
                with self.assertRaises(BackendError):
                    self.run_job(comfy)
                self.assertNotIn("/view", comfy.provider_paths())
                self.assertEqual(comfy.deleted, [{"delete": ["p-1"]}])

    def test_invalid_png_bytes_are_rejected(self):
        good = png(32, 40)
        corrupt = bytearray(good)
        corrupt[40] ^= 0xFF
        palette = png(32, 40, extra=((b"PLTE", b"\x00\x00\x00"),))
        cases = [b"\xff\xd8\xff\xe0 jpeg", good[:-12], good + b"trailing",
                 bytes(corrupt), palette,
                 png(32, 40, extra=((b"ZZZZ", b"critical"),))]
        for data in cases:
            with self.subTest(data=data[:12]):
                with self.assertRaises(BackendError):
                    self.run_job(MockComfy(data))

    def test_provider_error_and_refusal(self):
        comfy = MockComfy(png(32, 40), history={"status": {"status_str": "error"}})
        with self.assertRaisesRegex(BackendError, "execution failed"):
            self.run_job(comfy)
        self.assertEqual(comfy.deleted, [{"delete": ["p-1"]}])
        comfy = MockComfy(png(32, 40), history={"atlas_status": "refused",
                                                "atlas_code": "POLICY",
                                                "status": {"status_str": "success"}})
        with self.assertRaises(Refused) as caught:
            self.run_job(comfy)
        self.assertEqual(caught.exception.code, "POLICY")
        self.assertNotIn("/view", comfy.provider_paths())
        self.assertEqual(comfy.deleted, [{"delete": ["p-1"]}])

    # -- cancel ----------------------------------------------------------

    def test_cancel_mid_job_publishes_no_image_or_lineage(self):
        cancelled = threading.Event()
        comfy = MockComfy(png(32, 40), polls_before_done=2)
        comfy.on_poll = cancelled.set
        with self.assertRaises(Cancelled):
            self.run_job(comfy, cancelled=cancelled)
        self.assertNotIn("/view", comfy.provider_paths())
        self.assertEqual(comfy.deleted, [{"delete": ["p-1"]}])

    def test_cancel_through_job_store_clears_result(self):
        comfy = MockComfy(png(32, 40), polls_before_done=1000)
        store = JobStore(self.backend(comfy))
        started = threading.Event()
        comfy.on_poll = started.set
        try:
            job = store.submit(self.request())
            self.assertTrue(started.wait(5))
            store.cancel(job["id"])
            store.executor.shutdown(wait=True)
            snapshot = store.get(job["id"])
            self.assertEqual(snapshot["status"], "cancelled")
            self.assertNotIn("lineage", snapshot)
            self.assertEqual(snapshot["result_urls"], [])
            self.assertEqual(comfy.deleted, [{"delete": ["p-1"]}])
        finally:
            store.executor.shutdown(wait=True)

    def test_cancel_after_view_still_publishes_nothing(self):
        cancelled = threading.Event()
        comfy = MockComfy(png(32, 40))
        original = comfy.fetch

        def fetch(method, path, *args, **kwargs):
            data = original(method, path, *args, **kwargs)
            if path.startswith("/view?"):
                cancelled.set()
            return data
        backend = self.backend(comfy)
        backend._fetch = fetch
        with self.assertRaises(Cancelled):
            backend.generate(self.request(), cancelled)

    # -- gates -----------------------------------------------------------

    def test_changed_workflow_hash_never_submits(self):
        comfy = MockComfy(png(32, 40))
        backend = self.backend(comfy)
        changed = dict(WORKFLOW, **{"8": WORKFLOW["7"]})
        self.paths["workflow"].write_text(json.dumps(changed), encoding="utf-8")
        with self.assertRaisesRegex(BackendError, "changed"):
            backend.generate(self.request(), threading.Event())
        self.assertEqual(comfy.calls, [])

    def test_workflow_changed_while_running_publishes_nothing(self):
        comfy = MockComfy(png(32, 40), polls_before_done=1)
        comfy.on_poll = lambda: self.paths["workflow"].write_text("{}", encoding="utf-8")
        with self.assertRaisesRegex(BackendError, "changed"):
            self.run_job(comfy)
        self.assertEqual(comfy.deleted, [{"delete": ["p-1"]}])

    def test_any_gate_false_at_startup_refuses_activation(self):
        for gate in tuple(self.data["evidence"]):
            with self.subTest(gate=gate):
                self.data["evidence"][gate] = False
                self.write_config()
                with self.assertRaisesRegex(ValueError, "unverified"):
                    Flux2HeadBackend(str(self.config))
                self.data["evidence"][gate] = True
        self.write_config()
        with mock.patch.object(head_workflow, "HEAD_VERDICT_LEDGER", "NOT_MEASURED"):
            with self.assertRaisesRegex(ValueError, "verdict ledger"):
                Flux2HeadBackend(str(self.config))
        with mock.patch.dict(os.environ, {"ATLAS_FLUX2_HEAD_ENABLED": "false"}):
            with self.assertRaisesRegex(ValueError, "disabled"):
                Flux2HeadBackend(str(self.config))
        for mode in ("full_head", "Activate", "activate ", ""):
            with mock.patch.dict(os.environ, {"ATLAS_FLUX2_HEAD_MODE": mode}):
                with self.assertRaisesRegex(ValueError, "forbidden"):
                    Flux2HeadBackend(str(self.config))

    def test_gate_closed_after_startup_never_submits(self):
        def ledger(): return mock.patch.object(head_workflow, "HEAD_VERDICT_LEDGER", "FAIL")

        def evidence():
            self.data["evidence"]["head_verdict_passed"] = False
            self.write_config()
            return mock.patch.dict(os.environ, {})

        def enabled(): return mock.patch.dict(os.environ, {"ATLAS_FLUX2_HEAD_ENABLED": "no"})
        def mode(): return mock.patch.dict(os.environ, {"ATLAS_FLUX2_HEAD_MODE": "scaffold"})
        for label, closer in (("ledger", ledger), ("evidence", evidence),
                              ("enabled", enabled), ("mode", mode)):
            with self.subTest(label):
                self.data["evidence"]["head_verdict_passed"] = True
                self.write_config()
                comfy = MockComfy(png(32, 40))
                backend = self.backend(comfy)
                with closer():
                    with self.assertRaisesRegex(BackendError, "gate closed|not activated"):
                        backend.generate(self.request(), threading.Event())
                self.assertEqual(comfy.calls, [])

    def test_gate_closed_mid_job_publishes_nothing(self):
        comfy = MockComfy(png(32, 40), polls_before_done=1)

        def close():
            self.data["evidence"]["adapter_dossier_passed"] = False
            self.write_config()
        comfy.on_poll = close
        with self.assertRaisesRegex(BackendError, "gate closed"):
            self.run_job(comfy)
        self.assertEqual(comfy.deleted, [{"delete": ["p-1"]}])

    def test_scaffold_mode_still_never_calls_provider(self):
        with mock.patch.dict(os.environ, {"ATLAS_FLUX2_HEAD_MODE": "scaffold"}):
            comfy = MockComfy(png(32, 40))
            backend = self.backend(comfy)
            self.assertFalse(backend.capabilities()["features"]["full_head"])
            with self.assertRaisesRegex(BackendError, "scaffold only"):
                backend.generate(self.request(), threading.Event())
        self.assertEqual(comfy.calls, [])

    def test_invalid_requests_never_reach_provider(self):
        comfy = MockComfy(png(32, 40))
        backend = self.backend(comfy)
        for request in (self.request(mask=None), self.request(references=()),
                        self.request(options={"seed": 7}),
                        self.request(options={"seed": 7, "head_x": 90, "head_y": 0,
                                              "head_width": 32, "head_height": 40})):
            with self.subTest(request=request):
                with self.assertRaises(BackendError):
                    backend.generate(request, threading.Event())
        self.assertEqual(comfy.calls, [])


class CheckedInLedgerTest(unittest.TestCase):
    def test_checked_in_ledger_keeps_full_head_unavailable(self):
        # Only the verdict unit (#1092) may change this, with real evidence.
        self.assertEqual(head_workflow.HEAD_VERDICT_LEDGER, "NOT_MEASURED")


if __name__ == "__main__":
    unittest.main()
