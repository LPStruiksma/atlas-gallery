# WIDGET-1 · synthetic pre-build evidence index (task scaffold)

> **Status: NOT RENDERED.** This commit is only the input contract for a bounded Codex Cloud design task. No image, launcher, emulator capture, contrast assertion, or Design: PASS is present. Each finished mock caption must say **“Synthetic design, not actual launcher/emulator capture.”**

## Task and limits

Unit: [#846](https://github.com/LPStruiksma/atlas-gallery/issues/846) · Parent [WIDGET-1 #590](https://github.com/LPStruiksma/atlas-gallery/issues/590) · [WIDGET-1 rev-1 brief](https://github.com/LPStruiksma/atlas-gallery/issues/590#issuecomment-7057557272) · [prior blocking preflight](https://github.com/LPStruiksma/atlas-gallery/issues/590#issuecomment-7052555138) · [Quiet Frame v3](../QUIET_FRAME.md) · [Design rubric](https://github.com/LPStruiksma/autodev/blob/main/docs/design/rubric.md).

**Allowed output:** text-only SVG vector sheets, a self-contained deterministic generator/checker if useful, and a completed README *under this directory only*. Optional PNG may be generated locally as a proof aid, but do not claim it is committed or embed binary PNG in a Codex format-patch; auto-apply rejects binary. **Forbidden:** app, manifest/provider/config runtime changes; dependencies; other design directories; private photos/embeddings; actual Samsung/Ente/Aves artworks; unsupported runtime, density, permission or TalkBack claims.

## Numbered frame index — required output, not current proof

All primary launcher/configuration mock phones have an explicitly drawn **1080 × 2340 px** outer frame (S24+ target) and stated synthetic geometry. The nominal **360 dp logical width** is an annotation assumption, *not* a measured device density or host bound. For each, annotate provisional status/navigation/cutout, actual touch bounds and safe content area; explicitly label host-system metrics UNKNOWN pending physical measurement. Make each phone identifiable by id in the SVG and provide clickable fragment links here once generated.

| Frame IDs | Design to render | Variants / inspections |
| --- | --- | --- |
| L1–L4 | Full busy synthetic wallpaper and surrounding launcher icons with photo-led **2×2 (~160×160 dp)** and **4×2 (~328×160 dp)** widgets | Each size in **light and dark**; 22 dp nominal host-safe clipped radius, actual system radius UNKNOWN; center crop + face focal/crop guides, 48×48 dp bottom-right Next action, separate photo-tap region; optional contrast-safe source label wide-only |
| C1–C4 | Initial Photo widget configuration: preview, Favourites selected by default, Person, Album, On this day, 328×48 dp bottom Add widget | Light/dark at 100% and 200% text; measured rectangles, 16 dp side gutters, scrolling content independent of CTA and system navigation |
| P1–P4 | Person and Album picker *separately*, image-backed rows, search and long name, deterministic alphabetical list, 200+ items, zero-match result | Each source type in light/dark; 100%/200% type, search keyboard up and no-search; include ≥64 dp rows growing at 200%, 48 dp hit bounds |
| K1–K2 | Keyboard open with **minimum viable preview ≥96 dp** and pinned reachable Add/Select control above keyboard | Light/dark 200% font, explicit IME and insets, one-handed safe reach, no invented exact host keyboard heights |
| S1–S10 | **Ready, first/new-empty, loading, hidden-only, permission revoked, deleted source/invalid URI, decode failure, resize, removed, two independently configured widgets** | Paired light/dark and 100%/200% type *visibly inspectable*; do not merely describe these states; no old hidden/deleted image on fallback; two widgets have distinct source identities |
| A1–A2 | Interaction and accessibility overlay | Photo region says “Open photo in Atlas”; discrete Next control says “Next photo”; TalkBack reading order title → preview → source/search → action, selected/checked announced, ≥48 dp targets, ≥4.5:1 real-composite text contrast, reduced motion = no automatic animation |

Use **synthetic abstract landscape/portrait placeholders** created from SVG shapes. Do not reproduce competitor art. Text should be normal sentence case from Quiet Frame v3, with the source list in exactly this order: **Favourites (default), Person, Album, On this day**.

## Source → observed → ATLAS adaptation / deviation / unknown

Fill this table with date and platform and link exact sources in finished output. Distinguish claims found in the source from assumptions not observed; no reverse-engineered Samsung coordinates.

| Source | Observed / verifiable (to check on source) | Adaptation proposed | Deviation / unknown |
| --- | --- | --- | --- |
| [Android app widgets](https://developer.android.com/develop/ui/views/appwidgets), [configuration](https://developer.android.com/develop/ui/views/appwidgets/configuration), [responsive layouts](https://developer.android.com/develop/ui/views/appwidgets/layouts) | Launcher-host sizing and AppWidgetProvider/RemoteViews/configuration APIs, not measured Samsung cell dimensions | S24+ target placements and responsive crop/host-safe radius | Real launcher grid/corner, insets, density, Android 29 fallback remain unmeasured |
| [Ente Home Widgets official help](https://ente.com/help/photos/features/utilities/home-widgets) | Album/People/Memories widget source and launcher placement flow described by Ente | One placement-time source selector with an eligible photo tap | ATLAS selects one source per widget; no copied Ente UI pixels |
| [Aves changelog](https://github.com/deckerst/aves/blob/develop/CHANGELOG.md) | Widget shapes and tap action options recorded in changelog | Full photo region opens item; independent Next photo affordance | No claim of tested Aves current build, measurements or source picker |
| [Quiet Frame v3](../QUIET_FRAME.md) | Approved Lapis tokens, 22 dp secondary radius, 48 dp targets, held sphere and concise copy | Lapis next control, contrast-safe surfaces and no decorative chrome | Layout host may clip corners and captions; real contrast must be checked against composite background |

## Inspection criteria for generated assets

- Provide SVG direct links, frame IDs, output dimensions, generated-at/source access dates and generated deterministic command. Include at least one substantive *visually rendered* example of every state/variant above, not diagrams with mere labels.
- Check contrast ratios via **sRGB luminance math** for each text/background pair (≥4.5:1), including photo-backed controls/scrims. Draw measured 48 dp hit rectangles and annotate overlap/spacing, 22 dp radius and crop guide assumptions; prevent clipping at 200% text.
- Demonstrate a 4×2 wide widget source label **only** with contrast-safe surface, and omit it when 200% font would obscure photo. The 2×2 should prioritize photo.
- Favourites and On this day empty states permit placement; Person/Album require a selected stable identity. Person/Album results show cover/face placeholders, search and >200-list handling, no invented similarity ranking.
- Show safety behavior for hidden-only, source deletion, revoked permission, invalid URI and decode failure; state **never** displays a stale hidden/deleted bitmap. Show removal cleanup and independent A/B widget source identity in design annotations, without implying implementation verified.
- Explicitly separate **pre-build synthetic design evidence** from future exact-head runtime emulator/device screenshots, TalkBack, keyboard, resize, hidden-toggle/permission, 2-widget and cache-invalidation acceptance for #590. The reviewed #590 design gate must be independent; this file alone proves nothing.

No third-party material is bundled. Scope and intent must remain fidelity to [#846](https://github.com/LPStruiksma/atlas-gallery/issues/846).
