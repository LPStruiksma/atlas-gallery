"""Regression gate for the opt-in FLUX.2 head scaffold (IDS-12B)."""
import os
import unittest
from unittest import mock

from tools.ai_edit_server.backend import (
    BackendError, EditRequest, Flux2HeadBackend, select_backend,
)


class Flux2HeadAdmissionTest(unittest.TestCase):
    def test_disabled_by_default(self):
        with mock.patch.dict(os.environ, {}, clear=True):
            with self.assertRaisesRegex(ValueError, "disabled"):
                select_backend("flux2-head")

    def test_only_exact_true_admits_scaffold(self):
        for value in ("false", "1", "TRUE", "yes", " true"):
            with self.subTest(value=value), mock.patch.dict(
                    os.environ, {"ATLAS_FLUX2_HEAD_ENABLED": value}, clear=True):
                with self.assertRaisesRegex(ValueError, "disabled"):
                    select_backend("flux2-head")
        with mock.patch.dict(
                os.environ, {"ATLAS_FLUX2_HEAD_ENABLED": "true"}, clear=True):
            self.assertIsInstance(select_backend("flux2-head"), Flux2HeadBackend)

    def test_full_head_and_unknown_modes_are_refused(self):
        for mode in ("full_head", "full-head", "production", ""):
            with self.subTest(mode=mode), mock.patch.dict(os.environ, {
                "ATLAS_FLUX2_HEAD_ENABLED": "true",
                "ATLAS_FLUX2_HEAD_MODE": mode,
            }, clear=True):
                with self.assertRaisesRegex(ValueError, "scaffold"):
                    select_backend("flux2-head")

    def test_scaffold_never_executes_inference(self):
        backend = Flux2HeadBackend()
        request = EditRequest(b"synthetic", "image/png", "synthetic instruction")
        with self.assertRaisesRegex(BackendError, "scaffold only"):
            backend.generate(request, mock.Mock())


if __name__ == "__main__":
    unittest.main()
