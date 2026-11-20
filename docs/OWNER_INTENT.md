# ATLAS owner intent (read before any ATLAS Dev or Review)

Set by Lukas, 2026-11-09 (Strategy thread, after first daily use of ATLAS on his S24+); amended 2026-11-20 after the Lapis Atlas + Continuum decision ([BRAND-L1 #654](https://github.com/LPStruiksma/atlas-gallery/issues/654)). This page wins over any older ATLAS design doc in this repo. Every ATLAS Dev checks a change against it, and every Review names it in the receipt; a change that drifts from it is BLOCKING.

## What ATLAS is

1. **Base: Samsung Gallery feature parity, plus sort by file size. Structure and look are ATLAS's own.** Every Samsung Gallery feature must have an obvious home. Where features live, how navigation is organised, and how controls and surfaces look follow [`docs/design/QUIET_FRAME.md`](design/QUIET_FRAME.md), not Samsung's layout. The [Samsung parity spec](product/SAMSUNG_PARITY_SPEC.md) remains the source of truth for **what** each feature does and **how it behaves once you are in it**, not **where it sits**. This direction supersedes the previous Samsung-layout requirement. Lukas (2026-11-20 02:13Z): “I do want one more round where we loosen the adherence to the Samsung gallery layout… those round people images are totally arbitrary according to me… Feature-wise, I want parity, but the way that it is structured internally and the way that the various UI items look should not be limited by it. If there are better ways to do it, then we should be able to do that.” Lukas chose “Lapis Atlas + Continuum ideas” at 02:22Z. His original design goal (00:47Z) is “uniquely well designed, modern and intuitive yet distinct and immediately visually recognizable as itself, with a coherent strong visual identity throughout its entirety that is consistent throughout everything up to its logo.”
2. **Source of truth for "how Samsung does it":** the private Samsung parity research archive (raw research dataset) and the private Samsung parity research archive (187 findings). The parity spec built from them is `docs/product/SAMSUNG_PARITY_SPEC.md` (#509). Cite finding IDs. The research is reference only; write our own Kotlin.
3. **Extras belong in their Quiet Frame homes.** Face/People search, “find similar”, Photo DNA, declutter and the file-size sort live in Search, Collections, or the viewer overflow, as assigned by Quiet Frame—never as extra chrome on core screens.
4. **Fast and snappy over cautious.** It is his personal app. No blocking screens, consent ceremony or safety text beyond what Android itself enforces; refresh in the background; open instantly into the cached gallery.
5. **He wants it all.** Full Samsung Gallery feature parity is the target, not only the items he happens to name (e.g. swipe up on a photo for its details panel, 2026-11-10).
6. **Holistic, not piecemeal.** Owner feedback is a symptom list. Fix the whole surface, not only the named item: preserve parity features and their in-feature behavior while applying ATLAS's own structure and Quiet Frame visual system.

## Removal list (default: remove)

Extraneous UI that Samsung Gallery does not have goes unless the parity spec or Quiet Frame's approved structure gives it a reason to stay. Known examples: “SCRUBBING” / “DRAG THUMBNAILS TO SCRUB” labels, the extra timeline bar under the viewer filmstrip, oversized headers and viewer chrome, explanatory banners and texts, the “Updating your allowed photos…” blocker, **letterboxed real-ratio tiles**. “Letterboxed” means padding a real-aspect-ratio photo inside a fixed cell; it does **not** prohibit Quiet Frame's approved justified rows of real-shape photos with no letterboxing or square crop.

## Not decided here

Owner-gated items in AGENTS.md / SECURITY.md (signing, protected paths, real-device installs beyond the approved release channel) still apply.

Source messages: Lukas 2026-11-09 23:38Z, 23:47Z, 23:49Z, 23:55Z (project Strategy thread, ATLAS Samsung-feel drive), 2026-11-20 00:47Z, 02:13Z and 02:22Z (brand/design direction, [#654](https://github.com/LPStruiksma/atlas-gallery/issues/654)). Feature-parity goal: #509.
