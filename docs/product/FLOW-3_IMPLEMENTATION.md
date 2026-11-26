# FLOW-3 implementation gates

Issue #593 and its approved design preflight (https://github.com/LPStruiksma/atlas-gallery/issues/593#issuecomment-7056111190) govern this work. This draft is not implementation evidence. Preserve current-item URI/epoch-safe return, reduced-motion instant cut, gesture arbitration and existing viewer-open performance. Require regression tests, exact-head Android CI, emulator midpoint and reduced-motion captures, parity comparison and before/after timing evidence before completion.

Checkpoint C1: `SharedPhotoLightJourneyTest` and `SharedPhotoDarkJourneyTest` now target mixed-aspect synthetic photos and capture held, reversed, committed and returned down-drag frames and a paged Back midpoint. No runtime result yet. Remaining: actual capture inspection, pinch, original-ratio and offscreen/missing-target journeys, reduced-motion validation, parity comparison and before/after first-frame/full-decode/frame-p90 timing.

Checkpoint C2: the same light/dark journey targets below-fit two-pointer pinch, reversal and measured committed return. No runtime result yet; actual pinch geometry and cancellation/commit behavior must be inspected on the emulator.

Checkpoint C3: the offscreen dated-grid case uses 40 synthetic fixtures only for this test, forces/restores date sort, checks the exact synthetic MediaStore tile identity before opening and after a far-item measured Back. The before/midpoint/returned captures are targets, not actual evidence. Missing-target runtime fade, original-ratio route, quick close, paired parity and timing still owed.

Checkpoint C4: quick Back while the entry overlay is active, sampled overlay exclusivity, closure to the opening tile and reopen/return to a different synthetic tile. Actual paired captures, image identity and pixel continuity remain unverified; this is not a timing benchmark.
