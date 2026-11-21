# SHARE-1P · four-flow share and details design target (v1)

**Status: synthetic design proposal for independent preflight; not a Samsung observation, Android implementation, runtime screenshot, or design PASS.** Unit [#843](https://github.com/LPStruiksma/atlas-gallery/issues/843); parent [#584](https://github.com/LPStruiksma/atlas-gallery/issues/584); goal [#97](https://github.com/LPStruiksma/atlas-gallery/issues/97). The scope is this directory only. Every lighthouse/lake image is drawn from deterministic shapes; no private photo, coordinates or real user record.

## Decisions, sources and deviations

| Decision | Evidence and source | Status |
| --- | --- | --- |
| Black photo-first viewer, held sphere and white-ring strip, lapis slab, no permanent extra buttons | [Quiet Frame v3](../QUIET_FRAME.md), [owner intent](../../OWNER_INTENT.md), viewer target [#142](https://github.com/LPStruiksma/atlas-gallery/issues/142) | Proposed adaptation to ATLAS; not shipped |
| A: one short options sheet before Android's sharesheet, `Remove location` **off** initially | Android [FileProvider](https://developer.android.com/reference/androidx/core/content/FileProvider) and [sharing](https://developer.android.com/training/sharing/send), #843 | Proposed; no verified Samsung UI location |
| B: wallpaper as a viewer-overflow action, then `ACTION_ATTACH_DATA` | Android [Intent](https://developer.android.com/reference/android/content/Intent) | System chooser UI drawn only conceptually; actual handlers unknown |
| C: overflow `Copy image` passes a readable `content://` URI in `ClipData`, with system feedback | Android [clipboard](https://developer.android.com/develop/ui/views/touch-and-input/copy-paste) | Receiver grant and lifetime not tested |
| D: swipe up → Details → `Edit date and location` → consent → save + Undo | Android [MediaStore](https://developer.android.com/reference/android/provider/MediaStore), [shared storage](https://developer.android.com/training/data-storage/shared/media) | Strong original-byte rollback is a high-risk design dependency |
| Filmstrip white 2 dp ring and `#8C9BFF` sphere gain a black **1 dp external keyline** over bright imagery | [#142 critique](https://github.com/LPStruiksma/atlas-gallery/issues/142#issuecomment-7056330457) | **Design adaptation only.** Does not assert #142 implemented or fixed |
| Samsung behavior and finding IDs for these exact four actions | Private finding archive not included in the public spec | **UNVERIFIED**: no screenshots, OEM geometry, findings or parity assertion fabricated |

## Flow transitions, ownership and content rules

**A / location-free share.** `F0` viewer → `F1` ATLAS options sheet → `F2` Android sharesheet → `F3` same viewer item. Off sends the unchanged provider URI. On must first create and validate a *temporary*, GPS-free derivative. If strip/encoding/verification fails: block the outbound intent and show “Couldn't prepare location-free copy”, Retry or Cancel. If there is no GPS, report “No location stored”; do not claim a privacy rewrite occurred. Grant read access to the chooser and final consumer; deferred reads and URI revocation require implementation testing. Never delete the temporary file merely because a chooser returns. Do not rewrite the original or silently fall back to its URI when On.

**B / wallpaper.** Viewer overflow → “Set as wallpaper” → Android `ACTION_ATTACH_DATA` with readable `content://`, image MIME and read grant → same viewer item on cancel/return. Android owns the crop and target UI. Unsupported media, absent handler and revoked URI get a short error without mutation. No invented ATLAS cropper.

**C / copy.** Viewer overflow → “Copy image” → `ClipData` with provider URI → OS feedback → same viewer item. Check before copying that URI is readable; a broken/revoked URI never enters the clipboard. Actual app-to-app paste and recipient grant expiry are untested. Do not infer that clipboard copying makes a new media file or add a persistent success banner.

**D / date & location.** Swipe up / Details → read-only photo context → Edit date and location → compact date/time with explicit `Europe/Amsterdam` timezone **as a synthetic example**; manual place or offline lookup only when available → Save → Android write consent → mutate *only* selected EXIF fields → rescan/index → Details with Undo → viewer. Distinguish EXIF capture time, display date, UTC offset and user-selected local civil time. On Cancel/Denied/Unsupported/Full storage/Write failure, return with **byte-for-byte original and index unchanged**. The actual atomicity approach is **unresolved**, not an implementation promise: draft a staged transaction (snapshot original bytes + index, validate writable format, obtain per-item permission, write to a durable prepared copy, verify fields, atomically install only if supported, update index, recover on restart); seek independent critique of SAF/MediaStore semantics, crash windows and consent renewal. Undo after restart must restore previous values only after fresh permission, with an explicit backup-retention policy. Fail safely if the platform cannot provide atomic swap; never claim a successful edit first and roll back silently.

All platform-owned screens in the art are **conceptual placeholders**. Back/cancel from every flow returns to the same viewer item; no file writes occur in this document.

## Geometry and reproducible evidence

Individual proposed [SVG frames](svg/) are 1080×2340 portrait and physically rotated 2340×1080 landscape; see [geometry.json](geometry.json). Density **2.8125 px/dp** and insets are synthetic S24-profile design inputs copied from the [#142 emulator three-button viewer target](../viewer-142-target/README.md), not measurements of Lukas's S24+ and not independently observed on this layout. Portrait safeDrawing L/T/R/B = **0/125/0/135 px**; landscape = **125/68/135/0 px**. No gesture-navigation or cutout validation is claimed. `geometry.json` exposes every actual-pixel photo, sheet, Back, More, filmstrip and slab rectangle; all clickable header boxes and each slab slot measure at least **135 px = 48 dp** (48 × 2.8125), and the selected filmstrip hit is 140×154 px. Visual glyphs can be smaller than semantic hits. Focus order: Back → More → photo → sheet title and controls (when present) → filmstrip → slab. The focused action is the first contextual control. Landscape proposal reflows the sheet beside the photo, not on top of a tiny bottom strip. The `F1-share-200` SVG proposes **exactly 2×** type relative to `F1-share-light` for the visible sheet (title 45→90 px, toggle 36→72, helper 30→60, primary Share 39→78, Back 25→50). The 285 px (101 dp) switch section wraps the toggle below its enlarged label, and the 185 px (66 dp) Share control and 150 px (53 dp) Back region reflow vertically inside the 976×1015 px sheet; `geometry.json` records each hit rectangle. The viewer slab remains *behind* the modal sheet and is not interactive while it is open. This is a synthetic 200% **proposal**, not an Android font-scale capture; device clipping, TalkBack and accessibility reflow remain untested.

Frames: A has F0/F1 light/F1 dark/F1 200%/F2/strip error/F3 and rotated A; B shows overflow/system-owned handoff/missing handler/rotated B; C shows overflow/copy feedback/revoked URI/rotated C; D has light/dark Details, editor/IME, Undo/denied, rotated D. All frames have a recognizable synthetic lake/lighthouse/sail photo and no new permanent viewer chrome.

To reproduce PNGs from the authoritative editable SVG sources:

```bash
python3 docs/design/share-584-target/render_and_verify.py --render
python3 docs/design/share-584-target/render_and_verify.py --verify
```

Requires Python 3, CairoSVG for raster export and Pillow for PNG inspection. `--verify` checks all 23 SVG/PNG stage pairs at exact dimensions, all geometry rectangles and actual SVG text foreground against sampled text-free rendered SVG background pixels. All **23 editable SVGs and 23 real PNG files** are committed in this PR. Every PNG was retrieved through the GitHub connector and independently checked for its signature, expected 8-bit RGB dimensions, chunk CRC32, fixed-Huffman DEFLATE decompression, scanline filters and Adler32: **23/23 valid, zero errors**. The original 23-frame `geometry.json` passed **115 semantic-rectangle checks** at the previous head. The revised 200% control hit boxes and SVG-derived foreground/background verifier require an exact-head execution; this seat does not claim a fresh pass. On the validation commit all three repository CI workflows were successful. A local CairoSVG/Pillow prototype also rendered and inspected 23 frames, but the Python `--render --verify` command **was not executed on an exact-head checkout** in this seat. Editable SVG is canonical; the PNGs are synthetic raster previews made with a simplified bitmap-type fallback, so re-exporting via CairoSVG can change text glyph pixels. Independent Review should regenerate and visually compare actual frames before making a design-preflight recommendation. The F1 200%-labelled mock now doubles all five visible sheet text sizes and reflows their controls; independent rerender and runtime capture are still required. This evidence does **not** claim an Android runtime, OEM, accessibility or original-file write test.

## Matrix: shown, proposed, and untested

| Situation | A: share | B: wallpaper | C: copy | D: edit | Proof status |
| --- | --- | --- | --- | --- | --- |
| Normal and return | F0,F1,F2,F3 | B1,B2 | C1,C2 | D0,D1,D2 | SVG rendered proposal; Android untested |
| Light/dark surfaces, bright/dark media | F1 light/dark, F3 bright | B1/B2 | C1/C3 | D0 light/dark | SVG rendered proposal; no phone proof |
| No GPS / redacted metadata | F1 options, absence text planned | Unchanged URI required | Unchanged URI required | no-location field planned | **Planned, not rendered for all variants** |
| Read-only/revoked URI / missing target or consumer | F2 error; read grant pending | B3 missing handler | C3 revoked | unsupported fallback planned | Selected SVG; URI lifetime untested |
| Late recipient read / clipboard paste | Temporary copy retention TBD | Handoff grant TBD | Paste grant TBD | n/a | **Not tested** |
| Cancel / denied write consent / mid-write failure | Cancel → F3 | Cancel → viewer | Copy error → viewer | D2-denied | Selected SVG only; atomic rollback unproven |
| Undo after rescan or process restart | n/a | n/a | n/a | D2 Undo | Static design; recovery untested |
| Offline, large media, loading, narrow cutout landscape | Intent should not depend on network | no handler | invalid URI | offline/manual entry | Landscape drawn; performance/cutout not tested |
| 100%/200% text, keyboard/IME | F1-200 | system-owned | system feedback | D1-keyboard | Selected SVG; system font metrics unmeasured |
| TalkBack/focus, reach, reduced motion | Back → options → Share | More → target | More → Copy | Details → editor → consent | Specified only; platform testing required |
| Motion | ATLAS 200 ms Lift, cut for reduced-motion | OS controls system transitions | OS feedback | consent OS-owned | Specified only |

**Release gates:** Independent [design rubric](https://github.com/LPStruiksma/autodev/blob/main/docs/design/rubric.md) preflight for #584 must score checks 1–6 and 8 against rendered states *after this evidence PR is complete and merged*. #584 must not begin production Dev on these drawings alone. There is no runtime Kotlin change, no Android intent/grant/EXIF execution and no original-media modification here.
