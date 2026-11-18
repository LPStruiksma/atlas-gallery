# PERF-18: previous-inventory lookup in incremental MediaStore scans (#225, inventory A8)

**Change.** `GalleryScanRecordReuse` built its id-sorted lookup with `previous.sortedBy { it.id }.toTypedArray()`. `sortedBy` compares through `compareBy`, which boxes both `Long` ids on every comparison (about 2 × n log n `Long` objects for a library in MediaStore date order), and it copies the list twice. It now copies once with `toTypedArray()`, checks in O(n) whether that copy is already in id order, and otherwise sorts it in place with `Arrays.sort` and a primitive `Long.compareTo` comparator. Both sorts are stable TimSorts, so equal ids across volumes keep the same relative order. The binary search, URI disambiguation and full-equality reuse are untouched. `GalleryRepository.kt` and `GalleryPublishedInventory.kt` are not edited.

**Equivalence** (`GalleryScanRecordReusePerfTest`): against a frozen copy of the old lookup, every fresh row returns the very same object (`assertSame`) and the same reuse count for: a shuffled previous inventory with duplicate ids on a second volume, the same in ascending and descending id order, an empty previous list and a one-row list. Fresh rows include changed revisions, new ids and an id seen before on an unseen volume. Existing `GalleryScanMemoryTest` and `MediaRecordUriMemoryTest` pass unchanged. OOM handling (`failGalleryScanOnHeapExhaustion`) and cancellation are not touched.

**Numbers.** 250k synthetic rows, lookup build plus one `canonical` per fresh row, container JVM (HotSpot), same test and machine for both arms, n = 7 after warm-up:

| previous inventory | arm | p50 | p95 | allocated (median) |
|---|---|---|---|---|
| date order (shuffled ids) | before | 180 ms | 186 ms | 104.8 MB |
| date order (shuffled ids) | after | 149 ms | 157 ms | 4.0 MB |
| already in id order | before | 30 ms | 35 ms | 10.0 MB |
| already in id order | after | 24 ms | 24 ms | 3.0 MB |

The 4 MB left is the one record array plus the `LongArray` of ids (and TimSort's scratch when sorting).

**Limits.** JVM numbers; ART boxes through its own `Long` cache and allocator, so the on-device gain is not claimed here. The win is temporary garbage during a rescan, which is when the #41 OOMs happened.
