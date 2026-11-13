#!/usr/bin/env python3
"""No-key, no-device regression of the public APK preflight."""
import contextlib
import hashlib
import importlib.util
import runpy
from io import StringIO
from pathlib import Path
import sys
import tempfile
from unittest.mock import patch
from subprocess import CompletedProcess

script = Path(__file__).with_name("check_apk_upgrade_signing.py")
spec = importlib.util.spec_from_file_location("atlas_apk_signing", script)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

with tempfile.TemporaryDirectory() as directory:
    old = Path(directory) / "old.apk"
    new = Path(directory) / "new.apk"
    old.write_bytes(b"synthetic stub")
    new.write_bytes(b"synthetic stub")
    signatures = {"old.apk": "a" * 64, "new.apk": "a" * 64}
    identities = {"old.apk": ("nl.atlas.gallery", 2),
                  "new.apk": ("nl.atlas.gallery", 3)}
    extra_signer = False
    mutate_during_inspection = False

    def fake_run(args, **_kwargs):
        name = Path(args[-1]).name
        if args[0] == "apksigner":
            digest = signatures[name]
            result = "Signer #1 certificate SHA-256 digest: " + digest + "\n"
            if extra_signer:
                result += "Signer #2 certificate SHA-256 digest: " + "b" * 64 + "\n"
            return CompletedProcess(args, 0, result, "")
        if args[0] == "aapt":
            if name == "new.apk" and mutate_during_inspection:
                new.write_bytes(b"substituted candidate during inspection")
            package, version = identities[name]
            return CompletedProcess(args, 0, f"package: name='{package}' versionCode='{version}' versionName='preview'\n", "")
        raise AssertionError(args)

    def check(expected, *, allow_same_version=False, expected_candidate_hash=None, expected_installed_hash=None):
        options = ["--allow-same-version"] if allow_same_version else []
        if expected_candidate_hash is not None:
            options += ["--expected-candidate-sha256", expected_candidate_hash]
        if expected_installed_hash is not None:
            options += ["--expected-installed-sha256", expected_installed_hash]
        with patch.object(module.subprocess, "run", side_effect=fake_run), \
             patch.object(sys, "argv", ["preflight", str(old), str(new)] + options), \
             contextlib.redirect_stdout(StringIO()):
            if expected == 2:
                try:
                    module.main()
                except SystemExit as ex:
                    assert ex.code == 2
                else:
                    raise AssertionError("Preflight did not fail closed")
            else:
                assert module.main() == expected

    check(0)
    signatures["new.apk"] = "b" * 64
    check(1)
    signatures["new.apk"] = "a" * 64
    identities["new.apk"] = ("another.package", 3)
    check(1)
    identities["new.apk"] = ("nl.atlas.gallery", 1)
    check(1)
    identities["new.apk"] = ("nl.atlas.gallery", 2)
    check(1)  # refuse same version for an actual release
    check(0, allow_same_version=True)  # explicit test-only reinstall
    identities["new.apk"] = ("nl.atlas.gallery", 3)
    extra_signer = True
    with patch.object(module.subprocess, "run", side_effect=fake_run):
        try:
            module.public_fingerprint("apksigner", new)
        except ValueError as error:
            assert "exactly one" in str(error)
        else:
            raise AssertionError("Multiple signing identities accepted")
    extra_signer = False
    with patch.object(module.subprocess, "run", return_value=CompletedProcess([], 2, "", "bad signature")):
        try:
            module.public_fingerprint("apksigner", old)
        except ValueError as error:
            assert "failed" in str(error)
        else:
            raise AssertionError("Invalid signature accepted")
    with patch.object(module.subprocess, "run", return_value=CompletedProcess([], 2, "", "bad package")):
        try:
            module.manifest_identity("aapt", old)
        except ValueError as error:
            assert "Could not inspect" in str(error)
        else:
            raise AssertionError("Invalid package metadata accepted")
    try:
        module.public_fingerprint("apksigner", Path(directory) / "missing.apk")
    except ValueError as error:
        assert "missing" in str(error)
    else:
        raise AssertionError("Missing APK accepted")
    with patch.object(module.subprocess, "run", side_effect=FileNotFoundError("private path")):
        try:
            module.public_fingerprint("missing-apksigner", old)
        except ValueError as error:
            assert "unavailable" in str(error) and "private path" not in str(error)
        else:
            raise AssertionError("Missing SDK inspector not blocked")
    with patch.object(module.subprocess, "run", side_effect=module.subprocess.TimeoutExpired("apksigner", 30)):
        try:
            module.public_fingerprint("apksigner", old)
        except ValueError as error:
            assert "timed out" in str(error)
        else:
            raise AssertionError("SDK inspector hang not blocked")

    # Exact artifact bytes are externally identifiable, not just their public
    # signer digest; reject a swapped file even when fake SDK metadata matches.
    old_digest = hashlib.sha256(old.read_bytes()).hexdigest()
    new_digest = hashlib.sha256(new.read_bytes()).hexdigest()
    check(0, expected_candidate_hash=new_digest.upper(),
          expected_installed_hash=old_digest)
    with patch.object(module.subprocess, "run", side_effect=fake_run), \
         patch.object(sys, "argv", ["preflight", str(old), str(new)]), \
         contextlib.redirect_stdout(StringIO()) as output:
        assert module.main() == 0
    assert "apk_sha256=" + old_digest in output.getvalue()
    assert "apk_sha256=" + new_digest in output.getvalue()
    check(2, expected_candidate_hash="b" * 64)
    check(2, expected_installed_hash="xyz")
    mutate_during_inspection = True
    # main() exits 2 via argparse: collect the exception without leaking any
    # real paths, private row contents or untrusted stderr.
    with patch.object(module.subprocess, "run", side_effect=fake_run), \
         patch.object(sys, "argv", ["preflight", str(old), str(new)]), \
         contextlib.redirect_stdout(StringIO()):
        try:
            module.main()
        except SystemExit as exc:
            assert exc.code == 2
        else:
            raise AssertionError("Changed APK passed the inspector")
    mutate_during_inspection = False
    new.write_bytes(b"synthetic stub")
    # Reject a copied symlink to an unrelated APK before calling SDK commands.
    linked = Path(directory) / "linked.apk"
    linked.symlink_to(new)
    try:
        module.artifact_sha256(linked)
    except ValueError as exc:
        assert "linked" in str(exc)
    else:
        raise AssertionError("Symlinked APK accepted")
    # Reject an old-and-candidate path alias: no actual versioned upgrade.
    with patch.object(sys, "argv", ["preflight", str(old), str(old)]):
        try:
            module.main()
        except SystemExit as exc:
            assert exc.code == 2
        else:
            raise AssertionError("Same APK path accepted")

print("PASS: signer, multi-signer, invalid cert, package, version, SDK errors and missing APK, independent SHA256 and changed/symlinked input")

# The dedicated release-evidence files are within Lane E; workflow files are
# owned by Lane A under #83. Until the workflow has explicit path filters,
# run both synthetic safety suites from its existing lightweight signing gate.
# No Android SDK, APK installation, private files or credentials are required.
# Recovery CSV checks now bind the parser FD to the original pinned inode;
# fake path swaps and in-file mutations must reject rather than import names.
# The recovery-bundle suite also rejects late edits to either restored or
# original People/settings tree after an earlier logical parity pass.
# The A/B comparator also rejects self-contradictory refresh budgets,
# pooled/individual frame rates, PSS and malformed numeric metadata.
# Different trial files can replay overlapping Android frame IDs; the same
# already-wired synthetic viewer suite rejects even byte-different replays.
# The viewer suite also rejects repeated in-file frames, stitched framestats
# windows, and nonmonotonic records; do not mistake their count for new evidence.
for suite in ("test_verify_native_restore_evidence.py", "test_summarize_viewer_perf.py", "test_audit_recovery_ci_log.py", "test_generate_viewer_fixtures.py", "test_compare_viewer_perf.py", "test_verify_viewer_fixtures.py", "test_audit_native_restore_inventory.py", "test_check_offline_recovery_bundle.py", "test_verify_native_people_recovery_csv.py"):
    runpy.run_path(str(Path(__file__).with_name(suite)), run_name="__main__")

