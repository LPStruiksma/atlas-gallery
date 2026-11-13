#!/usr/bin/env python3
"""Synthetic, no-Android/no-secret validation of the P0 detached signer driver.

The mocked toolchain intentionally uses text files, not real APKs or credentials.
A successful test is NOT evidence of Android compilation, cryptographic signing
or any real device/emulator run. Requires Linux/macOS pty and git; no network.
"""
import os
import pathlib
import pty
import select
import subprocess
import tempfile
import time

SHELL = pathlib.Path(__file__).with_name("p0_owner_local_signer_emulator_proof.sh")

FAKE_GRADLE = '''#!/usr/bin/python3
import os,re,pathlib
assert not any(k.startswith('ATLAS_P0_') or k.startswith('ATLAS_STABLE_SIGNING_') for k in os.environ), 'OWNER SECRET REACHED GRADLE'
p=pathlib.Path.cwd()
n=int(re.search(r'versionCode\\s*=\\s*(\\d+)',(p/'app/build.gradle.kts').read_text()).group(1))
for name in ('app/build/outputs/apk/debug/app-debug.apk','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'):
 f=p/name;f.parent.mkdir(parents=True,exist_ok=True)
 f.write_text('PACKAGE=nl.atlas.gallery\\nVERSION='+str(n)+'\\nPREVIEW\\n')
'''
FAKE_APKSIGNER = '''#!/usr/bin/python3
import os,sys,pathlib
x=sys.argv[1:]; cert='a'*64
if x[0]=='sign':
 assert os.environ.get('ATLAS_P0_STORE_PASSWORD')=='fictional-store'
 assert os.environ.get('ATLAS_P0_KEY_PASSWORD')=='fictional-key'
 output=x[x.index('--out')+1]; original=x[-1]
 pathlib.Path(output).write_text(pathlib.Path(original).read_text()+'\\nSIGNED_CERT='+cert+'\\n')
elif x[0]=='verify':
 assert 'SIGNED_CERT='+cert in pathlib.Path(x[-1]).read_text()
 print('Signer #1 certificate SHA-256 digest: '+cert)
else: raise SystemExit('Unexpected fake apksigner command')
'''
FAKE_SHASUM = '''#!/usr/bin/python3
import hashlib,sys
assert sys.argv[1:3]==['-a','256'], sys.argv
args=sys.argv[3:]
data=open(args[0],'rb').read() if args else sys.stdin.buffer.read()
print(hashlib.sha256(data).hexdigest()+'  '+(args[0] if args else '-'))
'''

def macos_like_path(root: pathlib.Path) -> str:
    """A PATH like a stock Mac: no GNU sha256sum/realpath, a shasum instead."""
    mac = root / "mac-bin"
    mac.mkdir()
    for d in ("/usr/local/bin", "/usr/bin", "/bin"):
        for entry in sorted(pathlib.Path(d).iterdir()) if pathlib.Path(d).is_dir() else []:
            target = mac / entry.name
            if entry.name in ("sha256sum", "realpath", "shasum") or target.exists():
                continue
            target.symlink_to(entry)
    (mac / "shasum").write_text(FAKE_SHASUM)
    (mac / "shasum").chmod(0o700)
    return str(mac)

FAKE_AAPT = '''#!/usr/bin/python3
import sys,re,pathlib
t=pathlib.Path(sys.argv[-1]).read_text()
print("package: name='nl.atlas.gallery' versionCode='"+re.search(r'VERSION=(\\d+)',t).group(1)+"' versionName='fixture'")
'''
FAKE_ADB = '''#!/usr/bin/python3
import os,sys,pathlib,re
state=pathlib.Path(os.environ['MOCK_ADB_STATE'])
x=sys.argv[1:]
assert x[:2]==['-s','emulator-5554'], 'forbidden serial'
x=x[2:]
if x[:3]==['shell','getprop','ro.kernel.qemu']: print('1');exit()
if x[:3]==['shell','pm','path']: raise SystemExit('pm path exits 1 for a missing package; use pm list packages')
if x[:4]==['shell','pm','list','packages']:
 if os.environ.get('MOCK_ADB_PREINSTALLED'): print('package:nl.atlas.gallery.debugtools\\r\\npackage:nl.atlas.gallery\\r')
 else: print('package:nl.atlas.gallery.unrelated\\r')
 exit()
if x[0]=='install':
 data=pathlib.Path(x[-1]).read_text()
 assert 'SIGNED_CERT='+'a'*64 in data
 n=int(re.search(r'VERSION=(\\d+)',data).group(1))
 assert n==(4 if '-r' in x else 3), 'incorrect version progression'
 print('Success');exit()
if x[:3]==['shell','am','instrument']:
 assert x[-1]=='nl.atlas.gallery.test/androidx.test.runner.AndroidJUnitRunner'
 print('OK (1 test)');exit()
if x[0]=='shell' and x[1].startswith('run-as '):
 state.write_bytes(sys.stdin.buffer.read());exit()
if x[0]=='exec-out' and x[1]=='run-as':
 sys.stdout.buffer.write(state.read_bytes());exit()
raise SystemExit('Unexpected fake adb request: '+repr(x))
'''

def run_scenario(scenario: str) -> None:
    with tempfile.TemporaryDirectory(prefix="atlas-p0-signing-fixture-") as tmp:
        root = pathlib.Path(tmp)
        repo = root / "reviewed-fixture"
        kt = repo / "app/src/androidTest/java/nl/atlas/gallery/PeopleUpgradeTest.kt"
        kt.parent.mkdir(parents=True)
        (repo / "app/build.gradle.kts").write_text(
            "android { defaultConfig {\n    versionCode = 3\n } }\n"
        )
        source = (
            'private val productionPrefsName = "atlas_v4_live_sync"\n'
            'private val productionDigestKey = "sha256"\n'
            '@Test fun seedBeforeUpgrade() {\n'
            '  context.getSharedPreferences("test-only",0).edit().putString("a","b").commit()\n'
            '  context.getSharedPreferences(productionPrefsName,0).edit().putString(productionDigestKey,"fictional-digest-not-a-live-document").commit()\n'
            '}\n'
            '@Test fun snapshotBeforeSyntheticSignerMismatch() { }\n'
            '@Test fun verifyAfterUpgrade() {\n'
            '  val settings = context.getSharedPreferences("test-only",0); settings.getString("a",null)\n'
            '  val production = context.getSharedPreferences(productionPrefsName,0);\n'
            '  assertEquals("fictional-digest-not-a-live-document", production.getString(productionDigestKey,null))\n'
            '}\n'
        )
        if scenario == "missing-production-pref":
            source = source.replace('"atlas_v4_live_sync"', '"test-only-different-namespace"')
        if scenario == "missing-settings-assertion":
            source = source.replace("production.getString(", "production.getInt(")
        kt.write_text(source)
        prod = repo / "app/src/main/java/nl/atlas/gallery/GalleryRepository.kt"
        prod.parent.mkdir(parents=True, exist_ok=True)
        prod.write_text('val v4LinkPrefs = application.getSharedPreferences("atlas_v4_live_sync",0)\nval uri=v4LinkPrefs.getString("sha256", null)\n')
        subprocess.run(["git", "init", "-q", str(repo)], check=True)
        git = ["git", "-C", str(repo), "-c", "user.name=ATLAS Fixture",
               "-c", "user.email=fixture@example.invalid"]
        subprocess.run(git + ["add", "."], check=True)
        subprocess.run(git + ["commit", "-qm", "synthetic fixture"], check=True)

        bin_dir = root / "bin"
        bin_dir.mkdir()
        sdk = root / "sdk/build-tools/36.0.0"
        sdk.mkdir(parents=True)
        for path, code in [
            (bin_dir / "gradle", FAKE_GRADLE),
            (bin_dir / "adb", FAKE_ADB),
            (sdk / "apksigner", FAKE_APKSIGNER),
            (sdk / "aapt", FAKE_AAPT),
        ]:
            path.write_text(code)
            path.chmod(0o700)
        key = root / "SYNTHETIC-NOT-REAL-KEY.p12"
        key.write_text("NOT A KEY; synthetic mocked signer only")
        env = os.environ.copy()
        base_path = macos_like_path(root) if scenario == "macos-tools" else env["PATH"]
        env.update({
            "PATH": str(bin_dir) + ":" + base_path,
            "ANDROID_HOME": str(root / "sdk"),
            "MOCK_ADB_STATE": str(root / "fake-emulator-state"),
        })
        if scenario == "preinstalled-app":
            env["MOCK_ADB_PREINSTALLED"] = "1"
        if scenario == "inherited-signing-env":
            env["ATLAS_STABLE_SIGNING_KEY_ALIAS"] = "malicious"
        args = ["bash", str(SHELL), str(repo), str(key), "synthetic-alias",
                "emulator-5554", str(root / "private-proof")]
        if scenario == "physical-device":
            args[-2] = "R5NotAnEmulator"

        pid, master = pty.fork()
        if pid == 0:
            os.execvpe("bash", args, env)
        transcript = bytearray()
        deadline = time.monotonic() + 45
        password_stage = 0
        status = None
        while time.monotonic() < deadline:
            readable, _, _ = select.select([master], [], [], 0.1)
            if readable:
                try:
                    chunk = os.read(master, 8192)
                except OSError:
                    chunk = b""
                transcript.extend(chunk)
                if password_stage == 0 and b"Local owner keystore password:" in transcript:
                    os.write(master, b"fictional-store\n")
                    password_stage = 1
                elif password_stage == 1 and b"Local owner key password:" in transcript:
                    os.write(master, b"fictional-key\n")
                    password_stage = 2
            completed, raw = os.waitpid(pid, os.WNOHANG)
            if completed:
                status = os.waitstatus_to_exitcode(raw)
                break
        if status is None:
            os.kill(pid, 9)
            os.waitpid(pid, 0)
            raise AssertionError("Fixture driver stalled")
        msg = transcript.decode("utf-8", "replace")
        assert "fictional-store" not in msg and "fictional-key" not in msg, "Password echoed!"
        if scenario in ("success", "macos-tools"):
            assert status == 0, f"Mock happy path returned {status}: {msg[-1400:]}"
            evidence = (root / "private-proof/public-evidence.txt").read_text()
            for required in (
                "apk_a_sha256=", "apk_b_sha256=", "A=3\nB=4",
                "androidtest_apk_a_sha256=", "androidtest_apk_b_sha256=",
                "instrumentation_seed_sha256=", "instrumentation_verify_sha256=",
                "emulator_android_in_place_upgrade=PASS",
                "synthetic_sharedpreferences_file_preserved=PASS",
                "production_v4_sync_digest_preference_storage_instrumentation=PASS",
                "production_v4_live_link_ui_and_saf_grants=NOT_TESTED",
            ):
                assert required in evidence, required
            assert "NO S24+ approval" in msg
        else:
            assert status != 0, f"Unsafe fixture {scenario} was accepted"
            assert ("NO-GO" in msg or scenario == "missing-settings-assertion"), msg[-1000:]
            if scenario == "preinstalled-app":
                assert "Emulator already has ATLAS; use a fresh AVD" in msg, msg[-1000:]
        print("PASS synthetic mock: " + scenario)

if __name__ == "__main__":
    assert SHELL.is_file(), "Missing P0 driver in script directory"
    subprocess.run(["bash", "-n", str(SHELL)], check=True)
    scenarios = ("success", "macos-tools", "physical-device", "inherited-signing-env",
                 "preinstalled-app", "missing-settings-assertion", "missing-production-pref")
    for scenario in scenarios:
        run_scenario(scenario)
    print(f"PASS: {len(scenarios)} synthetic mock scenarios; NO Android/cryptography/device assertions")
