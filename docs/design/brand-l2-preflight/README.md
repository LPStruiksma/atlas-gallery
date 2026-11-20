# BRAND-L2 · inspectable pre-build design evidence

**Status:** synthetic design preflight, NOT an Android screenshot, CI check, bundled-font specimen or implementation.
**Source of truth:** [Quiet Frame v3 · Lapis Atlas](../QUIET_FRAME.md), approved via [BRAND-L1 #654](https://github.com/LPStruiksma/atlas-gallery/issues/654), merged in [PR #665](https://github.com/LPStruiksma/atlas-gallery/pull/665).
**Work unit:** [#655](https://github.com/LPStruiksma/atlas-gallery/issues/655). **Latitude:** fidelity. **UI:** yes. **Needs for implementation:** run:android and rendered proof.
**Owner words:** “a coherent strong visual identity throughout its entirety that is consistent throughout everything up to its logo.”
**Not this:** L4–L8 structural changes; new navigational/control components; actual photo or biometric content.

## Inspectable visual artifacts

1. [Light/dark colour tokens and contrast sheet](palette.svg) · every Quiet Frame v3 colour token, theme-specific HEX values and independently calculated WCAG contrast checks.
2. [Paired Pictures/Photos composition](pictures.svg) · synthetic S24+ aspect (1080 × 2340), light and dark, with deliberately fictional geometric media. Current four-tab Photos / Albums / People / Search and floating pill are **retained**; the L4 two-place lapis slab and held sphere are not drawn here.
3. [State-style matrix](states.svg) · viewer, selection, People, Settings, dialog, empty album, loading, Search with keyboard, and 200% font / 10k+ library probes. Sketches are representative styling, **not** new widget designs.

### Type specimen / implementation contract

Font family declarations in SVG use `Funnel Display` and `Funnel Sans`, but fall back to Arial/sans-serif in viewers without those fonts. **The SVG is not evidence of Funnel glyph metrics**. L2 Dev must bundle OFL-noticed Funnel Display 700/800 and Funnel Sans 400/500/600, then capture real Android rendered specimens.

| Role | Face | Sp size/line | Tracking | Text colour |
| --- | --- | --- | --- | --- |
| Screen title | Funnel Display 700 | 38/44 | −0.03em | brand-text |
| Wordmark only | Funnel Display 800 | spec mark | −0.03em | brand-text |
| Section heading | Funnel Sans 600 | 18/24 | normal | ink |
| Date header | Funnel Sans 600 | 16/22 | normal | ink |
| Body | Funnel Sans 400 | 15/22 | normal | ink |
| Secondary | Funnel Sans 400 | 14/20 | normal | ink-secondary |
| Navigation | Funnel Sans 600 | 14/20 | normal | existing role |

No monospace metadata or middle-dot-separated copy. The source app currently calls the destination **Photos**; use that existing label for L2. The Lapis spec's broader **Pictures** naming is not authorization to reorganize or rename navigation in L2.

## Token sheet · verification method

WCAG 2.1 relative luminance: per sRGB channel `v = channel/255`, linearize `L(v) = v/12.92` when `v <= 0.04045`, otherwise `((v+0.055)/1.055)^2.4`; combine `0.2126R+0.7152G+0.0722B`; contrast is `(lighter+0.05)/(darker+0.05)`. Values rounded to 2 decimal places **after** the comparison. Do not use these nominal numbers as a substitute for checking alpha and actual composited UI backgrounds.

| Foreground / background | Light | Dark | Minimum |
| --- | ---: | ---: | ---: |
| ink / ground | 18.49:1 | 18.95:1 | 4.5:1 |
| ink / surface | 16.69:1 | 16.19:1 | 4.5:1 |
| ink-secondary / ground | 6.26:1 | 8.09:1 | 4.5:1 |
| ink-secondary / surface | 5.65:1 | 6.92:1 | 4.5:1 |
| brand-text / ground | 8.36:1 | 8.25:1 | 4.5:1 |
| brand-text / surface | 7.55:1 | 7.05:1 | 4.5:1 |
| brand-fill / ground | 8.36:1 | 3.15:1 | 3:1 |
| on-brand / brand-fill | 8.36:1 | 6.67:1 | 4.5:1 |
| on-brand-secondary / brand-fill | 6.47:1 | 5.16:1 | 4.5:1 |

For on-brand text, the actual field background is **brand-fill**, not ground/surface. The dark filled element is close to its 3:1 minimum: evaluate rendered alpha, gradients or scrims rather than assuming every variant passes.

## L2 mapping proposal, preserving existing components

These are **role assignments to check**, not a claim code has changed.

| Current colour or role | Proposed token / treatment |
| --- | --- |
| `AtlasTokens.Colors.Ink / InkRaised` | ground/surface roles according to usage; text uses ink |
| `Paper`, `Mist` | ground, surface |
| `Tidal`, `DeepTidal` | brand-text for text/icon and brand-fill for solid controls |
| `WarmGray`, `QuietGray` | ink-secondary for text; surface for neutral fields; no independent tinted grey palette |
| `Selection.Accent #3E91FF` | brand-fill; keep existing indicator shape and selected/number semantics |
| `Viewer.SelectionAccent #7FA8FF` | brand-text for dark-background highlights; brand-fill for filled states |
| `Viewer.Favourite #FF4D5E` | brand-text on dark viewer; distinguish favourited by filled icon/accessible state, not a red semantic accent |
| `Viewer.FilmstripTrack #2A2A2A` | dark surface; actual filmstrip selection uses accessible contrast; held-sphere marker belongs L4 |
| `Viewer.MediaTrack #292929` | dark surface track |
| `Viewer.SeekInactive #888888` | ink-secondary on black, with contrast check |
| `Viewer.Backdrop #171A20` | viewer ground black |
| `Viewer.ActionPill #1C1E25` | existing action pill, brand-fill where solid, without constructing the future L4 slab |
| `Shell.DarkSurface / DarkAccent` | dark surface / brand roles |
| `People.Portrait*` | retain image-content variation as synthetic media; do not colour-tint actual faces just to force UI tokens |
| `SyntheticArt.AlbumPalettes` | fictional photo content, not UI chrome; excluded from UI token uniformity |

Material colour scheme aliases must map through these roles in **both** system themes. Keep non-UI media colours and necessary alpha/black scrims explicitly accounted for in the implementation PR audit of `Color(0x…)` outside token definitions.

## State check, accessibility and no-new-components rule

| Existing surface/state | Pre-build styling guidance | Required Android proof |
| --- | --- | --- |
| Photos, default 100% | Brand-text title, ink headers, existing floating 4-tab pill, neutral ground, synthetic media | Light/dark paired actual S24+ capture |
| Viewer / filmstrip / favourite / seek | Black viewer, brand foreground, existing actions; no L4 new sphere | Paired captures with controls shown; contrast of composited seek/filmstrip |
| People and Settings | Existing row/list anatomy, ink/secondary type hierarchy | Light/dark paired captures, count + latest-date wrapping |
| Dialog | Surface panel and meaningful action contrast | Light/dark and focus/TalkBack order |
| Empty album | One sentence plus existing actionable next step, no new explanatory chrome | Light/dark and readable long text |
| Loading and error/retry | Existing progress and existing retry copy/controls; recolour only, no autonomous signature animation | Light/dark with determinate/indeterminate and retry/error examples if exposed |
| 10k+ synthetic library | Existing lazy grid, tokens in sticky section/date metadata; no alteration to virtualization | Synthetic 10k+ dataset, long-scroll performance and header legibility |
| Search with keyboard | Existing Search destination and keyboard-respecting insets | Light/dark S24+ with IME opened; no obscured controls |
| 200% font | Text reflows; existing nav label cap remains; touch targets ≥48 dp | Actual 200% light/dark captures for Photos, People, Settings, Search, dialog and viewer; TalkBack order |

## Independent preflight and implementation gates

- Preflight reviewer should inspect **all three SVG assets**, reference the issue's brief and the approved Quiet Frame v3, and give `Design: PASS|BLOCKING`. Any missing realistic state or layout evidence must be named rather than inferred.
- Preflight **PASS** may route [#655](https://github.com/LPStruiksma/atlas-gallery/issues/655) to Dev with `needs:claude` or any seat able to run Android/render. No app work should be done by this Strategy seat.
- Later Dev PR must contain the token/font changes, OFL notices, automated contrast test in both themes, a complete stray literal inventory, and `scripts/compare_parity_renders.py` before/after pairs at S24+ in light/dark, 100%/200%, with the representative states. Do not accept these SVGs as actual implementation screenshots.
- Only independent exact-head CI and real rendered evidence can close the implementation unit. Neither tests nor device captures were executed for this pre-build artwork.
