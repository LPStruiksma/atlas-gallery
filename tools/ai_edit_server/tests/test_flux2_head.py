"""Regression gate for the opt-in FLUX.2 head scaffold (IDS-12B)."""
import os
import unittest
from unittest import mock

from tools.ai_edit_server.backend import select_backend


class Flux2HeadAdmissionTest(unittest.TestCase):
    def test_disabled_by_default(self):
        with mock.patch.dict(os.environ, {"ATLAS_FLUX2_HEAD_ENABLED": "false"}):
            with self.assertRaisesRegex(ValueError, "disabled"):
                select_backend("flux2-head")


if __name__ == "__main__":
    unittest.main()
