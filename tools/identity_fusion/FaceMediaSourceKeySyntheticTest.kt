package nl.atlas.gallery.featureface

fun main() {
    var assertions = 0
    fun checkCase(ok: Boolean) { check(ok); assertions++ }
    fun make(
        volume: String = "external_primary", id: Long = 42L, version: String? = "media-v7",
        generation: Long = 11L, size: Long = 250_000L, modified: Long = 1_700_000_000L,
        editor: Long = 0L, video: Boolean = false,
        width: Int = 1920, height: Int = 1080, orientation: Int = 0,
        uri: String = "content://media/$volume/images/media/$id"
    ) = FaceMediaSourceKey.fromVisiblePhoto(
        uri, volume, video, id, version, generation, size, modified, editor,
        width, height, orientation
    )
    val old = make()
    val same = make()
    checkCase(old == same)
    checkCase(old.canonicalMediaKey.startsWith("atlas-face-media-v1:"))
    checkCase(old.sourceRevision.startsWith("atlas-face-rev-v1:"))
    checkCase(old.canonicalMediaKey.length == 84)
    checkCase(old.sourceRevision.length == 82)
    checkCase(!old.canonicalMediaKey.contains("external_primary"))
    checkCase(!old.sourceRevision.contains("media-v7"))

    checkCase(make(orientation=360).sourceRevision == old.sourceRevision)
    checkCase(make(orientation=-90).sourceRevision == make(orientation=270).sourceRevision)
    checkCase(make(orientation=450).sourceRevision == make(orientation=90).sourceRevision)
    // M uses Int.MIN_VALUE for absent, NULL or invalid provider orientation.
    // This must NOT abort a 150k catalogue, and must NOT impersonate 0°.
    val unknownOrientation = make(orientation=Int.MIN_VALUE)
    checkCase(unknownOrientation.canonicalMediaKey == old.canonicalMediaKey)
    checkCase(unknownOrientation.sourceRevision != old.sourceRevision)
    checkCase(unknownOrientation.sourceRevision != make(orientation=90).sourceRevision)
    checkCase(make(orientation=Int.MIN_VALUE).sourceRevision == unknownOrientation.sourceRevision)
    checkCase(make(width=0,height=0,orientation=Int.MIN_VALUE).sourceRevision != unknownOrientation.sourceRevision)
    val editorUpdated = make(editor=1)
    checkCase(editorUpdated.canonicalMediaKey == old.canonicalMediaKey)
    checkCase(editorUpdated.sourceRevision != old.sourceRevision)
    val contentEdited = make(generation=12)
    checkCase(contentEdited.canonicalMediaKey == old.canonicalMediaKey)
    checkCase(contentEdited.sourceRevision != old.sourceRevision)
    for (changed in listOf(make(version="media-v8"), make(size=250_001),
        make(modified=1_700_000_001), make(width=800), make(height=1200),
        make(orientation=90))) {
        checkCase(changed.canonicalMediaKey == old.canonicalMediaKey)
        checkCase(changed.sourceRevision != old.sourceRevision)
    }
    checkCase(make(id=43).canonicalMediaKey != old.canonicalMediaKey)
    checkCase(make(volume="1234-ABCD").canonicalMediaKey != old.canonicalMediaKey)
    checkCase(make(id=42,version="media-v9").canonicalMediaKey == old.canonicalMediaKey)

    // Fail closed: an incomplete snapshot must never prune an already reviewed
    // observation by publishing an apparently successful partial inventory.
    var rejected = 0
    fun reject(block: () -> Any) {
        try { block(); error("Expected strict source refusal") }
        catch (_: IllegalArgumentException) { rejected++ }
    }
    reject { make(width=-1) }
    reject { make(height=-1) }
    reject { make(orientation=45) }
    reject { make(orientation=46) }
    reject { make(orientation=-45) }
    reject { make(orientation=Int.MIN_VALUE + 1) }
    reject { make(orientation=Int.MAX_VALUE) }
    reject { make(volume="external") }
    reject { make(volume="unknown") }
    reject { make(version=null) }
    reject { make(version=" ") }
    reject { make(generation=0) }
    reject { make(size=0) }
    reject { make(editor=-1) }
    reject { make(id=-1) }
    reject { make(video=true) }
    reject { make(uri="file:///storage/emulated/0/DCIM/photo.jpg") }
    reject { make(uri="content://other/external_primary/images/media/42") }
    reject { make(uri="content://media/external/images/media/42") }
    reject { make(uri="content://media/external_primary/images/media/42?token=123") }
    reject { make(uri="content://media/external_primary/images/media/42#fragment") }
    reject { make(uri="content://media/external_primary/images/media/43") }
    reject { make(uri="content://media/external_primary/videos/media/42") }
    reject { make(volume="external/../primary") }
    reject { make(version="x".repeat(257)) }
    reject { make(uri="content://media/external_primary/images/media/42/extra") }
    checkCase(rejected == 26)

    // 10k fully fictional media identifiers, no album/photo bytes or real URIs.
    val keys = HashSet<String>()
    for (i in 1L..10_000L) {
        val k = make(id=i)
        checkCase(keys.add(k.canonicalMediaKey))
        checkCase(k.sourceRevision == old.sourceRevision)
    }
    checkCase(keys.size == 10_000)
    println("PASS $assertions deterministic face-media source key assertions + $rejected strict rejections")
}
