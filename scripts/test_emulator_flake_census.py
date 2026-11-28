"""Offline regression for the emulator census; no network needed."""
import unittest
from pathlib import Path

from emulator_flake_census import parse_log, summarize, filter_test, normalize_message


class CensusTest(unittest.TestCase):
    def test_recorded_format(self):
        log = (Path(__file__).parent / "testdata/emulator-shard-log-sample.txt").read_text()
        failures, error = parse_log(log)
        self.assertEqual(failures, [
            ("SyntheticTest.a", "java.lang.AssertionError: waited 10 seconds at line 42"),
            ("testkit.SyntheticOtherTest.b[dark=true]", "java.lang.IllegalStateException: missing 3 nodes"),
        ])
        self.assertEqual(error, "Process completed with exit code 1.")

    def test_aggregation_distinct_jobs_branches_and_no_test(self):
        def job(j, branch, day, conclusion, log):
            return dict(id=j, run_id=100+j, branch=branch, day=day,
                        conclusion=conclusion, log=log,
                        url=f"https://github.com/example/example/actions/runs/{100+j}/job/{j}",
                        run_url=f"https://github.com/example/example/actions/runs/{100+j}")
        log = "nl.atlas.gallery.SyntheticTest > a FAILED\n\tAssertionError: waited 10\n"
        jobs = [
            job(1, "one", "2026-11-14", "failure", log + log),
            job(2, "two", "2026-11-18", "failure", log.replace("10", "20")),
            job(3, "one", "2026-11-18", "failure", "##[error]KVM unavailable\n##[error]later"),
            job(4, "one", "2026-11-18", "success", None),
            job(5, "one", "2026-11-18", "failure", None),
            job(6, "one", "2026-11-18", "failure", "nothing useful"),
        ]
        report = summarize(jobs)
        self.assertEqual(report["shard_jobs"], 6)
        self.assertEqual(report["failed_jobs"], 5)
        self.assertEqual(report["tests"][0], dict(test="SyntheticTest.a", failed_jobs=2,
                         distinct_branches=2, first_day="2026-11-14", last_day="2026-11-18",
                         message="AssertionError: waited #"))
        self.assertEqual(report["no_test"], [
            dict(message="(no error line)", failed_jobs=1),
            dict(message="KVM unavailable", failed_jobs=1),
        ])
        self.assertEqual(report["unavailable_logs"], 1)
        self.assertEqual([x["id"] for x in filter_test(jobs, "SyntheticTest.a")], [1, 2])
        self.assertEqual(filter_test(jobs, "not-present"), [])

    def test_normalization_and_blank_messages(self):
        self.assertEqual(normalize_message("line 43, 123ms"), "line #, #ms")
        self.assertEqual(parse_log("nl.atlas.gallery.A > b FAILED\n\n##[error]oops"),
                         ([("A.b", "(no message)")], "oops"))


if __name__ == "__main__":
    unittest.main()
