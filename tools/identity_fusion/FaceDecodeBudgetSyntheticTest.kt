package nl.atlas.gallery.featureface

fun main() {
    var cases = 0
    fun checkCase(ok: Boolean) { check(ok); cases++ }
    val ordinary = FaceDecodeBudget.plan(4032, 3024)
    checkCase(ordinary.targetWidth == 960 && ordinary.targetHeight == 720)
    checkCase(ordinary.estimatedBitmapBytes == 960L * 720L * 4L)
    val large = FaceDecodeBudget.plan(16384, 12288)
    checkCase(large.targetWidth == 960 && large.targetHeight == 720)
    checkCase(large.estimatedBitmapBytes <= FaceDecodeBudget.DEFAULT_BITMAP_BUDGET_BYTES)
    val tiny = FaceDecodeBudget.plan(30, 12)
    checkCase(tiny.targetWidth == 30 && tiny.targetHeight == 12)
    val pathological = FaceDecodeBudget.plan(Int.MAX_VALUE, Int.MAX_VALUE)
    checkCase(pathological.estimatedBitmapBytes <= FaceDecodeBudget.DEFAULT_BITMAP_BUDGET_BYTES)
    checkCase(maxOf(pathological.targetWidth, pathological.targetHeight) <= 960)
    val panorama = FaceDecodeBudget.plan(1000000, 17)
    checkCase(panorama.targetWidth <= 960 && panorama.targetHeight >= 1)
    val lowMemory = FaceDecodeBudget.plan(1800, 1800, maxLongEdge = 1800, maxBitmapBytes = 1024L * 1024)
    checkCase(lowMemory.estimatedBitmapBytes <= 1024L * 1024)
    val nonUpscale = FaceDecodeBudget.plan(1,1)
    checkCase(nonUpscale.targetWidth == 1 && nonUpscale.targetHeight == 1)
    for (dims in listOf(0 to 100, -1 to 200, 100 to -1)) {
        try { FaceDecodeBudget.plan(dims.first,dims.second); error("Accepted invalid source geometry") }
        catch (_: IllegalArgumentException) { cases++ }
    }
    // 100k *synthetic integer dimensions*, zero image bytes, assert bounds systematically.
    var n = 0
    for (i in 1..100_000) {
        val w = ((i.toLong()*7919L)%2_000_000L+1L).toInt()
        val h = ((i.toLong()*9973L)%2_000_000L+1L).toInt()
        val p = FaceDecodeBudget.plan(w,h)
        check(p.targetWidth in 1..minOf(w,960) && p.targetHeight in 1..minOf(h,960))
        check(p.estimatedBitmapBytes <= 8L*1024L*1024L)
        n++
    }
    println("PASS $cases targeted decode-budget assertions + $n synthetic bounded-size trials")
}
