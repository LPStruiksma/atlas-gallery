package nl.atlas.gallery.featureface

/** Standalone zero-Gradle probe: kotlinc ... -include-runtime -d probe.jar && java -jar probe.jar */
fun main() {
    val noPermission = FaceScanConditions(true, false, false, true, true, true, false, false)
    check(!noPermission.canRun())
    check(noPermission.holdReason() == FaceScanPhase.PERMISSION_REQUIRED)
    val idle = FaceScanConditions(true, true, true, true, true, false, false, true)
    check(!idle.canRun())
    check(idle.holdReason() == FaceScanPhase.BATTERY_LOW)
    check(idle.copy(charging = true).canRun())
    check(idle.copy(backgroundMaintenance = false).canRun())
    val snap = FaceScanSnapshot("p1", 150000, 149999, 0, 1, 0, 1, "mlkit-bundled-opt-in-v1", false)
    check(snap.pending + snap.processed == snap.totalVisibleMedia)
    val obs = UnknownFaceObservation(
        FaceMediaKey("content://media/external/images/media/1", "g1:rev3"), 0,
        UnknownFaceBox(.1f, .2f, .3f, .4f), "test-v1", "p1"
    )
    check(obs.ordinal == 0)
    try { UnknownFaceBox(Float.NaN, 0f, 1f, 1f); error("NaN accepted") } catch (_: IllegalArgumentException) { }
    try { UnknownFaceBox(.8f, 0f, .2f, 1f); error("inverted accepted") } catch (_: IllegalArgumentException) { }
    println("PASS 9 contract assertions (synthetic only)")
}
