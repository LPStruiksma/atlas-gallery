"""Offline admission tests for the provenance-gated IDS-12B route."""
import hashlib
import json
import os
from pathlib import Path
import tempfile
import threading
from types import ModuleType, SimpleNamespace
import unittest
from unittest import mock

from tools.ai_edit_server.backend import (BackendError, Cancelled, EditRequest,
                                           Flux2HeadBackend,
                                           select_backend)


class Flux2HeadAdmissionTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        root = Path(self.tmp.name)
        workflow = {
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
        self.paths = {}
        for name in ("model", "encoder", "runtime", "adapter"):
            path = root / name
            path.write_bytes((name + "-synthetic").encode())
            self.paths[name] = path
        path = root / "workflow.json"
        path.write_text(json.dumps(workflow), encoding="utf-8")
        self.paths["workflow"] = path
        artifacts = {}
        for name, item in self.paths.items():
            artifacts[name] = {
                "path": str(item),
                "sha256": hashlib.sha256(item.read_bytes()).hexdigest(),
                "original_host": "https://original.invalid/" + name,
                "licence": "independently-recorded-test-licence",
                "provenance": "synthetic offline fixture",
            }
        self.config = root / "admission.json"
        self.data = {
            "schema_version": 1,
            "comfy_url": "http://127.0.0.1:8188",
            "artifacts": artifacts,
            "evidence": {
                "licences_verified": True,
                "adapter_provenance_verified": True,
                "operator_safeguards_verified": True,
                "ids12a_acceptance_passed": True,
            },
        }
        self.write_config()
        self.enabled = mock.patch.dict(os.environ, {
            "ATLAS_FLUX2_HEAD_ENABLED": "true",
            "ATLAS_FLUX2_HEAD_CONFIG": str(self.config),
        }, clear=True)
        self.enabled.start()

    def tearDown(self):
        self.enabled.stop()
        self.tmp.cleanup()

    def write_config(self):
        self.config.write_text(json.dumps(self.data), encoding="utf-8")

    def request(self, **changes):
        values = dict(image=b"target", mime="image/png", instruction="replace head",
                      options={"seed": 7, "head_x": 2, "head_y": 3,
                               "head_width": 32, "head_height": 40},
                      mask=b"mask", references=(b"reference",),
                      image_width=100, image_height=100)
        values.update(changes)
        return EditRequest(**values)

    def test_route_is_distinct_disabled_by_default_and_never_aliases_flux(self):
        with mock.patch.dict(os.environ, {"ATLAS_FLUX2_HEAD_ENABLED": "false"}):
            with self.assertRaisesRegex(ValueError, "disabled"):
                select_backend("flux2-head")
        self.assertNotIsInstance(select_backend("flux"), Flux2HeadBackend)
        for value in ("false", "1", "TRUE", "yes", " true"):
            with mock.patch.dict(os.environ, {"ATLAS_FLUX2_HEAD_ENABLED": value}):
                with self.assertRaisesRegex(ValueError, "disabled"):
                    select_backend("flux2-head")
        for mode in ("full_head", "full-head", "production", ""):
            with mock.patch.dict(os.environ, {"ATLAS_FLUX2_HEAD_MODE": mode}):
                with self.assertRaisesRegex(ValueError, "scaffold"):
                    select_backend("flux2-head")

    def test_missing_mismatched_and_changed_artifacts_fail_closed(self):
        for condition in ("missing", "mismatch"):
            with self.subTest(condition=condition):
                original = self.data["artifacts"]["model"].copy()
                if condition == "missing":
                    self.data["artifacts"]["model"]["path"] += ".absent"
                else:
                    self.data["artifacts"]["model"]["sha256"] = "0" * 64
                self.write_config()
                with self.assertRaises(ValueError):
                    Flux2HeadBackend(str(self.config))
                self.data["artifacts"]["model"] = original
        self.write_config()
        backend = Flux2HeadBackend(str(self.config))
        self.paths["adapter"].write_bytes(b"changed")
        with self.assertRaisesRegex(BackendError, "changed"):
            backend.generate(self.request(), threading.Event())

    def test_each_independent_evidence_gate_is_mandatory(self):
        for gate in tuple(self.data["evidence"]):
            with self.subTest(gate=gate):
                self.data["evidence"][gate] = False
                self.write_config()
                with self.assertRaisesRegex(ValueError, "unverified"):
                    Flux2HeadBackend(str(self.config))
                self.data["evidence"][gate] = True

    def test_workflow_requires_real_semantic_bindings(self):
        workflow = json.loads(self.paths["workflow"].read_text())
        workflow["6"]["inputs"].pop("adapter")
        self.paths["workflow"].write_text(json.dumps(workflow))
        self.data["artifacts"]["workflow"]["sha256"] = hashlib.sha256(
            self.paths["workflow"].read_bytes()).hexdigest()
        self.write_config()
        with self.assertRaisesRegex(ValueError, "semantic bindings"):
            Flux2HeadBackend(str(self.config))

    def test_graph_rejects_wrong_fields_edges_classes_and_extra_outputs(self):
        original = self.paths["workflow"].read_text()
        changes = [
            lambda g: g["6"]["inputs"].update(target=["1", 0]),
            lambda g: g["6"]["inputs"].update(mask=["2", 0]),
            lambda g: g["6"]["inputs"].update(reference=["1", 0]),
            lambda g: g["4"]["inputs"].update(image=["1", False]),
            lambda g: g["4"]["inputs"].update(x="ATLAS_HEAD_Y"),
            lambda g: g["5"]["inputs"].update(y=0),
            lambda g: g["6"].update(class_type="UnknownNode"),
            lambda g: g["6"]["inputs"].update(seed="ATLAS_ADAPTER", adapter="ATLAS_SEED"),
            lambda g: g["7"]["inputs"].update(images=["4", 0]),
            lambda g: g.update({"8": g["7"]}),
            lambda g: g.update({"8": {"class_type": "LoadImage",
                                      "inputs": {"image": "ATLAS_REFERENCE_1"}}}),
        ]
        for change in changes:
            with self.subTest(change=change):
                workflow = json.loads(original)
                change(workflow)
                self.paths["workflow"].write_text(json.dumps(workflow))
                self.data["artifacts"]["workflow"]["sha256"] = hashlib.sha256(
                    self.paths["workflow"].read_bytes()).hexdigest()
                self.write_config()
                with self.assertRaises(ValueError):
                    Flux2HeadBackend(str(self.config))

    def test_non_origin_crop_binds_both_paths_without_mutating_template(self):
        backend = Flux2HeadBackend(str(self.config))
        before = json.dumps(backend.workflow, sort_keys=True)
        request = self.request()
        with mock.patch.object(backend, "_validate_mask"), mock.patch.object(
                backend, "_bind_head_workflow", wraps=backend._bind_head_workflow) as bind:
            with self.assertRaisesRegex(BackendError, "scaffold only"):
                backend.generate(request, threading.Event())
            bind.assert_called_once_with(request)
        graph = backend._bind_head_workflow(request)
        for node, source in (("4", "1"), ("5", "2")):
            self.assertEqual(graph[node]["inputs"],
                             {"image": [source, 0], "x": 2, "y": 3,
                              "width": 32, "height": 40})
        self.assertEqual(graph["6"]["inputs"]["adapter"], str(self.paths["adapter"]))
        self.assertEqual(graph["6"]["inputs"]["seed"], 7)
        self.assertEqual(graph[backend.output_node_id]["inputs"]["images"], ["6", 0])
        self.assertEqual(json.dumps(backend.workflow, sort_keys=True), before)
        second = self.request(options={"seed": 8, "head_x": 10, "head_y": 11,
                                       "head_width": 40, "head_height": 32})
        again = backend._bind_head_workflow(second)
        self.assertEqual(again["4"]["inputs"]["x"], 10)
        self.assertEqual(again["5"]["inputs"]["y"], 11)
        self.assertEqual(graph["4"]["inputs"]["x"], 2)

    def test_bounded_crop_refs_mask_seed_and_no_execution(self):
        backend = Flux2HeadBackend(str(self.config))
        self.assertFalse(backend.capabilities()["features"]["full_head"])
        self.assertFalse(backend.capabilities()["features"]["instruction_edit"])
        self.assertFalse(backend.capabilities()["features"]["lineage"])
        with mock.patch.object(backend, "_validate_mask"), mock.patch.object(
                Flux2HeadBackend.__mro__[1], "generate") as generate, mock.patch.object(
                backend, "_fetch") as fetch:
            with self.assertRaisesRegex(BackendError, "scaffold only"):
                backend.generate(self.request(), threading.Event())
            generate.assert_not_called()
            fetch.assert_not_called()
        invalid = [
            self.request(mask=None), self.request(references=()),
            self.request(references=(b"one", b"two")),
            self.request(options={"seed": 7}),
            self.request(options={"seed": 7, "head_x": 90, "head_y": 0,
                                  "head_width": 32, "head_height": 40}),
            self.request(options={"seed": -1, "head_x": 2, "head_y": 3,
                                  "head_width": 32, "head_height": 40}),
            self.request(options={"seed": True, "head_x": 2, "head_y": 3,
                                  "head_width": 32, "head_height": 40}),
            self.request(options={"seed": 7, "head_x": -1, "head_y": 3,
                                  "head_width": 32, "head_height": 40}),
        ]
        for request in invalid:
            with self.subTest(request=request), mock.patch.object(backend, "_validate_mask"):
                with self.assertRaises(BackendError):
                    backend.generate(request, threading.Event())

    def test_cancelled_never_calls_transport(self):
        backend = Flux2HeadBackend(str(self.config))
        cancelled = threading.Event()
        cancelled.set()
        with mock.patch.object(backend, "_fetch") as fetch:
            with self.assertRaises(Cancelled):
                backend.generate(self.request(), cancelled)
            fetch.assert_not_called()

    def test_mask_must_be_nonempty_and_bounded_by_crop(self):
        class Mask:
            size = (100, 100)
            def __init__(self, bounds): self.bounds = bounds
            def __enter__(self): return self
            def __exit__(self, *args): return False
            def convert(self, mode): return self
            def getbbox(self): return self.bounds
        pil = ModuleType("PIL")
        pil.Image = SimpleNamespace(open=lambda stream: Mask(bounds.pop(0)))
        pil.ImageOps = SimpleNamespace(exif_transpose=lambda image: image)
        bounds = [None, (0, 0, 50, 50), (2, 3, 34, 43)]
        with mock.patch.dict("sys.modules", {"PIL": pil}):
            for message in ("select pixels", "exceeds"):
                with self.assertRaisesRegex(BackendError, message):
                    Flux2HeadBackend._validate_mask(b"mask", 100, 100,
                                                    (2, 3, 32, 40))
            Flux2HeadBackend._validate_mask(b"mask", 100, 100, (2, 3, 32, 40))


if __name__ == "__main__":
    unittest.main()
