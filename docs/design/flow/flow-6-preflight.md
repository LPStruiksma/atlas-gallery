# FLOW-6 preflight visual evidence — synthetic board

> **Scope and evidence status.** This is an inspectable design preflight for [#825](https://github.com/LPStruiksma/atlas-gallery/issues/825), not a runtime capture or a Design PASS. The [rendered 1080 × 2340 SVG board](flow-6-preflight.svg) uses synthetic shapes and pixels only: it is **not an emulator**, contains no proprietary Samsung or personal imagery, and does not claim real safe-inset measurements. The dashed safe-inset placeholders are explicitly **not measured**. Actual Media3 `SurfaceView` / `PlayerView` stacking remains **unverified on Android**.

## Decision frame

The motion source is [FLOW-6/brief-v2](https://github.com/LPStruiksma/atlas-gallery/issues/715#issuecomment-7055644601), supplemented by the [missing-graphics preflight](https://github.com/LPStruiksma/atlas-gallery/issues/715#issuecomment-7056114689), and the visual authority is [Quiet Frame v3](../QUIET_FRAME.md). The board preserves the black viewer ground, unchanged `#3448E0` action slab, and held `#8C9BFF` sphere plus 2 dp white-ring filmstrip. It invents no new production UI.

**Baseline versus proposal.** Existing ATLAS behavior is a warm borrowed cached bitmap → 800 px preview → delayed 2400 px HQ path for photos, and a 700 px poster path for video. The proposal keeps that useful warm content but prevents a blink: release a borrowed bitmap only after P2 paints; retain the preview through P3 and dissolve HQ over it at P4 in at most 120 ms, removing preview only when HQ is fully opaque. Mount the player at V1 behind the poster; after a valid live frame is confirmed at V2, dissolve the poster as `1−p` at V3 and remove it at V4 (200 ms target, at most 350 ms). Nothing animates merely because time elapsed: readiness, URI + edit revision, and the current request token gate transitions.

## Indexed photo and video matrix

In every link, **L/D** means the light/dark *system-theme pair* around the same invariant black viewer. Yellow **C** is the container/crop outline, cyan **R** the fitted media rectangle, and **T** the one shared fit/crop plus zoom/pan transform. The slab and filmstrip remain unchanged. `u` is the selected media URI, `r` its edit revision, and `q` the cancellable request token.

| State and rendered pair | Visible owner and alpha | Crop / transform | Identity and cancellation | Theme and #715 acceptance line |
|---|---|---|---|---|
| P0 cached bitmap — [L](flow-6-preflight.svg#P0-l) / [D](flow-6-preflight.svg#P0-d) | borrowed cache α=1 | C/R, T=fit | `(u,r,q)`; discard mismatches | paired; warm content is visible, never a blank flash |
| P1 wait — [L](flow-6-preflight.svg#P1-l) / [D](flow-6-preflight.svg#P1-d) | cache α=1 when warm; black + held-sphere wait only when cold | same C/R/T reserved | 800 px request owns q | paired; cold black is intentional, not a warm-cache regression |
| P2 preview painted — [L](flow-6-preflight.svg#P2-l) / [D](flow-6-preflight.svg#P2-d) | 800 px preview α=1; borrowed cache released after paint | same C/R/T | paint must match `(u,r,q)` | paired; first replacement is already painted |
| P3 hold preview — [L](flow-6-preflight.svg#P3-l) / [D](flow-6-preflight.svg#P3-d) | preview α=1 while 2400 px HQ decodes | unchanged C/R/T | stale/cancelled HQ cannot paint | paired; delayed HQ cannot expose black |
| P4 HQ dissolve — [L](flow-6-preflight.svg#P4-l) / [D](flow-6-preflight.svg#P4-d) | HQ `p=0/.25/.5/.75/1` over preview α=1; remove preview only at HQ α=1 | identical C/R/T on both layers | one `(u,r,q)`; ≤120 ms after ready | paired; no geometry jump or transparent gap |
| P5 zoom/pan — [L](flow-6-preflight.svg#P5-l) / [D](flow-6-preflight.svg#P5-d) | HQ α=1 | shared T updates with fixed magenta finger anchor | q remains current during gesture | paired; replacement preserves the same anchored geometry |
| V0 poster — [L](flow-6-preflight.svg#V0-l) / [D](flow-6-preflight.svg#V0-d) | 700 px poster α=1 | C/R/T shared with video | `(u,r,q)` poster | paired; poster supplies valid pixels |
| V1 player mounted — [L](flow-6-preflight.svg#V1-l) / [D](flow-6-preflight.svg#V1-d) | poster α=1 over mounted player with no valid frame | same C/R/T | player belongs to current q only | paired; mount does not clear poster |
| V2 live confirmed — [L](flow-6-preflight.svg#V2-l) / [D](flow-6-preflight.svg#V2-d) | valid video α=1 below poster α=1 | same C/R/T | first-frame callback must match `(u,r,q)` | paired; transition starts only after valid live pixels |
| V3 poster dissolve — [L](flow-6-preflight.svg#V3-l) / [D](flow-6-preflight.svg#V3-d) | video α=1; poster `1−p` at p=.25/.5/.75 | exactly shared C/R/T | cancellation restores current owner, never stale player | paired; continuous valid pixels during crossfade |
| V4 poster gone — [L](flow-6-preflight.svg#V4-l) / [D](flow-6-preflight.svg#V4-d) | video α=1; poster removed | unchanged C/R/T | current `(u,r,q)` only; 200 ms target / ≤350 ms | paired; no post-transition poster or blink |

## Edge and state companion matrix

| State and rendered pair | Owner / alpha and geometry | URI, revision, cancellation, accessibility | Theme and #715 acceptance line |
|---|---|---|---|
| S01 portrait — [L](flow-6-preflight.svg#S01-l) / [D](flow-6-preflight.svg#S01-d) | valid media α=1; portrait R within C using T | same `(u,r,q)` contract | paired; aspect ratio does not change layer ownership |
| S02 extreme panorama — [L](flow-6-preflight.svg#S02-l) / [D](flow-6-preflight.svg#S02-d) | valid media α=1; shallow R using the same T | same `(u,r,q)` contract | paired; extreme ratio stays stable through replacement |
| S03 warm / cold black — [L](flow-6-preflight.svg#S03-l) / [D](flow-6-preflight.svg#S03-d) | warm cache remains α=1; cold path uses viewer black | geometry reserved before pixels | paired; distinguishes existing warm borrow from true cold load |
| S04 preview / HQ fail — [L](flow-6-preflight.svg#S04-l) / [D](flow-6-preflight.svg#S04-d) | last valid preview remains α=1 in C/R/T | failed HQ for current q does not clear preview | paired; failure never creates a blink |
| S05 stale edited revision — [L](flow-6-preflight.svg#S05-l) / [D](flow-6-preflight.svg#S05-d) | old rev cannot cover new rev; only matching layer α=1 | same URI with `r17 ≠ r18`; reject stale result | paired; revision is part of paint identity |
| S06 rapid swipe cancellation — [L](flow-6-preflight.svg#S06-l) / [D](flow-6-preflight.svg#S06-d) | B owns visible layers; A can no longer paint | cancel qA, create qB; callbacks validate selection | paired; rapid navigation cannot flash the prior item |
| S07 delayed / failed first video frame — [L](flow-6-preflight.svg#S07-l) / [D](flow-6-preflight.svg#S07-d) | poster remains α=1; player is not evidence of valid pixels | current q may retry; stale callback ignored | paired; timeout/failure does not expose an empty surface |
| S08 font 100% / 200% — [L](flow-6-preflight.svg#S08-l) / [D](flow-6-preflight.svg#S08-d) | slab bounds and media C/R/T unchanged | labels remain named/focusable; no added chrome | paired; font scale does not move or resize the slab in this preflight |
| S09 empty / large library — [L](flow-6-preflight.svg#S09-l) / [D](flow-6-preflight.svg#S09-d) | no-media case has no stale owner; large library uses current q only | empty and 50k-item conditions share cancellation rules | paired; collection size does not weaken no-stale-paint behavior |
| S10 accessibility — [L](flow-6-preflight.svg#S10-l) / [D](flow-6-preflight.svg#S10-d) | focus outline is illustrative; content remains α=1 and stationary | expose media description, action names, state, and logical focus order | paired; semantics do not depend on animation or colour alone |
| S11 ready-only reduced-motion photo — [L](flow-6-preflight.svg#S11-l) / [D](flow-6-preflight.svg#S11-d) | P2 preview α=1 cuts directly to P4 HQ α=1 | only after matching `(u,r,q)` HQ is ready | paired; remove animations means instant ready-state cut, not early clear |
| S12 ready-only reduced-motion video — [L](flow-6-preflight.svg#S12-l) / [D](flow-6-preflight.svg#S12-d) | V2 poster α=1 cuts to V4 video α=1 | only after matching `(u,r,q)` valid frame | paired; reduced motion preserves first-frame safety |

## Review boundary

The board is a visual contract, not proof of Android composition behavior, frame timing, or inset dimensions. In particular, real Media3 `SurfaceView` / `PlayerView` z-order, alpha, and first-frame behavior must be verified on Android before implementation acceptance. The explicit **#715 acceptance line** across every panel is: *for the current URI and edited revision, cancellation-safe valid pixels continuously own the shared fitted/cropped/zoomed rectangle; incoming content replaces outgoing content only when ready, and reduced motion cuts only between ready states.*
