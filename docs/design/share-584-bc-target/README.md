# SHARE-1BC-E · wallpaper and copy: Atlas-owned large-text states

**Status: synthetic design evidence for an independent B/C preflight.** Not a Samsung observation, Android implementation, runtime screenshot or design PASS. Unit [#1118](https://github.com/LPStruiksma/atlas-gallery/issues/1118), parent [#584](https://github.com/LPStruiksma/atlas-gallery/issues/584), goal [#97](https://github.com/LPStruiksma/atlas-gallery/issues/97). It answers state finding 6 of the [v3 preflight](https://github.com/LPStruiksma/atlas-gallery/issues/584#issuecomment-7232535564): the v3 README labelled B/C at 200 % as Android-owned, although the More menu and the B/C error dialogs are Atlas-owned. The [pinned v3 baseline](https://github.com/LPStruiksma/atlas-gallery/tree/7857a46f8df06491dd00719303c5236f94b5fd94/docs/design/share-584-target) stays read-only. Flows A and D are out of scope. No parity checklist item is completed here.

Every image is drawn from deterministic shapes: no private photo, real location or user record.

## What is proposed

**One viewer More menu.** Live `main` has one viewer overflow (`atlas-viewer-more`) with `AI Edit` and, only when eligible, `Swap person` ([GalleryViewer.kt](https://github.com/LPStruiksma/atlas-gallery/blob/7857a46f8df06491dd00719303c5236f94b5fd94/app/src/main/java/nl/atlas/gallery/GalleryViewer.kt#L949-L972)). The proposal keeps both, in that order, and appends `Set as wallpaper` and `Copy image`, shown for still images only.

| Item | Shown when | Origin |
| --- | --- | --- |
| AI Edit | still image and `PhotoEditorHandoff.eligible` | existing |
| Swap person | directly under AI Edit, only when the swap port says eligible | existing |
| Set as wallpaper | still image | proposed |
| Copy image | still image | proposed |

More is shown whenever at least one item applies (today only when AI Edit applies). A video has no item, so More stays hidden for videos, as today. Ineligible items are hidden, never greyed out.

**At 200 % text the menu reflows; it never shrinks text.** The menu follows the Material 3 `DropdownMenu` limits: width 112–280 dp (labels wrap at 280 dp, so `Set as wallpaper` takes two lines), rows grow with their text and stay ≥ 48 dp, and the popup keeps a 48 dp margin from the window edge. When it is still too tall, it first moves up, then scrolls (landscape). A cut-off row is the scroll cue; focus or a swipe brings every item fully into view.

**Explicit item identity at large text.** At font scale ≥ 1.5 the larger popup hides most of the photo (20–29 % stays visible), so the menu opens with a non-interactive row: a thumbnail of the current photo and **This photo**, announced as “Actions for *item*”. The row is **pinned**: it sits outside the scrolling rows, so it stays fully visible at every scroll position, including when Set as wallpaper and Copy image are scrolled into view in landscape. A plain `DropdownMenu` scrolls its whole content, so production needs a popup with a fixed header above a scrolling column. At 100 % the photo stays 62–79 % visible and no row is added. The verifier enforces the rule both ways and fails any frame below 40 % photo visibility without a fully visible, pinned identity row.

**Partly shown rows are scroll cues, not actions.** While the rows scroll, a row cut by the viewport edge never activates its action: a tap on its visible part or keyboard/TalkBack focus scrolls it fully into view, and only then can it be activated. `geometry.json` marks every menu row `activation: activate` (fully shown, labelled, ≥ 48 dp) or `reveal-only` (partly shown); the verifier fails any partly shown row that activates and checks size, safe insets and non-overlap for every activating row. The annotated frames mark reveal-only rows in pink, and every one is fully shown and activating in the matching scrolled frame.

**Atlas-owned errors, same dialog at any text size.** The title and body form one message that takes focus first (drawn with the focus outline) and is read whole. When it does not fit (landscape, 200 %), the message scrolls with a position bar on its trailing edge while **OK stays pinned** and reachable. OK or system Back returns to the same viewer item. Nothing is changed: no wallpaper, no clipboard write, no file change.

| Case | Frame stem | Copy (title · body) |
| --- | --- | --- |
| Missing handler: nothing resolves `ACTION_ATTACH_DATA` | `B-no-handler` | No app can set wallpaper · Nothing was changed. |
| Unsupported still, or grant revoked before handoff | `B-unsupported` (longest B copy) | Can't use this file as wallpaper · The format is not supported or the photo is no longer available. Nothing was changed. |
| Revoked grant or unreadable URI before `ClipData` is set | `C-revoked` (longest C copy) | Couldn't copy image · The photo can't be read right now. The clipboard was not changed. |

Copy is unchanged from v3. Android owns the wallpaper picker (its own crop and home/lock choice; Atlas has no cropper) and the clipboard confirmation. Copy puts a read-granted `content://` URI in `ClipData`; no persistent grant is assumed. Those system screens are not drawn here (v3 has conceptual placeholders).

## Frames (34)

Portrait 1080×2340 and landscape 2340×1080 at 2.8125 px/dp, synthetic safe insets as in v3. Direct links point to this directory; the PR records the exact commit.

| Group | Frames |
| --- | --- |
| Menu 100 % baseline | [BC-menu-light](png/BC-menu-light.png), [BC-menu-dark](png/BC-menu-dark.png), [BC-menu-light-landscape](png/BC-menu-light-landscape.png), [BC-menu-dark-landscape](png/BC-menu-dark-landscape.png) |
| Menu 200 % portrait | [BC-menu-light-200](png/BC-menu-light-200.png), [BC-menu-dark-200](png/BC-menu-dark-200.png) |
| Menu 200 % landscape, scroll top and end | [BC-menu-light-200-landscape](png/BC-menu-light-200-landscape.png), [scrolled](png/BC-menu-light-200-landscape-scrolled.png); [BC-menu-dark-200-landscape](png/BC-menu-dark-200-landscape.png), [scrolled](png/BC-menu-dark-200-landscape-scrolled.png) |
| Menu 200 % eligibility | [BC-menu-noswap-light-200](png/BC-menu-noswap-light-200.png) (Swap person ineligible), [BC-menu-noaiedit-dark-200](png/BC-menu-noaiedit-dark-200.png) (AI Edit ineligible: wallpaper and copy only) |
| Missing handler | 100 % [light](png/B-no-handler-light.png) / [dark](png/B-no-handler-dark.png); 200 % [light](png/B-no-handler-light-200.png) / [dark](png/B-no-handler-dark-200.png); 200 % landscape [light](png/B-no-handler-light-200-landscape.png) / [dark](png/B-no-handler-dark-200-landscape.png) |
| Unsupported / revoked (B) | 100 % [light](png/B-unsupported-light.png) / [dark](png/B-unsupported-dark.png); 200 % [light](png/B-unsupported-light-200.png) / [dark](png/B-unsupported-dark-200.png); 200 % landscape [light](png/B-unsupported-light-200-landscape.png) / [dark](png/B-unsupported-dark-200-landscape.png), scrolled [light](png/B-unsupported-light-200-landscape-scrolled.png) / [dark](png/B-unsupported-dark-200-landscape-scrolled.png) |
| Revoked (C) | 100 % [light](png/C-revoked-light.png) / [dark](png/C-revoked-dark.png); 200 % [light](png/C-revoked-light-200.png) / [dark](png/C-revoked-dark-200.png); 200 % landscape [light](png/C-revoked-light-200-landscape.png) / [dark](png/C-revoked-dark-200-landscape.png), scrolled [light](png/C-revoked-light-200-landscape-scrolled.png) / [dark](png/C-revoked-dark-200-landscape-scrolled.png) |

**Annotated geometry:** every 200 % frame has a twin in [`annotated/`](annotated/) with each control's semantic rectangle, its focus-order number and its size in dp (green: fully shown and activating; pink: partly shown, reveal-only), the surface outline (orange) and the scroll viewport (blue). Overlays are a review aid, not part of the design.

## Geometry, focus and dismissal

[`geometry.json`](geometry.json) lists per frame: every active control with id, role, label and visible semantic rectangle **in focus order** (offscreen or partly shown rows carry their content rectangle), the inert viewer controls behind the surface, the surface rectangle, scroll container and offset, dismissal path, return target (`same viewer item (synthetic-lake), viewer state unchanged`) and the fraction of the photo left visible.

- **Focus isolation:** while the menu or a dialog is open, the viewer (Back, More, photo, filmstrip, slab) is inert and absent from focus order.
- **Focus order:** menu: identity row (at ≥ 1.5×), AI Edit, Swap person, Set as wallpaper, Copy image. Dialog: message first, then OK.
- **Dismissal:** menu: outside tap or Back, no action, focus back on More. Dialog: OK or Back.
- **Return:** every path ends on the same viewer item with zoom and position unchanged (v3 [BC-return](https://github.com/LPStruiksma/atlas-gallery/blob/7857a46f8df06491dd00719303c5236f94b5fd94/docs/design/share-584-target/png/BC-return.png)).

## Reproduce and verify

```bash
python3 docs/design/share-584-bc-target/bc_frames.py --build --render --verify
```

Needs Python 3, CairoSVG, Pillow and DejaVu Sans. `--build` writes SVG and `geometry.json`; `--render` writes `png/` and `annotated/`; `--verify` re-renders each SVG and compares it with the committed PNG. It then checks Quiet Frame v3 palette tokens, text fit and ≥ 4.5:1 contrast for every readable text run, ≥ 48 dp size, safe insets and non-overlap of every fully shown tappable control, containment in the surface and scroll viewport, focus order equal to the active controls, viewer isolation, declared dismissal, error focus-first and OK-only actions, menu order and still-image items, the identity-row rule (fully visible and pinned when the rows scroll), a visible photo ≥ 40 % or explicit identity, exact 2× type of each 200 % frame against its 100 % counterpart, and that every row hidden at scroll top is fully shown in the scrolled frame.

Result at this commit: **34 frames** (24 at 200 %, 16 landscape), 97 controls, 345 control checks, 30 scale and scroll checks, 273 text runs, minimum contrast **6.26:1**, **0 errors**.

## Matrix: rendered, specified, runtime-untested

| Situation | Menu | B no handler | B unsupported / revoked | C revoked |
| --- | --- | --- | --- | --- |
| 100 % light / dark | Rendered (portrait + landscape) | Rendered | Rendered | Rendered |
| 200 % light / dark portrait | Rendered | Rendered | Rendered | Rendered |
| 200 % light / dark landscape | Rendered, scroll top + end | Rendered (fits) | Rendered, scroll top + end | Rendered, scroll top + end |
| Eligibility (no Swap, no AI Edit) | Rendered at 200 % | n/a | n/a | n/a |
| Video | Specified: More hidden | n/a | n/a | n/a |
| Other text sizes (115–190 %) | Specified: same reflow and scroll rules; identity row from 150 % | Specified | Specified | Specified |
| Android wallpaper picker, clipboard confirmation | Android-owned; not drawn (v3 placeholders) | | | |
| C on an unsupported format | Specified: no Atlas error; the receiving app decides | | | |
| TalkBack, real focus traversal, Funnel fonts, `ACTION_ATTACH_DATA` resolution, grants, clipboard | **Runtime-untested** | **Runtime-untested** | **Runtime-untested** | **Runtime-untested** |

**Release gates:** an independent reviewer must open the PNGs; artifact review of this PR is not parent design approval. Parent Strategy packages a separate B/C production preflight after merge. Shipping needs an independent visual PASS and synthetic Android intent and clipboard tests. Coordinate ownership of the viewer overflow with [#142](https://github.com/LPStruiksma/atlas-gallery/issues/142) and [#573](https://github.com/LPStruiksma/atlas-gallery/issues/573) before production. No Kotlin, manifest, intent, grant or media change is made here.
