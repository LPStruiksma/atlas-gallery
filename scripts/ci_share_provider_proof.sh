#!/usr/bin/env bash
# #1125 staged proof: capability probe followed by terminal grant-owner control.
set -euo pipefail
mkdir -p share-proof-evidence
python3 - <<'PY'
import pathlib, re, sys
root = pathlib.Path(".")
fixture = root / "test-fixtures/share-proof"
def value(path, pattern):
    m = re.findall(pattern, path.read_text())
    if len(m) != 1:
        sys.exit("version-drift")
    return m[0]
agp = r'id\("com.android.application"\)\s+version\s+"([^"]+)"'
sdk = r'(?m)^\s*compileSdk\s*=\s*(\d+)\s*$'
if value(root / "build.gradle.kts", agp) != value(fixture / "build.gradle.kts", agp):
    sys.exit("version-drift")
for module in ("publisher", "recipient"):
    if value(root / "app/build.gradle.kts", sdk) != value(fixture / module / "build.gradle.kts", sdk):
        sys.exit("version-drift")
PY
gradle -p test-fixtures/share-proof --init-script "$PWD/scripts/ci/central-mirror.init.gradle" :publisher:assembleDebug :recipient:assembleDebug --no-daemon --stacktrace
gradle --init-script "$PWD/scripts/ci/central-mirror.init.gradle" :app:assembleDebug --no-daemon --stacktrace
python3 - <<'PY'
import hashlib, json, os, pathlib, re, subprocess, sys, time, uuid, xml.etree.ElementTree as ET
P = "nl.atlas.shareproof.publisher"
R = "nl.atlas.shareproof.recipient"
A = P + ".proof"
URI = "content://" + A + "/probe"
AUTH = "nl.atlas.gallery.share"
API = int(os.environ["ATLAS_EMULATOR_API"])
HEAD = os.environ["EXPECTED_HEAD"]
NS = "{http://schemas.android.com/apk/res/android}"
BASE = pathlib.Path("test-fixtures/share-proof")
EVIDENCE = pathlib.Path("share-proof-evidence/probe.json")
PAYLOAD = b"atlas-share-proof-v1\n"
NONCE = uuid.uuid4().hex
receipt = {"schemaVersion": 1, "scope": "fixture-capability-and-terminal-owner-control",
           "completeHarness": False, "api": API, "head": HEAD,
           "pr": int(os.environ["PR_NUMBER"]), "observations": []}
installed = []
serial = None
expected = {P: set(), R: set()}

class Failed(Exception):
    pass
def fail(code, reason):
    raise Failed(code + ": " + reason)
def need(condition, code, reason):
    if not condition:
        fail(code, reason)
def run(*args, timeout=30):
    try:
        p = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                           text=True, timeout=timeout, check=False)
    except (OSError, subprocess.TimeoutExpired):
        fail("provider-phase-runner-unsupported", "command unavailable or timed out")
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
    if site.startswith(("post-kill-", "command-post-kill-", "command-status-")):
        receipt["lastPostKillProcessObservation"] = observation
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
         and rr["released"] is True and rr["ownerFinished"] is (phase in ("finish-owner", "deny", "finish-holder")),
         "malformed-result", "recipient field types")
    need(rr["uid"] == recipient_uid and process(R, recipient_uid, "command-" + phase + "-recipient-record", rr["pid"]) == rr["pid"],
         "actor-mismatch", "recipient UID/PID")
    if phase == "deny":
        need(rr["exceptionClass"] == "SecurityException" and rr["length"] is None
             and rr["sha256"] is None, "provider-phase-grant-lost", "actual terminal read did not deny")
    elif phase not in ("status", "holder", "finish-owner", "finish-holder"):
        need(rr["exceptionClass"] is None and rr["mime"] == "application/octet-stream"
             and type(rr["length"]) is int and rr["length"] == len(PAYLOAD)
             and rr["sha256"] == hashlib.sha256(PAYLOAD).hexdigest(),
             "provider-phase-grant-lost", "recipient type/read/digest failed")
    else:
        need(all(rr[k] is None for k in ("mime", "length", "sha256", "exceptionClass")),
             "malformed-result", "status-only recipient read")
    if publisher:
        need(type(pp["publisherUid"]) is int and type(pp["publisherPid"]) is int
             and pp["publisherUid"] == publisher_uid and process(P, publisher_uid, "command-" + phase + "-publisher-record", pp["publisherPid"]) == pp["publisherPid"]
             and pp["verifiedCallerPackage"] == R and pp["outcome"] == "ok"
             and type(pp["grantIssuedCount"]) is int and pp["grantIssuedCount"] == 1,
             "actor-mismatch", "publisher identity/status/grant count")
    receipt["observations"].append({"recipient": rr, "publisher": pp})
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
    need(owners[0] in activity_dump, "recipient-grant-owner-lost", "owner ActivityRecord missing")
    # Raw URI, grant and owner identity remain in process memory; never serialize them.
    return (entries[0].strip(), owners[0])
def holder_state(first, owner, require_removed=False):
    need(process(R, recipient_uid, "terminal-holder-after-finish" if require_removed else
                 "terminal-holder-before-finish", first["pid"]) == first["pid"],
         "recipient-died-with-provider", "terminal PID changed")
    dump = shell("dumpsys", "activity", "activities")
    # Match the resumed ActivityRecord, not just a history row or a window title.
    resumed = set(re.findall(r"(?m)^\s*(?:mResumedActivity|topResumedActivity|ResumedActivity):?\s*[=:]?\s*(ActivityRecord\{[^\n]+\})\s*$", dump))
    records = {x for x in resumed if R + "/.RecipientActivity$HolderActivity" in x
               and re.search(r"\bt" + str(first["taskId"]) + r"\b", x)}
    if not records:
        return None
    need(len(records) == 1, "provider-phase-runner-unsupported", "ambiguous resumed holder")
    record = next(iter(records))
    need(record != owner, "recipient-grant-owner-lost", "holder equals grant owner")
    if require_removed and owner in dump:
        return None
    state = shell("am", "get-uid-state", str(recipient_uid))
    # AOSP prints the numeric process state, sometimes with a symbolic suffix.
    m = re.fullmatch(r"(\d+)(?:\s*\([^)]*\))?", state)
    if state in ("TOP", "PROCESS_STATE_TOP"):
        top = True
    else:
        need(m is not None, "provider-phase-runner-unsupported", "unparseable recipient UID state")
        top = int(m[1]) == 2
    return record if top else None

def no_grant():
    dump = shell("dumpsys", "activity", "permissions")
    need("ACTIVITY MANAGER URI PERMISSIONS" in dump, "provider-phase-runner-unsupported",
         "permissions header missing")
    blocks = re.split(r"(?m)^\s*\* UID (\d+) holds:\s*$", dump)
    matching = [blocks[i+1] for i in range(1, len(blocks), 2) if int(blocks[i]) == recipient_uid]
    need(len(matching) <= 1, "provider-phase-runner-unsupported", "ambiguous recipient grant dump")
    return not matching

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

try:
    devices = [l.split() for l in run("adb", "devices").splitlines()[1:] if l.strip()]
    need(len(devices) == 1 and len(devices[0]) == 2 and devices[0][0].startswith("emulator-")
         and devices[0][1] == "device", "provider-phase-runner-unsupported", "not one live emulator")
    serial = devices[0][0]
    need(shell("getprop", "ro.kernel.qemu") == "1" and shell("getprop", "ro.build.version.sdk") == str(API),
         "provider-phase-runner-unsupported", "image/API mismatch")
    receipt["image"] = shell("getprop", "ro.build.fingerprint")
    receipt["bootId"] = shell("cat", "/proc/sys/kernel/random/boot_id")
    app_manifest = manifest(pathlib.Path("app/build/outputs/apk/debug/app-debug.apk"))
    authorities = [node.get(NS + "authorities") for node in app_manifest.findall(".//provider")]
    need(all(a and not a.startswith("@") and "$" not in a for a in authorities),
         "provider-detection-invalid", "unresolved manifest authority")
    product = sum(AUTH in a.split(";") for a in authorities)
    need(product <= 1, "provider-detection-invalid", "ambiguous product authority")
    # Stage 1 may never pass if production provider is registered.
    need(product == 0, "provider-phases-missing", "product provider registered; stage 1 is insufficient")
    for path in pathlib.Path("app/src").rglob("AndroidManifest.xml"):
        if AUTH in path.read_text():
            fail("provider-detection-invalid", "source authority inconsistent with debug APK")
    receipt["providerRegistration"] = "verified-absent"
    need(not any(pkg in packages() for pkg in (P, R)),
         "provider-phase-runner-unsupported", "fixture package already installed")
    for module, pkg in (("publisher", P), ("recipient", R)):
        apk = BASE / module / "build/outputs/apk/debug" / (module + "-debug.apk")
        root = manifest(apk)
        need(root.get("package") == pkg and root.get(NS+"sharedUserId") is None
             and all(node.get(NS+"process") is None for node in root.iter()),
             "provider-phase-runner-unsupported", "manifest process/package isolation")
        need("Success" in adb("install", str(apk), timeout=120),
             "provider-phase-runner-unsupported", "fixture install")
        installed.append(pkg)
    publisher_uid, recipient_uid = uid(P), uid(R)
    need(publisher_uid != recipient_uid, "actor-mismatch", "publisher and recipient same UID")
    first, pub = command("grant", publisher=True, initial=True)
    old = pub["publisherPid"]
    snapshot = grant(first)
    reread, _ = command("reread")
    same_owner(first, reread)
    need(grant(reread) == snapshot, "provider-phase-grant-lost", "grant changed after reread")
    def eligible(site):
        need(process(P, publisher_uid, site + "-publisher", old) == old
             and process(R, recipient_uid, site + "-recipient", first["pid"]) == first["pid"],
             "provider-phase-runner-unsupported", "process continuity before kill")
        state = readiness(old)
        return state if state["killEligible"] else None
    poll(lambda: eligible("pre-kill-readiness"), "publisher-not-background", "connection or OOM state did not become eligible", 20)
    need(grant(reread) == snapshot, "provider-phase-grant-lost", "prekill grant changed")
    witness = eligible("pre-kill-immediate-readiness")
    need(witness is not None, "publisher-not-background", "immediate readiness recheck")
    need(process(P, publisher_uid, "pre-kill-final-publisher", old) == old
         and process(R, recipient_uid, "pre-kill-final-recipient", first["pid"]) == first["pid"],
         "provider-phase-runner-unsupported", "identity changed immediately before kill")
    receipt["preKill"] = {**witness, "publisherPid": old, "grantCount": 1,
                          "grantSame": True, "readOnly": True, "ownerSame": True}
    shell("am", "kill", P)  # exactly one external kill, on a verified single-process synthetic package
    poll(lambda: process(P, publisher_uid, "post-kill-wait-absent", old) is None,
         "provider-phase-runner-unsupported", "old publisher PID did not disappear", 15)
    need(process(R, recipient_uid, "post-kill-recipient-continuity", first["pid"]) == first["pid"],
         "recipient-died-with-provider", "recipient PID changed")
    later, _ = command("post-kill")
    same_owner(first, later)
    new = process(P, publisher_uid, "post-kill-new-publisher", old)
    need(new is not None and new != old, "provider-phase-runner-unsupported", "new publisher PID missing")
    need(grant(later) == snapshot, "provider-phase-grant-lost", "post-kill grant changed")
    status, post = command("status", publisher=True)
    same_owner(first, status)
    need(post["publisherPid"] == new and post["grantIssuedCount"] == 1 and grant(status) == snapshot,
         "provider-phase-grant-lost", "reconstruction or count/grant changed")
    need(shell("cat", "/proc/sys/kernel/random/boot_id") == receipt["bootId"],
         "provider-phase-runner-unsupported", "boot changed")
    receipt["postKill"] = {"oldPublisherPid": old, "newPublisherPid": new, "grantSame": True,
                           "ownerSame": True, "grantCount": 1, "recipientPid": later["pid"]}
    receipt["capabilityProbe"] = "probe-pass"
    holder, _ = command("holder")
    same_owner(first, holder)
    holder_record = poll(lambda: holder_state(first, snapshot[1]), "recipient-cached",
                         "holder did not become top in original task", 15)
    need(grant(holder) == snapshot, "provider-phase-grant-lost", "holder added or changed grant")
    finished, _ = command("finish-owner")
    same_owner(first, finished)
    def terminal_ready():
        current = holder_state(first, snapshot[1], require_removed=True)
        if current is None or not no_grant():
            return None
        need(current == holder_record, "recipient-grant-owner-lost", "holder changed")
        return current
    poll(terminal_ready, "recipient-cached", "owner removal/grant removal/top holder not observed", 15)
    need(terminal_ready() == holder_record, "recipient-cached", "immediate terminal readiness recheck")
    denied, _ = command("deny")
    same_owner(first, denied)
    need(terminal_ready() == holder_record, "recipient-grant-owner-lost", "holder/grant changed after denial")
    receipt["terminal"] = {"ownerFinished": True, "holderSameTask": True, "holderTop": True,
                           "holderIdentitySha256": hashlib.sha256(holder_record.encode()).hexdigest(),
                           "grantRemoved": True, "recipientPid": denied["pid"],
                           "actualReadDenied": True, "grantCountAtLastStatus": 1}
    # The command writes its receipt before finishing the holder; cleanup below verifies removal.
    cleaned, _ = command("finish-holder")
    same_owner(first, cleaned)
    receipt["outcome"] = "terminal-probe-pass"
except Failed as e:
    receipt["outcome"] = "failed"
    receipt["failure"] = str(e)
except Exception as e:
    receipt["outcome"] = "failed"
    receipt["failure"] = "provider-phase-runner-unsupported: " + type(e).__name__
finally:
    for pkg in reversed(installed):
        try:
            need("Success" in adb("uninstall", pkg, timeout=60),
                 "provider-phase-runner-unsupported", "cleanup")
        except Failed:
            receipt["outcome"] = "failed"
            receipt["failure"] = "provider-phase-runner-unsupported: synthetic cleanup failed"
    EVIDENCE.write_text(json.dumps(receipt, indent=2) + "\n")
print("share-provider capability probe:", receipt["outcome"])
if receipt["outcome"] != "terminal-probe-pass":
    print("share-provider process diagnostics:", json.dumps({
        key: receipt.get(key) for key in ("lastProcessObservation", "lastPostKillProcessObservation")
    }, separators=(",", ":")))
    sys.exit(receipt["failure"])
PY
