# Generated tables (do not edit; run generate.py)

## Q1 Timestamp quantization

| Duration | N frames | Span dp | Span px | ms per px | Tile interval ms | Frame interval 30/60 fps | Drag precision limited by |
|---|---|---|---|---|---|---|---|
| 300 ms | 3 | 132.0 | 371.25 | 0.81 | 66.67 | 33.33 | decoder frames |
| 0:01 | 3 | 132.0 | 371.25 | 2.69 | 300.0 | 33.33 | decoder frames |
| 0:04 | 4 | 176.0 | 495.0 | 8.08 | 975.0 | 33.33 | decoder frames |
| 0:30 | 10 | 440.0 | 1237.5 | 24.24 | 2990.0 | 33.33 | both |
| 2:00 | 14 | 616.0 | 1732.5 | 69.26 | 8564.29 | 33.33 | strip pixels |
| 10:00 | 19 | 836.0 | 2351.25 | 255.18 | 31573.68 | 33.33 | strip pixels |
| 1:23:45 | 25 | 1100.0 | 3093.75 | 1624.24 | 200996.0 | 33.33 | strip pixels |

Frame-interval column shows 30 fps; 60 fps is half. ‘decoder frames’ = one px of drag moves less than one 60 fps frame, so precision is the decoder's; ‘strip pixels’ = one px skips more than one 30 fps frame.

## G1 Geometry checks per board

| Board | ≥48 dp | Inside safeDrawing | Over cutout | Hit collisions | Video row → band | Band → slab | N | ms/px |
|---|---|---|---|---|---|---|---|---|
| [svg/portrait-3button--light--held.svg](svg/portrait-3button--light--held.svg) | True | True | False | none | 12.0 | 8.0 | 10 | 24.24 |
| [svg/portrait-3button--light--dragging.svg](svg/portrait-3button--light--dragging.svg) | True | True | False | none | 12.0 | 8.0 | 10 | 24.24 |
| [svg/portrait-3button--dark--held.svg](svg/portrait-3button--dark--held.svg) | True | True | False | none | 12.0 | 8.0 | 10 | 24.24 |
| [svg/portrait-3button--dark--dragging.svg](svg/portrait-3button--dark--dragging.svg) | True | True | False | none | 12.0 | 8.0 | 10 | 24.24 |
| [svg/portrait-3button--dark--unavailable.svg](svg/portrait-3button--dark--unavailable.svg) | True | True | False | none | 12.0 | 8.0 | 10 | 24.24 |
| [svg/portrait-3button--dark--long-font200.svg](svg/portrait-3button--dark--long-font200.svg) | True | True | False | none | 12.0 | 8.0 | 25 | 1624.24 |
| [svg/landscape-3button--light--held.svg](svg/landscape-3button--light--held.svg) | True | True | False | none | 12.0 | 8.0 | 10 | 24.24 |
| [svg/landscape-3button--light--dragging.svg](svg/landscape-3button--light--dragging.svg) | True | True | False | none | 12.0 | 8.0 | 10 | 24.24 |
| [svg/landscape-3button--dark--held.svg](svg/landscape-3button--dark--held.svg) | True | True | False | none | 12.0 | 8.0 | 10 | 24.24 |
| [svg/landscape-3button--dark--dragging.svg](svg/landscape-3button--dark--dragging.svg) | True | True | False | none | 12.0 | 8.0 | 10 | 24.24 |
| [svg/landscape-3button--dark--unavailable.svg](svg/landscape-3button--dark--unavailable.svg) | True | True | False | none | 12.0 | 8.0 | 10 | 24.24 |
| [svg/landscape-3button--dark--long-font200.svg](svg/landscape-3button--dark--long-font200.svg) | True | True | False | none | 12.0 | 8.0 | 25 | 1624.24 |
| [svg/landscape-narrow-computed--light--held.svg](svg/landscape-narrow-computed--light--held.svg) | True | True | False | none | 12.0 | 8.0 | 10 | 24.24 |
| [svg/landscape-narrow-computed--light--dragging.svg](svg/landscape-narrow-computed--light--dragging.svg) | True | True | False | none | 12.0 | 8.0 | 10 | 24.24 |
| [svg/landscape-narrow-computed--dark--held.svg](svg/landscape-narrow-computed--dark--held.svg) | True | True | False | none | 12.0 | 8.0 | 10 | 24.24 |
| [svg/landscape-narrow-computed--dark--dragging.svg](svg/landscape-narrow-computed--dark--dragging.svg) | True | True | False | none | 12.0 | 8.0 | 10 | 24.24 |
| [svg/landscape-narrow-computed--dark--unavailable.svg](svg/landscape-narrow-computed--dark--unavailable.svg) | True | True | False | none | 12.0 | 8.0 | 10 | 24.24 |
| [svg/landscape-narrow-computed--dark--long-font200.svg](svg/landscape-narrow-computed--dark--long-font200.svg) | True | True | False | none | 12.0 | 8.0 | 25 | 1624.24 |
