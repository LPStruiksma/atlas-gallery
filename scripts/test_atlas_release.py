#!/usr/bin/env python3
"""Synthetic git repositories only: version bumps, build numbers, notes, app changelog."""
from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import atlas_release as ar  # noqa: E402


class Repo:
    def __init__(self, root: Path):
        self.root = root
        self.n = 0
        self.git("init", "-q", "-b", "main")

    def git(self, *args: str, when: str = "2026-11-20T03:00:00+00:00") -> str:
        env = {**os.environ, "GIT_AUTHOR_NAME": "t", "GIT_AUTHOR_EMAIL": "t@example.invalid",
               "GIT_COMMITTER_NAME": "t", "GIT_COMMITTER_EMAIL": "t@example.invalid",
               "GIT_AUTHOR_DATE": when, "GIT_COMMITTER_DATE": when,
               "GIT_CONFIG_GLOBAL": "/dev/null", "GIT_CONFIG_SYSTEM": "/dev/null"}
        return subprocess.run(["git", "-C", str(self.root), *args], check=True,
                              capture_output=True, text=True, env=env).stdout

    def commit(self, subject: str, when: str, files: dict[str, str] | None = None) -> str:
        self.n += 1
        (self.root / "app.txt").write_text(f"change {self.n}\n")
        for name, text in (files or {}).items():
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text)
        self.git("add", "-A")
        self.git("commit", "-q", "-m", subject, when=when)
        return self.git("rev-parse", "HEAD").strip()

    def tag(self, name: str) -> None:
        self.git("tag", name)

    def plan(self):
        return ar.plan(ar.Git(self.root))


def frag(kind: str, *entries: str) -> str:
    return f"type: {kind}\n" + "".join(f"- {e}\n" for e in entries)


class ReleaseTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.repo = Repo(Path(self.tmp.name))
        self.repo.commit("Start (#1)", "2026-11-20T02:00:00+00:00")
        self.repo.tag("v0.3.261120028")

    def tearDown(self):
        self.tmp.cleanup()

    def test_first_semantic_release_is_0_4_0_and_above_legacy_build(self):
        self.repo.commit("Add sheet (#598)", "2026-11-20T02:01:00+00:00",
                         {"changelog.d/598-sheet.md": frag("fix", "Sheet fixed.")})
        nxt, history = self.repo.plan()
        self.assertEqual(nxt.version, (0, 4, 0))
        # Commit time gives 261120020, below the legacy build: chain lifts it.
        self.assertEqual(nxt.code, 261120029)
        self.assertEqual(history, [])
        self.assertEqual(nxt.unlisted, [])

    def test_bumps_follow_strongest_fragment_type(self):
        self.repo.commit("A (#10)", "2026-11-20T04:00:00+00:00")
        self.repo.tag("v0.4.0")
        self.repo.commit("Fix (#11)", "2026-11-20T05:00:00+00:00",
                         {"changelog.d/11-fix.md": frag("fix", "Fixed.")})
        self.assertEqual(self.repo.plan()[0].version, (0, 4, 1))
        self.repo.commit("Feature (#12)", "2026-11-20T05:10:00+00:00",
                         {"changelog.d/12-feat.md": frag("feature", "New thing.")})
        self.assertEqual(self.repo.plan()[0].version, (0, 5, 0))
        self.repo.commit("Big (#13)", "2026-11-20T05:20:00+00:00",
                         {"changelog.d/13-big.md": frag("major", "One point oh.")})
        self.assertEqual(self.repo.plan()[0].version, (1, 0, 0))

    def test_merge_without_fragment_is_patch_and_listed(self):
        self.repo.commit("A (#10)", "2026-11-20T04:00:00+00:00")
        self.repo.tag("v0.4.0")
        self.repo.commit("Tidy tests (#20)", "2026-11-20T05:00:00+00:00")
        nxt, _ = self.repo.plan()
        self.assertEqual(nxt.version, (0, 4, 1))
        self.assertEqual(nxt.unlisted, ["Tidy tests (#20)"])
        notes = ar.notes_markdown(nxt, "ab" * 32)
        self.assertIn("### Other merged changes\n- Tidy tests (#20)", notes)
        self.assertIn(f"Build {nxt.code}", notes)
        self.assertIn("Signer certificate SHA-256: " + "ab" * 32, notes)

    def test_fragment_named_after_pr_covers_that_pr(self):
        self.repo.commit("Merge PR #30: something", "2026-11-20T04:00:00+00:00")
        self.repo.commit("Describe (#31)", "2026-11-20T04:10:00+00:00",
                         {"changelog.d/30-something.md": frag("improvement", "Better.")})
        self.assertEqual(self.repo.plan()[0].unlisted, [])

    def test_build_numbers_chain_and_never_repeat(self):
        self.repo.commit("A (#10)", "2026-11-20T04:00:00+00:00")
        self.repo.tag("v0.4.0")
        self.repo.commit("B (#11)", "2026-11-20T04:01:00+00:00")  # same 6-minute bucket
        self.repo.tag("v0.4.1")
        self.repo.commit("C (#12)", "2026-11-20T04:02:00+00:00")
        nxt, history = self.repo.plan()
        self.assertEqual([r.code for r in history], [261120041, 261120040])
        self.assertEqual(nxt.code, 261120042)
        self.assertEqual(nxt.version, (0, 4, 2))

    def test_refuses_to_release_an_already_tagged_head(self):
        self.repo.commit("A (#10)", "2026-11-20T04:00:00+00:00")
        self.repo.tag("v0.4.0")
        with self.assertRaisesRegex(ar.ReleaseError, "already points"):
            self.repo.plan()

    def test_app_changelog_has_history_and_hides_internal(self):
        self.repo.commit("A (#10)", "2026-11-20T04:00:00+00:00",
                         {"changelog.d/10-a.md": frag("feature", "Albums.")})
        self.repo.tag("v0.4.0")
        self.repo.commit("B (#11)", "2026-11-21T09:00:00+00:00",
                         {"changelog.d/11-b.md": frag("internal", "Tests."),
                          "changelog.d/11-c.md": frag("fix", "Crash fixed.")})
        nxt, history = self.repo.plan()
        data = ar.app_changelog(nxt, history)
        self.assertEqual([r["version"] for r in data["releases"]], ["0.4.1", "0.4.0"])
        self.assertEqual(data["releases"][0]["groups"],
                         [{"type": "fix", "title": "Fixed", "entries": ["Crash fixed."]}])
        self.assertEqual(data["releases"][0]["date"], "2026-11-21")
        self.assertEqual(data["releases"][1]["groups"][0]["entries"], ["Albums."])
        json.dumps(data)
        self.assertIn("### Under the hood\n- Tests.", ar.notes_markdown(nxt))

    def test_ignores_tags_not_on_head_history(self):
        base = self.repo.git("rev-parse", "HEAD").strip()
        self.repo.commit("Side (#5)", "2026-11-20T04:00:00+00:00")
        self.repo.tag("v0.9.0")
        self.repo.git("checkout", "-q", "-b", "other", base)
        self.repo.commit("Main (#6)", "2026-11-20T04:10:00+00:00")
        self.assertEqual(self.repo.plan()[0].version, (0, 4, 0))


class FragmentTest(unittest.TestCase):
    def test_valid(self):
        f = ar.parse_fragment("changelog.d/1-x.md", "type: fix\n- One.\n  continued.\n- Two.\n")
        self.assertEqual((f.type, f.entries), ("fix", ["One. continued.", "Two."]))

    def test_invalid(self):
        for text, msg in [("", "empty"), ("- x\n", "first line"), ("type: feat\n- x\n", "unknown type"),
                          ("type: fix\n", "at least one"), ("type: fix\nplain\n", "start with"),
                          ("type: fix\n" + "- x\n" * 9, "at most"),
                          ("type: fix\n- " + "x" * 301 + "\n", "characters")]:
            with self.assertRaisesRegex(ar.ReleaseError, msg):
                ar.parse_fragment("changelog.d/x.md", text)

    def test_repository_fragments_are_valid(self):
        self.assertEqual(ar.check(Path(__file__).resolve().parents[1]), 0)

    def test_bump_and_code(self):
        self.assertEqual(ar.bump((0, 4, 3), {"fix", "internal"}), (0, 4, 4))
        self.assertEqual(ar.bump((0, 4, 3), {"fix", "feature"}), (0, 5, 0))
        import datetime as dt
        when = dt.datetime.fromisoformat("2026-11-20T04:22:08+01:00")
        self.assertEqual(ar.code_for(when), 261120033)


if __name__ == "__main__":
    unittest.main()
