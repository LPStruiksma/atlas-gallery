#!/usr/bin/env python3
"""Synthetic, no-network, no-secret tests for scripts/owner_mac_m1_proof.sh (#488).

Every tool the bootstrap touches (uname, curl, keytool, sdkmanager, avdmanager,
emulator, adb and the candidate's proof script) is a mocked stand-in, driven
through a real pty so prompts read from /dev/tty exactly as on the Mac. A pass
is NOT evidence that the downloads, the Android SDK, the emulator or real
signing work on macOS: only Lukas's run on his Mac shows that.
"""
import hashlib
import io
import json
import os
import pathlib
import pty
import re
import select
import subprocess
import sys
import tarfile
import tempfile
import time
import zipfile

HERE = pathlib.Path(__file__).resolve().parent
SHELL = HERE / "owner_mac_m1_proof.sh"
PY = sys.executable
STORE_PW = "fictional-Store-Pw-7731"
WRONG_PW = "fictional-wrong-Pw-0042"
CERT = "c" * 64

LOGGER = f'''#!{PY}
import json, os, sys
def log(tool):
    with open(os.environ["MOCK_LOG"], "a") as f:
        f.write(json.dumps({{"tool": tool, "argv": sys.argv[1:], "env": dict(os.environ)}}) + "\\n")
'''

FAKE_UNAME = LOGGER + '''
log("uname")
print(os.environ.get("MOCK_UNAME_M", "arm64") if sys.argv[1:] == ["-m"] else os.environ.get("MOCK_UNAME_S", "Darwin"))
'''
FAKE_SYSCTL = LOGGER + '''
log("sysctl")
'''
FAKE_CURL = LOGGER + '''
log("curl")
args = sys.argv[1:]
url = args[-1]
src = os.path.join(os.environ["MOCK_CURL_DIR"], url.rsplit("/", 1)[1])
if not os.path.isfile(src):
    sys.exit(22)
data = open(src, "rb").read()
if "-o" in args:
    open(args[args.index("-o") + 1], "wb").write(data)
else:
    sys.stdout.buffer.write(data)
'''
FAKE_KEYTOOL = LOGGER + '''
import getpass
log("keytool")
a = sys.argv[1:]
ks = a[a.index("-keystore") + 1]
if a[0] == "-genkeypair":
    p1 = getpass.getpass("Enter keystore password:  ")
    p2 = getpass.getpass("Re-enter new password: ")
    if not p1 or p1 != p2:
        sys.exit("keytool error: passwords differ")
    open(ks, "w").write("FAKE PKCS12 KEYSTORE (synthetic, no key material)\\n")
elif a[0] == "-list":
    pw = getpass.getpass("Enter keystore password:  ")
    if os.environ.get("MOCK_KEYTOOL_LIST_FAIL") or not os.path.isfile(ks):
        sys.exit("keytool error: java.io.IOException: keystore password was incorrect")
    print("atlas-native-release, 7 Nov 2026, PrivateKeyEntry,")
    print("Certificate fingerprint (SHA-256): " + ":".join(["CC"] * 32))
else:
    sys.exit("unexpected keytool call")
'''
FAKE_GRADLE = LOGGER + '''
log("gradle")
'''
FAKE_SDKMANAGER = LOGGER + r'''
log("sdkmanager")
a = sys.argv[1:]
root = [x.split("=", 1)[1] for x in a if x.startswith("--sdk_root=")][0]
if "--licenses" in a:
    sys.stdin.read(64)
    sys.exit(0)
tools = {
    "platform-tools/adb": os.environ["MOCK_ADB_SRC"],
    "emulator/emulator": os.environ["MOCK_EMULATOR_SRC"],
    "build-tools/36.0.0/apksigner": os.environ["MOCK_NOOP_SRC"],
}
for rel, src in tools.items():
    dst = os.path.join(root, rel)
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    open(dst, "w").write(open(src).read())
    os.chmod(dst, 0o700)
'''
FAKE_AVDMANAGER = LOGGER + '''
import shutil
log("avdmanager")
a = sys.argv[1:]
home = os.environ["ANDROID_AVD_HOME"]
name = a[a.index("-n") + 1]
if a[:2] == ["create", "avd"]:
    os.makedirs(os.path.join(home, name + ".avd"))
    open(os.path.join(home, name + ".ini"), "w").write("image=" + a[a.index("-k") + 1] + "\\n")
elif a[:2] == ["delete", "avd"]:
    shutil.rmtree(os.path.join(home, name + ".avd"), ignore_errors=True)
    if os.path.exists(os.path.join(home, name + ".ini")):
        os.remove(os.path.join(home, name + ".ini"))
'''
FAKE_EMULATOR = LOGGER + '''
import time
log("emulator")
a = sys.argv[1:]
state = os.environ["MOCK_EMU_STATE"]
open(state, "w").write(a[a.index("-port") + 1])
for _ in range(600):
    if not os.path.exists(state):
        sys.exit(0)
    time.sleep(0.1)
'''
FAKE_ADB = LOGGER + '''
log("adb")
a = sys.argv[1:]
state = os.environ["MOCK_EMU_STATE"]
port = open(state).read() if os.path.exists(state) else ""
if a == ["start-server"]:
    sys.exit(0)
if a == ["devices"]:
    print("List of devices attached")
    if port:
        print("emulator-%s\\tdevice" % port)
    sys.exit(0)
if a[0] == "-s":
    if a[1] != "emulator-" + port:
        sys.exit("adb: device not found")
    rest = a[2:]
    if rest == ["shell", "getprop", "sys.boot_completed"]:
        print("1\\r"); sys.exit(0)
    if rest == ["shell", "pm", "path", "android"]:
        print("package:/system/framework/framework-res.apk"); sys.exit(0)
    if rest == ["emu", "kill"]:
        os.remove(state); sys.exit(0)
sys.exit("unexpected adb call: %r" % a)
'''
FAKE_PROOF = '''#!/usr/bin/env bash
# Synthetic stand-in for the candidate's p0_owner_local_signer_emulator_proof.sh.
set -euo pipefail
"$MOCK_PY" -c 'import json,os,sys; open(os.environ["MOCK_LOG"],"a").write(json.dumps({"tool":"proof","argv":sys.argv[1:],"env":dict(os.environ)})+"\\n")' "$@"
[[ $# -eq 5 && "$4" =~ ^emulator-[0-9]+$ && "$5" = /* && ! -e "$5" ]] || { echo "NO-GO: proof args" >&2; exit 1; }
read -r -s -p 'Local owner keystore password: ' storepass </dev/tty; printf '\\n' >/dev/tty
read -r -s -p 'Local owner key password: ' keypass </dev/tty; printf '\\n' >/dev/tty
[[ -n "$storepass" && "$storepass" == "$keypass" ]] || { echo "NO-GO: mock passwords" >&2; exit 1; }
unset storepass keypass
mkdir -m 700 "$5"
{ echo "apk_a_sha256=$(printf a | shasum -a 256 2>/dev/null | cut -c1-64 || true)"
  echo "source_commit=$(git -C "$1" rev-parse HEAD)"
  echo "signer_certificate_sha256=%s"
  echo "emulator_android_in_place_upgrade=PASS"; } > "$5/public-evidence.txt"
echo "private log line that must not be printed by the bootstrap" > "$5/instrumentation-seed.txt"
echo "PASS: mock proof"
''' % CERT


def write_exec(path: pathlib.Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text)
    path.chmod(0o700)


def zip_with(path: pathlib.Path, files: dict) -> None:
    with zipfile.ZipFile(path, "w") as z:
        for name, text in files.items():
            info = zipfile.ZipInfo(name)
            info.external_attr = (0o100755 << 16)
            z.writestr(info, text)


def tar_with(path: pathlib.Path, files: dict) -> None:
    with tarfile.open(path, "w:gz") as t:
        for name, text in files.items():
            data = text.encode()
            info = tarfile.TarInfo(name)
            info.size = len(data)
            info.mode = 0o755
            t.addfile(info, io.BytesIO(data))


def sha(path: pathlib.Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


class Fixture:
    def __init__(self, root: pathlib.Path, scenario: str):
        self.root = root
        self.scenario = scenario
        self.home = root / "home"
        self.home.mkdir()
        self.log = root / "calls.jsonl"
        self.dl = root / "served"
        self.dl.mkdir()
        bin_dir = root / "bin"
        write_exec(bin_dir / "uname", FAKE_UNAME)
        write_exec(bin_dir / "sysctl", FAKE_SYSCTL)
        write_exec(bin_dir / "curl", FAKE_CURL)
        self.bin = bin_dir
        for name, text in (("adb", FAKE_ADB), ("emulator", FAKE_EMULATOR), ("noop", LOGGER + "\nlog('noop')\n")):
            write_exec(root / "srcs" / name, text)
        # Served "vendor" files.
        tar_with(self.dl / "OpenJDK17U-jdk_aarch64_mac_hotspot_17.0.13_11.tar.gz",
                 {"jdk-17.0.13+11/Contents/Home/bin/keytool": FAKE_KEYTOOL,
                  "jdk-17.0.13+11/Contents/Home/bin/java": LOGGER + "\nlog('java')\n"})
        tar_with(self.dl / "OpenJDK17U-jdk_x64_mac_hotspot_17.0.13_11.tar.gz",
                 {"jdk-17.0.13+11/Contents/Home/bin/keytool": FAKE_KEYTOOL,
                  "jdk-17.0.13+11/Contents/Home/bin/java": LOGGER + "\nlog('java-x64')\n"})
        for f in ("OpenJDK17U-jdk_aarch64_mac_hotspot_17.0.13_11.tar.gz",
                  "OpenJDK17U-jdk_x64_mac_hotspot_17.0.13_11.tar.gz"):
            digest = sha(self.dl / f)
            if scenario == "jdk-sha-mismatch":
                digest = "0" * 64
            (self.dl / (f + ".sha256.txt")).write_text(f"{digest}  {f}\n")
        zip_with(self.dl / "gradle-8.13-bin.zip", {"gradle-8.13/bin/gradle": FAKE_GRADLE})
        (self.dl / "gradle-8.13-bin.zip.sha256").write_text(sha(self.dl / "gradle-8.13-bin.zip"))
        clt = {"cmdline-tools/bin/sdkmanager": FAKE_SDKMANAGER,
               "cmdline-tools/bin/avdmanager": FAKE_AVDMANAGER}
        zip_with(self.dl / "commandlinetools-mac_arm64-16111833_latest.zip", clt)
        zip_with(self.dl / "commandlinetools-mac_x86_64-16111833_latest.zip",
                 dict(clt, **{"cmdline-tools/NOTICE": "x86"}))
        clt_arm = sha(self.dl / "commandlinetools-mac_arm64-16111833_latest.zip")
        clt_x64 = sha(self.dl / "commandlinetools-mac_x86_64-16111833_latest.zip")
        if scenario == "clt-pin-mismatch":
            clt_arm = "1" * 64
        # Test copy of the bootstrap: only the PINS block changes.
        source = SHELL.read_text()
        start, end = source.index("# BEGIN PINS"), source.index("# END PINS")
        pins = source[start:end]
        assert "JDK_SHA256_AARCH64=''" in pins and "GRADLE_SHA256=''" in pins
        pins = pins.replace("https://github.com/adoptium/temurin17-binaries/releases/download/$JDK_VERSION_TAG",
                            "https://fixture.invalid/jdk")
        pins = pins.replace("https://services.gradle.org/distributions", "https://fixture.invalid/gradle")
        pins = pins.replace("https://dl.google.com/android/repository", "https://fixture.invalid/android")
        pins = re.sub(r"CLT_SHA256_AARCH64='[0-9a-f]{64}'", f"CLT_SHA256_AARCH64='{clt_arm}'", pins)
        pins = re.sub(r"CLT_SHA256_X64='[0-9a-f]{64}'", f"CLT_SHA256_X64='{clt_x64}'", pins)
        pins = pins.replace("https://github.com/LPStruiksma/atlas-gallery.git", "/nonexistent/no-network")
        self.script = root / "bootstrap-copy/scripts/owner_mac_m1_proof.sh"
        write_exec(self.script, source[:start] + pins + source[end:])
        # Candidate repository with a synthetic proof script.
        self.repo = root / "candidate-repo"
        write_exec(self.repo / "scripts/p0_owner_local_signer_emulator_proof.sh", FAKE_PROOF)
        subprocess.run(["git", "init", "-q", str(self.repo)], check=True)
        g = ["git", "-C", str(self.repo), "-c", "user.name=ATLAS Fixture", "-c", "user.email=fixture@example.invalid"]
        subprocess.run(g + ["add", "."], check=True)
        subprocess.run(g + ["commit", "-qm", "synthetic candidate"], check=True)
        self.commit = subprocess.run(["git", "-C", str(self.repo), "rev-parse", "HEAD"],
                                     check=True, capture_output=True, text=True).stdout.strip()
        self.backup = root / "owner-backup-stick"
        self.backup.mkdir()

    def env(self) -> dict:
        env = {k: v for k, v in os.environ.items() if not k.startswith(("ATLAS_", "ANDROID_", "JAVA_HOME"))}
        env.update({
            "HOME": str(self.home),
            "PATH": str(self.bin) + ":" + os.environ["PATH"],
            "MOCK_LOG": str(self.log),
            "MOCK_CURL_DIR": str(self.dl),
            "MOCK_EMU_STATE": str(self.root / "emulator-running"),
            "MOCK_ADB_SRC": str(self.root / "srcs/adb"),
            "MOCK_EMULATOR_SRC": str(self.root / "srcs/emulator"),
            "MOCK_NOOP_SRC": str(self.root / "srcs/noop"),
            "MOCK_PY": PY,
            "MOCK_UNAME_M": "x86_64" if self.scenario == "x86-existing-key" else "arm64",
            "MOCK_UNAME_S": "Linux" if self.scenario == "not-macos" else "Darwin",
        })
        if self.scenario == "backup-verify-fails":
            env["MOCK_KEYTOOL_LIST_FAIL"] = "1"
        if self.scenario == "inherited-secret-env":
            env["ATLAS_P0_STORE_PASSWORD"] = "x"
        return env

    def calls(self, tool: str) -> list:
        if not self.log.exists():
            return []
        rows = [json.loads(line) for line in self.log.read_text().splitlines()]
        return [r for r in rows if r["tool"] == tool]

    def run(self, args: list, backup_answer: str) -> tuple:
        answers = [
            (b"Enter keystore password:", STORE_PW),
            (b"Re-enter new password:", STORE_PW),
            (b"Backup folder path", backup_answer),
            (b"Type YES", "YES"),
            (b"Local owner keystore password:", STORE_PW),
            (b"Local owner key password:", STORE_PW),
        ]
        answered = {p: 0 for p, _ in answers}
        pid, master = pty.fork()
        if pid == 0:
            os.execvpe("bash", ["bash", str(self.script)] + args, self.env())
        transcript = bytearray()
        deadline = time.monotonic() + 90
        status = None
        while time.monotonic() < deadline:
            readable, _, _ = select.select([master], [], [], 0.05)
            if readable:
                try:
                    chunk = os.read(master, 65536)
                except OSError:
                    chunk = b""
                transcript.extend(chunk)
                for prompt, reply in answers:
                    if transcript.count(prompt) > answered[prompt]:
                        answered[prompt] += 1
                        if prompt == b"Enter keystore password:":
                            # The fake keytool logs before it prompts, so the last
                            # call says whether this is genkeypair or -list.
                            last = self.calls("keytool")[-1]["argv"][0]
                            failing = self.scenario == "backup-verify-fails" and last == "-list"
                            reply = WRONG_PW if failing else STORE_PW
                        os.write(master, reply.encode() + b"\n")
            done, raw = os.waitpid(pid, os.WNOHANG)
            if done:
                status = os.waitstatus_to_exitcode(raw)
                while True:
                    try:
                        chunk = os.read(master, 65536)
                    except OSError:
                        break
                    if not chunk:
                        break
                    transcript.extend(chunk)
                break
        if status is None:
            os.kill(pid, 9)
            os.waitpid(pid, 0)
            raise AssertionError("bootstrap stalled: " + transcript.decode("utf-8", "replace")[-1500:])
        return status, transcript.decode("utf-8", "replace")

    def assert_no_secret_anywhere(self, transcript: str) -> None:
        for pw in (STORE_PW, WRONG_PW):
            assert pw not in transcript, "password reached the terminal transcript"
            for call in (json.loads(l) for l in self.log.read_text().splitlines()) if self.log.exists() else []:
                assert pw not in " ".join(call["argv"]), f"password in argv of {call['tool']}"
                assert pw not in json.dumps(call["env"]), f"password in environment of {call['tool']}"
            for path in self.root.rglob("*"):
                if path.is_file() and not path.is_symlink():
                    assert pw.encode() not in path.read_bytes(), f"password written to {path}"

    def assert_emulator_gone(self) -> None:
        assert not (self.root / "emulator-running").exists(), "emulator left running"
        avd_home = self.home / "ATLAS-M1/android-user/avd"
        leftovers = list(avd_home.glob("atlas-m1-*")) if avd_home.exists() else []
        assert not leftovers, f"AVD not deleted: {leftovers}"


def check(scenario: str) -> None:
    with tempfile.TemporaryDirectory(prefix="atlas-m1-bootstrap-") as tmp:
        fx = Fixture(pathlib.Path(tmp), scenario)
        key = fx.home / "ATLAS-signing/atlas-permanent.p12"
        commit = fx.commit
        backup_answer = str(fx.backup)
        if scenario == "x86-existing-key":
            key.parent.mkdir(mode=0o700)
            key.write_text("FAKE EXISTING PKCS12 (synthetic)\n")
            (key.parent / "backup-verified.txt").write_text(
                f"key_sha256={sha(key)}\nbackup_file={fx.backup}/atlas-permanent.p12\n")
        if scenario == "candidate-mismatch":
            commit = "0123456789abcdef0123456789abcdef01234567"
        if scenario == "malformed-candidate":
            commit = commit[:12]
        if scenario == "backup-inside-signing":
            backup_answer = str(fx.home / "ATLAS-signing")
        status, out = fx.run([commit, str(fx.repo)], backup_answer)
        fx.assert_no_secret_anywhere(out)
        proof_calls = fx.calls("proof")

        if scenario in ("new-key-arm64", "x86-existing-key"):
            assert status == 0, f"{scenario} exit {status}: {out[-2000:]}"
            assert len(proof_calls) == 1
            argv = proof_calls[0]["argv"]
            assert argv[1] == str(key) and argv[2] == "atlas-native-release" and argv[3] == "emulator-5580", argv
            assert argv[4].startswith(str(fx.home / "ATLAS-M1/evidence-")), argv
            assert pathlib.Path(argv[0], ".git").exists() and argv[0].startswith(str(fx.home / "ATLAS-M1/source-"))
            assert "ATLAS M1 owner-local proof (public evidence only)" in out
            assert f"public_certificate_sha256={CERT}" in out and f"source_commit={commit}" in out
            assert "private log line" not in out, "bootstrap printed a private log"
            fx.assert_emulator_gone()
            sdk_installs = [c["argv"] for c in fx.calls("sdkmanager") if "--licenses" not in c["argv"]]
            abi = "x86_64" if scenario == "x86-existing-key" else "arm64-v8a"
            assert any(f"system-images;android-35;google_apis;{abi}" in a for a in sdk_installs), sdk_installs
            assert all("build-tools;36.0.0" in a and "platforms;android-36" in a for a in sdk_installs)
            jdk = "x64" if scenario == "x86-existing-key" else "aarch64"
            urls = [c["argv"][-1] for c in fx.calls("curl")]
            assert any(f"OpenJDK17U-jdk_{jdk}_mac" in u and u.endswith(".tar.gz") for u in urls), urls
            # The proof gets Java/Gradle/SDK from ~/ATLAS-M1 and no signing variables.
            env = proof_calls[0]["env"]
            assert env["ANDROID_HOME"] == str(fx.home / "ATLAS-M1/android-sdk")
            assert env["JAVA_HOME"].startswith(str(fx.home / "ATLAS-M1/tools/jdk-17.0.13+11"))
            assert not any(k.startswith(("ATLAS_P0_", "ATLAS_STABLE_SIGNING_")) for k in env)
            genkey = [c for c in fx.calls("keytool") if c["argv"][0] == "-genkeypair"]
            if scenario == "x86-existing-key":
                assert not genkey, "existing key must be reused, never regenerated"
                assert "Backup folder path" not in out, "verified backup must not be asked again"
                assert key.read_text() == "FAKE EXISTING PKCS12 (synthetic)\n"
            else:
                assert len(genkey) == 1
                g = genkey[0]["argv"]
                for flag, value in (("-storetype", "PKCS12"), ("-alias", "atlas-native-release"),
                                    ("-keyalg", "RSA"), ("-keysize", "4096")):
                    assert g[g.index(flag) + 1] == value, g
                assert sha(fx.backup / "atlas-permanent.p12") == sha(key)
                assert (key.stat().st_mode & 0o777) == 0o600
                assert (key.parent.stat().st_mode & 0o777) == 0o700
                marker = (key.parent / "backup-verified.txt").read_text()
                assert f"key_sha256={sha(key)}" in marker
                # Idempotent re-run: no downloads, same key, no backup prompt, new AVD/evidence.
                curl_before = len(fx.calls("curl"))
                status2, out2 = fx.run([commit, str(fx.repo)], backup_answer)
                fx.assert_no_secret_anywhere(out2)
                assert status2 == 0, f"re-run exit {status2}: {out2[-2000:]}"
                assert len(fx.calls("curl")) == curl_before, "re-run downloaded again"
                assert len([c for c in fx.calls("keytool") if c["argv"][0] == "-genkeypair"]) == 1
                assert "Backup folder path" not in out2
                proofs = fx.calls("proof")
                assert len(proofs) == 2 and proofs[0]["argv"][4] != proofs[1]["argv"][4]
                fx.assert_emulator_gone()
        else:
            assert status != 0, f"unsafe scenario {scenario} was accepted: {out[-1500:]}"
            assert "NO-GO" in out, out[-1500:]
            assert not proof_calls, f"{scenario}: proof ran despite refusal"
            expected = {
                "jdk-sha-mismatch": "sha256 mismatch for OpenJDK17U-jdk_aarch64",
                "clt-pin-mismatch": "sha256 mismatch for commandlinetools-mac_arm64",
                "backup-verify-fails": "no verified backup",
                "backup-inside-signing": "Backup must be outside",
                "candidate-mismatch": "not found",
                "malformed-candidate": "40-character",
                "not-macos": "macOS only",
                "inherited-secret-env": "Refusing inherited signing variable",
            }[scenario]
            assert expected in out, f"{scenario}: expected {expected!r} in {out[-1500:]}"
            if scenario == "jdk-sha-mismatch":
                assert not (fx.home / "ATLAS-M1/tools/jdk-17.0.13+11").exists()
                assert not list((fx.home / "ATLAS-M1/downloads").glob("OpenJDK*.tar.gz*"))
            if scenario in ("backup-verify-fails", "backup-inside-signing"):
                assert not (fx.home / "ATLAS-signing/backup-verified.txt").exists()
                fx.assert_emulator_gone()
            if scenario == "backup-verify-fails":
                assert len([c for c in fx.calls("keytool") if c["argv"][0] == "-list"]) == 3
        print("PASS synthetic mock: " + scenario)


SCENARIOS = (
    "new-key-arm64", "x86-existing-key", "jdk-sha-mismatch", "clt-pin-mismatch",
    "backup-verify-fails", "backup-inside-signing", "candidate-mismatch",
    "malformed-candidate", "not-macos", "inherited-secret-env",
)


def static_checks() -> None:
    subprocess.run(["bash", "-n", str(SHELL)], check=True)
    text = "\n".join(l for l in SHELL.read_text().splitlines() if not l.lstrip().startswith("#"))
    # macOS ships bash 3.2: keep bash-4-only syntax out.
    for pattern in (r"\bmapfile\b", r"\breadarray\b", r"\$\{[A-Za-z_]+,,\}", r"\$\{[A-Za-z_]+\^\^\}",
                    r"declare -A", r"\|&", r"&>>", r"\[\[ -v "):
        assert not re.search(pattern, text), f"bash 3.2 incompatible syntax: {pattern}"
    assert "set +x" in text and "set -x" not in text.replace("set +x", "")
    assert not re.search(r"(--ks-pass|-storepass|-keypass)\b", text), "credential flag on a command line"


if __name__ == "__main__":
    assert SHELL.is_file(), "Missing owner_mac_m1_proof.sh next to this test"
    static_checks()
    for name in SCENARIOS:
        check(name)
    print(f"PASS: {len(SCENARIOS)} synthetic mock scenarios; NO macOS/Android/download/cryptography assertions")
