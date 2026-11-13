# ATLAS Native → AutoDev · proposed ownership handoff (3 November 2026)

**Status: PREPARED / NOT ACTIVATED.** Owner has requested readiness assessment for transferring ATLAS work ownership to AutoDev. This document is a source-linked, reversible handoff brief; it is **not** an AutoDev tenant configuration, worker dispatch, permission grant, merge authorization, CI run, model budget, or transfer of physical S24+ control. Do not interpret an owner inquiry as a waiver of AutoDev's constitutional gates.

## Product charter (unchanged)

Build an original Kotlin/Compose near-complete **Samsung Gallery-class daily-driver**: visual design, navigation, gestures, photo/video timeline, mosaic/original-proportion pinch, Albums, selection, Trash/restore, viewer/video/details, Search/People/Places, editing/share, Stories/Memories, stability and 150k-scale performance. The documented Samsung Gallery 15.9.00.45 research report and 187-finding triage inform behavior, **not** license to reuse proprietary code/assets/private Samsung APIs. Differentiate beyond this parity with private native People intelligence, Photo DNA, explainable Declutter and Life Graph. See [controlling product plan](ATLAS_SAMSUNG_PARITY_PRODUCT_PROGRAM_20261103.md) and [live coordination issue #83](https://github.com/LPStruiksma/atlas-native/issues/83).

## Immutable anchors and source truth

- Installed and reportedly working on owner S24+: package `nl.atlas.gallery`, owner-signed **versionCode 4** based on production RC2 `1f078d59f0ab59c6cc87256e176ad05b9cc752e4`. **Do not touch installed app, APKs, signer or media.**
- **`main=f83689209bcfc700020685a653ae03db3970c3c8` is stale** relative to installed RC2. No raw "clone main, start editing" approach.
- Future-code source baseline `atlas/postinstall-next-update-base-20261103` at `a55b716ce3418c8203542c8230fc9d95e587df9f` = exact production RC2 + versionCode=5; **source only, not yet built, tested or signed**.
- Controlling parity programme is on docs-only `atlas/coordinator-samsung-parity-program-20261103`; not an installable Android branch.
- Current implementation branches (refresh refs and evidence before integration):

| Lane | Isolated branch | Last reviewed SHA prefix | Integration notes |
| --- | --- | --- | --- |
| G · grid/zoom | `atlas/lane-g-unified-zoom-20261103` | `5ce96d6` | Host MainActivity callbacks and UI gesture acceptance not wired |
| M · metadata | `atlas/lane-m-samsung-camera-date-parity-20261103` | `6353132` | Samsung filename date/EXIF date path; standalone host checks only |
| V · viewer/share | `atlas/lane-v-viewer-share-action-20261103` | `99cf31d` | Sharesheet/details; modifies GalleryViewer.kt |
| V · #400 Random scrub | `atlas/lane-v-random-filmstrip-release-400-20261103` | `6f90a43` | **P1** tentative recenter-race fix; conflicts in GalleryViewer.kt; real Android test **not run** |
| D · albums | `atlas/lane-d-albums-quickfind-20261103` | `a9a7c34` | Quick-find + not-yet-wired batch toolbar |
| U · search/People UX | `atlas/lane-u-search-date-facets-20261103` | `fea9ffa` | Dates, search reset, reviewed People sorting |
| P · faces | `atlas/lane-p-native-face-engine-port-20261103` | `2cece49` | Detection contracts; no runtime ML Kit/index integration |
| C · Photo DNA | `atlas/lane-c-curation-review-contract-20261103` | `eb9f0f2` | Read-only keeper/review, never delete originals |
| X · persistence | `atlas/lane-x-postinstall-persistence-parity-20261103` | `0d57c36` | People DB v2/migration/backup and index; Android migrations **not run** |
| S · Stories | `atlas/lane-s-stories-offline-deck-20261103` | `9b21a56` | Offline stories UI/planner; no host nav/persistence |

All are isolated work; remote source code ≠ integrated version 5 ≠ owner-phone tested. See current head summaries in issue #83, refresh in every AutoDev cycle. Some original nine ChatGPT lane chats may continue; AutoDev must not assume they are inactive merely because a branch is idle.

## AutoDev readiness (live-inspected)

Current AutoDev: `LPStruiksma/autodev` `main@0c66dcf4fd394eb3689b6c66b8f006490bd0535a`; Relay: `LPStruiksma/autodev-relay` `main@6b62b27917908a710e813eea9d23669ef4a30a45`. [AutoDev NOW #130](https://github.com/LPStruiksma/autodev/issues/130), [tenant onboarding procedure](https://github.com/LPStruiksma/autodev/blob/main/docs/strategy/tenant-onboarding.md), [read-only tenant readiness CLI](https://github.com/LPStruiksma/autodev/blob/main/bin/autodev-tenant-readiness.mjs).

**Not ready to assert delegated, running, governed AutoDev execution for ATLAS:** neither ATLAS `main` nor future-update source tree has `.autodev/project.json` or `.autodev/autodev.lock.json`; no ATLAS tenant config or lead worker binding in AutoDev main; whether ATLAS is saved to the actual Claude AutoDev project is **unverified**; no ATLAS-specific Android compiler/API35 runner or exact-head CI budget/protocol validated under AutoDev. Current generic read-only consumer lead is more mature than arbitrary-tenant candidate execution (GeoAsset path is the evidenced sample). Do not confuse the external *Development Relay* with the full *AutoDev Runtime*.

## Owner-independent, reversible preparation

1. AutoDev Strategy completes a **read-only ATLAS tenant study**: main/RC2/base diff, 10 isolated branch heads, open #83/#400/legacy PRs, app/test/workflow inventory, Android SDK/Gradle 8.13/SDK36/API35 requirements, privacy/signing/OS consent boundaries and actual existing CI charges. This document is a launch point, **not** an executable authority source.
2. Prepare a proposed tenant manifest and worker binding as **reviewed drafts**, not live policy; validate with `autodev-tenant-readiness.mjs --repo <clean local ATLAS checkout> --role project-development-lead --lead-config <bound config>` and worker contract source. The checker is read-only. Explicitly list real limits and unknowns, not fabricate READY.
3. Plan **first one-source integration branch** from exactly `a55b716c`, with exclusive source ownership. Include V's two overlapping `GalleryViewer.kt` edits as **one manual reviewed combination**, not blind merge; wire G in I-owned MainActivity; prioritize #400 regression.
4. Define **one honest Android acceptance package**: free local Gradle JVM + assembleDebug/assembleDebugAndroidTest, API35 test with synthetic fixtures (filmstrip, grid zoom, album search, grant/revoke, People v1→v2 migration, backup, Stories), and a high-scale metadata trial. **No vendor data or real photos in tests**. GitHub PR workflows trigger Android CI on `app/**`; do not open/merge app PRs assuming no Actions cost.
5. Register the tenant's goal and a living integration board with bounded, non-overlapping Dev/Review units and explicit tests/evidence. Link back to #83 so AutoDev is the one software integration lead and duplicate ChatGPT writers cease after ownership cutover.

## Owner-only gates before active transfer

- Confirm/supply **ATLAS repository access** to the actual Claude/AutoDev project; do not assume existing GitHub connector access implies a Claude project connection.
- Approve specific tenant `.autodev/**` authority/manifest/lock setup and any required AutoDev worker-binding or project rules change under **AutoDev AGENTS.md, SECURITY.md, tenant onboarding process**. This brief **does not self-grant** auto-merge, costs, privileged photo permission, signature or host execution.
- Decide the explicit **spending envelope** (default proposal: *no paid GitHub CI / API billing without owner authorization*; use already-provisioned/free local tools where proven). Actual subscription access/quota and Windows host reachability are not verified remotely.
- Decide cutover: prevent concurrent authors editing the same ATLAS paths. Existing worker chats can finish bounded slices or be intentionally parked; AutoDev then owns integration and priority queue. No destructive user-data changes.
- **Never transfer** the permanent APK signing key/password, personal S24+ install authority or real personal MediaStore/V4 Termux database access to AutoDev. Owner approves final device operations separately. Use same package/signer and versionCode **>4** for future in-place updates.

## Suggested bounded first goal (once legitimately activated)

**Goal:** From the *installed RC2-derived v5 source*, produce **one coherent Samsung-class integrated gallery candidate**, beginning with #400 viewer Random-scrub stabilization plus selected viewer/share, G pinch ladder, D Albums, M dates and U Search, while keeping P/C/X/S independent until safe APIs and Android evidence support integration.

**Done for the first cycle when:** exact merged source SHA; approved source/commit provenance; no conflicting owner; free exact-head JVM and Android compile results; P1 scrub instrumentation on genuine API35 emulator; revert path and no old private-data loss; independent review and an honest staged-result ledger. **Phone installation is a separate human decision; source-only work isn't considered an installed feature.**

**Stop/fail-closed:** wrong baseline, missing tenant authorization, unknown cost, no accessible Android build host, inability to validate migrations, conflicting source owner, unauthorized signing/photo access, failed tests or stale PR head. Continue other independent authorized units; no invented PASS.

**Cutover policy:** until owner and AutoDev record a verified activation, **ATLAS coordination issue #83 and existing ChatGPT lanes retain software work ownership**. This document transfers context, not control.


## Owner decision update · 3 Nov 2026 — legacy V4 People is DISPENSABLE, new native People is PRIORITY

**This later explicit owner instruction supersedes the V4-data-retention assumption anywhere in this handoff or the older lane notes.** Lukas is comfortable **losing V4 People identities, assignments and old review history** in order to accelerate the substantially more capable planned native People experience and begin scanning his actual photo library. Do **not** require legacy V4 Termux import, label recovery, V4 live-SAF linking or migration of old V4 identities as product/release blockers. Old V4 imports can remain optional and isolated, but do not invest critical-path effort in maintaining them. There is no need to physically delete legacy V4 files or records just to stop depending on them.

**Clarify the separate concerns:** installed native Gallery **APK versionCode 4** and the legacy **V4 Termux People workflow** are different things. The working installed Gallery package, its signing/upgradability and real photos/videos still deserve protection. Existing native app-private People tables may be reset or recreated **as part of an explicitly disclosed, data-scoped upgrade strategy**, but do not uninstall or reset the whole app or destroy unrelated settings/photos to sidestep a schema problem. Keep simple crash-safe schema/version initialization and permission tests; do not demand legacy identity-retention certification. Owner has consented to *loss of legacy People state*, not destruction or exfiltration of photographs.

**New high-priority product milestone: native People first-class launch.** P + X + U + I should integrate the local, permission-scoped workflow as a coherent end-to-end slice: obtain normal Android photo grants and one opt-in for *on-device People indexing*; crawl the current authorized MediaStore inventory in resumable small batches; decode at bounded resolution; use locally bundled face detector; persist face observations keyed by permission epoch, media revision and model version; add genuine, tested on-device face embeddings and similarity-based anonymous grouping; surface Unknown/suggested clusters with clear review/merge/split/rename/correction; populate person albums and Search. No guessed confirmation or automatic merging of reviewed people. Subsequent new/edited photos should be scanned incrementally while opted in. Initial scanning and all future scanning must be pausable/resumable and respect permission revocation, battery, storage and thermals; background maintenance should favor charging. User can stop scanning and delete the native face index. The fact that the library contains ~150k items is an operational/performance requirement, not permission to bypass Android battery/OS safeguards.

**Permission boundary reinterpreted (important for AutoDev):** locally running ATLAS **may scan real photos on the user's device** once the app has the Android photo permission and People scan opt-in. This does not grant autonomous Claude/AutoDev/CI/cloud workers access to user media, biometrics, V4 DB, credentials or device control. Synthetic assets remain the default for development/CI. No image/embedding uploads, cloud recognition or automatic real-person naming. AutoDev should be authorized to *develop and test* full-library local scanning, not to access the owner's originals through its development host.

**Acceptance:** on an authorized Android build/emulator, end-to-end opt-in → deterministic scanning → persisted progress → user-visible unknown-face review → confirmed person navigation works on synthetic media, including app restart and grant revoke/regrant, without photos leaving the device. Separately, real S24+ optional first scan can be started by Lukas after a safe same-signer v5+ install; show estimated remaining batches, pause/resume and progress. A functioning detection count is not evidence the full identity/grouping experience is complete.

**Priority:** native People is now a near-term product pillar, **not** held hostage by legacy V4 migration. It must coexist with the entire Samsung parity plan, and with #400 viewer stability, rather than replace those priorities.

## Activation record · 3 Nov 2026 (AutoDev onboarding seat)

Lukas asked AutoDev to take over ATLAS development on 3 Nov 2026 (Claude project thread "Onboard ATLAS Native into AutoDev", 04:32Z). The bootstrap PR that carries this section does three things, and nothing else changes authority:

1. **Default branch correction.** It merges the installed-release lineage into `main` (`main` f8368920 is an ancestor of `a55b716c`, which contains RC2 `1f078d59`). Merge with a merge commit, never squash or rebase, so RC2 and every lane base stay ancestors of `main`. After it lands, `main` is the v5 development baseline and AutoDev's ordinary main-based join applies.
2. **Tenant manifest.** `.autodev/project.json` moves from the draft's `propose` to `auto-merge-low-risk` with `autoMergeCategories: ["routine"]`, so unprotected product PRs merge through the AutoDev join (exact-head CI green, CLEAN independent receipt, base = live main). Every path in `promotion.humanMergePaths` (signing config, Gradle app build, Android manifest, `.github/**`, `.autodev/**`) still needs Lukas's Merge or his "merge #N".
3. **Actions minutes.** `build-apk.yml` runs the API35 emulator job only on non-draft PRs (and opted-in manual runs). Draft heads get the Gradle build and JVM tests. `autodev-ci.yml` gives every PR a one-minute static check so non-app PRs have exact-head CI.

Cutover: once this PR is merged, AutoDev (goal issue in this repository, linked from #83) owns integration and the priority queue. The earlier ChatGPT lane chats stop writing to `atlas/lane-*-20261103` branches; AutoDev seats integrate those heads read-only and write only to their own `claude/…` branches.
