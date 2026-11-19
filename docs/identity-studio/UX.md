# Swap person studio: screens and states (IDS-8, #477)

Built from [design brief rev 2](https://github.com/LPStruiksma/atlas-gallery/issues/477#issuecomment-6985033497)
(Design: [PASS](https://github.com/LPStruiksma/atlas-gallery/issues/477#issuecomment-6985083237)).
Code: `app/src/main/java/nl/atlas/gallery/featureidentity/ui/`. Entry from the viewer ⋮ is IDS-11 (#478);
nothing here touches `GalleryViewer.kt` or `MainActivity.kt`.

## Flow

⋮ → Swap person → tap a person → [tap a face, only when several] → version 1 is preselected → Save.
4 taps at minimum, 5 counting ⋮.

| Screen | File | Shown when |
| --- | --- | --- |
| S1 Person sheet | `PersonPickerSheet.kt` | Start, and "Change person" |
| S6 One-time setup | `SwapPersonFlow.kt` (`SetupSheet`) | The engine needs an install |
| S2 Target | `TargetPickerScreen.kt` | IDS-7 returns `Ask`, too small, no face; "Change face" |
| S3/S4 Studio | `CandidateStudioScreen.kt` | Versions exist or are being made |
| Compare all | `CompareAllScreen.kt` | Grid icon in the top bar |
| Split | `CandidateStudioScreen.kt` (`SplitCompareScreen`) | Long press on a strip tile |
| S7 Photos used | `ReferencePhotosSheet.kt` | Long press in S1, S4 ⋮, "too few photos" |
| S8 Choose photos | `ReferencePhotosSheet.kt` | "Choose photos" in S1, "+ Add photos" in S7 |

## S4 layout budget (annotated wireframe)

S24+ default: 411 × 891 dp. The canvas is always black; sheets and dialogs follow the system theme.

```
┌──────────────────────────────── 411 dp ───────────────────────────────┐
│ status bar inset                                              32 dp   │
├───────────────────────────────────────────────────────────────────────┤
│ ✕                                    ⊞ Compare   ⋮   Save     56 dp*  │
├───────────────────────────────────────────────────────────────────────┤
│                                                                       │
│        canvas: selected version, full-bleed, swipe ←→ versions        │
│        (position 0 = Original), pinch ≤ 8×, double tap = face,        │
│        long press = Original while held                   ≈ 635 dp*   │
│                                                                       │
│ (Original)  ← 36 dp pill, 48 dp hit area, 12 dp in                    │
├───────────────────────────────────────────────────────────────────────┤
│ [Orig][ v1 ][ v2 ][ v3 ][ v4 ]  ␣  [ v5 ]…[ + ]   72 dp tiles  88 dp  │
├───────────────────────────────────────────────────────────────────────┤
│ (More natural) (Stronger likeness) (Keep expression) (With their hair)│
│ (Glasses) …   — or "Making 4 versions…" while the first batch runs    │
│                                                               56 dp*  │
├───────────────────────────────────────────────────────────────────────┤
│ gesture inset                                                 24 dp   │
└───────────────────────────────────────────────────────────────────────┘
```

| Band | 100% font | 200% font |
| --- | --- | --- |
| Status bar inset | 32 | 32 |
| Top bar | 56 | 64 |
| Canvas (flex) | ≈ 635 | ≈ 603 |
| Strip | 88 | 88 |
| Refine chip row | 56 | 80 |
| Gesture inset | 24 | 24 |

\* grows with font; the canvas absorbs it. Strip tiles are square crops around the target face
(face box × 1.6), the Original tile uses the same crop with an 11 sp caption. Selection: 2 dp accent
border and scale 1.08 (150 ms). A new batch from Change face/person starts after a 12 dp gap
(8 dp spacing + 4 dp start padding).

## How versions are made

- One version at a time on a warm `IdentityCandidateSession` (`more(1)`), so tiles fill in one by one,
  one failure only marks its own tile (↻ retries it), and face and hair versions can alternate.
- **Mixed first batch** (fix 6f): with a head-capable engine (`HEAD` in `supportedModes`) the first
  batch is face, hair, face, hair. Tiles carry no labels; TalkBack says "Version 2 of 4, with their
  hair". With a face-only engine nothing hair-related is shown. The IDS-6 contract has no batch
  concept, so this rule lives in the studio (`SwapPersonMachine.startBatch`); the contract doc can
  point here when IDS-6 is next touched.
- **More**: 2 new versions of the selected person, look and target (face + hair when available).
- **Refine chips** make 2 versions from the selected version and record it as the refine parent for
  save lineage. The contract offers `IdentityPreferences(hair, keepExpression, moreNatural)` only, so:
  "More natural" → `moreNatural = true`; "Stronger likeness" → `keepExpression = false,
  moreNatural = false`; "Keep expression" → `keepExpression = true`; the hair chip switches mode
  ("With their hair" on a face version, "Keep original hair" on a hair version). There is no separate
  likeness weight in the contract; the session's variation policy still varies blend per attempt.
- **Look chips** (fix 6a): one chip per other saved look; each makes 2 versions from that look.
  The first batch uses "Current" (or the only look). S1 shows a stacked-circle badge on people with
  more than one look.

## Rules decided in the build

- **✕** (fix 6d): no version ready → leave at once and cancel. One or more ready and none saved →
  "Discard these versions?" (Discard · Keep editing); making continues while it is open. This dialog
  **deliberately departs from parity rule 3** because versions take real time on the phone. After a
  successful save, ✕ leaves without asking (the person got their copy).
- **Change face / Change person** (fix 6e): existing versions are kept; the new batch is appended and
  the selection jumps to its first version when it is ready. Compare all shows every version.
- **Zoomed swipe**: when zoomed, a horizontal drag pans and the pager is locked; change version from
  the strip. The pager never jumps mid-pan.
- **Split**: the selected version and the long-pressed tile, stacked in portrait, linked zoom and pan,
  draggable divider. Tap a side to pick it; Back or ✕ exits. A third long press replaces the side
  shown longest.
- **S8**: pick 3–12 photos. The first picked photo has nothing to match against, so a multi-face photo
  always asks "Which one is <name>?" ("this person" for a new profile) with "Skip this photo". Later
  photos are matched locally by the host (`SwapPersonHost.matchFace`) and ask only when the margin is
  not clear. The photo source is supplied by the host; IDS-11 connects it to the ATLAS grid pick mode.
- **S6**: no percentages; a progress bar and "Downloading…". A failed download says "The download
  stopped. It will pick up where it left off." with Try again. OD-1 (private use, weights downloaded
  after install from their original hosts) is the host's concern; the sheet only states the size.
- **Save** goes through `SwapPersonSaver`; IDS-10's `IdentityEditSave` is the production saver (file
  name `<source-stem>_AI_<n>`, never a person's name). IDS-11 constructs it with the source URI.
- **Rotation and process death**: `SwapPersonViewModel` keeps the machine across rotation. Every
  finished version is copied out of the session folder (`cacheDir/atlas-identity-kept/<flow>`), and a
  small text snapshot (ids, face geometry, kept-file paths, variation) goes into `SavedStateHandle`.
  After process death the versions and the chosen version come back without running the engine;
  More and refine reopen a session on demand.

## States

| State | What the person sees | Next action | Render |
| --- | --- | --- | --- |
| People loading | Sheet with grey circles | — | `s1-loading` |
| People | Recents, then named People by photo count; badge for several looks | Tap · long press | `s1-people` |
| Search with keyboard | Full-height sheet, grid above the keyboard | Type · tap | `s1-search-keyboard` |
| No named People | "No named people yet…" and Choose photos | Choose photos | `s1-no-people` |
| Too few photos | "Add a few more photos of <name>" | Add photos → S7 | `s1-too-few` |
| Setup needed / running / failed | "Get Swap person ready" sheet | Download · Not now · Try again | `s6-setup-*` |
| Several faces | Outlined faces, "Tap the face to change" | Tap a face | `s2-target` |
| No face | "We couldn't find a face in this photo" | Close | `s2-no-face` |
| Face too small | "This face is small, results may look soft" | Try anyway · Cancel | `s2-face-small` |
| Making k/4 | Photo visible, shimmer on the face, placeholder tiles, "Making 4 versions…" | Wait · ✕ | `s3-generating-0`, `-2` |
| Studio | Version 1 selected, mixed batch, look chip | Swipe · tap · Save | `s4-studio` |
| Hold Original | The original while the pill is held | Release | `s4-hold-original` |
| ⋮ menu | Change face · Change person · Photos used for <name> | Pick | `s4-menu` |
| More/refine running | Spinner before the chips, pending tiles | Wait | `s4-refine-running` |
| Compare all | Face-crop grid, Original first, linked zoom | Tap to pick · Back | `s4-compare-all` |
| Split | Two versions, divider | Tap a side · Back | `s4-split` |
| ✕ with versions | Discard dialog | Discard · Keep editing | `s4-discard` |
| One version failed | ↻ on its tile | Tap ↻ | `s4-tile-failed` |
| All failed | "Couldn't make versions of this photo" | Try again · Change face | `s4-all-failed` |
| Out of memory | "Your phone needed more memory. Close some apps and try again." | Try again | `s4-out-of-memory` |
| Change person with versions | New batch after a gap | Pick | `s4-appended-batch` |
| Face-only engine | No hair chip, no hair versions | — | `s4-face-only` |
| Saving / saved / failed | Spinner in Save · "Saved as a copy" (View) · "Couldn't save this copy." | View · Try again | `s5-*` |
| Photos used | Faces with remove ✕, + Add photos, Forget photos for <name> | Remove · add · forget | `s7-photos-used` |
| Forget | "Forget? Your saved photos stay." | Forget · Cancel | `s7-forget` |
| Choose photos | 3-column grid, check circles, "Choose 3 to 12 photos…" | Next | `s8-choose` |
| Several faces in a reference | "Which one is this person?" | Tap face · Skip this photo | `s8-which-face` |
| Name with keyboard | Field above the keyboard, chosen faces above it | Done | `s8-name-keyboard` |

Every render exists in light and dark at 100% and 200% font
(`scripts/ui-evidence.d/467-swap-person.txt`, 132 images), all synthetic: drawn cartoon faces,
invented names and `FakeIdentityEditEngine`.

## Motion and access

Sheets 300 ms emphasized decelerate, crossfades 200 ms, selection 150 ms; all instant under reduced
motion (animator scale 0), where the S2 pulse and S3 shimmer also stop. Touch targets ≥ 48 dp,
including small faces in S2 and S8. TalkBack: "Version 2 of 4, selected", "Original", "Hold to see
original", "Compare all versions", and "Version 3 ready" announced politely.
