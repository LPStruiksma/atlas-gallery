# BRAND-L3 synthetic launcher and splash preflight (#717)

**Status:** draft scaffold for Codex-produced design evidence. The annotated visual sheet is not yet present; do not treat this scaffold as a design preflight PASS.

**Approved identity:** [Quiet Frame v3](QUIET_FRAME.md) · **Source unit:** [#717](https://github.com/LPStruiksma/atlas-gallery/issues/717) · **Implementation gate:** [#656](https://github.com/LPStruiksma/atlas-gallery/issues/656).

## What this documentation must show

A **synthetic, labelled design mock**, never a screenshot from a real S24+, launcher or emulator. The canonical deliverable is [`lapis-l3-preflight.svg`](lapis-l3-preflight.svg), with an internal **1080 × 2340** S24+-proportioned launcher panel, busy *synthetic* wallpaper and surrounding fictional app icons. Show both round and squircle masks, full-colour and monochrome marks, themed-icon on/off and light/dark launcher contexts. Each Atlas icon is depicted in a labelled **48 dp** cell, with masks and variant labels. This is an optical-recognition check, not evidence of device behaviour.

Geometry inside the **108 dp** adaptive canvas: solid Lapis background `#2639C9`; white sphere `(54, 40)`, radius `15`; white chevron path `M30 84L54 60L78 84` with `8 dp` white stroke, round caps and joins. Apply `translate(54 54) scale(.77) translate(-54 -54)` to the *entire mark*, including stroke, **not** to the background. Show and annotate the centre `(54,54)`, safe circle `r=33 dp`, and the outermost endpoint distance `(sqrt(24² + 30²) + 4) × 0.77 ≈ 32.66 dp < 33 dp`. The scaled sphere centre is `(54,43.22)`, radius `11.55`; scaled stroke width is `6.16 dp`. When the 108 dp canvas is shown in a 48 dp launcher cell, multiply lengths by `48/108`: safe-circle radius `14.67 dp`, chevron stroke `2.74 dp`. Keep the complete stroked mark inside each mask's visible safe zone.

Include clearly labelled **synthetic static cold-start** panels for both API 29–30 (`windowBackground` layer-list) and API 31+ (`windowSplashScreen*`) in light and dark system contexts. Each uses Lapis ground and the same centred mark, no wordmark, no animation. Show the intended handoff to the *existing post-launch app theme*, without asserting that runtime theme resources have been implemented.

## Variant checklist

| Dimension | Required examples |
| --- | --- |
| Launcher masks | Round, squircle |
| Launcher icon mode | Full-colour, monochrome |
| Themed icons | Off, on |
| Launcher context | Light, dark |
| Splash API family | 29–30 layer-list, 31+ platform |
| Splash theme | Light, dark |

## Limits and next gate

This PR is **documentation only**: do not modify `app/**`, manifests, app icon previews, build files, signing, names or store assets. Do **not** overwrite `docs/design/app-icon-preview.png`. The eventual BRAND-L3 implementation still requires a fresh independent design preflight and, on its own exact PR head, actual emulator/device launcher and cold-start captures for both API families; none are established by a synthetic SVG. A separate reviewer must inspect this evidence before it is merged.
