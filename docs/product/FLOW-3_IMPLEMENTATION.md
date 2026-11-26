# FLOW-3 implementation gates

Issue #593 and its approved design preflight (https://github.com/LPStruiksma/atlas-gallery/issues/593#issuecomment-7056111190) govern this work. This draft is not implementation evidence. Preserve current-item URI/epoch-safe return, reduced-motion instant cut, gesture arbitration and existing viewer-open performance. Require regression tests, exact-head Android CI, emulator midpoint and reduced-motion captures, parity comparison and before/after timing evidence before completion.

Checkpoint C1: `SharedPhotoLightJourneyTest` and `SharedPhotoDarkJourneyTest` now target mixed-aspect synthetic photos and capture held, reversed, committed and returned down-drag frames and a paged Back midpoint. No runtime result yet. Remaining: actual capture inspection, pinch, original-ratio and offscreen/missing-target journeys, reduced-motion validation, parity comparison and before/after first-frame/full-decode/frame-p90 timing.

Checkpoint C2: the same light/dark journey targets below-fit two-pointer pinch, reversal and measured committed return. No runtime result yet; actual pinch geometry and cancellation/commit behavior must be inspected on the emulator.

Checkpoint C3: the offscreen dated-grid case uses 40 synthetic fixtures only for this test, forces/restores date sort, checks the exact synthetic MediaStore tile identity before opening and after a far-item measured Back. The before/midpoint/returned captures are targets, not actual evidence. Missing-target runtime fade, original-ratio route, quick close, paired parity and timing still owed.

Checkpoint C4: quick Back while the entry overlay is active, sampled overlay exclusivity, closure to the opening tile and reopen/return to a different synthetic tile. Actual paired captures, image identity and pixel continuity remain unverified; this is not a timing benchmark.

Checkpoint C5: ratio rows now publish the clicked photo's measured entry bounds through the same URI/epoch-checked one-shot handoff as square tiles. The synthetic light/dark journey opens original proportions by its accessibility action, selects a non-square fixture, captures entry and measured Back, and verifies the same ratio tile is displayed after closing. These are capture targets, not rendered proof; ordinary entry/Back crop interpolation, missing-target fade, paired parity and timings remain owed.

Checkpoint C6: ordinary entry and Back now reuse the centered crop interpolation against an oriented catalogue-based fitted frame rather than the whole viewport. Missing dimensions suppress the moving copy and leave the fade; committed gesture pose retains priority. JVM geometry and source-contract tests added. Catalogue dimensions may differ from actual decoded pixels; mixed-aspect rendered crop/continuity, missing-target runtime fade, paired parity and before/after timing remain owed.

Checkpoint C7: synthetic anniversary fixtures open the real Stories player, enter the viewer and sample Back with no shared exit overlay before restoring the same moment. This exercises the non-grid fallback, not the separate 1500 ms grid-measurement timeout. Actual fade pixels, paired parity and timing still need hosted evidence.
