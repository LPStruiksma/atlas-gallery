import json
import tempfile
import unittest
from collections import Counter
from pathlib import Path

try:
    import PIL  # noqa: F401
    HAVE_PIL = True
except ImportError:  # the scorer's own tests stay dependency-free
    HAVE_PIL = False

from tools.ocr_bench import evaluate, gen_corpus


@unittest.skipUnless(HAVE_PIL, "Pillow not installed")
class CorpusRecipeTest(unittest.TestCase):
    def test_strata_split_and_determinism(self):
        with tempfile.TemporaryDirectory() as first, tempfile.TemporaryDirectory() as second:
            rows = gen_corpus.generate(Path(first), per_cell=6, negatives=6)
            again = gen_corpus.generate(Path(second), per_cell=6, negatives=6)
            self.assertEqual([r["text"] for r in rows], [r["text"] for r in again])
            self.assertEqual([r["id"] for r in rows], [r["id"] for r in again])
            cells = Counter((r["language"], r["kind"]) for r in rows if r["kind"] != "no_text")
            self.assertEqual(set(cells.values()), {6})
            self.assertEqual(len(cells), 6)
            self.assertEqual({r["split"] for r in rows}, {"dev", "test"})
            self.assertTrue(all(r["text"] == "" for r in rows if r["kind"] == "no_text"))

    def test_default_recipe_meets_unit_minimums(self):
        rows = gen_corpus.generate(Path("/nonexistent"), write_images=False)
        cells = Counter((r["language"], r["kind"]) for r in rows if r["kind"] != "no_text")
        self.assertTrue(all(count >= 25 for count in cells.values()) and len(cells) == 6)
        self.assertGreaterEqual(sum(1 for r in rows if r["kind"] == "no_text"), 50)
        self.assertTrue(any(ch in "".join(r["text"] for r in rows if r.get("language") == "nl") for ch in "éëï"))

    def test_no_ground_truth_text_is_clipped(self):
        rows = gen_corpus.generate(Path("/nonexistent"), write_images=False)
        text_rows = [r for r in rows if r["text"]]
        self.assertTrue(all(r["text_px"] <= r["text_limit_px"] for r in text_rows))
        self.assertEqual(len(text_rows), 180)

    def test_split_scoring_and_negatives_stay_out_of_cer(self):
        rows = [{"id": "a", "kind": "sign", "language": "en", "text": "exit", "split": "dev"},
                {"id": "b", "kind": "no_text", "text": "", "split": "test"}]
        preds = [{"id": "a", "text": "exit"}, {"id": "b", "text": "ghost"}]
        with tempfile.TemporaryDirectory() as tmp:
            manifest, pred = Path(tmp, "m.jsonl"), Path(tmp, "p.jsonl")
            manifest.write_text("".join(json.dumps(r) + "\n" for r in rows))
            pred.write_text("".join(json.dumps(r) + "\n" for r in preds))
            full = evaluate.evaluate(manifest, pred)
            self.assertEqual(full["false_text"], 1)
            self.assertEqual(full["char_edits"], 0)  # "ghost" on the negative is false_text, not CER
            dev = evaluate.evaluate(manifest, pred, split="dev")
            self.assertEqual((dev["samples"], dev["false_text"]), (1, 0))
            with self.assertRaises(ValueError):
                evaluate.evaluate(manifest, pred, split="none")
            # a prediction missing from the EXCLUDED split must still fail the integrity check
            pred.write_text(json.dumps(preds[1]) + "\n")
            with self.assertRaises(ValueError):
                evaluate.evaluate(manifest, pred, split="test")
            pred.write_text("".join(json.dumps(r) + "\n" for r in preds + [{"id": "zzz", "text": ""}]))
            with self.assertRaises(ValueError):
                evaluate.evaluate(manifest, pred, split="dev")


if __name__ == "__main__":
    unittest.main()
