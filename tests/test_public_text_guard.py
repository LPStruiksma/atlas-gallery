"""Tests for scripts/public_text_guard.py (atlas-gallery#533).

Every value below is invented. This file lives under tests/, which the guard
allows by path, so its synthetic positives do not trip the guard itself.
"""

from __future__ import annotations

import io
import json
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

import public_text_guard as guard  # noqa: E402

# kind -> (positive lines, negative lines)
CASES = {
    "email": (
        ["mail me at jan.jansen@gmail.com please", "Contact: someone+tag@kpn-mail.nl"],
        [
            "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>",
            "author 12345+bot@users.noreply.github.com",
            "use person@example.com in docs",
            "@Composable fun Grid() = Unit",
            "override val cancellation get() = this@HostedEditJob.cancellation",
            "JOB androidx.work.systemjobscheduler:u0a150/4 @androidx.work.systemjobscheduler@nl.atlas.gallery/x",
        ],
    ),
    "phone": (
        ["bel 06-12345678", "call +31 6 1234 5678", "office 020 1234567", "+1 (415) 555-0134"],
        ["version 1.2.3+45", "sum = a +1 2", "size 1234567 bytes", "id 0612"],
    ),
    "street-address": (
        ["I live at Kerkstraat 12 now", "221 Baker Street", "post 1234 AB Utrecht"],
        ["the street layout test", "Grid 12 columns", "4096 KB Free"],
    ),
    "personal-path": (
        [
            "/storage/emulated/0/DCIM/Camera/IMG_0001.jpg",
            "adb pull /sdcard/Download/log.txt",
            r"C:\Users\jdoe\Documents\atlas",
            "/home/alice/projects/atlas",
            "/Users/bob/Desktop/x.png",
        ],
        [
            'val prefix = "/storage/emulated/0/"',
            "/home/user/atlas-gallery/scripts",
            "/home/runner/work/atlas-gallery",
            r"C:\Users\<name>\atlas",
            "app/src/main/res/drawable",
        ],
    ),
    "exif-gps": (
        [
            "GPS Latitude : 52 deg 22' 12.78\" N",
            "GPSLongitude=4.8952",
            "location 52.370216, 4.895168",
            "ExifTool Version Number : 12.40",
        ],
        [
            "ExifInterface.TAG_GPS_LATITUDE is read",
            "the GPS toggle in Settings",
            "scale 1.5, 2.0",
            "version 52.3, 4.8",
            '"arcface_112_v1": [[0.35473214, 0.45658929], [0.64526786, 0.45658929]],',
            "TEMPLATE = np.array([[38.2946, 51.6963], [73.5318, 51.5014]])",
        ],
    ),
    "content-uri": (
        ["content://media/external/images/media/982341", "uri=content://media/external_primary/video/media/42"],
        ["content://media/external/images/media", "MediaStore content:// URIs only", "content://com.example/ids"],
    ),
    "chat-session-url": (
        [
            "see https://chatgpt.com/c/6740a1b2-0c3d-8000-9a1b-123456789abc",
            "https://chatgpt.com/share/6740a1b2-0c3d-8000-9a1b-123456789abc",
            "https://claude.ai/chat/0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0",
            "https://chatgpt.com/g/g-p-abc123-atlas/c/6740a1b2-0c3d-8000",
        ],
        [
            "Claude-Session: https://claude.ai/code/session_01AbCdEfGhIjKlMn",
            "https://claude.ai/code/project/chan_01xyz?thread=cmsg_01abc",
            "ChatGPT reviewed this",
            "https://chatgpt.com/codex/cloud/settings/usage",
        ],
    ),
}


class KindCases(unittest.TestCase):
    def test_every_kind_has_cases(self):
        self.assertEqual(set(CASES), set(guard.KINDS))

    def test_positive_cases(self):
        for kind, (positives, _) in CASES.items():
            for line in positives:
                with self.subTest(kind=kind, line=line):
                    self.assertIn(kind, guard.scan_line(line))

    def test_negative_cases(self):
        for kind, (_, negatives) in CASES.items():
            for line in negatives:
                with self.subTest(kind=kind, line=line):
                    self.assertNotIn(kind, guard.scan_line(line))

    def test_allow_marker_skips_every_kind(self):
        for kind, (positives, _) in CASES.items():
            for line in positives:
                with self.subTest(kind=kind, line=line):
                    self.assertEqual(guard.scan_line(f"{line}  # guard: allow-public"), [])


DIFF = """\
diff --git a/docs/notes.md b/docs/notes.md
index 1111111..2222222 100644
--- a/docs/notes.md
+++ b/docs/notes.md
@@ -1,3 +1,4 @@
 heading
-old line jan@gmail.com
+new line with jan@gmail.com
 context
+safe line
@@ -10,2 +11,3 @@ section
 kept
+uri content://media/external/images/media/77
diff --git a/app/src/androidTest/java/X.kt b/app/src/androidTest/java/X.kt
--- a/app/src/androidTest/java/X.kt
+++ b/app/src/androidTest/java/X.kt
@@ -0,0 +1 @@
+val uri = "content://media/external/images/media/5"
diff --git a/old.txt b/old.txt
deleted file mode 100644
--- a/old.txt
+++ /dev/null
@@ -1 +0,0 @@
-gone jan@gmail.com
"""


class DiffScan(unittest.TestCase):
    def test_only_added_lines_outside_test_folders(self):
        findings = guard.scan_diff(DIFF)
        self.assertEqual(
            [(f.kind, f.source, f.line) for f in findings],
            [("email", "docs/notes.md", 2), ("content-uri", "docs/notes.md", 12)],
        )

    def test_allowed_paths(self):
        for path in [
            "test/additive-regressions/x.mjs",
            "tests/test_public_text_guard.py",
            "app/src/test/java/A.kt",
            "app/src/androidTest/java/B.kt",
            "scripts/testdata/sample.csv",
            "scripts/test_usage_rank.py",
            "tools/identity_fusion/test_evidence_store.py",
            "tools/identity_fusion/FaceScanEngineSyntheticTest.kt",
        ]:
            with self.subTest(path=path):
                self.assertTrue(guard.is_allowed_path(path))
        for path in ["docs/notes.md", "app/src/main/java/A.kt", "scripts/usage_rank.py", "README.md"]:
            with self.subTest(path=path):
                self.assertFalse(guard.is_allowed_path(path))


class Output(unittest.TestCase):
    SECRET = "jan.jansen@gmail.com"

    def test_report_never_prints_matched_text(self):
        findings = guard.scan_text(f"first\nmail {self.SECRET}\n", "issue body")
        self.assertEqual([(f.kind, f.line) for f in findings], [("email", 2)])
        for as_json in (False, True):
            text = guard.report(findings, as_json)
            self.assertNotIn(self.SECRET, text)
            self.assertNotIn("jansen", text)

    def test_cli_event_json_and_exit_code(self):
        event = {"comment": {"body": f"hello\nreach me: {self.SECRET}"}, "issue": {"title": "t", "body": ""}}
        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as handle:
            json.dump(event, handle)
        buffer = io.StringIO()
        with redirect_stdout(buffer):
            code = guard.main(["--event", handle.name, "--json"])
        out = json.loads(buffer.getvalue())
        self.assertEqual(code, 1)
        self.assertEqual(out["kinds"], ["email"])
        self.assertEqual(out["findings"][0]["source"], "comment")
        self.assertEqual(out["findings"][0]["line"], 2)
        self.assertNotIn(self.SECRET, buffer.getvalue())

    def test_issue_event_scans_title_and_body(self):
        event = {"issue": {"title": "Crash at /sdcard/DCIM/a.jpg", "body": "ok"}}
        findings = guard.scan_event(event)
        self.assertEqual([(f.kind, f.line) for f in findings], [("personal-path", 1)])

    def test_clean_event_exits_zero(self):
        self.assertEqual(guard.scan_event({"pull_request": {"title": "Fix grid", "body": "No data here."}}), [])
        self.assertEqual(guard.scan_event({"review": {"body": None}, "pull_request": {}}), [])


class CommitScan(unittest.TestCase):
    def test_commit_messages_in_range(self):
        with tempfile.TemporaryDirectory() as repo:
            def git(*args):
                return subprocess.run(
                    ["git", "-C", repo, "-c", "user.name=t", "-c", "user.email=t@example.com", *args],
                    check=True, capture_output=True, text=True,
                ).stdout.strip()

            git("init", "-q")
            git("commit", "-q", "--allow-empty", "-m", "base")
            base = git("rev-parse", "HEAD")
            git("commit", "-q", "--allow-empty", "-m", "clean\n\nCo-Authored-By: X <noreply@anthropic.com>")
            git("commit", "-q", "--allow-empty", "-m", "subject\n\nsee https://chatgpt.com/share/6740a1b2-0c3d")
            head = git("rev-parse", "HEAD")
            cwd = Path.cwd()
            try:
                import os

                os.chdir(repo)
                findings = guard.scan_commits(f"{base}..{head}")
            finally:
                os.chdir(cwd)
        self.assertEqual([(f.kind, f.line) for f in findings], [("chat-session-url", 3)])
        self.assertTrue(findings[0].source.startswith("commit " + head[:12]))


if __name__ == "__main__":
    unittest.main()
