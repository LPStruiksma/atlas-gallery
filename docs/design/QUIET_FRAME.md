# Quiet Frame v2 · Meridian

Approved by Lukas on 2026-11-20 (Meridian decision, BRAND-1 [#637](https://github.com/LPStruiksma/atlas-gallery/issues/637)). Scope: the **look** of every ATLAS surface. Layout, navigation, gestures and feature placement still follow [`OWNER_INTENT.md`](../OWNER_INTENT.md) and [`SAMSUNG_PARITY_SPEC.md`](../product/SAMSUNG_PARITY_SPEC.md). The OWNER_INTENT removal list still binds: Quiet Frame adds no chrome, banners or labels. This specification changes no app code.

## Idea

**Your life, charted.** ATLAS is a precision instrument for a lifetime of photos. Photos reach edge to edge; chrome floats and fades. Meridian hairlines, an amber “you are here” pole dot and numbers set like coordinates give it an identity without competing with the photos.

## Brand

- **Mark:** two meridian arcs meet at a pole to form the A; the equator forms its crossbar. An amber pole dot sits at the apex. Exact geometry belongs to BRAND-3.
- **Wordmark:** `ATLAS` in IBM Plex Mono 500, tracking +0.32em, followed by an amber dot.
- **Tone:** plain, short, factual copy. Numbers carry the detail.

## Colour tokens

| Token | Dark (default) | Light |
|---|---|---|
| ground | `#0D1117` | `#F5F3EE` |
| surface | `#161B23` | `#FFFFFF` |
| raised | `#212833` | `#ECE8DF` |
| line (hairlines, rail) | `#3A4350` | `#CFC9BC` |
| tick | `#6B7480` | `#9A958A` |
| ink | `#EEF1F5` | `#12161C` |
| ink-muted | `#A9B1BD` | `#4A515C` |
| ink-faint | `#8B94A1` | `#5E6672` |
| accent (pole amber; text, active, selection) | `#FFB547` | `#9A5B00` |
| accent-fill (dots, primary buttons) | `#FFB547` | `#FFB547` |
| on-accent | `#1A1206` | `#1A1206` |
| support (places and maps only) | `#7ED0C4` | `#1F7A6E` |
| viewer ground | `#000000` | `#000000` |
| favourite | accent (no separate red) | accent |

Follow the system theme. All text meets a contrast ratio of at least 4.5:1 against its actual background. Amber is the only saturated chrome colour; support is reserved for place/map surfaces, and favourite uses accent rather than a separate red. Use `accent` for text and strokes, `accent-fill` for solid dots and primary buttons, and `on-accent` on those fills.

## Type

**Instrument Sans** (OFL, bundled) for titles and body text; **IBM Plex Mono** (OFL, bundled) for every number and meta line, including dates, counts, sizes, places and scroll labels.

| Role | Size / line | Weight and tracking |
|---|---|---|
| Screen title | 34 / 40 sp | Instrument Sans 600, −0.02em |
| Section title | 20 / 26 sp | Instrument Sans 600 |
| Date header | 15 / 20 sp | Instrument Sans 600 with a mono meta part, e.g. `19.11 · AMSTERDAM` |
| Body | 15 / 22 sp | Instrument Sans 400 |
| Label / nav | 12 / 16 sp | Instrument Sans 600 |
| Meta | 11 / 16 sp | IBM Plex Mono 400–500, +0.06em |

Upper case is permitted for mono meta only; avoid all-caps body copy and bold filenames in viewer chrome. The branded `ATLAS` wordmark is the explicit uppercase exception.

## Spacing and shape

4 dp base; screen margin 16 dp. Grid tile gap 2 dp, square edge-to-edge tiles, no letterboxing. Radii: tiles 4 dp, cards 20 dp, bottom sheets 28 dp (top corners), pills for rounded buttons and chips. Touch targets ≥ 44 dp. Preserve the existing screen, navigation and action geometry from OWNER_INTENT and Samsung parity; shape tokens do not authorize moving or adding controls.

## Components

- **Signature — meridian rail:** one component vocabulary shared across existing surfaces:
  - **Grid fast-scroll:** a hairline at the right edge with month ticks. While dragging, the amber pole dot and an IBM Plex Mono month label track the position.
  - **Viewer filmstrip:** an amber pole dot above the current item and a 2 dp amber ring around it; no second timeline bar.
  - **Bottom-navigation active state:** a 5 dp amber dot above the active icon, an accent-coloured label and icon, **no capsule**. Retain the established tabs, position and navigation behavior.
  - **Loading:** amber progress along a hairline, paired with a mono count; no blocking banner.
- **Selection:** a 2 dp inset amber ring and a numbered amber dot indicating pick order.
- **Top area:** established large title and mono meta line (counts); search and overflow retain ≥ 44 dp touch targets. Collapsing behavior continues to follow parity.
- **Buttons:** primary has `accent-fill` and `on-accent` text; secondary uses `raised`; text buttons use `accent`. Keep existing actions and positions.
- **Cards:** `surface`, 20 dp radius and existing padding; hairlines only where the component calls for them.
- **Viewer:** black ground; retain the date, time and place metadata, floating actions and chrome-fade behavior in their parity-defined positions. The filmstrip current-item treatment uses the meridian rail signature.
- **People:** preserve the parity-defined face grid, names, counts and suggestion actions; use the new type and colour tokens, not a new layout.
- **Icons:** 1.5 dp stroke, square caps, 22 dp; active icons use `accent`.
- **Empty state:** a faint chart grid (24 dp hairline grid in `raised`) behind a one-line title and a mono meta line, without an explanatory banner.

## Motion

**Plot:** elements travel on straight paths, 220 ms decelerate, no bounce. The pole dot glides along the rail; shared-element grid-tile-to-viewer motion follows a straight path. Keep existing gesture and navigation outcomes. With the system “remove animations” setting enabled, everything cuts immediately.

## Order of landing

1. **BRAND-2:** tokens and bundled fonts.
2. **BRAND-3:** Meridian icon and splash.
3. **BRAND-4:** meridian rail and bottom-navigation visual treatment.
4. **Existing #120 screen units:** inherit the tokens without changing layout, navigation, gestures or feature placement.

Each rendering unit provides exact-head light/dark visual evidence; this BRAND-1 unit defines the specification only.
