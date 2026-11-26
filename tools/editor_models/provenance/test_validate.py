import copy
import json
import unittest
from pathlib import Path

from validate import validate

ROOT = Path(__file__).resolve().parent


class ProvenanceTests(unittest.TestCase):
    def setUp(self):
        self.data = json.loads((ROOT / "manifest.json").read_text())
        self.catalogue = json.loads((ROOT.parent / "candidates.json").read_text())

    def test_inventory(self):
        self.assertEqual(validate(self.data, self.catalogue), [])

    def test_missing_is_not_complete(self):
        data = copy.deepcopy(self.data)
        row = data["candidates"][0]
        row.update(status="missing", bytes=None, sha256=None,
                   retrieved_url=None, retrieved_at_utc=None)
        self.assertTrue(validate(data, self.catalogue, True))

    def test_reject_duplicate_and_unrecorded_digest(self):
        data = copy.deepcopy(self.data)
        data["candidates"].append(copy.deepcopy(data["candidates"][0]))
        self.assertTrue(validate(data, self.catalogue))
        data = copy.deepcopy(self.data)
        data["candidates"][0].update(status="missing", sha256="a" * 64)
        self.assertTrue(validate(data, self.catalogue))

    def test_recorded_without_evidence_fails(self):
        data = copy.deepcopy(self.data)
        data["candidates"][0].update(status="recorded", bytes=None, sha256=None)
        self.assertTrue(validate(data, self.catalogue))
