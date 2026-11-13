package nl.atlas.gallery.featureface

fun main() {
    val tokens = (1..25000).map { NativeFaceIds.createObservationId() }
    check(tokens.toSet().size == tokens.size)
    check(tokens.all { it.localHex.length == 32 && it.localHex == it.localHex.lowercase() })
    val v4 = ConfirmedV4FaceRef("a".repeat(64), 42L)
    check(v4.v4FaceId == 42L)
    try { NativeFaceObservationId("not a valid face ID"); error("malformed accepted") }
    catch (_: IllegalArgumentException) { }
    try { ConfirmedV4FaceRef("a".repeat(63), 42L); error("bad digest accepted") }
    catch (_: IllegalArgumentException) { }
    val ref = NativeFaceReviewReference(tokens.first(), FaceMediaKey("content://fictional/images/1", "r"), "mlkit-test")
    check(ref.id != tokens[1] && ref.media.sourceRevision == "r")
    println("PASS 25000 random native observation IDs unique, cross-namespace typing checks")
}
