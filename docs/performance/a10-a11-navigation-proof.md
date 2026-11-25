# A10/A11 navigation proof — issue #1020

The production callbacks share anonymous monotonic `NavigationActionTimers` origins
and the frame-settled `NavigationActionSampleEffect` completion hook. The isolated
`NavigationActionInstrumentationTest` exercises this hook using synthetic buttons;
it does **not** prove any actual route starts its timer. The existing
`PeoplePrototypeJourneyTest` now asserts sample-count deltas on two real MainActivity
navigation actions. No test calls `recordActionMs` directly.

| Action | Timer start / completion hook | Actual app-route test | Fix / outcome |
| --- | --- | --- | --- |
| Person from `viewerPhotoPeople` | `startPerson` before viewer close | Pending | Start wired; unverified |
| Person from native People home | `startPerson` in `onOpenNativePersonAlbum` | PeoplePrototypeJourneyTest: A10 +1 | Assertion added; execution pending |
| Person from `FaceSearchRoute.onOpenAlbum` | `startPerson` before closing Face Search | Pending | Start wired; unverified |
| Other person entry paths | V4 profile/search shortcuts, native hidden, split and retarget paths | Pending | Starts wired; unverified |
| Search from bottom tab | `startSearch` in primary navigation callback | PeoplePrototypeJourneyTest: A11 +1 | Assertion added; execution pending |
| Search from `FindSimilarRoute` | `startSearch` before closing route | Pending | Start wired; unverified |
| Search from `FaceSearchRoute.onSearchWholeLibrary` | `startSearch` before closing route | Pending | Start wired; unverified |
| Other Search entry paths | Album, hidden-album, person-album, hidden-person, People home, header scope and Declutter callbacks | Pending | Starts wired; unverified |

Acceptance requires actual app-route assertions for all rows plus exact-head JVM and
emulator evidence. Synthetic-hook coverage alone is not acceptance. No physical-device
proof is claimed.
