package nl.atlas.gallery.tools.peoplebench

import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featurepeople.cluster.ClusterLinkage
import nl.atlas.gallery.featurepeople.cluster.ClusterPolicy
import nl.atlas.gallery.featurepeople.cluster.FaceClusterState
import nl.atlas.gallery.featurepeople.cluster.FaceEmbedding
import nl.atlas.gallery.featurepeople.cluster.ClusterReviewFacts
import nl.atlas.gallery.featurepeople.cluster.FaceClusterer
import nl.atlas.gallery.featurepeople.cluster.PersonKey
import nl.atlas.gallery.featurepeople.identity.NamedPersonMatches
import nl.atlas.gallery.featurepeople.identity.SamePersonHypotheses
import nl.atlas.gallery.featurepeople.cluster.FaceMetric
import nl.atlas.gallery.featurepersistence.ClusterPublication
import java.io.DataInputStream
import java.io.File
import java.security.MessageDigest

/**
 * Runs the app's own grouping code (FaceClusterer through ClusterPublication,
 * in publications of 1,024 faces) on the vectors export_vectors.py wrote, and
 * prints aggregates. Not part of the app build; see ../README.md.
 *
 * Arguments: VECTORS policy...
 *   conservative | gallery | measured     the named ClusterPolicy constants
 *   c:join,merge                           centroid linkage on raw vectors
 *   cm:join,merge                          centroid linkage after FaceMetric.SFACE
 *   p:join,merge[,crowd[,group,min[,frame]]]   mean-pair linkage after FaceMetric.SFACE
 * -Ddump=DIR writes each policy's face-to-group table for judge_groups.py.
 * -Dhypotheses=DIR writes each policy's "same person?" questions for judge_hypotheses.py.
 * -Dautoadd=DIR (no policy argument) scores later faces against named people for judge_autoadd.py.
 * -Dearly=FILE with it: the photo numbers that come first (split_by_upload.py); default even numbers.
 * -Dphase1=DIR (no policy argument) plays the owner's Phase 1 path for phase1.py: group, name each
 *   recurring person's largest group, let sure faces join, list the questions. -Dfloors=0.45,0.40 sweeps.
 */
private fun uid(i: Int): String = MessageDigest.getInstance("MD5").digest("f$i".toByteArray())
    .joinToString("") { "%02x".format(it) }

fun main(args: Array<String>) {
    val d = DataInputStream(File(args[0]).inputStream().buffered(1 shl 20))
    val n = d.readInt(); val dim = d.readInt()
    val raw = Array(n) { FloatArray(dim) { d.readFloat() } }
    val photo = IntArray(n) { d.readInt() }
    val author = IntArray(n) { d.readInt() }
    val label = IntArray(n) { d.readInt() }
    for (i in 0 until n) if (label[i] < 0) label[i] = -(i + 2) // seen once: its own identity
    val metric = Array(n) { FaceMetric.SFACE.apply(raw[it]) }
    val ids = Array(n) { NativeFaceObservationId(uid(it)) }
    val index = HashMap<NativeFaceObservationId, Int>().also { m -> ids.forEachIndexed { i, id -> m[id] = i } }

    println("faces=$n photos=${photo.toSet().size} authors=${author.toSet().size}")
    System.getProperty("autoadd")?.let { autoAdd(it, n, dim, metric, ids, index, photo, label); return }
    System.getProperty("phase1")?.let { phase1(it, n, dim, metric, ids, index, photo, label); return }
    for (spec in args.drop(1)) {
        val (policy, useMetric) = when (spec) {
            "conservative" -> ClusterPolicy.SFACE_CONSERVATIVE to false
            "gallery" -> ClusterPolicy.SFACE_GALLERY to false
            "measured" -> ClusterPolicy.SFACE_MEASURED.copy(metric = null) to true
            else -> {
                val (kind, rest) = spec.split(":")
                val p = rest.split(",").map { it.toFloat() }
                if (kind == "p") ClusterPolicy(p[0], 0f, p[1], 0.5f, 0.08f,
                    linkage = ClusterLinkage.MEAN_PAIR, crowdWeight = p.getOrElse(2) { 0f },
                    groupThreshold = p.getOrElse(3) { 1f }, groupMinSize = p.getOrElse(4) { 0f }.toInt(),
                    sameFrameVeto = p.getOrElse(5) { 0f } > 0f) to true
                else ClusterPolicy(p[0], 0f, p[1], 0.5f, 0.08f) to (kind == "cm")
            }
        }
        val start = System.nanoTime()
        val faces = (0 until n).map {
            if (useMetric) FaceEmbedding(ids[it], "m", metric[it].vector, metric[it].crowd, photo[it] + 1L)
            else FaceEmbedding(ids[it], "m", raw[it])
        }
        var state = FaceClusterState.empty("m", dim, policy)
        for (chunk in faces.chunked(1024)) state = ClusterPublication.publish(state, chunk)
        System.getProperty("dump")?.let { dir ->
            File(dir).mkdirs()
            val table = IntArray(n) { -1 }
            state.unknownClusters.forEachIndexed { k, c -> for (m in c.members) table[index.getValue(m)] = k }
            File(dir, spec.replace(':', '_') + ".txt").writeText(table.joinToString("\n"))
        }
        System.getProperty("hypotheses")?.let { dir ->
            // One line per "same person?" question: a face index of each group, the two scores.
            File(dir).mkdirs()
            val t = System.nanoTime()
            val asked = SamePersonHypotheses.of(state, limit = 10_000)
            File(dir, spec.replace(':', '_') + ".txt").writeText(asked.joinToString("\n") {
                "${index.getValue(it.first.anchor)} ${index.getValue(it.second.anchor)} ${it.meanPair} ${it.bestMatch}" })
            println("  same-person questions=${asked.size} in %.1fs".format((System.nanoTime() - t) / 1e9))
        }
        var groups = 0; var shown = 0; var mixed = 0; var minority = 0; var samePhoto = 0; var pairs = 0
        for (c in state.unknownClusters) {
            val m = c.members.map { index.getValue(it) }
            if (m.size == 2) pairs++
            if (m.size < 3) continue
            groups++; shown += m.size
            val byAuthor = m.groupingBy { author[it] }.eachCount()
            if (byAuthor.size > 1) { mixed++; minority += m.size - byAuthor.values.max() }
            if (m.map { photo[it] }.toSet().size < m.size) samePhoto++
        }
        val top = state.unknownClusters.take(4).joinToString(" ") { c ->
            "${c.members.size}(${c.members.map { author[index.getValue(it)] }.toSet().size} authors)" }
        println("%-26s groups3+=%d faces_in_them=%d groups_mixing_authors=%d other_author_faces=%d groups_with_two_faces_of_one_photo=%d pairs=%d largest: %s  %.1fs".format(
            spec, groups, shown, mixed, minority, samePhoto, pairs, top, (System.nanoTime() - start) / 1e9))
    }
}

/**
 * -Dautoadd=DIR: would it be safe to add a later face to a NAMED person without asking?
 *
 * Half of the photos come first (even photo number, or with -Dearly=FILE the
 * photo numbers split_by_upload.py lists) and are grouped with the measured rule; every group of 3+ is "named" as it stands, mistakes included.
 * Each face of the other half is then scored against every named person the
 * way SamePersonHypotheses.forPeople scores a lone face: the mean over the
 * person's faces and the best single match, both crowd-corrected. Writes
 * DIR/people.txt (one named person per line: its face indexes) and
 * DIR/late.txt (face, best person, its mean, the second-best person's mean,
 * best single match) for judge_autoadd.py. Later faces are scored against the
 * named faces only, as if added faces were never used as examples.
 * DIR/matches.txt (face, person, mean) is what the app's own
 * NamedPersonMatches reads from the same named people and later faces.
 * By default every group is its own named person; -DsameName=1 gives groups
 * with the same majority reference label one name.
 */
private fun autoAdd(
    dir: String, n: Int, dim: Int, metric: Array<FaceMetric.Face>, ids: Array<NativeFaceObservationId>,
    index: Map<NativeFaceObservationId, Int>, photo: IntArray, label: IntArray
) {
    val policy = ClusterPolicy.SFACE_MEASURED.copy(metric = null)
    val w = policy.crowdWeight
    val first = System.getProperty("early")?.let { f -> File(f).readLines().mapTo(HashSet()) { it.trim().toInt() } }
    fun isEarly(i: Int) = if (first != null) photo[i] in first else photo[i] % 2 == 0
    val late = (0 until n).filterNot(::isEarly)
    var state = FaceClusterState.empty("m", dim, policy)
    for (chunk in (0 until n).filter(::isEarly)
        .map { FaceEmbedding(ids[it], "m", metric[it].vector, metric[it].crowd, photo[it] + 1L) }
        .chunked(1024)) state = ClusterPublication.publish(state, chunk)
    val groups = state.unknownClusters.filter { it.members.size >= 3 }
        .map { c -> c.members.map { index.getValue(it) } }
    // -DsameName=1: the owner gives groups of one person (same majority
    // reference label) the same name, so they are one named person.
    val people = if (System.getProperty("sameName") == null) groups
        else groups.groupBy { m -> m.groupingBy { label[it] }.eachCount().maxBy { it.value }.key }
            .values.map { it.flatten() }
    val sums = people.map { m -> FloatArray(dim).also { s -> for (i in m) for (d in 0 until dim) s[d] += metric[i].vector[d] } }
    val crowd = people.map { m -> m.sumOf { metric[it].crowd.toDouble() }.toFloat() / m.size }
    val out = StringBuilder()
    for (f in late) {
        val v = metric[f].vector
        var best = -1; var mean1 = Float.NEGATIVE_INFINITY; var mean2 = Float.NEGATIVE_INFINITY
        for (p in people.indices) {
            var dot = 0f
            val s = sums[p]
            for (d in 0 until dim) dot += v[d] * s[d]
            val mean = dot / people[p].size - w * (metric[f].crowd + crowd[p]) / 2f
            if (mean > mean1) { mean2 = mean1; mean1 = mean; best = p } else if (mean > mean2) mean2 = mean
        }
        var top = Float.NEGATIVE_INFINITY
        for (j in people[best]) {
            var dot = 0f
            val u = metric[j].vector
            for (d in 0 until dim) dot += v[d] * u[d]
            top = maxOf(top, dot - w * (metric[f].crowd + metric[j].crowd) / 2f)
        }
        out.append("$f $best $mean1 $mean2 $top ${photo[f]}\n")
    }
    File(dir).mkdirs()
    // The app's own rule on the same split: name the groups, publish the later
    // faces (they group among themselves as on a phone), read NamedPersonMatches.
    val confirmed = HashMap<NativeFaceObservationId, PersonKey>()
    people.forEachIndexed { k, m -> for (i in m) confirmed[ids[i]] = PersonKey("p$k") }
    var live = FaceClusterer.update(state, reviews = ClusterReviewFacts(confirmed = confirmed))
    for (chunk in late.map { FaceEmbedding(ids[it], "m", metric[it].vector, metric[it].crowd, photo[it] + 1L) }
        .chunked(1024)) live = ClusterPublication.publish(live, chunk)
    val t = System.nanoTime()
    val matches = NamedPersonMatches.of(live)
    println("NamedPersonMatches: ${matches.size} faces in %.2fs".format((System.nanoTime() - t) / 1e9))
    File(dir, "matches.txt").writeText(matches.joinToString("\n") {
        "${index.getValue(it.face)} ${it.person.value.drop(1)} ${it.mean}" })
    File(dir, "people.txt").writeText(people.joinToString("\n") { it.joinToString(" ") })
    File(dir, "late.txt").writeText(out.toString())
    println("named people=${people.size} from ${groups.size} groups of 3+ in the first half of the photos, later faces=${late.size}")
}

/**
 * -Dphase1=DIR: the owner's Phase 1 path on the bench (#580).
 *
 * All faces are grouped with the measured rule. Then, per recurring reference
 * identity (10+ faces), largest first, the owner names the unnamed group that
 * holds most of that identity's faces, as it stands (mistakes included), once
 * per group. FaceClusterer.update with those names lets sure faces join a
 * named person (ClusterPolicy.personMatchFloor), and SamePersonHypotheses lists
 * the questions the app would ask. For each floor in -Dfloors (default the
 * shipped one) writes DIR/<floor>/faces.txt (one line per face: n|m|u, the
 * person or group index, the match mean or 0) and DIR/<floor>/named.txt
 * (person index, reference identity) and DIR/<floor>/questions.txt (person or
 * -1, group anchor face, other anchor face or -1, group size, mean pair, best
 * match) for phase1.py, which judges them with the reference model.
 */
private fun phase1(
    dir: String, n: Int, dim: Int, metric: Array<FaceMetric.Face>, ids: Array<NativeFaceObservationId>,
    index: Map<NativeFaceObservationId, Int>, photo: IntArray, label: IntArray
) {
    val base = ClusterPolicy.SFACE_MEASURED.copy(metric = null)
    val floors = System.getProperty("floors")?.split(",")?.map { it.toFloat() } ?: listOf(base.personMatchFloor)
    val faces = (0 until n).map { FaceEmbedding(ids[it], "m", metric[it].vector, metric[it].crowd, photo[it] + 1L) }
    var grouped = FaceClusterState.empty("m", dim, base.copy(personMatchFloor = 0f))
    for (chunk in faces.chunked(1024)) grouped = ClusterPublication.publish(grouped, chunk)
    val groupOf = IntArray(n) { -1 }
    grouped.unknownClusters.forEachIndexed { k, c -> for (m in c.members) groupOf[index.getValue(m)] = k }
    val sizes = label.filter { it >= 0 }.groupingBy { it }.eachCount()
    val recurring = sizes.filterValues { it >= 10 }.entries.sortedByDescending { it.value }.map { it.key }
    val taken = HashSet<Int>()
    val named = ArrayList<Pair<Int, Int>>() // group, identity
    for (who in recurring) {
        val best = (0 until n).filter { label[it] == who && groupOf[it] >= 0 && groupOf[it] !in taken }
            .groupingBy { groupOf[it] }.eachCount().maxByOrNull { it.value } ?: continue
        if (grouped.unknownClusters[best.key].members.size < 3) continue
        taken += best.key; named += best.key to who
    }
    val confirmed = HashMap<NativeFaceObservationId, PersonKey>()
    named.forEachIndexed { k, (g, _) -> for (m in grouped.unknownClusters[g].members) confirmed[m] = PersonKey("p$k") }
    println("phase1: recurring identities=${recurring.size} named=${named.size} named faces=${confirmed.size}")
    for (floor in floors) {
        val t = System.nanoTime()
        var state = FaceClusterState.empty("m", dim, base.copy(personMatchFloor = floor))
        for (chunk in faces.chunked(1024)) state = ClusterPublication.publish(state, chunk)
        state = FaceClusterer.update(state, reviews = ClusterReviewFacts(confirmed = confirmed))
        val out = File(dir, "%.2f".format(floor)).apply { mkdirs() }
        val line = Array(n) { "" }
        for ((f, p) in confirmed) line[index.getValue(f)] = "n ${p.value.drop(1)} 0"
        for ((f, m) in state.matched) line[index.getValue(f)] = "m ${m.person.value.drop(1)} ${m.mean}"
        state.unknownClusters.forEachIndexed { k, c -> for (m in c.members) line[index.getValue(m)] = "u $k 0" }
        File(out, "faces.txt").writeText(line.joinToString("\n"))
        File(out, "named.txt").writeText(named.withIndex().joinToString("\n") { (k, v) -> "$k ${v.second}" })
        val forPeople = SamePersonHypotheses.forPeople(state, limit = 10_000)
        val between = SamePersonHypotheses.of(state, limit = 10_000)
        File(out, "questions.txt").writeText((forPeople.map { q ->
            "${q.person.value.drop(1)} ${index.getValue(q.group.anchor)} -1 ${q.groupFaces} ${q.meanPair} ${q.bestMatch}"
        } + between.map { q ->
            "-1 ${index.getValue(q.first.anchor)} ${index.getValue(q.second.anchor)} ${q.firstFaces + q.secondFaces} ${q.meanPair} ${q.bestMatch}"
        }).joinToString("\n"))
        println("floor %.2f: matched=%d unnamed groups=%d person questions=%d group questions=%d  %.1fs".format(
            floor, state.matched.size, state.unknownClusters.size, forPeople.size, between.size, (System.nanoTime() - t) / 1e9))
    }
}
