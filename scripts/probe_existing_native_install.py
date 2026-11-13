#!/usr/bin/env python3
"""Offline, read-only Shizuku/rish probe for an existing ATLAS debug installation.

It never reads SQLite rows, copies any data, calls app activities, or prints
package paths, names or other private data. Do not mistake access for a backup.
"""
import json
import re
import shutil
import subprocess
import sys
from typing import Callable

PACKAGE = "nl.atlas.gallery"
PROBES = (
    ("installed", f"pm path {PACKAGE}", "package:"),
    ("debug_run_as", f"run-as {PACKAGE} sh -c 'echo ATLAS_RUNAS_OK'", "ATLAS_RUNAS_OK"),
    ("private_people_db_readable",
     f"run-as {PACKAGE} sh -c 'test -r databases/atlas_native_people.db && echo ATLAS_DB_OK'",
     "ATLAS_DB_OK"),
    ("private_settings_directory_present",
     f"run-as {PACKAGE} sh -c 'test -d shared_prefs && echo ATLAS_PREFS_OK'",
     "ATLAS_PREFS_OK"),
    ("private_datastore_directory_present",
     f"run-as {PACKAGE} sh -c 'test -d files/datastore && echo ATLAS_DATASTORE_OK'",
     "ATLAS_DATASTORE_OK"),
)


def _verified_response(key: str, output: str, marker: str) -> bool:
    """Accept a COMPLETE, exact capability response, never a substring.

    pm path may return multiple APK splits, but each expected line must be
    a package: absolute APK path and one must end with /base.apk. The returned
    path is never recorded or printed. Echo probes must output one exact token.
    """
    if not isinstance(output, str) or len(output) > 4096 or "\x00" in output:
        return False
    lines = output.splitlines()
    if key == "installed":
        return bool(lines and all(re.fullmatch(r"package:/[^\r\n]+\.apk", line) for line in lines)
                    and any(line.endswith("/base.apk") for line in lines))
    return lines == [marker]


def probe(run: Callable[[str], tuple[bool, str]]) -> dict:
    """Inject a fake runner in CI; only print fixed-status fields."""
    states = {}
    for key, command, marker in PROBES:
        try:
            success, stdout = run(command)
            # Ignore all diagnostic output except a deterministic marker.
            states[key] = bool(success and _verified_response(key, stdout, marker))
        except (OSError, subprocess.TimeoutExpired):
            states[key] = False

    if not states["installed"]:
        states["debug_run_as"] = False
        states["private_people_db_readable"] = False
        states["private_settings_directory_present"] = False
        states["private_datastore_directory_present"] = False
    elif not states["debug_run_as"]:
        states["private_people_db_readable"] = False
        states["private_settings_directory_present"] = False
        states["private_datastore_directory_present"] = False

    return {
        "probe": "atlas_native_existing_install_v1",
        "read_only": True,
        "package_installed": states["installed"],
        "debug_run_as_available": states["debug_run_as"],
        "private_people_db_readable": states["private_people_db_readable"],
        "private_settings_directory_present": states["private_settings_directory_present"],
        "private_datastore_directory_present": states["private_datastore_directory_present"],
        "signer_compatibility_verified": False,
        "consistent_backup_created": False,
        "safe_to_uninstall": False,
        "interpretation": (
            "An inaccessible database is not proof that People data is absent; "
            "Shizuku permission, app debuggability, or device policy may block run-as. "
            "Access is not a backup and does not authorize replacement or uninstall."
        ),
    }


def rish_run(command: str) -> tuple[bool, str]:
    process = subprocess.run(
        ["rish", "-c", command], text=True, encoding="utf-8",
        errors="replace", capture_output=True, timeout=15, check=False
    )
    # No stdout/stderr from rish is printed, written to disk, or uploaded.
    # Truncated responses could hide extra diagnostics and falsely satisfy a
    # marker. Mark them unavailable rather than trusting the leading bytes.
    if len(process.stdout) > 4096:
        return False, ""
    return process.returncode == 0, process.stdout


def main() -> int:
    if shutil.which("rish") is None:
        print(json.dumps({
            "probe": "atlas_native_existing_install_v1",
            "read_only": True,
            "bridge_available": False,
            "signer_compatibility_verified": False,
            "consistent_backup_created": False,
            "safe_to_uninstall": False,
            "interpretation": "No rish command found in PATH; no device check was attempted."
        }, indent=2))
        return 2
    print(json.dumps(probe(rish_run), indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
