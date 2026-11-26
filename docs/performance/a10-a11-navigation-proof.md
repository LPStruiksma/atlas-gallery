# A10/A11 navigation proof — issue #1020

The production callbacks share anonymous monotonic `NavigationActionTimers` origins
and the frame-settled `NavigationActionSampleEffect` completion hook. The isolated
`NavigationActionInstrumentationTest` exercises this hook using synthetic buttons;
it does **not** prove any actual route starts its timer. The existing
`PeoplePrototypeJourneyTest` now asserts sample-count deltas on four real MainActivity
navigation actions; a focused second journey exercises Find Similar and both Face Search
exits using the same disposable MediaStore fixture and synthetic visual vectors. The real split/Hidden journey and a synthetic reviewed V4 roster journey extend A10 coverage. No test calls `recordActionMs` directly.

| Action | Timer start / completion hook | Actual app-route test | Fix / outcome |
| --- | --- | --- | --- |
| Person from `viewerPhotoPeople` | `startPerson` before viewer close | PeoplePrototypeJourneyTest: A10 +1 | Assertion added; execution pending |
| Person from native People home | `startPerson` in `onOpenNativePersonAlbum` | PeoplePrototypeJourneyTest: A10 +1 | Assertion added; execution pending |
| Person from `FaceSearchRoute.onOpenAlbum` | `startPerson` before closing Face Search | PeoplePrototypeJourneyTest.findSimilarAndFaceSearchRecordNavigationSamples: A10 +1 | Assertion added; execution pending |
| Named Needs splitting row (visible and hidden lists) | `startPerson` before named album request | Hub1CNeedsSplittingJourneyTest.needsSplittingRowsOpenGroupsAndBackReturnsToTheFilteredList: A10 +1 each | Assertion added; execution pending |
| Unnamed Needs splitting row | `startPerson` before unnamed album request | Hub1CNeedsSplittingJourneyTest.needsSplittingRowsOpenGroupsAndBackReturnsToTheFilteredList: A10 +1 | Assertion added; execution pending |
| Hidden People direct person row | `startPerson` in HiddenPeopleRoute onOpen | Hub1CNeedsSplittingJourneyTest.needsSplittingRowsOpenGroupsAndBackReturnsToTheFilteredList: A10 +1 | Assertion added; execution pending |
| Native person retarget | `startPerson` in native album onRetarget | Hub1CNeedsSplittingJourneyTest.needsSplittingRowsOpenGroupsAndBackReturnsToTheFilteredList: rename and A10 +1 | Assertion added; execution pending |
| Imported V4 People profile | `startPerson` in PeopleHomeScreen onOpenProfile | PeopleSearchAppEvidenceTest.reviewedProfileAndSearchShortcutRecordA10: A10 +1 | Assertion added; execution pending |
| Imported V4 Search shortcut | `startPerson` in PeopleSearchShortcuts onSelect | PeopleSearchAppEvidenceTest.reviewedProfileAndSearchShortcutRecordA10: A10 +1 | Assertion added; execution pending |
| Search from bottom tab | `startSearch` in primary navigation callback | PeoplePrototypeJourneyTest: A11 +1 | Assertion added; execution pending |
| Search from `FindSimilarRoute` | `startSearch` before closing route | PeoplePrototypeJourneyTest.findSimilarAndFaceSearchRecordNavigationSamples: A11 +1 | Terminal Results (including empty results), not index-off; execution pending |
| Search from `FaceSearchRoute.onSearchWholeLibrary` | `startSearch` before closing route | PeoplePrototypeJourneyTest.findSimilarAndFaceSearchRecordNavigationSamples: A11 +1 | Assertion added; execution pending |
| Native person album Whole library | `startSearch` in native album callback | PeoplePrototypeJourneyTest: A11 +1 | Assertion added; execution pending |
| Declutter Whole library | `startSearch` in Declutter callback | Pending | Unverified |
| Shared folder/person header Whole library | `startSearch` in header scope callback | Pending | Unverified |
| Hidden Albums Whole library | `startSearch` in hidden albums callback | Pending | Unverified |
| Albums home Whole library | `startSearch` in Albums callback | Pending | Unverified |
| Hidden People Whole library | `startSearch` in hidden people callback | Pending | Unverified |
| People home Whole library | `startSearch` in People home callback | Pending | Unverified |

Acceptance requires actual app-route assertions for all rows plus exact-head JVM and
emulator evidence. Synthetic-hook coverage alone is not acceptance. No physical-device
proof is claimed.
