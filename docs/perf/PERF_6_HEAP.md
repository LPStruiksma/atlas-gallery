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


## People projection (PERF-6b, #601)

The gallery's `MediaRecord` list remains the authoritative permission-scoped
snapshot. People now receives a `MediaRecordFaceView` whose face rows and URI
strings are created only while consumed, not held for the lifetime of the face
scope. `newestFirst` reuses the view if already sorted; otherwise a stable
primitive `IntArray` of source offsets replaces the sorted copy of face objects.
The face directory uses the same view plus its hashed `LongArray` index.
Other callers still get a frozen admitted-row snapshot so mutating an ordinary
input list cannot change an authorized source after publication.

`peopleProjectionAt250kRetainsOnlyIndicesAndPreservesDigest` reports JVM
retained-heap deltas for the view and directory, compares `faceInventoryDigest`
with the pre-change materialized projection, checks newest-first reuse and
full-key/revision lookup, and asserts **at most 16 bytes per gallery row of
structural retained People indices** (8 B per admitted row plus up to 4 B
for sorting, or up to 4 B for source offsets above the 20-bit direct limit).
GC-dependent heap deltas are printed, not asserted. These are JVM measurements;
the separate PERF-0 emulator `heap_fraction` from exact-head CI is the
device-level peak-heap acceptance evidence.
