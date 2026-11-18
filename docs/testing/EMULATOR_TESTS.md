# Emulator tests: the rules

One pattern for every instrumented test in `app/src/androidTest`. It comes from the 2026-11-18 audit of the whole emulator suite (#437). The kit lives in `app/src/androidTest/java/nl/atlas/gallery/testkit/`; `TestKitSelfTest` proves each helper and each fact below on the emulator.

## The eight rules

**1. Wait for an outcome, never for a time.** Every wait is `awaitThat("label") { condition }` (or `waitForNode(matcher)` for "this node exists"). On timeout it reports the label, the test line, the screen and the device. No `Thread.sleep`, no bare `waitUntil`.

```kotlin
compose.awaitThat("the person album to open") { compose.exists(hasTestTag("atlas-native-person-album")) }
```

**2. The keyboard is always in a named state.** After typing, the keyboard is hidden (the default) or shown and still. A screenshot that should show the keyboard comes after `KeyboardAfter.Shown`.

```kotlin
compose.typeText(hasTestTag("atlas-group-name"), "Synthetic Ada")                    // hidden again
compose.typeText(hasTestTag("atlas-group-name"), "Synthetic Ada", KeyboardAfter.Shown) // up, height stable
```

**3. `tap(matcher)` is the default tap.** It waits for the node, scrolls it into view, requires it enabled, whole on screen, above the keyboard and still, then invokes its click action. A cut-off or disabled node fails with its bounds. It never retries.

```kotlin
compose.tap(hasTestTag("atlas-group-create-person"))
```

**4. `touchTap(matcher) { effect }` is for tests about touch delivery** (TOUCH-1, gestures, hit targets). Same preconditions, real pointer events, and the caller names the effect. No effect is a failure that says where the pointer went.

```kotlin
compose.touchTap(hasTestTag("atlas-unknown-skip")) { skipped == 1 }
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

## Which helper do I use

| I want to | Use | Not |
|---|---|---|
| wait for something | `awaitThat(label) { … }`, `waitForNode(matcher)` | `Thread.sleep`, a private `waitFor` |
| press a button | `tap(matcher)` | `performClick()` |
| prove a touch arrives | `touchTap(matcher) { effect }` | a retry loop around a click |
| type | `typeText(matcher, text)` | `performTextInput` and hope |
| screenshot or assert with the keyboard up | `typeText(…, KeyboardAfter.Shown)`, `assertAboveKeyboard(matcher)` | a check on `boundsInWindow` |
| read pixels of a node | `node.captureWhole()` | `captureToImage()` |
| let a product timer pass | `advanceVirtualTime(ms)` | sleeping |
| run without the emulator's keyboard | `KeyboardRule(TestKeyboard.None)` or `Fixed` | |
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

A test can also take the keyboard out of the picture. The androidTest APK carries two input methods of its own (nothing ships in ATLAS):

- `TestKeyboard.None`: fields take focus and text; nothing slides up.
- `TestKeyboard.Fixed`: a flat "Test keyboard" panel, always 38% of the display (912 px of 2400, 0.5 to 1.1 s to open, same height on 80 of 80 opens in runs 44334939961 and 44335143186).

```kotlin
@get:Rule val chain: RuleChain = RuleChain
    .outerRule(KeyboardRule(TestKeyboard.Fixed))   // outermost: chosen before the Activity starts
    .around(S24DisplayRule()).around(compose)
```

The two services are plain Java on `android.*` only: the system runs them in the test package's own process, where the app's classes, AndroidX and the Kotlin library do not exist.

## When an emulator test fails

1. Read the failing step's own message first: `awaitThat`, `tap` and `touchTap` print what was on screen and the device state.
2. Decide which clock the missing event runs on before touching a timeout.
3. A failure before any test ran (Maven 429, KVM, emulator boot) gets one re-run of that job per head. A failed assertion never does.
4. Never fix a test by sleeping longer or retrying the tap: each hides what a person would hit.
