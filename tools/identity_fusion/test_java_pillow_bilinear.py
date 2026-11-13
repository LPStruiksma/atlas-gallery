#!/usr/bin/env python3
"""Only fully artificial reference geometry; never private photos or models."""
from __future__ import annotations

import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from audit_intel_resize_goldens import REFERENCE_SHA256
from synthetic_pillow_bilinear_reference import synthetic_pillow_bilinear
from benchmark_resize_parity import PROBES

ROOT = Path(__file__).resolve().parent

# No image files read. The Java harness uses independently source-pinned
# artificial RGB bytes and checks its own negative/mutation behavior.
NEGATIVE_HARNESS = r"""
public final class SyntheticPillowNegativeHarness {
    private static void check(boolean value) {
        if (!value) throw new AssertionError("Fictional Java resize invariant failed");
    }
    public static void main(String[] args) {
        final int[] widths = {341, 199, 128, 65};
        final int[] heights = {173, 397, 256, 83};
        IntelSyntheticPillowBilinear candidate = new IntelSyntheticPillowBilinear();
        for (int i = 0; i < 4; i++) {
            byte[] original = IntelSyntheticCanary.generate(i);
            String checksum = IntelSyntheticCanary.sha256(original);
            byte[] output = candidate.resize(widths[i], heights[i], original);
            check(output.length == 128 * 256 * 3);
            check(! (output == original)); // even same-size colorbars cannot alias
            check(checksum.equals(IntelSyntheticCanary.sha256(original)));
            original[0] ^= 1;
            boolean refused = false;
            try { candidate.resize(widths[i], heights[i], original); }
            catch (IllegalArgumentException expected) { refused = true; }
            check(refused);
        }
        boolean missing = false;
        try { candidate.resize(341, 174, IntelSyntheticCanary.generate(0)); }
        catch (IllegalArgumentException expected) { missing = true; }
        check(missing);
        missing = false;
        try { candidate.resize(341, 173, null); }
        catch (IllegalArgumentException expected) { missing = true; }
        check(missing);
        missing = false;
        try { candidate.resize(341, 173, new byte[0]); }
        catch (IllegalArgumentException expected) { missing = true; }
        check(missing);
        System.out.println("{\"fictional_mutation_and_geometry_refusal\":true,"
             + "\"private_photos_read\":false,\"production_approved\":false}");
    }
}
"""


@unittest.skipUnless(shutil.which("javac") and shutil.which("java"),
                     "Java 8-compatible compiler/runtime unavailable")
class PortableJavaPillowBilinearTests(unittest.TestCase):
    def _compile(self, work):
        files = [ROOT / name for name in (
            "IntelSyntheticCanary.java", "IntelSyntheticPillowBilinear.java"
        )]
        for file in files:
            self.assertTrue(file.is_file())
        built = subprocess.run(
            ["javac", "-Xlint:-options", "--release", "8",
             "-d", str(work), *map(str, files)],
            text=True, capture_output=True, timeout=45)
        self.assertEqual(built.returncode, 0, built.stderr[:1300])

    def test_java_calculates_all_four_rgb_and_bgr_tensor_goldens_without_python_handoff(self):
        with tempfile.TemporaryDirectory(prefix="fictional-jvm-bilinear-") as td:
            target = Path(td)
            self._compile(target)
            run = subprocess.run(
                ["java", "-cp", str(target), "IntelSyntheticPillowBilinear",
                 "--self-test"],
                text=True, capture_output=True, timeout=45)
            self.assertEqual(run.returncode, 0, run.stderr[:1300])
            report = json.loads(run.stdout)
            self.assertEqual(report["checked_patterns"], 4)
            self.assertEqual(report["passing_patterns"], 4)
            self.assertTrue(report["all_four_exact"])
            for field in ("real_model_or_android_runtime_verified",
                          "production_approved", "auto_confirm_allowed",
                          "private_media_used"):
                self.assertFalse(report[field])
            for secret in ("/storage", "photo_path", "face_uid", "identity"):
                self.assertNotIn(secret, run.stdout)
            forbidden = subprocess.run(
                ["java", "-cp", str(target), "IntelSyntheticPillowBilinear",
                 "/private/portrait.jpg"],
                text=True, capture_output=True, timeout=20)
            self.assertEqual(forbidden.returncode, 2)
            self.assertNotIn("portrait", forbidden.stderr)

    def test_one_bit_mutation_wrong_geometry_and_aliases_fail_closed(self):
        with tempfile.TemporaryDirectory(prefix="fictional-jvm-negative-") as td:
            target = Path(td)
            self._compile(target)
            file = target / "SyntheticPillowNegativeHarness.java"
            file.write_text(NEGATIVE_HARNESS, encoding="utf-8")
            compiled = subprocess.run(
                ["javac", "-Xlint:-options", "--release", "8",
                 "-cp", str(target), "-d", str(target), str(file)],
                text=True, capture_output=True, timeout=45)
            self.assertEqual(compiled.returncode, 0, compiled.stderr[:1300])
            run = subprocess.run(
                ["java", "-cp", str(target), "SyntheticPillowNegativeHarness"],
                text=True, capture_output=True, timeout=45)
            self.assertEqual(run.returncode, 0, run.stderr[:1300])
            report = json.loads(run.stdout)
            self.assertTrue(report["fictional_mutation_and_geometry_refusal"])
            self.assertFalse(report["private_photos_read"])
            self.assertFalse(report["production_approved"])

    def test_stdlib_reference_is_frozen_independently_of_java_implementation(self):
        # Fixed Python stdlib implementation from the previous independent PR,
        # not derived from the Java resizer output or a dynamic test acceptance.
        import hashlib
        for label, width, height in PROBES:
            with self.subTest(pattern=label):
                expected = synthetic_pillow_bilinear(label, width, height)
                self.assertEqual(hashlib.sha256(expected).hexdigest(),
                                 REFERENCE_SHA256[label]["resized_rgb"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
