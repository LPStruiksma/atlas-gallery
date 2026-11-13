# ATLAS Native P0 — stable signing and two distinct installation choices

**6 November 2026: versionCode-5 preview candidate is Android-tested; S24+ release remains NO-GO.** This is an evidence checklist, not phone or keystore authorization. Read [coordination issue #83](https://github.com/LPStruiksma/atlas-native/issues/83).

> **Update 9 November 2026 (#497):** Lukas chose GitHub signing for phone updates. After a one-time setup, every merge to `main` that touches the app becomes a signed release that the S24+ installs via Obtainium; see [RELEASE_PHONE_UPDATES.md](RELEASE_PHONE_UPDATES.md). The Mac pastes below stay as the fallback. The Mac release paste follows the same nine-digit UTC versionCode numbering and newest-tag floor as GitHub releases.

## Lukas's one paste (macOS, M1 gate, #488)

Approved in [#402](https://github.com/LPStruiksma/atlas-native/issues/402#issuecomment-6713758767): a permanent offline key on Lukas's own Mac plus a disposable-emulator A→B proof. `scripts/owner_mac_m1_proof.sh` wraps the proof below so that the only things Lukas types are his key password (and a backup folder). Strategy fills in `<COMMIT>` (the pinned candidate, full 40-character SHA) and posts the line; paste it as **one line** into Terminal:

~~~bash
xcode-select -p >/dev/null 2>&1 || { xcode-select --install; echo "Finish Apple's installer, then paste this line again."; false; } && { [ -d ~/ATLAS-M1/bootstrap/.git ] || git clone -q https://github.com/LPStruiksma/atlas-native.git ~/ATLAS-M1/bootstrap; } && git -C ~/ATLAS-M1/bootstrap fetch -q origin && git -C ~/ATLAS-M1/bootstrap checkout -q --detach <COMMIT> && bash ~/ATLAS-M1/bootstrap/scripts/owner_mac_m1_proof.sh <COMMIT>
~~~

What it does, in order (macOS bash 3.2, no Homebrew, no sudo):

1. Checks macOS and the chip (Apple Silicon uses arm64 tools and an `arm64-v8a` emulator image; Intel uses x86_64). Tools go to `~/ATLAS-M1/`, the key to `~/ATLAS-signing/` (mode 700), never inside a checkout.
2. Downloads Temurin JDK 17, Gradle 8.13 and Android command-line tools. The command-line tools zip is pinned by sha256 in the script; the JDK and Gradle must match the checksum their vendor publishes next to the file (the cloud seat that wrote the script could not reach Adoptium or Gradle to pin them; a non-empty pin in the script is also enforced). Any mismatch deletes the file and stops. `sdkmanager` then installs platform-tools, build-tools 36.0.0, android-36, the emulator and the API 35 Google APIs image (it checks its own packages).
3. Clones the pinned candidate into a new `~/ATLAS-M1/source-…` folder and refuses unless `HEAD` equals it and the checkout is clean.
4. Creates a fresh AVD, boots it headless and finds its `emulator-NNNN` serial.
5. If `~/ATLAS-signing/atlas-permanent.p12` is missing, runs `keytool -genkeypair` (PKCS12, RSA 4096, alias `atlas-native-release`); keytool asks for the password on the keyboard. It then asks for a backup folder (a USB stick under `/Volumes/` or, after typing YES, another owner location), copies the key there, compares bytes and opens the copy with `keytool -list`. Without a verified backup it stops before any signing.
6. Runs `p0_owner_local_signer_emulator_proof.sh` with that checkout, key, alias, serial and a new `~/ATLAS-M1/evidence-<time>` folder. For this PKCS12 key, type the same password at both prompts.
7. Stops the emulator and deletes the AVD (also on failure).
8. Prints only `public-evidence.txt`, the public certificate SHA-256 and the tool checksums, between two lines, to paste back into the thread.

A re-run reuses downloaded tools, the key and its recorded backup check, and makes a fresh AVD, checkout and evidence folder. The script never reads, prints or logs a password; keytool and the proof prompt on the terminal themselves. If `git clone` asks for a GitHub login (the repository is private), sign in with your GitHub account or a token. Tests: `python3 scripts/test_owner_mac_m1_proof.py` (mocked tools, no network, no real key) — **not** evidence that it works on a real Mac; only Lukas's run is.

## Phone build (macOS, #490)

After the M1 paste has passed, `scripts/owner_mac_release.sh` builds the **release** APK of a pinned commit, signed with the same key, ready to copy to the S24+. Strategy fills in `<COMMIT>` and posts the line; paste it as **one line** into Terminal:

~~~bash
{ [ -d ~/ATLAS-M1/bootstrap/.git ] || git clone -q https://github.com/LPStruiksma/atlas-native.git ~/ATLAS-M1/bootstrap; } && git -C ~/ATLAS-M1/bootstrap fetch -q origin && git -C ~/ATLAS-M1/bootstrap checkout -q --detach <COMMIT> && bash ~/ATLAS-M1/bootstrap/scripts/owner_mac_release.sh <COMMIT>
~~~

What it does, in order (macOS bash 3.2, no Homebrew, no sudo):

1. Refuses unless `~/ATLAS-signing/atlas-permanent.p12` exists and `backup-verified.txt` records that exact key (it never creates a key). Reuses Java 17, Gradle 8.13 and the Android SDK in `~/ATLAS-M1/`; anything missing is downloaded with the same checksum rules as the M1 paste (the x86_64 JDK and Gradle are now pinned to the checksums Lukas's G3 run recorded), and `sdkmanager` adds only build-tools 36.0.0 and android-36.
2. Clones the pinned commit into a new `~/ATLAS-M1/release-…` folder and refuses unless `HEAD` equals it and the checkout is clean.
3. In a private copy outside Git (never committed), sets `versionCode` to UTC `yyMMddHH` × 10 + minute ÷ 6 (for example `261109153` at 15:18), raised above the newest `v0.3.<code>` release tag and `versionName` to `0.3.<versionCode>`. It refuses unless the code is above 261109153 (the GitHub-signed S24+ baseline) and above the source's own value. The clone fetches release tags and refuses to guess if it cannot read them; successive releases must increase the code.
4. Runs `gradle :app:assembleRelease` with no signing configuration, so Gradle (third-party build code) never sees the key or a password, and has exited before the password is asked for. The full Gradle log stays in the release folder; on failure the last 60 lines are shown.
5. `zipalign -p`, then asks **once** for the key password (PKCS12: one password unlocks the keystore and the key; three tries). `apksigner sign` gets it only through its environment in a subshell, never on a command line or in a log. `apksigner verify --print-certs` must show exactly one signer, and it must be `5f15596090e3cfa339c2fd73a2039a23ecca9d02ae4e3506e22dabe976ce170b` (the M1 key); otherwise the APK is deleted.
6. `aapt dump badging` must show package `nl.atlas.gallery`, the chosen versionCode and versionName, and no `application-debuggable`.
7. Copies the APK to `~/Desktop/ATLAS-0.3.<versionCode>.apk`, removes the Gradle intermediates, and prints a public block (commit, versionCode, versionName, APK sha256, signer sha256) to paste back into the thread.

It never runs adb, an emulator or an install. Installing on the S24+ is a separate owner step and a separate decision. Tests: `python3 scripts/test_owner_mac_release.py` (mocked tools, no network, no real key) — **not** evidence that it works on a real Mac.

## P0 execution: owner-local same-signer proof (Lane E)

**No GitHub signing secrets are needed.** On a trusted workstation with Java 17, Gradle 8.13, Android SDK build-tools 36.0.0, platform-tools, and a disposable API 35 emulator, use a reviewed, pinned clean source checkout and an **already securely owner-created and backed-up keystore** outside the checkout.

**Key security boundary (updated):** Both Gradle preview APK builds finish **before the owner password is even requested**. The script then invokes Android SDK `apksigner sign` in separate short-lived subprocesses and checks the certificate on both independently produced app/test APKs; no permanent credential is intentionally exported to Gradle or GitHub. Re-signing replaces a runner/debug signature. This is *detached post-build signing* but **not air-gapped**: do not run unreviewed Gradle scripts or tools on the owner's trusted signing workstation, or assume that a compromised host protects key material. Offline ownership and backups remain mandatory.

~~~bash
bash scripts/p0_owner_local_signer_emulator_proof.sh \
  /absolute/path/to/clean-reviewed-atlas-native \
  /external-owner-held/atlas-permanent.jks \
  approved-key-alias emulator-5554 \
  /external-private/new-atlas-p0-evidence-directory
~~~

The script refuses a non-emulator ADB serial or existing ATLAS installation; archives the same pinned source commit twice; increments B's versionCode; independently compiles both app/test APK sets with ephemeral **preview** signing; **then prompts** for passwords in the local terminal and detached-signs all four APKs with the owner key using Android `apksigner`. It checks package, version, verified certificate fingerprints and whole-file hashes, and performs a real emulator A→B `adb install -r` with fictional People **and application SharedPreferences** seed/verify instrumentation. A separate synthetic preferences XML is also verified byte-for-byte. Public-only evidence stays in the chosen private output directory.

**Corrected settings semantics and execution gate (21 October):** Lane A's original frozen test (blob `bac247affa248bc9fee110f56d01ff0b7910da2d`) asserted only `atlas-ci-upgrade-settings/synthetic-selected-collection`: **test-only persistence, not existing ATLAS application state**. Lane A's updated `PeopleUpgradeTest.kt` (blob `671ea5880fd13483b43a4d56ead2a415316738fb`) additionally seeds and verifies a fictional `sha256` digest in the **actual production preference namespace** `atlas_v4_live_sync`; this is the field read by `GalleryRepository.kt` for linked V4 CSV synchronization. It intentionally does **not** seed a fictional `uri`, avoiding false SAF document access. Lane E independently source-checked the updated RC and all production-preference preflight conditions pass. The revised proof driver **fails before Gradle or a password prompt** if the actual two-phase production preference assertion is missing.

On a real successful emulator A→B run, the script reports distinct `test_only_preference_instrumentation=PASS` and `production_v4_sync_digest_preference_storage_instrumentation=PASS`; `production_v4_live_link_ui_and_saf_grants=NOT_TESTED` remains explicit. **A successful production digest round-trip proves existing app-preference persistence, not that the V4 link UI or SAF URI permission has been exercised.** Actual Android execution of this signed A→B pair remains pending.

The owner-local proof records whole-file SHA-256 for both app APKs and both instrumentation APKs, the public signer certificate SHA-256, package, increasing versionCodes, pinned source commit, plus **private fictional instrumentation logs** `instrumentation-seed.txt` and `instrumentation-verify.txt` and their SHA-256 digests. These logs are evidence only if the real Android emulator ran successfully and may be published only after privacy review. The driver and its five synthetic mocked scenarios have passed independent source-equivalent local tests, **not** real Android compilation, cryptographic signing or a device upgrade. No additional GitHub Actions budget was consumed by Lane E; any native CI run is coordinated by Lane A under the shared cumulative $1 approval.

### One-time owner custody (not executed by Lane E)

On the **owner's trusted machine**, if no approved retained key already exists, generate the key with an interactive keytool prompt rather than placing passwords on a command line:

~~~bash
umask 077
keytool -genkeypair -keystore /secure/off-repo/atlas-permanent.p12 \
  -storetype PKCS12 -alias atlas-native-release -keyalg RSA \
  -keysize 4096 -validity 12000 \
  -dname "CN=ATLAS Native local owner signing"
~~~

Keep the original and an independently protected, encrypted backup in two separate owner-controlled locations; verify the backup can be read with keytool locally. Record the **public certificate SHA-256 fingerprint** and initial package/version/artifact hash in a separate release manifest. Losing the private key breaks future upgrades; publishing it allows impersonation. Do **not** paste keystore passwords/Base64 into GitHub, prompts, comments, artifacts or this chat. The command is guidance only: key generation and backup are genuinely **owner-dependent**, not undertaken here.

**Owner dependencies:** Keeper/backup of the irreversible long-lived signing identity and approval of its use on a trusted local machine; any approved CI expenditure; final separate permission for the **specific phone uninstall/install**. Same package nl.atlas.gallery; old native v0.2 app-private data is expendable only after exact APK and signer proof, and future higher-version in-place upgrades must retain newly created People/settings. The corrected PR-preview workflow does not need permanent signing credentials; do not introduce them into PR-capable workflows.

## PR-preview signing secret isolation: corrected on main; owner key remains offline

The **former** `.github/workflows/build-apk.yml` blob `15a9beb7ecdd12f6bad906523ae2ad06fd9a2b19` combined PR-controlled Gradle with seven permanent-secret references. **On 21 October 2026 Lane A fixed this**: main commit `30f92766facbd1711def476a87b0fa8b3092b0dd` contains preview-workflow blob `96cffe98611646836e67ac2ed08377a4bcef6872`, independently inspected by Lane E: PR-head builds run with ephemeral debug signing and **zero GitHub signing-secret references**. This closes that specific potential exposure path, not every conceivable GitHub trust boundary. No leak was ever evidenced.

**Do not add permanent signing credentials to GitHub.** The elected P0 pathway is owner-custodied offline/detached signing, with no permanent signing environment in PR workflows. Do not mistake the corrected preview workflow for evidence of a permanent signer, owner key custody, Android cross-build upgrade or S24+ release GO.

Offline, without keys, phone, network or build:
~~~sh
python3 scripts/audit_stable_signing_workflow.py .github/workflows/build-apk.yml
python3 scripts/test_audit_stable_signing_workflow.py
python3 scripts/test_p0_owner_local_signer_emulator_proof.py
~~~

Result `signing_secret_source_isolation_pass=false` / exit 1 means **NO-GO for configuring secrets**. Exit 2 means malformed/unreadable source. Exit 0 says only that this narrow static isolation check passed; it **does not** establish protected GitHub environments, branch protection, signer continuity, keystore backup, or phone compatibility. This conservative audit requires **no secret reference in a PR-capable workflow**; a step-local `if` is not treated as an adequate separation boundary. The script always returns `permanent_signing_ready=false` and `release_go=false`.

**Lane A owns workflow changes.** The desired architecture separates disposable, secrets-free PR preview builds from *trusted, vetted main-only* signing. Protected environments/reviewer gates are acceptable only if demonstrably supported and reviewed for this repository. If not, prefer **owner-controlled offline signing** with the permanent keystore never uploaded to GitHub. Either approach requires explicit owner approval before any credentials are created/configured. Never place keystore bytes, passwords, aliases or private signing state in PRs, logs, artifacts, caches or chat. This checker is intentionally conservative rather than a complete YAML security proof.

## Independent P0 audit hardening — event and YAML alias ambiguity (21 October)

A source-level red-team review found that the original offline auditor **falsely passed valid untrusted workflow forms** while signing-secret references were present:

- `on: \"pull_request\":` or `'pull_request':` under `on` (the first regex recognized only an unquoted `pull_request:` key).
- `merge_group:` or other non-`push` GitHub Actions event keys not included in the original event-denylist.
- `push:` configured with both `branches: [main]` and `tags: ['v*']` (tag-triggered executions may target commits outside the vetted main branch).
- A **valid YAML anchor / alias event key**: an innocuous-looking `event_label: &pr pull_request` together with `on: *pr:` can evaluate to a PR event even though the workflow text does not contain the literal `pull_request:` mapping key. A separate `push:/branches: [main]` mapping made the old checker report a false positive. Reproduced with a local YAML BaseLoader and the old auditor; no secrets or repository workflow were exercised.

**Correction:** when `secrets.` or `secrets[...]` is present, the checker now requires a **single explicit top-level `on:` mapping, one direct `push:` child, and one directly nested `branches: [main]` field**. Any alternate event, quoted variant of extra event, YAML anchor/alias, tag/tag-ignore/branches-ignore selector, duplicate `on` or unsupported syntax fails closed. This check intentionally rejects advanced main-only YAML idioms instead of guessing whether they are safe. Secret-free PR previews remain permitted. The public output still always says `permanent_signing_ready=false`, `release_go=false`.

The **complete updated exact GitHub-source Python suite** was reconstructed locally and passed on Python 3.13.5, including the previously undetected YAML alias attack, quoted triggers, tag/merge-group variants, unknown events, invalid arguments and private-path protection. A further real YAML-decoder test exposed **duplicate branch-selector false passes**: `branches: [main]` followed by `branches: [dev]` (or a quoted `"branches": [dev]`) was silently accepted despite commonly decoding to **dev** due to last-wins semantics. The positive push-only check now allows only a single direct `branches: [main]` plus optional `paths:`/`paths-ignore:` selectors, rejecting all duplicate/unknown push keys and escaped YAML hex/Unicode control keys. The **entire updated synthetic suite and `py_compile` reran SUCCESS** on byte-exact reconstructed files under Python 3.13.5; exact source/test Git blobs are `3fc7da4629c294b9eefa01127c39a72992e3599b` / `bbe40c475a667bb441e8e88852cdec8c0e143f0b`. This is *source-text heuristic evidence*, not formal GitHub workflow execution/permissions proof; the stable signing workflow must still be separately reviewed.

### Bare secrets-context reference fail-open closed (Lane E, 21 October)

A further independent exact-code probe showed a **real source-auditor false pass** for secret-bearing PR workflows with GitHub expressions such as `toJSON(secrets)` or a bare `secrets` value. The old detection regex recognized only `secrets.KEY` and `secrets['KEY']`, and returned `signing_secret_source_isolation_pass=true` on the bare-context cases. A credential context remains sensitive whether accessed through member syntax, brackets or as the whole object. The checker now conservatively recognizes any standalone `secrets` token in uncommented workflow lines, without relying on a particular context-access syntax. The explicit negative tests for `toJSON(secrets)`, bare `secrets`, and `format('{0}', secrets)` all PASS, along with the complete updated synthetic regression suite and Python compilation on Python 3.13.5. Byte-exact tested Git blobs: source `aa3d33ba24fe1138bcaeacde5ae0e7e12e0e7021`, tests `4f525914a7e4d88bb2cfec0911589207b720e9c0`. False-positive rejection of a workflow that merely *mentions* secrets in prose is acceptable in this conservative guard; signers must undergo additional human trust-boundary review anyway.

### Untrusted source code under an apparently main-only signing trigger (21 October)

A separate real false pass remained after event tightening: a workflow with `on: push: branches: [main]` and `secrets.STABLE_SIGNING_KEY` can explicitly run `actions/checkout@v4` with `ref: experimental` or `repository: unreviewed/other`, then execute the downloaded build scripts with signing credentials. The previous source guard returned `signing_secret_source_isolation_pass=true` solely because the **event** was restricted to main, without inspecting **code provenance**. The checker now rejects explicit `ref:` or `repository:` overrides and recognizable `git clone` steps in a secret-bearing workflow, including quoted variants of the YAML keys. Fictional valid YAML regression cases cover unrelated branches, pull refs, another repository and a shell clone; secrets-free previews are unaffected. **Entire updated test suite and Python compilation passed** against exact Git blobs source `cf681925e4b67049a795561efc1a55a54e702a01`, tests `63f1e37a12b6548a69e71d35d7b32faa4373bb9c`.

This static filter remains **deliberately incomplete**: source-controlled Gradle can itself fetch and execute arbitrary code; third-party actions and reusable workflows also need independent trust review. A syntactic pass alone must never authorize storing keys, and `permanent_signing_ready` remains `false` even when `signing_secret_source_isolation_pass` is `true`. Only genuine isolated signing architecture with protected, approved code and credentials can advance the gate.

## Independent durable-signer acceptance

1. Lane A freezes **one immutable latest-main native candidate**, builds it successfully, and completes real Android emulator/Compose/API34–35 acceptance of People, settings, viewer/filmstrip, Back/video, and photo/SAF grants. Signer-only emulator tests do not prove a native candidate green.
2. The owner safeguards an independently retained permanent signing key and a protected backup. Lane E does not create keys or request secrets while the trust boundary is unsafe.
3. Produce **two independently executed signed APK builds** with one permanent retained key. Verify their *public* certificate SHA-256 fingerprints are identical; verify each whole-file APK SHA-256, package `nl.atlas.gallery`, successful APK signature checks and strictly increasing **64-bit Android longVersionCode**. Reusing a temporary debug key inside a single CI runner does not meet this requirement.
4. On a **disposable emulator**, install signed build A, create fictional private People and persisted settings, then genuinely upgrade in place to independently signed build B. Confirm identities/settings retained, app data not reset, and permissions/selected-media behavior understood. The existing [green synthetic mismatched-signer restoration run](https://github.com/LPStruiksma/atlas-native/actions/runs/39509082080) is **not** this independent two-build permanent-key continuity proof.
5. Bind the final tested APK and CI provenance to exact commit, cert and artifact hash. Obtain owner consent for one of the distinct first-phone transitions below. **No APK install or uninstall is authorized by source work.**

## Explicitly separate initial installation routes

| Gate | Preserve installed native v0.2 app-private state | Owner-elected one-time fresh native install |
| --- | --- | --- |
| Existing phone signer/base+splits | **Required:** authenticate actual installed package and prove signed candidate can replace in place | Document package/impact where safe; no claim same-key replacement works if older signer differs |
| Native v0.2 People/settings data | Independently complete *phone-origin* backup, private inventory and isolated restore required | Owner may choose to **discard** only old native v0.2 app-private People/settings; old-data export is **not** an immutable prerequisite |
| Future People/settings persistence | Two independent permanent-key builds + real synthetic in-place A→B preservation **required** | **Same future-upgrade proof required before any fresh installation** |
| Permissions and media | Safely validate persisted SAF/selected-media grants and continuity | Review loss/regrant of app-scoped grants and settings; **shared MediaStore photos and the separate V4 Termux DB are not disposable** |
| Destructive action | Never uninstall or clear app data to bypass a mismatch | Possible **only after new candidate and future-upgrade proof pass, app-private impact is reviewed, and the owner explicitly authorizes losing old native state** |

The owner's earlier statement that old native People work was only ~20 minutes indicates *conditional willingness*, **not** permission to uninstall, wipe data or touch V4 media. Do not present old-native preserve-only requirements as unavoidable for the elected fresh path. Future private data must be durable over ordinary in-place upgrades regardless of which first-phone path is selected.

## Current evidence record (6 November 2026; never invent signer hashes or owner approvals)

[PR #458](https://github.com/LPStruiksma/atlas-native/pull/458) and [PR #459](https://github.com/LPStruiksma/atlas-native/pull/459) are merged. The [#459 exact-head Android/API35 run, attempt 2](https://github.com/LPStruiksma/atlas-native/actions/runs/42183343979) succeeded at `21399a4c2f504e43f64e93c06b4b6575bbd0140b`: 179 instrumented tests, zero failures/errors and six skips. Its Git tree `35c02c4ca5a8adb3743de03cc7d5395cc528d6bf` is identical to merged `main` commit [`0c29dd9`](https://github.com/LPStruiksma/atlas-native/commit/0c29dd9ddeadb8c7d4df44be17b9ee4b972f2ce1). The app declares `versionCode = 5` in `app/build.gradle.kts`. This is **preview-candidate test evidence**, not an owner-signed release artifact.

| Required proof | Status at 6 November 2026 |
| --- | --- |
| VersionCode-5 native preview candidate built and API35-emulator tested | **PASS (preview only)** — [#459 exact-head run, attempt 2](https://github.com/LPStruiksma/atlas-native/actions/runs/42183343979), 179 instrumented tests, 0 failures/errors, 6 skips; not physical-device acceptance |
| Random viewer scrub/release on a synthetic album | **PASS (API35)** — `ViewerInteractionTest.randomAlbumScrubReleaseDoesNotRewindOrReshuffle` |
| Synthetic native People opt-in, review, person-album route | **PASS (API35)** — `PeopleDiscoveryHostTest.optInScanProgressClustersReviewThenRevokeStops` and `PeopleDiscoveryHostTest.nameAGroupThenOpenThePersonAlbumSurvivesRestartAndRevoke`; no owner photos |
| Scale evidence: 150k catalog sort vs face-index publication | **PASS** — `AsyncCatalogSortTest.synthetic150kCatalogCompletesAsyncSortAlbumIndexAndSelection` (150k catalog) and 50k face SQLite publication; **NOT VERIFIED** — `FaceIndexStoreScaleAndroidTest.scale150kManySmallClusters` was skipped (opt-in), so 150k face publication and device-class face throughput remain unproven |
| PR build isolation from permanent signing credentials | **PASS (preview-workflow boundary only)** — corrected secret-free, ephemeral preview signing on `main` (see 21 October source audit above); not stable-signer readiness |
| Permanent owner-held key retained and safely backed up | **NOT VERIFIED** |
| Two distinct, independently signed APK builds with same retained public signer | **NOT VERIFIED** |
| Monotonic full version, public certificate continuity and exact signed artifact hashes | **NOT VERIFIED** — source `versionCode = 5` is not signed-build evidence |
| Stable-signer A→B real emulator upgrade preserving synthetic People/settings | **NOT VERIFIED** |
| Owner chooses preserve-old vs one-time-fresh route | **NOT APPROVED** — both routes remain available under the different conditions above |
| Real S24+ installation, state reset or on-device face throughput | **NOT AUTHORIZED / NOT VERIFIED — NO-GO** |

**Release gate: NO-GO.** Hosted preview CI and synthetic API35 tests do not establish owner custody of a permanent signing identity, two independently signed builds, same-signer A→B upgrade retention, real phone behavior, or specific owner authorization. Neither first-install route is silently selected; the preserve-old and owner-elected fresh-first alternatives above remain intact.

Related technical detail: [stable signing](STABLE_SIGNING.md), [release checklist](RELEASE_GO_NO_GO.md), [owner decision card](S24_OWNER_UPGRADE_CARD.md). No real credentials, phone data, personal media, installations, GitHub secrets or paid CI were used to prepare this document.
