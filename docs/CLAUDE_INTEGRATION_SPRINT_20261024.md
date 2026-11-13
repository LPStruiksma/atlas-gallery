# Claude integration sprint — 24 October 2026

Temporary lead integrator: Claude (Cowork), while ChatGPT Lane I was paused.
Coordination: issue #83. Roadmap: `docs/ATLAS_NEXT_GENERATION_PARALLEL_ROADMAP.md`.

## Branches and exact SHAs

| Ref | SHA | Meaning |
|---|---|---|
| `main` | `f83689209bcfc700020685a653ae03db3970c3c8` | unchanged |
| `atlas/lane-i-photo-first-shell-20261022` | `4c26b34906b25b6be493d1f1dc59536e1f25776e` | **start point**, untouched |
| `claude/integration-sprint-20261024` | `91439e057de6bfd999f817aefe9814646ee0ded4` (code) + this docs-only commit | **first gallery candidate** |
| `claude/next-gen-slice-20261024` | `736466ea` | candidate + Lanes P/X/C + V native tiles. **Not** for the first release |

The candidate is a strict fast-forward of Lane I `4c26b34`. No lane branch, `main`, PR, workflow, secret, phone or private media was touched. Every commit carries `[skip ci]`, and all push triggers are `main`-only. **No GitHub Actions ran.**

## What was actually executed (real tools, no shims)

Toolchain: owner's Windows host with a portable, single-folder install (`C:\Users\LStruiksm\atlas-buildtools`), $0. Temurin JDK 17.0.20.1, Gradle 8.13, AGP 8.13.2, Kotlin 2.4.10, SDK platform 36, build-tools 36.0.0 (+35.0.0), emulator 37.2.12 with WHPX, API 35 `default/x86_64` image, AVD `atlas-api35` (pixel-6 geometry).

At candidate code `91439e05`:
- `gradle clean`, then `gradle :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon --stacktrace --no-build-cache`: **BUILD SUCCESSFUL**, **572/572** JUnit tests (82 suites).
  - `app-debug.apk` sha256 `bad7e7baf9155afe85be6407abbfaa621e8fdd1fda1f18a7bda56786da100ae9`. It is signed with a **throwaway local debug key** (cert sha256 `017620f6…9b49b5`) and is **not for the phone**.
- Python gates on an LF export: signing boundary 6/6, `verify_contract.py` **15/15**, `test_verify_contract.py` 14/14.
- API 35 emulator, `connectedDebugAndroidTest`: **65 methods / 28 classes, 60 passed, 5 skipped by design, 0 failed, in each of 4 consecutive full runs**. The skipped ones are the 4 `PeopleUpgradeTest` phases (run separately below) and 1 opt-in benchmark.
- Real `adb install -r` upgrade, main `f836892` APK → candidate (same signer): synthetic People SQLite **and** settings preserved (`PeopleUpgradeTest` seed → verify OK).
- Real-app smoke: 120 synthetic JPEGs (EXIF dates spanning 4 years, 5 folders) inserted into the emulator's MediaStore, plus `pm grant`. Checked visually via screenshots: Photos timeline (day/month headers), View as Large/Medium/Small, Original proportions, Albums, album → viewer → swipe → Back, cover chooser, Declutter (nothing staged or trashed), People, Search.

## Defects found and fixed (each re-verified)

1. `ff67209`: two real Kotlin compile errors in `MainActivity` (unclosed `Row(`; nonexistent `::isReviewedPrimaryImage`).
2. `3baf9b0`: four broken JVM tests (digit-leading name, nonexistent `isCurrent()`, missing `volumeOf`, video expectation contradicting `6f833df`).
3. `5cfb973`: Lane D `*OrNull` APIs **threw** for a negative epoch, because a default `AlbumCoverPermissionScope(accessEpoch)` argument ran before the fail-closed body.
4. `9ef87d5`: integrating G's numeric lazy keys silently broke I's Timeline↔grid anchor (`TimelineLayoutSwitch` parsed `media:<uri>` from keys). Fixed with an `onDisplayedSlots` hook.
5. `7d29862`: U's androidTest imported member functions (`assertExists`) as top-level symbols.
6. `ae5ef75` (viewer, emulator-found, pre-existing in Lane I):
   - (a) `pointerInput` keyed on `viewport`/`decodedSize` restarted when the HQ decode landed → lost taps, cancelled pinches;
   - (b) input modifiers after the scaling `graphicsLayer` → zoomed pan at 1/scale of finger speed;
   - (c) `scrollToItem` inside a measure pass → "performMeasureAndLayout called during measure layout" crash.
7. `fedde79`: whole app shell rendered headings/icons **black-on-black** (Scaffold `contentColorFor(DeepBg)` fell back to black).
8. `91439e0`: edge-to-edge (targetSdk 36 on Android 15+). Viewer Back/title sat under the status bar and the coarse seek bar under the navigation bar. Cover chooser and Declutter had the same problem.

## Lane intake into the candidate

| Lane | Head taken | Taken | Not taken (why) |
|---|---|---|---|
| G | `41e02e0` | numeric lazy keys (3 galleries), `GridLazySaveableKey`, `RecentGridDensityPlans`, `matchesMeasuredViewport` + tests | — (I added the `onDisplayedSlots` hook) |
| V | `da6bef4` | positional pager/filmstrip keys only | native region tiles → next-gen slice |
| D | `f674b7a` | full `featurealbums/**` + tests | — (default-scope crash fixed) |
| M | `320d58a` | bounded V4 CSV read, row-ID admission, date admission + tests | — |
| U | `28f01ac` | picker admission, review-screen keys, `AuthorizedUnknownReviewProjection` (unmounted) | — |
| P/X/C | `243c19d`/`d17c03a`/`eb89414` | — | next-gen slice only |

## Next-gen slice (`claude/next-gen-slice-20261024`), real evidence

- P compiles against the real bundled ML Kit AAR (`face-detection:16.1.7`). X needed one Kotlin 2.4 fix (`arrayOf<Any>`).
- Unit tests **751/751**: C `featurecuration` 131 (7 stale fixtures fixed: non-numeric MediaStore ids), X `featurepersistence` 36.
- API 35 at `073d710` (P/X/C, before V tiles): **109 methods, 104 passed / 5 skipped / 0 failed**. This includes the first-ever runs of X `FaceIndexStoreTest` 34/34, `CurationKeepStoreTest` 3/3 and `PeoplePersistenceMigrationTest` 7/7.
- At `736466e` (+ V tiles): 118 methods. V `ViewerRegionDecodePrototypeTest` passed 14/14 when run on its own.
  - **Two full-suite runs each hit one intermittent failure.** The first was a `ViewerDetailsSheetTest` 8 s timeout (it passed 4/4 in isolation). The second was a Compose `SlotWriter.moveSlotGapTo` `ArrayIndexOutOfBoundsException` during activity teardown after `AsyncGallerySearchTest`, which crashed the instrumentation process.
  - The candidate showed **0 failures in 4 consecutive full runs**, so the slice needs this investigated before it is promoted.
- Real `adb install -r` main (People DB v1) → slice (X v2): synthetic People preserved.
- V tiles in-app: an 8000×6000 JPEG zooms and the native region decode runs without a crash. The visual gain at 2.5× was negligible; ≥5× pinch was not exercised.
- P's Python reference `test_face_index_reference.py`: **25/27**. `test_ignored_catalog_insert_cannot_publish_partial_index` and `test_equal_catalog_counts_do_not_authorize_wrong_membership` expect `queued == 1` after recovery but get 2. Unfixed, for Lane P.

## Not verified

S24+ hardware, Android 16 / One UI, 150k **real MediaStore** scale (only in-memory 150k unit tests), API 34, ≥5× pinch, video playback on real codecs, TalkBack, owner stable signer and any phone install.

## How to reproduce on this laptop (cmd.exe)

```bat
call C:\Users\LStruiksm\atlas-buildtools\env.cmd
cd /d C:\Users\LStruiksm\Documents\atlas-native
gradle :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon --stacktrace
rem headless API 35 emulator:
C:\Users\LStruiksm\atlas-buildtools\android-sdk\emulator\emulator.exe -avd atlas-api35 -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot -port 5554
rem ALWAYS target the emulator only:
set ANDROID_SERIAL=emulator-5554&& gradle :app:connectedDebugAndroidTest
```

Gotchas: put `TEMP` on a long-name folder (already done in `env.cmd`; the 8.3 short TEMP path breaks JDK selectors). Never reference `%VAR%` in the same cmd line that `call`s `env.cmd`. Uninstall the app before switching between the candidate (People DB v1) and the next-gen slice (v2), because downgrades crash.

## Resume instructions for ChatGPT Lane I

1. `git fetch origin`. The candidate is a fast-forward of your branch: `git checkout atlas/lane-i-photo-first-shell-20261022 && git merge --ff-only origin/claude/integration-sprint-20261024`. Nothing is lost and no rebase is needed. Or continue directly on `claude/integration-sprint-20261024`.
2. Keep the next-gen slice separate until the first candidate is signed and installed. Then merge or cherry-pick `claude/next-gen-slice-20261024` (2 commits on top of the candidate).
3. Lanes must port these fixes back to their own branches, otherwise the next intake reintroduces the bugs:
   - **V:** `ae5ef75` (identity-only `pointerInput` keys + `rememberUpdatedState`, input before `graphicsLayer`, `requestScrollToItem`) and `91439e0` (viewer insets). Also decide on EXIF orientation 0.
   - **D:** the nullable-scope fix in `5cfb973` and the two test fixes.
   - **G:** adopt the `onDisplayedSlots` hook from `9ef87d5`.
   - **U:** the androidTest import fix from `7d29862`.
   - **X:** the `arrayOf<Any>` and `1000L` fixes from `073d710`.
   - **C:** the numeric fixture ids and planner-time rejection from `073d710`.
   - **P:** the 2 failing Python reference tests.
4. Owner-gated next steps: the durable stable signer (Lane E) and one approved fresh install on the S24+.
