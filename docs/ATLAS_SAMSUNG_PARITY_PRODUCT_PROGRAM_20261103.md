# ATLAS · Samsung Gallery-class product parity, then ATLAS intelligence
**Product direction confirmed 3 Nov 2026 by Lukas.** This is the north star for development after ATLAS Native's successful first launch on the actual Galaxy S24+. **Do not reduce the programme to the mosaic grid.** The target is a near-complete, polished *daily-driver* Samsung Gallery experience in ATLAS's own Kotlin/Compose code, plus differentiated local-first intelligence on top.

## Authoritative state and evidence boundaries
- The **installed, owner-reported working** ATLAS release is from production RC2 `1f078d59f0ab59c6cc87256e176ad05b9cc752e4`, personally signed APK B `versionCode 4`. Owner confirmed installation and gallery loading; **do not claim all features or all 150k items have been validated on that phone**. Keep signing identity owner-held and backed up.
- **Current `main=f83689209bcfc700020685a653ae03db3970c3c8` is 95 commits/155 paths behind RC2**. The next-update *source* base is `atlas/postinstall-next-update-base-20261103` at `a55b716ce3418c8203542c8230fc9d95e587df9f`, RC2 plus only `versionCode=5`. This does NOT represent a built or install-ready APK. Never build the next app from stale `main`.
- Our principal Samsung reference is **Samsung Gallery 15.9.00.45 static parity research** in the private Samsung parity research archive, with its 187-finding triage, the live S24+ Samsung Gallery and Lukas's 3-column versus fully-zoomed justified-mosaic screenshots. Research findings are *not* runtime behavior proof and **187 findings do not cover every Galaxy Gallery feature**.
- Use a clean-room **behavioral/UX reference**, not copied/decompiled proprietary Samsung source, assets, private DB/service hooks, or confidential vendor capabilities. Platform APIs and original implementations only. Keep no INTERNET / broad storage permissions without a separate product decision.
- **This supersedes the scope/priority assumption in the Oct 22 roadmap** that all Stories/Places/advanced-media functions can simply be excluded. They are now explicit *coverage items to investigate and, wherever viable, implement or offer an honest supported equivalent*. Detailed research priority labels are input, not product vetoes.
- Reuse the **working code**: RC2 already contains a justified row algorithm `featuregrid/JustifiedRows.kt`, `AtlasRatioGallery.kt`, 3/4/7 grid density, timeline/index/scrub, viewer, albums, People/Search UI and partial supporting subsystems. The first grid task is **integration/behavior parity**, not rebuilding a justified-row solver from scratch.

## What 'Samsung Gallery-class' means
Benchmark the **user journey and tactile result** across the whole app: surfaces, hierarchy, navigation, gestures, layout, controls, menu states, animations, interactions, loading, sensible defaults and failure behavior. UI resemblance alone is insufficient; functional outcomes should match. Favor **nearly indistinguishable ergonomics** where feasible, not blindly copied pixels/assets or technical internals. When Samsung relies on system privilege or private services, achieve a user-facing equivalent with public APIs / an explicit Samsung editor handoff, or mark *vendor-blocked* with a clear gap and suitable alternative. Do not pretend parity.

One feature-parity register covers the whole user experience with columns: Samsung behavior & screen/gesture, parity research/source or observed device evidence, actual installed-RC2 implementation, status `Matched | Partial | Missing | Needs runtime confirmation | Vendor-blocked`, owner lane, next smallest implementation slice, deterministic test, visual/interaction acceptance, and dependency. Rows should be **meaningful user capabilities**, not 187 micro-PRs. New discoveries from the S24+ go into this register. Owners can verify screenshots on invented/sample media; private real screenshots stay in conversation unless Lukas permits uploading to GitHub.

## Parity coverage: ALL major areas are in scope

| Family / deliverable | Reference experience to approach | Current evidence; priority / lane |
| --- | --- | --- |
| 1. Shell, visual system & navigation | Photo-first landing, Samsung-like toolbar/bottom navigation, contextual actions, overflow menus, motion, accessibility, dark/light styling, back stack, immersive chrome | Existing shell / design present; *full visual and navigation comparison not done*. **Core · D + I** |
| 2. Photos timeline, density, mosaic & scrub | Authentically proportioned **full-in zoom level**, 3/4/7/12/28-level overview where useful, anchored multi-stage pinch, year/month/day sections, scrub-to-date, no visual jumps, filters | RC2 already has `JustifiedRows`, `AtlasRatioGallery`, 3/4/7 grid and scrub. **Integration/interaction partial, not blank-slate. Core · G + I + M** |
| 3. Albums, folders & collections | Photographic album covers, sorting, grouping, album browsing, favorite/hidden conventions, manage/move/copy/rename/create operations where Android permits, default album behavior | Existing Albums and cover chooser; full create/edit/file-ops inventory needed. **Core · D** |
| 4. Multi-select, batch actions, Trash & recovery | Long-press selection, select-range/all, shared toolbar, safe share/copy/move/favorite, trash confirmation + restore, intuitive undo and exclusions | Some Declutter/Trash and selection code exists; live end-to-end flow **unverified**. Mutations only with explicit Android consent and owner-driven UX. **Core · D + C + I** |
| 5. Image viewer & navigation | Natural open/close/back, full-screen chrome, pinch/double-tap/pan/pager handoff, filmstrip, tiled zoom, viewer-to-grid continuity, contextual action row | Existing viewer, tiles/geometry, editor handoff; compare true behavior and finish rough edges. **Core · V + I** |
| 6. Video & special formats | Inline video poster, play/pause, gestures, timeline seeking, resume, audio behavior, GIF/animated formats, HDR/HEIF/motion photo where public APIs allow, panorama/360 indicators or supported viewing | Some Media3 video viewer; broad compatibility incomplete/unverified. **Core/extended · V + M** |
| 7. Details/metadata & media semantics | Location/date/dimensions/EXIF details sheet, filenames, favorites, contextual People, safe refresh after external editor changes; correct sorting and metadata | Partial media model and details components; validate real surfaced fields and correctness. **Core · V + M** |
| 8. Search, People, Places | Search UX, suggestions/history/facets, album/person combinations, face portrait cards, filters, location clusters and map-like discovery if feasible offline | Existing Search and reviewed People UIs; native automatic discovery separate, location may need explicit media-location permission. **Core/extended · U + P + M** |
| 9. Editing and sharing | Discoverable edit/share actions, supported Samsung Photo Editor & optional Galaxy AI handoff, share sheets and sensible format/location behavior | Editor intent handoff exists; Samsung proprietary editor/AI can only be used if exposed to third-party apps. Equivalent via public APIs otherwise. **Core/extended · V + I** |
| 10. Stories, Memories, highlights, slideshow/collage | Albums and media surfaced into memorable timelines, user-created story collections, controlled media suggestions, highlights/slideshow, hide-person/date policies | **Previously excluded**, now explicitly investigate and implement a local/offline subset with honest gaps. Samsung's BGM/story/cloud assets are not portable. **Extended parity · S (new)** |
| 11. Settings, maintenance, import/export and backup | Intuitive preferences, cache/storage controls, permission states, safe recovery of People decisions, continuity across ordinary updates | Owner-signed A→B People/settings proof passed; full UX and migrations need later implementation. **Foundation · X + D** |
| 12. Cold start, 150k scale, thumbnail responsiveness | Responsive startup, fast scrolling/scrubbing, size-tier reuse, bounded decoding, graceful permission changes and revocation | Actual 150k synthetic acceptance exists, but **not** Samsung-vs-ATLAS live S24+ performance comparison. **Cross-cutting · M + G + V + I** |
| 13. Cloud, secure albums, Samsung-specific integrations | Make conscious case-by-case parity decisions about OneDrive/cloud sync, Secure Folder, hardware codecs, AI/remaster, Quick Share extras and Smart Switch | Most system-private functions cannot be replicated directly; public Android handoff / opt-in substitutes where meaningful. Never claim full equivalence without evidence. **Extended/exception register · I + X + V** |
| 14. ATLAS-only intelligence | Native private face indexing with Unknown abstention, reviewed identities, Photo DNA families, keeper decisions, explainable Declutter, semantic/time/place/People Life Graph | This is the differentiator **on top of** the familiar gallery; do not block core Samsung parity for research. **ATLAS layer · P + C + U + X** |

No area silently disappears from the plan: statuses may be *deferred*, *vendor-blocked*, or *intentional difference*, but the product decision must be visible.

## Parallel lanes: responsibilities and first deliverables
Preserve prior lane work/branches. **Chats do not run autonomously when dormant**: these are assignments ready to activate. Work in newly isolated feature branches derived from the **postinstall next-update source base** (not stale main). Respect exclusive source ownership; hand cross-lane changes to I.

| Lane | Exclusive working surface / mission | First independently useful output |
| --- | --- | --- |
| **I · Product integrator** | `MainActivity.kt`, manifest, app Gradle, RC acceptance, cross-lane integration only | Current-RC2 parity snapshot and one coherent navigation / gesture / feature API contract; decide minimal first integrated bundle |
| **D · Design, shell, Albums and selection** | `featuredesign/**`, `featurealbums/**`, new `featureorganization/**`; not MainActivity | Audit Samsung screen hierarchy, toolbar / menu states, albums and multi-selection; implement best bounded reusable Compose component and screenshot fixtures |
| **G · Grid and timeline** | `featuregrid/**` | Turn **existing** justified ratio gallery into highest zoom stop in the same pinch ladder as 3/4/7; plan 12/28 only where timeline grouping makes sense; preserve photo under fingers; tests |
| **M · Media engine** | `featuremedia/**`, metadata/caching benchmark helpers, coordinate `GalleryRepository.kt` changes with I | Verify aspect/orientation metadata actually fed to G, thumbnail request/cancellation/revision contracts and performance comparisons; fix one real correctness gap |
| **V · Viewer, video, details and interoperability** | `GalleryViewer.kt`, viewer-owned helpers/tests, editor/share entry contracts; no MainActivity | End-to-end Samsung viewer UX gap map; complete one coherent pan/pager/filmstrip/video/detail/edit/share slice with tests |
| **U · Search, People presentation and Places** | `featurepeopleui/**`, `featuresearch/**`, new `featureplaces/**`; not face engine or PeopleRepository | Match Samsung search hierarchy/facets and reviewed People flows; identify missing People/Places UX vs data APIs; deliver first real user-facing search refinement |
| **P · Native People intelligence** | `featureface/**`, `tools/identity_fusion/**` | Private on-device face-discovery roadmap with real bounded detector/indexing implementation evidence; keep detected faces vs confirmed identities separate; no auto identity merge |
| **C · Photo DNA, Curation and Declutter** | `tools/photo_lineage/**`, `featurecuration/**` | Safe duplicate/similar family review flow and keeper rationale connected to Samsung-style batch-selection/trash UI; no background deletion |
| **X · Persistence and upgrade durability** | `featurepersistence/**`, `PeopleRepository.kt`; safe private storage | Migration/export design supporting future new People/collections/Stories data while keeping current installed v4 intact; no new signer bureaucracy |
| **S · Stories and Memories (new, after core interfaces)** | new `featurestories/**` and synthetic story tests; none of D/U/V source | Investigate Samsung story UX using parity research + available public behavior; implement original local story/recap model and visual prototype; no cloud or music licensing assumptions |

**Integration dependencies:** I establishes screen and intent contracts; G consumes M's dimensions/media identity and V's return-anchor contract; D owns visual/selection UI but C owns recommendation/keeper logic; U owns People/Places UI but P/X own inference/DB; S consumes U/M/C public media summaries. Keep feature logic in owned modules, not `MainActivity.kt`; integrate in coherent batches.

## Execution waves / visible outcomes
These are **capability milestones, not calendar deadlines or mandatory audit ceremonies**. Lanes can work in parallel as long as interfaces and file ownership are honored.

1. **Wave A — Samsung-class daily photo browsing:** visual shell + timeline/zoom/mosaic/headers/scrub + viewer/video/Back + albums and common actions. Reuse RC2 code; user-perceived fluidity matters more than number of commits. Integrate one coherent build for ordinary S24+ usage when demonstrably ready.
2. **Wave B — Samsung-class library operations:** selection and batch toolbars, favorites, safe Trash/restore, album management, richer file details, search polish, share/edit launch and formats. End-to-end flows rather than disconnected buttons.
3. **Wave C — Samsung's broader experience:** People and places navigation, Memories/Stories and useful highlights, specialized video/animated media; find public equivalents for vendor-backed capabilities and mark clear exceptions. Build ATLAS native face processing in parallel.
4. **Wave D — ATLAS superpowers and performance leadership:** Photo DNA, human-reviewed identities, advanced faceted/semantic discovery, explainable curation and Life Graph; continually compare warm/cold usability and 150k-scale behavior against Samsung.
5. **Continuous polish:** animations, accessibility, dark mode, graceful empty/permission states, cache/thermal/battery responsiveness, stability and owner feedback. No "complete" claim without actual interaction review.

## Fidelity assessment (lightweight but meaningful)
Each major flow earns three separate assessments:
- **Behavior:** touch/gesture/back/navigation/result compared with Samsung Gallery on the S24+.
- **Visual:** composition, image proportions, spacing, hierarchy, chroming, animation, empty/selection states. Use synthetic screenshots and user-provided reference crops; no proprietary asset copying.
- **Quality:** permission-safe operation; large-library smoothness, battery/memory and update retention. Use measured values rather than marketing claims.

Mark a flow **matched** only after all three dimensions are convincingly demonstrated. At the end of each integrated wave, get **one** owner-visible sample for qualitative feedback, not six redundant QA scripts. Do not demand sensitive user photos for CI or require user to continuously run tests.

## P1 owner-observed regression in installed v4 — Random scrub stability

- **[Issue #400](https://github.com/LPStruiksma/atlas-native/issues/400):** Lukas observes Random photo ordering appear to reshuffle/recalculate on every scrub release on the S24+. Core Samsung-style daily-driver predictability is at stake. **Not fixed or independently reproduced yet.**
- Code audit shows `randomSeed` only increments on explicit `Reshuffle`; `sortMedia` is deterministic. The problem may instead be a catalogue/source invalidation or a timeline/filmstrip/slider scroll-state mismatch. Do not change shuffle mathematics without reproducing the real path.
- Prioritize one focused synthetic Compose/emulator Random scrub-release regression; verify exact ordered URI/selected item and no unexpected sort publication after two identical gestures, and actual source/grant continuity. Lane **G + I** for Photos timeline, **V + I** for full-screen viewer strip, **M** only if a real catalogue rebuild is proven. No real photos, paid CI or automatic phone access. Fix before optional visual polish in next in-place release.

## Immediate unblocked starts
- **I:** reconcile source baseline, create the shared **screen map / component ownership and data contracts**, define safe integration order; do not commit directly to old RC2/main.
- **D:** enumerate Samsung navigation, tabs, selection and Albums screen/action flows and current ATLAS correspondence; build first reusable visual component on its own branch.
- **G:** confirm pinch currently ends at 3 columns while independent ratio destination exists; design and implement the **ratio-as-a-zoom-stop integration interface** in G-owned code without touching host.
- **M:** check whether recorded width/height/orientation and thumbnail loads meet G/V contracts, expose only necessary missing metadata.
- **V:** complete viewer/video/details/edit/share user journey checklist against RC2, fix the largest demonstrable on-device behavior gap within V ownership.
- **U:** search/People gap audit and first self-contained UI improvement.
- **P/C/X:** continue already scoped validated research/implementation that feeds subsequent waves; no duplicate release checks.
- **S:** research/story capability interface only until I/D/U agree collection and presentation contracts.

### Guardrails that remain, not enterprise compliance
- **Same package `nl.atlas.gallery` and same owner-retained signer**, future versionCode greater than installed `4`. No signing material in source/chat/CI; no automatic phone installs.
- Only authorized MediaStore reads; no real photos, V4 Termux People DB, or private biometrics uploaded to GitHub. Android OS-mediated explicit user confirmation for destructive changes. Unknown identity stays unknown.
- No paid CI, private-device operations or credentials without owner approval. Keep unit/synthetic tests inexpensive; batch unavoidable Android tests.
- Reviewer/integrator must distinguish **source-only**, **emulator-validated**, and **owner-phone-observed** evidence. Commit with `[skip ci]` where consistent with existing no-charge protocol; no PR or main merge that unexpectedly triggers billing.

**North star:** someone accustomed to Samsung Gallery should feel at home using ATLAS for their whole photo library, then discover useful, user-controlled capabilities Samsung Gallery does not offer.


## One reusable worker launch prompt
Launch a separate ChatGPT worker chat for each lane you want active. Paste this prompt, replacing only `[LANE]` with the lane letter from the table above:

> Continue ATLAS Native as **Lane [LANE]**, using the current Samsung Gallery-class parity programme on `atlas/coordinator-samsung-parity-program-20261103`, `docs/ATLAS_SAMSUNG_PARITY_PRODUCT_PROGRAM_20261103.md`. The product north star is a near-complete Samsung Gallery-equivalent daily-driver app in our own clean-room implementation, with ATLAS intelligence on top—not just a mosaic/grid demo. Read the existing full parity research, 187-finding triage, previous lane work and live coordination issue #83. Refresh GitHub before acting; **installed working RC2 is `1f078d59`, `main` is stale, and `atlas/postinstall-next-update-base-20261103` is the future source baseline**. Stay strictly within the ownership of Lane [LANE], work on a new isolated branch off that next-update baseline, avoid duplicating already integrated code, implement the highest-value unblocked *user-visible* or necessary foundation slice, and run free real tests available to you. Do not merely write another plan. Preserve original photos, V4 data, source RC2, owner signer and current installed app; no paid CI, physical phone, main merges, secret handling or destructive actions. Publish actual tested SHA, source changes, test counts, gaps, dependencies and next handoff to issue #83. Coordinate shared host/API changes through Lane I. Keep user experience fidelity to Samsung visible as the benchmark; do not copy Samsung proprietary code/assets.

**Coordinator's recommended launch order:** reactivate I and launch D/G/M/V/U first for broad independent Samsung parity; P/C/X continue their independent underlying capabilities; activate S as soon as D/U define Stories/collections interfaces. This sequencing is about minimizing contention, not reducing product ambition.
