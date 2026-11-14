package nl.atlas.gallery.tools.peoplebench

import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featurepeople.cluster.FaceEmbedding
import nl.atlas.gallery.featurepeople.cluster.FaceMetric
import nl.atlas.gallery.featurepeople.split.SplitFace
import nl.atlas.gallery.featurepeople.split.SplitProposal
import nl.atlas.gallery.featurepeople.split.SplitProposer
import nl.atlas.gallery.featurepeople.split.SplitRequest
import java.io.DataInputStream
import java.io.File
import java.security.MessageDigest
import kotlin.random.Random

/**
 * SPLIT-0 (#105) bench: runs the app's SplitProposer on profiles built from
 * the reference-labelled library faces export_vectors.py wrote, and prints
 * aggregates only. Not part of the app build; see ../README.md.
 *
 * Profiles (reference identities with at least MIN_FACES faces):
 *   clean    one identity                      truth k = 1 (false-split control)
 *   two      two identities merged             truth k = 2
 *   three    three identities merged           truth k = 3
 *   alike    the two-identity profiles whose identities score highest against
 *            each other (mean pair of the top tenth), reported again apart
 *   family   two identities of one Flickr author (often relatives)
 *
 * Arguments: VECTORS [maxProfilesPerKind]
 */
private const val MIN_FACES = 12

private fun fid(i: Int): String = MessageDigest.getInstance("MD5").digest("s$i".toByteArray())
    .joinToString("") { "%02x".format(it) }

private class Row(val kind: String, val truthK: Int, val k: Int, val purity: Double, val placed: Int,
                  val unsure: Int, val faces: Int, val coverage: Double)

fun main(args: Array<String>) {
    val d = DataInputStream(File(args[0]).inputStream().buffered(1 shl 20))
    val n = d.readInt(); val dim = d.readInt()
    val raw = Array(n) { FloatArray(dim) { d.readFloat() } }
    val photo = IntArray(n) { d.readInt() }
    val author = IntArray(n) { d.readInt() }
    val label = IntArray(n) { d.readInt() }
    val maxPer = args.getOrNull(1)?.toInt() ?: 150
    val metric = Array(n) { FaceMetric.SFACE.apply(raw[it]) }
    val emb = Array(n) { FaceEmbedding(NativeFaceObservationId(fid(it)), "m", metric[it].vector, metric[it].crowd) }
    val byLabel = (0 until n).filter { label[it] >= 0 }.groupBy { label[it] }.filterValues { it.size >= MIN_FACES }
    val ids = byLabel.keys.sorted()
    val authorOf = ids.associateWith { l -> byLabel.getValue(l).groupingBy { author[it] }.eachCount().maxBy { it.value }.key }
    println("faces=$n identities($MIN_FACES+)=${ids.size} faces_in_them=${ids.sumOf { byLabel.getValue(it).size }}")

    fun meanPair(a: List<Int>, b: List<Int>): Double {
        val sa = DoubleArray(dim); val sb = DoubleArray(dim)
        for (i in a) for (x in 0 until dim) sa[x] += metric[i].vector[x].toDouble()
        for (i in b) for (x in 0 until dim) sb[x] += metric[i].vector[x].toDouble()
        var s = 0.0; for (x in 0 until dim) s += sa[x] * sb[x]
        return s / (a.size.toDouble() * b.size)
    }

    fun run(kind: String, people: List<Int>): Row {
        val faces = people.flatMap { byLabel.getValue(it) }
        val truth = HashMap<NativeFaceObservationId, Int>()
        for (l in people) for (i in byLabel.getValue(l)) truth[emb[i].observation] = l
        val req = SplitRequest(faces.map { SplitFace(emb[it], media = photo[it] + 1L) })
        val p: SplitProposal = SplitProposer.propose(req)
        var right = 0; var placed = 0
        val bestShare = HashMap<Int, Int>()
        for (g in p.groups) {
            val counts = g.members.groupingBy { truth.getValue(it) }.eachCount()
            val (maj, c) = counts.maxBy { it.value }
            right += c; placed += g.members.size
            bestShare[maj] = maxOf(bestShare[maj] ?: 0, c)
        }
        // Coverage: share of each true person's faces found in its own best group (mean over people).
        val coverage = people.map { (bestShare[it] ?: 0).toDouble() / byLabel.getValue(it).size }.average()
        return Row(kind, people.size, p.k, if (placed == 0) 1.0 else right.toDouble() / placed, placed,
            p.unsure.size, faces.size, coverage)
    }

    val rng = Random(105)
    val rows = ArrayList<Row>()
    val t0 = System.nanoTime()
    for (l in ids.shuffled(rng).take(maxPer)) rows += run("clean", listOf(l))
    // Two-identity profiles from different authors, with their mutual score.
    val pairs = ArrayList<Triple<Int, Int, Double>>()
    val shuffled = ids.shuffled(rng)
    for (x in shuffled.indices) for (y in x + 1 until shuffled.size) {
        val a = shuffled[x]; val b = shuffled[y]
        if (authorOf[a] == authorOf[b]) continue
        pairs += Triple(a, b, meanPair(byLabel.getValue(a), byLabel.getValue(b)))
    }
    for ((a, b, _) in pairs.shuffled(rng).take(maxPer)) rows += run("two", listOf(a, b))
    val alike = pairs.sortedByDescending { it.third }.take(maxOf(1, pairs.size / 10)).shuffled(rng).take(maxPer)
    println("alike pairs: mean pair %.3f..%.3f".format(alike.minOf { it.third }, alike.maxOf { it.third }))
    for ((a, b, _) in alike) rows += run("alike", listOf(a, b))
    val family = ArrayList<List<Int>>()
    for ((_, group) in ids.groupBy { authorOf.getValue(it) }) {
        for (x in group.indices) for (y in x + 1 until group.size) family += listOf(group[x], group[y])
    }
    for (f in family.shuffled(rng).take(maxPer)) rows += run("family", f)
    repeat(maxPer) {
        val pick = ids.shuffled(rng).distinctBy { authorOf[it] }.take(3)
        if (pick.size == 3) rows += run("three", pick)
    }
    val seconds = (System.nanoTime() - t0) / 1e9

    for (kind in listOf("clean", "two", "three", "alike", "family")) {
        val r = rows.filter { it.kind == kind }
        if (r.isEmpty()) continue
        val kRight = r.count { it.k == it.truthK }
        val split = r.count { it.k >= 2 }
        val purity = r.sumOf { it.purity * it.placed } / r.sumOf { it.placed }.coerceAtLeast(1)
        val unsure = r.sumOf { it.unsure }.toDouble() / r.sumOf { it.faces }
        val wrongSplit = r.count { it.k >= 2 && it.purity < 0.9 }
        println("%-7s profiles=%d k_accuracy=%.3f split=%d purity=%.4f coverage=%.3f unsure_share=%.4f impure_splits=%d k_over=%d k_under=%d"
            .format(kind, r.size, kRight.toDouble() / r.size, split, purity, r.map { it.coverage }.average(), unsure,
                wrongSplit, r.count { it.k > it.truthK }, r.count { it.k < it.truthK }))
    }
    val clean = rows.filter { it.kind == "clean" }
    println("false_split_rate(clean)=%.4f  profiles=%d in %.1fs".format(
        clean.count { it.k >= 2 }.toDouble() / clean.size.coerceAtLeast(1), rows.size, seconds))
}
