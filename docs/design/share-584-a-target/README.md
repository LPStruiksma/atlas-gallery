# SHARE-1A-E · share without location: identifiable photo at 200 % text

**Status: synthetic design evidence for independent artifact Review.** Not an Android screenshot, a Samsung observation, runtime evidence or a design PASS. Unit [#1117](https://github.com/LPStruiksma/atlas-gallery/issues/1117); parent [#584](https://github.com/LPStruiksma/atlas-gallery/issues/584); goal [#97](https://github.com/LPStruiksma/atlas-gallery/issues/97). Scope is this directory only. It covers flow **A** (share with *Remove location*) and nothing of B/C (wallpaper, copy) or D (date and location). The pinned v3 baseline in [`../share-584-target`](https://github.com/LPStruiksma/atlas-gallery/tree/7857a46f8df06491dd00719303c5236f94b5fd94/docs/design/share-584-target) is read-only and unchanged; this directory has its own renderer and does not import it.

## What this fixes

The [v3 independent preflight](https://github.com/LPStruiksma/atlas-gallery/issues/584#issuecomment-7232535564) failed A at 200 % text: `A-ready-light-200` covered the whole photo at the moment of dispatch and `A-nogps-light-200` left only a nondescript strip, so the user could not tell which item they were about to share.

Here the share sheet has three zones in every state, orientation, theme and text size:

| Zone | Holds | Scrolls? |
| --- | --- | --- |
| **Pinned header** | Title *Share photo* and the **item identity**: a thumbnail of the photo being shared, **fitted whole** (`preserveAspectRatio meet`, never centre-cropped), plus its name and date (`Lake photo`, `14 Jul 2024, 14:05`, synthetic). Portrait: a 102 dp thumbnail box beside the text. Landscape: a left column with title, thumbnail and text. | Never |
| **Scroll region** | The status message (first, so an alert is visible where focus lands), then the *Remove location* row. Off has the row first and its helper text after. | When it overflows |
| **Pinned footer** | Cancel + Share, Cancel + Retry, or Cancel alone (unsupported). | Never |

Text is never shrunk: every 200 % frame uses exactly twice the 100 % sheet type sizes (verified). When the scroll region overflows, the **initial** frame and a **scrolled-to-end** frame are both rendered; header and footer stay anchored, a scroll indicator shows position, and the scrolled frame's focus ring sits on the last fully visible control in the region. No content band is left unrendered (the verifier checks the offsets cover the full content height; a middle frame is generated automatically when one viewport is not enough, and none is needed at the current sizes).

## State machine (unchanged from v3, re-verified)

The *Remove location* preference is **Off only on first use** and changes only when the user flips the switch while the sheet is idle (Off, ready or no-location). Atlas, a failure or a recovery button never changes it. When it is **On (remembered)**, viewer Share opens straight into *preparing*.

| State | Remove location | Actions (focus order) | Dispatch |
| --- | --- | --- | --- |
| `off` (first use) | Off, switch | identity, switch, helper, Cancel, Share | Original provider URI with read grant (the user's explicit choice) |
| `preparing` | On, badge “Stays on for this share” | identity, status (polite live region), badge, Cancel, Share **disabled** | **None** until the copy is verified |
| `ready` | On, switch | identity, status, switch, Cancel, Share | Verified temporary GPS-free derivative only |
| `nogps` | On, switch | identity, status, switch, Cancel, Share | Still the verified derivative, never the original |
| `failed` | On, badge | **alert (focused)**, identity, badge, Cancel, Retry | **Blocked**: no intent, no original fallback |
| `unknown` | On, badge | **alert (focused)**, identity, badge, Cancel, Retry | **Blocked** |
| `unsupported` | On, badge | **alert (focused)**, identity, badge, Cancel | **Blocked** |

Unreadable or redacted location is `unknown`, never `nogps`. Retry → `preparing`. Share → Android sharesheet (system-owned, not drawn) → back to the **same viewer item**. Cancel, system Back, or a tap on the visible scrim at any state → same viewer item, nothing sent; an in-flight preparation is cancelled and its temporary file deleted. While the sheet is open the viewer behind is inert and absent from focus order. Alerts take focus first and carry an outline; errors are never colour-only.

**Loading vocabulary.** *Preparing* uses Quiet Frame's held sphere climbing an arc beside plain progress copy (“Step 1 of 2, removing location”), replacing v3's generic ellipsis. Step 2 is verification of the derivative.

## Temporary copy, grant lifetime and late reads (specified, runtime-untested)

* The derivative is written to an app-private cache subdirectory used only for share derivatives, never beside the original. The original file is never rewritten by A.
* It is offered through a `FileProvider` `content://` URI with `FLAG_GRANT_READ_URI_PERMISSION` on the `ACTION_SEND` intent and its `ClipData`. No write grant. The provider's paths expose that subdirectory only, so no grant can reach the original. Today's manifest declares one `FileProvider` for diagnostics (`atlas_diagnostics_file_paths`); whether production adds a path there or a separate provider is for the A production preflight, and **any manifest edit is separately owner-gated**.
* **No cleanup on chooser return.** The recipient may read after the sharesheet closes (uploads, background sends), so the file is not deleted when Atlas regains focus.
* **Bounded window.** The derivative is kept for a bounded retention window after dispatch (proposed 24 h; the exact value is a production-preflight decision) and then deleted. Expired derivatives are also swept on next app start. Reads after deletion fail at the provider; there is **no fallback** to the original URI. Android itself bounds the URI grant to the receiving component's lifetime; Atlas does not extend it.
* Cleanup also runs on Cancel and on failure before dispatch. Offline makes no difference: preparation is local.

## Frames

83 frames: 41 portrait 1080×2340, 42 landscape 2340×1080, at 2.8125 px/dp with synthetic insets L/T/R/B portrait 0/125/0/135 px and landscape 125/68/135/0 px (from the #142 target, not measured on an S24+).

| Set | Frames |
| --- | --- |
| Entry | `A-viewer` (photo-first viewer, Share on the slab) |
| Portrait 100 % | all 7 states × light, dark (14) |
| Portrait 200 % | all 7 states × light, dark (14), plus `-scrolled` for the 12 that overflow (Off fits) |
| Landscape 200 % | all 7 states × light, dark (14), plus `-scrolled` for all 14 |
| Landscape 100 % | all 7 states × light, dark (14; none overflows) |
| Overviews | `png/contact-portrait-100.png`, `png/contact-portrait-200.png`, `png/contact-landscape-100.png`, `png/contact-landscape-200.png` (downscaled, labelled; for navigation only, review the full-size PNGs) |

Direct links for the frames the preflight named (pinned URLs to the reviewed head are in the PR): [`A-ready-light-200`](png/A-ready-light-200.png), [`A-ready-light-200-scrolled`](png/A-ready-light-200-scrolled.png), [`A-nogps-light-200`](png/A-nogps-light-200.png), [`A-failed-light-200`](png/A-failed-light-200.png), [`A-failed-dark-200`](png/A-failed-dark-200.png), [`A-failed-light-200-landscape`](png/A-failed-light-200-landscape.png).

[`geometry.json`](geometry.json) lists per frame: every control with id, role, label, rectangle, region (`pinned` or `scroll`) and visibility (`full`, `partial`, `none`, with the visible rectangle); focus order; the focused control and focus ring; the modal surface; the thumbnail box, fitted image rectangle and photo landmarks; the scroll viewport, content height, offset and indicator; dismissal; and the share-state record (preference, dispatch, whether the original URI is exposed).

## Reproduce and verify

```bash
python3 docs/design/share-584-a-target/build_frames.py                       # SVG + geometry.json
python3 docs/design/share-584-a-target/render_and_verify.py --render --verify # PNG + contact sheets + checks
python3 docs/design/share-584-a-target/render_and_verify.py --self-test       # guards reject mutated geometry
```

Needs Python 3, CairoSVG, Pillow and DejaVu Sans. `--verify` re-renders every SVG and compares it with the committed PNG (and rebuilds the contact sheets), then checks:

* **Identity:** in every share-state frame the thumbnail is pinned outside the scroll region, inside the modal and the safe area, ≥ 64 dp on its short side, drawn with `meet` at the photo's own aspect ratio, and six landmark pixels (both corners, sun, lighthouse, sail, hull) render in their exact colours, so the whole photo is visible; the item name is visible text and the complete date and time appear as consecutive visible lines in reading order.
* **Actions:** Cancel and Share/Retry are pinned, fully visible and outside the scroll region in every frame, including scrolled ones.
* **Scrolling:** an overflowing frame has a scrolled-to-end sibling, the offsets cover the whole content, each has an indicator, and the focus ring encloses a fully visible control.
* **Accessibility geometry:** tappable controls ≥ 135 px (48 dp), inside frame, safe insets and modal, no overlaps; focus order equals the active controls; the viewer is inert; an alert is first and fully visible in the initial frame; switch tracks ≥ 3:1 against their row; every visible text run ≥ 4.5:1 against its rendered background.
* **Palette:** every UI colour outside the photo art is a Quiet Frame v3 token.
* **Privacy state:** Share disabled **and dispatch recorded as `disabled`** while preparing; no Share after failure; On stays remembered in every non-Off state; the original URI is offered only in Off; failure dispatch is blocked.
* **Scale:** 200 % sheet type sizes are exactly 2× the 100 % ones.

* **Coverage:** every state × theme × text scale × orientation (7 × 2 × 2 × 2 = 56 cells) has its base frame, plus the viewer entry; a missing combination fails.

`--self-test` mutates a copy of `geometry.json` and shows each guard rejects it: preparing that dispatches a derivative or the original, an enabled Share while preparing, Share after failure, the original URI exposed when On, a missing landscape 100 % frame, a missing scrolled sibling; and that the date check accepts only the complete `14 Jul 2024, 14:05` across consecutive visible lines.

Current result at the PR head is recorded in the PR: **83 frames, 0 errors**, minimum text contrast 5.65:1; self-test pass.

## Matrix: rendered, specified, runtime-untested

| Situation | Status |
| --- | --- |
| All 7 states, light and dark, portrait 100 % and 200 % | **Rendered** |
| All 7 states, light and dark, landscape 200 %, initial and scrolled | **Rendered** |
| All 7 states, light and dark, landscape 100 % | **Rendered** |
| Item identity (fitted thumbnail, name, date) in every Atlas-owned state | **Rendered and verified** |
| Share/Cancel or Retry/Cancel reachable without shrinking text | **Rendered and verified** |
| Scrolled states with anchored context, focus, dismissal | **Rendered** (scroll end); middle state generated only when needed (none at current sizes) |
| On remembered → straight to preparing | Specified; `preparing` frames are the entry |
| Android sharesheet and return to the same item | Specified (system-owned; drawn in v3 as a placeholder) |
| Cancel / Back / scrim dismissal, temporary-file deletion on cancel | Specified; Cancel rendered in every frame |
| Grant lifetime, late reads, bounded retention, no chooser-return cleanup, start-up sweep | **Specified above** |
| Huge or unsupported media, offline | Specified (`unsupported` rendered; offline has no effect) |
| TalkBack announcements, real focus traversal and scroll-into-view, runtime fonts (Funnel Sans), FileProvider grants, EXIF removal and verification, file deletion | **Runtime-untested** |

## Gates

No Kotlin, manifest, intent, grant, EXIF or original-media change is made here. An independent reviewer must open the full-size PNGs; artifact Review of this PR is not parent design approval. After this merges, parent Strategy packages a separate A production preflight; implementation still needs an independent visual PASS, later synthetic Android tests and the [#142](https://github.com/LPStruiksma/atlas-gallery/issues/142)/[#573](https://github.com/LPStruiksma/atlas-gallery/issues/573) ownership check. Any manifest edit is separately owner-gated. No Samsung parity is claimed.
