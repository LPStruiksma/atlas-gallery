# PERF-7a: debug StrictMode baseline (#417, parent #128, goal #119)

**Owner words:** no stutters when opening screens for the first time; StrictMode clean run.

This unit measures. It changes no app behaviour, no WAL, People schema or restore path, no start-up ordering (#146) and no ONNX model holder. Follow-on fixes are separate units, chosen from the table this produces.

## What is instrumented

- `app/src/debug/java/nl/atlas/gallery/StrictModeBaseline.kt` holds a debug-only `ContentProvider`, `DebugStrictModeInstaller`. It sets a main-thread `StrictMode.ThreadPolicy` with `detectDiskReads()`, `detectDiskWrites()` and `detectNetwork()`, using `penaltyLog()` and a `penaltyListener` that keeps each violation with its phase label. It never uses `penaltyDeath` or `penaltyDialog`.
- The provider is declared only in `app/src/debug/AndroidManifest.xml`, so release builds do not contain it. It does nothing unless the app-private marker file `files/atlas-strictmode-baseline` exists. Ordinary debug installs and the normal emulator suite therefore run without StrictMode, because capturing a stack per violation would slow their timing assertions. (A disabled component cannot be the switch: the API 35 emulator answers `pm enable` from the shell with `SecurityException: Shell cannot change component state`.) Android creates providers before `Application.onCreate`, so once the marker exists the policy also covers `AtlasApplication` start-up from the next process start.
- `StrictModeSites` (debug, JVM-tested in `app/src/testDebug/.../StrictModeSiteTest.kt`) groups violations by phase, violation type, trigger frame and the deepest ATLAS frame. It names what was opened: `sqlite`, `shared-prefs`, `asset`, `model` (ONNX runtime), `file`, `network` or `other`. A stack that runs through the test runner or test code is marked `harness=true` and is not an app finding.

## How the two measurements are taken

Both run on emulator shard 1 right after the normal suite (before its pass/fail status is applied, so an unrelated red test still leaves the baseline), in `strictmode_baseline_417` in `scripts/ci_android_upgrade_emulator.sh`. The step only reports: a violation never fails the shard, and a missing report shows as a `::warning::`.

1. **Cold start.** With the marker in place, the step runs `am force-stop` then `am start -W` six times, 4 s apart. Launch 0 is the first after the shard's reinstall. Launches 1 to 5 give `STRICT cold-start n=5 p50_ms=… p95_ms=…` from `TotalTime`. Every `StrictMode` logcat stack from those launches is printed, so `Application.onCreate` and the first Photos grid of a real, non-instrumented process are covered.
2. **First-open flows.** `StrictModeBaselineTest` runs in its own `am instrument` process, so no earlier test has opened a store. It seeds 48 invented PNGs and then opens, in order: the Photos grid (Activity launch to first tile), the viewer (tap the first tile), People, Search, and the first typed Search query. It then re-enters Photos, the viewer, People and Search 5 times each. Each phase logs to `ATLAS_STRICT`:
   - `timing phase=… n p50_ms p95_ms`: tap to settled, measured from the test side, including a 500 ms settle. This is coarse. For first opens n=1.
   - `frames phase=…`: `dumpsys gfxinfo` total frames, janky frames, and p50/p90/p95/p99 frame time, reset at each phase start.
   - `site #n phase=… opening=… count=… trigger=… app=…`: one row per distinct main-thread opening, followed by its stack.

**First open or per entry?** A site that appears under `*-first-open` and not under `*-reentry`/`viewer-reopen` is a one-time first-open cost. A site that also appears under a re-entry phase recurs on every entry. StrictMode fires only on real I/O, so a helper object that is constructed but not yet opened (for example an `SQLiteOpenHelper` before `getReadableDatabase()`) never shows up. Every row is an actual main-thread read, write or network call.

## Run it by hand

On a debug install (emulator or device), with the test APK installed:

```bash
adb shell run-as nl.atlas.gallery sh -c 'mkdir -p files && touch files/atlas-strictmode-baseline'
adb shell am force-stop nl.atlas.gallery && adb logcat -c
adb shell am start -W -n nl.atlas.gallery/.MainActivity      # cold start
adb logcat -d -v brief -s StrictMode:D                        # its stacks
adb shell am instrument -w -r -e atlas_strictmode_baseline true \
  -e class nl.atlas.gallery.StrictModeBaselineTest \
  nl.atlas.gallery.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -d -v brief -s ATLAS_STRICT:I                      # the table
adb shell run-as nl.atlas.gallery rm -f files/atlas-strictmode-baseline
```

The test inserts and then deletes its own invented MediaStore PNGs. On a personal phone, run only the cold-start part (the first four lines and the last), which reads nothing from the library beyond what the app itself loads.

## Limits

- Emulator numbers are not Galaxy S24+ numbers. The emulator uses software rendering, so frame times are high in absolute terms. Compare phases with each other, not with the targets in #119.
- StrictMode may drop a repeat of the same violation within about a second, so `count` is a lower bound.
- `#128` is not complete until debug StrictMode runs clean with the persistence checks passing. The per-head table lives on the PR and on #417, not in this file, so it always names the exact head it came from.
