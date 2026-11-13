# ATLAS Native — one-page Galaxy S24+ upgrade decision

**Current decision (18 October 2026): NO-GO for the installed S24+ application.**

This is a decision checklist, **not permission to access the phone or install an APK**. Android synthetic CI is valuable, but it does not establish that a new build is compatible with the *actual installed* package or that private People/settings can be restored. If any required item below is **UNKNOWN**, the decision is **NO-GO**. No uninstall, clear-data, signing-key handling, paid action or private-data extraction without explicit owner approval.

## P0 release-path update (21 October 2026)

**No permanent signing secrets may be added yet.** The current Android PR build workflow references them while checking out PR-controlled Gradle code. This is a **potential key-exposure risk if configured**, not a report that keys exist or leaked. Lane A must isolate secrets-free PR previews from vetted main-only signing, or the owner can use a secure offline signing path. The [P0 signing/fresh-start protocol](P0_SIGNING_AND_FRESH_START.md) and its [offline source check](../scripts/audit_stable_signing_workflow.py) are now the controlling prerequisite for any durable keystore.

**Two different first-phone routes are permissible only after the exact native candidate, permanent signer and A→B synthetic in-place People/settings retention pass:**

- **Preserve native v0.2:** Require the five old-state proofs below, including actual installed signer/splits, complete private People/settings backup and isolated restore, compatible APK replacement, grants and explicit owner consent.
- **Owner-elected one-time fresh native install:** Existing v0.2 People/settings (described as expendable) need not be exported as an immutable blocker. Instead require an exact validated candidate, stable retained signing key proved identical across **two independent builds**, monotonic 64-bit version, successful synthetic real A→B **future** in-place People/settings preservation, device/grant/media impact review and **separate explicit owner approval to discard old native app-private state**. This does not authorize uninstall/data clearing now, and never includes the separate V4 Termux DB or shared MediaStore photos.

The older five-proof table remains the strict **preserve-old-v0.2 pathway**, not a mandatory checklist for someone who deliberately elects a separately safe fresh start. Either route is **NO-GO** until *its* prerequisites and final owner approval are demonstrated.

## The five proofs — all must be established independently

| Proof | Required evidence for GO | Current status |
| --- | --- | --- |
| 1. Actual installed APK | Public certificate/package/version and whole-file SHA-256 independently bound to the **actually installed** `nl.atlas.gallery` package; account for **all** installed split APKs, if any (not a historic download or different CI build) | **UNKNOWN** |
| 2. Exact replacement APK | Independently pinned candidate APK SHA-256 and offline verification that package matches, there is exactly one compatible signing identity, and candidate `versionCode` is strictly higher | **UNKNOWN** |
| 3. Complete private recovery | Independently evidenced complete **phone-origin** People + settings capture, exact observed row/settings counts, two protected independent copies, supported schema and verified **isolated** recovery | **UNKNOWN** |
| 4. Device behavior and grants | Exact candidate passes full Android instrumentation and synthetic upgrade evidence, followed by authorized **isolated** validation of People/settings, SAF/photo access and relevant viewer/Back/video behavior | **PARTIAL: synthetic CI only** |
| 5. Owner decision | Owner reviews the above evidence and deliberately approves a normal **in-place** upgrade with a contingency plan | **NOT APPROVED** |

**Preserve-old decision: 5/5 verified and explicitly approved = candidate for GO. Fresh-start decision: every fresh-start prerequisite in the P0 protocol independently verified plus explicit authorization to discard old native private state. Anything else = NO-GO.** No script in this repository can alone turn that status green.

## Offline read-only commands, only when genuine inputs are already available

**APK signing:** The old input must be independently established as the installed binary; a public hash alone does not authenticate its origin. The following is a **single-APK comparison**, not an inventory or attestation of an installed multi-split package.

```sh
python3 scripts/check_apk_upgrade_signing.py /secure/actually-installed.apk /secure/exact-candidate.apk \
  --apksigner "$ANDROID_HOME/build-tools/36.0.0/apksigner" \
  --aapt "$ANDROID_HOME/build-tools/36.0.0/aapt" \
  --expected-installed-sha256 "INDEPENDENT_64_HEX_SHA256" \
  --expected-candidate-sha256 "INDEPENDENT_64_HEX_SHA256"
```

**Recovery consistency:** These are two *already obtained*, separate, owner-protected **offline directory trees**, **not** paths into a running SQLite app database. Every count below must come from a separate trusted phone-origin capture observation, not a guess or the same unverified tree.

```sh
python3 scripts/check_offline_recovery_bundle.py \
  /secure/private-backup /secure/isolated-restored \
  --expected-rows 123 \
  --expected-settings-files 2 \
  --expected-backup-sha256 "INDEPENDENT_64_HEX_SHA256"
```

**Important:** `123` and `2` are **illustrative placeholders, not known ATLAS counts**. This combined check requires the **exact** confirmed face-assignment count and exact persisted-settings file count (not only a lower bound). It fails closed on unknown private files, sidecars, changed content and insufficient permissions. Passing establishes **consistency of the supplied offline trees only**; it cannot show whether *both* omitted a phone-private file, whether SAF grants remain usable, or whether a truly isolated Android restore worked.

**Check installed-package provenance without touching ATLAS app data.** Android's `pm path nl.atlas.gallery` may report a single `base.apk` **or several installed `package:` entries** (base and configuration/feature splits). A verified copy of only `base.apk` is not evidence that every installed split was accounted for. Independently retain a protected, source-bound inventory of **all** observed package entries and their exact byte hashes/certificates; never publish their private device paths. The existing `check_apk_upgrade_signing.py` compares **one** old APK with **one** candidate; it does **not** attest to split-set completeness, an original APK's on-phone provenance, signing-key rotation lineage or the candidate's platform/ABI requirements. A previous download or unrelated CI APK may serve as an investigative comparison, **not** as the verified installed input. If any part of this provenance or compatibility check cannot be established, record **UNKNOWN → NO-GO**; do not uninstall or guess. The app's private People/settings are not part of APK extraction.

The standalone `verify_native_restore_evidence.py --min-rows` accepts an independently observed *minimum*, but it is **not** a substitute for the exact-count combined gate above.

## Hard STOP situations

- **Preserve-old route:** the actual installed APK's public signing identity is unknown/different or its **split set** cannot be independently established. **Fresh-start route:** old signer unknown is not a proof of future continuity, and may only be bypassed after explicit first-install state-loss consent and a separately verified permanent new signer.
- **Preserve-old route:** no coherent, complete owner-controlled native v0.2 People/settings backup and restore. **Fresh-start route:** no proof that the future new-signer build A→B genuinely preserves invented People/settings. The app has `android:allowBackup=false`; Android/ADB/Shizuku `run-as` probes are not backups.
- A GitHub emulator workflow is red, missing KVM, testing a different commit/APK, or still running.
- Any mandatory signer/People/settings/future-upgrade check for the **chosen route** is incomplete, or gallery/SAF permission impacts remain unreviewed.
- An uninstall or data clear proposed **without an independently accepted one-time-fresh-start route and separate explicit owner approval**, or tapping **Import once** on a populated People database. PR #150 added explicit consent, **not lossless merge**; successful import still replaces existing People assignments.

**If installation fails:** Stop and preserve the existing app/data. Do **not** uninstall. A lower `versionCode` build is not automatically a viable rollback; coordinate a compatible, verified higher-version corrective build.

## Evidence record (no personal records in chat or GitHub)

For each release attempt, retain privately: exact tested Git commit, trusted Actions run URL and result, the observed installed base/split APK count, candidate and independently proven installed APK SHA-256 and public signing certificate digests, actual known People/settings counts, independently protected backup receipt, isolation/restore and SAF-grant observations, and explicit owner approval or rejection. Never upload actual SQLite, CSV, images, signing credentials, passwords, source paths or People names as release evidence.

**Detailed technical procedures:** [release runbook](RELEASE_GO_NO_GO.md), [signing](STABLE_SIGNING.md), [private backup validation](NATIVE_PEOPLE_BACKUP_VALIDATION.md), [blocked emulator KVM gate and approved alternatives](EMULATOR_KVM_GATE.md), and [coordination issue #83](https://github.com/LPStruiksma/atlas-native/issues/83).
