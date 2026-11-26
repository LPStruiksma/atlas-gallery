# DESIGN-0E — historical FLOW-1 D1 pinch evidence

**Historical source only; not a current-head score or full D1 acceptance.** Audit by chatgpt/dev, session pull-13ad38d1, for [#1033](https://github.com/LPStruiksma/atlas-gallery/issues/1033). Scope: the pinch subset of M (motion), with incidental content visibility observations relevant to S. See [D1 and checklist](../../product/DESIGN_POLISH_CHECKLIST.md). All nine current D1 cells remain unknown.

## Provenance and method

- [FLOW-1 #711](https://github.com/LPStruiksma/atlas-gallery/issues/711), [PR #1010](https://github.com/LPStruiksma/atlas-gallery/pull/1010), [run 45194391159](https://github.com/LPStruiksma/atlas-gallery/actions/runs/45194391159), attempt 2, source head `cde4f91d197cedcaacc5a225665ff175f65963a7`; aggregate artifact `14835041859`, ATLAS-android-UI-evidence. [Prior receipt](https://github.com/LPStruiksma/atlas-gallery/pull/1010#issuecomment-7106698635) explicitly did not inspect pixels.
- Authenticated GitHub connector downloaded the ZIP; 2,132 entries. The aggregate `ui-evidence/parity-matrix/manifest.tsv` names the source SHA and synthetic-only captures (its separate compact-pinch row uses parity-24 frames, not these FLOW-1 samples). The FLOW-1 files below are direct members of `ui-evidence/`, not inferred from that manifest. Decoded original PNG bytes with Pillow, calculated SHA-256 on the original bytes and visually inspected all 30 selected frames in four labelled contact sheets (downscaled for viewing, not regenerated app renders). All selected files are 1080 × 2340 portrait; source uses S24DisplayRule, synthetic local fixtures, API 35 emulator. No owner media.
- Source assertions: [timeline test at source head](https://github.com/LPStruiksma/atlas-gallery/blob/cde4f91d197cedcaacc5a225665ff175f65963a7/app/src/androidTest/java/nl/atlas/gallery/featuregrid/PinchCrossFlowEvidenceTest.kt#L184-L291), [plain grid test at source head](https://github.com/LPStruiksma/atlas-gallery/blob/cde4f91d197cedcaacc5a225665ff175f65963a7/app/src/androidTest/java/nl/atlas/gallery/featuregrid/PlainGridPinchFlowEvidenceTest.kt#L117-L210). The named tests enable animator scale 1; 25/50/75 are ladder positions, not elapsed time. Settling snapshot is taken after advancing the clock 48 ms, not the exact handover frame.

## Actual frame observations

**Timeline (Pictures, 1,500 generated numbered media tiles, scrolled from four columns; paired light/dark):** inward 25 shows overlapping four-column dated rows and fainter compact tiles (e.g. 63–66 and duplicate/overlaid 82–88); 50 shows a denser layer emerging with some residual ghost numbers, particularly 98/99 and old 82/83; at 75 the smaller tiles dominate and 56–124 form a much denser grid. The settling frame retains this composition with small shifts in tile edges; the settled frame shows seven clean columns and an October 2023 separator, with no obvious empty grid or duplicated numbers. Both themes show the same overall sequence, with pale versus dark background through the crossfade. The outward 25/50/75 samples instead show enlarging tiles, translucent overlapping numbers/edges and changing date-header placement; after retreat and release, four columns return with 6 Nov and 5 Nov separators. Revert is not a committed three-column endpoint. Visible translucency/overlap is an observation, not a judgment of user comfort or an automatically failed cell.

**Plain grid (600 synthetic colour tiles, no date groups, View as controls; paired light/dark):** inward 25 shows large tiles with fading overlay edges; 50 and 75 show progressively smaller tiles with more visible columns, slight offsets and colour overlap; settling still shows the compact transitional layout. Settled frames show seven columns, Small selected, no obvious blank tile field. The toolbar and system bars are visible in every sampled frame. Dark-only outward 50 shows larger tiles and overlapping edges; dark revert shows the same seven-column image as the dark inward settled frame (identical byte hash). No matching light plain-grid outward/revert capture in the aggregate; do not claim a light/dark pair for that substate. Plain colours repeat, so visual identity tracking alone is not reliable; use the test's tag/bounds assertions.

These are sampled poses: they support a historical two-layer transition and visible content through the sampled pinch, but do not prove every intervening frame, latency, absence of all jumps, contrast, current design tokens, physical finger contact, or performance on a real phone.

## Inspected PNG inventory

All paths relative to `ui-evidence/`; dimensions 1080 × 2340 for every row. Theme/state encoded by filename; timeline and plain fixtures as described above. SHA-256 values calculated from the ZIP's PNG bytes, not from thumbnails.

| PNG | SHA-256 |
|---|---|
| flow1-light-in-25.png | df88352f40f96aee6f7b6252d0d93b0d6979002b23c30507990d83ff1e4dedf2 |
| flow1-light-in-50.png | 4eb63f86d0829fd0821a4be2241fca998340d211d41efd19f166f20873fd2214 |
| flow1-light-in-75.png | 2af7d274f3a9239da2edf1d0e105c5826ec449080d0f0100ffd12c9d804d6585 |
| flow1-light-in-settling.png | d16ea07af29f7f5c06ceee145e3540234874b81359d5956d34090ff8f003bd93 |
| flow1-light-in-settled-7.png | dcda9f2d089385e7a91363e966481c175707e0de44f0a10cd325bc421fa9c8d7 |
| flow1-light-out-25.png | c56663d4531d0a731d7a310e2efaca466f9d745eb7df00f18c8aef65d73156de |
| flow1-light-out-50.png | d98e7919d59ba4ae643bc6672dee529336601ce90b523d523059ef928e269a7c |
| flow1-light-out-75.png | 6c33f9e5ec6409fbded3f212464ee88171c304d1c152f3e2e917c9274eb8a2fd |
| flow1-light-revert-settled.png | 38078e6a88d30686b6cd86689925e64fa5f90937af677fa6bf799d25272c71bc |
| flow1-dark-in-25.png | 4b6089c980dfa3a3819db64b7323ff87b09261c3c6cc6dc1717e6c568ae3389e |
| flow1-dark-in-50.png | 87a76fe5b5e88b1b52edeaea334ddc55618a2d7d2338110011bebf01b71dd435 |
| flow1-dark-in-75.png | 48a84357c6d7bb8c4f1fdd5f5f18b4313469fbbb3f68f56791399af886905c1d |
| flow1-dark-in-settling.png | 03400c084ac6a7648a682a80f27f6e2fcd7bee36e231613790bd8e8359472c37 |
| flow1-dark-in-settled-7.png | d0e1559b9861722248a1e3fdae3431984766d1e6507d453868bd00f0dec9f782 |
| flow1-dark-out-25.png | 9d7d6b08daf2e420e6f1d1c53071783cf844428d2ea122358f8fff74bddb0574 |
| flow1-dark-out-50.png | 0df8a326ff91eebfb1d9e24b75082bd0d1b006651ee6090456dcc8272fee1952 |
| flow1-dark-out-75.png | efde814fb94aa7e872fc21cded9d87866509e131674ec59c9586b071df51ad51 |
| flow1-dark-revert-settled.png | b0d2a4df6f17bac836a804c8d8ad27c4542116da12c8f5d13ee1bb078f217f42 |
| flow1-plain-light-in-25.png | 0db43192b39132feea763c68aca6216bdc983b2a7615400a3e27f6ae5b717198 |
| flow1-plain-light-in-50.png | 42e7dd345d605fda8f8e3a322168c0a460a395478495f0d37bd4a29ec56eca39 |
| flow1-plain-light-in-75.png | 68d0db2e3b3469b14a6cfedc3b729ae10a9fbf1697ccf273b2d567e62a72f3fd |
| flow1-plain-light-in-settling.png | e228a5c3c6ccfb8dac4b83e7ed996c24764d2936c34953404eebc0864b257e2f |
| flow1-plain-light-in-settled-7.png | 43d8d6ca6e347bc2944d4e794fe1b231f3fd0c5d4f582b9fc677b38bd8954073 |
| flow1-plain-dark-in-25.png | 4378dff0c3608cfe0a36199bf124b6d88606600c6ac2a64ddfbb3e24881eb4ab |
| flow1-plain-dark-in-50.png | 31f06e4bb0e05f0d26e0056ad66883b520ffedc2a20e3133a5a7e4c32255a002 |
| flow1-plain-dark-in-75.png | 1283161fad5ce0cb9ae298e41a2d630c75459306e60bd9a38522932a8e10194f |
| flow1-plain-dark-in-settling.png | adfd696fe555ebfdd966a861df729ec7995bdcf37d89220f21edd48e6f1f30ae |
| flow1-plain-dark-in-settled-7.png | 349bae7df143cdcda6eb041aceefdf1e90c06f00f394b83f88f6feed3c9df68f |
| flow1-plain-dark-out-50.png | 2bcd8aa3ccb62079e96ada9529e99d412644184108328a20365600301dae05b8 |
| flow1-plain-dark-revert-settled-7.png | 349bae7df143cdcda6eb041aceefdf1e90c06f00f394b83f88f6feed3c9df68f |

## Historical task assertions and reports (distinct from pixel observations)

The aggregate includes both `shards/ATLAS-android-UI-evidence-shard-{0,1}/app/build/outputs/androidTest-results/connected/debug/TEST-emulator-5554 - 15-_app-.xml` files, timestamps 2026-11-23T15:08:51 and 15:40:32 respectively (XML timezone unspecified). Both have zero failures/errors (384 tests, 13 skipped; 340 tests, 14 skipped). The four named theme journeys below have testcase entries without failure, error or skipped children. The per-test logs are in the same directory under `emulator-5554 - 15/logcat-nl.atlas.gallery.featuregrid.<Class>-<method>.txt`. Read the `ATLAS_SCALE` JSON reports; stdout and logcat duplicate the same report, not separate trials.

| Grid / theme | Shard / method | Mean absolute channel difference | Pixels over 32 (%) | Anchor top/left delta (px) |
|---|---|---:|---:|---|
| Timeline light | 1 / PinchCrossFlowEvidenceTest-pinchFlowsBetweenLevelsLight | 2.8 | 2.8 | not logged |
| Timeline dark | 1 / PinchCrossFlowEvidenceTest-pinchFlowsBetweenLevelsDark | 1.9 | 2.2 | not logged |
| Plain light | 0 / PlainGridPinchFlowEvidenceTest-plainGridPinchFlowsLight | 0.8 | 1.1 | 0 / 0 |
| Plain dark | 0 / PlainGridPinchFlowEvidenceTest-plainGridPinchFlowsDark | 1.1 | 1.4 | 0 / 0 |

All four reports state emulator=true and both overlay_frame and grid_frame=true. Source tests require mean channel difference <40 on the viewport; these reported values pass. Exact last-overlay and first-grid bitmaps are measured in instrumentation and recycled, not the sampled PNGs above; this audit did not independently recompute that metric.

Timeline source asserts cross-layer presence mid-spread, revert to four columns, commit to seven, anchor tag still present and finger Y within its bounds (±1 px), then top and left stable within 1 px after a virtual second. This is not an X-centred finger-lock assertion. Plain source asserts cross-layer presence at half pinch, seven columns after release, anchor Y inside settled bounds, overlay-to-grid anchor top/left/width each within 1 px, and late vertical stability within 1 px. Dark plain revert preserves anchor visibility. The passing XML and logs support those historical assertions, not an independent replay.

Additional report-only evidence (not visually audited here): passing XML entries for moving-finger tests on both grids (source asserts final vertical point within 2 px and late vertical stability within 1 px), reduced-motion cut (scale 0, no remaining layer, seven columns, anchored/still), and timeline top/bottom, cold neighbour, tiny/empty, selection, landscape and 150k library cases. Do not substitute those reports for paired visual checks of those states. The 150k test log (shard 0, PinchCrossFlowEvidenceTest-hugeLibraryPinchFrameTimes) reports 150,000 synthetic rows / 64 shared 32px images, 57 frames, median 67.3 ms, p90 166.8 ms, p99 233.5 ms, 100% over 16 ms; scroll p90 316.8 ms. Per-kind p90: bitmaps 2.7, pose 0, geometry 0.1, draw 1.2 ms. Source's relative emulator threshold and per-kind <=16.6 ms pass; this is emphatically not proof of smooth 60 fps or device timing. Only a fresh measured device/paired runtime record can establish that.

## Separate uncertainty: fling and remaining D1 work

[Attempt 1 triage](https://github.com/LPStruiksma/atlas-gallery/pull/1010#issuecomment-7105041541) records fourColumnFling: no scroll on throw 1 (before=0.0, after=0.0); [attempt 2 handoff](https://github.com/LPStruiksma/atlas-gallery/pull/1010#issuecomment-7106070715) records unchanged-head pass. Shard 1 XML confirms the latter. Cause remains unestablished; neither successful rerun nor pinch screenshots resolve the original fling anomaly. Keep it separate from the sampled pinch progression.

D1 M has bounded historical evidence for sampled pinch transitions, anchor/handover and report-only reduced motion. Full M is unknown: no independent continuous timing/350 ms or actual-device smoothness measurement, no complete paired edge/selection/cold/multi-level/reduced-motion visual audit, no verified fling reliability. S likewise remains unknown outside sampled visible content. T/R/C/I/G/E/H require separate checks; no current-head typography, radius, contrast, insets, glyphs, accessibility or feedback verdict. Original-ratio pinch/rows (#712/#661), held-sphere fast scroll and atlas overlay (#659) need their own current-head paired action proof (including accessible list, jumps and dismissal). No historical result is promoted to the current scorecard, no ledger edit or full row verdict. Independent Review should download this same retained artifact, inspect the actual PNGs and compare hashes and source assertions; current docs PR CI is a separate requirement. No new emulator run or local repo tests performed.
