# A10/A11 navigation proof — issue #1020

This matrix tracks real user actions and must be completed with code and emulator-backed tests before acceptance. No row is yet verified.

| Action | Timer start / completion hook | Real Compose test | Fix / outcome |
| --- | --- | --- | --- |
| Person from viewerPhotoPeople | Inspect personEntryStartNs / A10 | Pending | Pending |
| Person from native person album | Inspect personEntryStartNs / A10 | Pending | Pending |
| Person from FaceSearchRoute onOpenAlbum | Inspect personEntryStartNs / A10 | Pending | Pending |
| Other person entry paths | Enumerate and inspect / A10 | Pending | Pending |
| Search from bottom tab | Inspect searchTabStartNs / A11 | Pending | Pending |
| Search from FindSimilarRoute | Inspect searchTabStartNs / A11 | Pending | Pending |
| Search from FaceSearchRoute onSearchWholeLibrary | Inspect searchTabStartNs / A11 | Pending | Pending |
| Other Search entry paths | Enumerate and inspect / A11 | Pending | Pending |

Acceptance requires real Compose navigation tests asserting that PerformanceSession aggregate counts increase; direct recordActionMs-only tests are insufficient. Record exact-head JVM CI and emulator:run evidence. Do not claim physical-device proof.
