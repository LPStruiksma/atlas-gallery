"""Fail-closed fixtures for the bounded ATLAS KVM retry policy."""
import copy
import unittest

from kvm_failure_retry import assess

REPO = "LPStruiksma/atlas-gallery"
WORKFLOW = "ATLAS Android - Build and UI Evidence"
WORKFLOW_PATH = ".github/workflows/build-apk.yml"
KVM_MISSING = (
    "2026-11-13T00:00:00Z ##[error]KVM unavailable; /dev/kvm does not exist "
    "on this runner, refusing multi-minute software-emulation fallback\n"
)
KVM_UNUSABLE = (
    "2026-11-13T00:00:00Z ::error::KVM unavailable; /dev/kvm still not usable "
    "after 5 attempts, refusing multi-minute software-emulation fallback\n"
)
JOIN_FAILURE = (
    "2026-11-13T00:00:00Z ##[error]An emulator-ui shard did not pass (failure)\n"
)


def job(job_id, name, conclusion, failed_step=None):
    steps = [{"name": "Checkout", "conclusion": "success"}]
    if failed_step:
        steps.append({"name": failed_step, "conclusion": "failure"})
    return {
        "id": job_id,
        "run_id": 4242,
        "run_attempt": 1,
        "name": name,
        "status": "completed",
        "conclusion": conclusion,
        "steps": steps,
    }


def example(attempt=1):
    event = {
        "repository": {"full_name": REPO},
        "workflow_run": {
            "id": 4242,
            "workflow_id": 99,
            "name": WORKFLOW,
            "event": "pull_request",
            "status": "completed",
            "conclusion": "failure",
            "run_attempt": attempt,
            "head_repository": {"full_name": REPO},
            "head_sha": "a" * 40,
        },
    }
    current = copy.deepcopy(event["workflow_run"])
    workflow = {"id": 99, "name": WORKFLOW, "path": WORKFLOW_PATH}
    jobs = [
        job(1, "build", "success"),
        job(2, "emulator-ui shard 0", "failure",
            "Enable KVM hardware acceleration (fail fast if unavailable)"),
        job(3, "emulator-ui shard 1", "success"),
        job(4, "emulator-ui", "failure", "Require every emulator shard to pass"),
    ]
    for item in jobs:
        item["run_attempt"] = attempt
    return event, current, workflow, jobs, {2: KVM_MISSING, 4: JOIN_FAILURE}


class RetryPolicyTests(unittest.TestCase):
    def check(self, fixture, expected, reason=None):
        allowed, why = assess(*fixture)
        self.assertIs(allowed, expected, why)
        if reason:
            self.assertIn(reason, why)

    def test_first_and_second_attempt_are_eligible(self):
        for attempt in (1, 2):
            with self.subTest(attempt=attempt):
                self.check(example(attempt), True)
        case = example()
        case[4][2] = KVM_UNUSABLE
        self.check(case, True)

    def test_third_and_later_attempt_not_retried(self):
        for attempt in (3, 4):
            self.check(example(attempt), False, "attempt")

    def test_non_kvm_failure_not_retried(self):
        case = example()
        case[4][2] = "2026-11-13T00:00:00Z ##[error]Tests failed\n"
        self.check(case, False, "KVM")
        case = example()
        case[3][1]["steps"][-1]["name"] = "Emulator Compose interactions"
        self.check(case, False, "step")

    def test_empty_incomplete_and_unknown_logs_fail_closed(self):
        for logs in ({}, {2: KVM_MISSING}, {2: "", 4: JOIN_FAILURE}):
            case = example()
            case[4].clear()
            case[4].update(logs)
            self.check(case, False)
        case = example()
        case[4][2] = "KVM unavailable; /dev/kvm does not exist\n"
        self.check(case, False)

    def test_wrong_repository_workflow_or_fork_not_retried(self):
        for mutator in (
            lambda e, c, w: e["repository"].update(full_name="other/repo"),
            lambda e, c, w: e["workflow_run"]["head_repository"].update(
                full_name="fork/atlas-gallery"),
            lambda e, c, w: e["workflow_run"].update(name="Other workflow"),
            lambda e, c, w: e["workflow_run"].update(workflow_id=101),
            lambda e, c, w: w.update(path=".github/workflows/other.yml"),
            lambda e, c, w: w.update(name="Other workflow"),
            lambda e, c, w: c.update(name="Other workflow"),
            lambda e, c, w: e["workflow_run"].update(event="workflow_run"),
        ):
            case = example()
            mutator(case[0], case[1], case[2])
            self.check(case, False)

    def test_run_status_and_attempt_must_still_match(self):
        for status in ("cancelled", "skipped", "success", None):
            case = example()
            case[0]["workflow_run"]["conclusion"] = status
            self.check(case, False)
        case = example()
        case[1]["status"] = "in_progress"
        self.check(case, False)
        case = example()
        case[1]["run_attempt"] = 2
        self.check(case, False, "attempt")
        case = example()
        case[1]["conclusion"] = "success"
        self.check(case, False)

    def test_mixed_or_unrecognized_failed_jobs_not_retried(self):
        case = example()
        case[3][0]["conclusion"] = "failure"
        case[3][0]["steps"] = [{"name": "Gradle tests", "conclusion": "failure"}]
        self.check(case, False)
        case = example()
        case[3][2]["conclusion"] = "failure"
        case[3][2]["steps"] = [{"name": "Emulator Compose interactions",
                                 "conclusion": "failure"}]
        self.check(case, False)
        case = example()
        case[3][2]["conclusion"] = "cancelled"
        self.check(case, False)
        case = example()
        case[3][2]["conclusion"] = "skipped"
        self.check(case, False)
        case = example()
        case[3].append(job(5, "unknown job", "failure"))
        self.check(case, False)

    def test_derived_join_failure_must_be_exact(self):
        case = example()
        case[3][3]["steps"][-1]["name"] = "Download shard evidence"
        self.check(case, False)
        case = example()
        case[4][4] = "2026-11-13T00:00:00Z ##[error]UI evidence missing\n"
        self.check(case, False)
        case = example()
        case[3][3]["conclusion"] = "success"
        self.check(case, False)

    def test_replay_budget_and_malformed_job_data_fail_closed(self):
        case = example()
        case[4][2] += "GitHub Actions spending limit exceeded\n"
        self.check(case, False, "budget")
        case = example()
        case[3][1]["id"] = None
        self.check(case, False)
        case = example()
        case[3][1]["run_id"] = 111
        self.check(case, False)
        case = example()
        case[3][1]["run_attempt"] = 2
        self.check(case, False)
        case = example()
        case[3].append(copy.deepcopy(case[3][1]))
        self.check(case, False)
        case = example()
        case[0]["workflow_run"]["run_attempt"] = True
        self.check(case, False)


if __name__ == "__main__":
    unittest.main()
