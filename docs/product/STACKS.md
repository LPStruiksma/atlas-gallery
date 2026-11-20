# STACK-0 — Display-only stack grouping feasibility

Status: **feasibility draft** for [#574](https://github.com/LPStruiksma/atlas-gallery/issues/574). No runtime integration or UI changes are authorised here. The synthetic JVM tests and pure implementation are required before this proposal is accepted.

## Safety invariants

- Grouping is a **view projection** of existing ordered media records. No media moves, deletes, hides, edits, or persists a second similarity index. An unstacked view must reproduce the original ordered records.
- Video and photos never stack together. Never merge across directories based only on a matching filename. A shared timestamp alone is not enough.
- Do not form unsafe transitive chains: if A resembles B and B resembles C but A does not resemble C, do not collapse all three. Proposed candidate members must satisfy the group's scene guard.
- Use existing Photo DNA/perceptual-hash and visual-index data as input only; this prototype must neither compute an additional library-wide similarity index nor touch their runtime code.

## Candidate groups (rules to finalise against tests)

1. **Vendor burst:** recognise a *specific*, documented burst naming grammar, plus a tightly bounded capture-time window and same-directory identity; reject unrelated sequential filenames.
2. **RAW companion:** same directory and exact filename stem, a supported RAW extension paired with JPEG or HEIC, and compatible capture times. Never group equal stems from different directories.
3. **Edited original:** explicit original identity or unambiguous documented edited-suffix naming, same directory, compatible media kind and guarded scene identity. A generic suffix must not merge unrelated files.
4. **Near-identical capture:** same-directory still photos captured within a small configurable number of seconds, with a conservatively low perceptual-hash Hamming distance and corroborating geometry/scene checks. A same-second different scene is a hard rejection. Missing or unreliable hash must not become a match merely from time adjacency.

These rules are intentionally conservative: a false negative costs a little scrolling; a false positive conceals a distinct shot. Hash collision risk is nonzero, so the tested thresholds, false-positive boundary and limits must be explicit in the completed proposal rather than claiming an absolute mathematical guarantee.

## Cover proposal

A user-selected cover always wins. Otherwise prefer the sharpest eligible frame, then face-quality when relevant, then the largest file, then a deterministic stable identifier. Missing quality observations must not outrank measured good quality. A cover is a display choice, not a mutation.

## Aggregate-only evaluation

Estimate **collapsed tiles / original tile count** from on-device aggregate counts, recording only total photos, stack count, items grouped, and estimated tiles saved. Perform the computation through the existing aggregate-only on-device reporting path, never by exporting filenames, timestamps, hashes, faces, images or per-stack records to GitHub. For a stack of size k, savings are k-1; singleton savings are zero. The percentage denominator is the total original tiles.

## Future units (not STACK-0)

- Picture-grid stack count and badge, separately designed and preflighted.
- Viewer strip, explicit expansion and reversible **Unstack**.
- Selection semantics: top-pick only vs entire stack, especially for actions with side effects.
- Settings switch to disable stacking globally; retain all individual images when disabled.

## Required feasibility evidence

Pure Kotlin grouping under `app/src/main/java/nl/atlas/gallery/featurestacks/StackGroupingRules.kt` and synthetic JVM `StackGroupingRulesTest` must cover every candidate rule, stable ordering, cover override, reversibility, scene-hard-negatives, same-name different-folder and video/photo boundaries. Acceptance requires hosted exact-head `./gradlew :app:testDebugUnitTest` to pass. No emulator and no personal media.
