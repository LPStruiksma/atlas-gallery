"""Synthetic privacy contract for the read-only ATLAS install probe."""
import importlib.util
import json
import subprocess
from unittest.mock import patch
from pathlib import Path

path = Path(__file__).with_name("probe_existing_native_install.py")
spec = importlib.util.spec_from_file_location("atlas_phone_probe", path)
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


def responder(mapping):
    def run(command):
        return mapping.get(command, (False, ""))
    return run


commands = {key: (cmd, marker) for key, cmd, marker in m.PROBES}
good = {cmd: (True, ("package:/data/app/synthetic/base.apk" if key == "installed" else marker))
        for key, (cmd, marker) in commands.items()}
status = m.probe(responder(good))
assert status["package_installed"]
assert status["debug_run_as_available"]
assert status["private_people_db_readable"]
assert status["private_settings_directory_present"]
assert status["private_datastore_directory_present"]
assert not status["signer_compatibility_verified"]
assert not status["consistent_backup_created"]
assert not status["safe_to_uninstall"]
assert status["read_only"]

# A Shizuku/Android error may reveal a package path or app private data;
# the report must never copy it, even if the process exits zero.
sensitive = "Maria van Test /storage/emulated/0/DCIM/private.jpg /data/user/0"
untrusted = {cmd: (True, sensitive) for key, (cmd, marker) in commands.items()}
untrusted[commands["installed"][0]] = (True, "package:/data/app/real-id/base.apk")
status = m.probe(responder(untrusted))
assert status["package_installed"]
assert not status["debug_run_as_available"]
assert not status["private_people_db_readable"]
assert not status["private_datastore_directory_present"]
assert sensitive not in json.dumps(status)
assert "base.apk" not in json.dumps(status)

# Even if a bridge reports an inaccessible app, don't claim data absence
# or safety to uninstall. Failure never mutates anything.
status = m.probe(lambda _: (False, ""))
assert not status["package_installed"]
assert not status["private_people_db_readable"]
assert not status["private_datastore_directory_present"]
assert not status["safe_to_uninstall"]

status = m.probe(lambda _: (_ for _ in ()).throw(subprocess.TimeoutExpired("rish", 15)))
assert not status["package_installed"]
assert not status["safe_to_uninstall"]


# Reject marker substrings and apparent successes embedded in diagnostics.
base = dict(good)
for key in ("debug_run_as", "private_people_db_readable",
            "private_settings_directory_present", "private_datastore_directory_present"):
    command, marker = commands[key]
    poisoned = dict(base)
    poisoned[command] = (True, "WARNING: not " + marker + " yet")
    response = m.probe(responder(poisoned))
    actual_field = {
        "debug_run_as": "debug_run_as_available",
        "private_people_db_readable": "private_people_db_readable",
        "private_settings_directory_present": "private_settings_directory_present",
        "private_datastore_directory_present": "private_datastore_directory_present",
    }[key]
    assert response[actual_field] is False

# Successful run-as followed by extra stdout is *not* an exact probe.
poisoned = dict(base)
poisoned[commands["debug_run_as"][0]] = (True, "ATLAS_RUNAS_OK\nUnexpected warning")
assert m.probe(responder(poisoned))["debug_run_as_available"] is False

# A successful pm path response may list multiple split APKs, but every line
# must be strictly shaped and include a base.apk.
poisoned = dict(base)
poisoned[commands["installed"][0]] = (True,
    "package:/data/app/fictional/base.apk\npackage:/data/app/fictional/split_en.apk\n")
assert m.probe(responder(poisoned))["package_installed"]
for bad in ("package:", "not package:/data/app/base.apk",
            "package:/data/app/split_en.apk",
            "package:/data/app/base.apk\nError: package unavailable",
            "package:/data/app/base.apk "):
    poisoned[commands["installed"][0]] = (True, bad)
    assert not m.probe(responder(poisoned))["package_installed"]

# Spoofed output with a failing process status is never accepted.
poisoned = dict(base)
poisoned[commands["debug_run_as"][0]] = (False, commands["debug_run_as"][1])
assert not m.probe(responder(poisoned))["debug_run_as_available"]

# Truncated bridge output is not a complete proof; never print it.
with patch.object(m.subprocess, "run", return_value=subprocess.CompletedProcess(
        ["rish"], 0, "ATLAS_RUNAS_OK" + "X" * 10000, sensitive)):
    success, text = m.rish_run("mock-command-only")
    assert not success and text == ""

print("PASS: synthetic Shizuku capability probe, suppressed sensitive output, fail closed, strict markers, DataStore inventory, no backup/uninstall claims")
