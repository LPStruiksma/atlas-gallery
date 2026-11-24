# PERF-HOOKS-1b real-action proof plan

Issue: https://github.com/LPStruiksma/atlas-gallery/issues/1019

Acceptance: instrumented tests drive the real viewer open, preview-ready (including cached preview and shared-photo transition), and return paths, then assert that PerformanceSession aggregates for A3, A4 and A7 gain samples. Direct `recordActionMs` calls alone are insufficient.

Only fix hooks demonstrated broken by the tests. Report action, hook, test and fix/already-working status in the PR. Verify exact-head JVM CI; independent Review runs instrumented tests via `emulator:run`. No physical-device proof is claimed.

| Action | Production hook exercised by the real action | Instrumented assertion | Fix/status |
| --- | --- | --- | --- |
| A3 viewer open to preview-ready | `openViewer` arms `PerformanceSession.viewerOpened`; the focused `GalleryViewer` page reports Coil preview success through `ViewerTimingProbe`, including a memory-cached preview while the shared-photo entry layer is active | `SharedPhotoJourneyTest.photoTravelsFromMeasuredTileAndReturnsToSameCell` taps an authorized MainActivity grid tile, completes the shared-photo entry/return, and asserts the `action_a3_ms` aggregate count increases | Already working; no production hook change |
| A4 viewer selection to preview-ready | A real filmstrip click calls `selectionRequested`, pager settlement calls `pageEntered`, and the newly focused page reports preview-ready | The same test reopens the real viewer, clicks another thumbnail, waits for its counter to settle, returns, and asserts the `action_a4_ms` aggregate count increases | Already working; no production hook change |
| A7 viewer return | `closeViewer` starts the return clock and the post-unmount frame records A7; the shared-photo exit reaches `closeViewer` through `onExitFinished` | The first shared-photo Back/exit/return cycle asserts the `action_a7_ms` aggregate count increases after the real viewer disappears and its originating tile is restored | Already working; no production hook change |
