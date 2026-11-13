#!/usr/bin/env python3
"""Privacy-minimal OFFLINE summary of a synthetic Android signer-recovery CI log.

Reads an existing text log and prints only fixed-schema booleans / diagnostic
categories. It neither calls GitHub nor runs adb. Markers are observations of
log text, NOT proof of a trusted CI origin, true device restore or release GO.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re

MAX_BYTES = 16 * 1024 * 1024
# Require a runner timestamp followed by a *standalone* marker. Naive substring
# search would accept commands printed by the runner, or example docs.
STAMP = r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d+Z\s+"
MARKERS = {
    "same_signer_people_preserved":
        "PASS: real emulator adb install -r preserved synthetic app-private People SQLite",
    "mismatched_signer_rejected":
        "PASS: Android rejected the synthetic incompatible APK signing certificate",
    "fictional_people_restored":
        "PASS: incompatible-signer emulator fixture restored every synthetic People assignment",
    "synthetic_screenshots_verified":
        "PASS: 3 real PNG screenshots",
}
RECOVERY_CSV = re.compile(STAMP + r"Created local recovery CSV: [1-9][0-9]* face assignments; [1-9][0-9]* People\.\s*$", re.M)
SUITE_FAILURE = re.compile(r"Tests on emulator-\d+ .* failed: There was \d+ failure\(s\)\.|> Task :app:connectedDebugAndroidTest FAILED", re.M)
THREAD_ERROR = "android.view.ViewRootImpl$CalledFromWrongThreadException"
KVM_ERROR = ("KVM unavailable", "Fail fast without Linux KVM FAILED")
INSTALL_MISMATCH = "INSTALL_FAILED_UPDATE_INCOMPATIBLE"


def summarize(log: str) -> dict:
    """Do not interpolate any raw log line into the result: logs may contain PII."""
    markers = {
        key: bool(re.search(STAMP + re.escape(value) + r"\s*$", log, re.M))
        for key, value in MARKERS.items()
    }
    csv_evidence = bool(RECOVERY_CSV.search(log))
    core_ok = (
        markers["same_signer_people_preserved"]
        and markers["mismatched_signer_rejected"]
        and markers["fictional_people_restored"]
        and csv_evidence
    )
    ui_failed = bool(SUITE_FAILURE.search(log) or "BUILD FAILED" in log)
    ui_pass = bool(markers["synthetic_screenshots_verified"] and not ui_failed)
    if core_ok and ui_pass:
        category = "synthetic_evidence_present_full_suite_pass"
    elif core_ok and ui_failed:
        category = "synthetic_people_recovery_pass_full_suite_red"
    elif any(markers.values()) or csv_evidence:
        category = "partial_synthetic_evidence"
    else:
        category = "no_verified_synthetic_markers"
    diagnostics = []
    if THREAD_ERROR in log:
        diagnostics.append("android_view_thread_affinity_exception")
    if any(message in log for message in KVM_ERROR):
        diagnostics.append("emulator_kvm_problem")
    if INSTALL_MISMATCH in log and not markers["mismatched_signer_rejected"]:
        diagnostics.append("android_signature_mismatch_unexplained")
    return {
        "source": "untrusted_local_ci_log",
        "scope": "synthetic_emulator_only",
        "category": category,
        "observed": {**markers, "fictional_recovery_csv_created": csv_evidence},
        "synthetic_people_recovery_markers_complete": core_ok,
        "full_android_suite_marker_passed": ui_pass,
        "diagnostic_categories": diagnostics,
        "ci_job_attested": False,
        "real_phone_signer_verified": False,
        "real_phone_backup_verified": False,
        "real_phone_upgrade_go": False,
        "note": "Markers are observations, not authentication or a CI substitute. Independently check exact-head GitHub Actions conclusion."
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Summarize an existing Android synthetic recovery log without reproducing private lines")
    parser.add_argument("logfile", type=Path)
    args = parser.parse_args()
    try:
        if args.logfile.is_symlink() or not args.logfile.is_file() or args.logfile.stat().st_size > MAX_BYTES:
            raise ValueError("Invalid or oversized log")
        log = args.logfile.read_text(encoding="utf-8", errors="replace")
        report = summarize(log)
    except (ValueError, OSError):
        parser.exit(2, "NO EVIDENCE: local synthetic recovery log unavailable or oversized\n")
    print(json.dumps(report, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
