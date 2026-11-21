# VIDEO-1T — proposed video gestures, speed and frame capture

> **Design evidence only: not a runtime capture, OEM-placement claim, implementation, approval, or independent preflight PASS.** This #778 package proposes a future #580 behavior atop Quiet Frame v3. The SVGs are source-generated mockups, never screenshots.

## Provenance and limits

The geometry is reconciled with [`viewer-142-target/target-geometry.json`](../viewer-142-target/target-geometry.json) and its SVG/tables. That parent is a **proposed** #142 target. The distinct #609 evidence is a measured API-35 Pixel 6 emulator forced to 1080×2340px at 450dpi; it is not physical S24+ proof. Portrait is 384×832dp / 1080×2340px and physical rotation is 832×384dp / 2340×1080px, both at 2.8125px/dp. The 640×384dp narrow rotated case is the #142 computed fallback, not a measured device.

Gesture-navigation insets remain **UNKNOWN (#627)**: only three-button navigation was captured. Green safe boundaries and red cutouts therefore reproduce known #142 inputs, not an inference about OEM placement or gesture navigation. Any implementation remains gated on isolated gesture-nav capture. `OWNER_INTENT` and Samsung parity §6 require Samsung feature behavior, while ATLAS structure and placement remain Quiet Frame's own; the proposed Details placement makes no claim about Samsung placement.

## Evidence map

* [`geometry.json`](geometry.json) is the machine-readable source output and [`tables.md`](tables.md) exposes every viewport, safe/cutout boundary, filmstrip, and semantic rectangle.
* Dark playing, light paused, dark current Details, light speed choices, dark landscape transient zones, and the light narrow 200%-font fallback are committed under [`svg/`](svg/). Every SVG declares exact px dimensions and a dp `viewBox`.
* [`behavior.md`](behavior.md) records state, arbitration, capture, accessibility, theme, and motion contracts.
* `generate.py` is Python-only and deterministic. `verify.py` compares all generated text byte-for-byte and checks ≥48dp semantic hits, play/seek collision freedom, known safe/cutout boundaries, and ≥8dp video-control clearance above the filmstrip.

Run `python3 docs/design/video-580-target/verify.py --check`. Regenerate with `python3 docs/design/video-580-target/verify.py --write`, then check again. Optional PNG review overlays may be rasterized on demand from committed SVGs (for example with an external SVG renderer), but PNGs are derived convenience output, are not runtime captures, and are intentionally not committed through this text-only unit.

## Reading the mockups

Outlined rectangles are semantic hit areas, not painted bounds. The speed row and **Save current frame** action are 48dp minimum. Playing and paused preserve the #142 filmstrip/play/seek stack; video controls clear the filmstrip by 24dp in this proposal. The landscape speed sheet and narrow Details sheet demonstrate rotation and 200%-font reflow without putting controls into known cutouts. Independent review must still validate real rendering, TalkBack output, actual system insets, decoder behavior, MediaStore behavior, and light/dark contrast on an exact implementation head.
