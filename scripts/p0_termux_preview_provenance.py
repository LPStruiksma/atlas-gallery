#!/usr/bin/env python3
"""Fail-closed, independent GitHub Actions provenance for unprivileged preview APKs.

The downloaded ZIP and its member checksums are untrusted until the ZIP digest
matches GitHub's authenticated Actions artifact API. No signing/key access here.
"""
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import zipfile

REPO = 'LPStruiksma/atlas-gallery'
REPO_ID = 1417745611  # independent immutable GitHub repository identity
SOURCE = '36bd476530abad6ebe794bd2d00128d83940518a'
WORKFLOW = 'p0-cloud-g3.yml'
WORKFLOW_NAME = 'ATLAS P0 Phone G3 Cloud Handoff (manual only)'
ARTIFACT = 'ATLAS-P0-G3-preview-apks'
APKS = ('A-app.apk', 'A-test.apk', 'B-app.apk', 'B-test.apk')
NAMES = set(APKS) | {'SHA256SUMS', 'source-commit.txt', 'artifact-manifest.json'}
# Reviewed Lane A #396 cloud Generate Git blobs. These are independently pinned,
# NOT learned from main, the run payload, the downloaded APK ZIP, or a manifest.
# If A changes any executed generator input, a new independent review and E pin
# update are required BEFORE any owner-held signing.
REVIEWED_GENERATOR_BLOBS = {
    '.github/workflows/p0-cloud-g3.yml': '3ce29cbd6ff88d4a40501b25a2bc55ecf0fe0c1e',
    'scripts/p0_cloud_g3_contract.py': '90dbc29cb51e6b5905d8af62b6209b8dcf1be218',
    'scripts/test_p0_cloud_g3_contract.py': 'abe54869a1fd52ec0ce19a35b5b2ba725d509e53',
}
HEX40 = re.compile(r'[0-9a-f]{40}\Z')
HEX64 = re.compile(r'[0-9a-f]{64}\Z')


def reject(why):
    raise ValueError('NO-GO: untrusted preview provenance: ' + why)


def api_json(endpoint):
    proc = subprocess.run(['gh', 'api', endpoint], capture_output=True, timeout=40)
    if proc.returncode:
        reject('GitHub authenticated API unavailable (check gh auth status)')
    try:
        result = json.loads(proc.stdout)
    except (ValueError, UnicodeDecodeError):
        reject('GitHub API did not return valid JSON')
    if not isinstance(result, dict):
        reject('GitHub API response must be an object')
    return result


def sha256(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


def require_hex(value, pattern, field):
    if not isinstance(value, str) or not pattern.fullmatch(value):
        reject('invalid ' + field)


def stage(run_id, output):
    if not re.fullmatch(r'[0-9]{6,16}', run_id):
        reject('run ID must be 6–16 decimal digits')
    if not output.is_dir() or any(output.iterdir()) or output.is_symlink():
        reject('require an empty private staging directory')

    repo = api_json(f'repos/{REPO}')
    if repo.get('full_name') != REPO or repo.get('private') is not True or repo.get('id') != REPO_ID:
        reject('wrong repo identity or not private')
    repo_id = REPO_ID
    wf = api_json(f'repos/{REPO}/actions/workflows/{WORKFLOW}')
    if (wf.get('path') != f'.github/workflows/{WORKFLOW}'
            or type(wf.get('id')) is not int or wf['id'] <= 0):
        reject('unexpected GitHub workflow identity')
    run = api_json(f'repos/{REPO}/actions/runs/{run_id}')
    head = run.get('head_sha')
    require_hex(head, HEX40, 'generator SHA')
    if (run.get('id') != int(run_id) or run.get('workflow_id') != wf['id']
            or run.get('name') != WORKFLOW_NAME
            or run.get('repository', {}).get('id') != repo_id
            or run.get('head_repository', {}).get('id') != repo_id
            or run.get('event') != 'workflow_dispatch'
            or run.get('head_branch') != 'main'
            or run.get('status') != 'completed'
            or run.get('conclusion') != 'success'):
        reject('not a successful generate run of the trusted main workflow/repository')
    # A successful main run with an authentic artifact is NOT sufficient:
    # GitHub must independently attest that this immutable run HEAD contained
    # the exact independently reviewed generating workflow and executed helpers.
    # These Contents API paths are fixed literals, never from an APK ZIP.
    for path, expected_blob in REVIEWED_GENERATOR_BLOBS.items():
        entry = api_json(f'repos/{REPO}/contents/{path}?ref={head}')
        sha = entry.get('sha')
        if (entry.get('path') != path or entry.get('type') != 'file'
                or not isinstance(sha, str) or not HEX40.fullmatch(sha)
                or sha != expected_blob):
            reject('unreviewed generator Git blob at immutable run HEAD: ' + path)
    # A workflow has generate and verify operations; only generate uploads this name.
    listing = api_json(f'repos/{REPO}/actions/runs/{run_id}/artifacts?per_page=100')
    artifacts = listing.get('artifacts')
    if (type(listing.get('total_count')) is not int
            or not isinstance(artifacts, list)
            or listing['total_count'] != len(artifacts)):
        reject('incomplete artifact listing (pagination is refused)')
    matches = [a for a in artifacts if isinstance(a, dict) and a.get('name') == ARTIFACT]
    if len(matches) != 1:
        reject('expected exactly one preview artifact from this run')
    a = matches[0]
    digest = a.get('digest')
    if not isinstance(digest, str) or not digest.startswith('sha256:'):
        reject('missing independent Actions archive SHA256')
    digest = digest[7:]
    require_hex(digest, HEX64, 'archive digest')
    artifact_id = a.get('id')
    link = a.get('workflow_run')
    if (type(artifact_id) is not int or artifact_id <= 0 or a.get('expired') is not False
            or type(a.get('size_in_bytes')) is not int
            or not (0 < a['size_in_bytes'] <= 512 * 1024 * 1024)
            or not isinstance(link, dict)
            or link.get('id') != int(run_id)
            or link.get('repository_id') != repo_id
            or link.get('head_repository_id') != repo_id
            or link.get('head_sha') != head
            or link.get('head_branch') != 'main'
            or a.get('archive_download_url') != f'https://api.github.com/repos/{REPO}/actions/artifacts/{artifact_id}/zip'):
        reject('artifact not bound to authenticated main generating run')

    archive = output / '.authenticated-preview.zip'
    with archive.open('xb') as dest:
        proc = subprocess.run(['gh', 'api', f'repos/{REPO}/actions/artifacts/{artifact_id}/zip'],
                              stdout=dest, stderr=subprocess.PIPE, timeout=180)
    if proc.returncode:
        reject('authenticated GitHub archive download failed')
    if archive.stat().st_size > 512 * 1024 * 1024:
        reject('preview archive too large')
    if sha256(archive) != digest:
        reject('downloaded archive SHA256 differs from authenticated GitHub API digest')
    with zipfile.ZipFile(archive) as z:
        members = z.infolist()
        if len(members) != len(NAMES) or {m.filename for m in members} != NAMES:
            reject('unexpected/missing/duplicate preview ZIP entries')
        if sum(m.file_size for m in members) > 1024 * 1024 * 1024:
            reject('preview ZIP decompressed size too large')
        for member in members:
            if (member.is_dir() or member.file_size > 512 * 1024 * 1024
                    or (member.external_attr >> 16) & 0o170000 == 0o120000):
                reject('unsafe preview ZIP member')
            with z.open(member) as src, (output / member.filename).open('xb') as dst:
                for chunk in iter(lambda: src.read(1024 * 1024), b''):
                    dst.write(chunk)
    archive.unlink()
    if (output / 'source-commit.txt').read_text() != SOURCE + '\n':
        reject('pinned source mismatch')
    try:
        manifest = json.loads((output / 'artifact-manifest.json').read_text())
    except (ValueError, UnicodeDecodeError):
        reject('invalid preview manifest JSON')
    if (not isinstance(manifest, dict)
            or set(manifest) != {'schema', 'source_sha', 'generator_sha', 'package', 'versions', 'apks'}
            or manifest.get('schema') != 'atlas.p0.preview-apks.v1'
            or manifest.get('source_sha') != SOURCE
            or manifest.get('generator_sha') != head
            or manifest.get('package') != 'nl.atlas.gallery'
            or manifest.get('versions') != {'v3': 3, 'v4': 4}
            or not isinstance(manifest.get('apks'), dict)
            or set(manifest['apks']) != set(APKS)):
        reject('manifest contradicts authenticated generator/source')
    lines = []
    for name in APKS:
        expected = manifest['apks'][name]
        require_hex(expected, HEX64, name + ' digest')
        if sha256(output / name) != expected:
            reject('APK differs from authenticated archive manifest: ' + name)
        lines.append(f'{expected}  {name}\n')
    if (output / 'SHA256SUMS').read_text() != ''.join(lines):
        reject('checksum text differs from authenticated archive manifest')
    print(f'AUTHENTICATED: {REPO}, workflow {wf["id"]}, generate run {run_id}, generator {head}, artifact {artifact_id}', flush=True)


def main():
    if len(sys.argv) != 3:
        reject('usage: provenance.py RUN_ID EMPTY_PRIVATE_STAGE')
    stage(sys.argv[1], Path(sys.argv[2]))


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, zipfile.BadZipFile, zipfile.LargeZipFile,
            subprocess.TimeoutExpired) as exc:
        print('NO-GO: ' + str(exc), file=sys.stderr)
        sys.exit(1)
