import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

// #41: the startup rescan ran out of Java heap in GalleryRepository.readMedia
// (OutOfMemoryError in the phone's Last crash report). The scan must share
// unchanged rows with the inventory on screen, fail the refresh (not the app)
// when the heap is exhausted, and the crash report must carry heap figures and
// Android's process-exit history without any free text.
const read = (path) => readFileSync(path, 'utf8');
const repo = read('app/src/main/java/nl/atlas/gallery/GalleryRepository.kt');
const reuse = read('app/src/main/java/nl/atlas/gallery/GalleryScanRecordReuse.kt');
const evidence = read('app/src/main/java/nl/atlas/gallery/GalleryScanFailureEvidence.kt');
const report = read('app/src/main/java/nl/atlas/gallery/LastCrashRecorder.kt');

assert.match(repo, /failGalleryScanOnHeapExhaustion \{/);
assert.match(repo, /target\.add\(reuse\.canonical\(MediaRecord\(/);
assert.match(repo, /reuse\.shared\(cursor\.stringOr\(idxPath, ""\)\)/);
assert.match(repo, /repo\.load\(providerCancellation, shownAtStart\)/);
// Cold start: the scan picks its previous rows only after the cache load settled.
assert.match(repo, /cachedInventoryLoad = viewModelScope\.launch \{/);
assert.match(repo, /val shownAtStart = inventoryShownAfterCacheSettles\(cachedInventoryLoad\) \{\s*_publishedInventory\.value\.items/);
assert.match(reuse, /cacheLoad\?\.join\(\)\s*return shown\(\)/);
// Reuse only on full equality; never by URI or ID alone.
assert.match(reuse, /if \(before == fresh\)/);
assert.match(reuse, /catch \(_: OutOfMemoryError\) \{[\s\S]*throw GalleryScanOutOfMemory\(\)/);
assert.match(evidence, /cause is GalleryScanOutOfMemory -> GalleryScanFailureCode\.OUT_OF_MEMORY/);
assert.match(report, /getHistoricalProcessExitReasons/);
assert.match(report, /result\.append\("Heap: "\)/);
// Android's free-text exit description must never enter the report.
assert.doesNotMatch(report, /\.description/);

// The inventory cache ran out of heap three times on the phone (decode twice,
// encode once): reading and writing it must absorb that, not end the app.
const cache = read('app/src/main/java/nl/atlas/gallery/GalleryInventoryCache.kt');
assert.match(cache, /catch \(_: OutOfMemoryError\) \{[\s\S]*?event=cache_read_out_of_memory"\)\s*null/);
assert.match(cache, /catch \(_: OutOfMemoryError\) \{[\s\S]*?tmp\.delete\(\)[\s\S]*?event=cache_write_out_of_memory/);

console.log('scan-memory-41 source contracts passed');
