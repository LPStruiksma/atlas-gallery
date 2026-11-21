# Accessible states matrix

All labels below are proposed semantics for implementation review. “Shown” means inspectable in the linked synthetic SVG, never runtime-verified. Touch actions target at least 48 dp; swipe arrows supplement, rather than replace, named controls.

| Group / state | Visual evidence | Proposed TalkBack announcement / role / state | 200% and input check | Evidence status |
|---|---|---|---|---|
| V11 canvas / chooser / selected / error | `v11-ai-edit.svg` | “Provider, button, Local selected”; “Result 1, selected”; error announced as status, retry button | Wrapping specimen shown; keyboard-open absent | Concept shown; exact provider error frame absent |
| V12 chooser / studio / compare | `v12-swap-person.svg` | “Mina, button, selected”; ordered sphere conveys selection in text and shape | Wrapping shown; controls remain in lower reach zone | Three panels shown; reference/face sheets absent |
| V13 visual / face / no matches | `v13-search-results.svg` | “Match confidence, 92 percent”; photo button; “No matches. Clear search.” | Confidence is text, not colour-only; keyboard absent | Named results shown; loading/error absent |
| V14 single / multi / handoff | `v14-android-entry.svg` | “Photo 1, selected, 1 of 2”; “Add 2 photos, button”; share/edit are buttons | Count remains visible at 200%; scoped selection labels | Atlas entry concept only; every OS-owned frame absent |
| V15 empty / loading / no matches | `v15-shared-states.svg` | Empty sentence plus next-step button; loading count as polite status; clear-search button | Plain count wraps; no focus-stealing spinner | Three shared states shown; permission/error absent |
| V16 long / error / grant | `v16-album-detail.svg` | Album title and count heading; photo buttons; “Allow photo access, button” | Long title/count wrapping; primary action reachable | Panels shown; partial grant/rotation absent |
| V17 progress / error / retry | `v17-model-install.svg` | “Downloading, 68 percent”, progress status; “Retry, button”; cancel button | Android may control scaling/layout | Only an intent study; actual templates absent |

## Cross-cutting acceptance notes

- Focus order should follow title → content/reference → choices → primary action; this remains unverified until runtime evidence exists.
- Selected items require both the filled lapis sphere with 2 dp white halo and a spoken selected state. Ordered multi-select also speaks the pick number.
- Loading speaks truthful known progress only. Indeterminate work must not invent a percentage or count.
- Lift is 200 ms only after an intentional selection; remove-animations must cut immediately. Static SVGs cannot prove either behavior.
- Error, empty, and no-match states use one useful next step without decorative or blocking chrome.
