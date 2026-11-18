# DESIGN-8 · state and collision matrix for issue #142

These are **design checks for future Review**, not test results. `safeDrawing` and pixel scale are unmeasured pending emulator capture.

| State | Intended geometry/action | Collision and accessibility check |
|---|---|---|
| Portrait photo, light theme, chrome shown | Bright synthetic image full-bleed; translucent top left back/right actions; four bottom actions below filmstrip | Contrast on bright white areas; every reachable action remains inside measured safe bounds |
| Portrait photo, dark theme, chrome shown | Dark synthetic photo, same control bounds | Pill/icons remain identifiable, no opaque bars reducing media area |
| Portrait/landscape photo, chrome hidden | No top/bottom pills or strip; status/nav hidden with 200ms fade | Tap unoccupied media reveals all; edge swipe can reveal transient system bars; Back remains available |
| Real 90° rotated cutout landscape, shown | Safe inset side may differ; back/action groups stay opposite, filmstrip/actions fit bottom | Measure cutout/gesture exclusion; 280dp all-actions example fits safe width but remaining height needs checking |
| Video short duration, paused and playing | 54dp play/seek pill above filmstrip; timecode and end label | Seek start/end, thumb reachability and 48dp interaction region; do not toggle chrome when scrubbing |
| Video long duration, held seek | 176×110dp preview above 54dp row (8dp gap); no persisted frames | Preview must not intersect top actions or filmstrip; seek clamps at 0 and duration |
| Video seeking with chrome hidden | Chrome revealed by direct tap before interaction | Player-surface tap should not intercept seek; pause/resume state preserved |
| Video duration unknown or loading | Seek disabled until duration positive; poster remains while loading | Distinguish loading/unavailable; safe Back exit and a screen reader description |
| Video decode error/empty catalogue | No non-dismissible panel; reachable Back or empty-state navigation | Check focus order and Back; do not present a misleading enabled scrub |
| Long filmstrip/many items | Centre tile enlarged; strip moving snaps to current item | Screen-reader tile naming and click region, 30×40dp cell is below typical 48dp target; consider hit slop/semantics |
| Conditional top actions absent/present | Search/face/menu depend on current media eligibility; details always visible | Prevent collisions with back/cutout; stable visual order and focus |
| Bottom actions ineligible | Hide unsupported actions without blank placeholders | Preserve order of remaining actions, with share/edit handoffs and Android consent confirmation |
| Reduced motion/TalkBack | All controls carry meaningful spoken actions; selected item announced | Motion setting and activation focus; no filename or counter visible (screen reader may announce position) |

### Diagram conventions

A filled rectangle is illustrative synthetic media; dashed lines indicate unknown safe boundaries, not measured Samsung positions. Numbers beside control groups are **dp units from source code**, whereas SVG canvas is in **pixels**. Relative visual placement is not a pixel-to-dp conversion. Assertions about hardware cutout shape, status bar height, gesture zone and OEM spacing are intentionally withheld.
