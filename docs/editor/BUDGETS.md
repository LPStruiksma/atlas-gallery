# EDIT-0 — Editor performance budgets and measurement contract

[Goal #629](https://github.com/LPStruiksma/atlas-gallery/issues/629) / [unit #630](https://github.com/LPStruiksma/atlas-gallery/issues/630). These are **Atlas acceptance targets**, not Samsung performance measurements. Static Samsung [REA report](https://github.com/LPStruiksma/atlas-native/blob/main/docs/research/samsung-rea/report/samsung-gallery-findings-v2.md) describes Gallery caching/boosting but **does not benchmark S24+ Photo Editor or Studio**. No host/emulator measurement can be described as S24+ latency.

## Fixed budgets

| Metric | Goal budget | Unit / precise boundary | Enforcement |
| --- | --- | --- | --- |
| Photo editor opens with image visible | **≤ 300 ms** | elapsed monotonic time from pencil tap dispatch to **first visible, correctly oriented non-placeholder image pixel** in the editor, including URI/decode/preview init; exclude prior viewer cache only by reporting warm vs cold separately | M1 EDIT-4 and EDIT-2, S24+ canary; report emulator trace by device profile |
| Slider/crop/rotate preview | **display refresh; zero missed frame deadlines in a 10 s continuous drag** | at 60 Hz: **16.67 ms/frame**; at 120 Hz: **8.33 ms/frame**. Judge by actual SurfaceFlinger/FrameTimeline deadline and refresh rate rather than arbitrary Compose recomposition count | EDIT-2 and each UI transform unit; emulator trace and S24+ |
| Full-resolution non-AI 12 MP export | **≤ 2,000 ms** | 4000 × 3000 output pixels; from **Save confirmed** to file fully written, MediaStore finalized, readable and decoder-validated. No background work may be hidden from the stopwatch | EDIT-2/3; synthetic 12 MP, emulator trace and S24+ |
| Export normalized rate (12 MP reference) | **≤ 166.7 ms/MP**, equivalent to **≥ 6.0 MP/s** | elapsed milliseconds / output megapixels (1 MP = 1,000,000 pixels); **the binding ≤2 s budget applies at 12 MP only**. No unjustified linear promise for 50/200 MP | Report alongside total latency |
| AI tool elapsed time | **Measured, no fixed pass/fail latency from Goal #629** | device end-to-end duration (button → applied candidate in editor); separately record **device compute, service/network wait if used, decode and save/import**, model id/version, result and error; redact payloads | M5/EDIT-5; never present CI host/emulator time as phone time |
| Video export | **No numerical baseline yet** | elapsed processing, frames, resolution, fps, codec/profile and HDR preserved / downgraded; retain actual 10 s clip duration | M6, then S24+ canary |

At display refresh means **no sustained frame pacing regression**. For a strict acceptance trace, a frame past its actual display deadline is a miss, irrespective of reported average fps. If the emulator cannot sustain its configured rate or lacks hardware acceleration, the run is **environment-invalid**, not a performance PASS; keep the raw trace identifier and investigate on S24+.

## Reproducible emulator trace protocol

1. Use only **generated or licensed synthetic media**—12 MP JPEG (4000×3000), HEIF, Ultra HDR with **known gainmap**, motion photo with clip, and 1080p/4K SDR/HDR video where supported. Record fixture hash, file size, codec/color space, emulator image/API/ABI, CPU/RAM, renderer, display refresh, app version, build commit, cold/warm cache state and export configuration.
2. On emulator, use Android **Perfetto** (atrace categories `gfx,view,wm,am,render,input, sched,freq` where available, and **FrameTimeline / SurfaceFlinger / HWUI** tracks), start capture **before** the editor tap, add instrumented monotonic trace sections `editor_open_start`, `first_photo_pixel`, `preview_drag_start/end`, `export_start`, `output_verified`. The end marker for open must be rendered content—not Composition entry, navigation dispatch or a spinner.
3. Measure **cold** (force-stop, disk cache state recorded) and **warm** (already in viewer, renderer ready) separately. At least **10 repetitions** each for open/export with the same fixture and thermal/charging condition; report median, p95 nearest-rank, worst, failures and samples, not a single best figure. At least **three 10 s uninterrupted gestures** per representative tool, each with finger/automated touch path constant and actual refresh mode recorded. Record missed deadlines / total frames / fraction; acceptance requires **zero** missed deadlines in each valid drag trace.
4. Export test starts on user Save/Save as copy confirmation and ends **only** after MediaStore pending flag clears, a new reader opens it successfully and dimensions, orientation, MIME, EXIF, gainmap/clip and original-byte backup invariants pass. On failed save count **failure**, not an omitted slow sample. Report JPEG/HEIF separately; the 12 MP non-AI reference fixture is the comparison gate.
5. In CI publish trace/measurement artifacts **without media bytes, private URIs, file paths, EXIF user fields, tokens or personal data**. Include timestamp, exact head SHA, test command, summary table, environment and reproducible trace capture recipe. An emulator result is labeled **EMULATOR**, never **S24+**. A green build without an instrumented trace is **not evidence** of meeting the timing targets.
6. If a measurement cannot be reproduced or FrameTimeline is absent, record `NOT_MEASURED` with reason and keep the claim open for the canary. Compare before/after changes on the same emulator fixture and profile; do not fabricate speedup ratios.

### Required data record per measured case

```text
commit=<40-char SHA> device_kind=EMULATOR|S24_PLUS
device/oneui/gallery/editor/studio=<versions or NA>
api/renderer/refresh_hz=<recorded> fixture_sha256=<synthetic-fixture hash>
operation=open_cold|open_warm|drag_slider|drag_crop|export_jpeg12|export_heif|ai|video
sample_count=<n> median_ms=<n|NA> p95_ms=<n|NA> worst_ms=<n|NA>
frame_count=<n|NA> missed_deadlines=<n|NA> elapsed_ms=<n|NA>
megapixels=<n|NA> ms_per_mp=<n|NA> codec_hdr_gainmap_motion=<verified statuses>
result=PASS|FAIL|NOT_MEASURED|ENV_INVALID trace_id=<sanitized id>
```

## Late S24+ canary (first owner-approved install, no owner photo export)

The S24+ run is an **independent** hard comparison: record OS/One UI/Gallery/Photo Editor versions, battery/thermal governor, refresh/brightness, installed Atlas head/version and cold/warm state. Import only disposable synthetic/owner-approved test media; never read or post personal photos. Use Android on-device Perfetto/FrameTimeline where access permits; capture ten openings/exports and three drag traces, inspect output files **on device** for EXIF, HDR gainmap, HEIF, motion clip, revert byte identity and video HDR. Do not change signing or install over the owner's stable APK until approved gate.

Acceptance means photo visible by 300 ms, every valid gesture frame meeting the displayed refresh deadline throughout 10 seconds, and non-AI 12 MP export finalized within 2 s. Report both per-sample results and conditions. If the emulator passes and S24+ fails, S24+ result governs. If S24+ cannot be tested yet, document **NOT_MEASURED**, not "parity achieved". AI tool time is **reported** rather than compared to an invented Samsung budget.

## Regression ownership

- **EDIT-1:** monotonic undo recipe benchmark, including a candidate-return operation; no performance claim without visual result.
- **EDIT-2:** GPU preview, all transforms (later M2–M4), HDR gainmap and real full-resolution export; own frame/latency instrumentation.
- **EDIT-3:** completed output, original-byte restoration and copy handling; include I/O in export timing.
- **EDIT-4:** pencil to first photo pixel end-to-end; design states and touch traces.
- **EDIT-5 / M5:** AI receipt includes device/service segmentation and tool-recipe commit time.
- **M6:** video codec/HDR fidelity and export telemetry (budget to be determined after baseline).

Do not substitute Gallery scroll/cache results from the REA dataset for editor benchmark measurements. Thresholds originate from **Goal #629**.
