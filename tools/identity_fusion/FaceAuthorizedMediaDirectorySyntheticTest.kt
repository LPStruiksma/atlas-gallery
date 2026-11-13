package nl.atlas.gallery.featureface

import java.util.concurrent.CancellationException
import java.io.IOException

fun main() {
    var assertions = 0
    fun expect(ok: Boolean) { check(ok); assertions++ }
    fun photo(i: Int, volume: String = "external_primary") = FaceVisibleMedia(
        "content://media/$volume/images/media/$i", volume, false, i.toLong(),
        "provider-v4", i.toLong() + 1, 100_000L + i, 1_700_000_000L + i,
        0L, 1920, 1080, 0
    )
    val source = (1..50_000).map(::photo).toMutableList()
    var epoch = "grant-A"
    var providerFresh = true
    var providerFailure: Exception? = null
    val started = System.nanoTime()
    val directory = FaceAuthorizedMediaDirectory.build(
        "grant-A", source, {it}, {epoch}, {providerFailure?.let { error -> throw error }; providerFresh}
    )
    val elapsedMs = (System.nanoTime() - started) / 1_000_000
    expect(directory.photoCount == 50_000)
    for (i in listOf(1, 2, 42, 13_371, 24_999, 50_000)) {
        val identity = photo(i).toDurableFaceKey()
        expect(directory.authorizedUri(identity) == photo(i).uri)
        expect(directory.sourceRevisionCurrent(identity))
    }
    // Hundreds of exact-positive lookups, no scan over all 50k source items.
    for (i in 1..1_000) {
        val n = (i * 47) % 50_000 + 1
        expect(directory.authorizedUri(photo(n).toDurableFaceKey()) == photo(n).uri)
    }
    val key = photo(42).toDurableFaceKey()
    // Same shortened 45-bit fingerprint must NOT be treated as authority.
    val last = if (key.canonicalMediaKey.last() == '0') '1' else '0'
    val forged = key.copy(canonicalMediaKey=key.canonicalMediaKey.dropLast(1)+last)
    try { directory.authorizedUri(forged); error("partial hash collision trusted") }
    catch (_: SecurityException) { assertions++ }
    expect(!directory.sourceRevisionCurrent(forged))
    val geom = photo(42).copy(orientationDegrees=90).toDurableFaceKey()
    expect(geom.canonicalMediaKey == key.canonicalMediaKey)
    expect(geom.sourceRevision != key.sourceRevision)
    expect(!directory.sourceRevisionCurrent(geom))
    try { directory.authorizedUri(key.copy(sourceRevision="edited")); error("stale revision trusted") }
    catch (_: SecurityException) { assertions++ }
    try { directory.authorizedUri(key.copy(canonicalMediaKey="corrupt")); error("bad key trusted") }
    catch (_: SecurityException) { assertions++ }

    // Input list structure is frozen: later source-list modifications cannot
    // silently replace authorized rows. Permission epoch still gates access.
    source.clear()
    expect(directory.authorizedUri(key) == photo(42).uri)
    epoch = "grant-B"
    expect(!directory.sourceRevisionCurrent(key))
    try { directory.authorizedUri(key); error("old grant retained URI") }
    catch (_: SecurityException) { assertions++ }
    epoch = "grant-A"
    providerFresh = false
    expect(!directory.sourceRevisionCurrent(key))
    providerFresh = true
    // Provider version/generation IPC can fail on unmount, transient revocation,
    // or app lifecycle; none is a detector failure or valid source URI.
    for (ex in listOf(SecurityException("grant gone"), IOException("provider I/O"),
        IllegalStateException("volume unmounted"))) {
        providerFailure = ex
        expect(!directory.sourceRevisionCurrent(key))
        try { directory.authorizedUri(key); error("provider exception authorized URI") }
        catch (_: SecurityException) { assertions++ }
    }
    providerFailure = CancellationException("worker stopped")
    try { directory.sourceRevisionCurrent(key); error("cancellation swallowed") }
    catch (_: CancellationException) { assertions++ }
    providerFailure = null
    expect(directory.sourceRevisionCurrent(key))

    val withVideo = FaceAuthorizedMediaDirectory.build(
        "grant-A", listOf(photo(42), FaceVisibleMedia(
            "content://media/external_primary/video/media/52","external_primary",true,
            52,"provider-v4",3,200,1_700_000_000, 0L, 0, 0, 0
        )), {it}, {epoch}, {providerFresh}
    )
    expect(withVideo.photoCount == 1)
    expect(withVideo.authorizedUri(key) == photo(42).uri)

    // Duplicate physical identity may hide contradictory revisions. Abort,
    // rather than arbitrarily pick the first record based on list order.
    for (second in listOf(photo(42),photo(42).copy(generationModified=999))) {
        try {
            FaceAuthorizedMediaDirectory.build("grant-A",
                listOf(photo(42),second),{it},{epoch},{providerFresh})
            error("duplicate physical identity accepted")
        } catch (_: IllegalArgumentException) { assertions++ }
    }
    try {
        FaceAuthorizedMediaDirectory.build("grant-A",
            listOf(photo(42),photo(43).copy(providerVersion=null)),
            {it},{epoch},{providerFresh})
        error("incomplete source metadata accepted")
    } catch (_: IllegalArgumentException) { assertions++ }

    var checks = 0
    try {
        FaceAuthorizedMediaDirectory.build("grant-A",
            (1..20_000).map(::photo), {it}, {epoch}, {providerFresh}, {
                checks++
                if (checks == 9) throw CancellationException("synthetic user stop")
            })
        error("cancellation swallowed")
    } catch (_: CancellationException) { assertions++ }

    println("PASS $assertions exact-hash/permission media directory assertions on 50k fictional rows; build ${elapsedMs}ms (synthetic JVM only)")
}
