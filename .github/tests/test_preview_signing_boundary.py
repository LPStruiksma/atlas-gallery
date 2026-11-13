"""Fail-closed source guard: preview PR Gradle MUST NEVER receive signing secrets.

Synthetic static tests, no network/keys/APK/phone. This guard is a regression
alarm, not a sandbox for untrusted PR code: keeping permanent secrets absent
from the workflow remains the actual trust boundary.
"""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]


class PreviewSigningBoundaryTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workflow = (ROOT / ".github/workflows/build-apk.yml").read_text(
            encoding="utf-8"
        )

    def test_pr_or_push_preview_never_interpolates_any_github_secret(self):
        self.assertIn("\n  pull_request:\n", self.workflow)
        self.assertIn("\n  push:\n", self.workflow)
        self.assertIn("\n  workflow_dispatch:\n", self.workflow)
        self.assertNotRegex(self.workflow, r"\$\{\{\s*secrets\.")
        self.assertNotIn("pull_request_target:", self.workflow)
        self.assertNotIn("ATLAS_STABLE_DEBUG_", self.workflow)

    def test_preview_does_not_materialize_or_export_permanent_key(self):
        for forbidden in (
            "ATLAS_KEYSTORE_B64",
            "base64 --decode",
            "atlas-dev-signing.jks",
            "ATLAS_STABLE_SIGNING_PATH=",
            "secrets.",
        ):
            with self.subTest(forbidden=forbidden):
                self.assertNotIn(forbidden, self.workflow)

    def test_untrusted_checkout_is_read_only_and_cannot_persist_token(self):
        self.assertIn("permissions:\n  contents: read\n", self.workflow)
        self.assertIn("persist-credentials: false", self.workflow)
        self.assertIn(
            "github.event.pull_request.head.sha || github.sha", self.workflow
        )

    def test_preview_still_compiles_without_signer_materialization(self):
        self.assertIn(
            "gradle :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest",
            self.workflow,
        )
        self.assertIn("Preview-only signing safety notice", self.workflow)
        self.assertIn("runner-local debug certificate", self.workflow)
        self.assertLess(
            self.workflow.index("Preview-only signing safety notice"),
            self.workflow.index("run: gradle :app:testDebugUnitTest"),
        )

    def test_manual_rc_api34_35_and_distinct_head_guard(self):
        # One API level per run avoids spending twice for every PR check.
        for line in (
            "api_level:",
            "api-level: ${{ inputs.api_level || '35' }}",
            "- '34'",
            "- '35'",
            "github.event.pull_request.base.sha || 'main'",
            "Refuse falsely identical baseline and candidate APK sources",
            'if [[ "$rc_sha" == "$baseline_sha" ]]; then',
        ):
            with self.subTest(line=line):
                self.assertIn(line, self.workflow)
        self.assertEqual(
            self.workflow.count("runs-on: ubuntu-24.04"), 3,
            "Keep the Android build, the emulator shards and the emulator-ui "
            "aggregate (#588) on the pinned acceptance host",
        )

    def test_environment_values_are_rejected_without_logging_them(self):
        for key in (
            "ATLAS_STABLE_SIGNING_PATH",
            "ATLAS_STABLE_SIGNING_STORE_PASSWORD",
            "ATLAS_STABLE_SIGNING_KEY_ALIAS",
            "ATLAS_STABLE_SIGNING_KEY_PASSWORD",
        ):
            self.assertIn(key, self.workflow)
        self.assertIn('if [[ -n "${!key:-}" ]]', self.workflow)
        self.assertNotRegex(self.workflow, r"echo [^\n]*\$ATLAS_")


if __name__ == "__main__":
    unittest.main()
