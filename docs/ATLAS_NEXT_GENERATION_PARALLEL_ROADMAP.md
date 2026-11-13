# ATLAS Next Generation — full Samsung parity research reassessment and parallel execution plan

**Coordinator decision proposal · 22 October 2026**  
**Context:** user-owned private hobby gallery, Galaxy S24+ preview already installed and personally evaluated. ~150,000 private media, no personal Linux PC, managed work laptop excluded, GitHub Actions ~$8 remaining according to owner. **Zero implicit spending permission.**  
**Source authority:** complete Samsung Gallery parity research study (private research archive), 187/187 finding-level independent triage (private research archive), [earlier critical engineering review #372](https://github.com/LPStruiksma/atlas-native/issues/372), [previous product brief](ATLAS_NATIVE_EXPERIENCE_DIRECTION.md). All trace back to reported Samsung Gallery v15.9.00.45 static parity research, **not** runtime measurements. Concrete ATLAS code review anchored to main `75c3092d72450d555cef0bb4487a0dfbbd5a0349`.

> **Decision:** Reorganize by **product subsystems**, not the former Android-release A–E lanes. Preserve scientific work and genuine signing work already completed. Do not confuse PR planning with always-running agents. Each ChatGPT conversation runs only when activated. This is a development roadmap; it is not authorization for phone modification, money, key material, private photo access or core instruction changes.

## Executive product thesis

**From functional technical preview → delightful, extremely fluid, intelligible private gallery.** Samsung teaches interaction architecture; ATLAS wins through explainability, private user-controlled intelligence and archive tools Samsung lacks or restricts. The experience should start from *photos*, not a metrics dashboard or import wizard.

### Nonnegotiable user journeys

1. Open **Photos** into a visually compelling, promptly populated timeline. Pinch out/in smoothly through density levels; the focused image remains under the fingers and the largest view respects authentic image proportions. Scrub to an old year without a decode storm or losing place.
2. Tap a photo: get a quick preview, then crisp zoom with tiled decode on 50/200 MP originals and correct pan/page transitions. Swipe the filmstrip continuously and resume videos. Back returns to the **last viewed** photo's grid context.
3. Open **People** and immediately understand whether face discovery has ever run. Offer a real **Discover faces** path (eventually periodic idle maintenance), progress/pause/resume and reviewable unknown clusters. Distinguish **face location**, **identity proposal** and **human-confirmed person**. Importing confirmed V4 is an optional interoperability tool, **not** a substitute for built-in discovery.
4. Open **Albums/Search**: photograph-led covers, natural ordering, meaningful suggestions, recent searches, People combinations and clear results. Photos/Videos/All, Largest and deterministic Random remain available.
5. Open **Find duplicates / Declutter**: see explainable exact and near-duplicate *families*, quality/keeper rationale, a safe persistent `Keep` decision and OS-consented reversible trash. Nothing auto-deletes source photos.
6. Across **150k-item** scale, never leak private imagery to repository/CI and never let a revocation reveal cached photos again. User can export personal People decisions and update an installed app without accidentally destroying them once a durable signer is chosen.

### The 6 parity research areas — actionable evaluation

| Appendix | Underlying Samsung lesson | ATLAS gap/strength and our decision |
|---|---|---|
| A. PERF | Debounced/invalidated image work; thumbnail size tiers and caches; exact scrub mapping; instant preview | **Build carefully.** Existing app has a flat lazy Compose grid, Coil requested at 400 px and no grid scrollbar. Viewer already has some selective prefetch. Benchmark local thumbnails rather than adopting Samsung's 1.7 GB cache, 50 ms delays or vendor CPU boosting. |
| B. GRID | Multi-depth anchored pinch, justified ratio rows, clustered date headers, album covers, selection, live updates | **Signature product feature.** Current `GalleryGrid` uses fixed `GridCells.Adaptive(108.dp)` and square `ContentScale.Crop`. Implement the gesture/focus-level model and optional explicit control; preserve user-selected view and media identity. Be careful when date grouping would break Largest/Random. |
| C. VIEW | Tiled 1:1 zoom, screen-scale preview, synchronized filmstrip, video seeking, details, return position | **Major independent lane.** Current `GalleryViewer.kt` already pages and zooms but uses non-tiled bitmap transform. Evaluate existing Telephoto *test-only* spike; do not ship it without source/license/render tests. Retain read-only media handling and correct Back. |
| D. PEOPLE | Face portraits, quality covers, human identity review; fast cached faceted search and OCR | **Two separate challenges.** Samsung uses inaccessible CMH/SCS recognition services; native ATLAS currently only displays exported confirmed V4 CSV. Build a genuine owner-controlled on-device discovery pipeline, and independently build polished review/search UI and versioned V4 sidecar interoperability. Face detection is not automatic identity recognition. |
| E. DCL | Explainable duplicate groups, persistent keeper/keep decisions, reversible trash, screenshots/B&W | **Leverage ATLAS Photo DNA.** Port review mechanics, not Samsung-private detection/Trash; current research in `tools/photo_lineage/**` is more ambitious than a same-file SHA check. No automatic deletion or unsafe quality claims. |
| F. DATA | Stable cache invalidation, dates, metadata, migrations, backups, memory pressure, safe editing | **Targeted foundations.** Current `PeopleRepository` DB v1 has empty `onUpgrade`; no native People export. Current media date fallback `DATE_TAKEN → DATE_ADDED` misorders bulk-imported photos. Add only foundations required by actual user journeys, not a compliance framework. |

### Evidence strength and why not to blindly implement the report

- **High source support / incomplete ATLAS equivalence:** Samsung's resource arrays include `1 real-ratio / 3 / 4 / 7 / 12 / 28` portrait timeline levels; ATLAS fixed 108 dp grid and V4 confirmed CSV import are independently confirmed by native source.
- **Medium source support:** the exact behaviour of pinch cross-layout fake holders, zoom rounding, face-cover offsets and several data-stamp and debounce values is inferred. Build an original UX, test usability rather than copy constants.
- **Unsupported as phone-performance claims:** Coil's cold-scroll gap, per-device HDR/gain-map handling, Samsung Trash interoperability, native 150k latency and S24+ power draw were **not measured** by this study. Compare controlled evidence on actual device only with owner participation.
- **Not portable:** private Samsung CMH face, similarity and scene tables; Quram/SemHEIF; vendor DVFS; Knox; private Smart Switch; cloud/OneDrive; privileged no-consent file writes; assistant exposure to private People; Samsung non-destructive editor store and opaque codec fixes.
- **Not a new area in Claude's study:** an independent native face-recognition model and persisted face-scanning worker. These are **our proposed ATLAS extensions**, with feasibility to be established, not Samsung functionality.
- **External Android feasibility:** Google ML Kit offers a **bundled** local face detector but says it does *not* identify people; an embedding/clustering system is separate. WorkManager can defer work on charging, battery/storage-not-low or idle constraints. See Google's [ML Kit face detection](https://developers.google.com/ml-kit/vision/face-detection/android), [detection vs recognition](https://developers.google.com/ml-kit/vision/face-detection), [model installation choices](https://developers.google.com/ml-kit/tips/installation-paths), and [WorkManager constraints](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work).

## Priority portfolio: major milestones, not 187 PRs

| Rank / horizon | Outcome with a visible benefit | First independent engineering slice | Proof needed |
|---|---|---|---|
| **1 · Experience** | Samsung-class grid feel | 3/4/7 anchored pinch + `View as` control, preserved position; separate ratio-aware large mode | Grid/anchor invariants on synthetic catalog + eventual S24+ gesture impression |
| **2 · Experience** | Polished daily photo UI | Style tokens, actual photographic empty states, redesigned navigation/Albums instead of technical Activity front-and-center | Real screenshots on synthetic media, accessibility and clear labels |
| **3 · Clarity** | People no longer mysterious | Truthful native People empty state, `Connect existing V4` secondary pathway, concept for `Discover faces` | Nobody confuses MediaStore refresh with a face scan |
| **4 · Speed** | Predictable large archive scrolling | Provider thumbnail vs Coil vs bounded cache experiment, cancellable requests | p50/p95 frame time, decode counts, bytes, memory at 150k synthetic |
| **5 · Delight** | Truly usable full-screen photos/videos | Tiled zoom test, preview reuse, strip/pager synchronization, viewer return anchor | Bounded memory with big images, video and Back |
| **6 · Ownership** | In-app facial discovery and review | Bundled-detector feasibility and private incremental index with pause/resume, confirmed V4 sidecar bridge | Real no-cloud workflow, Unknown abstention, no auto-merge |
| **7 · Archive magic** | Duplicate families + safe Declutter | Complete #391 exact-head validation; quality/lineage local evidence; explainable keeper/review deck | No nonconsensual destructive action; false-merge checks |
| **8 · Everyday power** | Search, date, albums and Places | Natural sorting, album covers, date provenance and true timeline headers; later face-facet search | Reliable sorting across copied WhatsApp images and legacy dates |
| **9 · Durability** | New People labels survive app upgrades | Migration/export architecture now; real owner-signer 3→4 proof later, explicitly funded | Real retained source data and user-controlled export |

**Priority is by user value, not by the original study's P1 marks.** A low-risk native People explainer can ship early; full face indexing is a larger research/product endeavor. Full original-aspect justified rows require valid dimensions and layout-index data. Fancy pinch cross-layout animation is optional after stable anchored layout. A 28-column year mode should not arrive as a useless wall of tiny blank squares.

## New parallel development lanes: eight feature workers plus one integrator

### Lane G — Grid, gestures & chronological exploration

**Ownership:** new `app/src/main/java/nl/atlas/gallery/featuregrid/**`, grid-focused pure Kotlin tests, optionally a separate grid feature Compose file. **DO NOT directly modify `MainActivity.kt`**—integration lane I owns it.

**First output:** self-contained, compile-oriented `GridDensityState` / level selection, focal item anchor + pixel-offset transformation, clear accessible fallback and a visible prototype for 3/4/7. Next: justification geometry, ratio-aware rows, date grouping, date scrub bubble and grouping-aware stable positions. Include deterministic large-list tests of sorting/group/header rows and immutable identity. **Depends on** Lane M for width/height/orientation and media key only for full justified mode; 3/4/7 does not need that dependency.

**parity research:** `GRID:G1–G8,T1–T6,K1`, `PERF:S4–S9,D3`.

### Lane M — Media catalogue, speed, thumbnails & data correctness

**Ownership:** `GalleryRepository.kt`, `ArchiveLogic.kt` (metadata/model APIs only), `AtlasApplication.kt`, new `featuremedia/**`, and tests. Single owner of media presentation key/record metadata; negotiate any breaking type signatures with lane I.

**First output:** benchmark-ready deterministic thumbnail path comparison and cancellation policy; strict proper media key (URI/volume/type/provider version, content revision) and revocation-safe cache rules. Add validated date fallback, dimensions/orientation, `isFavorite` if available, and source provenance without writing EXIF. Optimize 150k memory allocation only after measured bottleneck; NO unconditional 1.7 GB cache.

**parity research:** `PERF:S1–S3,S7,T1–T12,D1–D2`, `GRID:L1–L4`, `DATA:DI9,DI12–DI15,DI18,DI25`.

### Lane V — Viewer, gestures, videos & details

**Ownership:** `GalleryViewer.kt` plus new viewer-support files/tests; no `MainActivity.kt` ownership.

**First output:** bounded tiled-zoom spike with fallback, correct zoom-to-tap/pan boundary, video/audio-focus lifecycle checks, and an independently testable pager ↔ filmstrip synchronization model. Next: optimized handoff from grid thumbnail, prefetch landing photo, details sheet with lazy EXIF, last-viewed return anchor, optional motion/HDR after device feasibility.

**parity research:** `VIEW:V1–V31`, `DATA:DI16,DI21–DI23`. Do not implement Samsung vendor decoders.

### Lane P — Native People discovery & identity intelligence

**Ownership:** new `app/src/main/java/nl/atlas/gallery/featureface/**`, `tools/identity_fusion/**`, its test/protocol docs. **Continue existing V5 PR #384**, don't abandon or duplicate it. Can propose a small bundled ML Kit detector experiment through normal dependency governance; only lane I edits `app/build.gradle.kts` / manifest.

**First output:** report the smallest free exact-head validation route for #384, then research a local detector + private resumable progress/index contract (queued/pending/processed/failures), memory/battery bounds and repeat-scan idempotence. Build model quality experiments on synthetic/public consented data; never ingest user photos into GitHub. Detection results create **unknown face observations**; identity fusion is optional suggestions, never irreversible person merges. Early experiment may be opt-in before any automatic background service.

**Dependency:** Lane U owns user-facing review and optional V4 face-geometry sidecar; Lane I owns native People DB integration and permissions.

### Lane U — People & Search experience, V4 review interoperability

**Ownership:** `tools/v4/**` (formerly D), `PeopleRepository.kt` **read-only contract consumer** (Lane X is sole writer), new `featurepeopleui/**`, `featuresearch/**`, own tests. Retain the five-column V4 CSV contract and existing user decisions. **Continue optional #395 if reproduced and useful.**

**First output:** semantic native People screen states: unindexed, scan available, scanning, review required, profiles; human-friendly confirmed V4 import as optional route; truthful explanation while native detection is absent. Prototype portrait crops from verified face boxes/cover sidecar; query chips, recent search and co-occurrence filters without pretending Samsung semantic search exists.

**Dependency:** P's detector/index schema and M's reliable media key; do not block useful UI prototype on P's scientific experiments.

### Lane C — Photo DNA, curation & safe Declutter

**Ownership:** `tools/photo_lineage/**`, new `featurecuration/**`, curation tests. **Continue existing draft PR #391** with full synthetic unit tests and `ci_gate.py` on a verifiably unbilled route. Existing `DeclutterScreen.kt` is owned by this lane once integration review clears; no concurrent modifications from others.

**First output:** prove the current family/graph pipeline and review gap resolver, prepare quality/keeper rationale as reviewable structured output, and design accessible exact-duplicate cluster review with `Keep`. Model hard exclusions for confirmed People, favourites, B&W and permission-restricted media. Android `createTrashRequest` remains explicit user-consented, with reversible OS Trash and restoration; never construct automatic delete jobs.

**parity research:** `DCL:1–30`, `DATA:DI5,DI10–DI11`.

### Lane D — Visual design system, navigation, albums & product coherence

**Ownership:** new `featuredesign/**` and `featurealbums/**` UI components/tokens, design brief, screenshots with synthetic imagery. **Do not edit `MainActivity.kt`**; lane I integrates. Research existing app visual hierarchy and Samsung findings; this is not a mandate to duplicate copyrighted assets/layouts exactly.

**First output:** portable Compose colour/type/spacing/motion components and photographic album cover cards, navigation proposal (Photos, Albums, People, Search; operational Activity moves to secondary status/settings), elegant empty/loading/error states, intuitive controls, readable accessibility and coherent Material/One UI tactile feel. Synthetic side-by-side screenshot comparisons if possible. Avoid a superficial reskin while behaviours stay confusing.

**parity research:** `GRID:A1–A8,F1,P1s`, `PPL:1–4,15,20,24,26` shared with U, plus independent user experience evaluation.

### Lane X — Native data integrity, local persistence & upgrade durability

**Ownership:** new `featurepersistence/**`, exclusive ownership of `PeopleRepository.kt` schema/import/backup changes (coordinate API contracts with U/I), `docs/` relevant persistence and synthetic JVM tests. Single owner of migration/export contracts; do **not** take over G3 release controls.

**First output:** forward-only, seeded native People v1 schema migration and honest downgrade behaviour; user-controlled export/import design with atomic staged validation, no cloud assumption about SAF destination, checksums and no destructive rollback; reconcile identity schema safely. Protect resume checkpoints for P and C and cache lifecycle; only actual defects should change legacy release scripts.

**parity research:** `DATA:DI1–DI11,DI26–DI31` selected items. **E (old signing lane)** remains parked. Genuine G3 permanent signer 3→4 proof is separately priced/consented only when useful.

### Lane I — Lightweight product integrator & test librarian (not a feature-development monopoly)

**Sole ownership of conflict hot-spots:** `MainActivity.kt`, `app/build.gradle.kts`, `AndroidManifest.xml`, cross-lane navigation wiring, top-level native functional/UI acceptance tests, merge ordering and source manifest. Integrator issues small functional interface contracts, not approval rituals. No independent wholesale rewrite or refactor unless required to connect a verified feature.

**First output:** establish capability interfaces and mounting points: `GridPresentation`, `MediaRecord/MediaKey`, `PeopleDiscoveryStatus`, `PeopleExportContract`, `PhotoFamilyReviewModel`, `ViewerReturnAnchor`. Workers implement modules in unique paths; I performs final Compose screen insertion in short reviews. If any worker must touch shared files, place patch suggestions in own directory or request I pickup, not simultaneous writes.

**One human-facing board:** `READY / BUILDING / INTEGRATING / WAITING-COST / DONE` with source PR, exact SHA, reproducible tests and next blocker. No fake “always-on” workers. #83 stays master historical coordination; new roadmap is authoritative for this post-preview product wave **once activated**, not a rewrite of unrelated core project instructions.

## Dependencies, interfaces and concurrency

```text
  G grid density ───────────────────────┐
  M media model/cache ──► G ratios ─────┤
             ├─────────► V details ─────┤
             └─────────► D albums ──────┤
  P V5 research ─► U People contracts ─┤──► I Android app integration
  V4 sidecar ────► U portrait covers ───┤     + bounded validation/release
  C Photo DNA → curated deck ───────────┤
  D design system + navigation ─────────┘
  X persistence ─► P progress / U labels / I safe data
```

**No need to serialize the first week of research and module coding.** Grid density state, thumbnail benchmark, tiled zoom spike, face detector pilot, V4 portrait schema, Photo DNA validation, design components and People DB migration tests can all start independently. Integrate in slices as validated. Avoid merging multiple `app/**` feature branches at once without cross-merge tests.

**Suggested practical order:** first concurrently G/M/V/P/U/C/D/X; I posts minimal interfaces and integrates a coherent first UX release containing grid gesture, People transparency and design refinement. Next release brings real ratio, viewer zoom, album cards and measured cache. Face discovery becomes a separate substantial milestone once detector privacy/performance evidence is real.

## Operational guardrails that matter

1. **No unapproved GitHub Actions spending.** The existing `build-apk.yml` has `pull_request` and `push main` path filters for `app/**`, Kotlin/Gradle settings. A seemingly ordinary app PR can trigger billable CI. Branch-only commits may be staged without a PR; workers must inspect triggers before opening PRs or merging. Consolidate unavoidable native build/emulator verification into **one bounded cost proposal** (for example a ≤$1 incremental batch, if verified within owner budget). The ~$8 headroom is NOT carte blanche.
2. **No owner-phone mutation** just because earlier preview install was allowed. Future APK updates/install/uninstalls, real photo/biometric processing, credentials, key creation, and destructive flows need the relevant explicit approval. Synthetic tests and pure source/code research are pre-approved, provided no money/credentials.
3. **No private media in GitHub** (including derived embeddings, image paths, actual People labels, thumbnails and metrics revealing identities). Avoid telemetry/network permission. Test with fictional or public licensed media when possible; real metrics are local-only and may require separate phone consent.
4. **Source quality without unnecessary process:** small meaningful PRs/branches per behaviour, tests at the granularity of risk, one concise reviewer when necessary, a single integration record; no micro-PRs that only change signoff documents, no repeated green fictional canaries.
5. **Known current separate PRs:** B Photo DNA [#391](https://github.com/LPStruiksma/atlas-native/pull/391), V5 [#384](https://github.com/LPStruiksma/atlas-native/pull/384), V4 optional [#395](https://github.com/LPStruiksma/atlas-native/pull/395). Preserve completed #396/#397 releases. Stale old E signing draft PRs remain separate parked backlog, not eight extra workers.
6. **Android physical runtime proof** is not provided by static research. Label `DESIGNED`, `SOURCE CODED`, `TESTED SYNTHETIC`, `EMULATOR VERIFIED`, `S24+ EXPERIENCED` separately; do not describe a proposed feature as released until the owner actually installs an APK.
7. **Major change to data flow** (face detection, GPS, media access revocation, backup/export) receives a targeted safety test before production, not a generic certification process. Restoring/migrating app-private new labels is more valuable than continuing to test fictional signatures.

## Lane-ready continuation prompts

These are intended for **separate ChatGPT conversations**, not autonomous processes. Old A–E chats can be reassigned by telling them the new role; their incomplete reviewed work must be handed to its new owner.

**G — Grid**  
> ATLAS new Lane G under docs/ATLAS_NEXT_GENERATION_PARALLEL_ROADMAP.md. Read full Claude/parity research study and 187-finding triage. Refresh main. Own new featuregrid/** Kotlin/Compose components only, not MainActivity.kt. First build focus-anchored pinch-density 3/4/7 plus accessible View as with synthetic correct-anchor tests, then justify uncropped ratio mode when M dimensions are available. Avoid paid Actions and private media. Publish exact branch/head and integrator handoff, not just plans.

**M — Media**  
> ATLAS new Lane M. Read roadmap, full study Appendix A/F and #372 corrections. Own GalleryRepository.kt, ArchiveLogic.kt media model and AtlasApplication.kt. Benchmark Coil actual local behaviour vs ContentResolver.loadThumbnail vs bounded private cache; design stable identity/invalidation for 150k, safe permissions, date sources and dimensions. Test synthetic only, no paid CI or phone data. Coordinate API contract with integrator, publish measurable results and focused code.

**V — Viewer**  
> ATLAS new Lane V. Read roadmap and Claude/parity research Appendix C. Own GalleryViewer.kt and viewer helpers only. First evaluate tiled zoom (existing Telephoto test-only spike), memory/correct bounds, filmstrip↔pager continuous coupling and video lifecycle. Preserve editor/back semantics. Synthetic no-cost validation, no new Gradle dependency without I coordination; hand integration patch to I.

**P — People engine**  
> ATLAS new Lane P. Read roadmap, parity research People findings and existing #384 V5 PR. Own tools/identity_fusion/** and new featureface/**. Finish free exact-head validation of #384; research a fully on-device, resumable face detection index with optional bundled ML Kit, careful battery/storage/permission status and unknown-person review. Detection is NOT identity recognition. Preserve V4 DB, no private face/photo uploads, no paid CI. Publish real benchmark/protocol and API schema for U/I.

**U — People and Search UX**  
> ATLAS new Lane U. Read roadmap, parity research Appendix D and #372. Own tools/v4/** plus new featurepeopleui/** and featuresearch/**; Lane X solely owns PeopleRepository.kt; request API changes through I. Safely complete #395 if useful. Implement truthful, beautiful People onboarding and optional confirmed V4 interoperability, portrait sidecar compatibility, reviewer UI and intuitive search facets. No fake face indexing button. No private media or paid CI. Supply clean UI interfaces to I.

**C — Photo DNA and Declutter**  
> ATLAS new Lane C (curation), formerly Photo DNA B. Read roadmap and parity research Appendix E. Own tools/photo_lineage/** and new featurecuration/**; work on existing #391 exact-head full synthetic test + ci_gate via free route. Then build source-safe, explainable photo-family/keeper review contracts and reversible Declutter UX. Never delete or auto-trash originals. Avoid paid CI and real media uploads.

**D — Design + Albums**  
> ATLAS new Lane D (design). Read roadmap and full parity research Appendices B/D. Own new featuredesign/** + featurealbums/**, not MainActivity.kt. Create a polished photo-first design language, elegant album covers/navigation and empty states with original Kotlin/Compose. Preserve ATLAS differentiators and accessible controls. Supply synthetic UI examples and small component deliverables; I integrates. No paid CI.

**X — Persistence**  
> ATLAS new Lane X. Read roadmap/parity research Appendix F and #372. Own featurepersistence/** and PeopleRepository.kt exclusively, plus targeted People DB/backup contract tests. Build forward-only v1 People migration and safe opt-in export/import with real SAF destination warning and atomic validation; coordinate PeopleRepository changes with U/I, no app installs, data/keys or spending. Avoid old G3 bureaucracy.

**I — Integrator**  
> ATLAS Lane I integrator/coordinator. Read roadmap and full parity research report; refresh main and PRs. Own MainActivity.kt, build.gradle.kts, AndroidManifest.xml and integration acceptance only. Publish minimum cross-lane interfaces; receive independent G/M/V/P/U/C/D/X features in order, keep source ownership and full gallery performance. Do not dispatch paid Actions or install APKs without owner approval. The product experience outranks release process, but protect actual photos and People data.

## First board / handoff

| Stream | Available now | Immediate first deliverable | Current bottleneck |
|---|---|---|---|
| G | Yes | Density/anchor pure component | Interface with I |
| M | Yes | Thumbnail benchmark + MediaKey | Need actual decoding measurements before cache choice |
| V | Yes | Tiled-zoom / filmstrip spike | No-cost Android test route or deferred cost batch |
| P | Yes | #384 green + detector feasibility | Full exact-head suite and model semantics |
| U | Yes | V4 sidecar/People truthful UX | Schema contract with P/X |
| C | Yes | #391 full test and quality deck | Demonstrably no-cost test runner |
| D | Yes | Design system + Album cards | Integration hooks with I |
| X | Yes | Forward migration and export contracts | PeopleRepository ownership negotiation |
| I | Yes | Shared screen/module API contracts | Native builds may bill automatically |

**First visible win:** smoothly adjustable gallery grid + photographic Albums/People framing, **without** pretending indexers or permanent upgrades already exist. Keep work parallel and integrate proven slices in a budget-conscious batch.  
