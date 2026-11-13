#!/data/data/com.termux/files/usr/bin/python3
"""FICTIONAL-ONLY S24+ Termux smoke test using EXISTING G1 version-3 APKs.

No installed app, permanent key, credentials transfer, signed-file upload, ADB,
G3 3->4 upgrade, or GitHub Actions dispatch. Deletes its own private temp data.
Run ONLY after separate owner approval for authenticated private artifact reads
and disposable local signing. Not part of the real owner signing workflow.
"""
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import zipfile

REPO = 'LPStruiksma/atlas-gallery'
REPO_ID = 1417745611
RUN_ID = 39649561900
WORKFLOW_ID = 383546963
WORKFLOW = 'build-apk.yml'
RUN_NAME = 'ATLAS Android - Build and UI Evidence'
SOURCE_SHA = 'a17204e2c484813d3c812d9cc637658ed183564e'
HEAD_BRANCH = 'atlas/lane-a-p0-rc-frozen-oct21'
ARTIFACTS = (
    ('ATLAS-native-debug-APK', 12368896672,
     'c479ebf9640f70891ab8d666181a9f22bc228f16eba90237cd5f04f9e1b78dfb',
     'nl.atlas.gallery', 'app'),
    ('ATLAS-ui-test-APK', 12368687009,
     '1fb05c3a1b89fa0772b851cc23ffa892a9153101596a89adc50fe70324c04b6e',
     'nl.atlas.gallery.test', 'test'),
)
APK_LIMIT = 96 * 1024 * 1024
ZIP_LIMIT = 96 * 1024 * 1024
FICTIONAL_ALIAS = 'atlas-fixture-delete-today'
FICTIONAL_CN = 'FICTIONAL-ONLY-DELETE'
HEX64 = re.compile(r'[0-9a-f]{64}\Z')


def stop(message):
    raise RuntimeError('NO-GO (fictional canary): ' + message)


def run(command, *, stdin=None, stdout=None, timeout=180):
    try:
        result = subprocess.run(command, stdin=stdin, stdout=stdout,
                                check=False, timeout=timeout)
    except (OSError, subprocess.TimeoutExpired):
        stop('tool unavailable or timed out: ' + command[0])
    if result.returncode:
        stop('tool returned failure: ' + command[0])
    return result


def api(endpoint):
    try:
        response = subprocess.run(['gh', 'api', f'repos/{REPO}/{endpoint}'.rstrip('/')],
                                  capture_output=True, check=False, timeout=45)
    except (OSError, subprocess.TimeoutExpired):
        stop('authenticated GitHub metadata request unavailable')
    if response.returncode:
        stop('authenticated GitHub metadata request failed (check gh auth status)')
    try:
        value = json.loads(response.stdout)
    except (ValueError, UnicodeDecodeError):
        stop('malformed GitHub REST JSON')
    if type(value) is not dict:
        stop('non-object GitHub REST JSON')
    return value


def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


def verify_metadata():
    repo = api('')
    if (repo.get('full_name') != REPO or repo.get('id') != REPO_ID
            or repo.get('private') is not True):
        stop('unexpected private repository identity')
    workflow = api(f'actions/workflows/{WORKFLOW}')
    if (workflow.get('id') != WORKFLOW_ID
            or workflow.get('path') != f'.github/workflows/{WORKFLOW}'):
        stop('unexpected generating workflow identity')
    source_run = api(f'actions/runs/{RUN_ID}')
    if (source_run.get('id') != RUN_ID
            or source_run.get('workflow_id') != WORKFLOW_ID
            or source_run.get('name') != RUN_NAME
            or source_run.get('head_sha') != SOURCE_SHA
            or source_run.get('head_branch') != HEAD_BRANCH
            or source_run.get('event') != 'pull_request'
            or source_run.get('status') != 'completed'
            or source_run.get('conclusion') != 'success'
            or source_run.get('run_attempt') != 4
            or not isinstance(source_run.get('repository'), dict)
            or source_run['repository'].get('id') != REPO_ID
            or not isinstance(source_run.get('head_repository'), dict)
            or source_run['head_repository'].get('id') != REPO_ID):
        stop('not the approved successful historical G1 run')
    artifact_list = api(f'actions/runs/{RUN_ID}/artifacts?per_page=100')
    values = artifact_list.get('artifacts')
    if (type(artifact_list.get('total_count')) is not int
            or type(values) is not list
            or len(values) != artifact_list['total_count']):
        stop('incomplete original Actions artifact listing')
    for name, artifact_id, expected, _, _ in ARTIFACTS:
        matching = [a for a in values if isinstance(a, dict)
                    and a.get('name') == name and a.get('id') == artifact_id]
        if len(matching) != 1:
            stop('not exactly one expected historical artifact: ' + name)
        a = matching[0]
        link = a.get('workflow_run')
        if (a.get('digest') != 'sha256:' + expected
                or not HEX64.fullmatch(expected)
                or a.get('expired') is not False
                or type(a.get('size_in_bytes')) is not int
                or not (0 < a['size_in_bytes'] <= ZIP_LIMIT)
                or a.get('archive_download_url') !=
                    f'https://api.github.com/repos/{REPO}/actions/artifacts/{artifact_id}/zip'
                or type(link) is not dict or link.get('id') != RUN_ID
                or link.get('repository_id') != REPO_ID
                or link.get('head_repository_id') != REPO_ID
                or link.get('head_sha') != SOURCE_SHA
                or link.get('head_branch') != HEAD_BRANCH):
            stop('untrusted artifact metadata: ' + name)
    print('PASS: independent historical run, repo, workflow and artifact digest pins', flush=True)


def retrieve_one(temp, artifact):
    name, artifact_id, expected, package, role = artifact
    archive = temp / (role + '-ORIGINAL-GITHUB.zip')
    with archive.open('xb') as dst:
        try:
            outcome = subprocess.run(['gh', 'api',
                f'repos/{REPO}/actions/artifacts/{artifact_id}/zip'],
                stdout=dst, stderr=subprocess.DEVNULL, timeout=180)
        except (OSError, subprocess.TimeoutExpired):
            stop('original GitHub Actions ZIP fetch unavailable: ' + role)
    if outcome.returncode or archive.stat().st_size > ZIP_LIMIT:
        stop('original GitHub Actions ZIP download failed/too large: ' + role)
    if digest(archive) != expected:
        stop('original GitHub ZIP SHA256 differs from independent pin: ' + role)
    # Never extract member paths. Accept one APK payload and copy to an
    # explicitly named fresh private file AFTER hashing the *entire* ZIP.
    try:
        with zipfile.ZipFile(archive) as z:
            names = [m.filename for m in z.infolist()]
            if len(names) != len(set(names)):
                stop('duplicate archive members')
            apks = []
            for entry in z.infolist():
                posix = PurePosixPath(entry.filename)
                mode = (entry.external_attr >> 16) & 0o170000
                if (entry.filename.startswith('/') or '\\' in entry.filename
                        or '\x00' in entry.filename or '..' in posix.parts
                        or mode not in (0, stat.S_IFDIR, stat.S_IFREG)
                        or entry.file_size > APK_LIMIT):
                    stop('unsafe archive member')
                if not entry.is_dir() and entry.filename.lower().endswith('.apk'):
                    apks.append(entry)
            if len(apks) != 1:
                stop('expected exactly one APK in authenticated ' + role + ' ZIP')
            result = temp / (role + '-G1-debug-input.apk')
            n = 0
            with z.open(apks[0], 'r') as src, result.open('xb') as dst:
                for buf in iter(lambda: src.read(1024 * 1024), b''):
                    n += len(buf)
                    if n > APK_LIMIT:
                        stop('APK decompression exceeded limit')
                    dst.write(buf)
            if n != apks[0].file_size:
                stop('truncated ZIP APK member')
    except (zipfile.BadZipFile, zipfile.LargeZipFile, RuntimeError) as exc:
        if isinstance(exc, RuntimeError) and str(exc).startswith('NO-GO'):
            raise
        stop('invalid ZIP archive: ' + role)
    print(f'PASS: {role} original ZIP matches pinned GitHub SHA256', flush=True)
    check_apk(result, package)
    return result


def check_apk(path, package):
    try:
        proc = subprocess.run(['aapt', 'dump', 'badging', str(path)],
                              capture_output=True, text=True, timeout=60)
    except (OSError, subprocess.TimeoutExpired):
        stop('native aapt missing or timed out')
    if proc.returncode:
        stop('aapt cannot read real APK')
    # Instrumentation APKs legitimately omit android:versionCode. Require
    # their exact package name, but demand versionCode 3 on the app APK.
    match = re.search(r"^package: name='([^']+)'([^\r\n]*)",
                      proc.stdout, re.MULTILINE)
    if not match or match.group(1) != package:
        stop('unexpected genuine APK package')
    version = re.search(r"\bversionCode='([^']*)'", match.group(2))
    if package == 'nl.atlas.gallery' and (
            not version or version.group(1) != '3'):
        stop('unexpected genuine app APK versionCode')
    label = version.group(1) if version and version.group(1) else '(not declared)'
    print(f'PASS: official aapt reads {package}, versionCode {label}', flush=True)


def fingerprint(path, expected_cn=None):
    try:
        proc = subprocess.run(['apksigner', 'verify', '--verbose', '--print-certs',
                               '--min-sdk-version', '29', str(path)],
                              capture_output=True, text=True, timeout=120)
    except (OSError, subprocess.TimeoutExpired):
        stop('native apksigner verify unavailable')
    v2 = 'Verified using v2 scheme (APK Signature Scheme v2): true' in proc.stdout
    v3 = 'Verified using v3 scheme (APK Signature Scheme v3): true' in proc.stdout
    if proc.returncode or not (v2 or v3):
        # Only display documented public verifier status/error lines, never
        # keystore passwords, raw command environments, or entire tool output.
        print(f'CHECK: apksigner verify exit code {proc.returncode}', file=sys.stderr)
        for line in (proc.stdout + '\n' + proc.stderr).splitlines():
            stripped = line.strip()
            if re.match(r'^(Verifies$|DOES NOT VERIFY|Verified using v[0-9.]|ERROR:|WARNING:)', stripped):
                safe = stripped.replace(str(path), '<FICTIONAL_APK>')
                print('CHECK: ' + safe[:280], file=sys.stderr)
        stop('official apksigner did not verify Android v2 or v3 signature')
    # SDK 37 reports "V2 Signer:" or "V3.0 Signer:" where older
    # apksigner releases reported "Signer #1". Multiple scheme records
    # are allowed ONLY when they attest the exact same public certificate.
    cert_pattern = (r'^(?:Signer #[0-9]+|V[23](?:\.[0-9]+)? Signer:)'
                    r' certificate SHA-256 digest: ([0-9a-fA-F]{64})$')
    certs = re.findall(cert_pattern, proc.stdout, re.MULTILINE)
    raw_cert_lines = [line for line in proc.stdout.splitlines()
                      if 'certificate SHA-256 digest:' in line]
    if (not certs or len(certs) != len(raw_cert_lines)
            or len(set(value.lower() for value in certs)) != 1):
        stop('could not authenticate exactly one signing certificate identity')
    if expected_cn:
        dn_pattern = (r'^(?:Signer #[0-9]+|V[23](?:\.[0-9]+)? Signer:)'
                      r' certificate DN: .*' + re.escape(expected_cn))
        if not re.search(dn_pattern, proc.stdout, re.MULTILINE):
            stop('signed certificate does not identify a fictional-only key')
    return certs[0].lower()


def make_fictional_key_and_sign(temp, packages):
    key = temp / 'FICTIONAL-delete-after-test.p12'
    print('\nCreate a one-day FICTIONAL key. Enter a NEW TEST PASSWORD (6+ chars).', flush=True)
    print('Do NOT enter any current or future permanent ATLAS password.', flush=True)
    run(['keytool', '-genkeypair', '-noprompt', '-keystore', str(key),
         '-storetype', 'PKCS12', '-alias', FICTIONAL_ALIAS,
         '-keyalg', 'RSA', '-keysize', '2048', '-validity', '1',
         '-dname', f'CN={FICTIONAL_CN}, O=ATLAS-TOOL-SMOKE'], timeout=180)
    if not key.is_file():
        stop('fictional PKCS12 not created')
    key.chmod(0o600)
    backup = temp / 'FICTIONAL-delete-after-test.p12.age'
    print('\nPassphrase-encrypt the disposable key using official age.', flush=True)
    print('age may generate a one-time TEST recovery passphrase; do not share or reuse it.', flush=True)
    run(['age', '-p', '-o', str(backup), str(key)], timeout=180)
    if not backup.is_file():
        stop('age did not create a fictional backup')
    if backup.read_bytes() == key.read_bytes():
        stop('age backup is unexpectedly unencrypted')
    print('Now enter the fictional age TEST passphrase to verify recovery.', flush=True)
    try:
        restored = subprocess.run(['age', '-d', str(backup)],
                                  stdout=subprocess.PIPE, check=False, timeout=180)
    except (OSError, subprocess.TimeoutExpired):
        stop('age decryption unavailable')
    if restored.returncode or restored.stdout != key.read_bytes():
        stop('age fictional key backup failed independent restore comparison')
    print('PASS: native age encrypt/decrypt byte-for-byte recoverable', flush=True)

    cert = None
    for role, (apk, package) in packages.items():
        signed = temp / f'FICTIONAL_DO_NOT_INSTALL-{role}.apk'
        print(f'\nEnter the same FICTIONAL PKCS12 TEST password for {role}.', flush=True)
        print('This re-signs a copy of an existing debug APK; failure is a real compatibility finding.', flush=True)
        try:
            with open('/dev/tty', 'rb') as tty:
                run(['apksigner', 'sign', '--ks', str(key), '--ks-type', 'PKCS12',
                     '--ks-key-alias', FICTIONAL_ALIAS, '--ks-pass', 'stdin',
                     '--v2-signing-enabled', 'true',
                     '--out', str(signed), str(apk)], stdin=tty, timeout=180)
        except OSError:
            stop('no safe interactive Termux terminal for fictional password')
        found = fingerprint(signed, FICTIONAL_CN)
        if cert is not None and cert != found:
            stop('fictional signing certificates differ across APKs')
        cert = found
        check_apk(signed, package)
        print(f'PASS: official apksigner modern sign/verify + aapt for {role}', flush=True)
    print('\nFICTIONAL NATIVE TOOL SMOKE PASS (GH, age, aapt, apksigner).', flush=True)
    print('Fictional certificate SHA-256: ' + cert, flush=True)
    print('NOT G3 PASS. No permanent key, no 3->4 upgrade, no install/upload.', flush=True)


def main():
    if len(sys.argv) != 1:
        stop('no arguments accepted; this smoke canary never signs owner APKs')
    if (os.environ.get('PREFIX') != '/data/data/com.termux/files/usr'
            or os.environ.get('HOME') != '/data/data/com.termux/files/home'):
        stop('require unmodified native S24+ Termux app-private home, no proot/cloud')
    if not sys.stdin.isatty() or not sys.stdout.isatty():
        stop('require a real interactive Termux terminal')
    for name in ('gh', 'age', 'aapt', 'apksigner', 'keytool', 'java', 'python3'):
        if not shutil.which(name):
            stop('missing official Termux tool: ' + name)
    print('ATLAS FICTIONAL-ONLY v3 Termux compatibility smoke test', flush=True)
    print('Authenticates TWO EXISTING G1 GitHub ZIPs; creates only a 1-day test key.', flush=True)
    print('NO G3 or owner release key; no package install, APK install, cloud CI or upload.', flush=True)
    if input('Type FICTIONAL to authorize this local disposable test: ').strip() != 'FICTIONAL':
        stop('owner declined fictional local test')
    verify_metadata()
    with tempfile.TemporaryDirectory(prefix='.atlas-FICTIONAL-V3-REMOVE-',
                                     dir=os.environ['HOME']) as dirname:
        private = Path(dirname)
        if private.is_symlink() or private.stat().st_mode & 0o077:
            stop('private temporary directory has unsafe permissions')
        apk_inputs = {}
        for entry in ARTIFACTS:
            role = entry[4]
            apk_inputs[role] = (retrieve_one(private, entry), entry[3])
        make_fictional_key_and_sign(private, apk_inputs)
    print('PASS: disposable test PKCS12, age ciphertext, original and signed APKs deleted.', flush=True)
    print('Keep #397 DRAFT; G3 owner-held retained 3->4 proof remains NOT EXECUTED.', flush=True)


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, OSError, ValueError, EOFError) as err:
        print('NO-GO: ' + str(err), file=sys.stderr)
        print('No APK was installed or uploaded. Temporary fictional files removed.', file=sys.stderr)
        sys.exit(1)
