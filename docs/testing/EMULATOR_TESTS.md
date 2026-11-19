# Emulator tests: the rules

One pattern for every instrumented test in `app/src/androidTest`. It comes from the 2026-11-18 audit of the whole emulator suite (#437). The kit lives in `app/src/androidTest/java/nl/atlas/gallery/testkit/`; `TestKitSelfTest` proves each helper and each fact below on the emulator, and `test/additive-regressions/ui-test-reliability.mjs` stops the old patterns from growing.

## The nine rules

**1. Wait for an outcome, never for a time.** Every wait is `awaitThat("label") { condition }` (or `waitForNode(matcher)` for "this node exists"). On timeout it reports the label, the test line, the screen and the device. No `Thread.sleep`, no bare `waitUntil`.

```kotlin
compose.awaitThat("the person album to open") { compose.exists(hasTestTag("atlas-native-person-album")) }
```

**2. The keyboard is always in a named state.** After typing, the keyboard is hidden (the default) or shown and still. A screenshot that should show the keyboard comes after `KeyboardAfter.Shown`.

```kotlin
compose.typeText(hasTestTag("atlas-group-name"), "Synthetic Ada")                    // hidden again
compose.typeText(hasTestTag("atlas-group-name"), "Synthetic Ada", KeyboardAfter.Shown) // up, height stable
```

**3. `tap(matcher)` is the tap.** It waits for the node, scrolls it into view, requires it enabled, whole on screen, above the keyboard and still on two frames, then sends a real pointer DOWN and UP at its centre. A cut-off or disabled node fails with its bounds; a node that moves while the pointer is down fails with both positions. It never retries. A click action (`OnClick`) is only for tests of the accessibility route.

```kotlin
compose.tap(hasTestTag("atlas-group-create-person"))
```

**4. A test about touch delivery names the effect** (TOUCH-1, gestures, hit targets): `tap(matcher) { effect }`. No effect within the timeout is a failure that says where the pointer went and what is there now.

```kotlin
compose.tap(hasTestTag("atlas-unknown-skip")) { skipped == 1 }
```

**5. After anything that opens another window** (sheet, menu, dialog, system dialog), wait for its first node before touching it. A popup is a separate window that attaches a moment later.

```kotlin
compose.tap(hasTestTag("atlas-photos-sort"))
compose.awaitThat("the sort sheet") { compose.exists(hasTestTag("atlas-photos-sort-date")) }
compose.tap(hasTestTag("atlas-photos-sort-date"))
```

**6. After anything that changes the window** (display size, rotation, night mode, recreate, coming back from a system dialog), call `compose.awaitWindowSettled()`.

**7. Device settings change only through kit rules**, which restore the old value. Every test leaves the keyboard hidden. All tests of a shard share one device and one process: what one test leaves behind, the next one meets.

**8. Product code does not wait a fixed time for the system.** React to the inset, layout or state itself. A refused action logs why.

**9. Capture only a node that is whole on screen.** `captureToImage()` copies only the visible part of a node; `node.captureWhole()` scrolls it in and refuses a node that is still cut.

## Which helper do I use

| I want to | Use | Not |
|---|---|---|
| wait for something | `awaitThat(label) { … }`, `waitForNode(matcher)` | `Thread.sleep`, a private `waitFor` |
| press a button | `tap(matcher)` | `performClick()` |
| prove a touch arrives | `tap(matcher) { effect }` | a retry loop around a click |
| type | `typeText(matcher, text)` | `performTextInput` and hope |
| screenshot or assert with the keyboard up | `typeText(…, KeyboardAfter.Shown)`, `assertAboveKeyboard(matcher)` | a check on `boundsInWindow` |
| read pixels of a node | `node.captureWhole()` | `captureToImage()` |
| let a product timer pass | `advanceVirtualTime(ms)` | sleeping |
| choose the keyboard | `KeyboardRule()` (none), `KeyboardRule(TestKeyboard.Fixed)` | the emulator's own |
| describe the device in a failure | `describeDevice()` | a private `shell("dumpsys …")` |

## Why

### A click on a cut-off node is lost without an error

`performClick()` is `performTouchInput { click() }`: a touch at the centre of the node's **clipped** bounds (`ui-test` 1.11, `visibleSize` is `boundsInRoot`). A node scrolled out of its list, or below a list that just shrank for the keyboard, has empty clipped bounds. The touch lands elsewhere, nothing happens, nothing fails; the test times out later on whatever the tap should have caused. `captureToImage()` has the same blind spot, and so has any "is it above the keyboard" check on clipped bounds: off screen they collapse onto the viewport edge and pass.

`PeoplePrototypeJourneyTest` "the name is stored" failed 12 of 12 (run 44326214186) with Create brought into view but never tapped. With the click replaced by scroll-into-view plus a visibility check it passed 12 of 12 on two emulators (run 44335143186).

`tap()` does not see other content drawn on top of the node (a floating bar). Compose test pointers go straight to the Compose view: they never pass the window, so the keyboard window cannot take them and a `Window.Callback` never sees them. Only `UiAutomation.injectInputEvent` travels the real path.

### Two clocks

Everything inside Compose runs on the test's **virtual** clock: `delay()` in a `LaunchedEffect`, animations, snackbar timeouts. It moves only when the test advances it. `waitForIdle()` does not run a pending `delay()`, and sleeping moves nothing. `awaitThat` advances it one 16 ms frame per poll of about 10 ms real time.

Everything outside Compose runs on **real** time: the keyboard, window insets, image decodes, SQLite, MediaStore, system dialogs, `Dispatchers.IO`.

A product rule like "bring the name form into view 300 ms after the keyboard" mixes both. So: wait for the real event (`awaitKeyboardShown`, `awaitThat`), then let product timers run (`advanceVirtualTime`). `awaitKeyboardShown` does both.

### The emulator's keyboard

LatinIME takes 0.3 to 2 s to appear, changes height with its suggestion strip, can open its own permission screen over ATLAS (#424), and on one shard never came back after a display reset. `typeText` and `hideKeyboard` make tests independent of when it arrives.

So tests bring their own. The androidTest APK carries two input methods (nothing ships in ATLAS), chosen with `KeyboardRule`:

- `TestKeyboard.None`, the default for every test: fields take focus and text; nothing slides up.
- `TestKeyboard.Fixed`, for tests whose claim is about layout above the keyboard, and for evidence that shows a keyboard: a flat panel that says "Test keyboard", 918 px tall at 1080x2340 (the height LatinIME had there) or the height the rule names. On the CI emulator it opened at one height on 80 of 80 opens, in 0.5 to 1.1 s. The open animation is the platform's and runs as for any keyboard.

```kotlin
@get:Rule val chain: RuleChain = RuleChain
    .outerRule(KeyboardRule(TestKeyboard.Fixed))   // outermost: chosen before the Activity starts
    .around(S24DisplayRule()).around(compose)
```

`typeText`, `hideKeyboard` and `awaitKeyboardShown` work the same with either. No migrated test depends on LatinIME.

In a Dialog or Popup the kit sends keyboard requests to the focused window. A Dialog sits above the keyboard, so its own IME inset stays 0 while the keyboard is up; the kit then counts the keyboard as up when that window asks for it and the input method's window is on screen, and waits until the Dialog has stopped moving. Each keyboard wait logs, under `ATLAS_TESTKIT`, every Compose window with its focus and IME inset and which one it picked.

Kit code that the system starts must be plain Java on `android.*` only. The system runs an input method in the test package's own process, where the app's classes, AndroidX and the Kotlin library do not exist.

## Stress a test

One sample per 25-minute suite cannot tell a 30% flake from a fix. To repeat a test, commit `scripts/ci-opt-in/stress-classes` on a throwaway branch and dispatch `build-apk.yml` with `run_ui_tests`:

```
# <count> <Class or Class#method>
8 nl.atlas.gallery.PeoplePrototypeJourneyTest
8 nl.atlas.gallery.testkit.TestKitSelfTest#tapWithAnEffectFailsWhenNothingHappens
```

Each line runs as its own instrumentation run, on both shards (two independent emulators), and prints `STRESS <target> run i/n: PASS` or `FAIL` with the failure and the kit's log lines. About 8 minutes for 10 runs of one class. The full suite is skipped on such a head, and the static job fails while the file exists, so it cannot reach main: never open a PR from that branch. Call a flake fixed only after a stress run on the fix head.

## When an emulator test fails

1. Read the failing step's own message first: `awaitThat`, and `tap` print what was on screen and the device state.
2. Decide which clock the missing event runs on before touching a timeout.
3. A failure before any test ran (Maven 429, KVM, emulator boot) gets one re-run of that job per head. A failed assertion never does.
4. Never fix a test by sleeping longer or retrying the tap: each hides what a person would hit.

## The ratchet

`test/additive-regressions/ui-test-reliability.mjs` runs in the cheap static job. Per test file it counts sleeps, raw captures, raw typing without a `KeyboardRule`, private device-state writes and private waits, and fails when a file has more than `ui-test-debt.json` records (a new file starts at zero). Raw clicks are counted but not enforced yet. After migrating a file, lower its numbers:

```
ATLAS_UI_DEBT_WRITE=1 node test/additive-regressions/ui-test-reliability.mjs
```
