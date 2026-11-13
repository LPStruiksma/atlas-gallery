#!/usr/bin/env bash
# No Android SDK, Gradle, network, GitHub Actions or private user files.
# Compile the *real* production ArchiveLogic + featuremedia Kotlin sources.
set -euo pipefail
command -v kotlinc >/dev/null || { echo 'kotlinc required locally' >&2; exit 2; }
command -v java >/dev/null || { echo 'java required locally' >&2; exit 2; }
dir="$(cd "$(dirname "$0")" && pwd)"
root="$dir"
while [[ ! -f "$root/app/src/main/java/nl/atlas/gallery/ArchiveLogic.kt" ]]; do
    [[ "$root" != "/" ]] || { echo 'not inside an ATLAS checkout' >&2; exit 2; }
    root="$(dirname "$root")"
done
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
# The real PersonEntry resides in Android SQLite source. Reproduce just its
# immutable public contract to compile the Android-free ArchiveLogic file.
cat > "$tmp/PersonEntryStub.kt" <<'KOTLIN'
package nl.atlas.gallery
data class PersonEntry(val id: Long, val name: String, val imagePaths: Set<String>) {
    val count: Int get() = imagePaths.size
}
KOTLIN
cat > "$tmp/RunMediaContracts.kt" <<'KOTLIN'
import nl.atlas.gallery.*
import java.time.ZoneId

fun main() {
    var checks = 0
    fun verify(value: Boolean, name: String) {
        check(value) { "FAILED: $name" }
        checks++
    }
    val zone = ZoneId.of("Europe/Amsterdam")
    for (name in listOf("PXL_20240229_235959431.jpg",
                        "IMG_20240229_235959.jpg", "Screenshot_20240130-152433.png")) {
        verify(resolveMediaDate(0L, name, 100L, 200L, zone).source ==
            MediaDateSource.FILENAME_TIMESTAMP, "date: $name")
    }
    for (name in listOf("IMG_20230229_235959.jpg", "IMG_20240302_246199.jpg",
                        "IMG_20230326_023000.jpg", "PXL_20240101_1200001234.jpg")) {
        verify(resolveMediaDate(0L, name, 100L, 200L, zone).source ==
            MediaDateSource.MODIFIED, "invalid: $name")
    }
    verify(resolveMediaDate(0L, "IMG-20230109-WA0012.jpg", 100L, 200L, zone).source ==
        MediaDateSource.FILENAME_DATE, "WhatsApp")
    // Stock Samsung Camera basenames lack the IMG_/VID_ prefix. These
    // metadata-only regressions matter for copied photos with no DATE_TAKEN.
    for (name in listOf("20261101_093007.jpg", "20261101_093007.heic",
            "20261101_093007.mp4", "20261101_093007123.jpg")) {
        verify(resolveMediaDate(0L, name, 100L, 200L, zone).source ==
            MediaDateSource.FILENAME_TIMESTAMP, "Samsung camera date: $name")
    }
    for (name in listOf("20260229_120000.jpg", "20260329_023000.jpg",
            "20261101_253000.mp4", "20261101_0930071234.jpg",
            "holiday20261101_093007.jpg")) {
        verify(resolveMediaDate(0L, name, 1_700_000_000L, 200L, zone).source ==
            MediaDateSource.MODIFIED, "invalid Samsung camera date: $name")
    }
    verify(resolveMediaDate(1_700_000_000_000L, "20261101_093007.jpg",
        100L, 200L, zone).source == MediaDateSource.TAKEN,
        "explicit provider capture date must win")
    for (name in listOf("Screen_Recording_20261101_093007_Chrome.mp4",
            "screen_recording_20261101_093007.mp4")) {
        verify(resolveMediaDate(0L, name, 100L, 200L, zone).source ==
            MediaDateSource.FILENAME_TIMESTAMP, "Samsung recording date: $name")
    }
    for (name in listOf("Screen_Recording_20260229_120000_Chrome.mp4",
            "prefix_Screen_Recording_20261101_093007.mp4")) {
        verify(resolveMediaDate(0L, name, 1_700_000_000L, 200L, zone).source ==
            MediaDateSource.MODIFIED, "invalid Samsung recording date: $name")
    }
    verify(resolveMediaDate(Long.MAX_VALUE, "IMG_20240229_235959.jpg",
        Long.MAX_VALUE, Long.MAX_VALUE, zone).source ==
        MediaDateSource.FILENAME_TIMESTAMP, "implausible taken uses filename")
    verify(resolveMediaDate(Long.MAX_VALUE, "invalid.jpg",
        Long.MAX_VALUE, 1_700_000_000L, zone).source ==
        MediaDateSource.ADDED, "implausible modification uses valid added")
    verify(resolveMediaDate(Long.MAX_VALUE, "invalid.jpg",
        Long.MAX_VALUE, Long.MAX_VALUE, zone).source ==
        MediaDateSource.UNKNOWN, "all invalid dates must stay unknown")
    for (providerId in listOf(1L, 42L, Int.MAX_VALUE.toLong() + 1, Long.MAX_VALUE)) {
        verify(requireMediaStoreItemId(providerId) == providerId,
            "64-bit valid MediaStore ID preserved")
    }
    for (badId in listOf(0L, -1L, Long.MIN_VALUE)) {
        verify(runCatching { requireMediaStoreItemId(badId) }
            .exceptionOrNull() is IllegalStateException,
            "nonpositive MediaStore ID aborts scan")
    }
    val base = MediaRecord(42L, "content://media/external_primary/images/media/42",
        "duplicate.jpg", "DCIM/FICTION/", 1200, 500, false,
        modifiedAtSeconds = 100, volumeName = "external_primary",
        providerVersion = "ver1", generationModified = 77,
        widthPixels = 4000, heightPixels = 3000, orientationDegrees = 90)
    val sd = base.copy(
        uri = "content://media/1234-ABCD/images/media/42",
        volumeName = "1234-ABCD")
    val unknown = base.copy(volumeName = "unknown")
    verify(base.absolutePath != sd.absolutePath, "path storage separation")
    verify(base.absolutePath != unknown.absolutePath, "missing-volume separation")
    verify(base.mediaIdentity() != sd.mediaIdentity(), "identity")
    verify(selectPersonPhotos(listOf(base, sd, unknown), setOf(base.absolutePath)) ==
        listOf(base), "no false People assignment from same id/name")
    val collidingVideo = base.copy(
        uri = "content://media/external_primary/video/media/42", isVideo = true)
    verify(selectPersonPhotos(listOf(collidingVideo, base), setOf(base.absolutePath)) ==
        listOf(base), "reviewed photo path must reject video")
    verify(filterMedia(listOf(collidingVideo, base), "Reviewed person",
        setOf(base.absolutePath)) == listOf(base),
        "People-name search cannot attribute video with same path")
    verify(filterMedia(listOf(collidingVideo, base), "duplicate.jpg") ==
        listOf(collidingVideo, base), "ordinary filename search still finds videos")
    verify(base.displayAspectRatioOrNull() == 0.75f, "rotated aspect")
    verify(base.copy(widthPixels = 0).displayAspectRatioOrNull() == null, "unknown ratio")
    verify(mediaPixelDimensionOrUnknown(4000L) == 4000,
        "valid width is unchanged")
    for (raw in listOf(0L, -1L, 1L shl 32, Long.MAX_VALUE)) {
        val dimension = mediaPixelDimensionOrUnknown(raw)
        verify(dimension == 0, "invalid 64-bit width becomes unknown")
        verify(base.copy(widthPixels = dimension).displayAspectRatioOrNull() == null,
            "invalid width never fabricates giant panorama")
    }
    for (reported in listOf(0L, 90L, 180L, 270L)) {
        val unknown = mediaOrientationDegreesOrUnknown(reported, false)
        // #504: NULL ORIENTATION (PNGs, screenshots, EXIF-less JPEGs) = unrotated.
        verify(unknown == Int.MIN_VALUE &&
            base.copy(orientationDegrees = unknown).displayAspectRatioOrNull() == 4f / 3f,
            "missing provider orientation reads as unrotated stored pixels")
    }
    verify(mediaOrientationDegreesOrUnknown(0L, true) == 0,
        "explicit zero-degree orientation is valid")
    verify(base.copy(orientationDegrees = 45).displayAspectRatioOrNull() == null,
        "malformed orientation rejects geometry")
    verify(base.copy(orientationDegrees = -90).displayAspectRatioOrNull() == 0.75f,
        "normalized quarter turn")
    verify(mediaOrientationDegreesOrInvalid(90L) == 90,
        "legitimate MediaStore quarter turn")
    for (raw in listOf(1L shl 32, (1L shl 32) + 90L,
            Long.MIN_VALUE, Long.MAX_VALUE)) {
        val orientation = mediaOrientationDegreesOrInvalid(raw)
        verify(orientation == Int.MIN_VALUE, "64-bit provider rotation cannot wrap")
        verify(base.copy(orientationDegrees = orientation).displayAspectRatioOrNull() == 4f / 3f,
            "invalid provider orientation never fabricates a quarter turn")
    }
    val key = base.presentationCacheKeyOrNull(400, 9)!!
    verify(key.startsWith("atlas-media-v2|"), "geometry-aware cache namespace")
    verify(key != base.copy(generationModified = 78).presentationCacheKeyOrNull(400, 9),
        "same-second edit revision")
    verify(key != base.copy(widthPixels = 4001).presentationCacheKeyOrNull(400, 9),
        "same-generation pixel width change invalidates decoded cache")
    verify(key != base.copy(heightPixels = 3001).presentationCacheKeyOrNull(400, 9),
        "same-generation pixel height change invalidates decoded cache")
    verify(key != base.copy(orientationDegrees = 180).presentationCacheKeyOrNull(400, 9),
        "same-generation orientation change invalidates decoded cache")
    verify(key != base.presentationCacheKeyOrNull(400, 10), "access epoch")
    verify(base.copy(providerVersion = null).presentationCacheKeyOrNull(400, 9) == null,
        "unknown provider version fails closed")
    verify(canReuseMediaGeneration("ver1", "ver1", 77, 77, true),
        "identical generation may be reused after grant checks")
    verify(!canReuseMediaGeneration("ver1", "ver1", 77, 78, true),
        "changed generation cannot reuse decoded thumbnail")
    verify(!canReuseMediaGeneration("ver1", "ver2", 77, 77, true), "reset")
    verify(!canReuseMediaGeneration("ver1", "ver1", 77, 77, false), "grant changed")
    val ticket = ThumbnailBindingTicket(1, base.mediaIdentity(), 9, 1)
    verify(mayPublishThumbnailResult(ticket, ticket, true), "current cell")
    verify(!mayPublishThumbnailResult(ticket, ticket.copy(sequence = 2), true),
        "recycled cell")
    verify(!mayPublishThumbnailResult(ticket, ticket, false), "revoked permission")
    verify(chooseThumbnailStage(true, true, true, false) ==
        ThumbnailStage.MEMORY_OR_PLACEHOLDER, "no cold decode during scrub")
    verify(chooseThumbnailStage(false, true, true, false) ==
        ThumbnailStage.VISIBLE_SHARPEN, "settled upgrade")
    // Full pure catalogue code from ArchiveLogic is compiled above, so also
    // exercise its original deterministic sorting and selection contracts.
    val catalog = listOf(base, sd, unknown)
    verify(sortMedia(catalog, SortOrder.LARGEST, 0).size == 3, "archive sort")
    verify(selectAlbum(catalog, "DCIM/FICTION/").size == 3, "album selection")
    val tiedPrimary = base.copy(takenAtMillis = 123, sizeBytes = 12)
    val tiedSd = tiedPrimary.copy(uri = "content://media/1234-ABCD/images/media/42",
        volumeName = "1234-ABCD")
    val tiedOther = tiedSd.copy(uri = "content://media/5678-EFGH/images/media/42",
        volumeName = "5678-EFGH")
    verify(tiedPrimary.randomShuffleDiscriminator() == (tiedPrimary.id shl 1),
        "legacy primary random seed")
    verify(tiedSd.randomShuffleDiscriminator() != tiedPrimary.randomShuffleDiscriminator(),
        "secondary-volume random discriminator")
    val mounted = setOf("external_primary", "1234-ABCD")
    verify(requireMountedMediaVolume("external_primary", mounted) == "external_primary",
        "mounted-volume primary success")
    verify(requireMountedMediaVolume("1234-ABCD", mounted) == "1234-ABCD",
        "mounted-volume removable success")
    for (invalidVolume in listOf<String?>(null, "", "unknown", "external", "UNMOUNTED")) {
        verify(runCatching { requireMountedMediaVolume(invalidVolume, mounted) }
            .exceptionOrNull() is IllegalStateException, "missing volume fails closed")
    }
    verify(runCatching { requireMountedMediaVolume("external_primary", emptySet()) }
        .exceptionOrNull() is IllegalStateException, "unavailable volume list fails closed")
    verify(requireMediaCatalogueCursor(emptyList<String>()).isEmpty(),
        "valid empty query remains empty")
    verify(runCatching { requireMediaCatalogueCursor<String>(null) }
        .exceptionOrNull() is IllegalStateException, "null cursor cannot become empty")
    val versions = mapOf("external_primary" to "v1", "1234-ABCD" to "v2")
    val generations: Map<String, Long?> =
        mapOf("external_primary" to 100L, "1234-ABCD" to 200L)
    verify(sameMediaProviderScanSnapshot(versions, versions, generations, generations),
        "coherent version and generation scan")
    verify(!sameMediaProviderScanSnapshot(versions, versions, generations,
        generations + ("1234-ABCD" to 201L)), "mid-scan provider mutation")
    verify(!sameMediaProviderScanSnapshot(versions, versions + ("1234-ABCD" to "v3"),
        generations, generations), "provider reset")
    verify(!sameMediaProviderScanSnapshot(versions, versions - "1234-ABCD",
        generations, generations - "1234-ABCD"), "media volume eject")
    verify(sameMediaProviderScanSnapshot(versions, versions, emptyMap(), emptyMap()),
        "API29 version-only snapshot")
    verify(!sameMediaProviderScanSnapshot(
        mapOf("external_primary" to null),
        mapOf("external_primary" to null),
        mapOf("external_primary" to 5L),
        mapOf("external_primary" to 5L)),
        "unmounted volume null version cannot validate")
    verify(!sameMediaProviderScanSnapshot(
        mapOf("external_primary" to " "),
        mapOf("external_primary" to " "),
        emptyMap(), emptyMap()),
        "blank version on API29 must fail too")
    verify(!sameMediaProviderScanSnapshot(
        mapOf("external_primary" to "v1"),
        mapOf("external_primary" to "v1"),
        mapOf("external_primary" to null),
        mapOf("external_primary" to null)),
        "equal but unavailable generation cannot validate")
    verify(sameMediaProviderScanSnapshot(
        mapOf("external_primary" to "v1"),
        mapOf("external_primary" to "v1"),
        mapOf("external_primary" to 0L),
        mapOf("external_primary" to 0L)),
        "valid zero generation is accepted")
    verify(!sameMediaProviderScanSnapshot(
        mapOf("external_primary" to "v1"),
        mapOf("external_primary" to "v1"),
        mapOf("external_primary" to -1L),
        mapOf("external_primary" to -1L)),
        "negative generation must fail")
    val imagesGrant = GalleryMediaGrant(true, false, false)
    val videoGrant = GalleryMediaGrant(false, true, false)
    val revokedGrant = GalleryMediaGrant(false, false, false)
    verify(mayPublishCompletedGalleryScan(true, false,
        imagesGrant, imagesGrant, 5L, 5L),
        "unchanged grant and provider may publish")
    verify(!mayPublishCompletedGalleryScan(true, false,
        imagesGrant, imagesGrant, 5L, 6L),
        "midflight MediaStore notification blocks publication")
    verify(!mayPublishCompletedGalleryScan(true, false,
        imagesGrant, videoGrant, 5L, 5L),
        "midflight permission scope transition blocks publication")
    verify(!mayPublishCompletedGalleryScan(true, false,
        imagesGrant, revokedGrant, 5L, 5L),
        "revoke blocks stale publication")
    verify(!mayPublishCompletedGalleryScan(false, false,
        imagesGrant, imagesGrant, 5L, 5L),
        "superseded request cannot publish")
    verify(shouldRetryCompletedGalleryScan(true, false,
        imagesGrant, imagesGrant, 5L, 6L, true),
        "one automatic retry on same-scope MediaStore change")
    verify(!shouldRetryCompletedGalleryScan(true, false,
        imagesGrant, imagesGrant, 5L, 6L, false),
        "a second automatic retry is disallowed")
    verify(!shouldRetryCompletedGalleryScan(true, false,
        imagesGrant, revokedGrant, 5L, 6L, true),
        "never retry provider after revoked grant")
    val queue = GalleryRefreshQueue()
    val firstTicket = queue.request()
    verify(queue.begin() == firstTicket, "first ticket starts")
    verify(shouldRetryCompletedGalleryScan(
        queue.mayPublish(firstTicket), false, imagesGrant, imagesGrant,
        5L, 6L, true), "observer drift requests one follow-up")
    val autoTicket = queue.request()
    verify(!queue.mayPublish(firstTicket) && queue.finish(firstTicket),
        "new queued ticket prevents stale publication")
    verify(queue.begin() == autoTicket, "automatic ticket starts")
    verify(!shouldRetryCompletedGalleryScan(
        queue.mayPublish(autoTicket), false, imagesGrant, imagesGrant,
        6L, 7L, false), "repeat churn cannot auto-retry forever")
    verify(!queue.finish(autoTicket), "no third automatic ticket")
    val privateExceptionDetail =
        "private-name.jpg content://media/external_primary/images/media/44"
    for (failure in listOf(
            SecurityException(privateExceptionDetail),
            IllegalStateException(privateExceptionDetail),
            IllegalArgumentException(privateExceptionDetail))) {
        val rendered = galleryScanFailureMessage(failure)
        verify(!rendered.contains("private-name") &&
            !rendered.contains("content://") &&
            rendered.contains("refresh", ignoreCase = true),
            "MediaStore exception details never reach gallery UI")
    }
    val validV4Csv = "person_id,person_name,image_path,face_id,confidence\r\n" +
        "1,Éloïse,/storage/emulated/0/DCIM/fixture.jpg,9,0.99\r\n"
    val v4Bytes = validV4Csv.toByteArray(Charsets.UTF_8)
    verify(readBoundedV4Csv(java.io.ByteArrayInputStream(v4Bytes), v4Bytes.size) ==
        validV4Csv, "bounded V4 exact Unicode-safe CSV preservation")
    verify(runCatching { readBoundedV4Csv(
        java.io.ByteArrayInputStream(v4Bytes), v4Bytes.size - 1) }
        .exceptionOrNull() is IllegalArgumentException,
        "V4 export one byte over limit fails before parsing")
    verify(runCatching { readBoundedV4Csv(
        java.io.ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28))) }
        .exceptionOrNull() is java.nio.charset.MalformedInputException,
        "malformed V4 UTF8 cannot silently replace Person labels")
    val syntheticPrivateDocument =
        "hidden_name.csv content://provider/collection/42 /storage/private/path"
    for (operation in V4DocumentOperation.entries) {
        for (failure in listOf(
            SecurityException(syntheticPrivateDocument),
            IllegalArgumentException(syntheticPrivateDocument),
            java.io.IOException(syntheticPrivateDocument),
            RuntimeException(syntheticPrivateDocument))) {
            val message = v4DocumentFailureMessage(operation, failure)
            verify(!message.contains("hidden_name") &&
                !message.contains("content://") &&
                !message.contains("/storage/") &&
                message.contains("V4 export"),
                "V4 document provider details never reach UI")
        }
    }
    val benchmark = summarizeThumbnailBenchmark(
        listOf(50000L) + (1L..20L).map { it * 1000L })
    verify(benchmark.firstNs == 50000L && benchmark.warmCount == 20,
        "benchmark first sample separate from 20 repeats")
    verify(benchmark.warmP50Ns == 10000L && benchmark.warmP95Ns == 19000L,
        "benchmark nearest-rank warm percentile")
    verify(runCatching { summarizeThumbnailBenchmark(listOf(1L, 0L, 2L)) }
        .exceptionOrNull() is IllegalArgumentException,
        "failed benchmark sample rejected")
    for (mode in SortOrder.entries) {
        val forward = sortMedia(listOf(tiedPrimary, tiedSd, tiedOther), mode, 12345L)
        val backward = sortMedia(listOf(tiedOther, tiedSd, tiedPrimary), mode, 12345L)
        verify(forward == backward, "source-independent tied sort $mode")
    }
    val syntheticSort = (0 until 25000).map { index ->
        base.copy(id = index.toLong(), uri = "content://fiction/media/" + index,
            takenAtMillis = index.toLong(), sizeBytes = index.toLong())
    }.reversed()
    for (order in SortOrder.entries) {
        var checkpoints = 0
        val cancel = IllegalStateException("superseded sort")
        verify(runCatching {
            sortMedia(syntheticSort, order, 11L) {
                if (++checkpoints == 4) throw cancel
            }
        }.exceptionOrNull() === cancel && checkpoints == 4,
            "large $order sort checks cancellation within computation")
        verify(sortMedia(catalog, order, 7L) ==
            sortMedia(catalog, order, 7L) { },
            "optional checkpoint preserves $order exact order")
    }
    println("PASS $checks media source-contract assertions (actual pure Kotlin source)")
}
KOTLIN
src="$root/app/src/main/java/nl/atlas/gallery"
kotlinc "$src/ArchiveLogic.kt" \
    "$src/GalleryMediaGrant.kt" \
    "$src/GalleryRefreshQueue.kt" \
    "$src/featuremedia/GalleryScanPublicationAdmission.kt" \
    "$src/featuremedia/MediaDateAdmission.kt" \
    "$src/featuremedia/MediaMetadata.kt" \
    "$src/featuremedia/ThumbnailBindingPolicy.kt" \
    "$src/featuremedia/ThumbnailBenchmarkStats.kt" \
    "$src/featuremedia/GalleryScanErrorMessage.kt" \
    "$src/featuremedia/V4DocumentErrorMessage.kt" \
    "$src/featuremedia/V4CsvReadPolicy.kt" \
    "$src/featuremedia/MediaRowIdAdmission.kt" \
    "$tmp/PersonEntryStub.kt" "$tmp/RunMediaContracts.kt" \
    -include-runtime -d "$tmp/check.jar"
java -jar "$tmp/check.jar"
