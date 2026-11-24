# A10/A11 navigation proof — issue #1020

This matrix enumerates the production callbacks that share the anonymous monotonic
`NavigationActionTimers` origins and the frame-settled
`NavigationActionSampleEffect` completion hook. Instrumentation performs Compose
clicks and compares aggregate sample counts before and after each action; it does
not call `recordActionMs` directly.

| Action | Timer start / completion hook | Real Compose test | Fix / outcome |
| --- | --- | --- | --- |
| Person from `viewerPhotoPeople` | `startPerson` before viewer close; sample after native album is visible | `viewer-person` Compose click; A10 count +1 | Fixed missing start and native-album completion |
| Person from native People home | `startPerson` in `onOpenNativePersonAlbum`; sample after native album is visible | `native-person` Compose click; A10 count +1 | Fixed missing start and native-album completion |
| Person from `FaceSearchRoute.onOpenAlbum` | `startPerson` before closing Face Search; sample after native album is visible | `face-person` Compose click; A10 count +1 | Fixed missed start |
| Other person entry paths | Existing V4 profile/search shortcuts plus native hidden, split, retarget paths call `startPerson` | Same action host exercises repeated A10 transitions | Retained existing V4 coverage; fixed every native entry callback |
| Search from bottom tab | `startSearch` in primary navigation callback; sample after Search is visible | `bottom-search` Compose click; A11 count +1 | Shared completion hook replaces bottom-only timer handling |
| Search from `FindSimilarRoute` | `startSearch` before closing route; sample after Search is visible | `similar-search` Compose click; A11 count +1 | Fixed missed start |
| Search from `FaceSearchRoute.onSearchWholeLibrary` | `startSearch` before closing route; sample after Search is visible | `face-search` Compose click; A11 count +1 | Fixed missed start |
| Other Search entry paths | Album, hidden-album, person-album, hidden-person, People home, header scope and Declutter callbacks call `startSearch` | Same action host exercises repeated A11 transitions | Fixed all direct `tab = 3` navigation callbacks |

Execution evidence is recorded in the PR response; no emulator or physical-device
proof is claimed unless that command actually ran.
