# ATLAS — Live development handoff

## Latest ATLAS Native checkpoint — 2026-10-15 night

This is **`LPStruiksma/atlas-native`**, not the separate `autodev`
repository. Live GitHub main/PRs/CI override this dated checkpoint.
At writing, main was `1eb6976b020debb907e99d6d128272a147c0d0ed`,
zero open PRs; V4's preview bundle post-merge was green. Check the
latest main V4 synthetic suite independently before calling it green.

**Merged tonight:**

- [#75](https://github.com/LPStruiksma/atlas-native/pull/75),
  `330ff41`: standalone, **offline-only** V5 person-reID scored-manifest
  benchmark. Strict opaque CSV fields, independent known/Unknown
  ground-truth, identity + session + candidate-disjoint calibration vs
  holdout, no raw photos/embeddings or automatic confirmations. Chooses
  a model similarity floor on calibration only while meeting separate
  Wilson 95% upper-bound budgets for Unknown false offers and wrong-known
  offers; produces aggregates with small-strata suppression. Synthetic
  hard negatives show real held-out failures rather than fake success.
  [PR CI 38801738514](https://github.com/LPStruiksma/atlas-native/actions/runs/38801738514)
  and [main CI 38801875166](https://github.com/LPStruiksma/atlas-native/actions/runs/38801875166)
  green. Optional future models: OSNet/Torchreid and FastReID,
  NOT currently downloaded or integrated.
- [#76](https://github.com/LPStruiksma/atlas-native/pull/76),
  `0e28f14`: threshold selection is now O(N log N) rather than rescanning
  every trial for each score; **unrounded** Wilson upper bounds decide
  the gate (0.05004 cannot be rounded to a pass at 0.0500). Extra
  synthetic boundary/score-sweep tests, [PR run 38802319839](https://github.com/LPStruiksma/atlas-native/actions/runs/38802319839)
  and [main 38802426669](https://github.com/LPStruiksma/atlas-native/actions/runs/38802426669)
  green.
- [#77](https://github.com/LPStruiksma/atlas-native/pull/77),
  `1eb6976`: V4 Discover persists only **four anonymous browser
  navigation primitives** (review/all, ordering, shuffle seed, page).
  It resumes on navigating back after a browser reload, clamps stale
  pages to currently indexed group counts with a read-only endpoint,
  and stays usable when localStorage is disabled. No names, IDs,
  filenames, person selection, private session token, image paths,
  or embeddings enter browser storage. Synthetic 2000-group SQL
  counts and strict JS cursor-decoder tests, zero DB writes,
  [PR V4 run 38802967951](https://github.com/LPStruiksma/atlas-native/actions/runs/38802967951),
  [postmerge V4 suite 38803081058](https://github.com/LPStruiksma/atlas-native/actions/runs/38803081058)
  and [postmerge V4 Termux bundle 38803081167](https://github.com/LPStruiksma/atlas-native/actions/runs/38803081167)
  all green.

**Hard boundaries remain:** GitHub merges do NOT update or reinstall
the native app currently on the user's S24+, nor the running local Termux
V4 server. Actual v0.2 signing identity, settings/People backup
completeness, independent restore validation, SAF permissions and owner
consent are still unresolved. Never uninstall the on-phone app as an
upgrade shortcut or ask the user to upload face labels, keys or media.
Existing reversible signing/capture/emulator tests are documented in
`docs/STABLE_SIGNING.md` and
`docs/NATIVE_PEOPLE_BACKUP_VALIDATION.md`; scripts exist for public
certificate comparison, offline People backup integrity, WAL
consolidation and read-only Shizuku feasibility check.

**Remaining autonomous engineering targets:** benchmark real
open-source body/face models *only on legally usable, opt-in local
test materials*, document preprocessing and deployment/runtime
constraints, add a privacy-safe aggregate inference p95 memory/thermal
profiler, polish V4 People Discover triage and the native
Samsung-style filmstrip. Physical phone measurements, actual local
identity pilot, and signing secrets/installation require owner action.

---

## Current ATLAS Native checkpoint — 2026-10-15 evening

**Repository identity:** `LPStruiksma/atlas-native` is the private local
Android gallery + V3/V4/V5 Termux research, **not** `autodev`. At
checkpoint start, the latest verified `main` was
`e8c7612de150c327a1643df8432640b3bded464a`. Always check live
GitHub `main`, open PRs, workflow conclusions, and installed-device state
before assuming this checkpoint is current.

**This day's new V4 work:**

- [#70](https://github.com/LPStruiksma/atlas-native/pull/70),
  merged `7049d88`. Discover defaults to **Needs review**, based on LIVE
  counts of unassigned and nonignored faces, rather than stale original
  cluster sizes. **All groups** restores historical clusters; sorting,
  seeded pagination, and returning after a completed page remain stable.
  No label mutation. PR [38769778095](https://github.com/LPStruiksma/atlas-native/actions/runs/38769778095)
  and postmerge main V4/Termux bundle
  [38769900267](https://github.com/LPStruiksma/atlas-native/actions/runs/38769900267) /
  [38769900465](https://github.com/LPStruiksma/atlas-native/actions/runs/38769900465)
  green.
- [#71](https://github.com/LPStruiksma/atlas-native/pull/71),
  merged `5844302`. Active group cover selects the best-quality
  currently unassigned, nonignored face, never an already reviewed/hidden
  historical representative. Completed All groups retains original cover.
  [PR V4 CI 38770217856](https://github.com/LPStruiksma/atlas-native/actions/runs/38770217856)
  and postmerge V4/preview workflows
  [38770336691](https://github.com/LPStruiksma/atlas-native/actions/runs/38770336691) /
  [38770336684](https://github.com/LPStruiksma/atlas-native/actions/runs/38770336684)
  green.

**This day's new V5 research/evaluation work:**

- [#72](https://github.com/LPStruiksma/atlas-native/pull/72),
  merged `e8c7612`. Offline-only model-free evaluation compares
  face-only, face+session, face+body+session, and all evidence on the SAME
  independently reviewed hypothetical cases. Reports aggregate
  false-person suggestions, offer coverage, condition strata and Unknown
  abstentions. Requires 2+ independent positive groups and zero
  contradictions for even a **manual-review suggestion**; never assigns
  a person or claims calibrated probabilities. The fictional fixture
  intentionally includes a wrong-body lookalike and dependent clothing/
  context/body clues. Exact-head [PR 38771046205](https://github.com/LPStruiksma/atlas-native/actions/runs/38771046205)
  and [postmerge main 38771130799](https://github.com/LPStruiksma/atlas-native/actions/runs/38771130799)
  synthetic CI green.
- This documentation/safety follow-up explicitly rejects *absent*
  reference truth instead of misreporting an unlabeled observation as a
  deliberately human-confirmed Unknown. CI test added; check latest PR
  and run before promotion.

**Do not conflate code with on-device installation.** These GitHub merges
did NOT restart the owner's Termux V4 server, install an Android APK or
access private photos, labels, embeddings or keys. The owner's old native
v0.2 signer/People backup gap (#2/#32) continues to block a safe APK
upgrade. Do not uninstall to work around a signer mismatch. Real V4
bundle deployment requires finishing any live jobs and a verified
local database backup, with owner involvement for on-device actions.

**Open independently actionable work:** improve V4 review triage/selection
and benchmark unassigned queries under realistic large synthetic
clusters; develop more robust independent-cue and abstention calibration
and a repeatable model benchmark suite; prepare read-only physical S24+
filmstrip/jank measurement scripts for use only *after* signing-safe
deployment. The actual real-device signing/backup/installation, identity
pilot using reviewed personal examples, and spending/credentials require
owner participation. Keep all person-name proposals explicitly
review-only and reversible.

---

## Viewer and 150k-catalogue engineering — 2026-10-15

**This is ATLAS Native, `LPStruiksma/atlas-native`, the local Android gallery.**
Do not conflate with `LPStruiksma/autodev`. **Live GitHub main, PRs and
Actions are authoritative**; the following is a dated evidence handoff,
not an instruction to change signing, perform a device migration or access
private photos.

**Latest observed main:** `e3a40e0ae6ee1424d8155ab1eddaa3027f8da230`
after integrating the two viewer-performance PRs below. Both exact-head
premerge CI runs and the
[postmerge main Android run 38747373726](https://github.com/LPStruiksma/atlas-native/actions/runs/38747373726)
completed successfully. This is a dated checkpoint; recheck live CI before
acting on it.

- **[#62](https://github.com/LPStruiksma/atlas-native/pull/62)**
  `94f686c`: full-range position Slider to jump across large albums
  while preserving the bottom centered precision filmstrip; 64 synthetic
  photo emulator navigation/center tests green.
- **[#63](https://github.com/LPStruiksma/atlas-native/pull/63)**
  `3dd188d`: one-tap photo-only immersive mode hiding/restoring
  header, strip and coarse rail. First emulator red was KVM runner setup;
  second red was an ambiguous test selector seeing neighboring composed
  pages. Unique visible-pager test fixed, exact-head emulator CI green.
- **[#64](https://github.com/LPStruiksma/atlas-native/pull/64)**
  `750a891`: photo zoom and pan reset when an offscreen composed
  photo loses focus so returning to it doesn't block normal swiping.
  PR Android CI
  [38627309848](https://github.com/LPStruiksma/atlas-native/actions/runs/38627309848)
  and postmerge main [38742017703](https://github.com/LPStruiksma/atlas-native/actions/runs/38742017703)
  green.
- **[#65](https://github.com/LPStruiksma/atlas-native/pull/65)**
  `4d0c547`: filmstrip centered-index `collectLatest` drops obsolete
  pager jumps when scrubbing quickly. Settled neighboring Coil
  prefetch prioritizes the last browsing direction, still max 4
  neighbors by default, max 8 for caller radius 4, no extra decode
  budget. Alternating-swipe emulator + JVM 150k-index safety tests
  [PR run 38743119510](https://github.com/LPStruiksma/atlas-native/actions/runs/38743119510)
  and [postmerge main 38744374222](https://github.com/LPStruiksma/atlas-native/actions/runs/38744374222)
  green. These are **correctness gates**, not demonstrated S24+ speedups.
- **[#66](https://github.com/LPStruiksma/atlas-native/pull/66)**
  `e3a40e0`: `rememberAsyncSortedCatalog` moves sorting 150k
  catalogue metadata out of Compose composition to `Dispatchers.Default`.
  `CatalogSourceKey` uses *referential* equality: two lists with equal
  metadata but different MediaStore generations trigger a new sort;
  stale order/seed/media snapshots cannot be shown between effects.
  Sorting UI shows 'Ordering library…'. Synthetic Compose test
  performs 15k records newest → oldest → random → newest and
  checks a fresh equal-content source generation. Initial test
  compile failed due to unsupported semantics helper; corrected.
  [Final exact-head PR Android build + emulator 38746031280](https://github.com/LPStruiksma/atlas-native/actions/runs/38746031280)
  green, including synthetic People SQLite upgrade preservation and
  three PNG screenshots. No device or private archive was used.

**Signing/phone safety unchanged:** installed S24+ 0.2 APK is NOT
automatically updated from GitHub. Stable public certificate comparison,
verified consistent native People + settings backup, and explicit owner
consent remain gates (#2/#32). Never uninstall/reinstall as an upgrade
shortcut. Termux V4 preview and V5 evidence adapters are separate.

**Next open independently actionable work:** prep a privacy-preserving
cold/warm viewer benchmark for *after* the owner-authorized phone upgrade;
review main-thread *search/filter* cost on 150k synthetic records
(a simplistic case-insensitive `contains` rewrite was microbenchmarked
locally and did not reliably outperform the existing code; do not
apply it without evidence); continue bounded Discover triage and
calibrated local-only V5 face/body/session proposals with Unknown and
no automatic merges. Only real-device signing/backup/install/identity
pilot data require owner participation. All actions taken in this
checkpoint touched the gallery GitHub repo, not AutoDev or the phone.

---

## Current ATLAS Native checkpoint — 2026-10-14, evening

**Project boundary:** This is the private Android gallery repository
`LPStruiksma/atlas-native`, **not** the separate `LPStruiksma/autodev`
autonomous-engineering system. The ATLAS app is one Kotlin/Compose gallery
with separate Termux V3 B&W, V4 People and V5 local evidence/search layers.
Treat live main, PRs, Actions and device state as the authority; this is a
factual snapshot, not permission to change user-controlled signing/data.

**Last verified GitHub `main`:** `e42daeb7487df70bce684a5cce99c94d6295901c`
following #57, #59 and #58 merges. The combined postmerge Android run
[38620125485](https://github.com/LPStruiksma/atlas-native/actions/runs/38620125485)
has now **completed successfully** at this exact main SHA.

- **[#57 visual native reviewed-People Search](https://github.com/LPStruiksma/atlas-native/pull/57)**
  merged `a3a9aff129a2d3695ab8abafa14630fe99ba3c49`.
  On the same underlying reviewed People albums, Search now shows
  accessible MediaStore-backed photo previews/counts instead of name-only
  chips, with neutral fallback when a profile's exact exported paths are
  inaccessible. Same-name identities stay distinct by ID. Synthetic exact
  path vs same-basename/wrong-folder regression and actual emulator UI green
  [run 38617265572](https://github.com/LPStruiksma/atlas-native/actions/runs/38617265572);
  postmerge main Android build
  [38618678682](https://github.com/LPStruiksma/atlas-native/actions/runs/38618678682)
  green. No S24+ installation implied.
- **[#59 V4 Discover stable shuffle](https://github.com/LPStruiksma/atlas-native/pull/59)**
  merged `39a49703c26d3f2414bfef42f008b7e566d316c5`.
  Provisional group listing now supports Largest first / Surprise me /
  Shuffle again. Seed stays stable through Next/Previous and group Back;
  only cluster metadata are reordered. Existing-name hints remain page-
  bounded, advisory and never assign identities. 2,000 fictional clusters
  across 50 pages preserved all groups with zero SQLite mutations in
  [PR CI 38619595706](https://github.com/LPStruiksma/atlas-native/actions/runs/38619595706).
  [Postmerge V4 CI 38619723503](https://github.com/LPStruiksma/atlas-native/actions/runs/38619723503)
  and [new private V4 preview bundle 38619723494](https://github.com/LPStruiksma/atlas-native/actions/runs/38619723494)
  both green. The on-phone Termux V4 server is unchanged until owner installs
  the preview after backing up its curated DB and finishing active jobs.
- **[#58 precomputed random keys](https://github.com/LPStruiksma/atlas-native/pull/58)**
  merged `e42daeb7487df70bce684a5cce99c94d6295901c`.
  Computes a stable per-item shuffle key before comparing 150k media items,
  preserving the exact previous order for the same seed, mixed image/video
  ID namespaces and tie-breaks. Kotlin/JVM synthetic benchmark suggested
  an approximate 22% median speedup in one local run (not S24+ timing).
  Tests include a legacy-order oracle across input reversals and edge seeds.
  [Exact-head Android CI 38618972880](https://github.com/LPStruiksma/atlas-native/actions/runs/38618972880)
  full build/emulator green before merging.
- **README project reality correction:** the same docs-only change
  supersedes stale foundation/build/repo-bootstrap instructions, preserves
  old text in `docs/FOUNDATION_0_2_README.md`; no application behavior changed.

**Remaining bottleneck:** installed S24+ native v0.2 has an unknown real-device
signing match and potentially valuable app-private People data. Synthetic
same-signer and signer-mismatch emulator recovery are proven, but a physical
backup/public signer check and consent are STILL REQUIRED before installing
a new APK. Never uninstall to fix an incompatible signature. Secondary gates:
physical cold/warm filmstrip measurements vs Samsung Gallery, first actual
native live V4 SAF import, and measured local-only V5 face/body/session
identity pilot with explicit Unknown rather than invented recognitions.

---

## Integrated product milestone — 2026-10-14 evening, reconciled

**Fetch live GitHub main, open PRs, CI and issues at the start of each
development session; this is a dated evidence record, not release authority.**
Last observed `main`: `3d5da822911480fcb826ab5ed197fdca0ebea399`.
No open PRs at that checkpoint.

- **Release/rollback safety:** [#47](https://github.com/LPStruiksma/atlas-native/pull/47)
  merged `3892b1b6bcffd8daf367a0fc3de0405fc6a468e4`,
  dedicated emulator signer-mismatch/SQLite-backup/CSV-restore proof
  [run 38598384327](https://github.com/LPStruiksma/atlas-native/actions/runs/38598384327)
  green. Existing same-signer People preservation also green.
  **No S24+ installed signer fingerprint or real-device backup/SAF
  migration proof**. #2/#32 remain owner-controlled; never uninstall
  or replace installed app without verified backup and consent.
- **Complete native user journey:** [#51](https://github.com/LPStruiksma/atlas-native/pull/51)
  merged `b3b5788969b00fba29c79bd6d9d0529b61f45015`,
  [full Android PR CI 38600481528](https://github.com/LPStruiksma/atlas-native/actions/runs/38600481528)
  green. Test exercises synthetic Search → manually confirmed
  duplicate-name Person IDs → exact B&W-folder filter → actual
  full-screen filmstrip → Back to filtered album → Back to Search.
  Original test exposed overlapping independent-route UI because
  `PeopleAlbumContent` implicitly depended on outer Column;
  component now owns its vertical layout. A main-branch Android
  [run 38602728354](https://github.com/LPStruiksma/atlas-native/actions/runs/38602728354)
  was **in progress** at last observation, not yet assumed green.
- **V4 People Discover:** [#53](https://github.com/LPStruiksma/atlas-native/pull/53)
  merged `007038ba797f274319ab305c5b27fff24a5b5322`.
  Page-batched "Possibly already named" hints appear on provisional
  group cards when at least two **distinct photos** resemble a
  manually confirmed profile. Mixed groups retain multiple possibilities,
  unidentified groups remain visible, no People assignments written.
  Synthetic 40 groups × 500 profiles × 4 reference faces measured
  **430 ms in GitHub CI** (NOT physical S24+ timing) and passed
  identity-safety tests; [main run 38601378086](https://github.com/LPStruiksma/atlas-native/actions/runs/38601378086)
  and [Termux preview bundle 38601378119](https://github.com/LPStruiksma/atlas-native/actions/runs/38601378119)
  green. The updated V4 preview is **not automatically installed
  on Lukas's phone**; actual performance/precision must still be measured.
- **V5 groundwork:** [#52](https://github.com/LPStruiksma/atlas-native/pull/52)
  merged `ff28ad9b9e3a8993010205f04533d79393d8e313`.
  Dedicated SQLite V5 evidence store tracks exact local source,
  normalized person region, front/back/unknown visibility, provenance
  for face/body/clothing/session/context, independent correlation
  groups, contradictions, explicit Unknown; no biometric models,
  identity probabilities, automatic merging, V4 writes or cloud upload.
- **Read-only V4↔V5 connection:** [#55](https://github.com/LPStruiksma/atlas-native/pull/55)
  merged `3d5da822911480fcb826ab5ed197fdca0ebea399`.
  `tools/identity_fusion/import_v4_snapshot.py` imports only
  manually confirmed non-ignored V4 face observations from a
  **consistent offline standalone snapshot** into an ephemeral,
  separate V5 evidence store. Refuses SQLite WAL/SHM sidecars,
  validates integrity/schema/exact paths/regions and reports aggregate
  skipped counts only; does not copy private photos/embeddings, call
  a model, change source identities or confirm V5 guesses.
  `occlusion_known=0` explicitly distinguishes *unmeasured*
  occlusion from measured zero; additive compatible legacy V5
  schema migration. Fast synthetic PR [run 38602811521](https://github.com/LPStruiksma/atlas-native/actions/runs/38602811521)
  and [main run 38602894997](https://github.com/LPStruiksma/atlas-native/actions/runs/38602894997)
  green. **Still no person-from-behind model or real local pilot.**

**Biggest next independent bottlenecks:** (1) Real-device release
preflight can be *prepared* autonomously, but comparing installed APK
signer, acquiring a private consistent local People+settings backup,
selecting migration strategy and any real installation require owner
participation/consent. (2) Turn the V5 evidence foundation into
**measured, local-only** face/body/session candidate comparisons on
a small reviewed set with unknown/contradiction controls, no hidden
assignments. (3) Benchmark viewer and V4 batched Discover on physical
S24+ in cold/warm conditions, including 120 Hz frame jank and false
identity suggestion review; no results claimed before measurement.
(4) Continue unified native exact+AI search as a provenance-separated,
reversible local facet, reusing existing MediaStore, Coil, Media3 and
Telephoto baseline.

**No real media, biometric data, signing credentials or app-private DB
were accessed, modified or uploaded by these GitHub PRs.**

---

## Latest ATLAS progress — 2026-10-14 evening (recovery + reviewed identity)

**Always fetch live main/PRs/CI first.** Last observed `main` =
`007038ba797f274319ab305c5b27fff24a5b5322`.
This note captures merges and remaining blockers, not permission for
phone actions.

- [PR #47](https://github.com/LPStruiksma/atlas-native/pull/47)
  **MERGED** `3892b1b6bcffd8daf367a0fc3de0405fc6a468e4`.
  Dedicated [emulator synthetic mismatch run 38598384327](https://github.com/LPStruiksma/atlas-native/actions/runs/38598384327)
  **green**, including offline contract and isolated emulator recovery:
  same-signer APK preserves an invented People row; `VACUUM INTO`
  captures a consistent SQLite snapshot; offline integrity and CSV
  export validated; random temporary certificate B is **rejected**
  by Android's in-place installer; after strictly emulator-only reset,
  recovery CSV restores exactly the original fictional Person and
  full native UI suite succeeds. Routine Android PR emulator
  [run 38598384321](https://github.com/LPStruiksma/atlas-native/actions/runs/38598384321)
  also green. Earlier red was test-runner mistakenly reverting to
  signer A for final UI tests; fixed by retaining B across Gradle invocations.
  **This does not validate the physical S24+ installed certificate, real
  SQLite/WAL/SAF settings backup, or authorize uninstall.** Issue #2/#32
  remain open for owner-participating release.
- [PR #52](https://github.com/LPStruiksma/atlas-native/pull/52)
  **MERGED** `ff28ad9b9e3a8993010205f04533d79393d8e313`.
  `tools/identity_fusion/evidence_store.py` implements a **dedicated
  local-only, standard-library, versioned V5 evidence-store prototype**:
  exact media path/source IDs, normalized person regions and visibility,
  provenance for face/body/clothing/session/context, explicit contradiction
  and Unknown, grouping correlated same-session evidence, no fabricated
  identity probability, no People confirmation/merge/export API. Tests
  refuse accidental writes to a V4/native database or incompatible schema;
  fast synthetic CI [run 38600246464](https://github.com/LPStruiksma/atlas-native/actions/runs/38600246464)
  plus main push [38600514101](https://github.com/LPStruiksma/atlas-native/actions/runs/38600514101)
  succeeded. **No body model or actual person-from-behind recognition
  exists yet**; stage A schema foundation only.
- [PR #53](https://github.com/LPStruiksma/atlas-native/pull/53)
  **MERGED** `007038ba797f274319ab305c5b27fff24a5b5322`.
  V4 Discover group *listing cards* now show possible already-named
  People where at least **two different group photos** support a
  similarity, computed in a shared bounded page matrix
  (40–80 groups; 5 group refs; 4 confirmed refs per person), without
  writing identity or hiding mixed groups. V4 synthetic performance/
  correctness [run 38601278530](https://github.com/LPStruiksma/atlas-native/actions/runs/38601278530)
  and main [run 38601378086](https://github.com/LPStruiksma/atlas-native/actions/runs/38601378086)
  are green; distributable preview bundle [38601378119](https://github.com/LPStruiksma/atlas-native/actions/runs/38601378119)
  green. This code is **NOT automatically installed in Termux**.
  Resemblance hints are not identity probabilities; human review still
  required. Follow-up #20 global Discover filtering and physical S24+
  performance not complete.
- [PR #51](https://github.com/LPStruiksma/atlas-native/pull/51)
  **OPEN / NOT YET MERGED at snapshot.** It adds a full synthetic
  Compose user journey Search → reviewed Person → exact B&W folder →
  real full-screen viewer + filmstrip → Back to preserved filter/Search.
  First emulator test exposed an extracted People-album composable
  depending on the root caller's `Column`, so test content overlapped
  and taps could target an underlying grid. Code corrected by making
  `PeopleAlbumContent` own a `Column(Modifier.fillMaxSize())`;
  latest head `9ccbcca5fb9b81ebaff6cb88332003585ca4ba43`
  passed Android build; emulator
  [run 38600481528](https://github.com/LPStruiksma/atlas-native/actions/runs/38600481528)
  was **still in progress**. Verify the final head checks and merge only
  if the full emulator People upgrade + viewer interactions are green.
- **Immediate unblocked next work:** resolve #51 CI; create only safe
  release readiness and recovery steps requiring owner participation
  **after** signed phone cert/consistent local-backup evidence; expand
  read-only V5 adapters from a *consistent V4 copy* and benchmark real
  people-from-behind/session cues on small local holdouts;
  continue precision-first V4 Discover UX with mixed-group review,
  retention of manual identities and measured S24+ performance.
  Do not ask Lukas to install an unverified signing identity or
  replace/uninstall ATLAS v0.2. No real photos, faces, signing keys,
  private People records, or backups are in GitHub.

---

## Latest integration milestone — 2026-10-14 afternoon

**Always re-fetch GitHub main, PRs, CI and issues before proceeding.**
At this checkpoint latest `main` = `97a9092c353bbddb2f0cbd1048a8c96ba3eb9910`.

- [PR #49](https://github.com/LPStruiksma/atlas-native/pull/49)
  **MERGED** `33775a06661f0ef6c86919ffb6c8fecf860cc818`. New
  [unified gallery integration blueprint](ATLAS_UNIFIED_GALLERY_INTEGRATION.md)
  defines cohesive user journeys: Search → reviewed Person → B&W
  virtual album → rapid filmstrip → Back; sort/random browsing; V4 review
  → checksum-sealed native People sync; later review-only body/session AI.
  It is now linked from the formerly stale top-level README. Treat it as
  product direction, not a supersession of governance or live CI evidence.
- [PR #48](https://github.com/LPStruiksma/atlas-native/pull/48)
  **MERGED** `97a9092c353bbddb2f0cbd1048a8c96ba3eb9910`.
  Native Search now displays **reviewed People name shortcuts** leading
  into the existing Person album with B&W filter, filmstrip viewer and
  normal Android Back behavior. Distinct person IDs, including duplicate
  visible names, are never automatically merged. JVM and Compose
  synthetic tests passed; full Android native build/emulator upgrade/UI
  [run 38580958831](https://github.com/LPStruiksma/atlas-native/actions/runs/38580958831)
  succeeded. **No new APK has been installed on the physical phone**.
- [PR #47](https://github.com/LPStruiksma/atlas-native/pull/47)
  **OPEN**, intentionally **NOT MERGED** until CI proves full signer
  mismatch recovery on a disposable emulator. It has an opt-in, separately
  scoped expensive Actions workflow, with a fast cheap offline contract
  gate (Bash syntax, synthetic SQLite backup integrity, SQLite→CSV).
  First attempts failed in hosted infrastructure *before running the actual
  migration*: one Ubuntu runner lacked accessible KVM, the other Android
  SDK download reported corrupt x86_64 system-image ZIP. This is
  **not evidence that the application migration works or fails**.
  Latest changed head `629971bc4199baa9bfec954ba12ef0e7dbedd76c`
  has offline contract **green** (run 38582177205), and new dedicated
  emulator recovery / normal Android PR runs were **in progress** at
  snapshot (runs 38582177205 and 38582177253). Inspect the latest
  jobs/diagnostics rather than repeatedly blind-retrying CI.
  The test procedure is strict: seed fictional People on signer A;
  consistent SQLite `VACUUM INTO` snapshot; verify integrity;
  convert offline to recovery CSV; generate independent temporary
  emulator-only signer B; require Android in-place rejection; only with
  `ro.kernel.qemu=1` and GitHub CI guard uninstall **fictional emulator
  test** then import and verify the recovered Person. All non-emulator
  action is blocked; do not claim any phone migration/backup success.
- **Blocking real S24+ installation** (#2/#32): signing certificate for
  installed v0.2 is unverified against candidates. No phone APK replacement,
  uninstall, credential/key setup, private People backup or permission changes
  without Lukas's explicit authorization and a verified local recovery path.
- **Open independent work**: finish #47 gated synthetic migration evidence;
  add an actual end-to-end synthetic Search→People→B&W→viewer→Back test
  without creating another separate route; collect S24+ filmstrip/zoom
  A/B traces only after real-device release safety; eventually introduce
  read-only, versioned V5 face/body/clothing/session suggestions with
  conservative abstention and human provenance. Avoid exposing private
  face/name/path data in GitHub issues or artifacts.

---

## Latest native viewer milestone — 2026-10-14, 15:27 Europe/Amsterdam

**Always re-check current GitHub main, open PRs, CI and issue tracker.**
At this checkpoint `main` = `958d6caebf203e16df7e9255ca14448c840cac3f`.
No open PRs at last check.

- [PR #43](https://github.com/LPStruiksma/atlas-native/pull/43)
  **merged**: Telephoto 0.19.0 Coil3 zoom prototype is confined to
  `androidTestImplementation`, not user-shipped runtime. Synthetic
  emulator test passes load, double-tap and independent per-photo zoom state;
  production gallery still uses its existing implementation.
- [PR #44](https://github.com/LPStruiksma/atlas-native/pull/44)
  **merged**: emulator functional test verifies selected thumbnail is
  centered under the fixed filmstrip marker after initial open, tap and
  swipe/fling. CI complete and green.
- [PR #42](https://github.com/LPStruiksma/atlas-native/pull/42)
  **closed WITHOUT merging** due to conflicts with #43/#44; fully
  superseded by [PR #45](https://github.com/LPStruiksma/atlas-native/pull/45),
  **merged** `958d6caebf203e16df7e9255ca14448c840cac3f`.
  #45 rebased an opt-in bounded memory-only `ViewerTimingProbe` on updated
  main, retained prior filmstrip and Telephoto tests, and passed real
  emulator People-data-preserving upgrade + UI suite in
  [CI run 38570640502](https://github.com/LPStruiksma/atlas-native/actions/runs/38570640502).
  It records temporary indices and timings, no photo/name/identity paths,
  and uses a separate code path so default gallery preview stays unchanged.
  Cache-aware selection avoids misreporting predecoded neighbors as missing.
- Push-to-main [run 38571934994](https://github.com/LPStruiksma/atlas-native/actions/runs/38571934994)
  was in progress at the snapshot; it is a *new* build, not a PR gate
  failure. Android workflow already has per-ref `concurrency` and
  `cancel-in-progress: true`, and the heavy emulator job runs on PRs
  or explicit dispatch, not on every main push. Avoid repeated polling:
  check once when ready or use Actions status, and never mistake slow
  emulator provisioning for a hung user conversation.
- **Physical S24+ release blocker remains**: ephemeral runner key is not
  proven compatible with installed v0.2 signer; do not uninstall/update phone
  or modify app-private People data without owner-confirmed backup,
  installed public cert comparison and signing/migration preflight.
- **Next unblocked engineering**: verify synthetic signer-mismatch recovery
  and source-private backup round-trip; prototype realistic zoom/subsampling
  performance and gesture-conflict experiments on synthetic data with Telephoto
  still isolated; compare measured timing/heap/Perfetto evidence when a safe
  signed on-device test is possible. No 25k automatic People deployment
  without stronger identity-precision/novel-person validation.

---

## Continuity update — 2026-10-14 afternoon (calibration and mixed-media sorting)

**Live repo always outranks this note.** Last observed `main` before this
documentation update: `60b7a3410f7f88544b9821101eef08daa605c701`.
All active PRs #29, #35–#40 discussed below are merged; check live Actions
and pull requests before assuming no later changes.

- [PR #39](https://github.com/LPStruiksma/atlas-native/pull/39)
  **MERGED** `8eb051d05be698f5f8138f04f33e0a6ef41f86ad`.
  New V4 CLI `auto-calibrate --limit 500` compares the existing strict
  matcher against five fixed policies on exactly the same positive-person
  and sparse-novel-person held-outs in **one in-memory masked SQLite copy**.
  Numeric-only outputs contain known precision/coverage and unknown false-link
  rate for each. Experimental score policies have a hard code-level guard
  forbidding `commit=True`. No production threshold, auto-assign behavior,
  source People record, image, config, native CSV or phone APK was changed.
  Branch/main V4 synthetic CI green; preview bundle green. **Not yet run on
  Lukas's real private image archive**. Newer script must be explicitly
  installed after checking existing Termux jobs before on-phone diagnostics.
  The previous real holdout remains 86 correct, 0 observed wrong, 305
  abstained, 31 negatives with 0 observed false links; holdout correlation
  prevents claiming safe unattended 25k-face deployment.
- [PR #40](https://github.com/LPStruiksma/atlas-native/pull/40)
  **MERGED** `60b7a3410f7f88544b9821101eef08daa605c701`.
  Native seeded-random sorting now gives images and videos **different random
  identities even when their separate MediaStore tables share a numeric ID**.
  JVM tests sort 150,000 purely synthetic mixed-media metadata items, check
  exact membership, deterministic reshuffle, selection and bounded preview
  indices. Build **and hardware-accelerated emulator upgrade/UI checks**
  succeeded in [run 38559896850](https://github.com/LPStruiksma/atlas-native/actions/runs/38559896850).
  JVM synthetic timings are NOT real-device smoothness benchmarks; the installed
  phone APK was not touched.
- Preserve Android release **blockers #2 and #32**: same-runner emulator
  preservation is proven, but existing Galaxy S24+ v0.2 signing identity vs
  any new release candidate remains unverified. `scripts/probe_existing_native_install.py`
  is read-only and opt-in, not a backup. Never uninstall, migrate People
  records, configure signing keys or replace the installed app without
  explicit owner authorization and separately validated recovery.
- **Open unblocked engineering to prioritize:** (1) implement debug-only
  viewer input→thumbnail/preview-ready instrumentation, then compare a
  Telephoto large-image/subsampling **isolated prototype** against existing
  Compose Pager/Coil/Media3 without a rewrite; use physical S24+ only after
  safe-upgrade gate. (2) Tighten V4 novelty/error evaluation with
  same-session/lookalike challenge sets and a reversible reviewed pilot.
  (3) Demonstrate synthetic signer-mismatch recovery round-trip while
  keeping all real-device state unchanged. Preserve known-person abstention
  unless evidence strongly supports any automation change.

---

## Latest reconciled state — 2026-10-14 (after KVM and People-safety merges)

**Read this section first.** It supersedes the older snapshot below. Always
re-fetch GitHub main, open PRs and Actions before relying on these SHAs.

- Last observed `main` after merges: `5e94ce1d6af2302e75ac7aa7fbb1df3e31f469a2`.
  [PR #28](https://github.com/LPStruiksma/atlas-native/pull/28)
  native live V4 CSV sync remains merged, not an open task.
- [PR #29](https://github.com/LPStruiksma/atlas-native/pull/29)
  APK upgrade/preservation CI **MERGED** `1370881a5ab2d1fc0f5d9845b2665b8716e226cf`.
  Previous ten-minute emulator hang came from absent `/dev/kvm`
  permission on Linux GitHub Actions, not established People database damage.
  The PR added the documented udev KVM enable step and fails fast when
  KVM inaccessible, so the emulator now uses acceleration. Successful
  [Actions run 38555290288](https://github.com/LPStruiksma/atlas-native/actions/runs/38555290288)
  built base v0.2 + candidate v0.3 with **the same runner debug key**,
  seeded one fake People identity in app-private SQLite on old APK,
  installed the newer APK with `adb install -r`, verified unchanged People
  content, ran native viewer interaction tests, and produced 3 valid PNGs.
  [Signing unit-gate run 38555290197](https://github.com/LPStruiksma/atlas-native/actions/runs/38555290197)
  also passed; a main-branch Android build after merge was in progress
  at the last check (run 38556196570). **Never equate a same-runner
  emulator upgrade with phone signing compatibility.**
- [PR #35](https://github.com/LPStruiksma/atlas-native/pull/35)
  V4 People face-label protection **MERGED** `18020f67dfa9187b4b73ab7ee84019afc71db8cc`.
  Failed OpenCV/image reanalysis of an existing photo previously deleted
  all its face rows, potentially erasing confirmed manual identity tags.
  Now preserve existing rows and metadata on errors, retry later, scope
  preserved labels to only the current image on success. A synthetic test
  covers a failing decoder, subsequent recovery, and new-photo identity
  isolation. V4 synthetic CI passed at both branch and main
  ([main run 38555730023](https://github.com/LPStruiksma/atlas-native/actions/runs/38555730023)).
  Preview bundle main run 38555730124 passed. It is **not** automatically
  installed in any running Termux instance.
- [PR #36](https://github.com/LPStruiksma/atlas-native/pull/36)
  benchmark-first gallery filmstrip reuse study **MERGED**
  `5e94ce1d6af2302e75ac7aa7fbb1df3e31f469a2`.
  Read [gallery filmstrip study](GALLERY_FILMSTRIP_BENCHMARK_AND_REUSE.md):
  keep existing Compose Pager + LazyRow, Coil 3 and Media3 as baseline;
  benchmark Telephoto's Apache-2.0 ZoomableImage/subsampling before writing
  custom zoom or thumbnail infrastructure. Benchmark on an actual S24+
  **only after device release safety gate**, not using Linux emulator
  timings as native performance claims.
- **Real V4 auto-recognition held-out evaluation (user's private phone,
  read-only, no photos uploaded):** 391 known-person trial faces;
  86 correct, 0 observed wrong, 305 abstained; 22.0% coverage at 100%
  observed precision. Also 31 sparse unenrolled-person negative trials,
  0 false links (small/correlated sample). One-sided descriptive 95%
  zero-error bounds approximately 3.42% for 86 linked faces and 9.21% for
  31 negative trials; *not* a deployment safety guarantee. Do not turn on
  bulk unattended `auto-match` across ~25k unknown faces based on these
  numbers. Next: more measured data and read-only calibrated
  precision/coverage frontier, reversible pilot, then optional expansion.
- **Outstanding physical-phone blocker #2 / #32:** Installed native v0.2
  APK was signed with a runner-generated debug key. CI still showed the
  no-stable-key notice; a new durable key may not match phone v0.2.
  Owner involvement is needed for keystore/secret configuration and any
  actual device migration. `scripts/check_apk_upgrade_signing.py` verifies
  public signing certificate, package, version; synthetic
  `scripts/validate_native_people_backup.py` and
  `scripts/export_native_people_recovery.py` exist but **do not** extract
  phone-private data themselves. Before any update/uninstall, obtain an
  independently verified restorable backup and exact installed signer
  comparison. Never uninstall the installed app merely to fix signer mismatch.
- **Highest-leverage unblocked engineering:** develop a no-upload,
  read-only on-device backup/signature compatibility probe and isolated
  synthetic emulator migration fallback; improve V4 holdout/safety
  calibration without committing real face data; implement a debuggable
  viewer timing trace and Telephoto A/B spike behind an experimental flag.
  No direct S24+ APK or user-private People DB actions without owner
  authorization.

---
**Snapshot:** 2026-10-14, late morning Europe/Amsterdam. **Authoritative state: live GitHub `main`, branches, PRs and CI, not this snapshot when newer.** Repo: [LPStruiksma/atlas-native](https://github.com/LPStruiksma/atlas-native). Main feature SHA at handoff: `999184dda5ad3b17bdac47cd7c900432f5c4b7b2` (plus this handoff-document edit). Roadmap: [#12](https://github.com/LPStruiksma/atlas-native/issues/12); original mission [#6](https://github.com/LPStruiksma/atlas-native/issues/6). This file is a bootstrap, not an authority override.

## Start here in any NEW ChatGPT Project conversation

1. Fetch live `main`, open PRs, CI runs/jobs/logs, GitHub issues #2, #6, #9, #10, #12, #20, and `tools/v4/README.md`. **Do not rely on model memory or this dated file alone.** Note that the top-level README originated from an early foundation and contains stale installation/build claims; actual source and current CI supersede it.
2. **Latest milestone DONE:** [PR #28](https://github.com/LPStruiksma/atlas-native/pull/28) merged as `999184dda5ad3b17bdac47cd7c900432f5c4b7b2` after its People UX checks (run 38538792122) **and Android emulator/UI tests** (run [38538792176](https://github.com/LPStruiksma/atlas-native/actions/runs/38538792176)) succeeded. Live V4 CSV document sync is now on `main` alongside merged MediaStore resume optimization. Superseded [#27](https://github.com/LPStruiksma/atlas-native/pull/27) closed with explanation. **First engineering priority in the next chat:** verify post-merge `main` CI, get the live V4 `auto-eval` private read-only calibration path ready, then implement safe data-preserving native upgrade before device installation. Do not claim merged code is installed on the phone.
3. Continue [stable signing issue #2](https://github.com/LPStruiksma/atlas-native/issues/2), V4 real-device `auto-eval` holdout, safe native upgrade, native/V4 sync, then multilayer face+body+session identity [#10](https://github.com/LPStruiksma/atlas-native/issues/10). **Keep coding, PR reviewing and testing autonomously when reversible; don't end with generic planning if unblocked work is available.**

## Intent, philosophy, and project boundary

- ATLAS is the user's high-quality native Android gallery and private local photographic intelligence ecosystem, designed to rival Samsung Gallery smoothness and combine **stable random/size sorting**, fast viewer/filmstrip, People albums, efficient B&W classification, face and eventual body/context recognition. Native app is Kotlin/Jetpack Compose, package `nl.atlas.gallery`, tested via GitHub Actions Android SDK emulator and user Galaxy S24+; Python V3/V4/V5 run locally in Termux.
- User wants **breakneck but evidence-backed** development. Work on as many free/reversible, non-sensitive blockers as practical, create/merge passing PRs, fix CI, deliver polished UX; avoid asking for needless permission. Human involvement only for irreversible destruction without recovery, monetary spend, outside commitments, credential/key generation/setup, core project authority changes, and **identity ground-truth approval when needed**.
- **Core UX correction:** The user emphatically wants Google-Photos-like recognition. After each new scan, confident faces should **automatically appear under existing People** rather than repeatedly demanding per-person confirmation. Proposals with weak/contradictory evidence abstain; no automatic merges of already named profiles. Accidental *creating new person* is cheap and should be **one name entry/one save**. Accidentally assigning/merging **into an existing person** requires a brief visual destination/face count confirmation. Avoid autofocus opening Android keyboard over People picker.
- Protect privacy: never commit/upload photos, biometric embeddings, actual names, user-specific face database, phone content, signing secrets to GitHub. Existing original photographs never deleted/overwritten by inference. V3 moves only reviewed/authorized and journaled.

## Verified merged progress on GitHub at snapshot

- Native: #5 viewer warm adjacent previews/center-snap filmstrip; #11 filter confirmed People albums to exact **B&W folder**, not to model-predicted monochrome; #15 accessible vs unmatched V4 photo paths; #14 valid PNG emulator evidence; [#24](https://github.com/LPStruiksma/atlas-native/pull/24) read-only MediaStore observer and avoid expensive unchanged gallery reload upon resume, includes race handling; merged 2026-10-14 ~08:52 UTC. Main Android CI green after merge.
- V4 People UX: #13 searchable visual person picker, existing-person attach, preserved pagination/scroll, Select page; #16 explicit confirm before changing existing identities and better quality-ranked, high-res **manual-choice People portraits**; #17 one-step NEW person creation, while merge/attach remains confirm-gated; #18 show known-profile candidate portraits from provisional Discover groups; #19 suffix-proof Termux installer and truthful server-live job status.
- V4 auto recognition: [#21](https://github.com/LPStruiksma/atlas-native/pull/21) strong automatic identity assignment to already-named People after new photo scans, requiring >=3 manually confirmed *distinct reference photos*, two supporting cosine matches (heuristic thresholds .78/.73) and margin .14, image conflicts/rejections/quality gates; `review='auto'` provenance, undo and no cascading from automated labels. [#22](https://github.com/LPStruiksma/atlas-native/pull/22) **read-only private in-memory SQLite holdout benchmark** via `auto-eval --limit 500`. [#23](https://github.com/LPStruiksma/atlas-native/pull/23) 25k-face historical backlog with checkpointed 500-face chunks, invalidated by confirmed-label changes, undo/dry run. **These pass synthetic CI, but real-world precision/coverage on the user's photos is not yet measured**: do not claim model calibrated or safe at scale.
- [#25](https://github.com/LPStruiksma/atlas-native/pull/25) optional persistent debug signing via 4 GitHub Actions secrets, preflight public SHA256 APK certificate comparison tool and [docs/STABLE_SIGNING.md](STABLE_SIGNING.md). **Key not created/configured** as far as verified; no guarantee newly signed candidate matches installed APK.
- [#26](https://github.com/LPStruiksma/atlas-native/pull/26) downloadable **ATLAS-V4-Preview** Actions bundle with current Python V4 preview, Termux stable installer, SHA256 hashes, commit manifest; V4 Python-only pushes no longer trigger expensive Android emulator unnecessarily.
- PRs **#7 (old performance)** and **#8 (old People path coverage)** are closed as superseded. Native #27 still OPEN but superseded by #28; do not merge both.

## Active development work at snapshot

- [#28 Native live V4 CSV document sync](https://github.com/LPStruiksma/atlas-native/pull/28), **merged and green in GitHub CI**: local, opt-in V4 `native-sync` continually rewrites one stable CSV path on scan, tagging, merge, backfill and undo with row-count/SHA-256 footer. Native People **Link live V4** uses Android SAF persisted read permission to that document and validates completeness/hash on resume; replaces its private People copy only when valid changed export arrives, retains last good copy if invalid; manual Import once deliberately unlinks. No internet/all-files access. **Not yet installed/trialed on real S24+**; SAF URI preservation in the field remains to be validated.
- Native installed on user's Galaxy S24+, foundation v0.2 (app Gradle `versionCode=2`). **Do not distribute the latest APK as a safe upgrade until its signing identity matches existing app and app-private People data can be preserved.** [Issue #2](https://github.com/LPStruiksma/atlas-native/issues/2) remains open. Don't uninstall to fix a signer mismatch. Stable signing code exists but credentials/owner setup not verified.
- V4 currently on phone: user has named substantial numbers of profiles (recent screenshot around 71 named, 3,243 images indexed, 12,857 face detections, 788 provisional groups, 12,027 unassigned BEFORE further manual tagging). Counts are stale and not necessarily unique faces. **We cannot inspect live on-phone jobs from GitHub**. The running Termux script is NOT automatically updated when GitHub merges a new preview. Use local Overview `http://127.0.0.1:8767` for live job status or updated `python ~/atlas_v4_latest.py status --port 8767`; separate CLI jobs require inspecting their own Termux processes.
- Stable installer `tools/v4/install_latest_download.sh` picks newest `monochrome_studio_v4_*preview*.py` including Android ` (N).py` suffix and copies to `~/atlas_v4_latest.py`; keeps `~/atlas_v4_previous.py`. Release bundle instructions in [tools/v4/README.md](../tools/v4/README.md). **Back up** before switching V4 server; pause/finish active scan and stop old server on 8767 before launching preview. V4 private DB `~/.monochrome_studio_v4/people.sqlite3`.
- V4 data-grounded calibration command on phone (once newest preview installed; does **not** change People DB): `python ~/atlas_v4_latest.py auto-eval --limit 500`. Collect the anonymous numeric summary (correct, wrong, abstained, precision, coverage) for threshold decision. Only after this and review try real auto indexing/backfill. There is an `auto-match --preview` mode.
- V3 B&W sorter `python ~/bw_studio_v3.py serve` locally at `http://127.0.0.1:8765`. Most recent known scan: ~63,045 images, 47 moved by strict/user-approved process; **near gray ~301, toned/sepia ~12,674, selective colour ~195** remained pending review in their **original configured source folder**, not automatically moved. V3 `status` shows true up-to-date counts; V3 Review categories and manual approvals then preview/commit move. Native B&W filter is folder-based; it does not move or infer greyscale.
- V5 local semantic search/server 8775 is separate and NOT implemented as multi-cue person/body reidentification. Roadmap #10 covers future explainable face, body proportions, clothing, temporal and photo-session evidence; start with same-session front/back pairs, then evaluate across years with explicit abstention.

## Immediate next actions and gates

1. **#28 already merged** after full green gates and #27 closed. Verify latest `main` CI after this merger; next native release work is signing compatibility and preservation of app-private People data, then S24+ local SAF sync smoke test when safe.
2. Prepare true on-device V4 preview **without overriding live Termux**; ask user for only the minimal necessary on-phone read-only evaluation output when ready. Favor existing GitHub Actions artifact latest and no phone photo uploads.
3. Prove safe native APK update: one permanent private debug key configured by user outside repo and GitHub source, compare installed/new signer, two successive signed emulator APK upgrades with synthetic People data preservation. If mismatch, don't uninstall; investigate data-preserving migration/export before real phone change.
4. Test native SAF live CSV syncing under actual Android S24+ conditions and atomically/reliably update on V4 scan completion; no silent status claims or invalid partial imports.
5. Improve fast People matching accuracy benchmarks, correction UI, and later body+session identity model; ensure no accidental cross-identity merge.

## Handoff rule

Each new assistant turn should:
- Reconstruct **live** state (main, PRs, CI, issues) rather than assume previous snapshots are current;
- Choose the **highest-leverage, unblocked, reversible task** and begin coding/testing autonomously;
- Maintain real performance/quality and safety gates, disclose honestly untested runtime behavior;
- Keep concise milestone/status updates while developing, do not promise background activity without a scheduled task;
- Never require user approval for ordinary free, reversible GitHub development, but ask before touching credentials/signing setup, cost, destructive device data or consequential external actions.

**Historical note:** the initial snapshot's PR #28 instruction has been superseded by the reconciled section at the top; check live GitHub before selecting work.
