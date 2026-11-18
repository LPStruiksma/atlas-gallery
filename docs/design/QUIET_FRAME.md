# Quiet Frame: the ATLAS visual design

Approved by Lukas on 2026-11-18 (direction board in the "Redesign ATLAS into a modern app" project thread). Scope: the **look** of every ATLAS surface. Layout, navigation, gestures and feature placement still follow [`OWNER_INTENT.md`](../OWNER_INTENT.md) and [`SAMSUNG_PARITY_SPEC.md`](../product/SAMSUNG_PARITY_SPEC.md). The OWNER_INTENT removal list still binds: Quiet Frame adds no chrome, banners or labels.

## Idea
Photos edge to edge, chrome that floats and fades. One deep neutral ground, one cool accent, soft shapes, large light titles. Nothing competes with the photos.

## Colour tokens
| Token | Dark (default look) | Light |
|---|---|---|
| ground (screen background) | `#0B0C0F` | `#F6F7F9` |
| surface (cards, sheets) | `#17191F` | `#FFFFFF` |
| raised (chips, secondary buttons, nav pill) | `#23262E` | `#ECEEF2` |
| ink (primary text) | `#ECEDF0` | `#111318` |
| ink-muted (secondary text) | `#B4B8C1` | `#4A4F59` |
| ink-faint (meta, captions) | `#9A9EA8` | `#5F6470` |
| accent | `#7FA8FF` | `#2F6BE0` |
| on-accent | `#0B0C0F` | `#FFFFFF` |
| viewer ground | `#000000` | `#000000` |

Follow the system theme. All text pairs meet 4.5:1. The accent is the only saturated colour in chrome; selection, active tab and primary buttons use it.

## Type
Inter Tight, bundled (OFL), falling back to the system sans.
| Role | Size / line | Weight |
|---|---|---|
| Screen title (Pictures, Albums, People) | 34 / 40 sp, tracking −0.02em | 300 |
| Section title | 20 / 26 sp | 500 |
| Date header in grids | 15 / 20 sp | 500 |
| Body | 15 / 22 sp | 400 |
| Label / nav | 13 / 18 sp | 500 |
| Meta (counts, dates, captions) | 12 / 16 sp | 400 |
No all-caps, no bold filenames in viewer chrome.

## Spacing and shape
4 dp base. Screen margin 16 dp. Grid tile gap 2 dp, square tiles, no letterboxing. Radii: tile 0–4 dp, card 20 dp, bottom sheet 28 dp (top corners), buttons, chips and nav pill fully rounded. Touch targets ≥ 44 dp.

## Components
- **Bottom navigation:** a floating pill, 60 dp high, 16 dp from the screen sides and 28 dp from the bottom, raised colour at ~88% opacity with a soft shadow; active tab is an accent capsule with on-accent text. Tabs as in Samsung (Pictures, Albums, Stories, plus People where the parity spec places it).
- **Top area:** large light title with a meta line (for example "12.305 photos · 45 videos"); search and overflow as 44 dp icon buttons. Title collapses on scroll as in Samsung.
- **Buttons:** primary = accent capsule 44 dp; secondary = raised capsule; text buttons in accent.
- **Cards:** surface colour, 20 dp radius, 14–16 dp padding, no borders.
- **Viewer:** black ground; top bar date + time · place, centred, over a 35% black scrim; bottom actions in a floating pill (favourite, edit, share, delete); filmstrip thumbs 4 dp radius with an accent ring on the current item. Chrome fades on tap as in Samsung.
- **People:** round 96 dp faces in a three-column grid, name 14 sp/500 and count 12 sp below; suggestions ("Same person?") as one surface card with an accent "Review" button.
- **Icons:** 1.6 dp stroke outline icons, 22 dp.

## Motion
250 ms standard easing for chrome; shared-element zoom from grid tile to viewer and back; chrome fades rather than slides on scroll. Respect the system "remove animations" setting.

## Order of landing
1. Tokens and type in the Compose theme (no screen layout changes).
2. Bottom navigation pill and top area.
3. Pictures grid, then viewer, then Albums, People, Search, Settings, sheets and dialogs.
Each step is its own small PR with light and dark captures on the S24 rule, checked against this page.
