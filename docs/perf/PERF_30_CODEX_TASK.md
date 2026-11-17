# PERF-30 Codex task scaffold

Unit: [#331](https://github.com/LPStruiksma/atlas-gallery/issues/331). This is a request for a patch, not implementation evidence.

- Preserve album catalogue behavior across Albums tab opens and tab switches. Cache by MediaStore generation or a valid inventory version and incrementally recompute only changed albums.
- Add synthetic JVM coverage at 250,000 media items in 400 albums: baseline versus optimized open timings, count full/album rebuild operations; unchanged second open should require approximately zero rebuilds, and a change to one album should recompute only that album.
- Keep AlbumLoadingAdmissionTest and AsyncAlbumCatalogLogicTest green. Run ./gradlew :app:testDebugUnitTest on exact head. No device/media data, dependencies, manifest or design changes. Do not run an emulator until independent Review.
- Codex's response must contain a single parent-bound git format-patch; no push, claim, labels or merge from the bot. ChatGPT Dev validates and applies, then independently reviewed exact-head CI governs any release.
