# PERF-HOOKS-1b real viewer sample proof

Issue: https://github.com/LPStruiksma/atlas-gallery/issues/1019

The applied staging patch only checked A3/A4 after returning; the host forwarded completed viewer samples on open/close, not when the preview completed. The revised real MainActivity journey checks them before Back and requires a separate cached reopen. No direct recordActionMs calls are used.

| Action | Real production hook | Instrumented assertion | Fix/status |
| --- | --- | --- | --- |
| A3 viewer open | openViewer arms viewerOpened; focused Coil preview and page entry complete the timing probe | SharedPhotoJourneyTest asserts a new A3 while shared entry viewer is mounted, then another A3 on reopen before selection; focused preview semantics require actual Coil MEMORY_CACHE success | Forward the probe at focused preview and page entry, not only on Back |
| A4 viewer selection | Filmstrip selectionRequested, pageEntered, focused preview success | Same journey takes a fresh A4 baseline after cached reopen and requires a new sample after target counter settles, before Back | Same immediate forwarding |
| A7 viewer return | closeViewer and post-unmount frame | Same journey requires new A7 after shared exit and original tile return | Existing hook unchanged |

Exact-head JVM CI and independent Review's emulator:run are required. No local emulator or physical-device proof claimed.
