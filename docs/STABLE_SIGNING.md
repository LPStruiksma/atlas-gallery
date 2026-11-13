# ATLAS — stable development signing and upgrade safety

**Owner-first decision card:** [Galaxy S24+ release GO/NO-GO — five independently evidenced proofs](S24_OWNER_UPGRADE_CARD.md). This short card governs the actual-device decision; the remainder of this document is technical supporting detail.

This concerns pre-release debug APKs for package nl.atlas.gallery, not production/Play signing.

> **P0 safety supersession, 21 October 2026:** The four GitHub Actions secrets recipe below is **HISTORICAL and MUST NOT be executed** with the currently merged .github/workflows/build-apk.yml, which combines untrusted same-repo PR Gradle execution with signing-secret references. Prefer an owner-held local key isolated from GitHub; see [P0 signing and fresh start](P0_SIGNING_AND_FRESH_START.md). The old "never uninstall" statement below only applies when old native v0.2 private data must be kept, not the separately authorized one-time same-package clean-install route.
  
## The issue
Android upgrades require a compatible signing identity. Temporary GitHub runner debug keys can differ between builds. Installing a different-signer APK fails; uninstalling can erase private People imports and settings. **Never uninstall as a signing workaround.** ATLAS's Android manifest also disables Android auto-backup.

## How the build now works
Four GitHub Actions secrets, entered only after explicit approval from the project owner, activate one durable debug signing identity:
- ATLAS_STABLE_DEBUG_KEYSTORE_B64 — Base64 contents of the secure keystore
- ATLAS_STABLE_DEBUG_STORE_PASSWORD — keystore password
- ATLAS_STABLE_DEBUG_KEY_ALIAS — key alias
- ATLAS_STABLE_DEBUG_KEY_PASSWORD — key password

The workflow decodes the keystore to a temporary runner directory with restricted permissions, never logs or uploads private key material, and publishes only the public signer SHA-256 certificate fingerprint. If no secrets are supplied, the existing runner debug signing continues and resulting APKs are NOT promised to upgrade earlier installs. Partial secret configuration fails immediately without leaking values.

**No signing key is being generated, accessed or uploaded in this PR.** The one-time key creation and storage is a later owner-controlled action. Never paste passwords or keystore Base64 into chat or commit either to a repository. Store an independently encrypted offline backup.

## Existing phone requires a separate compatibility check
Even a newly stable key is probably different from the key used for the APK already installed on the S24+. Do not assume Android will let that APK replace the old one. Confirm the installed APK's public certificate first. On a computer with Android SDK platform-tools/build-tools:

    adb shell pm path nl.atlas.gallery
    # If the device permits pulling its APK, substitute the actual pm path:
    adb pull '/data/app/.../base.apk' ./installed-atlas.apk
    python3 scripts/check_apk_upgrade_signing.py installed-atlas.apk candidate-atlas.apk --apksigner "$ANDROID_HOME/build-tools/36.0.0/apksigner" --aapt "$ANDROID_HOME/build-tools/36.0.0/aapt"

If adb pull is blocked, use a supported extraction process or the original downloaded installed APK. Do not uninstall the installed app just to obtain its certificate. The offline preflight now **blocks** a signer mismatch, wrong package ID, unrecognized multiple signers, or versionCode downgrade; it requires both `apksigner` and `aapt`. The signer comparison deliberately fails closed on valid signing-key rotations because no rotation lineage is verified. Passing this preflight is still not a guarantee of installation or private-data preservation.

## Completion gates for issue #2
1. Owner creates and safeguards one permanent development keystore outside GitHub source.
2. Owner adds all four secrets to GitHub Actions.
3. Two independent CI builds show identical public signer SHA-256 fingerprints.
4. **Implemented CI gate:** on every native PR, the emulator builds the base commit's v0.2 APK and the PR's v0.3 candidate using one runner-local debug key. It installs the base APK, imports a synthetic People CSV via instrumentation, executes a genuine `adb install -r`, and verifies the unchanged private People SQLite row afterwards. This proves same-runner upgrade and preservation, **not** durable cross-run signing. The test app contains no personal data or key material.
5. **Still outstanding:** once owner-configured stable secrets exist, compare cert fingerprints from two *independent* CI runs; also check against the APK already on the S24+.
6. Never replace the existing phone installation unless signing, package, version, tested backup and preservation checks pass. In a mismatch, plan an explicit data-preserving migration, not uninstall.

Pure local test:

    python3 scripts/test_apk_upgrade_signing.py

Source files and test fixture media never contain personal photo, face-label or keystore data.


## Lane E 16 October 2026: strict release-check wrapper

Use the one-page [Galaxy S24+ go/no-go card](RELEASE_GO_NO_GO.md) before any real upgrade. The preflight now **blocks identical versionCodes by default**, in addition to downgrades, unknown/multiple signers, wrong packages and failed signature verification. A deliberate same-version **test** reinstall requires --allow-same-version and still does not authorize release. Missing or stalled Android SDK inspector processes fail closed with a bounded timeout and without exposing tool error paths.

A public signing fingerprint is not a keystore, and a successful same-runner emulator replacement does not establish cross-runner signer persistence or match to the original APK already installed on the phone. The currently installed S24+ app stays untouched; no stable signing credentials have been accessed for this work. A false install compatibility signal is more expensive than postponing the upgrade.


### Exact-APK artifact binding (Lane E, 16 October 2026)

\`check_apk_upgrade_signing.py\` now computes SHA-256 over **each APK's whole bytes**, once before and once after independent \`apksigner\` and \`aapt\` inspection. A file replaced or modified during inspection is rejected rather than combining one file's signer with another file's manifest. Symlinked APK paths and old/new paths resolving to the same file are rejected. The printed \`apk_sha256=\` identifiers bind the verdict to the inspected bytes, **not** merely to the public signing certificate. Tool/OS diagnostics no longer echo untrusted absolute paths or command stderr.

Optional \`--expected-installed-sha256\` and \`--expected-candidate-sha256\` accept independently recorded **64-hex-character SHA-256** values, e.g. the candidate checksum recorded with a pinned CI build artifact and the old checksum separately recorded at capture. A mismatched, invalid or substituted value forces exit code 2. These arguments are optional for exploratory checks but recommended for owner-controlled provenance. A SHA-256 hash copied from the *same* untrusted file is not independent evidence that it really came from the installed S24+ app. Inspecting an arbitrary downloaded debug APK remains insufficient to certify phone signing compatibility. The TOCTOU re-hash is not a cryptographic guarantee against a privileged adversary capable of simultaneous file substitution; keep APKs in a private immutable/read-only staging directory for an actual release review.

Pure synthetic test (no APK signing keys or device): \`python3 scripts/test_apk_upgrade_signing.py\`.


### Offline synthetic-recovery CI diagnostics (Lane E)

When the dedicated signer-mismatch emulator workflow is red, distinguish **failure of the People restoration operation** from **a different failure in its subsequent full Compose UI regression suite**; neither type of failure permits a merge. From an already obtained CI **job text log** stored locally, use:

\`\`\`sh
python3 scripts/audit_recovery_ci_log.py /path/to/synthetic-ci-job.log
\`\`\`

The fixed-schema JSON reports whether it observed the four synthetic milestones (same-signer People preservation, protected recovery CSV creation, deliberate mismatched-signer rejection, and restored invented People assignments), plus separately validated screenshot evidence, full-suite failure markers, and bounded diagnostic categories (for example Android view thread-affinity). Raw log lines, labels, paths, signing material or environment details are **never echoed**. The script reads only a nonlinked existing local text file up to 16 MiB; it never accesses GitHub, the phone or the Android SDK.

**Security/evidence limit:** Log markers are untrusted text and are **not authenticated evidence of CI success**, since an arbitrary log can be forged. The script deliberately always reports \`ci_job_attested=false\` and \`real_phone_upgrade_go=false\`. Only GitHub's exact-head trusted Actions run conclusion can establish the actual CI result, and even all-green synthetic tests cannot authorize an S24+ upgrade. A complete synthetic People recovery with a failed unrelated UI suite remains RED and **must not be waived**. Offline regression is \`python3 scripts/test_audit_recovery_ci_log.py\`, also invoked by the signing-safety workflow via its existing test entrypoint.


### Schema-forward-compatibility release gate (Lane E)

An offline People/settings backup comparison is **not lossless** if future ATLAS versions add more People fields, tables, triggers or schema versions that the existing four-column CSV recovery path does not preserve. \`scripts/verify_native_restore_evidence.py\` now refuses any unrecognized \`assignments\` columns, additional user tables/triggers/views and SQLite \`user_version\` beyond the current version 1. It also compares the complete recognized SQLite schema (including indexes) and Android's optional \`android_metadata\` content between backup and restored copies. Existing supported current-version snapshots (including synthetically generated \`user_version=0\` test fixtures and real \`user_version=1\`) remain eligible for **offline parity only**.

This strictness is intentional: when the native People schema evolves, reviewers must explicitly update the migration/export contract **and synthetic restore proof**, rather than silently discarding new data while reporting parity. It does not independently prove capture freshness, all private settings, SAF permissions or installed phone upgradability. Tests run with invented data through \`scripts/test_verify_native_restore_evidence.py\` and the existing lightweight signing workflow; no private SQLite is read in CI.


### Owner recovery danger: CSV Import Once replaces People membership

APK certificate checks are a **separate** preflight from the native manual People import. In the current Android \`PeopleRepository.replaceFromV4Export\`, a successful CSV import transaction **deletes all current assignment rows** before adding CSV rows. The People screen leaves “Import once” available when labels already exist. Hence the existing isolated-emulator signer-mismatch proof against an empty installation must not be interpreted as evidence that partial import is safe on a populated real phone. Maintain complete independently protected target/source backups, verify source completeness, and require a non-destructive import preview and owner approval before any real migration. **Do not tap Import once on the running S24+ as a casual restoration or synchronization step.** Lane A controls any UI changes. The real-device release decision remains NO-GO.

### Native import approval vs populated-target recovery (Lane E, 17 October)

The native app's explicit one-use V4 CSV approval and synchronized People update path merged in [PR #150](https://github.com/LPStruiksma/atlas-native/pull/150), with full Android CI [#38976136701](https://github.com/LPStruiksma/atlas-native/actions/runs/38976136701) green. Choosing a CSV alone is now inert; cancel prevents the import. **Approval is still replacement, not merge:** after confirmation, `replaceFromV4Export` deletes all native People assignments before inserting the CSV rows. A complete protected source and target recovery plan remains required, and manual import into a populated S24+ is not release-approved. The green emulator used invented data and does not establish the phone's signer or preserved SAF grants.

