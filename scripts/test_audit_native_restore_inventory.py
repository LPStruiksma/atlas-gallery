#!/usr/bin/env python3
"""Adversarial fictional backup coverage tests; no Android/private data."""
from contextlib import redirect_stdout, redirect_stderr
from io import StringIO
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile

sys.path.insert(0, str(Path(__file__).parent))
from audit_native_restore_inventory import audit, inventory, main


def deny(action):
    try:
        action()
    except (ValueError, OSError):
        return
    raise AssertionError("Unsafe/incomplete app-private snapshot was accepted")


def private(path):
    path.chmod(0o700 if path.is_dir() else 0o600)


def fake(root):
    root.mkdir(mode=0o700)
    for rel in ("databases", "files", "files/datastore", "shared_prefs"):
        path = root/rel
        path.mkdir(exist_ok=True)
        private(path)
    (root/"databases/atlas_native_people.db").write_bytes(b"FAKE SQLITE FIXTURE; LOGICAL ROWS CHECKED SEPARATELY")
    (root/"shared_prefs/state.xml").write_text("<map><string name='invented'>test</string></map>")
    (root/"files/datastore/atlas.preferences_pb").write_bytes(b"\0\x01fictional bytes")
    for path in root.rglob("*"):
        private(path)


with tempfile.TemporaryDirectory() as scratch:
    outer = Path(scratch)
    backup, restored = outer/"backup", outer/"restored"
    fake(backup)
    shutil.copytree(backup, restored)
    for path in restored.rglob("*"):
        private(path)
    private(restored)
    result = audit(backup, restored)
    assert result["strict_inventory_verified"] and result["known_private_file_count"] == 3
    assert result["settings_file_count"] == 2
    assert result["unaccounted_private_files"] == 0
    assert not result["safe_to_uninstall"] and not result["people_logical_parity_verified"]
    assert "invented" not in str(result) and "state.xml" not in str(result)

    with redirect_stdout(StringIO()) as output:
        previous = sys.argv
        try:
            sys.argv = ["inventory", str(backup), str(restored)]
            assert main() == 0
        finally:
            sys.argv = previous
    assert "state.xml" not in output.getvalue()
    assert '"safe_to_uninstall": false' in output.getvalue()

    # A private file omitted from BOTH snapshots cannot be detected; the
    # report must never pretend this is independently sourced phone capture.
    assert result["capture_provenance_verified"] is False

    bad = restored/"files/settings.json"
    bad.write_bytes(b"new unknown future private setting")
    private(bad)
    deny(lambda: audit(backup, restored))
    bad.unlink()

    extra = restored/"files/secret-store"
    extra.mkdir(mode=0o700)
    deny(lambda: audit(backup, restored))
    extra.rmdir()

    sidecar = restored/"databases/atlas_native_people.db-wal"
    sidecar.write_bytes(b"uncheckpointed identities")
    private(sidecar)
    deny(lambda: audit(backup, restored))
    sidecar.unlink()

    hidden = restored/"shared_prefs/.extra.xml"
    hidden.write_text("<map/>")
    private(hidden)
    deny(lambda: audit(backup, restored))
    hidden.unlink()

    setting = restored/"shared_prefs/state.xml"
    setting.write_text("<map><string name='invented'>CHANGED</string></map>")
    private(setting)
    deny(lambda: audit(backup, restored))
    shutil.copy2(backup/"shared_prefs/state.xml", setting)

    setting.unlink()
    setting.symlink_to(backup/"shared_prefs/state.xml")
    deny(lambda: audit(backup, restored))
    setting.unlink()
    shutil.copy2(backup/"shared_prefs/state.xml", setting)

    # Hardlinked source and destination are not two independent copies.
    setting.unlink()
    os.link(backup/"shared_prefs/state.xml", setting)
    deny(lambda: audit(backup, restored))
    setting.unlink()
    shutil.copy2(backup/"shared_prefs/state.xml", setting)

    setting.chmod(0o644)
    deny(lambda: audit(backup, restored))
    setting.chmod(0o600)

    root_alias = outer/"alias"
    root_alias.symlink_to(backup, target_is_directory=True)
    deny(lambda: audit(root_alias, restored))
    root_alias.unlink()

    settings_dir = restored/"files/datastore"
    moved = restored/"files/datastore-original"
    settings_dir.rename(moved)
    settings_dir.symlink_to(moved, target_is_directory=True)
    deny(lambda: audit(backup, restored))
    settings_dir.unlink()
    moved.rename(settings_dir)

    no_settings = outer/"no_settings"
    shutil.copytree(backup, no_settings)
    for setting_path in (no_settings/"shared_prefs/state.xml",
                         no_settings/"files/datastore/atlas.preferences_pb"):
        setting_path.unlink()
    deny(lambda: inventory(no_settings))

    # Main DB need not have matching raw bytes after vacuum/backup API.
    db = restored/"databases/atlas_native_people.db"
    db.write_bytes(b"DIFFERENT SQLITE PHYSICAL LAYOUT; VERIFY LOGICAL ROWS SEPARATELY")
    private(db)
    assert audit(backup, restored)["people_logical_parity_verified"] is False

    # Negative CLI outcomes contain no raw filenames or source labels.
    bad.write_bytes(b"secret")
    with redirect_stderr(StringIO()) as errors:
        previous = sys.argv
        try:
            sys.argv = ["inventory", str(backup), str(restored)]
            try:
                main()
            except SystemExit as err:
                assert err.code == 2
            else:
                raise AssertionError("CLI unexpectedly accepted unsupported private file")
        finally:
            sys.argv = previous
    assert "settings.json" not in errors.getvalue() and "secret" not in errors.getvalue()
print("PASS: synthetic strict private-tree inventory, unknown files, WAL, permissions, links, privacy and no GO")
