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
- `IdentityModelManifest.entries` is **empty** in this unit. The OD-1 entries (HyperSwap 1a 256 from FaceFusion's host, InsightFace `w600k_r50` from InsightFace's release) and their adapters are IDS-17 (#516).

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

Logged under tag `IDS9_TIMING` by the emulator test; filled in from the first green review run. These measure the pipeline around the network, not a real network: the stub costs almost nothing. S24+ timings come from Lukas's phone at the first canary.

| Case | Time |
| --- | --- |
| First candidate (cold load included), 1024 x 768 | pending first run |
| Warm candidate, 1024 x 768 | pending first run |
| 50 MP source | pending first run |

## Open points

- **The HTTPS fetcher.** The #191 network fence (`test/additive-regressions/network-scope-191.mjs`) allows network code only under `featureaiedit/`. This unit therefore ships no HTTP code: `IdentityModelStore` takes a `ModelFetcher`, and nothing in production can download a model yet. Before the setup sheet (IDS-8/IDS-17) can install anything, Strategy picks one: a small `ModelFetcher` implementation inside `featureaiedit/network/` (keeps the fence as is), or widening the fence to `featureidentity/local/` (a change to the network scope that #191 set for user-started work only).

- **Segmenter delivery.** The MediaPipe multiclass segmenter (LICENCES.md tier A) is published as `.tflite` only. ORT cannot run it, and a converted ONNX would be a file we produce, not one fetched from the original host. Options for Strategy: bundle a converted copy (tier A allows bundling; it is 16 MB in the APK), or run the `.tflite` on LiteRT (a new dependency, owner-gated). Until then every candidate uses the landmark oval and says `mask=fallback`, and the 0.5 guard is untested on real segmenter output.
- Identity encoder and swap network entries arrive in IDS-17 (#516).
