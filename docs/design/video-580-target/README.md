# VIDEO-1T — proposed video gestures, Playback speed and Save current frame

> **PROPOSED design evidence only: not a runtime capture, OEM-placement claim, implementation, approval or design-preflight PASS.** This #778 package makes the #580 [design brief](https://github.com/LPStruiksma/atlas-gallery/issues/580#issuecomment-7055689019) inspectable on top of Quiet Frame v3. The SVGs are source-generated mockups, never screenshots.

## Provenance

| Input | Kind | Used for |
|---|---|---|
| #616 / #609 emulator capture (API-35 Pixel 6 emulator forced to 1080×2340px, 450dpi, three-button navigation) | MEASURED, not physical S24+ | safeDrawing insets and cutout rectangles, copied into #142 |
| [#142 target geometry](../viewer-142-target/target-geometry.json) | PROPOSED target | every viewer rectangle: top actions, metadata, video row, filmstrip, action slab; read only, digest recorded in `geometry.json` |
| `ViewerDetailsSheet.kt` / `ViewerDetailsMotion.kt` on main | existing code | Details surface: 42% of viewer height, vertical scroll, 24dp side padding, label-over-value rows |
| [Quiet Frame v3](../QUIET_FRAME.md), [OWNER_INTENT](../../OWNER_INTENT.md) | approved direction | ATLAS owns placement and look |
| [Samsung parity §6](../../product/SAMSUNG_PARITY_SPEC.md) | feature behavior | seek/play behavior only; speed and capture **OEM placement unknown**, no claimed match |

Viewports: portrait 384×832dp / 1080×2340px and physically rotated landscape 832×384dp / 2340×1080px at 2.8125px/dp, plus the #142 computed narrow rotated fallback 640×360dp / 1800×1013px. Gesture-navigation insets remain **UNKNOWN (#627)**; nothing here infers them.

## Evidence map

* [`tables.md`](tables.md): viewports and safe/cutout values (V1), the active semantic hits of every state with the inactive ones and why (V2), the Details rows with scroll metrics (V3), the Playback speed sheet (V4) and the transient zones (V5).
* [`geometry.json`](geometry.json): the same model, machine-readable.
* [`behavior.md`](behavior.md): exact copy and semantics, named transitions T01–T24, gesture arbitration, capture states, 200% font, focus order, theme and reduced animation.
* [`svg/`](svg/): for each viewport, `playing` (dark), `paused` (light), `transient-chrome-shown` and `transient-chrome-hidden` (±10 s / zoom / 2× zones), `details` (Details surface with Playback speed and Save current frame), `speed-sheet`, `font200-details-permission` (200% font, wrapped permission text, scrolled) and `font200-speed-sheet` (36sp title, 28sp options). Every SVG declares exact px size and a dp `viewBox`.

## Model rules

* A state lists only hits that can take input. Controls covered by the Details surface, or under the modal Playback speed sheet, are inactive in that state; everything active is checked pairwise, including play/seek against the new rows.
* The Playback speed sheet holds all nine choices inside the sheet in every viewport and font scale (portrait one column, landscape three), with a ≥48dp dismiss scrim above it.
* Transient zones are the safe video canvas minus top chrome, video row, filmstrip and action slab (8dp clearance), split 35/30/35; with chrome hidden they use the safe rect.

## Check

```bash
python3 docs/design/video-580-target/verify.py --check
```

`--write` regenerates everything (and removes stale SVGs), then run `--check` again. The verifier compares every generated file byte for byte, checks the #142 source digest, ≥48dp active hits, safe-rect and cutout exclusion, pairwise collisions per state, ≥8dp video-to-strip gap, sheet containment and order, zone placement and split, real 200% wrap/scroll, the behavior transitions and copy, and relative links.

Independent review must still validate real rendering, TalkBack output, measured gesture-navigation insets, decoder and MediaStore behavior and light/dark contrast on an exact implementation head.
