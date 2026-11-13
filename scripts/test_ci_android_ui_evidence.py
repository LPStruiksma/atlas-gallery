"""Host-only contract tests for scripts/ci_ui_evidence.sh (#588, #586, #596).

Runs the real production script with a fake adb placed first on PATH. It covers
directory discovery on the device (LF and CRLF listings, unsafe names,
duplicates, empty or missing directory, a failed listing that still printed
names, a failed copy) and the declared-set check in "verify". It is not
emulator, instrumentation or KVM acceptance.
Run: python3 -m unittest scripts/test_ci_android_ui_evidence.py -v
"""
from __future__ import annotations

import os
from pathlib import Path
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("ci_ui_evidence.sh").resolve()
DECLARED = Path(__file__).with_name("ui-evidence.d")
PNG = bytes.fromhex("89504e470d0a1a0a") + b"x" * 1100
PREFIX = "IMG_20010101_000000_"

FAKE_ADB = r"""#!/usr/bin/env python3
import os, sys
from pathlib import Path
a = sys.argv[1:]
remote = Path(os.environ["FAKE_REMOTE"])
if a[:3] == ["shell", "test", "-d"]:
    sys.exit(0 if os.environ["FAKE_DIR_EXISTS"] == "1" else 1)
if a[:3] == ["shell", "ls", "-1"]:
    sys.stdout.buffer.write(Path(os.environ["FAKE_LISTING"]).read_bytes())
    sys.exit(int(os.environ["FAKE_LIST_STATUS"]))
if a[:2] == ["exec-out", "cat"]:
    name = a[-1].rsplit("/", 1)[-1]
    p = remote / name
    if not p.is_file():
        sys.exit(2)
    sys.stdout.buffer.write(p.read_bytes())
    with open(os.environ["FAKE_COPIES"], "a") as f:
        f.write(name + "\n")
    sys.exit(int(os.environ["FAKE_READ_STATUS"]))
sys.exit(4)
"""


def remote_names(*names: str) -> dict[str, bytes]:
    return {PREFIX + n + ".png": PNG for n in names}


class EvidenceCollectionTest(unittest.TestCase):
    def run_script(self, mode: str, *, files: dict[str, bytes] | None = None,
                   listing: bytes | None = None, list_status: int = 0,
                   dir_exists: bool = True, read_status: int = 0,
                   local: dict[str, bytes] | None = None,
                   declared: dict[str, str] | None = None,
                   extra_env: dict[str, str] | None = None):
        files = files or {}
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            remote = root / "remote"
            remote.mkdir()
            for name, data in files.items():
                (remote / name).write_bytes(data)
            if listing is None:
                listing = "".join(n + "\n" for n in sorted(files)).encode()
            (root / "listing.bin").write_bytes(listing)
            fake = root / "bin" / "adb"
            fake.parent.mkdir()
            fake.write_text(FAKE_ADB, encoding="utf-8")
            fake.chmod(0o755)
            work = root / "work"
            (work / "ui-evidence").mkdir(parents=True)
            for name, data in (local or {}).items():
                (work / "ui-evidence" / name).write_bytes(data)
            env = dict(os.environ)
            env.update(PATH=f"{fake.parent}:{env.get('PATH', '')}",
                       FAKE_REMOTE=str(remote), FAKE_LISTING=str(root / "listing.bin"),
                       FAKE_LIST_STATUS=str(list_status),
                       FAKE_DIR_EXISTS="1" if dir_exists else "0",
                       FAKE_READ_STATUS=str(read_status),
                       FAKE_COPIES=str(root / "copies.log"))
            if declared is not None:
                ddir = root / "declared"
                ddir.mkdir()
                for fname, text in declared.items():
                    (ddir / fname).write_text(text, encoding="utf-8")
                env["ATLAS_UI_EVIDENCE_DECLARED_DIR"] = str(ddir)
            env.update(extra_env or {})
            proc = subprocess.run(["bash", str(SCRIPT), mode], cwd=work, env=env,
                                  text=True, capture_output=True, timeout=30)
            copies_log = root / "copies.log"
            copies = copies_log.read_text().splitlines() if copies_log.exists() else []
            outputs = sorted(p.name for p in (work / "ui-evidence").glob("*.png"))
            return proc, copies, outputs

    # --- discovery (pull / pull-some) ---

    def test_lf_listing_pulls_every_safe_name(self):
        files = remote_names("viewer-after-swipe", "selection_556-header")
        proc, copies, outputs = self.run_script("pull", files=files)
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertEqual(copies, sorted(files))
        self.assertEqual(outputs, ["selection_556-header.png", "viewer-after-swipe.png"])
        self.assertIn("Pulled 2 UI-evidence screenshots", proc.stdout)

    def test_crlf_listing(self):
        files = remote_names("viewer-after-swipe")
        listing = (PREFIX + "viewer-after-swipe.png\r\n").encode()
        proc, copies, outputs = self.run_script("pull", files=files, listing=listing)
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertEqual(outputs, ["viewer-after-swipe.png"])

    def test_unsafe_and_unrelated_names_are_not_copied(self):
        good = PREFIX + "good_one-2.png"
        listing = "\n".join([
            good, "../" + good, PREFIX + "bad name.png", PREFIX + "bad.png;touch-evil",
            "unrelated.png", PREFIX + "bad.PNG", PREFIX + "name.png/../other.png",
            PREFIX + ".png", PREFIX + "-leading-dash.png", PREFIX + "$(touch x).png",
        ]).encode() + b"\n"
        proc, copies, outputs = self.run_script("pull", files={good: PNG}, listing=listing)
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertEqual(copies, [good])
        self.assertEqual(outputs, ["good_one-2.png"])
        self.assertIn("Ignoring non-evidence file", proc.stderr)

    def test_duplicate_listing_entries_copied_once(self):
        name = PREFIX + "same-name.png"
        listing = (name + "\n" + name + "\r\n").encode()
        proc, copies, _ = self.run_script("pull", files={name: PNG}, listing=listing)
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertEqual(copies, [name])

    def test_empty_listing_fails_pull_but_not_pull_some(self):
        proc, copies, _ = self.run_script("pull", listing=b"")
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("No ATLAS UI screenshot evidence", proc.stdout)
        self.assertEqual(copies, [])
        proc, copies, _ = self.run_script("pull-some", listing=b"")
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertIn("Pulled 0 UI-evidence screenshots", proc.stdout)

    def test_missing_directory_is_zero_shots(self):
        proc, copies, _ = self.run_script("pull-some", dir_exists=False)
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertEqual(copies, [])
        proc, _, _ = self.run_script("pull", dir_exists=False)
        self.assertNotEqual(proc.returncode, 0)

    def test_failed_listing_with_valid_stdout_fails(self):
        files = remote_names("viewer-after-swipe")
        for mode in ("pull", "pull-some"):
            proc, copies, _ = self.run_script(mode, files=files, list_status=1)
            self.assertNotEqual(proc.returncode, 0, mode)
            self.assertIn("Could not list", proc.stderr)
            self.assertEqual(copies, [])

    def test_listed_but_missing_file_fails(self):
        listing = (PREFIX + "gone.png\n").encode()
        proc, _, _ = self.run_script("pull-some", listing=listing)
        self.assertNotEqual(proc.returncode, 0)

    def test_failed_adb_read_fails(self):
        proc, copies, _ = self.run_script(
            "pull-some", files=remote_names("viewer-after-swipe"), read_status=3)
        self.assertNotEqual(proc.returncode, 0)
        self.assertEqual(copies, [PREFIX + "viewer-after-swipe.png"])

    # --- verify against the declared set ---

    def test_verify_passes_on_exact_declared_set(self):
        proc, _, _ = self.run_script(
            "verify", local={"a.png": PNG, "b-2.png": PNG},
            declared={"one.txt": "# feature one\na\n", "two.txt": "b-2  # trailing comment\n\n"})
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertIn("PASS: 2 real PNG screenshots", proc.stdout)
        self.assertIn("::group::UI evidence base64 a.png", proc.stdout)
        self.assertIn("::group::UI evidence base64 b-2.png", proc.stdout)

    def test_verify_rejects_missing_declared_shot(self):
        proc, _, _ = self.run_script("verify", local={"a.png": PNG},
                                     declared={"x.txt": "a\nb\n"})
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("Missing UI-evidence PNGs: ['b']", proc.stderr)

    def test_verify_rejects_undeclared_shot(self):
        proc, _, _ = self.run_script("verify", local={"a.png": PNG, "new.png": PNG},
                                     declared={"x.txt": "a\n"})
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("Undeclared UI-evidence PNGs", proc.stderr)
        self.assertIn("'new'", proc.stderr)

    def test_verify_rejects_name_declared_twice(self):
        proc, _, _ = self.run_script("verify", local={"a.png": PNG},
                                     declared={"x.txt": "a\n", "y.txt": "a\n"})
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("declared in both", proc.stderr)

    def test_verify_rejects_bad_declared_name(self):
        proc, _, _ = self.run_script("verify", local={"a.png": PNG},
                                     declared={"x.txt": "a\nbad name\n"})
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("Bad UI-evidence name", proc.stderr)

    def test_verify_rejects_empty_declaration(self):
        proc, _, _ = self.run_script("verify", declared={"x.txt": "# nothing\n"})
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("No UI-evidence names declared", proc.stderr)

    def test_verify_rejects_invalid_png(self):
        for data in (b"not a png" * 200, PNG[:100]):
            proc, _, _ = self.run_script("verify", local={"a.png": data},
                                         declared={"x.txt": "a\n"})
            self.assertNotEqual(proc.returncode, 0)
            self.assertIn("Invalid image", proc.stderr)

    def test_verify_rejects_empty_evidence_with_real_declarations(self):
        proc, _, _ = self.run_script("verify")
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("Missing UI-evidence PNGs", proc.stderr)

    def test_real_declarations_are_valid_and_unique(self):
        local = {}
        for f in sorted(DECLARED.glob("*.txt")):
            for raw in f.read_text(encoding="utf-8").splitlines():
                n = raw.split("#", 1)[0].strip()
                if n:
                    local[n + ".png"] = PNG
        self.assertGreater(len(local), 0)
        proc, _, _ = self.run_script("verify", local=local)
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertIn(f"PASS: {len(local)} real PNG screenshots", proc.stdout)

    def test_verify_fails_closed_with_python_optimizations(self):
        # #637: PYTHONOPTIMIZE strips assert statements; verify must still fail.
        optimized = {"PYTHONOPTIMIZE": "1"}
        cases = {
            "empty pull dir": dict(),
            "missing shot": dict(local={"a.png": PNG}, declared={"x.txt": "a\nb\n"}),
            "undeclared shot": dict(local={"a.png": PNG, "new.png": PNG},
                                    declared={"x.txt": "a\n"}),
            "declared twice": dict(local={"a.png": PNG},
                                   declared={"x.txt": "a\n", "y.txt": "a\n"}),
            "bad name": dict(local={"a.png": PNG}, declared={"x.txt": "a\nbad name\n"}),
            "no declarations": dict(declared={"x.txt": "# nothing\n"}),
            "invalid png": dict(local={"a.png": PNG[:100]}, declared={"x.txt": "a\n"}),
        }
        for label, kwargs in cases.items():
            with self.subTest(label):
                proc, _, _ = self.run_script("verify", extra_env=optimized, **kwargs)
                self.assertEqual(proc.returncode, 1, proc.stdout + proc.stderr)
                self.assertNotIn("PASS:", proc.stdout)

    def test_unknown_mode_fails_closed(self):
        proc, copies, _ = self.run_script("not-a-mode")
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("usage:", proc.stderr)
        self.assertEqual(copies, [])


if __name__ == "__main__":
    unittest.main()
