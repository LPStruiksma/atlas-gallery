"""Count emulator shard failures across public ATLAS workflow runs (no token)."""
import argparse
from collections import Counter, defaultdict
from datetime import datetime, timedelta, timezone
import json
import re
import sys
from urllib.error import URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

REPO = "LPStruiksma/atlas-gallery"
API = f"https://api.github.com/repos/{REPO}"
WEB = f"https://github.com/{REPO}"
ANSI = re.compile(r"\x1b\[[0-?]*[ -/]*[@-~]")
TIMESTAMP = re.compile(r"^\d{4}-\d\d-\d\dT\S+Z\s?")
FAILED = re.compile(r"^nl\.atlas\.gallery\.([\w.$]+)\s+>\s+(.+?)\s+FAILED\s*$")
DEVICE = re.compile(r"\[emulator-\d+\s+-\s+[^\]]+\]$")
SHARD = re.compile(r"emulator-ui shard \d+")


def clean_line(line):
    return TIMESTAMP.sub("", ANSI.sub("", line)).strip()


def normalize_message(message):
    return re.sub(r"\d+", "#", message)


def parse_log(log):
    """Return (test/message pairs, first error); ignore Gradle device suffix."""
    failures = []
    error = None
    pending = None
    for raw in log.splitlines():
        line = clean_line(raw)
        if not line:
            continue
        match = FAILED.fullmatch(line)
        if pending is not None:
            if match or line.startswith("##[") or line.startswith("at "):
                failures.append((pending, "(no message)"))
            else:
                failures.append((pending, line))
            pending = None
        if match:
            name = DEVICE.sub("", match[2]).strip()
            pending = f"{match[1]}.{name}"
        if error is None and line.startswith("##[error]"):
            error = line[len("##[error]"):].strip()
    if pending is not None:
        failures.append((pending, "(no message)"))
    return failures, error


def summarize(jobs):
    """Aggregate one observation per unique job; never treat missing logs as no-test."""
    unique = {job["id"]: job for job in jobs}
    rows = defaultdict(list)
    no_test = Counter()
    unavailable = 0
    failed = 0
    for job in unique.values():
        if job["conclusion"] != "failure":
            continue
        failed += 1
        if job["log"] is None:
            unavailable += 1
            continue
        failures, error = parse_log(job["log"])
        if not failures:
            no_test[normalize_message(error or "(no error line)")] += 1
        # A duplicated summary or repeated test within a job counts once.
        per_test = {}
        for name, message in failures:
            per_test.setdefault(name, normalize_message(message))
        for name, message in per_test.items():
            rows[name].append((job["branch"], job["day"], message))
    tests = []
    for name, entries in rows.items():
        messages = Counter(entry[2] for entry in entries)
        common = min(messages, key=lambda message: (-messages[message], message))
        tests.append(dict(test=name, failed_jobs=len(entries),
                          distinct_branches=len({entry[0] for entry in entries}),
                          first_day=min(entry[1] for entry in entries),
                          last_day=max(entry[1] for entry in entries),
                          message=common))
    tests.sort(key=lambda row: (-row["failed_jobs"], row["test"]))
    return dict(shard_jobs=len(unique), failed_jobs=failed,
                unavailable_logs=unavailable, tests=tests,
                no_test=[dict(message=message, failed_jobs=count)
                         for message, count in sorted(no_test.items(),
                                                     key=lambda item: (-item[1], item[0]))])


def filter_test(jobs, name):
    """Exact fully qualified (without package) or method/class substring match."""
    hits = {}
    for job in jobs:
        if job["conclusion"] != "failure" or job["log"] is None:
            continue
        names = sorted({test for test, _ in parse_log(job["log"])[0] if name in test})
        if names:
            hits[job["id"]] = {key: job[key] for key in
                               ("id", "run_id", "branch", "day", "url", "run_url")}
            hits[job["id"]]["tests"] = names
    return sorted(hits.values(), key=lambda job: (job["day"], job["id"]))


def request(path):
    req = Request(API + path, headers={"Accept": "application/vnd.github+json",
                                       "User-Agent": "atlas-emulator-flake-census",
                                       "X-GitHub-Api-Version": "2022-11-28"})
    with urlopen(req, timeout=60) as response:
        return response.read().decode("utf-8", errors="replace")


def paginate(path, key, **params):
    page = 1
    while True:
        query = urlencode(dict(params, per_page=100, page=page))
        payload = json.loads(request(f"{path}?{query}"))
        # GitHub caps filtered workflow-run queries at 1000 results.
        if key == "workflow_runs" and payload["total_count"] > 1000:
            raise RuntimeError("More than 1000 runs in this date window; use a later SINCE_ISO")
        values = payload[key]
        yield from values
        if len(values) < 100:
            return
        page += 1


def iso(value):
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    return (parsed.replace(tzinfo=timezone.utc) if parsed.tzinfo is None
            else parsed.astimezone(timezone.utc))


def collect(since, until):
    """Scan runs created in range; latest attempt only (no retry double-counting)."""
    stamp = lambda dt: dt.isoformat(timespec="seconds").replace("+00:00", "Z")
    runs = paginate("/actions/workflows/build-apk.yml/runs", "workflow_runs",
                    created=f"{stamp(since)}..{stamp(until)}")
    seen = set()
    jobs = []
    for run in runs:
        if run["id"] in seen or not since <= iso(run["created_at"]) <= until:
            continue
        seen.add(run["id"])
        for job in paginate(f"/actions/runs/{run['id']}/jobs", "jobs", filter="latest"):
            if not SHARD.fullmatch(job["name"]) or job["conclusion"] not in ("success", "failure"):
                continue
            entry = dict(id=job["id"], run_id=run["id"], branch=run["head_branch"],
                         day=iso(job["started_at"] or run["created_at"]).date().isoformat(),
                         conclusion=job["conclusion"], log=None,
                         url=job["html_url"], run_url=run["html_url"])
            if job["conclusion"] == "failure":
                try:
                    entry["log"] = request(f"/actions/jobs/{job['id']}/logs")
                except URLError as exc:
                    print(f"Log unavailable: {entry['url']}: {exc}", file=sys.stderr)
            jobs.append(entry)
    return jobs


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("since", metavar="SINCE_ISO", nargs="?",
                        help="UTC date or ISO timestamp (default: last three days)")
    parser.add_argument("--until", help="optional inclusive UTC end timestamp")
    parser.add_argument("--json", action="store_true", help="machine-readable report")
    parser.add_argument("--test", help="list run/job links matching a test name")
    args = parser.parse_args()
    try:
        until = iso(args.until) if args.until else datetime.now(timezone.utc)
        since = iso(args.since) if args.since else until - timedelta(days=3)
        if since > until:
            parser.error("SINCE_ISO must not follow --until")
        jobs = collect(since, until)
    except (ValueError, KeyError, URLError, RuntimeError) as exc:
        print(f"Census could not complete: {exc}", file=sys.stderr)
        return 2
    report = summarize(jobs)
    report.update(since=since.isoformat(), until=until.isoformat(),
                  attempts="latest", complete=report["unavailable_logs"] == 0)
    if args.test:
        report["matching_jobs"] = filter_test(jobs, args.test)
    if args.json:
        print(json.dumps(report, indent=2))
    else:
        print(f"{report['since']} to {report['until']} (latest attempts)")
        print(f"{report['shard_jobs']} shard jobs, {report['failed_jobs']} failed, "
              f"{report['unavailable_logs']} unavailable failed logs")
        print("Failed jobs | Branches | First day | Last day | Test | Most common message")
        for row in report["tests"]:
            print(" | ".join(str(row[key]) for key in
                             ("failed_jobs", "distinct_branches", "first_day", "last_day",
                              "test", "message")))
        print("Failed jobs with no failing test:")
        for row in report["no_test"]:
            print(f"{row['failed_jobs']} | {row['message']}")
        if args.test:
            print(f"Matching jobs: {args.test}")
            for job in report["matching_jobs"]:
                print(f"{job['day']} {job['branch']} {job['run_url']} {job['url']}")
    return 0 if report["complete"] else 1


if __name__ == "__main__":
    sys.exit(main())
