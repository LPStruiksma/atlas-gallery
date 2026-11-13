# ATLAS · AutoDev tenant study (read-only findings; 3 Nov 2026)

**Purpose:** Reference study for AutoDev intake #657, not a granted authority or executed worker. Source checks were performed through live GitHub metadata and a subset of source; no clone, Gradle, Android runtime, device or paid CI was used. Follow AutoDev `docs/strategy/tenant-onboarding.md` and run its actual readiness CLI on a clean authorized clone before any transfer.

## 1. Product, stack and source truth
- Private repo: `LPStruiksma/atlas-native`; native Android Kotlin/Jetpack Compose/Media3/Coil, Gradle **8.13** invoked externally (no `gradlew` in source), Android SDK **36**, free target emulator API **35**.
- Actual S24+ working gallery: `nl.atlas.gallery`, signed v4 from RC2 `1f078d59f0ab59c6cc87256e176ad05b9cc752e4` (owner-reported install/gallery load, not full feature acceptance). Do not modify or uninstall.
- **GitHub default `main=f83689209bcfc700020685a653ae03db3970c3c8` is stale**; future source base `atlas/postinstall-next-update-base-20261103=a55b716ce3418c8203542c8230fc9d95e587df9f` (RC2 + versionCode 5). The correct parity programme is on separate docs branch, not on current default branch. A reviewed default-branch reconciliation is necessary before an ordinary AutoDev main-based candidate workflow.
- Reference: the private Samsung parity research archive, 187-finding triage; controlling programme `docs/ATLAS_SAMSUNG_PARITY_PRODUCT_PROGRAM_20261103.md` on docs branch.
- **No combined post-install Android build yet.** Prior exact RC2 590 JVM tests + Android instrumentation reported passed; cannot be projected onto new lane heads. No local build executed in this study.
- Existing `.github/workflows/build-apk.yml` triggers **automatically for Android app PRs and main pushes**, including emulator job. CI is potentially metered. `[skip ci]` history is not a universal policy substitute; require a known budget/approval before a workflow-triggering PR.

## 2. Coordination and concurrency
- Single live ATLAS board [#83](https://github.com/LPStruiksma/atlas-native/issues/83); P1 Random viewer filmstrip bug [#400](https://github.com/LPStruiksma/atlas-native/issues/400); AutoDev intake [autodev#657](https://github.com/LPStruiksma/autodev/issues/657).
- G/M/V/D/U/P/C/X/S = nine specialist source owners, **10 isolated implementation branches** (V has separate share + #400 branches). Exact head map and source exclusivity in [handoff](ATLAS_AUTODEV_OWNERSHIP_HANDOFF_20261103.md); refresh before any action.
- Main user-facing defect #400 has **plausible two-effect viewer recenter race**; no Android execution of proposed patch. V share action overlaps `GalleryViewer.kt`; manual combined review required. I owns `MainActivity.kt`, manifest/Gradle and integration, not duplicated lane writers.
- No current `AGENTS.md`, `CLAUDE.md` or `.autodev/**` tracked in ATLAS default main or v5 base. Existing CI and repo rules are not AutoDev tenancy.
- Eight older open PRs in ATLAS are independent legacy work, not blanket candidates for mass merge.

## 3. Protected data, privileges and costs
- **Personal/owner-only:** ~150k private photos and videos, actual device media, V4 Termux People DB, personal biometrics, owner-signed APK keystore/password, production app-private People, folder modifications/deletions and any owner-facing install/uninstall.
- Never search/copy real photos, V4 private data, personal exports, credentials or AutoDev `eval/held-out/**` into branches, logs or reviews. Public/test synthetic media only, with explicit Android OS consent for eventual Trash/file changes. Prevent accidental API/background face identity auto-merges.
- **No network/cloud, paid model/API, GitHub Actions or hardware provisioning authority** inferred from adding the repository to Claude project. Current Claude subscription allowance/host access must be checked by its actual execution owner. Never assume Windows local Android SDK access from the cloud project.
- Proposal human-only categories: permanent signing, phone install, destructive MediaStore operations, sensitive personal data and privacy policy, any external spend/credentials, authority/security/CI workflow changes, protected branch release promotion.

## 4. Draft tenant configuration to prepare (NOT merged)
- Use AutoDev schema v1 `.autodev/project.json` with `project.id=atlas-native`, `repository=LPStruiksma/atlas-native`, `project.defaultBranch=main` and protected `main`; `requiredDocs` must include two real committed docs (README + controlling parity doc on the *same* active source ref).
- Start with `authority.autonomyMode=propose`, `promotion.autoMergeCategories=[]`, independent review and checks enabled, no direct protected write, `budgets.maxModelCostUsdPerCycle=0` until owner defines an explicit cost envelope. Consider `pr-write` later only with an evidenced no-paid-CI pathway.
- Configure path restrictions with AutoDev adapter's **actual** path matching semantics; preserve signing and personal data exclusions. `.autodev/**`, `.github/**`, app manifest/permissions, version/signing/release config, policy and credentials require review/owner approval as applicable. Do not blindly copy a Node CI template as the Android build check.
- Queue lead needs **reviewed AutoDev-side config with `tenant:{projectId,displayName,repository,requiredDocs}`**. Candidate pipeline currently only accepts GeoAsset (per AutoDev `tenant-readiness.mjs`); extending it requires real implementation/review, not wishful prompt text.
- Before activation, pin a real current AutoDev SHA in a shadow `.autodev/autodev.lock.json` where required, run exact read-only `node bin/autodev-tenant-readiness.mjs --repo <clean ATLAS clone> --role project-development-lead --lead-config <reviewed binding file>`, and record every unverified or failing gate. Project resource access is confirmed by Lukas but not independently tool-inspectable.

## 5. Candidate first units (ranked, reversible; no authority expansion)
| Unit | Outcome / scope | Accept & gate |
| --- | --- | --- |
| U1 | Restore default-branch source correctness without overwriting RC2; preserve all historical branches; integrate full parity docs and protected manifest design | Proven ancestry; no paid main push/workflow without permission; protected merge owner-gated |
| U2 | Independent reviewer integrates V share + tentative #400 fix on v5; resolves overlapping `GalleryViewer.kt` hunk | Exact combined SHA, synthetic Random strip/rail release on **actual API35**; source-only is not a fix |
| U3 | Integrate M dates, D album quick-find, U date facet and G anchor-safe zoom via I host | Native compile + unit/instrumentation on a demonstrably free execution host; no private media |
| U4 | Integrate X People migrations, P face index and C keeper review as opt-in contracts; Stories shell via S | Disposable data migration/permission tests and privacy review; no silent face DB, cloud or Trash |
| U5 | Prepare human-controlled signed v5 update only after stable integrated, reviewed Android acceptance | Same signer v4→v5, package unchanged, no signing key in CI/chat, no automatic phone action |

**Cost estimate:** no cloud/paid-model or hosted CI runs are authorized by this study; optional local subscription time/compute actual cost not determined. Per-unit USD cannot responsibly be promised without execution/capacity evidence.

## State
The repo is now saved to the AutoDev Claude project by owner confirmation. **Research context is ready; active AutoDev control is not.** Next legitimate autonomous step is a separate AutoDev Strategy/Dev read-only preflight and reviewed config+lead binding proposal. Activation requires actual tenant/policy bootstrap and explicit cutover of concurrent lane ownership.
