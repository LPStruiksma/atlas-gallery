#!/usr/bin/env python3
"""Bounded, exact-evidence KVM retry for the ATLAS Android Actions workflow.

The workflow_run job checks out trusted default-branch code only. All data from
the triggering run, jobs and logs is treated as untrusted evidence. Denial is a
successful no-op; only an eligible attempt reaches the rerun-failed-jobs API.
"""
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request

REPO = "LPStruiksma/atlas-gallery"
WORKFLOW = "ATLAS Android - Build and UI Evidence"
WORKFLOW_PATH = ".github/workflows/build-apk.yml"
KVM_STEP = "Enable KVM hardware acceleration (fail fast if unavailable)"
JOIN_STEP = "Require every emulator shard to pass"
EXPECTED_JOBS = {
    "build", "emulator-ui shard 0", "emulator-ui shard 1", "emulator-ui"
}
KVM_MESSAGES = (
    "KVM unavailable; /dev/kvm does not exist on this runner, "
    "refusing multi-minute software-emulation fallback",
    "KVM unavailable; /dev/kvm still not usable after 5 attempts, "
    "refusing multi-minute software-emulation fallback",
)
JOIN_MESSAGE = "An emulator-ui shard did not pass (failure)"
ERROR_PREFIXES = ("::error::", "##[error]")
BUDGET_MARKERS = (
    "spending limit", "billing limit", "billing", "payment method",
    "actions minutes", "usage limit", "out of minutes",
)
MAX_LOG_BYTES = 2 * 1024 * 1024


def positive_int(value):
    return type(value) is int and value > 0


def full_name(info):
    return info.get("full_name") if isinstance(info, dict) else None


def error_messages(log):
    """Recognize only complete GitHub error lines, not incidental KVM prose."""
    if not isinstance(log, str):
        return set()
    found = set()
    for line in log.splitlines():
        line = line.strip()
        # GitHub job logs prefix output with an ISO timestamp followed by space.
        line = re.sub(
            r"^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d+)?Z\s+",
            "", line,
        )
        for prefix in ERROR_PREFIXES:
            if line.startswith(prefix):
                found.add(line[len(prefix):])
                break
    return found


def failed_step_only(job, name):
    steps = job.get("steps")
    if not isinstance(steps, list) or not steps:
        return False
    failed = [step.get("name") for step in steps
              if isinstance(step, dict) and step.get("conclusion") == "failure"]
    return failed == [name]


def assess(event, current, workflow, jobs, logs):
    """Return (should_retry, explanation), with no I/O and no side effects."""
    if not all(isinstance(item, dict)
               for item in (event, current, workflow, logs)):
        return False, "missing policy inputs"
    run = event.get("workflow_run")
    if not isinstance(run, dict):
        return False, "missing triggering run"
    if full_name(event.get("repository")) != REPO:
        return False, "repository mismatch"
    if full_name(run.get("head_repository")) != REPO:
        return False, "fork or missing head repository"
    if run.get("name") != WORKFLOW or current.get("name") != WORKFLOW:
        return False, "workflow name mismatch"
    wid = run.get("workflow_id")
    if not positive_int(wid) or workflow.get("id") != wid:
        return False, "workflow id mismatch"
    if workflow.get("name") != WORKFLOW or workflow.get("path") != WORKFLOW_PATH:
        return False, "workflow definition mismatch"
    if run.get("event") not in ("pull_request", "workflow_dispatch"):
        return False, "unsupported source event"
    if (run.get("status") != "completed" or
            run.get("conclusion") != "failure"):
        return False, "not a completed failed run"
    if (current.get("status") != "completed" or
            current.get("conclusion") != "failure"):
        return False, "run changed since event"
    run_id, attempt = run.get("id"), run.get("run_attempt")
    if not positive_int(run_id) or not positive_int(attempt):
        return False, "unknown run id or attempt"
    if attempt >= 3:
        return False, "run attempt cap reached"
    if (current.get("id") != run_id or
            current.get("workflow_id") != wid or
            current.get("run_attempt") != attempt or
            current.get("event") != run.get("event")):
        return False, "stale or mismatched run attempt"
    sha = run.get("head_sha")
    if (not isinstance(sha, str) or not re.fullmatch(r"[0-9a-f]{40}", sha)
            or current.get("head_sha") != sha):
        return False, "head mismatch"
    if not isinstance(jobs, list) or len(jobs) != 4:
        return False, "unknown or incomplete job set"

    by_name = {}
    ids = set()
    for job in jobs:
        if not isinstance(job, dict):
            return False, "invalid job"
        name, job_id = job.get("name"), job.get("id")
        if (name not in EXPECTED_JOBS or name in by_name or
                not positive_int(job_id) or job_id in ids):
            return False, "unknown or duplicate job id/name"
        if (job.get("run_id") != run_id or
                job.get("run_attempt") != attempt or
                job.get("status") != "completed" or
                job.get("conclusion") not in ("success", "failure")):
            return False, "unknown, skipped or cancelled job"
        ids.add(job_id)
        by_name[name] = job
    if set(by_name) != EXPECTED_JOBS:
        return False, "required jobs missing"
    if by_name["build"]["conclusion"] != "success":
        return False, "build did not pass"
    if by_name["emulator-ui"]["conclusion"] != "failure":
        return False, "missing derived emulator-ui failure"
    join = by_name["emulator-ui"]
    if not failed_step_only(join, JOIN_STEP):
        return False, "aggregate failed outside the shard-join step"
    join_log = logs.get(join["id"])
    if not isinstance(join_log, str):
        return False, "missing aggregate job log"
    if JOIN_MESSAGE not in error_messages(join_log):
        return False, "aggregate lacks exact shard-failure diagnostic"

    kvm_failed = 0
    for name in ("emulator-ui shard 0", "emulator-ui shard 1"):
        job = by_name[name]
        if job["conclusion"] == "success":
            continue
        if not failed_step_only(job, KVM_STEP):
            return False, "failed shard step was not KVM"
        log = logs.get(job["id"])
        if not isinstance(log, str) or not log:
            return False, "missing shard log"
        if not any(message in error_messages(log) for message in KVM_MESSAGES):
            return False, "failed shard lacks exact KVM diagnostic"
        kvm_failed += 1
    if not kvm_failed:
        return False, "no failed KVM shard"

    # A quota/budget failure anywhere in the observed failed-job logs is never
    # recoverable by spending more Actions minutes.
    for name in ("emulator-ui", "emulator-ui shard 0", "emulator-ui shard 1"):
        job = by_name[name]
        if job["conclusion"] != "failure":
            continue
        body = logs.get(job["id"])
        if not isinstance(body, str) or any(x in body.lower() for x in BUDGET_MARKERS):
            return False, "budget evidence or unknown log"
    return True, "exact KVM-only shard failure; eligible for failed-jobs rerun"


def api_json(token, path):
    req = urllib.request.Request(
        "https://api.github.com/repos/" + REPO + path,
        headers={
            "Authorization": "Bearer " + token,
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "atlas-kvm-failure-retry",
        },
    )
    with urllib.request.urlopen(req, timeout=20) as response:
        raw = response.read(MAX_LOG_BYTES + 1)
    if len(raw) > MAX_LOG_BYTES:
        raise ValueError("GitHub JSON response too large")
    return json.loads(raw.decode("utf-8"))


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def job_log(token, job_id):
    """Follow the GitHub log redirect WITHOUT forwarding GITHUB_TOKEN."""
    request = urllib.request.Request(
        "https://api.github.com/repos/" + REPO +
        "/actions/jobs/" + str(job_id) + "/logs",
        headers={
            "Authorization": "Bearer " + token,
            "Accept": "application/vnd.github+json",
            "User-Agent": "atlas-kvm-failure-retry",
        },
    )
    try:
        with urllib.request.build_opener(NoRedirect).open(request, timeout=20):
            raise ValueError("job logs unexpectedly lacked redirect")
    except urllib.error.HTTPError as err:
        if err.code not in (302, 307):
            raise
        location = err.headers.get("Location", "")
    url = urllib.parse.urlsplit(location)
    host = (url.hostname or "").lower()
    if (url.scheme != "https" or
            not (host.endswith(".blob.core.windows.net") or
                 host.endswith(".actions.githubusercontent.com"))):
        raise ValueError("unexpected GitHub job-log redirect host")
    # This request carries NO Authorization header to the external log host.
    with urllib.request.urlopen(location, timeout=30) as response:
        data = response.read(MAX_LOG_BYTES + 1)
    if len(data) > MAX_LOG_BYTES:
        raise ValueError("job log too large")
    return data.decode("utf-8", errors="replace")


def rerun_failed_jobs(token, run_id):
    req = urllib.request.Request(
        "https://api.github.com/repos/" + REPO +
        "/actions/runs/" + str(run_id) + "/rerun-failed-jobs",
        data=b"{}",
        method="POST",
        headers={
            "Authorization": "Bearer " + token,
            "Accept": "application/vnd.github+json",
            "Content-Type": "application/json",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "atlas-kvm-failure-retry",
        },
    )
    with urllib.request.urlopen(req, timeout=20) as response:
        if response.status not in (201, 202):
            raise RuntimeError("failed-job rerun API refused request")


def main():
    token = os.environ.get("GITHUB_TOKEN")
    event_file = os.environ.get("GITHUB_EVENT_PATH")
    if not token or not event_file or os.environ.get("GITHUB_REPOSITORY") != REPO:
        print("DENY: missing trusted workflow runtime context")
        return 0
    try:
        with open(event_file, encoding="utf-8") as handle:
            event = json.load(handle)
        run = event.get("workflow_run", {})
        run_id, attempt, wid = (run.get("id"), run.get("run_attempt"),
                                run.get("workflow_id"))
        if not positive_int(run_id) or not positive_int(wid) or not positive_int(attempt):
            print("DENY: invalid run metadata")
            return 0
        # The live API snapshot prevents a stale webhook from consuming another
        # attempt after a previous policy invocation already queued the rerun.
        current = api_json(token, "/actions/runs/" + str(run_id))
        workflow = api_json(token, "/actions/workflows/" + str(wid))
        listing = api_json(
            token, "/actions/runs/" + str(run_id) +
            "/attempts/" + str(attempt) + "/jobs?per_page=100",
        )
        jobs = listing.get("jobs")
        if (not isinstance(jobs, list) or
                listing.get("total_count") != len(jobs)):
            print("DENY: incomplete Actions job listing")
            return 0
        logs = {}
        if isinstance(jobs, list):
            for job in jobs:
                if (isinstance(job, dict) and
                        job.get("conclusion") == "failure" and
                        positive_int(job.get("id"))):
                    logs[job["id"]] = job_log(token, job["id"])
        allowed, why = assess(event, current, workflow, jobs, logs)
        print(("ELIGIBLE: " if allowed else "DENY: ") + why)
        if not allowed:
            return 0
        # One more current-state check before the write. A serial Actions
        # concurrency group prevents policy jobs for the same run overlapping.
        fresh = api_json(token, "/actions/runs/" + str(run_id))
        if (fresh.get("run_attempt") != attempt or
                fresh.get("status") != "completed" or
                fresh.get("conclusion") != "failure"):
            print("DENY: attempt advanced before rerun")
            return 0
        rerun_failed_jobs(token, run_id)
        print("DISPATCHED: rerun-failed-jobs for run " +
              str(run_id) + " attempt " + str(attempt))
        return 0
    except (OSError, ValueError, TypeError, KeyError, urllib.error.URLError) as exc:
        # Unknown log/API evidence fails closed. Do not leak token or URLs.
        print("DENY: evidence/API unavailable (" + type(exc).__name__ + ")")
        return 0


if __name__ == "__main__":
    sys.exit(main())
