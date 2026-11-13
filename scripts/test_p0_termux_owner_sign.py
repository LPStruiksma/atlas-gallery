"""Fictional host-only contract tests of the Termux G3 signer helper.

A mock signer emits v2-like evidence but DOES NOT produce APK v2 signatures.
Real OpenJDK creates a disposable PKCS12 key, and real GnuPG AES-256 encrypts
its fictional backup through a mock `age` adapter. This is NOT age/Termux/Android
runtime verification and must never be reported as G3 PASS.
"""
import hashlib
import json
import zipfile
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).with_name('p0_termux_owner_sign.sh')
PIN='36bd476530abad6ebe794bd2d00128d83940518a'
FILES=['A-app.apk', 'A-test.apk', 'B-app.apk', 'B-test.apk']
REVIEWED_GENERATOR_BLOBS={
    '.github/workflows/p0-cloud-g3.yml':'3ce29cbd6ff88d4a40501b25a2bc55ecf0fe0c1e',
    'scripts/p0_cloud_g3_contract.py':'90dbc29cb51e6b5905d8af62b6209b8dcf1be218',
    'scripts/test_p0_cloud_g3_contract.py':'abe54869a1fd52ec0ce19a35b5b2ba725d509e53',
}

class Contracts(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.key_fixture = tempfile.TemporaryDirectory(prefix='atlas-fictional-pkcs12-')
        key=Path(cls.key_fixture.name)/'fictional.p12'
        proc=subprocess.run(['keytool','-genkeypair','-noprompt','-keystore',str(key),
            '-storetype','PKCS12','-alias','atlas-native-release','-keyalg','RSA',
            '-keysize','2048','-validity','2','-dname','CN=FICTIONAL NO OWNER',
            '-storepass','FICTIONAL-only-test-passphrase', '-keypass','FICTIONAL-only-test-passphrase'],
            capture_output=True,timeout=20)
        if proc.returncode:
            raise RuntimeError(proc.stderr.decode())
        cls.fixture_key_bytes=key.read_bytes()
        cert=subprocess.run(['keytool','-exportcert','-keystore',str(key),'-storetype','PKCS12',
            '-alias','atlas-native-release','-storepass','FICTIONAL-only-test-passphrase'],capture_output=True,check=True).stdout
        cls.fixture_cert_hash=hashlib.sha256(cert).hexdigest()

    @classmethod
    def tearDownClass(cls):
        cls.key_fixture.cleanup()

    def setUp(self):
        self.t = tempfile.TemporaryDirectory(prefix='atlas-g3-fixture-')
        self.addCleanup(self.t.cleanup)
        self.root = Path(self.t.name)
        self.home = self.root/'private-home'
        self.home.mkdir()
        self.bin = self.root/'bin'
        self.bin.mkdir()
        self.gnupg = self.home/'.gnupg'
        self.gnupg.mkdir(mode=0o700)
        self.repo_script = self.home/'script-under-test.sh'
        src=SCRIPT.read_text()
        src=src.replace('/data/data/com.termux/files/usr',str(self.root/'fake-prefix'))
        src=src.replace('/data/data/com.termux/files/home',str(self.home))
        self.repo_script.write_text(src)
        shutil.copyfile(SCRIPT.with_name('p0_termux_preview_provenance.py'),
                        self.home/'p0_termux_preview_provenance.py')
        self.env=os.environ.copy()
        self.env.update(HOME=str(self.home),PREFIX=str(self.root/'fake-prefix'),GNUPGHOME=str(self.gnupg), PATH=f'{self.bin}:{os.environ["PATH"]}')
        self.fixture_pass=self.root/'fictional-passphrase'
        self.fixture_pass.write_text('FICTIONAL-ONLY-AES-BACKUP-TEST-PASSPHRASE-40CHARS')
        self.key=self.home/'keys/fictional.p12'
        self.key.parent.mkdir(mode=0o700)
        self.key.write_bytes(self.fixture_key_bytes)
        self.key.chmod(0o600)
        self.cert=self.fixture_cert_hash
        self.inbox=self.home/'incoming';self.inbox.mkdir()
        (self.inbox/'source-commit.txt').write_text(PIN+'\n')
        for f in FILES:
            (self.inbox/f).write_bytes(('FICTIONAL APK NOT INSTALLABLE:'+f).encode())
        self.run_id='1234567890'
        self.generator_sha='a'*40
        self.rehash()
        self.snapshot_archive()
        self.write_shims()

    def rehash(self):
        hashes = {f: hashlib.sha256((self.inbox/f).read_bytes()).hexdigest() for f in FILES}
        (self.inbox/'SHA256SUMS').write_text(''.join(
            f'{hashes[f]}  {f}\n' for f in FILES))
        manifest = {'schema':'atlas.p0.preview-apks.v1','source_sha':PIN,
                    'generator_sha':self.generator_sha,
                    'package':'nl.atlas.gallery','versions':{'v3':3,'v4':4},'apks':hashes}
        (self.inbox/'artifact-manifest.json').write_text(json.dumps(manifest)+'\n')

    def snapshot_archive(self, authenticated=False):
        self.archive=self.root/'github-preview.zip'
        with zipfile.ZipFile(self.archive,'w',compression=zipfile.ZIP_DEFLATED) as archive:
            for f in [*FILES, 'source-commit.txt', 'SHA256SUMS', 'artifact-manifest.json']:
                archive.write(self.inbox/f,arcname=f)
        digest=hashlib.sha256(self.archive.read_bytes()).hexdigest()
        if not hasattr(self,'trusted_digest') or authenticated:
            self.trusted_digest=digest
        self.write_api()

    def write_api(self, workflow_id=123456, branch='main', success=True, code_overrides=None):
        repo='LPStruiksma/atlas-gallery'
        run={'id':int(self.run_id),'workflow_id':workflow_id,
             'name':'ATLAS P0 Phone G3 Cloud Handoff (manual only)',
             'repository':{'id':1417745611},'head_repository':{'id':1417745611},
             'event':'workflow_dispatch','head_branch':branch,
             'head_sha':self.generator_sha,'status':'completed',
             'conclusion':'success' if success else 'failure'}
        artifact={'id':998877,'name':'ATLAS-P0-G3-preview-apks','expired':False,
                  'size_in_bytes':12345,
                  'digest':'sha256:'+self.trusted_digest,
                  'archive_download_url':f'https://api.github.com/repos/{repo}/actions/artifacts/998877/zip',
                  'workflow_run':{'id':int(self.run_id),'repository_id':1417745611,
                                  'head_repository_id':1417745611,'head_branch':'main',
                                  'head_sha':self.generator_sha}}
        endpoints={f'repos/{repo}':{'full_name':repo,'private':True,'id':1417745611},
                   f'repos/{repo}/actions/workflows/p0-cloud-g3.yml':
                       {'id':123456,'path':'.github/workflows/p0-cloud-g3.yml'},
                   f'repos/{repo}/actions/runs/{self.run_id}':run,
                   f'repos/{repo}/actions/runs/{self.run_id}/artifacts?per_page=100':
                       {'total_count':1,'artifacts':[artifact]}}
        # Authenticated GitHub Contents API at run's *immutable* head, never
        # the moving main branch or the APK package's own manifest.
        for path, git_blob in REVIEWED_GENERATOR_BLOBS.items():
            entry={'path':path,'type':'file','sha':git_blob}
            entry.update((code_overrides or {}).get(path, {}))
            endpoints[f'repos/{repo}/contents/{path}?ref={self.generator_sha}']=entry
        (self.root/'gh-api.json').write_text(json.dumps(endpoints))
        self.env['MOCK_GH_DIR']=str(self.root)

    def write_shims(self):
        def put(name,data):
            p=self.bin/name;p.write_text(data);p.chmod(0o700)
        put('gh', '''#!/usr/bin/env python3
import json,os,sys
from pathlib import Path
if len(sys.argv)!=3 or sys.argv[1]!='api':sys.exit(2)
root=Path(os.environ['MOCK_GH_DIR'])
url=sys.argv[2]
if url.endswith('/zip'):
    sys.stdout.buffer.write((root/'github-preview.zip').read_bytes())
else:
    endpoints=json.loads((root/'gh-api.json').read_text())
    if url not in endpoints:sys.exit(3)
    print(json.dumps(endpoints[url]))
''')
        put('aapt', '''#!/usr/bin/env python3
import os,sys
from pathlib import Path
f=Path(sys.argv[-1]).name
name='nl.atlas.gallery.test' if '-test.' in f else 'nl.atlas.gallery'
ver='4' if f.startswith('B-') else '3'
if os.getenv('MOCK_BAD_VERSION') and f.startswith('B-app.'):
    ver='3'
if os.getenv('MOCK_BAD_PACKAGE') and f.startswith('A-app.'):
    name='evil.example'
print("package: name='%s' versionCode='%s' versionName='0.3'"%(name,ver))
''')
        put('apksigner', '''#!/usr/bin/env python3
import os,sys,shutil
from pathlib import Path
args=sys.argv[1:]
if args[0]=='sign':
    if os.getenv('MOCK_APKSIGNER_LOG'):
        Path(os.environ['MOCK_APKSIGNER_LOG']).write_text('SIGN INVOKED')
    out=Path(args[args.index('--out')+1]);inp=Path(args[-1]);shutil.copyfile(inp,out)
    sys.exit(0)
if args[0]=='verify':
    apk=Path(args[-1])
    print('Verified using v2 scheme (APK Signature Scheme v2): '+('false' if os.getenv('MOCK_BAD_V2') else 'true'))
    print('Verified using v3 scheme (APK Signature Scheme v3): '+('true' if os.getenv('MOCK_GOOD_V3') else 'false'))
    fp=os.getenv('MOCK_CERT')
    if os.getenv('MOCK_CERT_MISMATCH') and apk.name.startswith('B-'):
        fp='f'*64
    label='V3.0 Signer: ' if os.getenv('MOCK_SDK37_CERT') else 'Signer #1 '
    print(label+'certificate SHA-256 digest: '+fp)
    if os.getenv('MOCK_SECOND_CERT'):
        print('V2 Signer: certificate SHA-256 digest: '+('f'*64))
    sys.exit(0)
sys.exit(2)
''')
        put('age', '''#!/usr/bin/env python3
import os,sys,subprocess
args=sys.argv[1:]
pw=os.environ['MOCK_PW_FILE'];gpg='gpg'
if args[0]=='-p':
    if os.getenv('MOCK_AGE_ENCRYPT_LOG'):
        from pathlib import Path
        Path(os.environ['MOCK_AGE_ENCRYPT_LOG']).write_text('ENCRYPT INVOKED')
    dest=args[args.index('-o')+1]
    proc=subprocess.run([gpg,'--batch','--yes','--pinentry-mode','loopback','--passphrase-file',pw,
        '--symmetric','--cipher-algo','AES256','--output',dest,args[-1]])
    sys.exit(proc.returncode)
if args[0]=='-d':
    proc=subprocess.run([gpg,'--batch','--pinentry-mode','loopback','--passphrase-file',pw,
        '--decrypt',args[-1]])
    sys.exit(proc.returncode)
sys.exit(2)
''')
        self.env.update(MOCK_CERT=self.cert,MOCK_PW_FILE=str(self.fixture_pass))

    def call(self,*args,more_env=None):
        env=self.env.copy()
        env.update(more_env or {})
        cmd=['script','-q','-e','-c',subprocess.list2cmdline(['bash',str(self.repo_script),*map(str,args)]),'/dev/null']
        return subprocess.run(cmd,env=env,capture_output=True,text=True,timeout=150)

    def test_full_mock_flow_real_fictional_key_and_encrypted_backup(self):
        c=self.call('check',self.run_id)
        self.assertEqual(c.returncode,0,c.stdout+c.stderr)
        backup=self.home/'keys/fictional-backup.age'
        b=self.call('backup',self.key,backup)
        self.assertEqual(b.returncode,0,b.stdout+b.stderr)
        self.assertFalse(self.key.read_bytes()==backup.read_bytes())
        self.assertNotIn(b'FICTIONAL NO OWNER', backup.read_bytes())
        # Actual GPG AES-256 encryption/decryption, not true age wire format.
        g=subprocess.run(['gpg','--batch','--pinentry-mode','loopback','--passphrase-file',str(self.fixture_pass),
            '--decrypt',str(backup)],env=self.env,capture_output=True,check=True)
        self.assertEqual(g.stdout,self.key.read_bytes())
        signed=self.home/'signed'
        s=self.call('sign',self.run_id,self.key,backup,signed)
        self.assertEqual(s.returncode,0,s.stdout+s.stderr)
        e=(signed/'public-evidence.txt').read_text()
        self.assertIn('A=3\nB=4\n',e)
        self.assertIn('signer_certificate_sha256='+self.cert,e)
        self.assertIn('owner_cert_emulator_upgrade=NOT_EXECUTED',e)
        for f in FILES:
            self.assertIn('signed_'+f+'_sha256=',e)
            self.assertTrue((signed/f).exists())

    def make_nonleaf_escape(self):
        """Immediate parent normal; a higher ancestor escapes HOME via symlink."""
        outside=self.root/'external-shared-storage'
        normal=outside/'normal-parent'
        normal.mkdir(parents=True)
        nested=self.home/'private-nested'
        nested.mkdir(mode=0o700)
        (nested/'indirect-link').symlink_to(outside, target_is_directory=True)
        escaped_parent=nested/'indirect-link'/'normal-parent'
        self.assertFalse(escaped_parent.is_symlink())
        self.assertEqual(escaped_parent.resolve(),normal.resolve())
        self.assertFalse(str(escaped_parent.resolve()).startswith(str(self.home.resolve())+'/'))
        return escaped_parent,normal

    def test_backup_rejects_nonleaf_symlink_escape_before_encryption(self):
        escaped,external=self.make_nonleaf_escape()
        destination=escaped/'fictional-owner-backup.age'
        invoked=self.root/'age-encrypt-invoked'
        p=self.call('backup',self.key,destination,
                    more_env={'MOCK_AGE_ENCRYPT_LOG':str(invoked)})
        self.assertNotEqual(p.returncode,0,p.stdout+p.stderr)
        self.assertIn('resolves outside private Termux home',p.stdout)
        self.assertFalse((external/destination.name).exists())
        self.assertFalse(invoked.exists(),'Encryption must not be attempted')

    def test_sign_rejects_nonleaf_symlink_escape_before_apksigner(self):
        backup=self.home/'keys/fictional-backup.age'
        self.assertEqual(self.call('backup',self.key,backup).returncode,0)
        escaped,external=self.make_nonleaf_escape()
        output=escaped/'new-signed'
        invoked=self.root/'apksigner-invoked'
        p=self.call('sign',self.run_id,self.key,backup,output,
                    more_env={'MOCK_APKSIGNER_LOG':str(invoked)})
        self.assertNotEqual(p.returncode,0,p.stdout+p.stderr)
        self.assertIn('resolves outside private Termux home',p.stdout)
        self.assertFalse((external/output.name).exists())
        self.assertFalse(invoked.exists(),'Signer must not be called')

    def test_regular_deeply_nested_private_backup_and_sign_succeed(self):
        parent=self.home/'deep'/'legitimate'/'private'
        parent.mkdir(parents=True,mode=0o700)
        backup=parent/'fictional-backup.age'
        p=self.call('backup',self.key,backup)
        self.assertEqual(p.returncode,0,p.stdout+p.stderr)
        signed=parent/'signed-v3-v4'
        p=self.call('sign',self.run_id,self.key,backup,signed)
        self.assertEqual(p.returncode,0,p.stdout+p.stderr)
        self.assertTrue((signed/'public-evidence.txt').is_file())
        self.assertEqual(set(FILES),set(p.name for p in signed.glob('*.apk')))

    def test_tampered_input_rejected_before_sign(self):
        # Attacker changes ALL preview APKs plus BOTH packaged checksum sources
        # and retains the public literal source pin, but not the authenticated API.
        for f in FILES:
            (self.inbox/f).write_bytes(('HOSTILE REPLACEMENT:'+f).encode())
        self.rehash()
        self.snapshot_archive()  # preserve original trusted GitHub artifact digest
        backup=self.home/'keys/backup.age'
        self.assertEqual(self.call('backup',self.key,backup).returncode,0)
        called=self.root/'sign-invoked'
        p=self.call('sign',self.run_id,self.key,backup,self.home/'signed',
                    more_env={'MOCK_APKSIGNER_LOG':str(called)})
        self.assertNotEqual(p.returncode,0)
        self.assertIn('archive SHA256 differs',p.stdout)
        self.assertFalse(called.exists(),'Permanent signer must not run')
        self.assertFalse((self.home/'signed').exists())

    def test_wrong_version_rejected(self):
        p=self.call('check',self.run_id,more_env={'MOCK_BAD_VERSION':'1'})
        self.assertNotEqual(p.returncode,0)
        self.assertIn('versionCode',p.stdout)

    def test_wrong_package_rejected(self):
        p=self.call('check',self.run_id,more_env={'MOCK_BAD_PACKAGE':'1'})
        self.assertNotEqual(p.returncode,0)
        self.assertIn('package',p.stdout)

    def test_bad_commit_rejected(self):
        (self.inbox/'source-commit.txt').write_text('wrong\n')
        self.snapshot_archive(authenticated=True)
        p=self.call('check',self.run_id)
        self.assertNotEqual(p.returncode,0)
        self.assertIn('pinned source mismatch',p.stdout)

    def test_duplicate_hash_record_rejected(self):
        (self.inbox/'SHA256SUMS').write_text((self.inbox/'SHA256SUMS').read_text().splitlines(keepends=True)[0]*4)
        self.snapshot_archive(authenticated=True)
        p=self.call('check',self.run_id)
        self.assertNotEqual(p.returncode,0)
        self.assertIn('checksum text differs',p.stdout)

    def assert_unreviewed_generator_rejected_before_sign(self, path, changed):
        # Valid main workflow ID, successful run, and real matching GitHub
        # artifact digest are intentionally preserved. ONLY one executable Git
        # blob differs from the independent reviewed allowlist.
        self.write_api(code_overrides={path:changed})
        backup=self.home/'keys/fictional-backup.age'
        self.assertEqual(self.call('backup',self.key,backup).returncode,0)
        invoked=self.root/'apksigner-invoked'
        signed=self.home/'signed'
        p=self.call('sign',self.run_id,self.key,backup,signed,
                    more_env={'MOCK_APKSIGNER_LOG':str(invoked)})
        self.assertNotEqual(p.returncode,0,p.stdout+p.stderr)
        self.assertIn('unreviewed generator Git blob',p.stdout)
        self.assertNotIn('Owner-local signing:',p.stdout)
        self.assertFalse(invoked.exists(),'No key-backed signing attempt allowed')
        self.assertFalse(signed.exists(),'No signed output directory allowed')

    def test_modified_main_workflow_blob_rejected_before_sign(self):
        self.assert_unreviewed_generator_rejected_before_sign(
            '.github/workflows/p0-cloud-g3.yml', {'sha':'b'*40})

    def test_modified_main_generator_contract_blob_rejected_before_sign(self):
        self.assert_unreviewed_generator_rejected_before_sign(
            'scripts/p0_cloud_g3_contract.py', {'sha':'c'*40})

    def test_modified_main_executed_test_blob_rejected_before_sign(self):
        self.assert_unreviewed_generator_rejected_before_sign(
            'scripts/test_p0_cloud_g3_contract.py', {'sha':'d'*40})

    def test_contents_api_spoofed_path_or_type_rejected(self):
        self.assert_unreviewed_generator_rejected_before_sign(
            'scripts/p0_cloud_g3_contract.py',
            {'path':'scripts/other.py','type':'symlink'})

    def test_reviewed_code_allowlist_positive(self):
        # This positive also authenticates the complete original ZIP and all
        # co-packaged manifest hashes without ever using the owner signer.
        p=self.call('check',self.run_id)
        self.assertEqual(p.returncode,0,p.stdout+p.stderr)
        self.assertIn('CHECK PASS: authenticated GitHub ZIP',p.stdout)

    def test_wrong_workflow_identity_rejected(self):
        self.write_api(workflow_id=123457)
        p=self.call('check',self.run_id)
        self.assertNotEqual(p.returncode,0)
        self.assertIn('workflow',p.stdout)

    def test_wrong_run_id_rejected(self):
        p=self.call('check','1234567891')
        self.assertNotEqual(p.returncode,0)
        self.assertIn('authenticated API unavailable',p.stdout)

    def test_wrong_archive_digest_rejected(self):
        self.trusted_digest='0'*64
        self.write_api()
        p=self.call('check',self.run_id)
        self.assertNotEqual(p.returncode,0)
        self.assertIn('archive SHA256 differs',p.stdout)

    def test_nonmain_run_rejected(self):
        self.write_api(branch='unsafe-feature')
        p=self.call('check',self.run_id)
        self.assertNotEqual(p.returncode,0)

    def test_signed_apks_reject_mismatched_certificate(self):
        backup=self.home/'keys/backup.age'
        self.assertEqual(self.call('backup',self.key,backup).returncode,0)
        p=self.call('sign',self.run_id,self.key,backup,self.home/'signed',more_env={'MOCK_CERT_MISMATCH':'1'})
        self.assertNotEqual(p.returncode,0)
        self.assertIn('identities differ',p.stdout)
        self.assertFalse((self.home/'signed/public-evidence.txt').exists())

    def test_bad_v2_signature_rejected(self):
        backup=self.home/'keys/backup.age'
        self.assertEqual(self.call('backup',self.key,backup).returncode,0)
        p=self.call('sign',self.run_id,self.key,backup,self.home/'signed',more_env={'MOCK_BAD_V2':'1'})
        self.assertNotEqual(p.returncode,0)
        self.assertIn('modern APK signature (v2/v3) not verified',p.stdout)


    def test_sdk37_v3_only_apksigner_certificate_accepted(self):
        """Android minSdk 29 authentic v3-only certificate is acceptable."""
        backup=self.home/'keys/backup.age'
        self.assertEqual(self.call('backup',self.key,backup).returncode,0)
        p=self.call('sign',self.run_id,self.key,backup,self.home/'signed-v3',
                    more_env={'MOCK_BAD_V2':'1','MOCK_GOOD_V3':'1',
                              'MOCK_SDK37_CERT':'1'})
        self.assertEqual(p.returncode,0,p.stdout)
        e=(self.home/'signed-v3/public-evidence.txt').read_text()
        self.assertIn('signer_certificate_sha256='+self.cert,e)

    def test_sdk37_two_scheme_labels_same_cert_accepted(self):
        backup=self.home/'keys/backup.age'
        self.assertEqual(self.call('backup',self.key,backup).returncode,0)
        p=self.call('sign',self.run_id,self.key,backup,self.home/'signed-v3',
                    more_env={'MOCK_GOOD_V3':'1','MOCK_SDK37_CERT':'1'})
        self.assertEqual(p.returncode,0,p.stdout)

    def test_multi_scheme_conflicting_certificate_rejected(self):
        backup=self.home/'keys/backup.age'
        self.assertEqual(self.call('backup',self.key,backup).returncode,0)
        p=self.call('sign',self.run_id,self.key,backup,self.home/'signed-bad',
                    more_env={'MOCK_GOOD_V3':'1','MOCK_SECOND_CERT':'1'})
        self.assertNotEqual(p.returncode,0)
        self.assertIn('Multiple distinct signer certs',p.stdout)
        self.assertFalse((self.home/'signed-bad/public-evidence.txt').exists())

    def test_missing_encrypted_backup_rejected(self):
        p=self.call('sign',self.run_id,self.key,self.home/'keys/missing.age',self.home/'signed')
        self.assertNotEqual(p.returncode,0)
        self.assertFalse((self.home/'signed').exists())

    def test_key_outside_private_home_rejected(self):
        other=self.root/'outside.p12'
        shutil.copy2(self.key,other)
        p=self.call('backup',other,self.home/'copy.age')
        self.assertNotEqual(p.returncode,0)
        self.assertIn('private',p.stdout)

    def test_real_private_keystore_permissions_rejected(self):
        self.key.chmod(0o644)
        p=self.call('backup',self.key,self.home/'copy.age')
        self.assertNotEqual(p.returncode,0)
        self.assertIn('chmod',p.stdout)

if __name__=='__main__':
    unittest.main(verbosity=2)
