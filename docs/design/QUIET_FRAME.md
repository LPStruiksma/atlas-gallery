# Quiet Frame v3 · Lapis Atlas

Approved by Lukas on 2026-11-20 (“Lapis Atlas + Continuum ideas”, [BRAND-L1 #654](https://github.com/LPStruiksma/atlas-gallery/issues/654); supersedes Meridian v2 / [#637](https://github.com/LPStruiksma/atlas-gallery/issues/637)). Scope: **the identity, structure, navigation and look** of ATLAS. Samsung Gallery still defines **feature parity and in-feature behavior**, with file-size sorting added; it does not prescribe where those features live or how they look. See [`OWNER_INTENT.md`](../OWNER_INTENT.md) and [`SAMSUNG_PARITY_SPEC.md`](../product/SAMSUNG_PARITY_SPEC.md). No feature is removed by this design direction. The OWNER_INTENT removal list still binds: no new explanatory banners, labels or extra chrome. This specification changes no app code.

## Idea

**Atlas holds up your world.** In the myth, Atlas holds up the sky; in the app, Atlas holds up your world. The mark is shoulders holding a sphere. That same **held sphere** identifies whatever is current, chosen or loading throughout the app. Let the photos carry the content and the sphere carry the state.

## Brand

- **App mark (108 dp canvas):** solid lapis `#2639C9` ground; white sphere centred at `(54, 40)` with radius `15`; white chevron/shoulders along path `M30 84L54 60L78 84`, stroke `8`, rounded line caps and joins. The safe-zone check belongs to BRAND-L3.
- **Wordmark:** “Atlas” in Funnel Display 800, tracking `−0.03em`; lapis `#2639C9` on light, `#8C9BFF` on dark.
- **Tone:** clear sentence case, specific counts in plain language, one useful next step; no coordinate-like meta strings or decorative slogans.

## Structure

Quiet Frame—not Samsung's tab positions or component shapes—defines where features live. Keep every Samsung Gallery capability and its in-feature behavior.

1. **Two places and search:** a short, centred lapis bottom **slab** holds **Pictures** and **Collections**, plus a **search sphere**. There is no four-tab bar.
2. **Pictures:** the photo timeline. At **day** zoom, each photo appears at its real shape in **justified rows**, with no letterboxing and no square cropping; **square tiles** appear only from **month** zoom outward. Pinch adjusts zoom from day → month → year. A held-sphere scroll thumb shows the current month.
3. **Atlas overlay:** pull down on Pictures, or tap the scroll-thumb sphere, to open an atlas **over** the grid. It presents **this year** as a strip of month bars, marks the current month with the sphere, and shows a dot map of your places. Tap a month or a place to jump the underlying grid there; push up to close. For TalkBack, expose places as a list rather than relying on the map, e.g. “Amsterdam, 6.120 photos”.
4. **Search under the thumb:** tap the search sphere to grow a **full-width search bar directly above the slab**; the keyboard opens with it. This is the existing AtlasSearch suite ([#175](https://github.com/LPStruiksma/atlas-gallery/issues/175)), not a reduced search mode.
5. **Collections:** albums, people and pets, places, stories, favourites, videos, screenshots and other categories, shared albums, then Trash, Hidden/Secure folder, cleanup suggestions and Settings at the end. Each collection is a **row with a strip of its latest photos at their real shapes**, not a cover tile or a circular portrait. People rows show **name, count and latest date**.
6. **Viewer:** black ground; actions live in a lapis-lit slab (`#3448E0`). The filmstrip marks the current item with a small `#8C9BFF` sphere above it and a **2 dp white ring**.
7. **Feature homes:** Pictures → timeline and atlas. Collections → albums, shared albums, Stories, People, Places, Favourites, Videos, Recent, categories, Trash, Hidden/Secure folder, Settings and cleanup. Search → sphere. Editor and AI edit → viewer slab. Multi-select → long press anywhere. Every Samsung feature must remain discoverable within these homes; the parity spec still defines its behavior after entry.

## Colour tokens

Use **one lapis accent**, not a second accent or tinted greys. `brand-fill` and `brand-text` are **one brand token in two states**: the solid-field value and the readable text/link value. Use lapis as **solid fields and title colour**, never as a thin outline. Follow the system light/dark theme.

| Token | Light | Dark |
|---|---|---|
| ground | `#FFFFFF` | `#000000` |
| surface / mist | `#F2F3F8` | `#14152A` |
| ink | `#0E1030` | `#F2F3F8` |
| ink-secondary | `#5A5F7A` (6.26:1) | `#9AA0B8` (8.09:1) |
| brand-fill (slab, sphere, primary button) | `#2639C9` | `#3448E0` |
| brand-text (titles, links) | `#2639C9` (8.36:1) | `#8C9BFF` (8.25:1) |
| on-brand | `#FFFFFF` (8.36:1 on light fill; 6.67:1 on dark fill) | `#FFFFFF` |
| on-brand-secondary | `#DCE1FF` (6.47:1 on lapis) | `#DCE1FF` |
| viewer ground | `#000000` | `#000000` |

All text meets at least 4.5:1 contrast against its actual background. The contrast values above are part of the approved design brief; check actual compositions when implementing.

## Type

Bundle **Funnel Display** (titles) and **Funnel Sans** (everything else), both under the OFL. No monospace meta type; no middle-dot meta strings. Use sentence case everywhere, including navigation and dates. Prefer “12.305 photos and 45 videos”, not a compressed dot-separated meta line.

| Role | Size / line | Face / weight / tracking |
|---|---|---|
| Screen title | 38 / 44 sp | Funnel Display 700, `−0.03em`, `brand-text` |
| Section title | 18 / 24 sp | Funnel Sans 600 |
| Date header | 16 / 22 sp | Funnel Sans 600 |
| Body | 15 / 22 sp | Funnel Sans 400 |
| Secondary | 14 / 20 sp | Funnel Sans 400 |
| Nav | 14 / 20 sp | Funnel Sans 600 |

The wordmark alone uses Funnel Display 800 as specified under Brand.

## Spacing and shape

- **Grid gap:** 2 dp. At day zoom use justified real-shape rows; from month zoom onward use square tiles. Do not letterbox real-ratio photos into fixed cells.
- **Radii:** 8 dp for strip tiles, 22 dp for secondary surfaces, 24 dp for the navigation/action slab, fully round for the held sphere.
- **Slab:** short, centred, lapis-filled, 24 dp radius. The active place is a **white pill with lapis text**.
- **Touch targets:** at least **48 dp** everywhere, including the sphere, thumb, slab actions and collection rows.

## Components

- **Signature — the held sphere:** one consistent state vocabulary on every surface:
  - **Selection:** lapis sphere on the tile corner with a **2 dp white halo**; show the pick number when selection is ordered.
  - **Fast scroll:** **46 dp lapis sphere** at the timeline thumb, showing the month, e.g. “Nov”. Tapping it opens the atlas.
  - **Viewer filmstrip:** small `#8C9BFF` sphere above the current item with a **2 dp white ring** on that item.
  - **Loading:** the sphere **climbs an arc** beside a plain progress count, e.g. “Finding faces, 7.140 of 12.305”. No blocking banner or extra explanatory chrome.
- **Navigation slab:** Pictures and Collections with their active white pill, plus the search sphere. It is a two-place control, not a re-skinned four-tab bar.
- **Search:** the bar expands immediately above the slab and summons the keyboard; keep the full AtlasSearch capability.
- **Collections:** named rows show a strip of latest real-shape photos. People and pets are not represented by arbitrary circles.
- **Viewer:** `#000000` ground with actions on the `#3448E0` lapis-lit slab, and the held-sphere filmstrip treatment.
- **Empty state:** one sentence and a practical next step, e.g. “No photos in this album yet. Add photos from Pictures to fill it.” No decorative grid, explanatory banner or additional empty-state chrome.

## Motion

**Lift:** the picked element rises a hair and the held sphere arrives from below it over **200 ms**. The atlas slides down with the user's finger, and pushes up to close. **Nothing moves on its own.** With the system “remove animations” setting enabled, every transition cuts immediately. Preserve the feature and gesture outcomes; motion must never introduce a blocker.

## Order of landing

1. **BRAND-L2:** tokens and bundled Funnel fonts.
2. **BRAND-L3:** Lapis Atlas icon and splash, including the 108 dp mark safe-zone check.
3. **BRAND-L4:** bottom slab and held sphere.
4. **BRAND-L5:** Collections rows.
5. **BRAND-L6:** atlas overlay.
6. **BRAND-L7:** expandable search bar.
7. **BRAND-L8:** justified real-shape rows and picture zoom.

Each implementation unit supplies exact-head light/dark rendered evidence for the surfaces it changes. This BRAND-L1 unit defines documentation only.
