#!/usr/bin/env python3
"""Zero-cost fictional G3 artifact contract and tamper tests; no Android keys."""
import json
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest
from unittest.mock import patch
import zipfile
import sys
sys.path.insert(0, str(Path(__file__).resolve().parent))
import p0_cloud_g3_contract as g3


def apk(path, changed=False):
    with zipfile.ZipFile(path, 'w') as z:
        z.writestr('AndroidManifest.xml', b'fictional binary manifest')
        z.writestr('classes.dex', b'fictional dex' + (b'TAMPER' if changed else b''))


class CloudG3ContractTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.preview = self.root / 'preview'
        self.signed = self.root / 'signed'
        self.preview.mkdir()
        self.signed.mkdir()
        manifest = {'schema': 'atlas.p0.preview-apks.v1', 'source_sha': g3.PIN,
                    'generator_sha': 'a' * 40, 'package': g3.PACKAGE, 'versions': {'v3': 3, 'v4': 4}, 'apks': {}}
        for name in g3.NAMES.values():
            apk(self.preview / name)
            manifest['apks'][name] = g3.digest(self.preview / name)
        for name in g3.SIGNED.values():
            apk(self.signed / name)
        (self.preview / 'artifact-manifest.json').write_text(json.dumps(manifest))
        (self.preview / 'source-commit.txt').write_text(g3.PIN + '\n')
        (self.preview / 'SHA256SUMS').write_text(''.join(
            f'{manifest["apks"][name]}  {name}\n' for name in g3.NAMES.values()))
        self.out = self.root / 'proof.json'
        self.args = SimpleNamespace(preview=str(self.preview), signed=str(self.signed),
                                    output=str(self.out), generator_sha='a' * 40, fingerprint='a' * 64,
                                    aapt='fictional-aapt', apksigner='fictional-apksigner')

    def run_validated(self):
        def fake_meta(path, _):
            match = next(((v, k) for (v, k), name in g3.SIGNED.items()
                          if name == Path(path).name), None)
            if match is None:
                raise AssertionError('not a signed fixture')
            v, k = match
            return ('nl.atlas.gallery' + ('.test' if k == 'test' else ''), v)
        with patch.object(g3, 'metadata', side_effect=fake_meta), patch.object(g3, 'cert', return_value='a' * 64):
            g3.validate(self.args)

    def test_accepts_only_four_matching_signed_payloads(self):
        self.run_validated()
        proof = json.loads(self.out.read_text())
        self.assertEqual(g3.PIN, proof['source_sha'])
        self.assertEqual('a' * 40, proof['generator_sha'])
        self.assertEqual(4, len(proof['apks']))
        self.assertEqual('a' * 64, proof['signer_certificate_sha256'])

    def test_rejects_modified_signed_dex(self):
        apk(self.signed / g3.SIGNED[(4, 'app')], changed=True)
        with self.assertRaisesRegex(ValueError, 'payload'):
            self.run_validated()

    def test_rejects_corrupt_preview_digest(self):
        manifest = json.loads((self.preview / 'artifact-manifest.json').read_text())
        manifest['apks'][g3.NAMES[(3, 'app')]] = '0' * 64
        (self.preview / 'artifact-manifest.json').write_text(json.dumps(manifest))
        with self.assertRaisesRegex(ValueError, 'SHA256'):
            self.run_validated()

    def test_rejects_generator_sha_mismatch(self):
        self.args.generator_sha = 'b' * 40
        with self.assertRaisesRegex(ValueError, 'manifest schema/source'):
            self.run_validated()

    def test_rejects_invalid_generator_sha(self):
        self.args.generator_sha = 'unexpected-ref'
        with self.assertRaisesRegex(ValueError, 'untrusted generation run SHA'):
            self.run_validated()

    def test_rejects_source_provenance_forgery(self):
        manifest = json.loads((self.preview / 'artifact-manifest.json').read_text())
        manifest['source_sha'] = '0' * 40
        (self.preview / 'artifact-manifest.json').write_text(json.dumps(manifest))
        with self.assertRaisesRegex(ValueError, 'manifest schema/source'):
            self.run_validated()

    def test_rejects_missing_signed_test_apk(self):
        (self.signed / g3.SIGNED[(4, 'test')]).unlink()
        with self.assertRaisesRegex(ValueError, 'exactly four'):
            self.run_validated()

    def test_rejects_extra_or_stale_signed_asset(self):
        apk(self.signed / 'C-app.apk')
        with self.assertRaisesRegex(ValueError, 'exactly four'):
            self.run_validated()

    def test_rejects_extra_preview_artifact(self):
        (self.preview / 'unrelated-upload.txt').write_text('junk')
        with self.assertRaisesRegex(ValueError, 'exactly four'):
            self.run_validated()

    def test_rejects_mismatched_owner_cert(self):
        with patch.object(g3, 'metadata', return_value=('nl.atlas.gallery', 3)), patch.object(g3, 'cert', return_value='b' * 64):
            with self.assertRaisesRegex(ValueError, 'owner signer certificate mismatch'):
                g3.validate(self.args)

    def test_rejects_bad_version(self):
        with patch.object(g3, 'metadata', return_value=('nl.atlas.gallery', 4)), patch.object(g3, 'cert', return_value='a' * 64):
            with self.assertRaisesRegex(ValueError, 'package/version mismatch'):
                g3.validate(self.args)

    def test_preview_exports_all_four_hashed_apks(self):
        trees = {v: self.root / f'source-v{v}' for v in (3, 4)}
        for v, root in trees.items():
            for k in ('app', 'test'):
                loc = ('app/build/outputs/apk/debug/app-debug.apk' if k == 'app'
                       else 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
                target = root / loc
                target.parent.mkdir(parents=True, exist_ok=True)
                apk(target)
        output = self.root / 'exported'
        params = SimpleNamespace(v3=str(trees[3]), v4=str(trees[4]),
                                 output=str(output), generator_sha='a' * 40, aapt='fake', apksigner='fake')
        def meta(file, _):
            version = 3 if 'source-v3' in str(file) else 4
            pkg = 'nl.atlas.gallery.test' if 'androidTest' in str(file) else 'nl.atlas.gallery'
            return pkg, version
        with patch.object(g3, 'metadata', side_effect=meta), patch.object(g3, 'cert', return_value='a' * 64):
            g3.preview(params)
        manifest = json.loads((output / 'artifact-manifest.json').read_text())
        self.assertEqual(g3.PIN, manifest['source_sha'])
        self.assertEqual(set(g3.NAMES.values()), set(manifest['apks']))
        self.assertEqual((output / 'source-commit.txt').read_text(), g3.PIN + '\n')
        self.assertEqual(len((output / 'SHA256SUMS').read_text().splitlines()), 4)
        for name, sha in manifest['apks'].items():
            self.assertEqual(g3.digest(output / name), sha)
        with patch.object(g3, 'metadata', side_effect=meta), patch.object(g3, 'cert', return_value='a' * 64):
            with self.assertRaisesRegex(ValueError, 'already exists'):
                g3.preview(params)

    def test_preview_rejects_wrong_package_before_upload(self):
        trees = {v: self.root / f'source-v{v}' for v in (3, 4)}
        for v, root in trees.items():
            for k in ('app', 'test'):
                loc = ('app/build/outputs/apk/debug/app-debug.apk' if k == 'app'
                       else 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
                target = root / loc
                target.parent.mkdir(parents=True, exist_ok=True)
                apk(target)
        params = SimpleNamespace(v3=str(trees[3]), v4=str(trees[4]),
                                 output=str(self.root / 'bad'), generator_sha='a' * 40, aapt='fake', apksigner='fake')
        with patch.object(g3, 'metadata', return_value=('nl.atlas.bad', 3)), patch.object(g3, 'cert', return_value='a' * 64):
            with self.assertRaisesRegex(ValueError, 'package/version mismatch'):
                g3.preview(params)

    def test_bump_changes_only_literal_three_to_four(self):
        p = self.root / 'gradle.kts'
        original = 'defaultConfig {\n  versionCode = 3\n  versionName = "0.3.0-upgrade-candidate"\n}\n'
        p.write_text(original)
        g3.bump(p)
        self.assertEqual(p.read_text(), original.replace('versionCode = 3', 'versionCode = 4'))
        with self.assertRaisesRegex(ValueError, 'exactly one'):
            g3.bump(p)

    def test_duplicate_or_corrupted_zip_rejected(self):
        p = self.signed / g3.SIGNED[(3, 'app')]
        with zipfile.ZipFile(p, 'a') as z:
            z.writestr('classes.dex', b'duplicate')
        with self.assertRaisesRegex(ValueError, 'duplicate APK ZIP member'):
            g3.payload(p)

    def test_signer_parser_requires_single_fingerprint(self):
        with patch.object(g3, 'run', return_value='Signer #1 certificate SHA-256 digest: ' + 'e' * 64 + '\n'):
            self.assertEqual('e' * 64, g3.cert('fake.apk', 'fake-signer'))
        with patch.object(g3, 'run', return_value='Signer #1 certificate SHA-256 digest: ' + 'e' * 64 + '\nSigner #1 certificate SHA-256 digest: ' + 'f' * 64):
            with self.assertRaisesRegex(ValueError, 'one signer'):
                g3.cert('fake.apk', 'fake-signer')


class CloudG3WorkflowTrustBoundaryTests(unittest.TestCase):
    def workflow(self):
        return (Path(__file__).resolve().parent.parent / '.github' / 'workflows' / 'p0-cloud-g3.yml').read_text()

    def test_only_manual_main_jobs_read_only_no_permanent_secrets(self):
        w = self.workflow()
        self.assertIn('  workflow_dispatch:', w)
        for trigger in ('  push:', '  pull_request:', '  pull_request_target:'):
            self.assertNotIn(trigger, w)
        self.assertIn('permissions:\n  contents: read\n  actions: read', w)
        self.assertEqual(2, w.count("github.ref == 'refs/heads/main'"))
        self.assertNotIn('secrets.', w)
        self.assertIn('ref: 36bd476530abad6ebe794bd2d00128d83940518a', w)
        self.assertIn('--generator-sha "$GITHUB_SHA"', w)

    def test_only_fetch_step_gets_token_and_verifier_binds_run_identity(self):
        w = self.workflow()
        fetch = w.split('      - name: Authenticate GitHub run and download the private signed transport\n', 1)[1].split(
            '      - name: Verify downloaded APKs offline without any GitHub token in the parser\n', 1)[0]
        offline = w.split('      - name: Verify downloaded APKs offline without any GitHub token in the parser\n', 1)[1].split(
            '      - name: Require usable KVM, refuse software-emulation fallback\n', 1)[0]
        self.assertEqual(1, w.count('GH_TOKEN:'))
        self.assertIn('GH_TOKEN: ${{ github.token }}', fetch)
        self.assertNotIn('GH_TOKEN:', offline)
        self.assertIn('workflowDatabaseId', fetch)
        self.assertIn('headSha', fetch)
        self.assertNotIn('assert ', fetch, 'Python -O must never disable authentication checks')
        self.assertIn("raise SystemExit('NO-GO: wrong generating workflow ID')", fetch)
        self.assertIn('isPrerelease', fetch)
        self.assertIn('gh run download', fetch)
        self.assertNotIn('apksigner', fetch)
        self.assertNotIn('aapt', fetch)
        self.assertNotIn('gh ', offline)
        self.assertIn('verify-signed', offline)
        self.assertIn('--generator-sha "$generator_sha"', offline)


if __name__ == '__main__':
    unittest.main()
