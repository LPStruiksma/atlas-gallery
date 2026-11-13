"""Fabricated byte fixtures and fake rawpy only. No camera media in CI."""
import hashlib
import json
import os
from pathlib import Path
import resource
import subprocess
import sys
from tempfile import TemporaryDirectory
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import numpy as np
from tools.photo_lineage import isolated_raw_probe as worker
from tools.photo_lineage import run_isolated_raw_probe as launcher


def fictional_dng(suffix: bytes = b"fictional") -> bytes:
    """Manufactured little-endian classic TIFF IFD0 with one DNGVersion tag."""
    return (b"II" + (42).to_bytes(2, "little") +
            (8).to_bytes(4, "little") + (1).to_bytes(2, "little") +
            (50706).to_bytes(2, "little") + (1).to_bytes(2, "little") +
            (4).to_bytes(4, "little") + b"\x01\x04\x00\x00" +
            (0).to_bytes(4, "little") + suffix)


class FakeRaw:
    def __init__(self):
        self.sizes = SimpleNamespace(top_margin=0, left_margin=0,
                                     width=256, height=256)
        self.raw_pattern = np.array([[0, 1], [3, 2]], dtype=np.uint8)
        self.color_desc = b"RGBG"
        self.black_level_per_channel = [0, 0, 0, 0]
        self.white_level = 4095
        self.camera_white_level_per_channel = None
        self.raw_image_visible = np.full((256, 256), 1500, dtype=np.uint16)

    def raw_color(self, row, col):
        return int(self.raw_pattern[row % 2, col % 2])

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False


class FakeRawpy:
    @staticmethod
    def imread(stream):
        assert stream.read(2) == b"II"
        return FakeRaw()


class IsolatedRawProbeTests(unittest.TestCase):
    def test_pinned_synthetic_frame_emits_aggregate_only_and_preserves_bytes(self):
        with TemporaryDirectory() as td:
            path = Path(td) / "payload_N000.dng"
            original = fictional_dng(b"synthetic" * 200)
            path.write_bytes(original)
            pin = hashlib.sha256(original).hexdigest()
            out = worker.probe_source(path, pin, decoder_module=FakeRawpy)
            self.assertEqual((out["grayscale_width"], out["grayscale_height"]), (128, 128))
            self.assertTrue(out["source_sha256_pin_matched_before_and_after"])
            self.assertTrue(out["basic_dng_header_passed"])
            self.assertFalse(out["full_dng_format_validated"])
            self.assertFalse(out["separate_shutter_identity_verified"])
            self.assertNotIn(pin, json.dumps(out))
            self.assertNotIn(str(path), json.dumps(out))
            self.assertEqual(path.read_bytes(), original)

    def test_bad_pin_rejects_before_decoder(self):
        with TemporaryDirectory() as td:
            path = Path(td) / "payload_N000.dng"
            path.write_bytes(fictional_dng())
            with patch.object(FakeRawpy, "imread", side_effect=AssertionError("called")):
                with self.assertRaisesRegex(ValueError, "predeclared"):
                    worker.probe_source(path, "0" * 64, decoder_module=FakeRawpy)

    def test_symlinks_hardlinks_and_mid_decode_mutation_fail_closed(self):
        with TemporaryDirectory() as td:
            path = Path(td) / "payload_N000.dng"
            original = fictional_dng(b"original" * 200)
            path.write_bytes(original)
            alias = Path(td) / "payload_N001.dng"
            alias.symlink_to(path)
            with self.assertRaisesRegex(ValueError, "Symlink"):
                worker.probe_source(alias, "0" * 64, decoder_module=FakeRawpy)
            alias.unlink()
            os.link(path, alias)
            with self.assertRaisesRegex(ValueError, "hardlinked"):
                worker.probe_source(path, "0" * 64, decoder_module=FakeRawpy)
            alias.unlink()

            class Mutating:
                @staticmethod
                def imread(stream):
                    path.write_bytes(fictional_dng(b"tampered" * 200))
                    return FakeRaw()
            with self.assertRaisesRegex(ValueError, "changed"):
                worker.probe_source(path, hashlib.sha256(original).hexdigest(),
                                    decoder_module=Mutating)

    def test_valid_checksum_but_fake_dng_refused_before_decoder(self):
        with TemporaryDirectory() as td:
            root = Path(td)
            for index, contents in enumerate((
                b"not-a-DNG-file-despite-its-name",
                fictional_dng()[:14],
                fictional_dng().replace((50706).to_bytes(2, "little"),
                                         (50707).to_bytes(2, "little")),
            )):
                with self.subTest(index=index):
                    path = root / f"payload_N{index:03d}.dng"
                    path.write_bytes(contents)
                    with patch.object(FakeRawpy, "imread",
                                      side_effect=AssertionError("native decoder called")) as call:
                        with self.assertRaises(ValueError):
                            worker.probe_source(
                                path, hashlib.sha256(contents).hexdigest(),
                                decoder_module=FakeRawpy)
                        call.assert_not_called()

    def test_header_uses_same_pinned_fd_and_preserves_original(self):
        with TemporaryDirectory() as td:
            path = Path(td) / "payload_N000.dng"
            original = fictional_dng(b"x" * 100)
            path.write_bytes(original)
            pin = hashlib.sha256(original).hexdigest()
            with patch.object(worker, "_check_dng_header",
                              wraps=worker._check_dng_header) as check:
                out = worker.probe_source(path, pin, decoder_module=FakeRawpy)
            self.assertEqual(check.call_count, 1)
            self.assertTrue(out["basic_dng_header_passed"])
            self.assertEqual(path.read_bytes(), original)

    def test_os_budgets_reject_invalid_values_and_android(self):
        with patch.object(worker.resource, "setrlimit") as limit:
            worker.install_limits(memory_mib=2048, cpu_seconds=20)
        self.assertEqual(limit.call_args_list[-1].args[0], resource.RLIMIT_AS)
        self.assertIn(resource.RLIMIT_CPU, [x.args[0] for x in limit.call_args_list])
        for mem, cpu in ((0, 20), (True, 20), (2048, 100)):
            with self.assertRaises(ValueError):
                worker.install_limits(memory_mib=mem, cpu_seconds=cpu)
        with patch.dict(os.environ, {"ANDROID_ROOT": "/system"}):
            with self.assertRaisesRegex(ValueError, "Android"):
                worker.install_limits(memory_mib=2048, cpu_seconds=20)
            with self.assertRaisesRegex(ValueError, "Android"):
                launcher.run(source=Path("payload_N000.dng"), sha256="a" * 64)

    def test_isolated_child_refuses_nonsensical_raw_without_leaking_path(self):
        with TemporaryDirectory() as td:
            path = Path(td) / "payload_N000.dng"
            payload = b"invalid-synthetic-raw" * 100
            path.write_bytes(payload)
            finished = subprocess.run([
                sys.executable, "-I", "-B", str(Path(worker.__file__)),
                "--source", str(path), "--sha256", hashlib.sha256(payload).hexdigest(),
                "--memory-mib", "2048", "--cpu-seconds", "10"],
                capture_output=True, stdin=subprocess.DEVNULL, timeout=15)
            self.assertEqual(finished.returncode, 2)
            self.assertNotIn(str(path).encode(), finished.stdout + finished.stderr)

    def test_launcher_enforces_flags_output_shape_and_timeout(self):
        good = {
            "source_sha256_pin_matched_before_and_after": True,
            "basic_dng_header_passed": True,
            "full_dng_format_validated": False,
            "images_exported_or_uploaded": False, "separate_shutter_identity_verified": False,
            "grayscale_width": 128, "grayscale_height": 128,
            "grayscale_bytes_generated_in_memory": 16384
        }
        with patch.object(launcher, "_capture_bounded", return_value=(0, json.dumps(good).encode())) as run:
            self.assertEqual(launcher.run(source=Path("payload_N000.dng"),
                                          sha256="a" * 64)["grayscale_width"], 128)
        self.assertIn("-I", run.call_args.args[0])
        unverified = good | {"basic_dng_header_passed": False}
        with patch.object(launcher, "_capture_bounded",
                          return_value=(0, json.dumps(unverified).encode())):
            with self.assertRaisesRegex(ValueError, "Inconsistent"):
                launcher.run(source=Path("payload_N000.dng"), sha256="a" * 64)
        self.assertIn("-B", run.call_args.args[0])
        with patch.object(launcher, "_capture_bounded",
                          side_effect=ValueError("RAW probe wall-time budget exceeded")):
            with self.assertRaisesRegex(ValueError, "wall-time"):
                launcher.run(source=Path("payload_N000.dng"), sha256="a" * 64)
        with patch.object(launcher, "_capture_bounded", return_value=(0, json.dumps(good).encode())):
            with self.assertRaises(ValueError):
                launcher.run(source=Path("bad.dng"), sha256="a" * 64)


    def test_noisy_worker_is_killed_before_parent_buffers_bulk_output(self):
        cmd = [sys.executable, "-c", "import sys;sys.stdout.write('x'*100000)"]
        with self.assertRaisesRegex(ValueError, "stdout quota"):
            launcher._capture_bounded(cmd, wall_seconds=5)

    def test_silent_worker_hits_independent_wall_deadline(self):
        cmd = [sys.executable, "-c", "import time;time.sleep(2)"]
        with self.assertRaisesRegex(ValueError, "wall-time"):
            launcher._capture_bounded(cmd, wall_seconds=0.2)

    def test_bounded_reader_accepts_short_valid_child_output(self):
        cmd = [sys.executable, "-c", "print('ok')"]
        rc, payload = launcher._capture_bounded(cmd, wall_seconds=3)
        self.assertEqual((rc, payload), (0, b"ok\n"))



if __name__ == "__main__":
    unittest.main()
