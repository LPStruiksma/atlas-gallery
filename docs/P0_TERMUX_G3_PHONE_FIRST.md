# ATLAS G3 — phone-first owner-custodied signing, **NOT APPROVED TO RUN ON REAL KEY YET**

**Status 22 Oct 2026:** API35 G1/G2 passed and native RC merged; G3 remains **NO-GO**. Lukas has an S24+ with Termux and a managed work laptop, **no personal Linux workstation**. The prior Linux-only proof remains optional. This guide prepares a phone-native owner-custody route; it is **not a request to make a real private key, install an APK, upload anything, or spend CI money**.

## Split the work

- **Lane A, cloud and secret-free**: compile **independent** versionCode `3` and `4` versions of both `nl.atlas.gallery` and its AndroidTest APK, from **the same immutable reviewed source** `36bd476530abad6ebe794bd2d00128d83940518a`. CI may use disposable debug signing. Hand back **one** 7-file GitHub Actions preview artifact **without** any owner key. Name extracted APK files **exactly** `A-app.apk`, `A-test.apk`, `B-app.apk`, `B-test.apk`. Supply a 40-hex-character pinned source in `source-commit.txt` and a four-line `SHA256SUMS` of the exact APK bytes. Owner must cross-check the artifact checksums against **the authenticated upstream GitHub run**—the manifest *inside a downloaded archive is not an independently trusted signature*. As of writing, the API35 green build provides only **version 3**, **not yet version 4**. Creating this artifact package might require a separate authorized CI run; never initiate it silently.
- **Lane E, phone and no cloud credentials**: once the four APK artifacts exist, the owner can use native Termux packages (`pkg install openjdk-21 apksigner aapt age coreutils python`) to **inspect** them before any key exists. Termux additionally requires authenticated GitHub CLI read access to this PRIVATE repository's Actions artifacts; the signer never prompts for or handles a GitHub token itself. The official Termux `apksigner` package is a wrapper around Google's Android apksigner Java JAR. A private long-term PKCS12 key must remain in `$HOME` in Termux, **never** under shared `/sdcard`/Download, source Git, company equipment or Actions.
- **Lane A, cloud emulator**: receive **the four signed APKs only** through a reviewed private artifact path. Verify public SHA256 hashes/owner cert, compile provenance, then on a fresh disposable API35 KVM emulator execute A install + `PeopleUpgradeTest#seedBeforeUpgrade`, B **`adb install -r`** + `PeopleUpgradeTest#verifyAfterUpgrade`; independently inspect both Android instrumentation outputs, persisted fictional People row, and **production** `atlas_v4_live_sync/sha256` SharedPreferences. **No private key or backup uploaded.** Require separate Actions cost authorization if charged.

## The preflight / sign / backup helper

`scripts/p0_termux_owner_sign.sh` is deliberately split into three modes. **These commands are examples, not permission to generate an owner key now.** They contain no keystore passwords and do not invoke any Android package-manager or ADB command:

```bash
# Only after Lane A provides authenticated artifacts, in native Termux:
pkg install openjdk-21 apksigner aapt age coreutils python gh
# A's successful main-only GENERATE run ID, not a downloaded ZIP path:
bash scripts/p0_termux_owner_sign.sh check 1234567890  # EXAMPLE FICTIONAL ID ONLY
# The backup destination parent is created once in private Termux home:
install -d -m 700 "$HOME/atlas-g3-backup"

# LATER, only with Lukas's explicit permission and retained owner PKCS12 key:
# bash scripts/p0_termux_owner_sign.sh backup \
#   "$HOME/.local/share/atlas-native-signing/atlas-permanent.p12" \
#   "$HOME/atlas-g3-backup/atlas-permanent.p12.age"
# bash scripts/p0_termux_owner_sign.sh sign \
#   1234567890 \
#   "$HOME/.local/share/atlas-native-signing/atlas-permanent.p12" \
#   "$HOME/atlas-g3-backup/atlas-permanent.p12.age" \
#   "$HOME/atlas-g3-signed-OUTPUT-NEW"
```

**Before a genuine owner key is ever created**, agree and test the backup/recovery plan. An acceptable one-time owner creation (not run/triggered by Lane E) would be `keytool -genkeypair` with a new **4096-bit RSA** long-lived **PKCS12** key, alias `atlas-native-release`, an owner-entered strong password, and no password in shell command arguments/environment/logs. The Termux script **never generates a key**. All APK signing is interactive (`apksigner ... --ks-pass stdin </dev/tty`); use the same PKCS12 store/key password, as common with Java PKCS12. Real signing keys are non-rotatable for existing package continuity: retain and protect the original identity and record its *public* certificate fingerprint.

**Backup:** `age -p` passphrase-encrypts the existing PKCS12 keystore *while still under private Termux home*. The helper immediately decrypts it **only through a pipe** and byte-compares against the original; on signing, it repeats the comparison and refuses to proceed on mismatch. This proves *local* encryption/recovery, **not independent backup custody**: after separate owner approval, copy only the `.age` ciphertext to an **off-phone** destination (e.g. owner's cloud Drive), retrieve a copy and compare/decrypt to establish off-device restore. Keep the **age recovery passphrase** in an independent password manager/offline recovery record, not only on the same phone. Never place plaintext `.p12` on Android shared storage; Android backups and app reinstalls do not guarantee Termux private directory recovery.

**Public evidence:** the helper emits `public-evidence.txt` recording the pinned source, package, `A=3`, `B=4`, one identical verified signing certificate SHA-256 across four signed APKs, four output APK SHA256 values, and **NOT_EXECUTED** for the final owner-cert emulator upgrade. The public manifest becomes G3 proof **only after Lane A independently executes and returns the emulator seed/verify, and verifies the resulting hashes/cert against this file.** APK v2 signatures are verified using the official `apksigner` binary on the phone, but Linux fixture mocks **do not** independently establish ARM64/runtime behavior.

## Validation boundary (fictional only)

Lane E's Linux test harness runs `bash -n` and fictional mock-GitHub provenance, signer and backup regressions with a **fictional, short-lived OpenJDK-generated RSA PKCS12 key**, real GPG AES-256 encryption and streaming decryption for the *backup adapter*, and fake `aapt`/`apksigner` Android operations. This establishes actual JCA key and AES storage behavior, source/version/hash/certificate-check control flow, and failure handling. **It does NOT execute native Termux's `age`, Android APK v2/v3 signing, genuine aapt manifest parsing or Android emulator.** Report those explicitly as untested instead of creating a fabricated G3 PASS.

**Fail-closed:** wrong GitHub repo/workflow/run ID, non-main/non-successful generation run, missing or mismatched authenticated archive digest, co-tampered APK/checksum bundle, wrong source, missing/duplicate hash, bad input digest, unexpected package or version, symlink/unsafe private key, missing or unrecoverable encrypted backup, mismatched certificate, non-v2 signatures and attempts to overwrite outputs abort. Partial output on failure is **not** a release artifact. Script contains no `adb`, `pm`, `am`, `install` action or cloud credential handling. No Android installation on S24+ without a separate future approval.

## Updated source-integrity review — non-leaf output symlinks fixed (Guard #6326992272)

The Guard reproduced a real escape through an intermediate symlink: a lexical path under `$HOME` whose ordinary immediate parent was outside the private directory after canonical resolution. The existing `private_parent()` now uses `realpath -e --` for **both HOME and the complete existing output parent**, refusing canonical parents outside HOME while retaining missing-leaf/no-overwrite and direct-parent checks. This guards **both** encrypted backup output and signed APK output before any encryption/signing call. Exactly three new deterministically reproduced cases assert: (1) a backup output through a non-leaf symlink fails with no file created and no mock age encryption call, (2) a signed-output path through the same pattern fails before any mock apksigner call, (3) normal deeply nested Termux-private backup+sign outputs still succeed. Guard's original objection is fixed at source and in the fictional regression harness; the PR remains draft pending actual native tool validation.

## Exact Lane A contract and private return route (draft PR #396)

Lane A's [draft PR #396](https://github.com/LPStruiksma/atlas-native/pull/396) explicitly emits the **same** input filenames already consumed by this helper: `A-app.apk`, `A-test.apk`, `B-app.apk`, `B-test.apk`, plus pinned `source-commit.txt` and four-entry `SHA256SUMS`; it adds `artifact-manifest.json` for cloud-side cross-checking. No renaming or conversion on the phone is needed. The owner supplies only the numeric successful Generate run ID. The helper authenticates the private GitHub repository and exact workflow ID, requires a successful `workflow_dispatch` on `main`, cross-checks generator HEAD/source pins and artifact ownership, retrieves the **Actions API SHA-256 digest** independently, downloads its original ZIP with `gh api`, verifies the full ZIP digest **before extraction**, validates the JSON manifest and 4 APK hashes, then signs only the resulting freshly staged private copies. A modified local APK folder or co-packaged SHA256SUMS is never accepted as signing input. The sign mode repeats the entire authentication rather than trusting an earlier `check` result. **The B=4 artifact has not yet been compiled in real Android CI**, because A's workflow is manual-only and remains draft with no Actions execution.

For the return path, A proposes a specifically **owner-approved published prerelease within the confirmed PRIVATE GitHub repository**, containing **only the four owner-signed APK files**. This is **not** a draft release (a read-only Actions token may not access draft releases) and not a public repository release; its assets may nevertheless be visible to users with private-repo access. The owner must knowingly approve this visibility and upload, or a new reviewed private transport must be selected. A's manual `verify` workflow would download only those four signed APKs, confirm immutable preview preimages, executable/resource payload preservation, app 3→4 and test packages, signer SHA-256 equality, then execute the existing fictional People + real production `atlas_v4_live_sync/sha256` two-phase test in a **disposable cloud API35 emulator**. No key, backup or passphrase may be uploaded.

## Guard #6340661568 — independently reviewed generator code at immutable run HEAD

**New pre-key source-authority gate (22 October 2026):** GitHub's artifact ZIP digest authenticates which ZIP GitHub stored; a successful `main` workflow run is **not** proof that the generating workflow and its executable Python inputs were the independently reviewed source. The provenance verifier now queries GitHub's authenticated **Contents API at `?ref=<exact run.head_sha>`**, not the moving `main`, for each of the three executable inputs from frozen Lane A draft #396:

- `.github/workflows/p0-cloud-g3.yml` — Git blob `3ce29cbd6ff88d4a40501b25a2bc55ecf0fe0c1e`
- `scripts/p0_cloud_g3_contract.py` — Git blob `90dbc29cb51e6b5905d8af62b6209b8dcf1be218`
- `scripts/test_p0_cloud_g3_contract.py` — Git blob `abe54869a1fd52ec0ce19a35b5b2ba725d509e53`

Each immutable Contents response must be an object reporting exactly the requested `path`, `type=file`, and its **Git blob SHA** matching the independently approved literal. A missing/malformed response, changed-workflow blob, changed helper/test blob, different path/type, failed API call, or missing authenticated credentials returns **NO-GO** *before artifact download and therefore before opening the owner keystore or calling `apksigner`*. A change to Lane A's generator requires another independent review and deliberate pin update; branch names, ZIP manifests and workflow numeric IDs alone cannot authorize it. E retains the **single** phone verifier and the original one-command run-ID UX; no additional APK packaging or owner step is introduced.

**Synthetic validation scope:** Separate changed-on-main fictional fixture tests preserve correct repo identity, workflow ID, successful main dispatch, run HEAD, artifact digest, and self-consistent APK/checksum/manifest, but substitute each Git blob response individually. All such cases must fail before signing. Original APK/checksum co-tamper and non-leaf symlink protections remain mandatory. Do not mistake host mocks for an ARM64 Termux tool test or G3 approval.

## Guard #6338964906 — independent pre-signing authenticity gate (22 Oct 2026)

This repair changes the phone interface on draft #397: `check GENERATE_RUN_ID` and `sign GENERATE_RUN_ID PRIVATE_KEY VERIFIED_BACKUP OUTPUT_DIR`. **An arbitrary ARTIFACT_DIR is intentionally no longer accepted.** The new `scripts/p0_termux_preview_provenance.py` queries `gh api repos/LPStruiksma/atlas-native`, the actual `actions/workflows/p0-cloud-g3.yml` workflow ID, `actions/runs/RUN_ID`, and its listed artifact ID. The verified run must be successful, complete, workflow-dispatched from `main` in the same private repo. The exact artifact `ATLAS-P0-G3-preview-apks` must report an independent Actions SHA-256 ZIP digest, matching repository/run/head metadata, and must not be expired. The helper downloads that **original** ZIP via `gh api .../actions/artifacts/ID/zip`, hashes it, rejects mismatch, then extracts only an exact allowlist of seven non-symlink top-level files under a fresh, owner-private staging directory. It re-checks the source pin, `generator_sha` and manifest/APK/checksum agreement. Nothing supplied solely by a local bundle can authenticate itself. The signer reads from this verified ephemeral staging directory, then removes only that staging directory on exit. Existing non-leaf symlink-ancestor checks for encrypted backup and signed output remain.

**Trust boundary:** `gh` must already be authenticated by Lukas through an owner-approved, read-only private repository/Actions access route. No API credential, token, password or signing key is stored or printed by this helper, and no auth setup is performed by Lane E. An unauthenticated GitHub client, expired artifact, GitHub CLI/API incompatibility, missing `digest`, changed run or wrong workflow all cause **NO-GO** before `apksigner sign`. A's upload-artifact v4 currently names the exact seven-file handoff and matching workflow; keep this contract frozen on both draft PRs. GitHub's SHA-256 artifact feature is not itself a human assertion that the app is safe; it authenticates origin relative to the trusted GitHub account, reviewed workflow and source. No actual generated 3→4 artifacts exist yet, so real native execution remains blocked.

**Mock-boundary regressions:** tests exercise an attacker who substitutes **all four APK bytes and regenerates both JSON and plain-text local checksums**, while the authenticated GitHub ZIP digest stays unchanged; this must abort before the signer mock is called. Additional tests cover wrong workflow/run/branch, changed archive SHA-256, valid private nested paths, and both existing non-leaf symlink escapes. They do **not** establish genuine Termux `gh` availability/permissions, native ARM64 `age`, genuine Android `apksigner`, or a real cloud-produced artifact. Those remain separate release gates.

## Official-tool validation limits and simple phone readiness

Official [Android apksigner documentation](https://developer.android.com/tools/apksigner) confirms `--ks-pass stdin` and the independent `verify --print-certs` operation. The [age CLI manual](https://github.com/FiloSottile/age/blob/main/doc/age.1.ronn) confirms that `age -p` interactively requests an encryption passphrase, `age -d` requests one for decryption, and **`age -o` normally overwrites an existing file**; the helper's preflight explicitly refuses existing output. The official Termux package definitions for `age`, `aapt`, `apksigner`, and `openjdk-21` were independently inspected, but this host **does not have the native Termux ARM64 binaries nor Google's actual apksigner JAR installed**, and outbound DNS for official downloads is unavailable. Do **not** mistake tests using a mock signer and GPG adapter for actual Termux `age` or Android APK v2 signature execution.

The shortest useful fictional **owner-phone tool canary, only when separately consented to by Lukas**, is a dry run entirely in native Termux: install those four packages, generate a disposable fictional short-lived key inside Termux private home (not an owner release identity), encrypt/decrypt a fictional small file with the real `age` package, and sign/verify one disposable **not installed** APK with real `apksigner`; destroy only those explicitly named throwaway canary files afterward. No permanent owner key should be created, backed up, uploaded or used before this passes; no phone APK installation is authorized.

**Real owner dependency (later only):** after A's artifact generation and the fictional tool canary, Lukas personally authorizes long-lived PKCS12 generation/custody, an independently retrievable **off-phone encrypted backup** plus separately retained passphrase and restore check, four APK signing locally, and transfer of **only signed APKs** via the reviewed private route. Code checks a local encrypted backup but **cannot establish off-phone custody** automatically. Any added Actions charges require explicit permission and shared P0 budget verification. **G3 remains NO-GO** until genuine owner-cert 3→4 emulator seed/verify evidence; G4 physical S24+ install is separately gated.


## Optional, no-paid-CI **FICTIONAL-ONLY G1 APK native tool canary** — two separate owner approvals

This **does not use** the unexecuted G3 version-3→4 cloud Generate workflow and **does not** generate, retain, back up or use a permanent owner release key. It reuses existing **G1 version-3-only** app and androidTest APK artifacts in successful [Actions run #39649561900](https://github.com/LPStruiksma/atlas-native/actions/runs/39649561900), on genuine RC SHA `a17204e2c484813d3c812d9cc637658ed183564e`. No APK installation, upload, ADB, new Actions run or real 3→4 proof occurs.

**Independent exact original ZIP pins**, retrieved from GitHub's authenticated Actions artifact metadata, then frozen into the separate canary script (not trusted from any downloaded ZIP's packaged checksum):

| Historical artifact | GitHub ID | Original ZIP SHA256 |
| --- | --- | --- |
| ATLAS-native-debug-APK | `12368896672` | `c479ebf9640f70891ab8d666181a9f22bc228f16eba90237cd5f04f9e1b78dfb` |
| ATLAS-ui-test-APK | `12368687009` | `1fb05c3a1b89fa0772b851cc23ffa892a9153101596a89adc50fe70324c04b6e` |

Both belong to historical run `39649561900` / source `a17204e2...`, expire **19 January 2027**, and were independently verified unexpired on 22 October 2026. The script fails closed on changed repo/run/workflow identity, artifact metadata or original downloaded ZIP SHA256 **before extracting any APK**. It selects exactly one APK per authenticated ZIP, safely copies only that payload into a fresh mode-700 temporary directory under **Termux private HOME**, and uses official `aapt` to confirm app `nl.atlas.gallery` versionCode 3 and AndroidTest `nl.atlas.gallery.test`. The source run's latest attempt was #4; the APK artifacts were uploaded in an earlier attempt of the same run/source. That is acceptable **only for fictional tool compatibility**, never G3 evidence.

### Phase 1: packages and tool checks ONLY — requires Lukas's explicit permission for phone package changes

Open the **real Termux app** on the personal S24+ (not a browser, Linux container, work laptop or desktop emulator). The commands below **do not log into GitHub, download APKs, create a key or sign/install anything**. They only update package metadata, install official tools (with Termux's confirmation prompts), and show local executables. Package installation does change the Termux environment and must be owner-approved separately.

```bash
pkg update
# Install small tools first; OpenJDK is a large separate download.
pkg install python gh age aapt coreutils git
pkg install openjdk-21 apksigner
for tool in gh age aapt apksigner keytool java python3 base64 git; do
  command -v "$tool" || echo "MISSING: $tool"
done
df -h "$HOME"
```

**If `pkg install` reports `Network is unreachable` while downloading from `packages.termux.dev`:** This is a repository/network download failure, not an APK-signing or package integrity result. Do **not** change other Termux projects, delete package databases, run blanket `pkg upgrade`, or proceed to Phase 2. In Termux run `termux-change-repo`, select the **Main repository**, choose one *different listed official/community Termux mirror* rather than the unavailable primary origin, then `pkg update`. Retry the **small-tools command** first and `pkg install openjdk-21 apksigner` separately (Java alone may require over 100 MB of download). The official [Termux mirror list](https://github.com/termux/termux-packages/wiki/Mirrors) includes individual mirrors in Europe, and the [package management guide](https://github.com/termux/termux-packages/wiki/Package-Management) recommends `termux-change-repo`. If another mirror fails similarly, check connectivity or change Wi-Fi/mobile network before more retries. A failed fetch is **not** evidence the tools are installed: run the `command -v` loop only after a successful installation. This remains Phase-1-only permission, with **no GitHub authentication or fictional key authorization**.

**Expected Phase-1 result:** all nine tool paths exist, and Termux has sufficient free private storage for native Java packages, ~21 MB of existing compressed APKs plus temporary working files. If any package cannot be installed, stop and report the missing command; do **not** install alternative unverified APK signing binaries or change the trusted real owner signer.

### Phase 2: authenticated original artifact download + **one-day FICTIONAL** test key — DIFFERENT explicit owner approval

Only after approving the fictional-key test and its access to this **private repository's** old GitHub Actions downloads. GitHub authentication is owner-controlled and credentials must **never** be provided to ChatGPT, a PR, shell arguments or public logs. Check locally:

```bash
gh auth status -h github.com
```

If not authenticated, this is a **separate account/credential action**, not covered by Phase-1 permission. Authorize `gh auth login -h github.com --web` yourself only if comfortable with GitHub's access prompts; prefer an existing sufficiently scoped login. The script neither asks for nor exports a token.

After owner consent to GitHub read access and disposable testing, paste this **single block**. It fetches the **exact immutable and checked** E canary script source through authenticated GitHub Contents, checks its Git blob SHA before running, and cleans up only its **own downloaded script** afterward. A code/hash mismatch means **STOP** (no fallback to running an unverified script):

```bash
(
  set -euo pipefail
  CANARY="$HOME/atlas-G1-fictional-canary.py"
  test ! -e "$CANARY" && test ! -L "$CANARY"
  trap 'rm -f -- "$CANARY"' EXIT
  gh api "repos/LPStruiksma/atlas-native/contents/scripts/p0_termux_v3_fictional_canary.py?ref=d79aa34d2d51d64e08f2042cd12602adbf5203fc" \
    --jq '.content' | base64 -d > "$CANARY"
  test "$(git hash-object "$CANARY")" = "0de868b86f021aa05a09a91689ab0875cbefaa5d"
  python3 "$CANARY"
)
```

Read each prompt carefully. The Python script requires you to type `FICTIONAL` before doing anything online. It checks original independent GitHub run/artifact metadata and **both entire ZIP SHA256 values before ZIP extraction**. It then prompts locally to generate a **one-day, 2048-bit fictional PKCS12** key with certificate CN `FICTIONAL-ONLY-DELETE`, prompts for a **different test** `age -p` passphrase, checks real `age -d` byte-for-byte recovery without writing new plaintext, and asks for the fictional PKCS12 test password to **re-sign copies** of both existing version-3 debug APKs. Official `apksigner verify --verbose --print-certs --min-sdk-version 29` must confirm v2 signing and that both output APKs use the same **fictional** certificate; official `aapt` rechecks package/version metadata. Re-signing an existing debug-signed APK is a **testable compatibility assumption**, not a promised success. **On success or failure**, temporary APKs, fictional key and encrypted backup are removed from the private Termux temp folder. Neither signed APK is installed or uploaded.

**Interpretation:** `FICTIONAL NATIVE TOOL SMOKE PASS` establishes only native compatibility of `gh`, `age`, `aapt`, `apksigner`, interactive prompts and short-lived PKCS12 handling. It does **not** establish real retained owner signer custody, recoverable off-phone backup, versionCode 3→4, actual `adb install -r` data retention, G3 PASS, or G4 install authorization.


**22 October 2026 S24+ checkpoint — genuine AndroidTest with no versionCode:** Owner's corrected canary **PASSED** historical workflow metadata, SHA256 validation of **both original GitHub ZIPs**, and app `aapt` package `nl.atlas.gallery` versionCode **3**, then stopped *before fictional key creation* with `unexpected genuine APK package / versionCode` on the **test** APK. Lane E independently downloaded the actual original GitHub AndroidTest ZIP and verified the same full SHA256 `1fb05c3a1b89fa0772b851cc23ffa892a9153101596a89adc50fe70324c04b6e`; its genuine binary Android manifest explicitly reports **`package=nl.atlas.gallery.test`**, `instrumentation.targetPackage=nl.atlas.gallery` and **no `android:versionCode`**. The previous test badging parser wrongly required a nonempty `versionCode` for every APK. This is a reproducible fixture/schema mismatch **not** wrong provenance. The newest frozen script version in the copy-paste block checks **exact test package name but allows missing/empty test versionCode**, and still strictly requires **app package `nl.atlas.gallery` plus versionCode 3**. Five focused host tests of the **exact corrected `check_apk` source body**, independently byte-compared with GitHub via FNV-1a `b075435d`, **5/5 PASS** (missing, blank, wrong package, app v3 strictness, aapt error); added five corresponding committed regressions to `scripts/test_p0_termux_v3_fictional_canary.py`. This is not a claim that native APK signing/age have passed; those require the owner running the updated canary.


**22 October live native v2-verifier checkpoint:** The owner reran the corrected G1-only canary on S24+ and obtained PASS for both original authenticated Actions ZIP SHA256 values, actual `aapt` app package/version 3 and AndroidTest package, ephemeral one-day Java PKCS12 creation, and genuine Termux `age -p` ciphertext + byte-for-byte `age -d` restore. It reached `apksigner sign` (no tool failure reported there) but then failed closed at `apksigner verify`, which the old script reported only as a generic v2 failure and discarded native verifier diagnostics. **Root cause not established**: the v2 bit might genuinely be false, tool return code nonzero, or verifier output unexpected. The separate fictional-only canary now passes official `--v2-signing-enabled true` when signing (documented Android SDK option) and, on verification failure, displays **ONLY allowed public status/error lines and the exit code**, with fictional file path redaction; it still requires successful exit, exact `Verified using v2 ...: true`, a single cert and the expected `CN=FICTIONAL-ONLY-DELETE` subject. Four independently executed unit regressions **4/4 PASS** against the exact GitHub `fingerprint()` source (FNV32 `42081aa8`), covering successful v2/fictional cert, false v2, missing v2, and nonzero verify error + redacted path. One additional committed source regression checks the literal explicit-v2 CLI arguments (complete 13-case module **not** rerun). The local test harness remains host-only and does **not** establish native v2 signing. `age` can auto-display a disposable recovery passphrase; **do not reuse or share it**, especially not for any real owner key. No APK installed/uploaded. **G3 still NO-GO; E remains draft.**

**Validation as of preparation:** script Git blob `0de868b86f021aa05a09a91689ab0875cbefaa5d` byte-identical to locally syntax-checked source; six new independent host-side fictional metadata/ZIP tests **6/6 PASS**, including an original-download byte-swap rejection before any `aapt` call, ZIP path-traversal rejection and positive safe selective extraction. **No actual Termux or phone operation performed**; host mocked GitHub download and APK parsing in offline tests, with no new Android APK compiled. Real S24+ execution is owner-dependent and still **BLOCKED**. Do not change or run the real owner signing shell to perform this smoke test; this separate canary is deliberately limited to the **old G1 app/test pair**.


## Simplified signing decision after real S24+ fictional canary (22 October 2026)

**Stop repeating the fictional-key canary.** Lukas's native Termux already authenticated both original GitHub Actions archives, inspected both actual APK manifests, created a disposable one-day PKCS12 key, and encrypted/decrypted that key successfully with official age. Official Android SDK apksigner also cryptographically verified the APK using **v3**, with exit status 0, while reporting **v2=false**. A later fictional-only v2 signing run passed v2 verification and then rejected valid certificate metadata because Termux ships **apksigner 37**, whose certificate label can be `V2 Signer:` or `V3.0 Signer:` rather than the old `Signer #1`. This was a **tool-output parsing mismatch**, not evidence of unsafe signing. The real Android app has minSdk **29**, which supports both APK signature v2 and v3.

**Updated E draft source, awaiting independent security review:** The separate fictional canary and `p0_termux_owner_sign.sh` now accept verified Android 29+ **v2 OR v3** while still requiring successful APK verification, parse either old or new signer certificate labels, reject missing, unknown or **conflicting** signer SHA-256 identities, and require one consistent certificate across APKs. The original pre-signing GitHub run/code/ZIP authentication, manifest checks, private-home and symlink safety, encrypted-backup recovery and original owner signing key privacy are unchanged. A's frozen workflow uses Android build-tools 36 for its own independent cloud re-verification; coordinate any cloud-side v37 output change with Lane A rather than silently loosening A's checks.

**No additional Lukas action requested for fictional smoke tests.** The remaining owner decisions, only when ready, are whether to install a temporary debug RC for direct UI use **without update/data-retention promises**, or to approve the separate permanent-cert signing and genuine v3→v4 same-cert cloud emulator acceptance later. The actual owner key, independently recoverable off-phone encrypted backup, and true **version 3→4 People data preservation** are still **not complete**. E #397 remains DRAFT; this simplification does not authorize owner key creation, APK installation/upload, paid CI or G3 PASS. The historical Phase-2 paste block above is retained for provenance/reference, not a request to execute it again.
