# Native ATLAS acceptance and benchmark plan (S24+)

## Reference comparison

Use the **same exact local album and photo ordering** in Samsung Gallery and ATLAS where possible. Screen-record both sessions if desired, but measured timing should rely on Android frame tracing rather than visual impression alone. Run at similar temperature, battery level and display refresh rate, with power-saving settings held constant. Report exceptions.

| Test | Procedure | Measure / criterion |
|---|---|---|
| Filmstrip rapid scrub | Drag across 30 neighbors in ≤2 seconds, stop on target | Correct image under finger; no sustained blanks, UI freezes or wrong selection |
| Main-image swipe | Navigate 20 neighbors in full-screen viewer | Consistent scroll direction, Back restores previous gallery context |
| Zoom | Pinch + double-tap 10 different resolutions | No crash; no accidental navigation while zoomed |
| Video interleaving | Sequence photo → video → photo | Player lifecycle stable; no leaked audio, no overlapping playback |
| Deep grid | Album of 10k images | Scroll memory stable, no eager bitmap decode for offscreen cells |
| Entire archive | Full access to 67k+ images | Native page loads without out-of-memory; refresh doesn't block UI thread |
| Sorting | Newest/oldest/largest/random | Correct order; stable random during scroll; reshuffle explicitly changes order |
| Folder filter | Select `Black and White` and open viewer | Filmstrip contains ONLY images in selected physical folder |
| V4 import | Export 225 tagged faces with 7 named profiles | Profiles found; associated photos render; V4 DB unchanged |
| Background pressure | Scan in Termux while viewing ATLAS | Measure slower frames, memory pressure, thermal throttling |
| Permission modes | Full access, selected-photo access, revoke/regrant | No crashes; grid reflects accessible set, not leaked inaccessible media |

### Instrumentation in next iteration

- Add AndroidX Macrobenchmark and JankStats for p50/p95/p99 frame times, high-velocity scrolling and photo transitions.
- Capture `adb shell dumpsys meminfo nl.atlas.gallery` at cold start and after 2 minutes of rapid scrubbing.
- Compare actual thumbnail cache hit rate; prefetch immediately adjacent 8–12 previews, not 67k.
- Report decoding latency separately for preview and full-resolution transitions.
- Add UI test that replays deterministic scrub gestures and verifies target index/Back stack.

## Targets, not measured outcomes

P95 interactive frame time under 16.7ms at 60Hz (ideally under 8.3ms at 120Hz), stable UI during background indexing, no incorrectly retained video player, no original media modifications. These are **aspirational acceptance gates**, not measured results.
