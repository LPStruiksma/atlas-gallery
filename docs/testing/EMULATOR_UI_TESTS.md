# Emulator UI tests: the rules

One pattern for every instrumented test in `app/src/androidTest`. It comes from the 2026-11-18 audit of the whole emulator suite (158 files, 247 tests per shard) after main went red on five tests in one day. Helpers live in `app/src/androidTest/java/nl/atlas/gallery/uitest/`; `UiTestFoundationTest` proves each fact below on the emulator, and `test/additive-regressions/ui-test-reliability.mjs` stops the old patterns from growing.

## The five rules

| # | Rule | Use | Instead of |
|---|---|---|---|
| 1 | Touch and capture only what is whole on screen | `node.tap()`, `node.captureWhole()`, `node.assertFullyVisible()` | `performClick()`, `captureToImage()`, checks on `boundsInWindow` |
| 2 | Choose the keyboard; never meet the emulator's own | `KeyboardRule(TestKeyboard.None)` or `KeyboardRule(TestKeyboard.Fixed)`, `typeText`, `awaitKeyboardOpen/Closed` | `performTextInput` with whatever keyboard the emulator has |
| 3 | Know which clock you wait on | `advanceVirtualTime(ms)` for product timers, `waitForNode`/`waitUntil` for real work | `Thread.sleep`, "wait 300 ms then hope" |
| 4 | Device-wide state goes through a shared rule that restores it | `S24DisplayRule`, `KeyboardRule` | a private `shell("settings put …")` in the test |
| 5 | One shared wait that says what was on screen | `waitForNode(matcher)` from `UiWaits.kt` | a private `waitFor` per file |

## Why, with the evidence

### 1. A click on a cut-off node is lost without an error

`performClick()` is not a semantics action. It is `performTouchInput { click() }`: a touch at the centre of the node's **clipped** bounds (`ui-test` 1.11, `Actions.android.kt`; `visibleSize` is `boundsInRoot`). A node scrolled out of its list, or below a list that just shrank for the keyboard, has empty clipped bounds. The touch lands somewhere else, nothing happens, nothing fails. The test times out later on whatever the tap should have caused.

- `PeoplePrototypeJourneyTest` "the name is stored": 12 of 12 isolated runs failed with no review decision ever sent (run 44326214186). The failure dump of run 44319216708 shows Create at 948..1061 px in a 125..1072 px viewport: brought into view, never tapped.
- #402's "Create is above the keyboard" check passed while Create was off screen, because clipped bounds of an off-screen node collapse onto the viewport edge (found in #411).
- `captureToImage()` has the same blind spot: it copies only the visible part of a node.

`tap()` scrolls the node into its scroll parent, then refuses unless the whole node is on screen and above the keyboard, and says where the node is. It does not see other content drawn on top (a floating bar); keep targets clear of those.

Compose test touches go straight to the ComposeView. They never pass the window, so the keyboard window cannot catch them and a `Window.Callback` never sees them. Only `UiAutomation.injectInputEvent` travels the real input path. Use the real path only in a test that is about that path (TOUCH-1), and then keep the target above the keyboard.

### 2. The emulator keyboard is not ours to test

Sixteen test files type text, and each then gets LatinIME: it takes 0.25 to 2 s to appear, changes height when its suggestion strip shows, can open its own `PermissionsActivity` on top of ATLAS (run 44303296470), and on one emulator never appeared at all (8 of 8, run 44326214186 shard 1, while shard 0 passed 8 of 8). With CI's animator scale 0 it also shows without any inset animation.

The androidTest APK therefore carries two input methods of its own:

- `TestKeyboard.None`: fields take focus and text; nothing slides up. The default choice for any test that is not about the keyboard.
- `TestKeyboard.Fixed`: a flat panel, always 38% of the display tall. For tests about keyboard insets (name form, search field, TOUCH-1).

`KeyboardRule` goes outermost in the `RuleChain` and restores the previous input method. Evidence screenshots taken with `Fixed` show a plain "Test keyboard" panel. The two services are Java and use only `android.*`, because the system runs them in the test package's own process, where the app's classes, AndroidX and the Kotlin library do not exist.

### 3. Two clocks

Everything inside Compose runs on the test's **virtual** clock: `delay()` in a `LaunchedEffect`, animations, snackbar timeouts. That clock moves only when the test advances it. `waitForIdle()` does not run a pending `delay()`, and sleeping moves nothing. `waitUntil` advances it one 16 ms frame per loop of at least 10 ms of real time.

Everything outside Compose runs on **real** time: the keyboard, image decodes, SQLite, MediaStore, system windows, coroutines on `Dispatchers.IO`.

A product rule like "bring the name form into view 300 ms after the keyboard settles" (`NAME_FORM_SETTLE_MS`) mixes both: the inset arrives on real time and the 300 ms pass on virtual time. A test that waits 300 real ms has not waited 300 virtual ms. So: wait for the real event with `waitForNode`/`waitUntil`/`awaitKeyboardOpen`, then let product timers run with `advanceVirtualTime`. `awaitKeyboardOpen` does both.

For product code the lesson is the same: react to the layout or inset change itself, not to a timer that guesses when it is over.

### 4. All 247 tests of a shard share one device and one process

There is no orchestrator and no data clearing between tests (adding one is a protected `app/build.gradle.kts` change). What one test changes, the next one meets: display size and density (58 files via `S24DisplayRule`), night mode (the same private rule copied into 8 files), animator scale (2 files), accessibility timeout, MediaStore fixtures, the selected input method. Each restore is sent in a `finally`, but the system applies it later: `wm size reset` reconfigures every app including the keyboard (`RESET_NEW_CONFIGURATION … onFailed` in the logs).

Until a device-state guard exists (see the units), every change of device-wide state belongs in one shared rule that restores it and waits until the restore is visible.

### 5. Private helpers keep fixes from spreading

There are 49 private `waitFor…`/`settle` helpers in 32 files and 11 private `shell` functions. A fix made in one copy never reaches the others, which is why the same flake gets fixed again in the next file. New tests use the shared helpers; old files move over in the migration units.

## Writing a new test

```kotlin
@get:Rule val chain: RuleChain = RuleChain
    .outerRule(KeyboardRule(TestKeyboard.None))   // or Fixed, if the test is about the keyboard
    .around(S24DisplayRule())                      // evidence tests only
    .around(compose)

compose.typeText(compose.onNodeWithTag("atlas-group-name"), "Synthetic Ada", TestKeyboard.None)
compose.onNodeWithTag("atlas-group-create-person").tap()
compose.waitForNode(hasTestTag("atlas-native-person-album"))
```

- A tap must be followed by a wait for its visible effect, never by a sleep.
- Take screenshots only after the wait for that effect.
- No retry loop around a tap. A tap that needs a retry is a finding: report it.

## The ratchet

`test/additive-regressions/ui-test-reliability.mjs` runs in the cheap static job on every PR. Per test file it counts sleeps, raw captures, typing without a `KeyboardRule`, private device-state writes and private waits, and fails when a file has more than `ui-test-debt.json` records (a new file starts at zero). Raw clicks are counted but not enforced until the migration is further along. After migrating a file, lower its numbers:

```
ATLAS_UI_DEBT_WRITE=1 node test/additive-regressions/ui-test-reliability.mjs
```

## Stress a test

One sample per 25-minute suite cannot tell a 30% flake from a fix. To repeat a test, commit `scripts/ci-opt-in/stress-classes` on a throwaway branch and dispatch `build-apk.yml` with `run_ui_tests`:

```
# <count> <Class or Class#method>
8 nl.atlas.gallery.PeoplePrototypeJourneyTest
8 nl.atlas.gallery.featurepeopleui.UnknownPersonImeTouchTest#openingImeTouchIsNeverSilentlySwallowed
```

Each line runs as its own instrumentation run, on both shards (two independent emulators), and prints PASS or FAIL per run with the `ATLAS_*` and keyboard log lines. The full suite is skipped, so the `emulator-ui` check stays red: remove the file before review. Call a flake fixed only after a stress run on the fix head.

## When an emulator test fails

1. Read the failing step's own message first; `waitForNode` and `tap()` print what was on screen.
2. Decide which clock the missing event runs on (rule 3) before touching a timeout.
3. A failure before any test ran (Maven 429, KVM, emulator boot) gets one re-run of that job per head. A failed assertion never does.
4. Never fix a test by sleeping longer, retrying the tap, or switching to a semantics `OnClick`: each hides what a person would hit.
