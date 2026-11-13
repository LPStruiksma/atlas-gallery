#!/usr/bin/env python3
"""Synthetic, no-network, no-secret tests for scripts/owner_mac_release.sh (#490).

Every tool the script touches (uname, curl, sdkmanager, gradle, zipalign,
apksigner, aapt) is a mocked stand-in, driven through a real pty so the
password prompt reads from /dev/tty exactly as on the Mac. A pass is NOT
evidence that Gradle, the Android SDK or real signing work on macOS: only
Lukas's run on his Mac shows that.
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
SHELL = HERE / "owner_mac_release.sh"
PY = sys.executable
KEY_PW = "fictional-Key-Pw-5519"
WRONG_PW = "fictional-wrong-Pw-0042"
M1_CERT = "5f15596090e3cfa339c2fd73a2039a23ecca9d02ae4e3506e22dabe976ce170b"
OTHER_CERT = "d" * 64

# Every mock logs its argv and environment. Password values never reach the
# log: a mock that legitimately receives one records only that it was present.
LOGGER = f'''#!{PY}
import json, os, sys
SECRETS = (os.environ.get("MOCK_KEY_PW_SHA", ""),)
def log(tool, redact=()):
    import hashlib
    env = dict(os.environ)
    for k in redact:
        if k in env:
            env[k] = "<present>"
    for k, v in list(env.items()):
        if hashlib.sha256(v.encode()).hexdigest() in SECRETS:
            env[k] = "<password-value-in-unexpected-variable>"
    with open(os.environ["MOCK_LOG"], "a") as f:
        f.write(json.dumps({{"tool": tool, "argv": sys.argv[1:], "env": env}}) + "\\n")
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
# Fake Gradle: reads the stamped version from the private build copy and writes
# a JSON "APK" where AGP puts the unsigned release APK.
FAKE_GRADLE = LOGGER + r'''
import re
log("gradle")
if sys.argv[1:2] != [":app:assembleRelease"]:
    sys.exit("unexpected gradle task %r" % sys.argv[1:])
if os.environ.get("MOCK_GRADLE_FAIL"):
    print("FAILURE: Build failed with an exception (synthetic)")
    sys.exit(1)
text = open("app/build.gradle.kts").read()
apk = {
    "package": "nl.atlas.gallery",
    "versionCode": re.search(r"versionCode\s*=\s*(\d+)", text).group(1),
    "versionName": re.search(r'versionName\s*=\s*"([^"]*)"', text).group(1),
    "debuggable": bool(os.environ.get("MOCK_DEBUGGABLE")),
    "signed": None,
}
out = "app/build/outputs/apk/release"
os.makedirs(out, exist_ok=True)
json.dump(apk, open(os.path.join(out, "app-release-unsigned.apk"), "w"))
open(os.environ["MOCK_GRADLE_DONE"], "w").write("done")
print("BUILD SUCCESSFUL (synthetic)")
'''
FAKE_ZIPALIGN = LOGGER + '''
import shutil
log("zipalign")
a = sys.argv[1:]
if "-c" in a:
    sys.exit(0)
shutil.copyfile(a[-2], a[-1])
'''
FAKE_APKSIGNER = LOGGER + '''
import hashlib
a = sys.argv[1:]
if a[0] == "sign":
    names = [a[i + 1][4:] for i, x in enumerate(a) if x in ("--ks-pass", "--key-pass")]
    log("apksigner", redact=names)
    if not names or not all(a[a.index(f) + 1].startswith("env:") for f in ("--ks-pass", "--key-pass")):
        sys.exit("mock apksigner: passwords must come from env:")
    for n in names:
        if hashlib.sha256(os.environ.get(n, "").encode()).hexdigest() != os.environ["MOCK_KEY_PW_SHA"]:
            sys.exit("Failed to load signer: keystore password was incorrect (synthetic)")
    apk = json.load(open(a[-1]))
    apk["signed"] = os.environ.get("MOCK_SIGNER", "%s")
    json.dump(apk, open(a[a.index("--out") + 1], "w"))
elif a[:2] == ["verify", "--print-certs"]:
    log("apksigner")
    apk = json.load(open(a[-1]))
    print("Signer #1 certificate DN: CN=ATLAS Native local owner signing")
    print("Signer #1 certificate SHA-256 digest: " + apk["signed"])
else:
    log("apksigner")
    sys.exit("unexpected apksigner call")
''' % M1_CERT
FAKE_AAPT = LOGGER + '''
log("aapt")
a = sys.argv[1:]
apk = json.load(open(a[-1]))
print("package: name='%%s' versionCode='%%s' versionName='%%s' platformBuildVersionName='16'"
      %% (apk["package"], apk["versionCode"], apk["versionName"]))
print("sdkVersion:'29'")
if apk["debuggable"]:
    print("application-debuggable")
print("application-label:'ATLAS'")
'''.replace("%%", "%")
FAKE_SDKMANAGER = LOGGER + r'''
log("sdkmanager")
a = sys.argv[1:]
root = [x.split("=", 1)[1] for x in a if x.startswith("--sdk_root=")][0]
if "--licenses" in a:
    sys.stdin.read(64)
    sys.exit(0)
for rel, src in (("build-tools/36.0.0/apksigner", "apksigner"), ("build-tools/36.0.0/zipalign", "zipalign"),
                 ("build-tools/36.0.0/aapt", "aapt"), ("platforms/android-36/android.jar", None)):
    dst = os.path.join(root, rel)
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    if src:
        open(dst, "w").write(open(os.path.join(os.environ["MOCK_SRCS"], src)).read())
        os.chmod(dst, 0o700)
    else:
        open(dst, "w").write("synthetic")
'''


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


BUILD_GRADLE = '''android {
    defaultConfig {
        applicationId = "nl.atlas.gallery"
        versionCode = 5
        versionName = "0.3.0-upgrade-candidate"
    }
}
'''


class Fixture:
    def __init__(self, root: pathlib.Path, scenario: str):
        self.root = root
        self.scenario = scenario
        self.home = root / "home"
        self.home.mkdir()
        self.log = root / "calls.jsonl"
        self.gradle_done = root / "gradle-done"
        self.dl = root / "served"
        self.dl.mkdir()
        self.bin = root / "bin"
        write_exec(self.bin / "uname", FAKE_UNAME)
        write_exec(self.bin / "sysctl", FAKE_SYSCTL)
        write_exec(self.bin / "curl", FAKE_CURL)
        self.srcs = root / "srcs"
        for name, text in (("apksigner", FAKE_APKSIGNER), ("zipalign", FAKE_ZIPALIGN), ("aapt", FAKE_AAPT)):
            write_exec(self.srcs / name, text)
        jdk_files = {"jdk-17.0.13+11/Contents/Home/bin/java": LOGGER + "\nlog('java')\n"}
        for f in ("OpenJDK17U-jdk_aarch64_mac_hotspot_17.0.13_11.tar.gz",
                  "OpenJDK17U-jdk_x64_mac_hotspot_17.0.13_11.tar.gz"):
            tar_with(self.dl / f, jdk_files)
            (self.dl / (f + ".sha256.txt")).write_text(f"{sha(self.dl / f)}  {f}\n")
        zip_with(self.dl / "gradle-8.13-bin.zip", {"gradle-8.13/bin/gradle": FAKE_GRADLE})
        (self.dl / "gradle-8.13-bin.zip.sha256").write_text(sha(self.dl / "gradle-8.13-bin.zip"))
        clt = {"cmdline-tools/bin/sdkmanager": FAKE_SDKMANAGER}
        zip_with(self.dl / "commandlinetools-mac_arm64-16111833_latest.zip", clt)
        zip_with(self.dl / "commandlinetools-mac_x86_64-16111833_latest.zip", dict(clt, **{"cmdline-tools/NOTICE": "x86"}))
        # Test copy of the script: only the PINS block changes.
        source = SHELL.read_text()
        start, end = source.index("# BEGIN PINS"), source.index("# END PINS")
        pins = source[start:end]
        assert f"EXPECTED_SIGNER_SHA256='{M1_CERT}'" in pins and "VERSION_CODE_FLOOR=261109153\n" in pins
        pins = pins.replace("https://github.com/adoptium/temurin17-binaries/releases/download/$JDK_VERSION_TAG",
                            "https://fixture.invalid/jdk")
        pins = pins.replace("https://services.gradle.org/distributions", "https://fixture.invalid/gradle")
        pins = pins.replace("https://dl.google.com/android/repository", "https://fixture.invalid/android")
        x64 = sha(self.dl / "OpenJDK17U-jdk_x64_mac_hotspot_17.0.13_11.tar.gz")
        if scenario == "fresh-x86-jdk-pin-mismatch":
            x64 = "0" * 64
        pins = re.sub(r"JDK_SHA256_X64='[0-9a-f]{64}'", f"JDK_SHA256_X64='{x64}'", pins)
        pins = re.sub(r"GRADLE_SHA256='[0-9a-f]{64}'", f"GRADLE_SHA256='{sha(self.dl / 'gradle-8.13-bin.zip')}'", pins)
        pins = re.sub(r"CLT_SHA256_AARCH64='[0-9a-f]{64}'",
                      f"CLT_SHA256_AARCH64='{sha(self.dl / 'commandlinetools-mac_arm64-16111833_latest.zip')}'", pins)
        pins = re.sub(r"CLT_SHA256_X64='[0-9a-f]{64}'",
                      f"CLT_SHA256_X64='{sha(self.dl / 'commandlinetools-mac_x86_64-16111833_latest.zip')}'", pins)
        pins = pins.replace("https://github.com/LPStruiksma/atlas-gallery.git", "/nonexistent/no-network")
        if scenario == "version-not-above-floor":
            pins = pins.replace("VERSION_CODE_FLOOR=261109153\n", "VERSION_CODE_FLOOR=991231230\n")
        self.script = root / "script-copy/scripts/owner_mac_release.sh"
        write_exec(self.script, source[:start] + pins + source[end:])
        # Source repository with a synthetic app/build.gradle.kts.
        self.repo = root / "source-repo"
        (self.repo / "app").mkdir(parents=True)
        (self.repo / "app/build.gradle.kts").write_text(BUILD_GRADLE)
        subprocess.run(["git", "init", "-q", str(self.repo)], check=True)
        g = ["git", "-C", str(self.repo), "-c", "user.name=ATLAS Fixture", "-c", "user.email=fixture@example.invalid"]
        subprocess.run(g + ["add", "."], check=True)
        subprocess.run(g + ["commit", "-qm", "synthetic source"], check=True)
        if scenario == "tag-floor":
            subprocess.run(["git", "-C", str(self.repo), "tag", "v0.3.991231230"], check=True)
        elif scenario == "tag-fetch-refused":
            subprocess.run(["git", "-C", str(self.repo), "tag", "v0.3.261109153"], check=True)
        self.commit = subprocess.run(["git", "-C", str(self.repo), "rev-parse", "HEAD"],
                                     check=True, capture_output=True, text=True).stdout.strip()
        # The M1 key and its backup record, as owner_mac_m1_proof.sh leaves them.
        self.key = self.home / "ATLAS-signing/atlas-permanent.p12"
        if scenario != "no-key":
            self.key.parent.mkdir(mode=0o700)
            self.key.write_text("FAKE PKCS12 KEYSTORE (synthetic, no key material)\n")
            if scenario != "key-not-backed-up":
                (self.key.parent / "backup-verified.txt").write_text(
                    f"key_sha256={sha(self.key)}\nbackup_file=/Volumes/STICK/atlas-permanent.p12\n")
        # Tools already in ~/ATLAS-M1, as the M1 run left them (unless "fresh").
        if not scenario.startswith("fresh"):
            m1 = self.home / "ATLAS-M1"
            write_exec(m1 / "tools/jdk-17.0.13+11/Contents/Home/bin/java", LOGGER + "\nlog('java')\n")
            write_exec(m1 / "tools/gradle-8.13/bin/gradle", FAKE_GRADLE)
            for name in ("apksigner", "zipalign", "aapt"):
                write_exec(m1 / "android-sdk/build-tools/36.0.0" / name, (self.srcs / name).read_text())
            (m1 / "android-sdk/platforms/android-36").mkdir(parents=True)
            (m1 / "android-sdk/platforms/android-36/android.jar").write_text("synthetic")

    def env(self) -> dict:
        env = {k: v for k, v in os.environ.items() if not k.startswith(("ATLAS_", "ANDROID_", "JAVA_HOME"))}
        env.update({
            "HOME": str(self.home),
            "PATH": str(self.bin) + ":" + os.environ["PATH"],
            "MOCK_LOG": str(self.log),
            "MOCK_CURL_DIR": str(self.dl),
            "MOCK_SRCS": str(self.srcs),
            "MOCK_GRADLE_DONE": str(self.gradle_done),
            "MOCK_KEY_PW_SHA": hashlib.sha256(KEY_PW.encode()).hexdigest(),
            "MOCK_UNAME_M": "x86_64" if self.scenario.startswith("fresh-x86") else "arm64",
            "MOCK_UNAME_S": "Linux" if self.scenario == "not-macos" else "Darwin",
        })
        if self.scenario == "wrong-signer":
            env["MOCK_SIGNER"] = OTHER_CERT
        if self.scenario == "debuggable":
            env["MOCK_DEBUGGABLE"] = "1"
        if self.scenario == "gradle-fails":
            env["MOCK_GRADLE_FAIL"] = "1"
        if self.scenario == "inherited-secret-env":
            env["ATLAS_RELEASE_KEY_PASSWORD"] = "x"
        return env

    def calls(self, tool: str) -> list:
        if not self.log.exists():
            return []
        rows = [json.loads(line) for line in self.log.read_text().splitlines()]
        return [r for r in rows if r["tool"] == tool]

    def run(self, args: list) -> tuple:
        prompt = b"ATLAS key password:"
        answered = 0
        self.prompted_before_gradle = False
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
                if transcript.count(prompt) > answered:
                    answered += 1
                    if not self.gradle_done.exists():
                        self.prompted_before_gradle = True
                    reply = WRONG_PW if self.scenario == "wrong-password" else KEY_PW
                    if self.scenario == "wrong-password-then-right" and answered == 1:
                        reply = WRONG_PW
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
            raise AssertionError("script stalled: " + transcript.decode("utf-8", "replace")[-1500:])
        self.prompts = answered
        return status, transcript.decode("utf-8", "replace")

    def assert_no_secret_anywhere(self, transcript: str) -> None:
        assert not self.prompted_before_gradle, "password prompt came before Gradle finished"
        for pw in (KEY_PW, WRONG_PW):
            assert pw not in transcript, "password reached the terminal transcript"
            for path in self.root.rglob("*"):
                if path.is_file() and not path.is_symlink():
                    assert pw.encode() not in path.read_bytes(), f"password written to {path}"
        rows = [json.loads(l) for l in self.log.read_text().splitlines()] if self.log.exists() else []
        for call in rows:
            for pw in (KEY_PW, WRONG_PW):
                assert pw not in " ".join(call["argv"]), f"password in argv of {call['tool']}"
            text = json.dumps(call["env"])
            assert "<password-value-in-unexpected-variable>" not in text, f"password in env of {call['tool']}"
            holders = [k for k, v in call["env"].items() if v == "<present>"]
            if call["tool"] == "apksigner" and call["argv"][0] == "sign":
                assert holders == ["ATLAS_RELEASE_KEY_PASSWORD"], holders
            else:
                assert "ATLAS_RELEASE_KEY_PASSWORD" not in call["env"], f"password variable reached {call['tool']}"

    def desktop_apks(self) -> list:
        d = self.home / "Desktop"
        return sorted(d.glob("ATLAS-*.apk")) if d.exists() else []


def check(scenario: str) -> None:
    with tempfile.TemporaryDirectory(prefix="atlas-release-") as tmp:
        fx = Fixture(pathlib.Path(tmp), scenario)
        commit = fx.commit
        if scenario == "commit-mismatch":
            commit = "0123456789abcdef0123456789abcdef01234567"
        if scenario == "malformed-commit":
            commit = commit[:12]
        if scenario == "tag-fetch-refused":
            # Force refresh failure after checkout, without harming the mock clone.
            source = fx.script.read_text()
            needle = 'git -C "$checkout" fetch -q --force --tags origin'
            assert needle in source
            fx.script.write_text(source.replace(needle, 'false'))
        start_utc = time.gmtime()
        status, out = fx.run([commit, str(fx.repo)])
        end_utc = time.gmtime()
        fx.assert_no_secret_anywhere(out)
        gradle_calls = fx.calls("gradle")

        if scenario in ("reuse-arm64", "fresh-x86", "wrong-password-then-right", "tag-floor"):
            assert status == 0, f"{scenario} exit {status}: {out[-2000:]}"
            assert len(gradle_calls) == 1 and gradle_calls[0]["argv"][0] == ":app:assembleRelease"
            genv = gradle_calls[0]["env"]
            assert not any(k.startswith(("ATLAS_P0_", "ATLAS_STABLE_SIGNING_", "ATLAS_RELEASE_")) for k in genv)
            assert genv["ANDROID_HOME"] == str(fx.home / "ATLAS-M1/android-sdk")
            assert genv["JAVA_HOME"] == str(fx.home / "ATLAS-M1/tools/jdk-17.0.13+11/Contents/Home")
            m = re.search(r"^version_code=(\d{9})\r?$", out, re.M)
            assert m, out[-1500:]
            code = m.group(1)
            assert int(code) > 261109153 and re.fullmatch(r"\d\d(0[1-9]|1[0-2])(0[1-9]|[12]\d|3[01])([01]\d|2[0-3])[0-9]", code)
            if scenario == "tag-floor":
                assert code == "991231231", f"latest tag + 1 not enforced: {code}"
            elif scenario == "reuse-arm64":
                # The build may cross a six-minute boundary; require the
                # actual UTC slot at either side, never an arbitrary future code.
                expected = {
                    int(time.strftime("%y%m%d%H", moment)) * 10 + moment.tm_min // 6
                    for moment in (start_utc, end_utc)
                }
                assert int(code) in expected, f"nine-digit UTC slot mismatch: {code} vs {sorted(expected)}"
            name = f"0.3.{code}"
            apks = fx.desktop_apks()
            assert [p.name for p in apks] == [f"ATLAS-{name}.apk"], apks
            apk = json.loads(apks[0].read_text())
            assert apk == {"package": "nl.atlas.gallery", "versionCode": code, "versionName": name,
                           "debuggable": False, "signed": M1_CERT}, apk
            for line in ("ATLAS release build (public evidence only)", f"source_commit={commit}",
                         "package=nl.atlas.gallery", f"version_name={name}", f"apk_sha256={sha(apks[0])}",
                         f"signer_certificate_sha256={M1_CERT}", "debuggable=absent"):
                assert line in out, f"missing {line!r}: {out[-1500:]}"
            # The checkout keeps the committed source; only the private copy was stamped.
            work = list((fx.home / "ATLAS-M1").glob(f"release-{commit[:12]}-*"))
            assert len(work) == 1
            assert (work[0] / "source/app/build.gradle.kts").read_text() == BUILD_GRADLE
            assert subprocess.run(["git", "-C", str(work[0] / "source"), "status", "--porcelain"],
                                  capture_output=True, text=True, check=True).stdout == ""
            assert not (work[0] / "build").exists(), "Gradle build copy should be removed after success"
            signs = [c for c in fx.calls("apksigner") if c["argv"][0] == "sign"]
            assert len(signs) == (2 if scenario == "wrong-password-then-right" else 1)
            s = signs[-1]["argv"]
            assert s[s.index("--ks") + 1] == str(fx.key) and s[s.index("--ks-key-alias") + 1] == "atlas-native-release"
            assert fx.prompts == len(signs)
            urls = [c["argv"][-1] for c in fx.calls("curl")]
            sdk = [c["argv"] for c in fx.calls("sdkmanager") if "--licenses" not in c["argv"]]
            if scenario == "fresh-x86":
                assert any("OpenJDK17U-jdk_x64_mac" in u and u.endswith(".tar.gz") for u in urls), urls
                assert any("commandlinetools-mac_x86_64" in u for u in urls), urls
                assert sdk and all("build-tools;36.0.0" in a and "platforms;android-36" in a for a in sdk), sdk
                assert not any("emulator" in " ".join(a) or "system-images" in " ".join(a) for a in sdk), sdk
            else:
                assert not urls, f"tools in ~/ATLAS-M1 must be reused, not downloaded: {urls}"
                assert not sdk
            # Nothing touches a device.
            assert not fx.calls("adb") and "adb " not in SHELL.read_text().replace("No adb", "")
        else:
            assert status != 0, f"unsafe scenario {scenario} was accepted: {out[-1500:]}"
            assert "NO-GO" in out, out[-1500:]
            assert not fx.desktop_apks(), f"{scenario}: an APK reached the Desktop despite refusal"
            expected = {
                "wrong-signer": "is not the M1 key",
                "debuggable": "APK is debuggable",
                "version-not-above-floor": "outside the safe release range",
                "commit-mismatch": "not found",
                "malformed-commit": "40-character",
                "wrong-password": "Signing failed 3 times",
                "inherited-secret-env": "Refusing inherited signing variable",
                "not-macos": "macOS only",
                "no-key": "No key at",
                "key-not-backed-up": "no verified backup record",
                "gradle-fails": "Gradle release build failed",
                "fresh-x86-jdk-pin-mismatch": "sha256 pin mismatch",
                "tag-fetch-refused": "Cannot fetch release tags",
            }[scenario]
            assert expected in out, f"{scenario}: expected {expected!r} in {out[-1500:]}"
            if scenario in ("version-not-above-floor", "tag-fetch-refused", "commit-mismatch", "malformed-commit", "inherited-secret-env",
                            "not-macos", "no-key", "key-not-backed-up", "fresh-x86-jdk-pin-mismatch"):
                assert not gradle_calls, f"{scenario}: Gradle ran despite refusal"
            if scenario in ("version-not-above-floor", "tag-fetch-refused", "commit-mismatch", "malformed-commit", "no-key",
                            "key-not-backed-up", "gradle-fails", "fresh-x86-jdk-pin-mismatch", "not-macos"):
                assert fx.prompts == 0, f"{scenario}: password asked despite refusal"
            if scenario == "wrong-password":
                assert fx.prompts == 3 and len([c for c in fx.calls("apksigner") if c["argv"][0] == "sign"]) == 3
            if scenario in ("wrong-signer", "debuggable"):
                work = next((fx.home / "ATLAS-M1").glob("release-*"))
                assert not list(work.glob("ATLAS-0.3.*[0-9].apk")), "refused signed APK must be deleted"
            if scenario == "gradle-fails":
                assert "synthetic" in out, "Gradle failure output must be shown"
        print("PASS synthetic mock: " + scenario)


SCENARIOS = (
    "reuse-arm64", "fresh-x86", "wrong-password-then-right",
    "wrong-signer", "debuggable", "version-not-above-floor", "commit-mismatch", "malformed-commit",
    "wrong-password", "inherited-secret-env", "not-macos", "no-key", "key-not-backed-up",
    "gradle-fails", "fresh-x86-jdk-pin-mismatch", "tag-floor", "tag-fetch-refused",
)


def static_checks() -> None:
    subprocess.run(["bash", "-n", str(SHELL)], check=True)
    text = "\n".join(l for l in SHELL.read_text().splitlines() if not l.lstrip().startswith("#"))
    # macOS ships bash 3.2: keep bash-4-only syntax out.
    for pattern in (r"\bmapfile\b", r"\breadarray\b", r"\$\{[A-Za-z_]+,,\}", r"\$\{[A-Za-z_]+\^\^\}",
                    r"declare -A", r"\|&", r"&>>", r"\[\[ -v ", r"\bcoproc\b", r"\$\{[A-Za-z_]+\[@\]"):
        assert not re.search(pattern, text), f"bash 3.2 incompatible syntax: {pattern}"
    assert "set +x" in text and "set -x" not in text.replace("set +x", "")
    # Passwords only via env: handoff, never on a command line.
    assert not re.search(r"(-storepass|-keypass|pass:)", text), "credential on a command line"
    assert text.count("--ks-pass env:ATLAS_RELEASE_KEY_PASSWORD") == 1
    assert text.count("--key-pass env:ATLAS_RELEASE_KEY_PASSWORD") == 1
    # No device, emulator or install step.
    for word in (r"\badb\b", r"\bemulator\b", r"\bavdmanager\b", r"\binstall -r\b", r"\bpm install\b"):
        code = "\n".join(l for l in text.splitlines() if not l.strip().startswith(("echo", "die", "say")))
        assert not re.search(word, code), f"device step in the release script: {word}"
    # Gradle must finish before the password is read.
    assert text.index("gradle :app:assembleRelease") < text.index("read -r -s")
    # bash 3.2 really parses it, when a bash 3.2 is available (macOS /bin/bash).
    old = pathlib.Path("/bin/bash")
    if old.exists():
        ver = subprocess.run([str(old), "-c", "echo ${BASH_VERSINFO[0]}"], capture_output=True, text=True).stdout.strip()
        if ver == "3":
            subprocess.run([str(old), "-n", str(SHELL)], check=True)


if __name__ == "__main__":
    assert SHELL.is_file(), "Missing owner_mac_release.sh next to this test"
    static_checks()
    for name in SCENARIOS:
        check(name)
    print(f"PASS: {len(SCENARIOS)} synthetic mock scenarios; NO macOS/Android/Gradle/cryptography assertions")
