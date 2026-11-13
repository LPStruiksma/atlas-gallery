#!/usr/bin/env python3
"""Fail-closed preflight for an in-place ATLAS APK upgrade.

Inspects public certificate and manifest metadata only. Does not read signing
secrets, modify APKs or access a phone. Requires Android SDK apksigner + aapt.
This is a necessary preflight, not a substitute for testing adb install -r.
"""
from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path
import re
import stat
import subprocess

CERT = re.compile(r"^Signer #([0-9]+) certificate SHA-256 digest:\s*([0-9a-fA-F:]{64,95})\s*$", re.M)
PACKAGE = re.compile(r"^package:\s+name='([^']+)'\s+versionCode='([0-9]+)'", re.M)


def artifact_sha256(apk: Path) -> str:
    """Hash exact local APK bytes; refuse symlinks and moving targets.

    A stable digest binds the two independent Android SDK inspection passes to
    the same file bytes. It does not establish which APK is on a real phone.
    """
    if apk.is_symlink() or not apk.is_file():
        raise ValueError("APK missing, unreadable or linked")
    before = apk.stat()
    if not stat.S_ISREG(before.st_mode) or before.st_size == 0:
        raise ValueError("APK must be a nonempty regular file")
    digest = hashlib.sha256()
    try:
        with apk.open("rb") as stream:
            opened = stream.fileno()
            actual = os.fstat(opened)
            if (actual.st_dev, actual.st_ino) != (before.st_dev, before.st_ino):
                raise ValueError("APK changed before hashing")
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(chunk)
    except OSError:
        raise ValueError("APK unreadable during hashing") from None
    after = apk.stat()
    old = (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns, before.st_ctime_ns)
    new = (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns, after.st_ctime_ns)
    if old != new or apk.is_symlink():
        raise ValueError("APK changed during inspection")
    return digest.hexdigest()


def _sdk_command(argv: list[str]) -> subprocess.CompletedProcess:
    """Bound SDK tool execution; never expose raw tool paths or accidental logs."""
    try:
        return subprocess.run(argv, capture_output=True, text=True,
                              check=False, timeout=30)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise ValueError("Android SDK inspector unavailable or timed out") from None


def public_fingerprint(apksigner: str, apk: Path) -> str:
    if apk.is_symlink() or not apk.is_file():
        raise ValueError("APK missing or linked")
    proc = _sdk_command([apksigner, "verify", "--print-certs", str(apk)])
    if proc.returncode:
        raise ValueError("APK signature verification failed")
    certs = CERT.findall(proc.stdout)
    # Signing lineage and multiple signer support are deliberately NOT guessed.
    if len(certs) != 1 or certs[0][0] != "1":
        raise ValueError(f"Expected exactly one verified APK signer; found {len(certs)}")
    digest = certs[0][1].replace(":", "").lower()
    if len(digest) != 64:
        raise ValueError("Unexpected certificate SHA-256 length")
    return digest


def manifest_identity(aapt: str, apk: Path) -> tuple[str, int]:
    if not apk.is_file():
        raise ValueError(f"APK not found: {apk}")
    proc = _sdk_command([aapt, "dump", "badging", str(apk)])
    if proc.returncode:
        raise ValueError("Could not inspect Android package/version")
    m = PACKAGE.search(proc.stdout)
    if not m:
        raise ValueError("Cannot establish package name and versionCode")
    return m.group(1), int(m.group(2))


def main() -> int:
    parser = argparse.ArgumentParser(description="Check public signer, package and version before upgrading ATLAS")
    parser.add_argument("installed_apk", type=Path, help="APK currently installed on the device")
    parser.add_argument("candidate_apk", type=Path, help="Candidate replacement APK")
    parser.add_argument("--apksigner", default="apksigner", help="Android SDK apksigner path")
    parser.add_argument("--aapt", default="aapt", help="Android SDK aapt path")
    parser.add_argument("--expected-package", default="nl.atlas.gallery")
    parser.add_argument("--allow-same-version", action="store_true",
                        help="Explicitly accept same versionCode for controlled reinstall/testing only")
    parser.add_argument("--expected-installed-sha256", help="Independent SHA-256 of the exact installed APK bytes")
    parser.add_argument("--expected-candidate-sha256", help="Independent CI SHA-256 for the candidate APK artifact")
    args = parser.parse_args()
    try:
        if args.installed_apk.resolve() == args.candidate_apk.resolve():
            raise ValueError("Old and candidate APK files must be distinct")
        old_hash_before = artifact_sha256(args.installed_apk)
        new_hash_before = artifact_sha256(args.candidate_apk)
        old_signer = public_fingerprint(args.apksigner, args.installed_apk)
        new_signer = public_fingerprint(args.apksigner, args.candidate_apk)
        old_package, old_code = manifest_identity(args.aapt, args.installed_apk)
        new_package, new_code = manifest_identity(args.aapt, args.candidate_apk)
        if old_hash_before != artifact_sha256(args.installed_apk):
            raise ValueError("Installed APK bytes changed during inspection")
        if new_hash_before != artifact_sha256(args.candidate_apk):
            raise ValueError("Candidate APK bytes changed during inspection")
        for label, actual, expected in (
            ("installed", old_hash_before, args.expected_installed_sha256),
            ("candidate", new_hash_before, args.expected_candidate_sha256),
        ):
            if expected is not None:
                if not re.fullmatch(r"[0-9a-fA-F]{64}", expected) or actual != expected.lower():
                    raise ValueError(f"{label.capitalize()} APK independent SHA-256 does not match")
    except ValueError as error:
        parser.exit(2, f"Cannot establish upgrade safety: {error}\n")
    except OSError:
        # OS exceptions may contain a private absolute pathname; suppress it.
        parser.exit(2, "Cannot establish upgrade safety: APK path unavailable or unreadable\n")

    print(f"Installed: {old_package} versionCode={old_code} signer={old_signer} apk_sha256={old_hash_before}")
    print(f"Candidate: {new_package} versionCode={new_code} signer={new_signer} apk_sha256={new_hash_before}")
    if old_signer != new_signer:
        print("BLOCKED: Different signing certificates. Do NOT uninstall the existing app.")
        return 1
    if old_package != args.expected_package or new_package != args.expected_package:
        print("BLOCKED: APK package identity is not the expected ATLAS package.")
        return 1
    if new_code < old_code:
        print("BLOCKED: Candidate versionCode is lower than the installed versionCode.")
        return 1
    if new_code == old_code and not args.allow_same_version:
        print("BLOCKED: Same versionCode. Build a versioned upgrade; only testing may pass --allow-same-version.")
        return 1
    print("PREFLIGHT PASS: public signer, package and version are compatible.")
    print("Still required: tested in-place install and People database preservation; do not uninstall.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
