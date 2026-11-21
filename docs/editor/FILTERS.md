# Atlas filters (EDIT-7)

Status: **original filter reference implementation and complete 14-look assets committed; independent proof and rendering integration still required.**

Unit: [EDIT-7 #725](https://github.com/LPStruiksma/atlas-gallery/issues/725) under [Editor #629](https://github.com/LPStruiksma/atlas-gallery/issues/629). Fourteen distinct Atlas looks use own parametric transforms, no Samsung LUT data, names, assets, packs or personal images. No UI is introduced (EDIT-10 owns it), and the existing `EditOperation.Filter(lutId, lutHash, strength)` schema is unchanged.

## Format and reproducibility

Run `python3 tools/atlas_luts/generate_luts.py app/src/main/assets/editor/luts` to regenerate the exact committed bundle. Fourteen 33³ `.lut` files total **1,509,494 bytes**; including catalogue JSON, the bundle is below 2 MB. Each binary file contains the nine ASCII magic bytes `ATLASLUT1`, one unsigned dimension byte (`33`), then interleaved RGB8 channel values across a 33×33×33 grid. The grid is ordered B outermost, G middle, R fastest. The raw format avoids external codecs; APK packaging compresses it. `catalogue.json` records stable identifiers, display names, bounded preview strengths, transform parameters, SHA-256 and byte lengths.

The CPU reference `AtlasLut.fromBytes` and `sample` interpolate trilinearly **in encoded sRGB** and blend the original in the same domain with `strength` from 0.0 to 1.0 (the 0–100 UI scale maps to `samplePercent`). The static `AtlasFilterCatalogue.load(id, context.assets::open)` verifies each packaged asset's SHA-256 and byte count before decoding. Unknown identifiers return null; corrupt assets fail closed. Previews use per-look strengths from 0.28 to 0.68 to limit extreme treatment of neutral and skin-like colours; these rules need visual verification on synthetic licensed fixtures before shipping.

## Custom filter from another photo

`LabColourTransfer.statistics` summarizes **on-device** encoded-sRGB pixels in D65 CIE Lab (channel mean and population deviation). `FilterFromPhoto.derive(sourceStats, referenceStats)` samples this Lab mean/deviation transform over a 33³ lattice and emits an original LUT in the same file format. It requires only numeric statistics, not the reference image, and JVM fixtures use synthetic palettes. Do not upload source/reference photos, pixel buffers or full Lab statistics to GitHub or external services. Release reference pixels once the user's ordinary media lifecycle allows it.

`CustomFilterStore(filesDir/subdirectory)` stores LUT bytes privately at `<sha256>.lut`, optionally stores a user-provided display name at `<sha256>.name`, verifies content hashes on read, and rejects malformed names, malformed hashes and corrupt files. Do not pass an external-storage directory. Existing recipes retain `lutId` and optional `lutHash` for copy/paste; a pasted recipe with a missing hash must report missing custom content, not silently substitute a preset. The storage helper does not itself synchronize assets across devices or manage the photo permission lifecycle.

## Acceptance and remaining independent proof

- Python generator tests cover byte-identical regeneration, exact checked-in assets, 14 distinct looks, SHA-256 catalogue and aggregate size. JVM tests cover identity, strength zero and 0–100, trilinear accuracy, catalogue loading and tampering, synthetic Lab transfer and named private storage.
- **Not yet claimed:** exact-head CI after the asset commit, Android compilation/JVM test execution, synthetic preview/skin-neutral evaluation, actual pipeline wiring for EDIT-2's GPU path, or measured GPU agreement (target ΔE ≤ 1). Don't call the feature shipped until these proofs and independent Review exist.
- The Codex first-stage auto-apply limit was text-only and excluded binary assets. The follow-up binary commit is ordinary Dev work, not another Codex patch; no additional `@codex` task is needed.
