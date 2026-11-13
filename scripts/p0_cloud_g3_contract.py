#!/usr/bin/env python3
"""ATLAS P0 cloud handoff: public APK metadata and exact payload verification.

No credential handling, signing, phone operations or network I/O here.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import zipfile

PIN = '36bd476530abad6ebe794bd2d00128d83940518a'
PACKAGE = 'nl.atlas.gallery'
NAMES = {(v, k): f'{"A" if v == 3 else "B"}-{k}.apk' for v in (3, 4) for k in ('app', 'test')}
SIGNED = NAMES.copy()
HEX = re.compile(r'[a-f0-9]{64}\Z')
VERSION_LINE = re.compile(r'(?m)^(\s*versionCode\s*=\s*)(\d+)(\s*)$')
PACKAGE_LINE = re.compile(r"^package: name='([^']+)' versionCode='(\d+)'", re.M)
CERT_LINE = re.compile(r'^Signer #1 certificate SHA-256 digest: ([a-fA-F0-9]{64})$', re.M)


def fail(message):
    raise ValueError('NO-GO: ' + message)


def digest(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as f:
        for block in iter(lambda: f.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def run(*cmd):
    return subprocess.run(cmd, check=True, capture_output=True, text=True).stdout


def bump(path):
    p = Path(path)
    data = p.read_text()
    matches = VERSION_LINE.findall(data)
    if len(matches) != 1 or int(matches[0][1]) != 3:
        fail('versionCode must be exactly one literal 3 in pinned source')
    changed = VERSION_LINE.sub(lambda m: m[1] + '4' + m[3], data, count=1)
    if changed == data or VERSION_LINE.findall(changed)[0][1] != '4':
        fail('only versionCode 3→4 permitted')
    p.write_text(changed)


def metadata(apk, aapt):
    output = run(aapt, 'dump', 'badging', str(apk))
    m = PACKAGE_LINE.search(output)
    if not m:
        fail('APK has no parseable package/version metadata')
    return m[1], int(m[2])


def cert(apk, signer):
    output = run(signer, 'verify', '--print-certs', str(apk))
    hashes = CERT_LINE.findall(output)
    if len(hashes) != 1 or not HEX.fullmatch(hashes[0].lower()):
        fail('APK verification requires exactly one signer certificate')
    return hashes[0].lower()


def payload(apk):
    """Hash every non-signature ZIP payload entry, detecting dupes and bombs."""
    entries = {}
    with zipfile.ZipFile(apk) as z:
        infos = z.infolist()
        if len(infos) > 100_000 or sum(i.file_size for i in infos) > 300 * 1024 * 1024:
            fail('APK ZIP aggregate exceeds bounded limits')
        # Streaming each ZIP member below also checks CRC; avoid a redundant
        # unbounded full decompression pass on untrusted signed APK input.
        for info in infos:
            name = info.filename
            if name in entries:
                fail('duplicate APK ZIP member')
            if info.is_dir():
                continue
            # AOSP apksigner strips old JAR signatures and can replace them.
            if re.fullmatch(r'META-INF/(?:MANIFEST\.MF|[^/]+\.(?:SF|RSA|DSA|EC))', name, re.I):
                continue
            if info.file_size > 250 * 1024 * 1024:
                fail('oversized APK ZIP entry')
            with z.open(info) as f:
                h = hashlib.sha256()
                for block in iter(lambda: f.read(1024 * 1024), b''):
                    h.update(block)
            entries[name] = h.hexdigest()
    if not entries or 'AndroidManifest.xml' not in entries or 'classes.dex' not in entries:
        fail('missing Android manifest/classes payload')
    return entries


def preview(args):
    dest = Path(args.output)
    if dest.exists():
        fail('preview directory already exists (no overwrite)')
    dest.mkdir(parents=True, mode=0o700)
    if not isinstance(args.generator_sha, str) or not re.fullmatch(r'[a-fA-F0-9]{40}', args.generator_sha):
        fail('generator workflow commit must be exactly 40 hexadecimal characters')
    manifest = {'schema': 'atlas.p0.preview-apks.v1', 'source_sha': PIN,
                'generator_sha': args.generator_sha.lower(),
                'package': PACKAGE, 'versions': {'v3': 3, 'v4': 4}, 'apks': {}}
    for (version, kind), name in NAMES.items():
        tree = Path(args.v3 if version == 3 else args.v4)
        loc = ('app/build/outputs/apk/debug/app-debug.apk' if kind == 'app'
               else 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
        src = tree / loc
        if not src.is_file() or src.is_symlink():
            fail('missing input APK ' + name)
        pkg, code = metadata(src, args.aapt)
        expected = PACKAGE if kind == 'app' else PACKAGE + '.test'
        if pkg != expected or (kind == 'app' and code != version):
            fail('preview APK package/version mismatch ' + name)
        cert(src, args.apksigner)
        shutil.copyfile(src, dest / name)
        manifest['apks'][name] = digest(dest / name)
    (dest / 'artifact-manifest.json').write_text(json.dumps(manifest, indent=2, sort_keys=True) + '\n')
    # Lane E Termux signer consumes these TWO plain-text files verbatim.
    (dest / 'source-commit.txt').write_text(PIN + '\n')
    (dest / 'SHA256SUMS').write_text(''.join(
        f'{manifest["apks"][name]}  {name}\n' for name in NAMES.values()))
    print('PASS: pinned preview A/B app/test pairs, source-commit.txt, SHA256SUMS')


def validate(args):
    pre = Path(args.preview)
    signed = Path(args.signed)
    manifest_file = pre / 'artifact-manifest.json'
    if not pre.is_dir() or not signed.is_dir():
        fail('preview and signed directories required')
    if {x.name for x in pre.iterdir()} != set(NAMES.values()) | {
        'artifact-manifest.json', 'source-commit.txt', 'SHA256SUMS'
    }:
        fail('preview artifact must contain exactly four APKs and provenance files')
    if {x.name for x in signed.iterdir()} != set(SIGNED.values()):
        fail('signed transport must contain exactly four owner-signed APKs')
    manifest = json.loads(manifest_file.read_text())
    if not isinstance(args.generator_sha, str) or not re.fullmatch(r'[a-fA-F0-9]{40}', args.generator_sha):
        fail('untrusted generation run SHA')
    if set(manifest) != {'schema', 'source_sha', 'generator_sha', 'package', 'versions', 'apks'} or (
        manifest['schema'] != 'atlas.p0.preview-apks.v1' or manifest['source_sha'] != PIN
        or manifest['generator_sha'] != args.generator_sha.lower()
        or manifest['package'] != PACKAGE or manifest['versions'] != {'v3': 3, 'v4': 4}
        or set(manifest['apks']) != set(NAMES.values())
    ):
        fail('incorrect pinned preview manifest schema/source')
    if (pre / 'source-commit.txt').read_text() != PIN + '\n':
        fail('Termux source-commit.txt does not match pinned source')
    expected_lines = ''.join(
        f'{manifest["apks"][name]}  {name}\n' for name in NAMES.values())
    if (pre / 'SHA256SUMS').read_text() != expected_lines:
        fail('Termux SHA256SUMS disagrees with JSON manifest')
    expected_cert = args.fingerprint.lower()
    if not HEX.fullmatch(expected_cert):
        fail('public owner certificate fingerprint must be 64 hexadecimal characters')
    report = {'schema': 'atlas.p0.signed-apks-verified.v1', 'source_sha': PIN,
              'generator_sha': args.generator_sha.lower(),
              'preview_manifest_sha256': digest(manifest_file),
              'signer_certificate_sha256': expected_cert, 'apks': {}}
    for (version, kind), original_name in NAMES.items():
        original, output = pre / original_name, signed / SIGNED[(version, kind)]
        if not original.is_file() or not output.is_file() or output.is_symlink():
            fail('missing or unsafe APK ' + original_name)
        old_hash = manifest['apks'][original_name]
        if not isinstance(old_hash, str) or not HEX.fullmatch(old_hash) or digest(original) != old_hash:
            fail('preview SHA256 mismatch ' + original_name)
        if payload(original) != payload(output):
            fail('signed APK differs in executable/resources payload ' + original_name)
        pkg, code = metadata(output, args.aapt)
        wanted_pkg = PACKAGE if kind == 'app' else PACKAGE + '.test'
        if pkg != wanted_pkg or (kind == 'app' and code != version):
            fail('signed APK package/version mismatch ' + original_name)
        if cert(output, args.apksigner) != expected_cert:
            fail('owner signer certificate mismatch ' + original_name)
        report['apks'][SIGNED[(version, kind)]] = {
            'sha256': digest(output), 'preimage_sha256': old_hash,
            'package': pkg, 'version_code': code,
        }
    output = Path(args.output)
    if output.exists():
        fail('evidence file already exists')
    output.write_text(json.dumps(report, indent=2, sort_keys=True) + '\n')
    print('PASS: all four verified APK signatures, source-bound payloads, package and 3→4 version')


def main():
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest='command', required=True)
    b = sub.add_parser('bump')
    b.add_argument('gradle_file')
    p = sub.add_parser('preview')
    p.add_argument('--v3', required=True)
    p.add_argument('--v4', required=True)
    p.add_argument('--output', required=True)
    p.add_argument('--generator-sha', required=True)
    p.add_argument('--aapt', required=True)
    p.add_argument('--apksigner', required=True)
    v = sub.add_parser('verify-signed')
    v.add_argument('--preview', required=True)
    v.add_argument('--signed', required=True)
    v.add_argument('--output', required=True)
    v.add_argument('--generator-sha', required=True)
    v.add_argument('--fingerprint', required=True)
    v.add_argument('--aapt', required=True)
    v.add_argument('--apksigner', required=True)
    args = parser.parse_args()
    try:
        if args.command == 'bump':
            bump(args.gradle_file)
        elif args.command == 'preview':
            preview(args)
        else:
            validate(args)
    except (OSError, ValueError, zipfile.BadZipFile, subprocess.CalledProcessError) as exc:
        print(f'NO-GO: {exc}', file=sys.stderr)
        sys.exit(1)

if __name__ == '__main__':
    main()
