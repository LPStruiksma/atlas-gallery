# PERF-6: retained heap at 250k items (#127, goal #119)

**Target:** peak heap < 60% of `maxMemory` with a synthetic 250k-item library, without largeHeap and without dropping features.

## Holder study (JVM, 250k synthetic MediaStore-shaped rows)

`GalleryInventoryMemoryTest.retainedHeapPerHolderAt250k` measures each holder as retained bytes per item, published rather than asserted.

| Holder | Before | After |
|---|---|---|
| `MediaRecord` list (shared URI prefix, folder, volume and version; own file name) | 172 B | 172 B |
| Timeline index, per cached density plan (up to 2 kept) | **141 B** | **45 B** |
| Sorted copy | 4 B | 4 B |
| Album summaries | 0.5 B | 0.5 B |

The timeline index was the largest derived holder. Its builder took a `TimelineMedia(uri, takenAt)` projection, and every media slot kept that URI string. Since #63, `MediaRecord.uri` builds a new string on each read, so the index rebuilt and retained about 100 bytes of URI per photo. That is 24 MB per plan at 250k, and up to 48 MB with two density plans cached, which undid #63's saving. Building the projection also allocated 250k `TimelineMedia` objects and 250k strings of garbage on every replan.

## Change (holder 1)

- `buildGridTimelineIndex(count, takenAtMillis, keyAt, …)` builds the same index straight from the catalogue. It makes no projection, and each media slot resolves its key through `keyAt` only when asked. The only reader is a viewer return, once.
- `AtlasTimelineGallery` uses it. The incremental-focus anchor is found by an allocation-free hash comparison before an exact URI check.
- The list overload is unchanged (it is used by tests and by the transient viewer-return path in MainActivity).
- `catalogueIndexEqualsTheProjectedIndex` checks that slots, headers, positions and keys are equal to the list overload's, for every density, with and without sparse-day merging, for chronological, reversed, shuffled and random orders.

## Next holders

`MediaRecord` itself (172 B/item, of which the file name string is about 60 B) is the largest remaining holder. It is a candidate for a separate PR, for example storing file names in one shared char buffer. The emulator peak-heap number against the 60% target needs a 250k emulator scenario, which does not exist yet.
