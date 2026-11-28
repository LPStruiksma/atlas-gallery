# Accessible states matrix — proposed, synthetic

Every linked SVG has an original paired full-size light/dark primary frame, plus **three distinct named state studies rendered in light and dark**. “Shown” means source artwork exists, not that Android produced the state or that independent visual inspection passed.

| Group / named state | State-specific visual and status | Proposed TalkBack semantics | Intended next action | Runtime boundary |
|---|---|---|---|---|
| V11 Provider chooser | [On-device selected](v11-ai-edit.svg) | “Local provider, selected” | Use on-device | Device and actual platform screen not verified |
| V11 Result selected | [One saved preview](v11-ai-edit.svg) | “Result one, selected” | Apply result | Device and actual platform screen not verified |
| V11 Provider error | [No edit was saved](v11-ai-edit.svg) | “Provider unavailable” | Try again | Device and actual platform screen not verified |
| V12 Person chooser | [Mina selected](v12-swap-person.svg) | “Mina, selected button” | Use Mina | Device and actual platform screen not verified |
| V12 Candidate studio | [Compare candidates](v12-swap-person.svg) | “Two candidates ready” | Compare | Device and actual platform screen not verified |
| V12 Compare results | [Review difference](v12-swap-person.svg) | “After version selected” | Keep result | Device and actual platform screen not verified |
| V13 Visual results | [18 example photos](v13-search-results.svg) | “Photo one, button” | Open photo | Device and actual platform screen not verified |
| V13 Face results | [92% sample similarity](v13-search-results.svg) | “First match, 92 percent” | View match | Device and actual platform screen not verified |
| V13 No matches | [Try broader terms](v13-search-results.svg) | “No matches, status” | Clear search | Device and actual platform screen not verified |
| V14 Single picker | [1 selected](v14-android-entry.svg) | “Photo one, selected” | Use photo | Device and actual platform screen not verified |
| V14 Multi picker | [2 selected, numbered](v14-android-entry.svg) | “Two photos selected” | Add 2 photos | Device and actual platform screen not verified |
| V14 Viewer / share / edit handoff | [Atlas handoff](v14-android-entry.svg) | “Share photo, button” | Share photo | Device and actual platform screen not verified |
| V15 Empty | [No photos here](v15-shared-states.svg) | “Empty album, status” | Browse photos | Device and actual platform screen not verified |
| V15 Loading | [7,140 of 12,305](v15-shared-states.svg) | “7140 of 12305, status” | Keep browsing | Device and actual platform screen not verified |
| V15 Search: no matches | [No matching photos](v15-shared-states.svg) | “No search results” | Clear search | Device and actual platform screen not verified |
| V16 Long album | [12,305 sample photos](v16-album-detail.svg) | “Album, 12305 photos” | Jump to recent | Device and actual platform screen not verified |
| V16 Album error | [Media could not load](v16-album-detail.svg) | “Album error, status” | Retry album | Device and actual platform screen not verified |
| V16 Permission grant | [No photos granted](v16-album-detail.svg) | “Allow access, button” | Choose photos | Device and actual platform screen not verified |
| V17 Notification progress | [68% sample progress](v17-model-install.svg) | “Download, 68 percent” | View status | Device and actual platform screen not verified |
| V17 Notification error | [Model not installed](v17-model-install.svg) | “Download failed, status” | Retry download | Device and actual platform screen not verified |
| V17 Retry action | [Not downloading now](v17-model-install.svg) | “Retry download, button” | Start retry | Device and actual platform screen not verified |

## Review assertions and remaining validation

- The chooser, result, failure, search, selection, empty, loading, permission and notification concepts use different geometry, text and actions. V13 no-match is **not** a 92% confidence state; V15 empty/no-match have **no** loading count; V17 failure/retry have **no** stale 68% progress.
- The proposed selected item uses filled lapis sphere with 2 dp white halo and spoken selected state. Scoped picker uses 1 for single and 2 for multi. Unknown load totals must never borrow the illustrated synthetic 7,140/12,305 known-count status.
- Primary frame annotations state proposed insets, 100%/200% specimen, action reach and 48 dp target intent. A scaled SVG study does not verify device-size touch target, Android layout/font scaling, actual safe insets, focus, TalkBack semantics, motion, physical reach or keyboard.
- Proposed focus order is heading → status/media → selection → action. Static SVG does not verify 200 ms deliberate Lift or immediate remove-animations behavior.
- V14 OS picker/share/editor chrome and V17 actual OS notification templates remain outside Atlas-owned UI and are intentionally **absent**. No runtime preflight PASS is claimed.
