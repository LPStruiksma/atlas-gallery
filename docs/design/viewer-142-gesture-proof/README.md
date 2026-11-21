# Viewer gesture-navigation evidence (#833, for the #142 preflight)

**Subject: the CURRENT viewer (`GalleryViewer` on `main`), not the #142 target.** The target layout is not built, so every target-only interaction below is *proposed/unverified*. Host: GitHub-hosted API 35 x86_64 `pixel_6` AVD with `S24DisplayRule` (1080 × 2340, 450 dpi). **Not a physical Galaxy S24+**: One UI's gesture zones and back sensitivity can differ.

Labels: **MEASURED** = read from the running system or app; **COMPUTED** = arithmetic on measured values; **UNKNOWN** = not observed.

## How it was captured (isolated, no shared-shard contamination)

The #627 attempt flipped the navigation overlay inside a shared shard and a later test in that shard failed ([GESTURE_NAV.md](../viewer-142-target/GESTURE_NAV.md)). This unit never does that:

1. `Viewer142GestureNavigationEvidenceTest` **skips itself** unless the instrumentation run names only this class (`-e class nl.atlas.gallery.Viewer142GestureNavigationEvidenceTest`). A Gradle `connectedDebugAndroidTest` run (the shared shards) passes no `class` argument, so on every normal head the test is reported as skipped and touches nothing.
2. It runs alone through the existing opt-in stress lane (`scripts/ci-opt-in/stress-classes`, docs/testing/EMULATOR_TESTS.md "Stress a test"), on a throwaway branch `claude/atlas-833-gesture-run` that is never a PR. Each shard is a separate emulator running only this one instrumentation. No workflow file was changed.
3. The overlay switch (`cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.gestural`) happens **before** the test Activity starts. In `finally` it restores the original navbar overlay set, waits for the original `navigation_mode`, and checks the overlay set again. A failed restore fails the test.
4. Results leave the device as logcat lines (tag `ATLAS_TESTKIT`, prefix `G142`) because the stress lane doesn't pull device files. [`decode_log.py`](decode_log.py) rebuilds each file from a job log and refuses any file whose chunk count, size or SHA-256 doesn't match.

Restore check, from the logs: every run, pass or fail, logged `G142 original navigation_mode=0` and then `G142 restored navigation_mode=0` (runs 44864103905, 44865393321, 44866376934, 44867998376, 44869342120, both shards).

## Evidence run

| | |
|---|---|
| Run | [44869342120](https://github.com/LPStruiksma/atlas-gallery/actions/runs/44869342120), head `9600527142706cf8cc6d252d73a52a4af1fe8232` (this PR's test file plus the opt-in marker; the test file blob is byte-identical to this PR's) |
| Shard 0 | [job 136741313293](https://github.com/LPStruiksma/atlas-gallery/actions/runs/44869342120/job/136741313293): `STRESS … run 1/1: PASS` |
| Shard 1 | [job 136741313289](https://github.com/LPStruiksma/atlas-gallery/actions/runs/44869342120/job/136741313289): `STRESS … run 1/1: PASS` |
| Aggregate `emulator-ui` | red by design: an opt-in head skips the full suite, so the merged UI-evidence set is missing |

Files in [`run-44869342120/`](run-44869342120/), all synthetic (flat PNG blocks and a 4 s red/blue clip drawn in code):

- `viewer-142-gesture.shard0.json` and `viewer-142-gesture.shard1.json`: full reports (frames, insets, regions, zone overlaps, 26 probes each).
- Shard 0 screenshots: `…-photo-portrait-shown`, `…-photo-landscape-shown`, `…-video-portrait-shown`, `…-video-portrait-hidden`.
- `…-photo-portrait-hidden.png`: on this run the logcat transport lost chunks of that file on both shards (refused by the decoder). The copy here is from run [44867998376](https://github.com/LPStruiksma/atlas-gallery/actions/runs/44867998376) shard 0, and its SHA-256 `f18f0bd4…` equals the `png_sha256` that **both** shards of the evidence run recorded for that frame, so the bytes are the same capture.
- In the video frames the clip had not shown its first frame yet (times read 0:00 / 0:00, black surface). The chrome geometry is valid; video pixels are UNKNOWN in these PNGs.

## Results: insets (MEASURED, identical on both shards)

`navigation_mode = 2` in every frame. Portrait, px (dp = px / 2.8125):

| Inset | Chrome shown | Chrome hidden |
|---|---|---|
| statusBars | top 125 | 0 (hidden) |
| navigationBars | bottom 68 (24.2 dp) | 0 (hidden); 68 ignoring visibility |
| displayCutout | top 125 | top 125 |
| **systemGestures** | **left 84, right 84 (29.9 dp)**, top 157, bottom 90 | same |
| **mandatorySystemGestures** | top 157, **bottom 90 (32 dp)** | same |
| tappableElement | top 125 | top 125 |
| Compose safeDrawing | top 125, bottom 68 | top 125, bottom 0 |

Landscape (rotation 1, 2340 × 1080): cutout left 125; systemGestures left 84, right 84, top 68, bottom 90; mandatorySystemGestures top 68, bottom 90; safeDrawing left 125, top 68, bottom 68.

Key points for #142:
- The gesture bar is 68 px tall but the **mandatory bottom zone is 90 px**. Anchoring to `safeDrawing` (68 px) leaves 22 px of the bottom chrome inside the mandatory zone.
- Side back zones are 84 px (29.9 dp) per edge, in both orientations.
- Hiding the chrome hides the system bars; `systemGestures` and `mandatorySystemGestures` stay the same.

## Results: what the current viewer puts in gesture zones (COMPUTED from measured semantics bounds)

Portrait, chrome shown, px inside each zone:

| Node | Left zone | Right zone | Bottom mandatory |
|---|---|---|---|
| atlas-back | 28 | 0 | 0 |
| atlas-viewer-details-open | 0 | 28 | 0 |
| atlas-viewer-bottom-chrome | 84 | 84 | **22** |
| atlas-filmstrip (LazyRow bounds, full width) | 84 | 84 | 0 |
| atlas-video-controls | 45 | 45 | 0 |
| atlas-video-play-pause | 23 | 0 | 0 |

Exclusion rects (MEASURED): the photo page requests **none**. The video page has one, from Media3's `DefaultTimeBar` (inside the AndroidView player): 1080 × 135 px in that view's own coordinates. Its position on screen is UNKNOWN (it isn't the Compose `atlas-video-seek`), and the edge probes below show it doesn't stop edge Back on the Compose seek bar.

## Results: injected-gesture probes (MEASURED, `input swipe` / `input tap`)

Each probe ran twice per shard (4 samples), from a reset viewer with chrome shown. All 4 samples agreed for every probe in the evidence run, except tap play/pause (see its row).

| Probe | Back to viewer | Other effect |
|---|---|---|
| Left-edge swipe, mid-screen (photo) | 4/4 | no page change |
| Right-edge swipe, mid-screen (photo) | 4/4 | no page change |
| Left-edge swipe **on the filmstrip** | 4/4 | filmstrip did **not** scroll |
| Inner drag on the filmstrip (start 24 dp inside the zone) | 0/4 | filmstrip scrolled, page changed |
| Inner swipe left of centre (start 24 dp inside the zone) | 0/4 | pager paged back one item |
| Tap photo centre | 0/4 | chrome hid |
| Tap toolbar Back at its centre (28 px of the button lies in the left zone; the centre does not) | 4/4 (toolbar onBack) | none |
| Landscape left-edge swipe, mid-screen | 4/4 | no page change |
| Left-edge swipe **on the seek bar** (video) | 4/4 | seek **unchanged** |
| Right-edge swipe on the seek bar | 4/4 | seek unchanged |
| Inner drag on the seek bar | 0/4 | seek moved (0 → 0.73) |
| Left-edge swipe, mid-screen (video) | 4/4 | none |
| Tap play/pause | 0/4 | 2/4 observable: the first tap per shard moved seek from ~0.72 to 1.0 (the clip ran to its end within the 2.5 s window). The second tap per shard started at seek 1.0 and recorded no change, so it is **inconclusive** for playback activation (the reset doesn't rewind the clip) |

Every Back went to `GalleryViewer`'s own handler (`back_not_consumed_by_viewer = 0` throughout). No probe paged or seeked by accident when it started in an edge zone, and no edge swipe was eaten by the filmstrip or the seek bar.

Earlier runs at older heads add one data point: in run 44866376934, shard 0's first left-edge mid swipe did **not** deliver Back (the other emulator did). Since then, 8 of 8 left-edge mid samples on photo, and 4 of 4 on video, delivered Back. Treat edge Back as reliable but not guaranteed on this host.

## Not measured (UNKNOWN)

- Physical Galaxy S24+ (One UI) zone sizes and back sensitivity.
- Home and recents swipes from the bottom zone: not injected, because they would leave the app under test.
- Predictive-back animation frames: only the delivered Back is counted.
- Media3 AndroidView touch handling beyond the Compose semantics tree, and where `DefaultTimeBar` sits on screen.
- Any #142 target interaction (proposed/unverified until the target is built).

## Safe design guidance for #142 (based on the measurements above)

- **Bottom:** anchor the lowest hit target to `mandatorySystemGestures.bottom` (90 px / 32 dp here), not to `safeDrawing.bottom` (68 px). Today the bottom chrome reaches 22 px into the mandatory zone.
- **Sides:** keep primary controls (back, details, play) whole outside the 84 px side zones. Taps at these controls' centres (outside the zone) worked; a tap inside the zone was not probed, and a drag that starts there is Back, not a control drag.
- **Strip and seek drags:** don't rely on exclusion rects. Today none are requested for the filmstrip, and edge swipes on the filmstrip and seek bar deliver Back with no scroll or seek. Users start drags inside the zone (24 dp in worked every time). If the target requests exclusions, measure them again with this test; Android caps them at 200 dp per edge.
- **Hidden chrome:** the zones don't shrink when the bars hide, so the same rules apply in immersive mode.

## Re-running

1. On a throwaway branch (never a PR), add `scripts/ci-opt-in/stress-classes` containing `1 nl.atlas.gallery.Viewer142GestureNavigationEvidenceTest`.
2. Dispatch `build-apk.yml` with `run_ui_tests` on that branch.
3. Save each shard's job log, then run `python3 -I decode_log.py <job.log> <out-dir>`.
