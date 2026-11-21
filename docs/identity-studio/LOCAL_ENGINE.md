# Local face engine (IDS-9, #475)

Goal: [#467](https://github.com/LPStruiksma/atlas-gallery/issues/467). Unit: [#475](https://github.com/LPStruiksma/atlas-gallery/issues/475). Contract: [ENGINE_CONTRACT.md](ENGINE_CONTRACT.md). Pipeline choice: [BENCH.md](BENCH.md). Model delivery: [#475 Strategy comment](https://github.com/LPStruiksma/atlas-gallery/issues/475#issuecomment-6990190659), owner decision [OD-1](https://github.com/LPStruiksma/atlas-gallery/issues/467#issuecomment-6990241396).

Code: `app/src/main/java/nl/atlas/gallery/featureidentity/local/`.

## Pipeline (per candidate)

`LocalFaceSwapEngine` implements `IdentityEditEngine` (route `LOCAL`, no network, free per candidate, face mode only; whole head is IDS-12).

1. **Identity.** Each reference is decoded at most 1280 px on its long edge, its largest face found by the shipped YuNet detector, aligned to the identity encoder's crop and encoded; the vectors are averaged (as the bench did) and cached for the warm period. SFace embeddings of the same faces are kept for step 6.
2. **Alignment.** The target's five landmarks (from target selection) are mapped onto the network's template with a closed-form least-squares similarity (`FaceAlignWarp`), and the crop is sampled bilinearly from the source region it covers.
3. **Swap network** on ONNX Runtime (CPU, 4 threads). `SwapNetworkSpec` describes crop size, template, tensor names, identity input size, pixel range and channel order, so a network allowed later (OD-1 HyperSwap in IDS-17 #516, or plan B #507) plugs in by spec and manifest without engine changes. The ORT session loads once and serves every candidate; it is dropped on `close()` and when Android reports low memory (`onTrimMemory` at `RUNNING_LOW` or above), and reloads on the next candidate. Those requests never wait for a running candidate (`WarmReleaseGate`): the callbacks arrive on the main thread, so the release happens at once when idle and otherwise right after the candidate finishes. The session's idle timer (IDS-6) closes it after 90 s.
4. **Mask with a guard** (`FaceBlendMask`). A `FaceSegmenter` gives face-skin probability per crop pixel. When its face area is below **0.5** of the area of the landmark oval, or when no segmenter is installed, the feathered landmark oval is used and the candidate's provenance says `mask=fallback`. The oval covers about 36 % of an ArcFace crop.
   - Why 0.5: in IDS-2 the MediaPipe segmenter's silent failures (T16, T23, T24, T25, T27) labelled 2 to 7 % of the crop, which is 0.06 to 0.19 of the oval, while working face masks cover 29 to 37 % of the crop (0.8 to 1.0 of the oval). The value is derived from those bench numbers; it has not been re-measured on the phone with the converted segmenter, because no segmenter ships yet (see Open points).
   - The planned blend strength (IDS-6 variation) scales the mask.
5. **Lab colour match** (`FaceColourMatch`), on by default: mean and spread of the swapped crop are moved to the target's, measured where the mask is at least 0.5, spread ratio clamped to 0.5 to 2.
6. **Paste-back** at full source resolution. Every source pixel in the crop's footprint is mapped into the crop; a pixel whose mask alpha is below 1/512 is not written, so everything outside the mask is bit-identical to the source. Output is PNG (lossless), source alpha kept.
7. **Weak-swap flag.** If the result's SFace similarity to the profile is lower than to the original face, `weakSwap: true` is recorded. SFace is a check, not a gate (BENCH.md).

Each candidate writes `candidate.png` and `candidate.provenance.json` inside the session's attempt folder. Provenance holds settings only (engine, version, mode, `mask`, colour match, blend strength, seed, changed pixel count, `weakSwap`); never pixels, landmarks, boxes or vectors.

Large photos: the header is read first. A source over 64 MP, or one whose full ARGB copy plus 48 MB would not fit in the free heap, fails as `PHOTO_TOO_LARGE` before any pixel is decoded. Otherwise the whole photo is decoded once (it is needed for a full-resolution PNG) and only the face region is read and written.

## Models: manifest and store

`IdentityModelStore` keeps models in `noBackupFilesDir/identity_models/`.

- A `ModelEntry` holds id, role (swap network, identity encoder, face segmenter), file name, original-host HTTPS URL, SHA-256, size, its LICENCES.md row, the register tier and `ownerDecision` (a link).
- **Gate:** tiers A and B may install; C and E only with `ownerDecision`; D never. A blocked entry never reaches the network, and a file planted under its name does not count as installed. JVM-tested.
- `install` runs once after the user taps setup, through a `ModelFetcher` the host passes in: resumable (HTTP Range; a host that ignores the range restarts cleanly), size and hash verified, then moved into place with a hash marker. `remove` deletes the file, marker and any partial download. `state` gives `NotInstalled`, `Partial`, `Installed` or `Blocked`, and the engine's `load()` returns `ENGINE_NOT_INSTALLED` without the swap model, which the studio turns into its setup sheet.
- **App updates:** the folder is the app's no-backup folder with fixed file names and no version-keyed path or wipe, so an installed model survives an in-place update (same signing key) and is used without downloading again; only uninstall or Clear data remove it. A manifest entry whose SHA-256 changes (a new model version) is not mistaken for the old file and reinstalls over it. JVM-tested (`installedModelSurvivesAnAppUpdateWithoutRefetching`).
- No download happens in `load()` or `generate()`. Nothing is re-hosted, exported, or put in git, APK assets, releases, CI artifacts or caches.
- **Host allowlist** (`ModelHostPolicy`): a manifest URL must start with an original host's release-download path (FaceFusion's `facefusion-assets` releases, InsightFace's `insightface` releases). Any other URL, a mirror, a query string, `..` or user info gives `ModelGate.HostNotAllowed`, and the store never calls the fetcher. `HttpsModelFetcher` follows GitHub's redirect from those URLs only to its release-asset storage (see Model downloader).
- **Archive members** (`ArchiveMember`): InsightFace publishes `w600k_r50` only inside `buffalo_l.zip`. The store fetches one HTTP range of the archive (the member's local header and deflated data), checks that range's SHA-256, inflates it, and checks the member's own size and SHA-256 before moving it into place. The archive's other four models are never downloaded. The range resumes like a whole file; a host that ignores ranges is read from byte 0 and skipped to the member.

## Model downloader (IDS-19, #619)

Strategy's choice on #619 closed the old "HTTPS fetcher" open point: the fetcher lives inside the #191 network fence and the fence is unchanged.

- **`HttpsModelFetcher`** (`featureaiedit/network/`, `HttpURLConnection`, no new dependency) implements `ModelFetcher`. HTTPS only; the first URL must pass `ModelHostPolicy`. Redirects are followed by hand, only to GitHub's release-asset storage by an exact host list (`release-assets.githubusercontent.com`, `objects.githubusercontent.com`; checked 2026-11-19 for both OD-1 URLs), over HTTPS on port 443 without user info, at most 4 hops. `Range: bytes=N-` resumes; a 200 to a ranged request restarts cleanly, a 206 for other bytes is never spliced in. Connect timeout 15 s, read timeout 30 s. The body streams straight into the store's partial file. Failures are a code only (`NETWORK`, `HOST_REFUSED`): no URL (signed redirect URLs carry tokens), header, cookie or exception text.
- **`ModelSetInstall`** (`featureidentity/install/`, no Android types) installs the set in order with overall progress, checks free space first, and maps every failure to `InstallFailure`: `NO_NETWORK` (retried; the partial file stays), `NOT_ENOUGH_SPACE`, `FILE_CHECK_FAILED` (wrong hash or size; the partial file is deleted and it is not retried) and `HOST_REFUSED`.
- **Space check:** the rest of setup's peak (what is left to download, plus the extracted encoder next to its range) plus 32 MiB headroom must fit in the free space of `noBackupFilesDir`. From scratch that is 737.4 MB + 32 MiB. If it does not fit, the state is `Failed(NOT_ENOUGH_SPACE)` and nothing is fetched.
- **`IdentityModelInstallWorker`** (WorkManager, unique work `identity-models:hyperswap-1a-256+insightface-w600k-r50`, `KEEP`): network constraint `CONNECTED` (any connection; the sheet states 563 MB once before the tap), exponential backoff from 30 s, up to 8 attempts for dropped connections. It survives the app being killed and resumes from the partial file. Notification "Downloading Swap person (563 MB)" with progress and Cancel. Cancel (queued, downloading or verifying) deletes every file of the set unless the whole set is installed, because a half-installed set is unusable and the sheet shows it as not installed; a stop for lost connection keeps the partial file. Cancel and Remove wait for WorkManager to record the cancel, then take a process-wide lock on the model folder that the install run holds, so they run only after the worker stopped and nothing it writes can come back. The notification's Cancel is WorkManager's own cancel intent, which runs no app code and, for queued work, no worker either. The installer, started off Main from `AtlasApplication`, therefore treats cancelled unique work as the user's Cancel whenever it sees it (`CancelledWorkCleanup`): it deletes the unfinished set and clears a notification a killed process left behind. Under the folder lock it checks again that the cancelled work is still the latest, so a newer start keeps its files.
- **`IdentityModelInstaller`** for the studio (#477): `start()`, `cancel()`, `remove()`, `downloadBytes`, and `state: StateFlow<InstallState>` with `NotInstalled`, `Downloading(bytes, total)`, `Verifying`, `Installed`, `Failed(reason)` and `Blocked`. `start()` runs only from the user's tap; `load()` and `generate()` never download.
- JVM-tested against a loopback fake server (`HttpsModelFetcherTest`, `ModelSetInstallTest`): resume after a dropped connection with a new store (as after a kill), a host that ignores the range, allowed and refused redirects, exact host list, hash and size mismatch, cancel, not enough space, temporary and refusing host errors.

## OD-1 models (IDS-17, #516)

`IdentityModelManifest.entries` holds the two entries allowed by owner decision [OD-1](https://github.com/LPStruiksma/atlas-gallery/issues/467#issuecomment-6990241396); URL, size and SHA-256 are in [LICENCES.md](LICENCES.md#downloaded-on-the-phone-under-od-1) with the date they were re-read.

| Model | Role | Downloaded | Stored |
|---|---|---|---|
| HyperSwap 1a 256 (FaceFusion's host) | swap network | 402.7 MB | 402.7 MB |
| InsightFace `w600k_r50` (one range of InsightFace's `buffalo_l.zip`) | identity encoder | 160.3 MB | 174.4 MB |
| **Total** | | **563.0 MB** (`IdentityModelManifest.downloadBytes`) | **577.1 MB** (`installedBytes`) |

- **Where:** `noBackupFilesDir/identity_models/` (`hyperswap_1a_256.onnx`, `w600k_r50.onnx`, each with a `.sha256` marker). App-private, excluded from backup, kept across app updates.
- **During setup** the folder briefly also holds the 160.3 MB range and the 174.4 MB extracted file side by side; the range is deleted as soon as the member verifies, so peak use is about 738 MB.
- **Remove** (`IdentityModelStore.remove` per entry) deletes each file, its marker, any partial download and any half-extracted file, which frees the full 577.1 MB. Uninstall or Clear data do the same.
- **Adapters.** `HyperSwapNetwork.SPEC` and `ArcFaceEmbedder` plug into this engine through `HyperSwapNetwork.engineConfig(store)`; a missing file gives `ENGINE_NOT_INSTALLED`. Pre- and post-processing are the bench's (`tools/identity_bench/pipeline.py`):
  - identity: references aligned to the 112 px ArcFace template (`arcface_112_v2`), RGB `x / 127.5 - 1`, raw 512-d `w600k_r50` vectors averaged then L2-normalised (`WeakSwap.meanUnit`) into HyperSwap's `source`;
  - target crop: **256 px on the `arcface_128` template** (not the 112 px ArcFace template), RGB `(x / 255 - 0.5) / 0.5` into `target`;
  - output `output` mapped back by `x * 0.5 + 0.5`, rounded and clipped; HyperSwap's own `mask` output is ignored, and the guarded mask, colour match and paste-back above decide the blend, as in the bench.
  - The tensor maths lives in `SwapTensors` (shared by every spec) and `ArcFacePreprocess`, so the JVM tests check the code the engine runs.
- **Parity fixture.** `app/src/test/resources/identity/hyperswap_io_fixture.json` is exported by `python3 -m tools.identity_bench.export.hyperswap_fixture` from the bench's own functions for one synthetic face drawn from integer rules (no photo, no weights). `HyperSwapNetworkTest` checks the alignment matrices (1e-4), templates, tensor values at sampled pixels (2e-6), output mapping (float32 and round-half-to-even like NumPy: exact sums, clip counts and samples) and source normalisation. Crop statistics agree within 0.001 (measured; the test allows 0.003) per channel mean and spread, in tensor units where one grey level is 0.0078: the app's warp samples pixel centres while OpenCV's maps pixel corners, a shift of `(1/scale - 1) / 2` source pixels, both bilinear with a replicated edge. The `real` block in the fixture records the real networks' output on the same synthetic face, for information only.

## CI stub network

`app/src/androidTest/assets/identity/stub_swapper.onnx` is 323 bytes, written for this repository: inputs `target` `[1,3,256,256]` and `source` `[1,512]`, output `output = 0.6 x target + 0.2` (the identity input is read and multiplied by zero). It lets the whole pipeline run on the emulator without third-party weights. It was generated with the `onnx` Python package (opset 13, IR 8):

```python
from onnx import helper, TensorProto
t = helper.make_tensor_value_info('target', TensorProto.FLOAT, [1,3,256,256])
s = helper.make_tensor_value_info('source', TensorProto.FLOAT, [1,512])
o = helper.make_tensor_value_info('output', TensorProto.FLOAT, [1,3,256,256])
c = lambda n, v: helper.make_tensor(n, TensorProto.FLOAT, [], [v])
nodes = [helper.make_node('Mul', ['target','a'], ['ta']), helper.make_node('Add', ['ta','b'], ['tab']),
         helper.make_node('ReduceMean', ['source'], ['sm'], keepdims=0),
         helper.make_node('Mul', ['sm','zero'], ['sz']), helper.make_node('Add', ['tab','sz'], ['output'])]
g = helper.make_graph(nodes, 'atlas_ids9_stub_swapper', [t, s], [o], initializer=[c('a',0.6), c('b',0.2), c('zero',0.0)])
m = helper.make_model(g, opset_imports=[helper.make_opsetid('', 13)], producer_name='atlas-ids9-stub'); m.ir_version = 8
```

SHA-256 `5adf9f48574afc366fa08a2223297b31e10142f879ae8f74efe1b5a0023cbac2`.

## Proof

- JVM: `./gradlew :app:testDebugUnitTest --tests 'nl.atlas.gallery.featureidentity.local.*'`: alignment maths (known similarity, agreement with the shipped SFace alignment, degenerate input, warp), mask guard (working mask kept, 2 % and 7 % patches fall back, no segmenter falls back), no change outside the mask on a synthetic photo, colour match, weak-swap rule, and the manifest gate with install, resume, wrong hash and Remove.
- Emulator, at `next:review`: `LocalFaceSwapEngineAndroidTest` with the stub network and synthetic cartoon faces: full pipeline, zero pixels changed outside the recomputed mask, provenance `mask=fallback`, no socket opened and StrictMode network death on the generating thread, warm reuse (three candidates, one load), cancel between steps with no candidate written, not-installed and mode failures, a header-only 72 MP photo refused without decoding, and a 50 MP photo either processed at full size or refused before allocation.

### Emulator timings (stub network, API 35 CI emulator)

Logged under tag `IDS9_TIMING` by the emulator test. These values come from the first green review run ([run 44419277477](https://github.com/LPStruiksma/atlas-gallery/actions/runs/44419277477), head `ec85cf0`, logcat in the `ATLAS-android-UI-evidence-shard-0` artifact). These measure the pipeline around the network, not a real network: the stub costs almost nothing. S24+ timings come from Lukas's phone at the first canary.

| Case | Time |
| --- | --- |
| First candidate (cold load included), 1024 x 768 | 1005 ms |
| Warm candidate, 1024 x 768 | 509 ms |
| 50 MP source (8000 x 6250) | 2142 ms, processed at full size (`Success`, not refused) |

## Open points

- **Segmenter delivery (IDS-18, [#519](https://github.com/LPStruiksma/atlas-gallery/issues/519); still NOT SHIPPED).** Strategy selected a bundled converted MediaPipe multiclass ONNX copy (no LiteRT). The text-only staging patch supplies an offline pinned-source size/SHA-256 check with `tflite2onnx==0.4.1`, a strict NHWC ONNX-layout check, a real LiteRT-vs-ORT parity harness for separate synthetic and licence-cleared corpora, a FACE skin / future HEAD hair+skin Kotlin adapter, synthetic JVM tests, and a missing-asset fallback. It does **not** deliver the ~16 MB ONNX or Google's full redistributed LICENSE, or independently establish conversion success, ONNX SHA-256, per-class IoU >=0.99, max output difference, T16/T23/T24/T25/T27 threshold remeasurement, or Android integration. The 0.5 coverage guard remains derived; HEAD mode stays unavailable. See `app/src/main/assets/models/mediapipe_multiclass/SOURCE.md`. No real `mask=segmenter` claim is made.
- **Foreground notification on Android 14+.** WorkManager's `SystemForegroundService` needs the `dataSync` service type and `FOREGROUND_SERVICE_DATA_SYNC` in `AndroidManifest.xml`, and the progress notification needs `POST_NOTIFICATIONS` (asked for by the studio). The manifest is a protected path, so those lines come in their own PR for Lukas. Until it merges, setup runs as ordinary background work, still resumable, and the notification shows only where notifications are allowed.
- The real-weight canary is Lukas's first "Swap person" on his S24+: download time, cold load and each candidate.
