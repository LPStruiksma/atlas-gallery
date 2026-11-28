# SHARE-1 v3 · share, wallpaper, copy and date/location design target

**Status: synthetic design proposal for independent preflight.** Not a Samsung observation, Android implementation, runtime screenshot or design PASS. Unit [#1115](https://github.com/LPStruiksma/atlas-gallery/issues/1115) (SHARE-1P2) corrects the [v2 preflight defects](https://github.com/LPStruiksma/atlas-gallery/issues/584#issuecomment-7231982556) on the [#966](https://github.com/LPStruiksma/atlas-gallery/pull/966) target. Parent [#584](https://github.com/LPStruiksma/atlas-gallery/issues/584); goal [#97](https://github.com/LPStruiksma/atlas-gallery/issues/97). Scope is this directory only. Every image is drawn from deterministic shapes: no private photo, real coordinates or user record. No parity checklist item is completed by this document.

## Sources and classification

| Decision | Source | Classification |
| --- | --- | --- |
| Black photo-first viewer, held sphere, white-ring filmstrip, lapis slab; no new permanent viewer chrome | [Quiet Frame v3](../QUIET_FRAME.md), [OWNER_INTENT](../../OWNER_INTENT.md), [#142](https://github.com/LPStruiksma/atlas-gallery/issues/142) | Approved identity, adapted |
| A: options sheet before Android's sharesheet; location-free share uses a verified temporary derivative | Android [FileProvider](https://developer.android.com/reference/androidx/core/content/FileProvider), [sharing](https://developer.android.com/training/sharing/send) | **Platform-derived proposal** |
| B: `Set as wallpaper` hands off with `ACTION_ATTACH_DATA` | Android [Intent](https://developer.android.com/reference/android/content/Intent) | **Platform-derived proposal** |
| C: `Copy image` puts a readable `content://` URI in `ClipData`; Android shows its own feedback | Android [clipboard](https://developer.android.com/develop/ui/views/touch-and-input/copy-paste) | **Platform-derived proposal** |
| D: Details → `Edit date and location` → Android write consent → Undo | Android [MediaStore](https://developer.android.com/reference/android/provider/MediaStore), [shared storage](https://developer.android.com/training/data-storage/shared/media) | **Platform-derived proposal**; mechanism unresolved (below) |
| Samsung placement or behaviour for these four actions | No independently sourced observation IDs available to this unit | **Not demonstrated Samsung parity.** No OEM geometry or finding IDs are claimed; private research is not copied |
| Black 1 dp external keyline around the 2 dp white filmstrip ring and the `#8C9BFF` sphere | [#142 critique](https://github.com/LPStruiksma/atlas-gallery/issues/142#issuecomment-7056330457) | Documented adaptation using the existing viewer ground; coordinate with #142, not a claim that #142 is fixed |

## A · share without location: state machine

The `Remove location` preference is **Off only on first use**. Only the user changes it, with the switch in this sheet, while the sheet is idle (Off, ready or no-location state). It is never changed by Atlas, by a failure, or by a recovery button. Inaccessible, redacted or unreadable location is **not** treated as “no location”.

| State | Frame | Remove location | Actions (focus order) | Dispatch |
| --- | --- | --- | --- | --- |
| Off (initial only) | `A-off-*` | Off, interactive switch | switch, Cancel, Share | Original provider URI with read grant (user's explicit choice) |
| On · preparing | `A-preparing-*` | On, badge “Stays on for this share” | progress message, Cancel, Share (disabled) | **Disabled** until verification ends |
| On · verified ready | `A-ready-*` | On, interactive | switch, Cancel, Share | Verified temporary GPS-free derivative only |
| On · verified no location | `A-nogps-*` | On, interactive | switch, Cancel, Share | Still the verified temporary derivative, never the original |
| On · failed | `A-failed-*` | On, badge | **error message (focused)**, Cancel, Retry | **Blocked**: no outbound intent, no original-URI fallback |
| On · location unknown | `A-unknown-*` | On, badge | **error message (focused)**, Cancel, Retry | **Blocked** |
| On · too large / unsupported | `A-unsupported-*` | On, badge | **error message (focused)**, Cancel | **Blocked** |

Transitions: viewer Share → Off (first use) or straight to preparing (On remembered). Switch Off→On → preparing → ready, no-location, failed, unknown or unsupported. Retry → preparing. Switch On→Off from ready/no-location → Off (explicit). Share → Android sharesheet (`A-sharesheet`, system-owned) → back to the **same viewer item** (`A-return`). Cancel, system Back or scrim tap at any state → same viewer item, nothing sent; an in-flight preparation is cancelled and its temporary file deleted.

Specified, not rendered as separate frames: **huge/unsupported media** fail closed into `A-unsupported`; **offline** makes no difference (preparation is local, no network); **late reads** by the recipient are covered by a bounded read grant on the temporary file, kept for a bounded window after dispatch rather than deleted when the chooser returns; **cleanup** removes temporary derivatives on cancel, on failure, after the window, and on next start. The original file is never rewritten by A. All seven states are rendered light and dark; all seven at 200 % (light) plus `A-failed-dark-200`; ready and failed also in landscape.

## B/C · one More menu

Live `main` at `eb1880e` has one viewer overflow: `atlas-viewer-more` with `AI Edit` and, only when eligible, `Swap person` ([GalleryViewer.kt lines 949–972](https://github.com/LPStruiksma/atlas-gallery/blob/eb1880e30f6ad41e75b5773d34c1a3ec0935190c/app/src/main/java/nl/atlas/gallery/GalleryViewer.kt#L949-L972)). The proposal keeps both, in that order, and appends `Set as wallpaper` and `Copy image`. The **same** menu (`BC-menu-light`, `BC-menu-dark`, `BC-menu-dark-landscape`) serves both flows; there is no second menu variant. Today More is shown only when AI Edit is eligible; the proposal shows it whenever at least one item applies (wallpaper and copy: still images only). Coordinate ownership of the overflow with [#142](https://github.com/LPStruiksma/atlas-gallery/issues/142) and [#573](https://github.com/LPStruiksma/atlas-gallery/issues/573) before production.

B: `Set as wallpaper` → Android wallpaper picker (`B-wallpaper-handoff`, system-owned, conceptual) → same item. No app (`B-no-handler-light`) or unsupported/revoked file (`B-unsupported-dark`) → short message, OK, nothing changed. No Atlas cropper. C: `Copy image` checks the URI is readable first → Android's own clipboard confirmation (`C-copy-feedback`) → same item; unreadable or revoked (`C-revoked-light`, `C-revoked-dark-landscape`) → message, clipboard unchanged. `BC-return` shows the safe return. The menu is a popup: outside tap or Back closes it with no action.

## D · date and location

One term, **location**, throughout. A location is a **point chosen on an offline map**, shown with its coordinates (synthetic `10.00° N, 20.00° E`), never a free-text place name alone. Time shows date, time and an explicit UTC offset with its time zone (synthetic example `UTC+02:00, Europe/Amsterdam`).

Frames: `D-details-light/dark`; no location (`D-details-nolocation-light`, `D-editor-nolocation-light` with “Add location”); read-only or unsupported, Edit absent (`D-details-readonly-dark`); editor light/dark and landscape; keyboard open at 100 % and 200 % portrait and 200 % landscape, where **Save and Cancel stay pinned above the keyboard** and the focused field stays visible; `D-discard-light` (explicit **Discard** vs **Keep editing**); `D-consent` (Android write consent, system-owned, conceptual; Deny keeps the draft); `D-write-failed-light/dark` (Retry/Cancel, edits kept); `D-saved-undo-light/dark` (Undo).

Keyboard dismissal (the IME's hide key or the first Back while it is open) only hides the keyboard and **keeps the draft**. Discarding needs Cancel or a second Back, then an explicit **Discard**.

**Unresolved, pending an independent high-stakes gap pass ([#786](https://github.com/LPStruiksma/atlas-gallery/issues/786), [#579](https://github.com/LPStruiksma/atlas-gallery/issues/579))**: transaction mechanism; coordinate validation; civil time and UTC-offset handling; consent renewal (including Undo after restart); atomicity and rollback; backup retention; index reconciliation. The copy “Nothing was saved” on write failure is the **target** behaviour and holds only if that gap pass proves it. This document does **not** imply a provider can atomically replace bytes, and performs no app or storage operation.

## Palette

All UI colours are [Quiet Frame v3 tokens](../QUIET_FRAME.md#colour-tokens); the verifier rejects any other colour outside the synthetic photo art. The v2 literal error red `#B3222B` and the extra dark card shade are gone. Errors are carried by explicit copy, an outlined `!`/`?` glyph and **focus moved to the message**, never by colour alone.

| Use | Light | Dark |
| --- | --- | --- |
| Sheet / dialog surface | ground `#FFFFFF` | mist `#14152A` |
| Rows, fields, secondary buttons | mist `#F2F3F8` | ground `#000000` on mist surfaces; mist on full screens |
| Primary text / error and focus outline | ink `#0E1030` | ink `#F2F3F8` |
| Helper text, Off switch track | ink-secondary `#5A5F7A` | ink-secondary `#9AA0B8` |
| Titles | brand-text `#2639C9` | brand-text `#8C9BFF` |
| Primary button, On badge | brand-fill `#2639C9` | brand-fill `#3448E0` |
| On switch track | brand-fill `#2639C9` | brand-text `#8C9BFF` (≥3:1 on its row) |
| Viewer ground, slab, sphere, ring, keyline | `#000000`, `#3448E0`, `#8C9BFF`, `#FFFFFF`, `#000000` | same |

## Geometry, focus and accessibility

Portrait 1080×2340 and landscape 2340×1080 at **2.8125 px/dp**; synthetic safe insets L/T/R/B portrait 0/125/0/135 px, landscape 125/68/135/0 px (from the [#142 target](../viewer-142-target/README.md), not measured on an S24+). Type uses Quiet Frame sizes (section title 18 sp, body 15 sp, secondary and labels 14 sp); 200 % frames double every sheet size exactly. Art uses DejaVu Sans as a measurable stand-in for Funnel Sans; runtime fonts are untested.

[`geometry.json`](geometry.json) lists, per frame, every control with id, role, label and semantic rectangle **in focus order**, the modal surface, inert background controls, the IME rectangle, the dismissal path and the share state. While a sheet, dialog, menu or editor is open the viewer behind it is inert and absent from focus order. Alerts take focus first and are drawn with a focus outline.

## Reproduce and verify

```bash
python3 docs/design/share-584-target/build_frames.py                     # SVG + geometry.json
python3 docs/design/share-584-target/render_and_verify.py --render --verify
```

Needs Python 3, CairoSVG, Pillow and DejaVu Sans. `--verify` re-renders every SVG and compares it with the committed PNG, then checks palette, text fit and contrast against the rendered text-free background, control size (≥135 px = 48 dp), safe insets, modal containment, IME clearance, non-overlap, focus order, modal isolation, dismissal, switch-track contrast, share-state invariants and the exact 2× scale. Current result: **54 frames** (48 portrait, 6 landscape), 197 controls, 1,107 control checks, 24 share-state checks, 8 scale checks, 432 text runs with minimum contrast **5.65:1**, **0 errors**. The PR records the exact commit and hosted CI.

## Matrix: rendered, specified, runtime-untested

| Situation | A share | B wallpaper | C copy | D edit |
| --- | --- | --- | --- | --- |
| Normal path and return | Rendered | Rendered (handoff conceptual) | Rendered (feedback conceptual) | Rendered |
| Light/dark | Rendered, all states | Rendered menu; errors in one theme each | Rendered menu; error light + dark landscape | Rendered |
| Landscape | Rendered ready + failed | Rendered menu | Rendered error | Rendered editor + 200 % IME |
| 200 % text | Rendered, all states | Specified (Android-owned) | Specified (Android-owned) | Rendered editor with IME |
| No location / unknown | Rendered both, kept distinct | n/a | n/a | Rendered no location |
| Failure / unsupported / revoked | Rendered | Rendered | Rendered | Rendered read-only + write failed |
| Cancel, consent, discard, Undo | Rendered cancel path | Rendered OK | Rendered OK | Rendered; atomicity unresolved |
| Offline, huge media, late read, cleanup | Specified above | Specified | Specified | Gap pass |
| TalkBack, real focus traversal, runtime fonts, grants, file writes | **Runtime-untested** | **Runtime-untested** | **Runtime-untested** | **Runtime-untested** |

**Release gates:** an independent reviewer must open the regenerated PNGs; artifact review of this PR is not parent design approval. #584 production stays held until this issue closes and an independent parent preflight passes; Parent Strategy owns the split into A, B/C, D and the D gap pass. No Kotlin, manifest, intent, grant, EXIF or original-media change is made here.
