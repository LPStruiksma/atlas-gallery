#!/usr/bin/env python3
"""Fully invented CI log fixtures: no GitHub calls, credentials or real photos."""
from pathlib import Path
import sys
import tempfile
from contextlib import redirect_stdout
from io import StringIO
sys.path.insert(0, str(Path(__file__).parent))
from audit_recovery_ci_log import summarize, main, MARKERS

ST = "2026-10-16T12:00:00.1234567Z "
secret = "PRIVATE PERSONAL NAME /storage/emulated/0/DCIM/real-family-secret.jpg"
def report_with(*keys, csv=False, tail=""):
    log = "\n".join(ST + MARKERS[k] for k in keys) + "\n"
    if csv:
        log += ST + "Created local recovery CSV: 3 face assignments; 2 People.\n"
    return summarize(log + tail + "\n" + secret)

all_core = ["same_signer_people_preserved", "mismatched_signer_rejected", "fictional_people_restored"]
complete = report_with(*all_core, "synthetic_screenshots_verified", csv=True)
assert complete["category"] == "synthetic_evidence_present_full_suite_pass"
assert complete["full_android_suite_marker_passed"]
assert complete["synthetic_people_recovery_markers_complete"]
assert complete["real_phone_upgrade_go"] is False
assert complete["ci_job_attested"] is False
assert secret not in str(complete)

error = "Tests on emulator-5554 - 15 failed: There was 1 failure(s).\n" + ST + (
    "android.view.ViewRootImpl$CalledFromWrongThreadException")
red = report_with(*all_core, "synthetic_screenshots_verified", csv=True, tail=error)
assert red["category"] == "synthetic_people_recovery_pass_full_suite_red"
assert "android_view_thread_affinity_exception" in red["diagnostic_categories"]
assert red["full_android_suite_marker_passed"] is False
assert red["synthetic_people_recovery_markers_complete"]

no_csv = report_with(*all_core, "synthetic_screenshots_verified")
assert no_csv["category"] == "partial_synthetic_evidence"
assert not no_csv["synthetic_people_recovery_markers_complete"]

# Command echo alone must not authenticate a milestone. Timestamps matter.
fake_echo = "run: echo '" + MARKERS["fictional_people_restored"] + "'"
assert not summarize(fake_echo)["observed"]["fictional_people_restored"]
assert summarize("")["category"] == "no_verified_synthetic_markers"

# A signer mismatch *without* the explicit expected rejection marker is not
# evidence that we successfully tested Android's refusal.
bad = summarize(ST + "INSTALL_FAILED_UPDATE_INCOMPATIBLE")
assert bad["diagnostic_categories"] == ["android_signature_mismatch_unexplained"]
assert not bad["synthetic_people_recovery_markers_complete"]

with tempfile.TemporaryDirectory() as tmp:
    log = Path(tmp) / "synthetic.log"
    log.write_text(ST + MARKERS["same_signer_people_preserved"] + "\n")
    old = sys.argv
    try:
        sys.argv = ["audit", str(log)]
        with redirect_stdout(StringIO()) as output:
            assert main() == 0
        assert '"same_signer_people_preserved": true' in output.getvalue()
        assert secret not in output.getvalue()
        link = Path(tmp) / "link.log"
        link.symlink_to(log)
        sys.argv = ["audit", str(link)]
        with redirect_stdout(StringIO()):
            try:
                main()
            except SystemExit as exc:
                assert exc.code == 2
            else:
                raise AssertionError("Linked logfile unexpectedly accepted")
    finally:
        sys.argv = old
print("PASS: synthetic recovery CI evidence classification, false GO prevention, red-UI differentiation and privacy")
