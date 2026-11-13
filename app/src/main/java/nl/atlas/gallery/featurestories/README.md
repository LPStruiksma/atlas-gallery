# ATLAS Stories / Memories · Lane S (3 November 2026)

**State:** standalone read-only implementation, **not integrated or installed**. Native code is original. The Samsung Gallery 15.9.00.45 static parity research inventories ~96 classes in module.story, plus create/rename/pin, recap/slideshow, hide rules and video/BGM commands. The parity research did not behavior-test these features. The superseding Samsung-parity product program explicitly brings Stories into scope.

## Implemented

- Offline On this day (3+ accessible moments from previous years), previous calendar month recap (4+), recent favorites (4+), with deterministic bounded per-day selection; favorites are preferred as covers when present.
- Version-and-grant-scoped read-only story deck: a revoked or replaced catalog never authorizes stale UI. Inputs are an authorized host-created projection; no independent MediaStore scan.
- Optional per-date and per-URI exclusions and disable-auto-suggestions policy; user-created manual collections remain accessible by explicit choice, but any hidden/trashed or no-longer-authorized item is excluded.
- Manual story definitions resolve user order against the current catalog, and report missing items. Definitions are supplied, **not persisted** in this slice.
- Original Compose Stories card grid, empty and partial-availability states, and full-screen photo slideshow with Back, pause, previous/next, and open-in-gallery. Video entries open the existing gallery player explicitly; there is no fake video playback or music.
- Synthetic JUnit-style test cases include 150,000 fictional media, date and leap-year edges, grant revocation, hidden/trashed exclusions, deterministic output, manual ordering, cancellation and suggestion opt-outs.

## Lane I/D/X integration contract

Lane I maps the existing immutable, authorized MediaRecord inventory to StoryMedia (content URI, effective capture instant, video, verified favorite and explicit privacy exclusions) off main thread. Pass a nonempty grant epoch and a catalogue revision that **both change whenever their relevant state changes**; treat planStories as cancellable work, never recompute it on every Compose recomposition. Pass the current grant and revision separately to AtlasStoriesScreen; do not simply reuse the deck's tokens as live state. Revalidate authorization before onOpenMedia (viewer route). No new permissions, network, file writes, MediaStore mutations or V4 face reads are needed.

Lane D/I decide navigation/tab placement, album selection handoff and final visual acceptance. Lane X owns persistent manual stories and preference migrations; Lane U/P own any reviewed-person input for later people-themed recaps. This code does not edit other lanes' files.

## Remaining gaps / evidence limits

No end-to-end installed navigation, user-facing create/rename/reorder, cover chooser, permanent hide rules, story-video export, collage, BGM licensing, cloud, Samsung proprietary hooks, S24+ screenshots, Android Compose build or emulator UI test yet. After host integration, compare navigation, animations, covers, swipe/Back, grid density and performance against Samsung Gallery using **synthetic media**. Samsung parity research descriptions are a behavioral research aid, not source to copy.
