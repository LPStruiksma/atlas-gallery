# Viewer feel: warm-cache / cold-cache / gesture protocol

**Context:** User-tested on Galaxy S24+ (Android 16), ATLAS Native 0.2. The user reports scrubbing through 20–30 images is nearly as responsive as Samsung Gallery *after caching*, with intermittent flicker on the first traversal, and Samsung's filmstrip having a more satisfying slow-versus-fast feel. These are user observations, not measured frame times.

## Candidate change (this branch)
1. Nearby screen-sized image previews are predecoded using the same singleton Coil image loader and exactly the same 800×800 request size as the viewer. Limit to two images each side (up to four decodes); only on settled navigation. Coroutine cancellation stops requests when scrubbing changes the current item.
2. Compose's built-in center snap-fling behavior aligns the selected filmstrip thumbnail after a decelerating release without implementing new physics.
3. The 2400×2400 enhancement layer fades in over the still-visible 800×800 preview, avoiding an abrupt sharpness jump.

No photo files are modified. No network is required.

## Gate A: deterministic / emulator
- Unit-test preview order and bounds at start/end, empty collections, invalid indices, giant archives, large radius.
- Compile Android app and test APK with pinned API-36 toolchain.
- Drag filmstrip in emulator and verify that selection advances; tap thumbnails; verify Back returns to gallery.
- Validate screenshots by PNG magic and dimensions, *not* merely file existence.

## Gate B: S24+ user experience (do only after stable signing and installability are settled)
Use a contiguous 30-photo set and comparable order in Samsung Gallery and ATLAS. No photos need to leave the device.

1. **Cold:** after a fresh app process, swipe slowly over 10 thumbnails, then rapidly through 30. Record blank/black frames, visible preview-to-full jumps, perceived lag.
2. **Warm:** repeat in the same direction and reverse. Record any hitching and whether the selection follows finger motion.
3. **Natural feel:** three light short drags, three medium drags, three fast flings; inspect overshoot, snap-to-center and unintended jumps, especially at first/last item.
4. **Large archive:** try the same with a random or size-sorted album of thousands of items, plus a photo/video boundary. Check thermal behavior and scroll consistency.

Optional objective comparison via Android JankStats or Macrobenchmark frame timings on a synthetic dataset; do not call simulator performance a proof of Samsung parity.

## Decision rule
Promote only if build and interaction gates pass and no regressions in zoom, swipe, video, Back, photo permissions or data safety. Change one major performance variable at a time. If the center snap feels worse than the existing physics, revert it while keeping any independently supported cache improvement.


## Lane E reproducible frame-time and memory evidence (synthetic / offline)

**script:** scripts/summarize_viewer_perf.py. It reads captured Android **dumpsys gfxinfo ... framestats** and **dumpsys meminfo** files locally and produces anonymous aggregate JSON: each phase/trial's valid-frame count, p50/p95/p99 end-to-end frame completion time (FrameCompleted minus IntendedVsync), fraction exceeding the supplied refresh-period budget, and max *observed* PSS snapshot. This is a reproducible numeric analysis procedure, **not yet a measured S24+ benchmark**. It does not execute adb, inspect actual phone data, or access photos; its fixture regression is **python3 scripts/test_summarize_viewer_perf.py**.

Fixture setup: use an **isolated emulator**, verified by ro.kernel.qemu=1 and a single attached emulator, with the existing Android synthetic ViewerInteractionTest (fictional generated PNGs, filmstrip/Back screenshots). The Compose catalogue tests also cover 15,000 invented items and JVM sort tests cover 150,000; these latter counts **must not** be described as GPU/frame metrics. Do not mix emulator UI frame timings and JVM sort throughput. For each cold or warm phase, keep the same 64-item synthetic filmstrip, ordered gestures (10 slow scrubs, 30 rapid selections, reverse direction, boundary, Back and reopen), fixed display refresh mode and identical build. After setting up synthetic UI fixtures, capture one new framestats window per repeat; reset gfxinfo counters *before* each window:

~~~sh
adb shell dumpsys gfxinfo nl.atlas.gallery reset
# Perform the fixed synthetic gesture protocol in the isolated emulator.
adb shell dumpsys gfxinfo nl.atlas.gallery framestats > cold-1.frames
adb shell dumpsys meminfo nl.atlas.gallery > cold-1.mem
~~~

Repeat three independent cold starts and three warm-cache traversals (cold-2, cold-3, warm-1, warm-2, warm-3) on the **same synthetic media and build**, clearing only intended cache/process state for cold starts and without altering any user's phone/app data. Ensure the test scene remains alive during measurements and inspect valid-frame counts; a JUnit interaction pass alone does not prove this benchmark was captured correctly. The entire above adb example is for a verified disposable emulator, **not** permission to change the physical S24+. Do not upload raw gfxinfo or meminfo because they can contain environment/device identifiers.

Example offline aggregation after capture:

~~~sh
python3 scripts/summarize_viewer_perf.py --refresh-hz 120 --catalogue-size 64 \
  --trial cold cold-1.frames cold-1.mem \
  --trial cold cold-2.frames cold-2.mem \
  --trial cold cold-3.frames cold-3.mem \
  --trial warm warm-1.frames warm-1.mem \
  --trial warm warm-2.frames warm-2.mem \
  --trial warm warm-3.frames warm-3.mem
~~~

**Required report metadata alongside JSON:** exact APK source/SHA, 60/120 Hz *actual* display mode, emulator vs S24+, Android version, catalogue fixture contents/count, action order, photo/video boundary test, screen resolution, cold/warm cache policy, image decode sizes, thermal state, and repetitions. The command's --refresh-hz value is **user-provided**, not inferred/validated against the Android display. Sample size and conditions must be comparable across revisions. Keep per-trial statistics; pooled values alone can hide a slow repeat. The script flags repeatable_evidence false for fewer than three runs per phase, and fails closed for unsupported/empty logs.

**Metric caveats:** Over-budget frames are not the official Android JankStats classification; end-to-end frame delivery is not GPU render time or finger-down-to-visible-image latency. Captured PSS is not peak Java heap/bitmap memory. Add Perfetto frame timeline, Coil preview latency/caching trace, peak allocations and native frame pacing before Samsung-level performance claims. Real S24+ measurements and comparative judgments are postponed until the [upgrade release gates](RELEASE_GO_NO_GO.md) are independently satisfied.


### Evidence-integrity hardening (16 October, Lane E)

The offline summarizer now rejects a capture with fewer than **60 valid completed frames** per trial and rejects reused frame/memory input paths or frame files with identical contents under different filenames. Three named \`cold\` and three named \`warm\` trials only count as independent measurements if each comes from a genuinely new synthetic interaction/capture; reusing the same file six times is NOT repeatability. With fewer frames, rerun a longer isolated emulator interaction instead of claiming a p95. The 60-frame minimum is a sampling sanity check, **not** statistically sufficient proof of smoothness or of S24+ parity. A recorded \`repeatable_evidence\` result still requires genuine process/cache state verification, stable thermal/display conditions, confidence intervals and physical device measurements. Run \`python3 scripts/test_summarize_viewer_perf.py\` on fictional logs.


## Reproducible synthetic fixture corpus (Lane E, 16 October 2026)

The standalone \`scripts/generate_viewer_fixtures.py\` creates **only invented data**: deterministic RGB PNGs (alternating flat bands and high-entropy pixel noise), a metadata-only CSV containing up to 150,000 catalogue rows, a manifest with SHA-256 for each generated image and the CSV, and a completion marker written **last**. Seed and dimensions are explicit; all files are mode \`0600\` in a new mode \`0700\` directory. It does not access photos, MediaStore, Shizuku, adb, credentials, or the real phone. It cannot overwrite an existing output directory and refuses shared Android-media/DCIM destinations.

Example, on an **offline development machine** (the output directory must not already exist):

~~~sh
python3 scripts/generate_viewer_fixtures.py --dry-run ./atlas-fixture \
  --catalogue 150000 --images 8 --width 3840 --height 2160 --seed 83
python3 scripts/generate_viewer_fixtures.py ./atlas-fixture \
  --catalogue 150000 --images 8 --width 3840 --height 2160 --seed 83
python3 scripts/test_generate_viewer_fixtures.py
~~~

Eight 4K RGB images require about **199 MiB of raw decoded pixel capacity**, independent of compressed PNG size; the script caps the total raw RGB image budget at 256 MiB, each image at 12 million pixels, image count at 128, and catalogue size at 150,000. **Dry run writes nothing.** To test thumbnail interactions without the heavy full-resolution images, use 64 320×240 images and the same metadata catalogue size. High-entropy synthetic PNGs can occupy substantial storage despite small manifest sizes: check free disk space before large generation.

Interpretation matters: \`catalogue.csv\` contains deliberately **inert** \`synthetic://\` URIs for generated and unmaterialized rows; it is **not** a real Android MediaStore inventory and cannot be opened as live content URIs. Only \`materialized=1\` PNG rows have files. Video rows are **metadata placeholders**, not real videos. Nothing in this generator measures loading/decode timings or injects test images into the native viewer. Use the existing Android instrumented 64-image viewer test for correctness; any future emulator/physical on-screen benchmark must use an explicit, separately reviewed bridge to local-only synthetic MediaStore fixtures and must report the actual number of materialized images. Do not claim the app rendered 150k images because it indexed 150k inert rows.

For A/B reproduction, preserve the \`manifest.json\` and compare \`catalogue_sha256\`, individual image hashes, seed, dimensions and Python/zlib runtime. Image-byte equality is tested on the **same runtime**; zlib output bytes can vary between runtime/library versions despite logically identical pixel content. The regression test generates PNGs and both tiny and 150k-row metadata-only sets, verifies the PNG pixel-stream shape and CRC, binary repeatability, permissions, refusal to overwrite and simulated shared-folder guardrails. It is also included in the existing short, synthetic-only signer/release CI gate.

**No physical S24+ frame-time, memory, thermal, zoom, video or Android filmstrip benchmark result is claimed.** Keep the [release go/no-go](RELEASE_GO_NO_GO.md) intact until owner-verified signer, People/settings backup and real device upgrade gate pass.


## Offline A/B comparison with environment and fixture parity (Lane E)

Raw frame percentiles only have meaning when the compared experiments used equivalent conditions. The new \`scripts/compare_viewer_perf.py\` screens two JSON summaries produced by \`scripts/summarize_viewer_perf.py\` and **requires separate, explicitly recorded run manifests**. It rejects comparisons with different fixtures, display/refresh rate, Android API, test device identity, gesture protocol, cache-reset protocol, thermal-state protocol, number of materialized images or catalogue size. It also refuses comparison of the same APK checksum, incomplete or fewer-than-three-trial captures, invalid metrics, and frame counts below 60 per trial.

For **each** baseline and candidate run, create a local JSON manifest with this exact schema (fictional sample shown, replace values with actual verified synthetic test information):

~~~json
{
  "schema_version": 1,
  "artifact_sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "environment": {
    "device_kind": "isolated_emulator",
    "device_token": "anonymous-controlled-emulator-1",
    "android_api": 35,
    "display_resolution": "1080x2400",
    "refresh_hz": 120,
    "fixture_manifest_sha256": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
    "gesture_protocol": "filmstrip-64-v1",
    "cache_protocol": "cold-then-warm-v1",
    "thermal_protocol": "stable-emulator",
    "materialized_images": 64
  }
}
~~~

Record the real APK SHA-256 from the **specific** tested build and derive \`fixture_manifest_sha256\` from the full \`manifest.json\` (rather than just \`catalogue.csv\`, which does not authenticate decoded pixel contents). Use a pseudonymous controlled-device label, not an IMEI, phone serial or unique account identifier. The two manifests must share exactly the same \`environment\`, but have **different** \`artifact_sha256\` values. When multiple devices, Android API levels, resolutions, cache/gesture or thermal conditions differ, **do not combine trials**.

~~~sh
python3 scripts/compare_viewer_perf.py \
  baseline-summary.json candidate-summary.json baseline-run.json candidate-run.json
python3 scripts/test_compare_viewer_perf.py
~~~

Output includes descriptive percentage changes in pooled p95 frame duration, median and worst per-trial p95 duration, observed maximum PSS, and percentage-point changes in budget exceedance for both cold and warm phases. The script flags changes exceeding **10% p95**, **15% observed PSS**, or **5 percentage points** over the frame-duration budget as *screening alerts only*; these are engineering review heuristics, **not** scientifically validated release tolerances. A flag-free result is **not** evidence of statistical equivalence (three captures are a small sample), nor does it measure GPU-only time, image-ready latency or actual screen presentation. The report always declares \`release_go=false\` and \`statistical_significance_established=false\`.

The run manifests themselves are local assertions, not signed attestations that capture conditions were followed; validate sources separately. A physical S24+ A/B comparison additionally requires owner-controlled authorization, same-device actual display-mode verification and the installation safety gate. No synthetic A/B metric can override the [release NO-GO](RELEASE_GO_NO_GO.md) decision.


## Independently verify saved synthetic PNG/catalogue fixtures (Lane E)

An apparently complete benchmark directory is **not** proof it still contains exactly the same data generated originally. The read-only \`scripts/verify_viewer_fixtures.py\` checks: explicit final completion marker, versioned \`manifest.json\`, each image's pinned SHA-256/byte count and PNG width/height/color header, exact permitted file inventory (no symlinks or extra entries), \`catalogue.csv\` SHA-256, **all** contiguous numbered metadata rows (including which images are actually materialized), known video placeholders, and decoded RGB budget. It refuses incomplete, interrupted, modified or unsupported datasets, even where the manifest file remains readable.

~~~sh
# Derive this fingerprint from a separately retained, trusted synthetic manifest.
python3 scripts/verify_viewer_fixtures.py ./atlas-fixture \
  --expected-manifest-sha256 <64-hex-sha256-of-trusted-manifest.json>
python3 scripts/test_verify_viewer_fixtures.py
~~~

The optional independently retained manifest SHA-256 provides **byte-level pinning** against substitution of both images and their manifest; if it is omitted, the tool proves only internal consistency of the currently provided directory. No files are modified. The fixture remains entirely synthetic, and even an intact corpus is **not evidence** that an Android app loaded it from MediaStore, rendered each bitmap or achieved a measured frame rate. For an A/B trial, use this verifier before and after test capture and record the pinned complete manifest hash in both run-provenance JSONs; physically comparable experiments still require an independently controlled emulator/device environment. The test suite exercises stale/changed PNG or CSV, missing completion marker, unexpected files, symlink substitution and all 150,000 inert metadata rows.

No user photo, real S24+ app, database or settings are accessed or changed by this verifier.


### Frame record integrity: reject pasted/duplicated Android captures (16 October, Lane E)

A separate gfxinfo framestats file **must contain one completed capture window**. The offline \`summarize_viewer_perf.py\` parser now rejects repeated framestats table headers (for example, two dumpsys outputs concatenated into one file), duplicate completed records with the same IntendedVsync/FrameCompleted pair, and nonmonotonic IntendedVsync timestamps among accepted frame records. It also refuses symlinked input logs. These protections avoid p95/p99 statistics being accidentally biased by repeated copies of the same frame or overlapping capture windows. Normal Android records with nonzero Flags or incomplete timestamps remain excluded according to the existing parser contract.

This does not establish touch-to-photon latency, actual GPU render time, sampled screen-presentation timing, thermally comparable test conditions or an S24+ release GO. Some Android vendors may emit multiple window sections in a combined \`dumpsys gfxinfo\`; such outputs need a separately defined, unambiguous per-window capture protocol instead of merging them into one trial. Run the synthetic regression \`python3 scripts/test_summarize_viewer_perf.py\` (also under the cheap release CI gate) before comparing cold/warm trials.


### Reject duplicated frame records across otherwise different trial logs (Lane E, 17 October)

The offline `summarize_viewer_perf.py` validator already refuses identical input files, but two *different files* can still replay the same complete Android gfxinfo frame records, or share a large subset of records, while producing different file SHA-256 values. This would falsely inflate cold/warm sample counts and violate the requirement for independent experiments. The parser now preserves each accepted frame's `(IntendedVsync, FrameCompleted)` identity and refuses **any repeated frame record across all cold and warm captures** in the same reported experiment, even when extra prose or different tails make files byte-distinct. It still rejects duplicate records *inside* one capture, concatenated windows and nonmonotonic records.

This is a conservative evidence gate: if captures reuse frames by overlapping their timing windows, retake them as nonoverlapping, individually cleared/recorded test passes. Identical timestamp pairs can theoretically arise after distinct device reboots, and should be investigated rather than automatically credited as new independent trials. A full Android benchmark additionally requires fixture matching, test protocol, storage, device refresh and controlled cold/warm state; the tool does not prove genuine Android screenshot rendering, finger-to-photon latency, true peak RAM or safe device upgrade. Purely synthetic regression includes a byte-different replay and a 70-frame partial overlap. No personal media or phone access.


### Reject mathematically inconsistent performance summaries (Lane E, 17 October)

The two-run `scripts/compare_viewer_perf.py` evidence checker now validates that the reported per-refresh frame budget matches `1000 / refresh_hz` (accounting for the original summarizer's 0.001 ms rounding), each pooled `p50 <= p95 <= p99`, observed maximum PSS equals the largest listed per-trial snapshot, and pooled frame-budget exceedance agrees with the **frame-count-weighted** per-trial rates within their documented 0.01-percentage-point rounding. Invalid booleans masquerading as integer counts/schema versions, non-finite rates and false `repeatable_evidence` types are refused. The source summarizer itself also rejects NaN/Infinity refresh rates and boolean catalogue counts, which could otherwise yield nonsensical JSON.

These checks catch inconsistent or corrupted report inputs before an A/B result is discussed. They cannot authenticate claimed APK digests, prove independence of owner-authored environment manifests, reconstruct raw frame timing from already summarized percentiles or establish statistical significance. Independent raw synthetic capture hashes and the new cross-trial frame-overlap checks remain necessary. `release_go=false` is unconditional.

Fictional regression tests: `python3 scripts/test_compare_viewer_perf.py` and `python3 scripts/test_summarize_viewer_perf.py`, both in the existing short signing-safety CI. No actual phone or media capture.
