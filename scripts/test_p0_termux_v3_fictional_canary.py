"""Host-only regression for native Termux canary GH endpoint construction.

No credentials, network, APKs, phone access, signing or CI needed.
"""
import importlib.util
import json
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch

MODULE = Path(__file__).with_name('p0_termux_v3_fictional_canary.py')
SPEC = importlib.util.spec_from_file_location('atlas_termux_v3_fictional', MODULE)
CANARY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CANARY)


class GitHubApiEndpointTests(unittest.TestCase):
    def test_private_repo_root_must_have_no_trailing_slash(self):
        response = subprocess.CompletedProcess([], 0, json.dumps({
            'id': CANARY.REPO_ID,
            'full_name': CANARY.REPO,
            'private': True,
        }).encode(), b'')
        with patch.object(CANARY.subprocess, 'run', return_value=response) as call:
            value = CANARY.api('')
        self.assertEqual(value['id'], CANARY.REPO_ID)
        self.assertEqual(call.call_args.args[0],
                         ['gh', 'api', 'repos/LPStruiksma/atlas-gallery'])
        self.assertTrue(call.call_args.kwargs['capture_output'])

    def test_nested_run_metadata_endpoint_is_unchanged(self):
        response = subprocess.CompletedProcess(
            [], 0, json.dumps({'id': CANARY.RUN_ID}).encode(), b'')
        with patch.object(CANARY.subprocess, 'run', return_value=response) as call:
            value = CANARY.api('actions/runs/39649561900')
        self.assertEqual(value['id'], CANARY.RUN_ID)
        self.assertEqual(call.call_args.args[0], [
            'gh', 'api',
            'repos/LPStruiksma/atlas-gallery/actions/runs/39649561900'])

    def test_authenticated_404_remains_fail_closed(self):
        response = subprocess.CompletedProcess(
            [], 1, b'', b'HTTP 404 Not Found')
        with patch.object(CANARY.subprocess, 'run', return_value=response):
            with self.assertRaisesRegex(RuntimeError,
                                        'authenticated GitHub metadata request failed'):
                CANARY.api('')



class ApkBadgingTests(unittest.TestCase):
    """Real historical AndroidTest APK omits manifest versionCode."""

    @staticmethod
    def fake_badging(text, code=0):
        return subprocess.CompletedProcess(
            [], code, text, '')

    def validate(self, output, pkg):
        with patch.object(CANARY.subprocess, 'run',
                          return_value=self.fake_badging(output)) as call:
            CANARY.check_apk(Path('/fictional/read-only.apk'), pkg)
        self.assertEqual(call.call_args.args[0][:3],
                         ['aapt', 'dump', 'badging'])

    def test_actual_androidtest_package_without_version_code_accepted(self):
        self.validate(
            "package: name='nl.atlas.gallery.test' versionName=''\n",
            'nl.atlas.gallery.test')

    def test_actual_androidtest_package_with_blank_version_code_accepted(self):
        self.validate(
            "package: name='nl.atlas.gallery.test' versionCode='' versionName=''\n",
            'nl.atlas.gallery.test')

    def test_androidtest_wrong_package_fails_closed(self):
        with patch.object(CANARY.subprocess, 'run',
                          return_value=self.fake_badging(
                              "package: name='nl.atlas.unrelated.test'\n")):
            with self.assertRaisesRegex(RuntimeError, 'unexpected genuine APK package'):
                CANARY.check_apk(Path('/fictional/test.apk'), 'nl.atlas.gallery.test')

    def test_real_app_must_keep_exact_version_three(self):
        self.validate("package: name='nl.atlas.gallery' versionCode='3' versionName=''\n",
                      'nl.atlas.gallery')
        for text in ("package: name='nl.atlas.gallery' versionCode='4'\n",
                     "package: name='nl.atlas.gallery' versionCode=''\n",
                     "package: name='nl.atlas.gallery'\n"):
            with self.subTest(text=text):
                with patch.object(CANARY.subprocess, 'run',
                                  return_value=self.fake_badging(text)):
                    with self.assertRaisesRegex(RuntimeError, 'unexpected genuine app APK versionCode'):
                        CANARY.check_apk(Path('/fictional/app.apk'), 'nl.atlas.gallery')

    def test_aapt_nonzero_still_fails_closed(self):
        with patch.object(CANARY.subprocess, 'run',
                          return_value=self.fake_badging('', code=1)):
            with self.assertRaisesRegex(RuntimeError, 'aapt cannot read real APK'):
                CANARY.check_apk(Path('/fictional/app.apk'), 'nl.atlas.gallery')



class NativeApksignerVerificationTests(unittest.TestCase):
    CERT = 'a' * 64

    @staticmethod
    def verify_response(v2='true', v3='false', status=0, diagnostics='',
                        label='Signer #1', second=None):
        output = (
            'Verifies\n'
            f'Verified using v2 scheme (APK Signature Scheme v2): {v2}\n'
            f'Verified using v3 scheme (APK Signature Scheme v3): {v3}\n'
            f'{label}{" " if label.startswith("Signer") else ": "}certificate DN: '
            'CN=FICTIONAL-ONLY-DELETE, O=ATLAS-TOOL-SMOKE\n'
            f'{label}{" " if label.startswith("Signer") else ": "}certificate SHA-256 digest: '
            + ('a' * 64) + '\n'
        )
        if second:
            output += f'V3.0 Signer: certificate SHA-256 digest: {second}\n'
        return subprocess.CompletedProcess([], status, output, diagnostics)

    def verify_cert(self, response):
        with patch.object(CANARY.subprocess, 'run', return_value=response) as call:
            actual = CANARY.fingerprint(
                Path('/fake/fictional.apk'), 'FICTIONAL-ONLY-DELETE')
        self.assertEqual(actual, self.CERT)
        args = call.call_args.args[0]
        self.assertEqual(args[:4], ['apksigner', 'verify', '--verbose',
                                     '--print-certs'])
        self.assertIn('--min-sdk-version', args)

    def test_legacy_apksigner_v2_cert_label_passes(self):
        self.verify_cert(self.verify_response())

    def test_sdk37_v2_cert_label_passes(self):
        self.verify_cert(self.verify_response(label='V2 Signer'))

    def test_sdk37_v3_only_is_valid_on_min_sdk_29(self):
        self.verify_cert(self.verify_response(
            v2='false', v3='true', label='V3.0 Signer'))

    def test_v2_and_v3_same_cert_passes(self):
        self.verify_cert(self.verify_response(
            v2='true', v3='true', label='V2 Signer', second='a' * 64))

    def test_different_cert_between_schemes_rejected(self):
        with patch.object(CANARY.subprocess, 'run',
                          return_value=self.verify_response(
                              v2='true', v3='true', label='V2 Signer',
                              second='f' * 64)):
            with self.assertRaisesRegex(RuntimeError,
                                        'exactly one signing certificate identity'):
                CANARY.fingerprint(Path('/fake/fictional.apk'),
                                   'FICTIONAL-ONLY-DELETE')

    def test_unrecognized_cert_line_rejected(self):
        response = self.verify_response(v2='false', v3='true',
                                        label='V3.0 Signer')
        bad = response.stdout.replace('V3.0 Signer:', 'OTHER SIGNER:')
        with patch.object(CANARY.subprocess, 'run',
                          return_value=subprocess.CompletedProcess([], 0, bad, '')):
            with self.assertRaisesRegex(RuntimeError,
                                        'exactly one signing certificate identity'):
                CANARY.fingerprint(Path('/fake/fictional.apk'),
                                   'FICTIONAL-ONLY-DELETE')

    def test_no_v2_or_v3_fails_closed_and_reports_status(self):
        from contextlib import redirect_stderr
        from io import StringIO
        err = StringIO()
        with patch.object(CANARY.subprocess, 'run',
                          return_value=self.verify_response(v2='false', v3='false')):
            with redirect_stderr(err):
                with self.assertRaisesRegex(RuntimeError,
                                            'did not verify Android v2 or v3'):
                    CANARY.fingerprint(Path('/fake/fictional.apk'),
                                       'FICTIONAL-ONLY-DELETE')
        self.assertIn('verify exit code 0', err.getvalue())
        self.assertIn('v2 scheme (APK Signature Scheme v2): false', err.getvalue())

    def test_nonzero_verifier_sanitized_and_rejected(self):
        from contextlib import redirect_stderr
        from io import StringIO
        path = Path('/private/not-for-sharing/fictional.apk')
        err = StringIO()
        failure = self.verify_response(
            status=1, v2='false', v3='false',
            diagnostics=f'ERROR: Failed to verify {path}\nSECRET-NEVER-DISPLAY')
        with patch.object(CANARY.subprocess, 'run', return_value=failure):
            with redirect_stderr(err):
                with self.assertRaisesRegex(RuntimeError, 'did not verify'):
                    CANARY.fingerprint(path, 'FICTIONAL-ONLY-DELETE')
        output = err.getvalue()
        self.assertIn('verify exit code 1', output)
        self.assertIn('<FICTIONAL_APK>', output)
        self.assertNotIn('/private/not-for-sharing', output)
        self.assertNotIn('SECRET-NEVER-DISPLAY', output)

    def test_source_keeps_password_on_stdin_not_shell_arg(self):
        source = MODULE.read_text()
        self.assertIn("'--ks-pass', 'stdin'", source)
        self.assertIn("'--out', str(signed)", source)


if __name__ == '__main__':
    unittest.main()
