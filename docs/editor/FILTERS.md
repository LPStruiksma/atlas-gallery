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
