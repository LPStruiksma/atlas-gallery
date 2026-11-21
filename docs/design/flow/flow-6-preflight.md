# FLOW-6 preflight visual evidence — synthetic board

> **Scope and evidence status.** This is an inspectable design preflight for [#825](https://github.com/LPStruiksma/atlas-gallery/issues/825), not a runtime capture or a Design PASS. The [screen-sized annotated frames](#screen-sized-graphical-evidence--primary-inspection-index) and [contact-sheet overview](flow-6-preflight.svg) uses synthetic shapes and pixels only: it is **not an emulator**, contains no proprietary Samsung or personal imagery, and does not claim real safe-inset measurements. The dashed safe-inset placeholders are explicitly **not measured**. Actual Media3 `SurfaceView` / `PlayerView` stacking remains **unverified on Android**.

## Decision frame

The motion source is [FLOW-6/brief-v2](https://github.com/LPStruiksma/atlas-gallery/issues/715#issuecomment-7055644601), supplemented by the [missing-graphics preflight](https://github.com/LPStruiksma/atlas-gallery/issues/715#issuecomment-7056114689), and the visual authority is [Quiet Frame v3](../QUIET_FRAME.md). The board preserves the black viewer ground, unchanged `#3448E0` action slab, and held `#8C9BFF` sphere plus 2 dp white-ring filmstrip. It invents no new production UI.

**Baseline versus proposal.** Existing ATLAS behavior is a warm borrowed cached bitmap → 800 px preview → delayed 2400 px HQ path for photos, and a 700 px poster path for video. The proposal keeps that useful warm content but prevents a blink: release a borrowed bitmap only after P2 paints; retain the preview through P3 and dissolve HQ over it at P4 in at most 120 ms, removing preview only when HQ is fully opaque. Mount the player at V1 behind the poster; after a valid live frame is confirmed at V2, dissolve the poster as `1−p` at V3 and remove it at V4 (200 ms target, at most 350 ms). Nothing animates merely because time elapsed: readiness, URI + edit revision, and the current request token gate transitions.

## Screen-sized graphical evidence — primary inspection index

**Every L and D link opens its own complete 1080 × 2340 SVG design frame**, not a mini-panel or an emulator capture. The compact [contact sheet](flow-6-preflight.svg) is navigation only. P1 retains the actual P0 cached bitmap; V2 paints the live video underneath a still fully opaque poster. All frames use synthetic content and schematic, explicitly unmeasured safe-inset guides; the screen's unchanged black viewer, lapis action slab and held-sphere filmstrip are shown with constant bounds. Text overlays are **review annotations, not app UI**. The cyan R Fit outline and yellow C viewport outline are held fixed for each sequence; all compositor layers share T. P5 illustrates an anchored shared zoom transform. SurfaceView stacking cannot be proven by these SVGs.

### Photo arrival

| Full-size state | Light SVG | Dark SVG | Rendered ownership / invariant | Acceptance reference |
|---|---|---|---|---|
| P0 Warm grid bitmap at first frame | [1080×2340 L](frames/p0-l.svg) | [1080×2340 D](frames/p0-d.svg) | P0 warm borrow, HQ α=0; Warm borrowed image already painted; no blank frame | #715 current URI/revision/request, valid pixels at shared C/R/T |
| P1 800 px request pending | [1080×2340 L](frames/p1-l.svg) | [1080×2340 D](frames/p1-d.svg) | P1 same borrowed cache α=1; no extra spinner; Same P0 cached pixels and R; request does not cover the bitmap | #715 current URI/revision/request, valid pixels at shared C/R/T |
| P2 800 px preview committed | [1080×2340 L](frames/p2-l.svg) | [1080×2340 D](frames/p2-d.svg) | P2 preview α=1, borrowed cache released; Release borrowed cache only after preview is painted | #715 current URI/revision/request, valid pixels at shared C/R/T |
| P3 2400 px HQ pending | [1080×2340 L](frames/p3-l.svg) | [1080×2340 D](frames/p3-d.svg) | P3 preview α=1, delayed HQ α=0; Preview remains fully opaque until same-revision HQ is ready | #715 current URI/revision/request, valid pixels at shared C/R/T |
| P4-p000 HQ replacement — p=0.00 | [1080×2340 L](frames/p4-p000-l.svg) | [1080×2340 D](frames/p4-p000-d.svg) | P4 preview α=1; HQ α=0.00; ≤120 ms; HQ overlays fully opaque preview in identical R/T; max 120 ms | #715 current URI/revision/request, valid pixels at shared C/R/T |
| P4-p025 HQ replacement — p=0.25 | [1080×2340 L](frames/p4-p025-l.svg) | [1080×2340 D](frames/p4-p025-d.svg) | P4 preview α=1; HQ α=0.25; ≤120 ms; HQ overlays fully opaque preview in identical R/T; max 120 ms | #715 current URI/revision/request, valid pixels at shared C/R/T |
| P4-p050 HQ replacement — p=0.50 | [1080×2340 L](frames/p4-p050-l.svg) | [1080×2340 D](frames/p4-p050-d.svg) | P4 preview α=1; HQ α=0.50; ≤120 ms; HQ overlays fully opaque preview in identical R/T; max 120 ms | #715 current URI/revision/request, valid pixels at shared C/R/T |
| P4-p075 HQ replacement — p=0.75 | [1080×2340 L](frames/p4-p075-l.svg) | [1080×2340 D](frames/p4-p075-d.svg) | P4 preview α=1; HQ α=0.75; ≤120 ms; HQ overlays fully opaque preview in identical R/T; max 120 ms | #715 current URI/revision/request, valid pixels at shared C/R/T |
| P4-p100 HQ replacement — p=1.00 | [1080×2340 L](frames/p4-p100-l.svg) | [1080×2340 D](frames/p4-p100-d.svg) | P4 preview α=1; HQ α=1.00; ≤120 ms; HQ fully opaque: release preview only after this painted frame | #715 current URI/revision/request, valid pixels at shared C/R/T |
| P5 Zoom/pan anchor held | [1080×2340 L](frames/p5-l.svg) | [1080×2340 D](frames/p5-d.svg) | P5 shared zoom pose T=1.30 with held finger anchor; HQ and preview must share T; pink anchor stays fixed during promotion | #715 current URI/revision/request, valid pixels at shared C/R/T |

### Video handoff

| Full-size state | Light SVG | Dark SVG | Rendered ownership / invariant | Acceptance reference |
|---|---|---|---|---|
| V0 700 px video poster | [1080×2340 L](frames/v0-l.svg) | [1080×2340 D](frames/v0-d.svg) | V0 poster α=1, live not ready; Valid poster pixels own the frame before player allocation | #715 current URI/revision/request, valid pixels at shared C/R/T |
| V1 Player mounted, no first frame | [1080×2340 L](frames/v1-l.svg) | [1080×2340 D](frames/v1-d.svg) | V1 player mounted; poster α=1, video not valid; Mounting Media3 is not proof of live pixels; poster stays | #715 current URI/revision/request, valid pixels at shared C/R/T |
| V2 Live first frame confirmed | [1080×2340 L](frames/v2-l.svg) | [1080×2340 D](frames/v2-d.svg) | V2 live video α=1 BELOW poster α=1; Video is ready beneath the fully opaque poster; do not clear poster yet | #715 current URI/revision/request, valid pixels at shared C/R/T |
| V3-p025 Poster handoff — p=0.25 | [1080×2340 L](frames/v3-p025-l.svg) | [1080×2340 D](frames/v3-p025-d.svg) | V3 live α=1; poster α=0.75; 200 ms target; Valid live pixels remain below poster throughout ≤350 ms dissolve | #715 current URI/revision/request, valid pixels at shared C/R/T |
| V3-p050 Poster handoff — p=0.50 | [1080×2340 L](frames/v3-p050-l.svg) | [1080×2340 D](frames/v3-p050-d.svg) | V3 live α=1; poster α=0.50; 200 ms target; Valid live pixels remain below poster throughout ≤350 ms dissolve | #715 current URI/revision/request, valid pixels at shared C/R/T |
| V3-p075 Poster handoff — p=0.75 | [1080×2340 L](frames/v3-p075-l.svg) | [1080×2340 D](frames/v3-p075-d.svg) | V3 live α=1; poster α=0.25; 200 ms target; Valid live pixels remain below poster throughout ≤350 ms dissolve | #715 current URI/revision/request, valid pixels at shared C/R/T |
| V4 Poster removed | [1080×2340 L](frames/v4-l.svg) | [1080×2340 D](frames/v4-d.svg) | V4 live α=1, poster removed after fade; Only matching current player frame remains, after readiness | #715 current URI/revision/request, valid pixels at shared C/R/T |

### Edge and state companions

| Full-size state | Light SVG | Dark SVG | Rendered ownership / invariant | Acceptance reference |
|---|---|---|---|---|
| S01 Portrait aspect preserved | [1080×2340 L](frames/s01-l.svg) | [1080×2340 D](frames/s01-d.svg) | Portrait Fit R, unchanged common C/T; Oriented portrait fit R; both quality layers share its bounds | #715 current URI/revision/request, valid pixels at shared C/R/T |
| S02 Extreme panorama fit | [1080×2340 L](frames/s02-l.svg) | [1080×2340 D](frames/s02-d.svg) | 5:1 panorama Fit R with black letterbox; Panorama uses shallow centred R; no forced full-screen crop | #715 current URI/revision/request, valid pixels at shared C/R/T |
| S03-warm Warm cache path | [1080×2340 L](frames/s03-warm-l.svg) | [1080×2340 D](frames/s03-warm-d.svg) | Warm valid cache α=1; Same-key cache is valid and painted from first frame | #715 current URI/revision/request, valid pixels at shared C/R/T |
| S03-cold Cold miss, intentional black | [1080×2340 L](frames/s03-cold-l.svg) | [1080×2340 D](frames/s03-cold-d.svg) | Cold miss: no valid bitmap, no stale paint; No valid borrowed pixels: quiet black until 800 px paint | #715 current URI/revision/request, valid pixels at shared C/R/T |
| S04-previewfail 800 px preview failed | [1080×2340 L](frames/s04-previewfail-l.svg) | [1080×2340 D](frames/s04-previewfail-d.svg) | Preview failed; warm cache α=1 retained; Keep same-revision warm cache if valid; allow HQ retry | #715 current URI/revision/request, valid pixels at shared C/R/T |
| S04-hqfail 2400 px HQ failed | [1080×2340 L](frames/s04-hqfail-l.svg) | [1080×2340 D](frames/s04-hqfail-d.svg) | HQ failed; preview α=1 retained; Keep preview α=1; never swap it for an error rectangle | #715 current URI/revision/request, valid pixels at shared C/R/T |
| S05 Edited revision rejects stale callback | [1080×2340 L](frames/s05-l.svg) | [1080×2340 D](frames/s05-d.svg) | URI u, r18, q18; reject r17/q17 response; Current r18 painted; old r17 must not cover its pixels | #715 current URI/revision/request, valid pixels at shared C/R/T |
| S06 Rapid swipe cancels prior item | [1080×2340 L](frames/s06-l.svg) | [1080×2340 D](frames/s06-d.svg) | Current item B/qB; cancel item A/qA; Item B owns R; late callback from item A is discarded | #715 current URI/revision/request, valid pixels at shared C/R/T |
| S07-delay Delayed video first frame | [1080×2340 L](frames/s07-delay-l.svg) | [1080×2340 D](frames/s07-delay-d.svg) | No first frame yet; poster α=1; Valid poster remains while frame event is delayed | #715 current URI/revision/request, valid pixels at shared C/R/T |
| S07-fail Failed video first frame | [1080×2340 L](frames/s07-fail-l.svg) | [1080×2340 D](frames/s07-fail-d.svg) | Failed player; poster α=1, no dissolve; No valid live frame or fade on failure; keep poster | #715 current URI/revision/request, valid pixels at shared C/R/T |
| S08-f100 System font 100% | [1080×2340 L](frames/s08-f100-l.svg) | [1080×2340 D](frames/s08-f100-d.svg) | Font scale 100%; unchanged C/R/T and slab; No additional viewer controls; slab bounds are fixed | #715 current URI/revision/request, valid pixels at shared C/R/T |
| S08-f200 System font 200% | [1080×2340 L](frames/s08-f200-l.svg) | [1080×2340 D](frames/s08-f200-d.svg) | Font scale 200%; unchanged C/R/T and slab; Annotation text changes; actual viewer slab remains same size | #715 current URI/revision/request, valid pixels at shared C/R/T |
| S09-empty Empty media set | [1080×2340 L](frames/s09-empty-l.svg) | [1080×2340 D](frames/s09-empty-d.svg) | Empty library: no frame owner; quiet black; No selected URI, stale player, image, or transition | #715 current URI/revision/request, valid pixels at shared C/R/T |
| S09-large 50k-item collection | [1080×2340 L](frames/s09-large-l.svg) | [1080×2340 D](frames/s09-large-d.svg) | Large library: selected URI only; Only selected (u,r,q) may paint; no per-frame list walk | #715 current URI/revision/request, valid pixels at shared C/R/T |
| S10 Accessibility semantics | [1080×2340 L](frames/s10-l.svg) | [1080×2340 D](frames/s10-d.svg) | TalkBack item/action names and logical focus order; Visual focus is illustrative; media/controls have named roles | #715 current URI/revision/request, valid pixels at shared C/R/T |
| S11-before Reduced motion: photo before ready | [1080×2340 L](frames/s11-before-l.svg) | [1080×2340 D](frames/s11-before-d.svg) | Animator=0; before readiness P2 preview α=1; Do not cut until matching HQ pixels are actually ready | #715 current URI/revision/request, valid pixels at shared C/R/T |
| S11-after Reduced motion: photo after ready | [1080×2340 L](frames/s11-after-l.svg) | [1080×2340 D](frames/s11-after-d.svg) | Animator=0; ready-only cut to HQ α=1; Instant P2→P4 cut once same-revision HQ is fully ready | #715 current URI/revision/request, valid pixels at shared C/R/T |
| S12-before Reduced motion: video before frame | [1080×2340 L](frames/s12-before-l.svg) | [1080×2340 D](frames/s12-before-d.svg) | Animator=0; before event poster α=1; Keep poster until matching first live frame is valid | #715 current URI/revision/request, valid pixels at shared C/R/T |
| S12-after Reduced motion: video after frame | [1080×2340 L](frames/s12-after-l.svg) | [1080×2340 D](frames/s12-after-d.svg) | Animator=0; ready-only cut to live α=1; Instant V2→V4 cut after confirmed first frame only | #715 current URI/revision/request, valid pixels at shared C/R/T |

**Audit convention:** Photo layers are drawn bottom-to-top at the same computed Fit R for oriented media; P4 uses distinct full-resolution frames at p=0/.25/.50/.75/1 with preview α=1 underneath. V2 has valid video α=1 beneath poster α=1, V3 keeps video α=1 beneath poster α=1−p, V4 removes the poster. All frame headers identify layer order, readiness and cancellation; light/dark themes change only system-theme annotation/inset styling, never the black viewer composition. The short #715 acceptance line is: *matching URI + edit revision + active request token continuously own visible pixels; replace only after ready and never jump C/R/T.*

## Indexed photo and video matrix

In every link, **L/D** means the light/dark *system-theme pair* around the same invariant black viewer. Yellow **C** is the container/crop outline, cyan **R** the fitted media rectangle, and **T** the one shared fit/crop plus zoom/pan transform. The slab and filmstrip remain unchanged. `u` is the selected media URI, `r` its edit revision, and `q` the cancellable request token.

| State and rendered pair | Visible owner and alpha | Crop / transform | Identity and cancellation | Theme and #715 acceptance line |
|---|---|---|---|---|
| P0 cached bitmap — [L](frames/p0-l.svg) / [D](frames/p0-d.svg) | borrowed cache α=1 | C/R, T=fit | `(u,r,q)`; discard mismatches | paired; warm content is visible, never a blank flash |
| P1 warm-cache wait — [L](frames/p1-l.svg) / [D](frames/p1-d.svg) | warm cache α=1; separate S03-cold shows black only on cache miss | same C/R/T reserved | 800 px request owns q | paired; cold black is intentional, not a warm-cache regression |
| P2 preview painted — [L](frames/p2-l.svg) / [D](frames/p2-d.svg) | 800 px preview α=1; borrowed cache released after paint | same C/R/T | paint must match `(u,r,q)` | paired; first replacement is already painted |
| P3 hold preview — [L](frames/p3-l.svg) / [D](frames/p3-d.svg) | preview α=1 while 2400 px HQ decodes | unchanged C/R/T | stale/cancelled HQ cannot paint | paired; delayed HQ cannot expose black |
| P4 HQ dissolve — [L](frames/p4-p050-l.svg) / [D](frames/p4-p050-d.svg) | HQ `p=0/.25/.5/.75/1` over preview α=1; remove preview only at HQ α=1 | identical C/R/T on both layers | one `(u,r,q)`; ≤120 ms after ready | paired; no geometry jump or transparent gap |
| P5 zoom/pan — [L](frames/p5-l.svg) / [D](frames/p5-d.svg) | HQ α=1 | shared T updates with fixed magenta finger anchor | q remains current during gesture | paired; replacement preserves the same anchored geometry |
| V0 poster — [L](frames/v0-l.svg) / [D](frames/v0-d.svg) | 700 px poster α=1 | C/R/T shared with video | `(u,r,q)` poster | paired; poster supplies valid pixels |
| V1 player mounted — [L](frames/v1-l.svg) / [D](frames/v1-d.svg) | poster α=1 over mounted player with no valid frame | same C/R/T | player belongs to current q only | paired; mount does not clear poster |
| V2 live confirmed — [L](frames/v2-l.svg) / [D](frames/v2-d.svg) | valid video α=1 below poster α=1 | same C/R/T | first-frame callback must match `(u,r,q)` | paired; transition starts only after valid live pixels |
| V3 poster dissolve — [L](frames/v3-p050-l.svg) / [D](frames/v3-p050-d.svg) | video α=1; poster `1−p` at p=.25/.5/.75 | exactly shared C/R/T | cancellation restores current owner, never stale player | paired; continuous valid pixels during crossfade |
| V4 poster gone — [L](frames/v4-l.svg) / [D](frames/v4-d.svg) | video α=1; poster removed | unchanged C/R/T | current `(u,r,q)` only; 200 ms target / ≤350 ms | paired; no post-transition poster or blink |

## Edge and state companion matrix

| State and rendered pair | Owner / alpha and geometry | URI, revision, cancellation, accessibility | Theme and #715 acceptance line |
|---|---|---|---|
| S01 portrait — [L](frames/s01-l.svg) / [D](frames/s01-d.svg) | valid media α=1; portrait R within C using T | same `(u,r,q)` contract | paired; aspect ratio does not change layer ownership |
| S02 extreme panorama — [L](frames/s02-l.svg) / [D](frames/s02-d.svg) | valid media α=1; shallow R using the same T | same `(u,r,q)` contract | paired; extreme ratio stays stable through replacement |
| S03 warm / cold black — [L](frames/s03-warm-l.svg) / [D](frames/s03-warm-d.svg) | warm cache remains α=1; cold path uses viewer black | geometry reserved before pixels | paired; distinguishes existing warm borrow from true cold load |
| S04 preview / HQ fail — [L](frames/s04-previewfail-l.svg) / [D](frames/s04-previewfail-d.svg) | last valid preview remains α=1 in C/R/T | failed HQ for current q does not clear preview | paired; failure never creates a blink |
| S05 stale edited revision — [L](frames/s05-l.svg) / [D](frames/s05-d.svg) | old rev cannot cover new rev; only matching layer α=1 | same URI with `r17 ≠ r18`; reject stale result | paired; revision is part of paint identity |
| S06 rapid swipe cancellation — [L](frames/s06-l.svg) / [D](frames/s06-d.svg) | B owns visible layers; A can no longer paint | cancel qA, create qB; callbacks validate selection | paired; rapid navigation cannot flash the prior item |
| S07 delayed / failed first video frame — [L](frames/s07-delay-l.svg) / [D](frames/s07-delay-d.svg) | poster remains α=1; player is not evidence of valid pixels | current q may retry; stale callback ignored | paired; timeout/failure does not expose an empty surface |
| S08 font 100% / 200% — [L](frames/s08-f100-l.svg) / [D](frames/s08-f100-d.svg) | slab bounds and media C/R/T unchanged | labels remain named/focusable; no added chrome | paired; font scale does not move or resize the slab in this preflight |
| S09 empty / large library — [L](frames/s09-empty-l.svg) / [D](frames/s09-empty-d.svg) | no-media case has no stale owner; large library uses current q only | empty and 50k-item conditions share cancellation rules | paired; collection size does not weaken no-stale-paint behavior |
| S10 accessibility — [L](frames/s10-l.svg) / [D](frames/s10-d.svg) | focus outline is illustrative; content remains α=1 and stationary | expose media description, action names, state, and logical focus order | paired; semantics do not depend on animation or colour alone |
| S11 ready-only reduced-motion photo — [L](frames/s11-before-l.svg) / [D](frames/s11-before-d.svg) | P2 preview α=1 cuts directly to P4 HQ α=1 | only after matching `(u,r,q)` HQ is ready | paired; remove animations means instant ready-state cut, not early clear |
| S12 ready-only reduced-motion video — [L](frames/s12-before-l.svg) / [D](frames/s12-before-d.svg) | V2 poster α=1 cuts to V4 video α=1 | only after matching `(u,r,q)` valid frame | paired; reduced motion preserves first-frame safety |

## Review boundary

The board is a visual contract, not proof of Android composition behavior, frame timing, or inset dimensions. In particular, real Media3 `SurfaceView` / `PlayerView` z-order, alpha, and first-frame behavior must be verified on Android before implementation acceptance. The explicit **#715 acceptance line** across every panel is: *for the current URI and edited revision, cancellation-safe valid pixels continuously own the shared fitted/cropped/zoomed rectangle; incoming content replaces outgoing content only when ready, and reduced motion cuts only between ready states.*
