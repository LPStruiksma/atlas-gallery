# Atlas filters (EDIT-7)

Status: **implementation task scaffold only**. No LUT catalogue, sampler, custom-filter storage, or tests are implemented by this document.

## Scope and contract

Unit: [EDIT-7 #725](https://github.com/LPStruiksma/atlas-gallery/issues/725) under [Editor #629](https://github.com/LPStruiksma/atlas-gallery/issues/629). Build **12–16 original Atlas looks** as deterministic parametric colour transforms, never derived from Samsung or unlicensed LUTs, names, or assets. Their naming and previews should fit the [Lapis Atlas Quiet Frame](../design/QUIET_FRAME.md) while leaving thumbnails recognisable. Preserve neutral tones and skin safety in each look's preview.

Existing engine: `EditOperation.Filter(lutId, lutHash, strength)` in `featureeditor.engine`, where `strength` is a finite `Double` in `0.0..1.0`; `RenderStage.Lut` carries the same id, optional hash and strength. A UI 0–100 control maps to this range. **Do not change the engine schema or add UI** (EDIT-10 owns it).

A committed Python generator must produce byte-identical 33³ LUT assets, with stable identifiers, display names, a thumbnail-safe preview rule and an aggregate asset budget of **at most 2 MB**. A reference CPU sampler must interpolate trilinearly, blend with the original in a defined working colour space, and provide a reference for EDIT-2's GPU path (target ΔE ≤ 1). Reference-photo custom filters must work with synthetic Lab-statistics fixtures, avoid personal media in the repo, and save under app-private storage with the content SHA-256 referenced by the recipe.

## Acceptance proof (not yet supplied)

- Python generator byte-identical regeneration and 12–16 asset count/size/integrity tests.
- JVM identity-LUT, strength-zero, trilinear-grid and custom-reference colour-transfer tests with synthetic palettes.
- Exact-head hosted CI of the generated source and assets, including Android compilation; independent Review before merge.
- Implementation note documents format, colour-space assumptions, custom-file corruption handling, privacy boundary and copy/paste semantics.

## Codex relay boundary

The first Codex Cloud patch is constrained by the installed auto-apply guard: **one commit, at most 12 changed regular text files, no binary changes, deletes or renames**. It can implement a bounded text-source first stage, but **cannot by itself prove bundled generated binary assets or the full unit complete**. Missing files, generated assets and executable proof must be reported as follow-up work, never claimed as shipped. The draft PR remains unready until those requirements are met.

## First-stage reference implementation

`tools/atlas_luts/generate_luts.py` is the clean-room specification for fourteen original
33³ looks. It emits losslessly zlib-compressed `ATLASLUT1` files containing RGB uint8 samples in
blue/green/red loop order (red varies fastest), normalized sRGB-domain inputs and SHA-256
catalogue entries. The compact complete catalogue is specified to remain below 2 MB.
The checked-in Kotlin sampler uses the same ordering, clamps finite sRGB inputs to the LUT
domain, interpolates in encoded sRGB, then linearly blends the filtered and original values
at the recipe's `0.0..1.0` strength. This is a CPU reference, not a claim of perceptual-linear
blending or the EDIT-2 GPU implementation.

Custom-filter colour transfer converts encoded sRGB through D65 CIE Lab and independently
matches each synthetic channel's mean and population deviation. Production integration must
compute statistics on-device, discard reference pixels when no longer otherwise needed, and
pass an app-private directory below `filesDir` to `CustomFilterStore`. The store names content
only by SHA-256, verifies bytes on every read, returns null for malformed hashes or corruption,
and supports recipe copy/paste through the existing optional `lutHash`; it does not grant a
receiving device access to missing content.

This bounded stage deliberately contains no generated LUT assets or UI. Follow-up work must
commit and regeneration-check the complete generated asset set (aggregate at most 2 MB), wire
asset parsing/rendering and app-private lifecycle policy, measure skin/neutral preview safety,
and prove GPU agreement (target ΔE ≤ 1), Android compilation and exact-head CI.
