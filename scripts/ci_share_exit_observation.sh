#!/usr/bin/env bash
# #1137: isolated API29 synthetic observation. Never execute the stopped #1129 runner.
set -euo pipefail
export FIXTURE_HEAD=6c2c3da9ad742aed92fb44de564688cbb3c869e7
if [[ "${1:-}" == --admission ]]; then
python3 - <<'ADMISSION'
import json, os, re, subprocess, sys, pathlib
REPO = "LPStruiksma/atlas-gallery"
OWNER = "LPStruiksma"
PATH = ".github/workflows/share-exit-observation.yml"
SCRIPT = "scripts/ci_share_exit_observation.sh"
PASS = "https://github.com/LPStruiksma/atlas-gallery/issues/1137#issuecomment-7235952279"
ANSWERS = "https://github.com/LPStruiksma/atlas-gallery/issues/1137#issuecomment-7235974529"
JOB = "share-exit-observation-admission"
head = os.environ["EXPECTED_HEAD"]
pr = int(os.environ["PR_NUMBER"])
run_id = int(os.environ["GITHUB_RUN_ID"])
def require(ok, reason):
    if not ok:
        raise RuntimeError(reason)
def api(path, pages=False):
    args = ["gh", "api", "repos/" + REPO + "/" + path]
    if pages:
        args += ["--paginate", "--slurp"]
    p = subprocess.run(args, capture_output=True, timeout=120)
    require(p.returncode == 0, "read-only API unavailable")
    return json.loads(p.stdout)
def all_items(path, key=None):
    pages = api(path, True)
    require(isinstance(pages, list) and pages, "missing paginated response")
    if key is None:
        require(all(isinstance(x, list) for x in pages), "invalid page")
        rows = [y for x in pages for y in x]
        require(all(len(x) == 100 for x in pages[:-1]) and len(pages[-1]) < 100,
                "pagination not conclusively complete")
    else:
        require(all(isinstance(x, dict) and isinstance(x.get(key), list) and
                    type(x.get("total_count")) is int for x in pages), "invalid counted page")
        rows = [y for x in pages for y in x[key]]
        require(all(x["total_count"] == len(rows) for x in pages), "incomplete or changing history")
    require(all(isinstance(x, dict) and type(x.get("id")) is int for x in rows)
            and len({x["id"] for x in rows}) == len(rows), "missing or duplicate API identity")
    return rows
def owner(c):
    return c.get("user", {}).get("login") == OWNER and c.get("author_association") == "OWNER"
def field(body, key):
    vals = re.findall(r"(?m)^" + re.escape(key) + r": ([^\r\n]+)$", body)
    require(len(vals) == 1, "missing or ambiguous " + key)
    return vals[0]
def workflow_path(run):
    return str(run.get("path", "")).split("@", 1)[0]
try:
    require(os.environ["GITHUB_REPOSITORY"] == REPO and os.environ["GITHUB_EVENT_NAME"] == "pull_request"
            and os.environ["GITHUB_RUN_ATTEMPT"] == "1" and re.fullmatch(r"[a-f0-9]{40}", head),
            "repository/event/attempt/head mismatch")
    event = json.loads(pathlib.Path(os.environ["GITHUB_EVENT_PATH"]).read_text())
    require(event["action"] == "labeled" and event["label"]["name"] == "share-proof:run"
            and event["pull_request"]["number"] == pr and event["pull_request"]["head"]["sha"] == head
            and event["pull_request"]["head"]["repo"]["full_name"] == REPO, "event mismatch")
    live = api("pulls/" + str(pr))
    require(live["state"] == "open" and not live["draft"] and live["base"]["ref"] == "main"
            and live["base"]["repo"]["full_name"] == REPO and live["head"]["repo"]["full_name"] == REPO
            and live["head"]["sha"] == head and owner(live)
            and live["title"].startswith("[DO NOT MERGE]")
            and "do-not-merge" in {x["name"] for x in live["labels"]}, "live PR mismatch")
    require(subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip() == head, "checkout head mismatch")
    files = api("pulls/" + str(pr) + "/files?per_page=100", True)
    require(isinstance(files, list) and len(files) == 1 and len(files[0]) == 2 and live["changed_files"] == 2
            and {x["filename"] for x in files[0]} == {PATH, SCRIPT}
            and all(x["status"] == "added" for x in files[0]), "scope mismatch")
    body = live["body"]
    require("Refs #1137" in body and "No merge or auto-merge." in body, "diagnostic PR binding missing")
    actor, session = field(body, "Actor"), field(body, "Session")
    require(actor in ("chatgpt/dev", "codex/dev", "claude/dev")
            and re.fullmatch(r"[A-Za-z0-9_-]+", session)
            and field(body, "Claim") == "dev " + actor + " " + session + " for #1137",
            "Dev PR identity malformed")
    claim = "Claim: dev " + actor + " " + session
    claims = all_items("issues/1137/comments?per_page=100")
    require(sum(owner(c) and c["body"].splitlines()[0] == claim for c in claims if c.get("body")) == 1,
            "owner Dev claim missing or ambiguous")
    issue = api("issues/1137")
    require(issue["state"] == "open" and re.search(r"(?m)^Spec rev: 3$", issue["body"]), "spec drift")
    approvals = []
    for c in all_items("issues/" + str(pr) + "/comments?per_page=100"):
        if not owner(c):
            continue
        b = c.get("body") or ""
        if not re.search(r"(?m)^(?:Type: diagnostic-execution-approval|Execution:)", b):
            continue
        require(c.get("created_at") and c["created_at"] == c.get("updated_at"), "edited execution evidence")
        require(field(b, "Type") == "diagnostic-execution-approval"
                and field(b, "Verified") == "head " + head and field(b, "Spec rev") == "3"
                and field(b, "Gap pass") == PASS + " · answers " + ANSWERS, "execution evidence not exact")
        reviewer, review_session = field(b, "Actor"), field(b, "Session")
        family = lambda x: "chatgpt" if x.startswith(("chatgpt/", "codex/")) else "claude"
        require(reviewer in ("claude/review", "chatgpt/review", "codex/review")
                and family(reviewer) != family(actor) and review_session != session
                and re.fullmatch(r"[A-Za-z0-9_-]+", review_session), "execution reviewer identity mismatch")
        decision = field(b, "Execution")
        require(decision in ("APPROVED", "DENIED"), "execution decision malformed")
        approvals.append((c["id"], decision))
    require(len(approvals) == 1 and approvals[0][1] == "APPROVED", "missing, denied or ambiguous execution approval")
    current = api("actions/runs/" + str(run_id))
    require(current["id"] == run_id and current["run_attempt"] == 1 and current["event"] == "pull_request"
            and current["head_sha"] == head and current["head_branch"] == live["head"]["ref"]
            and workflow_path(current) == PATH and type(current["workflow_id"]) is int,
            "current workflow identity ambiguous")
    definition = api("actions/workflows/" + str(current["workflow_id"]))
    require(definition["id"] == current["workflow_id"] and definition["path"] == PATH,
            "workflow path/ID cannot be enumerated reliably")
    history = all_items("actions/workflows/" + str(current["workflow_id"]) + "/runs?per_page=100", "workflow_runs")
    require(sum(r["id"] == run_id for r in history) == 1, "current run not in complete workflow history")
    for previous in history:
        if previous["id"] == run_id:
            continue
        require(workflow_path(previous) == PATH and previous["workflow_id"] == current["workflow_id"]
                and previous["event"] == "pull_request" and previous["status"] == "completed"
                and previous["conclusion"] in ("success", "skipped")
                and type(previous["run_attempt"]) is int and previous["run_attempt"] >= 1,
                "prior potentially admitted run " + str(previous["id"]))
        for attempt in range(1, previous["run_attempt"] + 1):
            jobs = all_items("actions/runs/" + str(previous["id"]) + "/attempts/" + str(attempt) + "/jobs?per_page=100", "jobs")
            require(len(jobs) == 1 and jobs[0]["name"] == JOB and jobs[0]["status"] == "completed"
                    and jobs[0]["conclusion"] == "skipped", "prior potentially admitted job " + str(previous["id"]))
        print("history excluded", previous["id"], "conclusively skipped")
    # Repeat live head at the end of the history walk; removal of the request label is not a failure.
    final = api("pulls/" + str(pr))
    require(final["state"] == "open" and not final["draft"] and final["head"]["sha"] == head
            and final["head"]["repo"]["full_name"] == REPO, "head changed during admission")
    print("ADMISSION verified", "pr", pr, "head", head, "run", run_id, "approval", approvals[0][0])
except Exception as e:
    # Only controlled error reasons, never API contents or stderr.
    print("ADMISSION stopped:", str(e) if type(e) is RuntimeError else type(e).__name__)
    sys.exit(1)
ADMISSION
exit
fi
[[ "${1:-}" == --observe && "${ATLAS_EMULATOR_API:-}" == 29 ]]
# All build inputs come from the separate immutable checkout; build only a copy.
mkdir -p share-exit-evidence
[[ "$(git -C pinned-share-input rev-parse HEAD)" == "$FIXTURE_HEAD" ]]
[[ -z "$(git -C pinned-share-input status --porcelain --untracked-files=all)" ]]
[[ "$(git rev-parse HEAD)" == "$EXPECTED_HEAD" ]]
for f in scripts/ci_enable_kvm.sh scripts/ci_install_emulator_image.sh; do
  cmp -s "$f" "pinned-share-input/$f"
done
build_dir="$(mktemp -d)"
trap 'rm -rf "$build_dir"' EXIT
git -C pinned-share-input archive "$FIXTURE_HEAD" test-fixtures/share-proof scripts/ci/central-mirror.init.gradle build.gradle.kts app/build.gradle.kts | tar -xf - -C "$build_dir"
export OBS_FIXTURE_BUILD="$build_dir/test-fixtures/share-proof"
export OBS_BUILD_ROOT="$build_dir"
python3 - <<'VERIFY'
import os, pathlib, re, sys
root = pathlib.Path(os.environ["OBS_BUILD_ROOT"])
fixture = root / "test-fixtures/share-proof"
def value(path, pattern):
    m = re.findall(pattern, path.read_text())
    if len(m) != 1:
        sys.exit("fixture version drift")
    return m[0]
agp = r'id\("com.android.application"\)\s+version\s+"([^"]+)"'
sdk = r'(?m)^\s*compileSdk\s*=\s*(\d+)\s*$'
if value(root / "build.gradle.kts", agp) != value(fixture / "build.gradle.kts", agp):
    sys.exit("fixture AGP drift")
for module in ("publisher", "recipient"):
    if value(root / "app/build.gradle.kts", sdk) != value(fixture / module / "build.gradle.kts", sdk):
        sys.exit("fixture SDK drift")
VERIFY
# Do not pass the read-only workflow token to Gradle or publish raw build output.
if ! env -u GH_TOKEN gradle -p "$OBS_FIXTURE_BUILD" --init-script "$build_dir/scripts/ci/central-mirror.init.gradle" :publisher:assembleDebug :recipient:assembleDebug --no-daemon --stacktrace >"$build_dir/build.log" 2>&1; then
  echo 'fixture-only build failed'
  exit 1
fi
[[ "$(git -C pinned-share-input rev-parse HEAD)" == "$FIXTURE_HEAD" ]]
[[ -z "$(git -C pinned-share-input status --porcelain --untracked-files=all)" ]]
python3 - <<'OBSERVE'
import hashlib, json, os, pathlib, re, subprocess, sys, time, uuid, xml.etree.ElementTree as ET
P = "nl.atlas.shareproof.publisher"
R = "nl.atlas.shareproof.recipient"
A = P + ".proof"
URI = "content://" + A + "/probe"
API = int(os.environ["ATLAS_EMULATOR_API"])
HEAD = os.environ["EXPECTED_HEAD"]
NS = "{http://schemas.android.com/apk/res/android}"
BASE = pathlib.Path(os.environ["OBS_FIXTURE_BUILD"])
EVIDENCE = pathlib.Path("share-exit-evidence/observation.json")
PAYLOAD = b"atlas-share-proof-v1\n"
NONCE = uuid.uuid4().hex
receipt = {"schemaVersion": 1, "scope": "synthetic-process-exit-observation-only",
           "api": API, "head": HEAD, "fixtureHead": os.environ["FIXTURE_HEAD"],
           "pr": int(os.environ["PR_NUMBER"]), "run": int(os.environ["GITHUB_RUN_ID"]),
           "killCount": 0, "killCompleted": False, "samples": [], "outcome": "stopped"}
deadline = None
installed = []
serial = None
expected = {P: set(), R: set()}

class Failed(Exception):
    pass
class Contradiction(Failed):
    pass
def fail(code, reason):
    raise Failed(code + ": " + reason)
def need(condition, code, reason):
    if not condition:
        fail(code, reason)
def run(*args, timeout=30):
    if deadline is not None:
        timeout = min(timeout, deadline - time.monotonic())
        need(timeout > 0, "budget-expired", "monotonic deadline")
    try:
        p = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                           text=True, timeout=timeout, check=False,
                           env={k: v for k, v in os.environ.items() if k != "GH_TOKEN"})
    except (OSError, subprocess.TimeoutExpired):
        fail("provider-phase-runner-unsupported", "command unavailable or timed out")
    if deadline is not None:
        need(time.monotonic() < deadline, "budget-expired", "monotonic deadline")
    if p.returncode:
        fail("provider-phase-runner-unsupported", "command failed")
    return p.stdout
def adb(*args, timeout=30):
    return run("adb", "-s", serial, *args, timeout=timeout)
def shell(*args):
    return adb("shell", *args).strip()
def poll(fn, code, reason, seconds=30):
    end = time.monotonic() + seconds
    while True:
        result = fn()
        if result is not None and result is not False:
            return result
        if time.monotonic() >= end:
            fail(code, reason)
        time.sleep(0.25)

def manifest(apk):
    try:
        root = ET.fromstring(run("apkanalyzer", "manifest", "print", str(apk)))
    except (ET.ParseError, Failed):
        fail("provider-detection-invalid", "APK manifest unreadable")
    need(root.tag == "manifest", "provider-detection-invalid", "manifest root")
    return root

def packages():
    result = {}
    for line in shell("pm", "list", "packages", "-U").splitlines():
        m = re.fullmatch(r"package:(\S+)\s+uid:(\d+)", line.strip())
        if m:
            result.setdefault(m[1], []).append(int(m[2]))
    return result
def uid(pkg):
    values = packages().get(pkg, [])
    need(len(values) == 1, "actor-mismatch", "package UID is absent or ambiguous")
    return values[0]
def android_user(s):
    if s.isdecimal():
        return int(s)
    m = re.fullmatch(r"u(\d+)_a(\d+)", s)
    return 100000 * int(m[1]) + 10000 + int(m[2]) if m else None
def process(pkg, wanted_uid, site, expected_pid=None):
    # Fixed call sites and aggregate matching-row evidence only; never publish ps output.
    observation = {"site": site, "actor": "publisher" if pkg == P else "recipient",
                   "stage": "package-uid", "packageUidMatch": None,
                   "matchingRowCount": None, "rowsComplete": False,
                   "mainNameCount": None, "secondaryNameCount": None,
                   "otherSameUidCount": None, "allUidMatch": None,
                   "allMainNameMatch": None, "expectedPidProvided": expected_pid is not None,
                   "anyExpectedPidMatch": None, "allExpectedPidMatch": None}
    receipt["lastProcessObservation"] = observation
    observation["packageUidMatch"] = uid(pkg) == wanted_uid
    need(observation["packageUidMatch"], "actor-mismatch", "package UID changed")
    observation["stage"] = "ps-command"
    lines = shell("ps", "-A", "-o", "USER,PID,NAME").splitlines()
    observation["stage"] = "ps-header"
    need(lines and lines[0].split() == ["USER", "PID", "NAME"],
         "provider-phase-runner-unsupported", "ps header")
    rows = []
    def summarize_rows(complete):
        observation.update({
            "matchingRowCount": len(rows), "rowsComplete": complete,
            "mainNameCount": sum(name == pkg for _, _, name in rows),
            "secondaryNameCount": sum(name.startswith(pkg + ":") for _, _, name in rows),
            "otherSameUidCount": sum(actor_uid == wanted_uid and name != pkg
                                     and not name.startswith(pkg + ":")
                                     for actor_uid, _, name in rows),
            "allUidMatch": all(actor_uid == wanted_uid for actor_uid, _, _ in rows) if rows else None,
            "allMainNameMatch": all(name == pkg for _, _, name in rows) if rows else None,
            "anyExpectedPidMatch": any(pid == expected_pid for _, pid, _ in rows)
                                   if rows and expected_pid is not None else None,
            "allExpectedPidMatch": all(pid == expected_pid for _, pid, _ in rows)
                                   if rows and expected_pid is not None else None,
        })
    observation["stage"] = "ps-row-parse"
    for line in lines[1:]:
        cols = line.split()
        if len(cols) != 3 or not cols[1].isdigit():
            summarize_rows(False)
            fail("provider-phase-runner-unsupported", "unparseable process row")
        user, pid, name = cols
        actor_uid = android_user(user)
        if name == pkg or name.startswith(pkg + ":") or actor_uid == wanted_uid:
            rows.append((actor_uid, int(pid), name))
    summarize_rows(True)
    observation["stage"] = "uniqueness"
    if not rows:
        observation["stage"] = "absent"
        return None
    need(len(rows) == 1 and rows[0][0] == wanted_uid and rows[0][2] == pkg,
         "provider-phase-runner-unsupported", "not a unique main process")
    observation["stage"] = "unique"
    return rows[0][1]
def files(pkg):
    top = shell("run-as", pkg, "ls", "-1").splitlines()
    if "files" not in top:
        return set()
    return {f for f in shell("run-as", pkg, "ls", "-1", "files").splitlines()
            if f.endswith(".json")}
def slot(pkg, inv):
    name = NONCE + "-" + inv + ".json"
    actual = files(pkg)
    need(name not in actual, "stale-result", "invocation slot already exists")
    need(actual == expected[pkg], "duplicate-extra-result", "unexpected prior records")
    return name
def pairs(items):
    result = {}
    for k, v in items:
        if k in result:
            fail("malformed-result", "duplicate key")
        result[k] = v
    return result
def record(pkg, inv, phase, schema, name):
    def ready():
        actual = files(pkg)
        need(actual <= expected[pkg] | {name}, "duplicate-extra-result", "unexpected record")
        need(expected[pkg] <= actual, "stale-result", "prior record disappeared")
        return name in actual
    poll(ready, "missing-result", "invocation timed out")
    data = shell("run-as", pkg, "cat", "files/" + name)
    try:
        value = json.loads(data, object_pairs_hook=pairs)
    except (ValueError, TypeError):
        fail("malformed-result", "JSON")
    need(isinstance(value, dict), "malformed-result", "not an object")
    common = {"schemaVersion", "caseNonce", "invocationId", "phase", "api"}
    required = ({"publisherUid", "publisherPid", "verifiedCallerPackage", "outcome", "grantIssuedCount"}
                if schema == 1 else {"uid", "pid", "taskId", "activityInstanceId", "mime", "length",
                                     "sha256", "exceptionClass", "released", "ownerFinished"})
    need(set(value) == common | required and type(value["schemaVersion"]) is int
         and value["schemaVersion"] == schema and type(value["api"]) is int and value["api"] == API
         and value["caseNonce"] == NONCE and value["invocationId"] == inv and value["phase"] == phase,
         "malformed-result", "schema or invocation/phase/API mismatch")
    expected[pkg].add(name)
    return value
def command(phase, publisher=False, initial=False):
    need((phase, publisher, initial) in (("grant", True, True), ("reread", False, False)),
         "phase-out-of-scope", "only pre-kill grant and reread permitted")
    inv = uuid.uuid4().hex
    rn = slot(R, inv)
    pn = slot(P, inv)
    args = ("--es", "caseNonce", NONCE, "--es", "invocationId", inv, "--es", "phase", phase)
    if initial:
        s = shell("am", "start", "-n", R + "/.RecipientActivity", *args)
        need("Error" not in s, "missing-result", "recipient start failed")
    else:
        s = shell("am", "broadcast", "-a", R + ".COMMAND", "-p", R, *args)
        need("Broadcast completed" in s, "missing-result", "command delivery failed")
    rr = record(R, inv, phase, 2, rn)
    pp = record(P, inv, phase, 1, pn) if publisher else None
    need(files(P) == expected[P] and files(R) == expected[R],
         "duplicate-extra-result", "unexpected publisher or recipient action")
    need(type(rr["uid"]) is int and type(rr["pid"]) is int and type(rr["taskId"]) is int
         and isinstance(rr["activityInstanceId"], str) and rr["activityInstanceId"]
         and rr["released"] is True and rr["ownerFinished"] is False,
         "malformed-result", "recipient field types")
    need(rr["uid"] == recipient_uid and process(R, recipient_uid, "command-" + phase + "-recipient-record", rr["pid"]) == rr["pid"],
         "actor-mismatch", "recipient UID/PID")
    need(rr["exceptionClass"] is None and rr["mime"] == "application/octet-stream"
         and type(rr["length"]) is int and rr["length"] == len(PAYLOAD)
         and rr["sha256"] == hashlib.sha256(PAYLOAD).hexdigest(),
         "provider-phase-grant-lost", "recipient type/read/digest failed")
    if publisher:
        need(type(pp["publisherUid"]) is int and type(pp["publisherPid"]) is int
             and pp["publisherUid"] == publisher_uid and process(P, publisher_uid, "command-" + phase + "-publisher-record", pp["publisherPid"]) == pp["publisherPid"]
             and pp["verifiedCallerPackage"] == R and pp["outcome"] == "ok"
             and type(pp["grantIssuedCount"]) is int and pp["grantIssuedCount"] == 1,
             "actor-mismatch", "publisher identity/status/grant count")
    return rr, pp

def grant(rr):
    dump = shell("dumpsys", "activity", "permissions")
    need("ACTIVITY MANAGER URI PERMISSIONS" in dump, "provider-phase-runner-unsupported",
         "permissions header missing")
    blocks = re.split(r"(?m)^\s*\* UID (\d+) holds:\s*$", dump)
    matching = [blocks[i+1] for i in range(1, len(blocks), 2) if int(blocks[i]) == recipient_uid]
    need(len(matching) == 1, "provider-phase-grant-lost", "recipient grant absent or ambiguous")
    block = matching[0]
    entries = re.findall(r"(?m)^\s*UriPermission\{[^\n]+\}\s*$", block)
    need(len(entries) == 1 and URI + " [user 0]" in entries[0] and "[prefix]" not in entries[0],
         "provider-phase-grant-lost", "not one exact URI grant")
    modes = re.findall(r"mode=0x([0-9a-f]+) owned=0x([0-9a-f]+) global=0x([0-9a-f]+) persistable=0x([0-9a-f]+) persisted=0x([0-9a-f]+)", block)
    need(modes == [("1", "1", "0", "0", "0")] and "writeOwners:" not in block
         and "sourcePkg=" + P + " targetPkg=" + R in block,
         "provider-phase-runner-unsupported", "grant mode or source/target")
    owners = re.findall(r"(?m)^\s*\* (ActivityRecord\{[^\n]+\})\s*$", block)
    need(len(owners) == 1 and R + "/.RecipientActivity" in owners[0]
         and re.search(r"\bt" + str(rr["taskId"]) + r"\b", owners[0]),
         "recipient-grant-owner-lost", "grant owner missing or ambiguous")
    activity_dump = shell("dumpsys", "activity", "activities")
    records = {x for x in re.findall(r"ActivityRecord\{[^\n}]+\}", activity_dump)
               if R + "/.RecipientActivity" in x and re.search(r"\bt" + str(rr["taskId"]) + r"\b", x)}
    need(records == {owners[0]}, "recipient-grant-owner-lost", "owner ActivityRecord missing or ambiguous")
    # Raw URI, grant and owner identity remain in process memory; never serialize them.
    return (entries[0].strip(), owners[0], block.strip())
def same_owner(first, later):
    need(all(first[k] == later[k] for k in ("uid", "pid", "taskId", "activityInstanceId")),
         "recipient-grant-owner-lost", "recipient owner changed")
def readiness(pid):
    dump = shell("dumpsys", "activity", "providers")
    need("ACTIVITY MANAGER CONTENT PROVIDERS" in dump,
         "provider-phase-runner-unsupported", "provider dump header")
    matches = re.findall(r"(?m)^  \* ContentProviderRecord\{[^\n]*" + re.escape(P) +
                         r"/(?:\.|"+re.escape(P)+r"\.)FixtureProvider[^\n]*\}\s*\n((?:    [^\n]*\n?)*)", dump)
    # A provider may be displayed in more than one map; require consistent unique block.
    matches = list(set(matches))
    need(len(matches) == 1 and re.search(r"\bproc=ProcessRecord\{[^}]*\b" + str(pid)
         + ":" + re.escape(P) + r"/", matches[0]),
         "provider-phase-runner-unsupported", "provider record/identity unsupported")
    block = matches[0]
    connections = [l.strip() for l in block.splitlines() if l.strip().startswith("->")]
    if "Connections:" in block and not connections:
        fail("provider-phase-runner-unsupported", "unparseable provider connection list")
    no_connection = not any(R in line for line in connections)
    dump = shell("dumpsys", "activity", "processes")
    pattern = (r"(?m)^  \*APP\* UID " + str(publisher_uid) + r" ProcessRecord\{[^}]*\b"
               + str(pid) + ":" + re.escape(P) + r"/[^}]*\}\s*\n((?:    [^\n]*\n?)*)")
    blocks = re.findall(pattern, dump)
    need(len(blocks) == 1, "provider-phase-runner-unsupported", "process block unsupported")
    adjs = re.findall(r"(?m)^\s*oom(?: adj)?: max=-?\d+ curRaw=-?\d+ setRaw=-?\d+ cur=-?\d+ set=(-?\d+)\s*$", blocks[0])
    need(len(adjs) == 1, "provider-phase-runner-unsupported", "OOM adjustment unsupported")
    adj = int(adjs[0])
    return {"noRecipientConnection": no_connection, "publisherSetAdj": adj,
            "killEligible": no_connection and adj >= 500}

def raw_shell(*args, timeout=5):
    if deadline is not None:
        timeout = min(timeout, deadline - time.monotonic())
        need(timeout > 0, "budget-expired", "monotonic deadline")
    try:
        p = subprocess.run(("adb", "-s", serial, "shell", *args), capture_output=True,
                           timeout=timeout, env={k: v for k, v in os.environ.items() if k != "GH_TOKEN"})
    except (OSError, subprocess.TimeoutExpired):
        fail("read-timeout", "passive proc command unavailable or timed out")
    if deadline is not None:
        need(time.monotonic() < deadline, "budget-expired", "monotonic deadline")
    return p
def missing(p, path):
    # A nonzero remote status and exact ENOENT diagnostic, not an empty or denied read.
    err = p.stderr.decode("utf-8", errors="replace").strip()
    return p.returncode == 1 and not p.stdout and err in (
        "stat: " + path + ": No such file or directory",
        "cat: " + path + ": No such file or directory",
        "stat: cannot stat '" + path + "': No such file or directory")
def read_stat(pid, expected_ticks=None, publisher=False):
    path = "/proc/" + str(pid) + "/stat"
    p = raw_shell("cat", path)
    need(p.returncode == 0 and not p.stderr, "proc-stat-unreadable", "stat unavailable")
    data = p.stdout.decode("utf-8", errors="strict").strip()
    end = data.rfind(")")
    need(end > 0 and data.startswith(str(pid) + " (") and data[end+1:end+2] == " ",
         "proc-stat-malformed", "PID or comm boundary")
    tail = data[end+2:].split()
    need(len(tail) >= 20 and re.fullmatch(r"[A-Za-z]", tail[0]) is not None
         and all(re.fullmatch(r"-?\d+", x) for x in tail[1:]) and tail[19].isdigit()
         and int(tail[19]) > 0, "proc-stat-malformed", "stat tail")
    ticks = int(tail[19])
    if expected_ticks is not None and ticks != expected_ticks:
        if publisher:
            raise Contradiction("publisher PID reused: start ticks changed")
        fail("recipient-identity-drift", "recipient start ticks changed")
    return ticks, tail[0]
def read_uid(pid, uid_value, ticks, publisher=False):
    read_stat(pid, ticks, publisher)
    p = raw_shell("cat", "/proc/" + str(pid) + "/status")
    need(p.returncode == 0 and not p.stderr, "proc-status-unreadable", "status unavailable")
    matches = re.findall(rb"(?m)^Uid:\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)[ \t]*\r?$", p.stdout)
    need(len(matches) == 1, "proc-status-malformed", "UID field")
    if any(int(x) != uid_value for x in matches[0]):
        if publisher:
            raise Contradiction("publisher UID changed")
        fail("recipient-identity-drift", "recipient UID changed")
    read_stat(pid, ticks, publisher)
def proc_kind(pid, ticks, name):
    read_stat(pid, ticks, True)
    p = raw_shell("cat", "/proc/" + str(pid) + "/" + name)
    read_stat(pid, ticks, True)
    return "unreadable" if p.returncode or p.stderr else ("empty" if not p.stdout.strip(b"\x00\r\n") else "nonempty")
def enumerate_rows():
    lines = shell("ps", "-A", "-o", "UID,PID,NAME").splitlines()
    need(lines and lines[0].split() == ["UID", "PID", "NAME"], "ps-header", "numeric UID/PID/NAME unsupported")
    rows = []
    pids = set()
    for line in lines[1:]:
        cols = line.split(None, 2)
        need(len(cols) == 3 and cols[0].isdigit() and cols[1].isdigit() and int(cols[1]) > 0
             and int(cols[1]) not in pids and bool(cols[2].strip()), "ps-row", "incomplete or ambiguous numeric enumeration")
        pids.add(int(cols[1]))
        rows.append((int(cols[0]), int(cols[1]), cols[2].strip()))
    need(bool(rows), "ps-empty", "no complete enumeration")
    return rows
def matching(rows, pkg, wanted_uid):
    return [r for r in rows if r[0] == wanted_uid or r[2] == pkg or r[2].startswith(pkg + ":")]
def strict_rows(rows, pkg, wanted_uid, pid):
    need(matching(rows, pkg, wanted_uid) == [(wanted_uid, pid, pkg)],
         "actor-mismatch", "not a unique strict main process")
def name_kind(name):
    if name == P:
        return "main"
    if name.startswith(P + ":"):
        return "secondary"
    if name.startswith("[") and name.endswith("]"):
        return "bracketed"
    return "other"
def publisher_rows(rows, old, sample):
    matches = matching(rows, P, publisher_uid)
    sample.update({"matchingRowCount": len(matches), "uidMatchCount": sum(x[0] == publisher_uid for x in matches),
                   "mainNameCount": sum(x[2] == P for x in matches),
                   "secondaryNameCount": sum(x[2].startswith(P + ":") for x in matches),
                   "oldPidMatchCount": sum(x[1] == old for x in matches),
                   "rowsComplete": True})
    if any(x[1] != old for x in matches):
        sample["newPublisherPidObserved"] = True
        receipt["newPublisherPidObserved"] = True
        raise Contradiction("new or unexpected publisher PID")
    if any(x[0] != publisher_uid for x in matches) or any(x[1] == old and x[0] != publisher_uid for x in rows):
        raise Contradiction("old publisher PID or package name with unexpected UID")
    need(len(matches) <= 1, "ps-ambiguous", "multiple old publisher rows")
    if matches:
        sample["nameCategory"] = name_kind(matches[0][2])
    return matches
def boot():
    value = shell("cat", "/proc/sys/kernel/random/boot_id")
    need(re.fullmatch(r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}", value) is not None,
         "boot-identity-unavailable", "boot ID malformed")
    return value
def safe_identifier(value, pattern, reason):
    need(re.fullmatch(pattern, value) is not None, "image-provenance", reason)
    return value
def check_admission():
    p = subprocess.run(("bash", "scripts/ci_share_exit_observation.sh", "--admission"),
                       capture_output=True, timeout=300)
    need(p.returncode == 0, "admission-recheck-failed", "exact-head execution admission unavailable")
def validate_sample(sample, old, old_ticks, first, rec_ticks, snap, boot_id):
    rows = enumerate_rows()
    matches = publisher_rows(rows, old, sample)
    if matches:
        _, state = read_stat(old, old_ticks, True)
        sample["oldStartTicksMatch"] = True
        sample["oldState"] = state
        read_uid(old, publisher_uid, old_ticks, True)
        sample["comm"] = proc_kind(old, old_ticks, "comm")
        sample["cmdline"] = proc_kind(old, old_ticks, "cmdline")
        need("unreadable" not in (sample["comm"], sample["cmdline"]), "proc-read-unavailable", "comm/cmdline unreadable")
    else:
        # Even with no matching name/UID row, a live/reused old PID is not absence.
        p = raw_shell("stat", "/proc/" + str(old))
        if p.returncode == 0:
            read_stat(old, old_ticks, True)
            fail("old-pid-still-present", "zero matches with existing old PID")
        need(missing(p, "/proc/" + str(old)), "old-pid-absence-unproven", "old proc directory not confirmed ENOENT")
    strict_rows(rows, R, recipient_uid, first["pid"])
    read_uid(first["pid"], recipient_uid, rec_ticks)
    need(grant(first) == snap, "grant-or-owner-drift", "passive grant entry changed")
    sample["grantOwnerSame"] = True
    read_uid(first["pid"], recipient_uid, rec_ticks)
    need(boot() == boot_id, "boot-drift", "boot identity changed")
    sample["recipientSame"] = True
    sample["bootSame"] = True
    # Second complete enumeration catches contradictory identity within the same sample.
    final_rows = enumerate_rows()
    final = {}
    sample["finalRows"] = final
    final_matches = publisher_rows(final_rows, old, final)
    strict_rows(final_rows, R, recipient_uid, first["pid"])
    need(bool(matches) == bool(final_matches), "interrupted-sample", "publisher presence changed within sample")
    if matches:
        read_stat(old, old_ticks, True)
        sample["valid"] = True
        return False
    p = raw_shell("stat", "/proc/" + str(old))
    if p.returncode == 0:
        read_stat(old, old_ticks, True)
        fail("old-pid-still-present", "old proc directory reappeared")
    need(missing(p, "/proc/" + str(old)), "old-pid-absence-unproven", "final proc ENOENT not verified")
    sample["oldProcAbsent"] = True
    sample["valid"] = True
    return True

try:
    need(API == 29 and os.environ["FIXTURE_HEAD"] == "6c2c3da9ad742aed92fb44de564688cbb3c869e7",
         "image-provenance", "API or pinned fixture SHA mismatch")
    devices = [l.split() for l in run("adb", "devices").splitlines()[1:] if l.strip()]
    need(len(devices) == 1 and len(devices[0]) == 2 and devices[0][0].startswith("emulator-")
         and devices[0][1] == "device", "image-provenance", "not one live emulator")
    serial = devices[0][0]
    need(shell("getprop", "ro.kernel.qemu") == "1" and shell("getprop", "ro.build.version.sdk") == "29"
         and shell("getprop", "ro.product.cpu.abi") == "x86_64", "image-provenance", "image/API/ABI mismatch")
    image_props = pathlib.Path(os.environ["ANDROID_HOME"]) / "system-images/android-29/default/x86_64/source.properties"
    props = image_props.read_text()
    rev = re.findall(r"(?m)^Pkg.Revision\s*=\s*([0-9.]+)\s*$", props)
    need(len(rev) == 1, "image-provenance", "image revision unavailable")
    receipt["image"] = safe_identifier(shell("getprop", "ro.build.fingerprint"), r"[A-Za-z0-9_./:+-]{1,250}", "guest fingerprint")
    receipt["imageRevision"] = rev[0]
    receipt["kernelRelease"] = safe_identifier(shell("uname", "-r"), r"[A-Za-z0-9_.+-]{1,160}", "kernel release")
    receipt["kernelBuild"] = safe_identifier(shell("uname", "-v"), r"[A-Za-z0-9_ .:#()+,/-]{1,250}", "kernel build")
    receipt["toyboxVersion"] = safe_identifier(shell("toybox", "--version"), r"[A-Za-z0-9_. +()-]{1,160}", "toybox version")
    digest = re.fullmatch(r"([a-f0-9]{64})\s+/system/bin/toybox", shell("sha256sum", "/system/bin/toybox"))
    need(digest is not None, "image-provenance", "toybox hash unavailable")
    receipt["toyboxSha256"] = digest[1]
    boot_id = boot()
    enumerate_rows()
    receipt["supportedColumns"] = ["UID", "PID", "NAME"]
    need(not any(pkg in packages() for pkg in (P, R)), "fixture-already-installed", "fixture package already installed")
    for module, pkg in (("publisher", P), ("recipient", R)):
        apk = BASE / module / "build/outputs/apk/debug" / (module + "-debug.apk")
        root = manifest(apk)
        need(root.get("package") == pkg and root.get(NS+"sharedUserId") is None
             and all(node.get(NS+"process") is None for node in root.iter()),
             "fixture-manifest", "manifest process/package isolation")
        need("Success" in adb("install", str(apk), timeout=120), "fixture-install", "fixture install failed")
        installed.append(pkg)
    publisher_uid, recipient_uid = uid(P), uid(R)
    need(publisher_uid != recipient_uid, "actor-mismatch", "publisher and recipient same UID")
    first, pub = command("grant", publisher=True, initial=True)
    old = pub["publisherPid"]
    need(old != first["pid"], "actor-mismatch", "publisher and recipient same PID")
    snapshot = grant(first)
    old_ticks, _ = read_stat(old)
    rec_ticks, _ = read_stat(first["pid"])
    read_uid(old, publisher_uid, old_ticks, True)
    read_uid(first["pid"], recipient_uid, rec_ticks)
    reread, _ = command("reread")
    same_owner(first, reread)
    need(grant(reread) == snapshot, "provider-phase-grant-lost", "grant changed after reread")
    def eligible():
        need(process(P, publisher_uid, "pre-kill-publisher", old) == old
             and process(R, recipient_uid, "pre-kill-recipient", first["pid"]) == first["pid"],
             "actor-mismatch", "strict process continuity before kill")
        rows = enumerate_rows()
        strict_rows(rows, P, publisher_uid, old)
        strict_rows(rows, R, recipient_uid, first["pid"])
        read_uid(old, publisher_uid, old_ticks, True)
        read_uid(first["pid"], recipient_uid, rec_ticks)
        state = readiness(old)
        return state if state["killEligible"] else None
    deadline = time.monotonic() + 20
    poll(eligible, "publisher-not-background", "connection or OOM state did not become eligible", 20)
    deadline = None
    check_admission()
    deadline = time.monotonic() + 20
    need(grant(reread) == snapshot, "provider-phase-grant-lost", "prekill grant changed")
    witness = eligible()
    need(witness is not None and boot() == boot_id, "prekill-readiness", "immediate readiness or boot drift")
    receipt["preKill"] = {**witness, "publisherPid": old, "publisherUid": publisher_uid,
                          "publisherStartTicks": old_ticks, "recipientPid": first["pid"],
                          "recipientUid": recipient_uid, "recipientStartTicks": rec_ticks,
                          "recipientTask": first["taskId"], "grantCount": 1, "grantSame": True,
                          "closedResourceReread": True, "ownerSame": True, "bootSame": True}
    deadline = None
    receipt["newPublisherPidObserved"] = False
    receipt["killCount"] = 1  # attempted; never retry even if command fails
    start = time.monotonic()
    deadline = start + 15
    shell("am", "kill", P)
    receipt["killCompleted"] = True
    for i in range(1, 61):
        sample = {"index": i, "valid": False, "rowsComplete": False, "newPublisherPidObserved": False}
        receipt["samples"].append(sample)
        try:
            absent = validate_sample(sample, old, old_ticks, first, rec_ticks, snapshot, boot_id)
            if time.monotonic() >= deadline:
                sample["valid"] = False
                fail("budget-expired", "sample exceeded deadline")
        finally:
            sample["elapsedMs"] = round((time.monotonic() - start) * 1000)
        if absent:
            receipt.update(outcome="observed-absence", stopReason="first fully valid absence sample", terminalSample=i)
            break
        remaining = deadline - time.monotonic()
        if remaining <= 0 or i == 60:
            fail("budget-expired", "old publisher remained bound within sample budget")
        time.sleep(min(0.25, remaining))
except Contradiction as e:
    receipt["outcome"] = "contradictory-identity" if receipt["killCompleted"] else "stopped"
    receipt["stopReason"] = str(e)
except Failed as e:
    receipt["outcome"] = "inconclusive" if receipt["killCompleted"] else "stopped"
    receipt["stopReason"] = str(e)
except Exception as e:
    receipt["outcome"] = "inconclusive" if receipt["killCompleted"] else "stopped"
    receipt["stopReason"] = "unsupported: " + type(e).__name__
finally:
    if receipt["killCount"]:
        receipt["elapsedMs"] = round((time.monotonic() - start) * 1000)
    receipt["sampleCount"] = len(receipt["samples"])
    deadline = None
    receipt["cleanup"] = True
    for pkg in reversed(installed):
        try:
            need("Success" in adb("uninstall", pkg, timeout=60), "fixture-cleanup", "synthetic cleanup failed")
        except Failed:
            receipt["cleanup"] = False
    if not receipt["cleanup"]:
        receipt["outcome"] = "stopped"
        receipt["stopReason"] = "isolated fixture cleanup failed"
    EVIDENCE.write_text(json.dumps(receipt, indent=2) + "\n")
print("share exit observation", receipt["outcome"], receipt.get("stopReason", "unknown"))
if receipt["outcome"] == "stopped":
    sys.exit(1)
OBSERVE
