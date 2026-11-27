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
        self.uploads = []
        self.on_poll = None
        self.on_upload = None

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
            self.uploads.append(body)
            if self.on_upload:
                self.on_upload()
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


class AdmittedFixture(unittest.TestCase):
    """Synthetic admitted activation config; holds no tests itself."""

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
        backend._orientation_is_canonical = lambda data: True
        return backend

    def run_job(self, comfy, request=None, cancelled=None):
        backend = self.backend(comfy)
        return backend.generate(request or self.request(), cancelled or threading.Event())


class HeadActivationTest(AdmittedFixture):
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

    def test_hidden_idat_payloads_and_bad_ordering_are_rejected(self):
        rows = b"".join(b"\x00" + bytes(32 * 3) for _ in range(40))
        ihdr = chunk(b"IHDR", struct.pack(">IIBBBBB", 32, 40, 8, 2, 0, 0, 0))
        sig = b"\x89PNG\r\n\x1a\n"
        stream = zlib.compress(rows)
        cases = {
            "trailing bytes": sig + ihdr + chunk(b"IDAT", stream + b"hidden") +
            chunk(b"IEND", b""),
            "second zlib stream": sig + ihdr + chunk(b"IDAT", stream + zlib.compress(b"x")) +
            chunk(b"IEND", b""),
            "non-empty IEND": sig + ihdr + chunk(b"IDAT", stream) + chunk(b"IEND", b"x"),
            "split IDAT": sig + ihdr + chunk(b"IDAT", stream[:10]) +
            chunk(b"tEXt", b"k\x00v") + chunk(b"IDAT", stream[10:]) + chunk(b"IEND", b""),
        }
        for label, data in cases.items():
            with self.subTest(label):
                with self.assertRaises(head_workflow.HeadOutputError):
                    head_workflow.strip_png_metadata(data, 32, 40)
                with self.assertRaises(BackendError):
                    self.run_job(MockComfy(data))
        # Consecutive IDAT chunks are fine and are recompressed into one.
        split = (sig + ihdr + chunk(b"IDAT", stream[:10]) + chunk(b"IDAT", stream[10:]) +
                 chunk(b"IEND", b""))
        self.assertEqual(head_workflow.strip_png_metadata(split, 32, 40),
                         sig + ihdr + chunk(b"IDAT", zlib.compress(rows)) +
                         chunk(b"IEND", b""))

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

    def test_admission_file_edits_after_startup_close_the_gate(self):
        def digest(d): d["artifacts"]["model"]["sha256"] = "0" * 64
        def path(d): d["artifacts"]["adapter"]["path"] = str(self.paths["model"])
        def provenance(d): d["artifacts"]["adapter"]["provenance"] = "changed"
        def missing(d): d["artifacts"]["encoder"].pop("licence")
        def no_artifacts(d): d.pop("artifacts")
        def endpoint(d): d["comfy_url"] = "http://localhost:9999"
        def timeout(d): d["timeout_seconds"] = 60
        for edit in (digest, path, provenance, missing, no_artifacts, endpoint, timeout):
            with self.subTest(edit.__name__):
                original = json.loads(json.dumps(self.data))
                comfy = MockComfy(png(32, 40))
                backend = self.backend(comfy)
                edit(self.data)
                self.write_config()
                self.assertFalse(backend.capabilities()["features"]["full_head"])
                self.assertFalse(backend.capabilities()["features"]["lineage"])
                with self.assertRaisesRegex(BackendError, "gate closed"):
                    backend.generate(self.request(), threading.Event())
                self.assertEqual(comfy.calls, [])
                self.data = original
                self.write_config()

    def test_gate_revoked_between_uploads_stops_next_upload(self):
        comfy = MockComfy(png(32, 40))

        def revoke():
            self.data["evidence"]["licences_verified"] = False
            self.write_config()
        comfy.on_upload = revoke
        with self.assertRaisesRegex(BackendError, "gate closed"):
            self.run_job(comfy)
        self.assertEqual(comfy.provider_paths(), ["/upload/image"])

    def revoke(self):
        self.data["evidence"]["head_verdict_passed"] = False
        self.write_config()

    def test_gate_revoked_during_upload_preparation_sends_nothing(self):
        comfy = MockComfy(png(32, 40))
        backend = self.backend(comfy)

        def prepare(data):
            self.revoke()
            return b"normalized:" + data
        backend._prepare_upload = prepare
        with self.assertRaisesRegex(BackendError, "gate closed"):
            backend.generate(self.request(), threading.Event())
        self.assertEqual(comfy.calls, [])

    def test_gate_revoked_during_hashing_blocks_prompt_and_publication(self):
        for phase in ("before prompt", "before publication"):
            with self.subTest(phase):
                self.data["evidence"]["head_verdict_passed"] = True
                self.write_config()
                comfy = MockComfy(png(32, 40))
                backend = self.backend(comfy)
                original = backend._verify_unchanged

                def hashing():
                    original()
                    paths = comfy.provider_paths()
                    if ((phase == "before prompt" and paths.count("/upload/image") == 3)
                            or (phase == "before publication" and "/view" in paths)):
                        self.revoke()
                backend._verify_unchanged = hashing
                with self.assertRaisesRegex(BackendError, "gate closed"):
                    backend.generate(self.request(), threading.Event())
                if phase == "before prompt":
                    self.assertNotIn("/prompt", comfy.provider_paths())
                else:
                    self.assertEqual(comfy.deleted, [{"delete": ["p-1"]}])

    def test_gate_revoked_at_job_store_publication_publishes_nothing(self):
        comfy = MockComfy(png(32, 40))
        backend = self.backend(comfy)
        original = backend.generate

        def generate(request, cancelled):
            result = original(request, cancelled)
            self.revoke()  # after the backend finished, before publication
            return result
        backend.generate = generate
        store = JobStore(backend)
        try:
            job = store.submit(self.request())
            store.executor.shutdown(wait=True)
            snapshot = store.get(job["id"])
            self.assertEqual(snapshot["status"], "error")
            self.assertNotIn("lineage", snapshot)
            self.assertEqual(snapshot["result_urls"], [])
        finally:
            store.executor.shutdown(wait=True)

    def test_revoked_gate_withdraws_capability(self):
        backend = self.backend(MockComfy(png(32, 40)))
        self.assertTrue(backend.capabilities()["features"]["full_head"])
        with mock.patch.object(head_workflow, "HEAD_VERDICT_LEDGER", "NOT_MEASURED"):
            self.assertFalse(backend.capabilities()["features"]["full_head"])
        with mock.patch.dict(os.environ, {"ATLAS_FLUX2_HEAD_ENABLED": "false"}):
            self.assertFalse(backend.capabilities()["features"]["full_head"])

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


try:
    from PIL import Image as PILImage
except ImportError:  # CI may lack Pillow; the stdlib cases above still run.
    PILImage = None


@unittest.skipUnless(PILImage, "Pillow not installed")
class RealPillowUploadTest(AdmittedFixture):
    """Real Pillow: metadata-bearing inputs leave as pixel-only PNG uploads."""

    def source(self, fmt, colour, orientation=1):
        import io
        image = PILImage.new("RGB", (100, 100), colour)
        exif = PILImage.Exif()
        exif[0x0112] = orientation
        exif[0x010E] = "SECRET-DESCRIPTION"   # image description
        exif[0x8825] = {2: (52.0, 22.0, 1.0)} # GPS IFD latitude
        out = io.BytesIO()
        kwargs = {"exif": exif.tobytes(), "icc_profile": b"SECRET-ICC" * 20}
        if fmt == "PNG":
            from PIL import PngImagePlugin
            info = PngImagePlugin.PngInfo()
            info.add_text("Comment", "SECRET-TEXT")
            kwargs["pnginfo"] = info
        image.save(out, format=fmt, **kwargs)
        return out.getvalue()

    def test_uploaded_multipart_pngs_carry_no_metadata(self):
        for fmt in ("PNG", "JPEG"):
            with self.subTest(fmt=fmt):
                comfy = MockComfy(png(32, 40))
                backend = Flux2HeadBackend(str(self.config))
                backend._fetch = comfy.fetch
                backend._validate_mask = lambda *args: None
                request = self.request(image=self.source(fmt, (200, 10, 10)),
                                       mask=self.source(fmt, (255, 255, 255)),
                                       references=(self.source(fmt, (10, 10, 200), 6),))
                backend.generate(request, threading.Event())
                self.assertEqual(len(comfy.uploads), 3)
                for body in comfy.uploads:
                    start = body.index(b"\x89PNG")
                    end = body.index(b"IEND") + 8
                    upload = body[start:end]
                    self.assertEqual(chunk_types(upload), [b"IHDR", b"IDAT", b"IEND"])
                    for secret in (b"SECRET", b"Exif", b"eXIf", b"iCCP", b"GPS"):
                        self.assertNotIn(secret, upload)
                    self.assertEqual(struct.unpack(">II", upload[16:24]), (100, 100))


@unittest.skipUnless(PILImage, "Pillow not installed")
class RealPillowGeometryTest(AdmittedFixture):
    """Real Pillow and real mask validation on a non-square asymmetric target."""

    def encode(self, image, fmt, orientation=None):
        import io
        out = io.BytesIO()
        kwargs = {}
        if orientation is not None:
            exif = PILImage.Exif()
            exif[0x0112] = orientation
            kwargs["exif"] = exif.tobytes()
        image.save(out, format=fmt, **kwargs)
        return out.getvalue()

    def inputs(self, fmt, orientation):
        target = PILImage.new("RGB", (100, 60), (20, 20, 20))
        target.paste((250, 0, 0), (65, 0, 97, 32))   # asymmetric marker
        mask = PILImage.new("L", (100, 60), 0)
        mask.paste(255, (65, 0, 97, 32))
        reference = PILImage.new("RGB", (40, 40), (0, 0, 250))
        return self.request(
            image=self.encode(target, fmt, orientation),
            mask=self.encode(mask, "PNG", orientation),
            references=(self.encode(reference, fmt),),
            image_width=100, image_height=60,
            options={"seed": 7, "head_x": 65, "head_y": 0,
                     "head_width": 32, "head_height": 32})

    def backend(self, comfy):
        backend = Flux2HeadBackend(str(self.config))
        backend._fetch = comfy.fetch
        return backend

    def test_rotated_target_or_mask_is_refused_before_upload(self):
        for fmt in ("JPEG", "PNG"):
            for orientation in (2, 3, 6, 8):
                with self.subTest(fmt=fmt, orientation=orientation):
                    comfy = MockComfy(png(32, 32))
                    with self.assertRaisesRegex(BackendError, "canonical orientation"):
                        self.backend(comfy).generate(self.inputs(fmt, orientation),
                                                     threading.Event())
                    self.assertEqual(comfy.calls, [])

    def test_canonical_inputs_upload_in_the_validated_coordinate_space(self):
        for fmt, orientation in (("JPEG", None), ("PNG", None), ("JPEG", 1)):
            with self.subTest(fmt=fmt, orientation=orientation):
                comfy = MockComfy(png(32, 32))
                result = self.backend(comfy).generate(self.inputs(fmt, orientation),
                                                      threading.Event())
                self.assertEqual(struct.unpack(">II", result.image[16:24]), (32, 32))
                sizes = []
                for body in comfy.uploads:
                    start = body.index(b"\x89PNG")
                    sizes.append(struct.unpack(">II", body[start + 16:start + 24]))
                # Target and mask keep the 100x60 grid the crop was checked in.
                self.assertEqual(sizes, [(100, 60), (100, 60), (40, 40)])
                graph = comfy.prompts[0]["prompt"]
                self.assertEqual(graph["4"]["inputs"]["x"], 65)

    def test_mask_outside_crop_still_fails_with_real_validation(self):
        comfy = MockComfy(png(32, 32))
        request = self.inputs("PNG", None)
        request = self.request(image=request.image, mask=request.mask,
                               references=request.references,
                               image_width=100, image_height=60,
                               options={"seed": 7, "head_x": 0, "head_y": 0,
                                        "head_width": 32, "head_height": 32})
        with self.assertRaisesRegex(BackendError, "exceeds the explicit crop"):
            self.backend(comfy).generate(request, threading.Event())
        self.assertEqual(comfy.calls, [])


class CheckedInLedgerTest(unittest.TestCase):
    def test_checked_in_ledger_keeps_full_head_unavailable(self):
        # Only the verdict unit (#1092) may change this, with real evidence.
        self.assertEqual(head_workflow.HEAD_VERDICT_LEDGER, "NOT_MEASURED")


if __name__ == "__main__":
    unittest.main()
