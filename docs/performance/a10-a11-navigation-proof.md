# A10/A11 navigation proof — issue #1020

The production callbacks share anonymous monotonic `NavigationActionTimers` origins
and the frame-settled `NavigationActionSampleEffect` completion hook. The isolated
`NavigationActionInstrumentationTest` exercises this hook using synthetic buttons;
it does **not** prove any actual route starts its timer. The existing
`PeoplePrototypeJourneyTest` now asserts sample-count deltas on four real MainActivity
navigation actions; a focused second journey exercises Find Similar and both Face Search
exits using the same disposable MediaStore fixture and synthetic visual vectors. No test calls `recordActionMs` directly.

| Action | Timer start / completion hook | Actual app-route test | Fix / outcome |
| --- | --- | --- | --- |
| Person from `viewerPhotoPeople` | `startPerson` before viewer close | PeoplePrototypeJourneyTest: A10 +1 | Assertion added; execution pending |
| Person from native People home | `startPerson` in `onOpenNativePersonAlbum` | PeoplePrototypeJourneyTest: A10 +1 | Assertion added; execution pending |
| Person from `FaceSearchRoute.onOpenAlbum` | `startPerson` before closing Face Search | PeoplePrototypeJourneyTest.findSimilarAndFaceSearchRecordNavigationSamples: A10 +1 | Assertion added; execution pending |
| Other person entry paths | V4 profile/search shortcuts, native hidden, split and retarget paths | Pending | Starts wired; unverified |
| Search from bottom tab | `startSearch` in primary navigation callback | PeoplePrototypeJourneyTest: A11 +1 | Assertion added; execution pending |
| Search from `FindSimilarRoute` | `startSearch` before closing route | PeoplePrototypeJourneyTest.findSimilarAndFaceSearchRecordNavigationSamples: A11 +1 | Terminal Results (including empty results), not index-off; execution pending |
| Search from `FaceSearchRoute.onSearchWholeLibrary` | `startSearch` before closing route | PeoplePrototypeJourneyTest.findSimilarAndFaceSearchRecordNavigationSamples: A11 +1 | Assertion added; execution pending |
| Other Search entry paths | Album, hidden-album, person-album, hidden-person, People home, header scope and Declutter callbacks | PeoplePrototypeJourneyTest: native person Whole library A11 +1; other paths pending | Partial assertions; execution pending |

Acceptance requires actual app-route assertions for all rows plus exact-head JVM and
emulator evidence. Synthetic-hook coverage alone is not acceptance. No physical-device
proof is claimed.
