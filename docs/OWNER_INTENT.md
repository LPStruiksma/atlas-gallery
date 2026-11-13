# ATLAS owner intent (read before any ATLAS Dev or Review)

Set by Lukas, 2026-11-09 (Strategy thread, after first daily use of ATLAS on his S24+). This page wins over any older ATLAS design doc in this repo. Every ATLAS Dev checks a change against it, and every Review names it in the receipt; a change that drifts from it is BLOCKING.

## What ATLAS is
1. **Base: an exact Samsung Gallery replica**, plus **sort by file size**. Look, layout, motion and behaviour follow Samsung Gallery 15.9 on every surface he uses daily. "Not a half-baked knockoff."
2. **Source of truth for "how Samsung does it":** the private Samsung parity research archive (raw research dataset) and the private Samsung parity research archive (187 findings). The parity spec built from them is `docs/product/SAMSUNG_PARITY_SPEC.md` (#509). Cite finding IDs. The research is reference only; write our own Kotlin.
3. **Extras sit where Samsung would put them.** Face/People search, "find similar", Photo DNA, declutter and the file-size sort live in menus, Search, the People tab or the viewer overflow, never as extra chrome on core screens.
4. **Fast and snappy over cautious.** It is his personal app. No blocking screens, consent ceremony or safety text beyond what Android itself enforces; refresh in the background; open instantly into the cached gallery.
5. **He wants it all.** Full Samsung Gallery feature parity is the target, not only the items he happens to name (e.g. swipe up on a photo for its details panel, 2026-11-10).
6. **Holistic, not piecemeal.** Owner feedback is a symptom list. Fix the surface the way Samsung does it, don't patch only the named item.

## Removal list (default: remove)
Anything Samsung Gallery doesn't have goes, unless the parity spec names a reason to keep it. Known examples: "SCRUBBING" / "DRAG THUMBNAILS TO SCRUB" labels, the extra timeline bar under the viewer filmstrip, oversized headers and viewer chrome, explanatory banners and texts, the "Updating your allowed photos…" blocker, letterboxed real-ratio tiles.

## Not decided here
Owner-gated items in AGENTS.md / SECURITY.md (signing, protected paths, real-device installs beyond the approved release channel) still apply.

Source messages: Lukas 2026-11-09 23:38Z, 23:47Z, 23:49Z, 23:55Z (project Strategy thread, ATLAS Samsung-feel drive). Goal: #509.
