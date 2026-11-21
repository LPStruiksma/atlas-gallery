# L5 Collections route and reference audit — #819

Status: **pre-build proposal; not Android proof or a completed sheet**. The branch generator has changed; the committed L5 PNG and its SHA-256 in `manifest.json` still show the earlier 13-panel board until a Python/render-capable seat regenerates and checks them. The dark/200% shared slab remains governed by [L4 correction #816](https://github.com/LPStruiksma/atlas-gallery/issues/816), not by this L5 proposal. Re-review #658 only after that integration and artifact verification.

## Scope and counted tap paths

Count **button taps**, not typing or scrolling, from any screen where the Pictures/Collections slab is present. A visible recent *member* row is distinct from a *category* row. The member opens the existing detail view directly; category rows open the relevant category landing.

| Named goal and state | Exact path | Taps | Return by system Back |
|---|---|---:|---|
| Person Noor, recent and visible | Collections → **Noor** direct row → existing Noor person detail | 2 | Noor detail → Collections → origin |
| Album Coastal weekend, recent and visible | Collections → **Coastal weekend** direct row → named album detail | 2 | Album detail → Collections → origin |
| Place/trip Dunes trip, recent and visible | Collections → **Dunes trip** direct row → named place/trip detail | 2 | Place detail → Collections → origin |
| Person Lotte, not recent in a 500-person library | Collections → **scoped search** → enter “lotte” → select **Lotte** person result | **3 + typing** | Hide IME → close scoped search → origin |
| Person Noor through the People and pets *category* | Collections → People and pets landing → Noor | 3 | Person → category → Collections → origin |
| Nonrecent album or place through a category | Collections → Albums or Places landing → desired member (if visible) | 3 | Member → category → Collections → origin |

The scoped **Collections** result above is a **design proposal**: only if the collection index offers a direct typed entity destination can it be a result tap. It must preserve the existing entity detail screens and #175 search/sort behavior. If an implementation lacks a direct scoped result, label the extra navigation rather than silently subtracting a tap. Existing **global** AtlasSearch direct-entity results are **unverified**; no two-tap global shortcut is claimed.

The source frames are C1–C3 (person), C4–C5 (album), C6–C7 (place/trip), D1 (500-person category and sort) and D2 (dark/200% scoped search with synthetic IME). Their lettering must match the regenerated PNG and manifest before Review.

## Two inspected alternatives and design choice

Sources accessed **2026-11-21**. A support page's access date is not the date of a specific app build or a guarantee that every device presents identical UI.

| Source and specific observed evidence | Atlas adaptation / explicit deviation | Unknown, not inferred |
|---|---|---|
| [Apple Support — Find and name people and pets in Photos on iPhone](https://support.apple.com/guide/iphone/find-and-name-people-and-pets-iph9c7ee918c/ios): official illustrated People & Pets screen shows individually pictured named person/pet thumbnails and a Sort control. [Apple Collections guide](https://support.apple.com/en-asia/guide/iphone/iph4f36c4148/27/ios/27) describes curated collections including Trips and People & Pets with collection navigation. | Keep a full People and pets category, but inline **only genuinely recent named members**; show a **four-photo, real-aspect strip with 8 dp radii** instead of an individual portrait/face crop. Retain sort and dedicated person detail. | Apple’s exact high-font/IME layout and counts to reach arbitrary members; source does not prove any universal two-tap shortcut. |
| [Immich — Facial Recognition](https://docs.immich.app/features/facial-recognition/): documentation describes grouped/named people and clicking a person to view associated assets; documents person feature thumbnails. [Immich v2.4.0 release, 18 December 2025](https://immich.app/blog/v2.4.0-release) notes mobile album sorting/layout persistence. | Keep named person and album detail destinations, with category entry and sort; inline direct named/recent albums and places only where shown. Replace the sole person/album cover as entry affordance with a four-photo mixed-aspect strip; do not crop it into circles or 1:1 tiles. | Current Immich mobile thumbnail geometry, its navigation tap counts, and whether its search results open the same type of detail destination. |

This deliberately does not credit the private Round 3 board with observed behavior. The older ATLAS People screen and Android Compose controls are useful local/technical references but **not** the two independent alternative gallery navigations requested in #658.

## Safe 200% composition — acceptance contract

- 384×832 dp / 1080×2340 px synthetic S24+ proposal; assumed status 29 dp, gesture 16 dp and test IME 324 dp, **not** device measurements.
- A2 dark/200% draws only complete recent rows before navigation, leaving the rest scrollable instead of clipping a photo strip under controls. The shared L4 fix #816 must place **Pictures, Collections and search in one centred slab** at the same scale; the current floating sphere is **not** a safe accepted fallback.
- D2 dark/200% shows one direct scoped Lotte result and a keyboard. Verify each named/result target ≥48×48 dp and verify panel, row, slab, IME and insets are disjoint. The added Python geometry tests check row-to-slab clearance and result/IME bounds; a second exact-head render and pixel inspection are still needed.
- Synthetic gradients and fictional labels only; no real faces, personal photos or geodata. No Android navigation, TalkBack, keyboard or font behavior is claimed from this evidence.

## Required finishing work before Review

Regenerate L5 PNG with the same documented Funnel fonts/Pillow version; update `manifest.json` (SHA, dimensions, C1–C7 panels and frame count), update the preflight README (including stale universal two-tap and old A2 copy), run `python3 tools/design/lapis_preflight.py --check` and `python3 -m unittest discover -s tools/design/tests`; inspect actual A1/A2/C1–C7/D2 pixels and coordinate with #816's merged L4 slab correction. Only then route #819 to Review and #658 to independent pre-build re-preflight after this repair merges.
