package nl.atlas.gallery.featurepeople.split

import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featurepeople.cluster.ClusterReviewFacts
import nl.atlas.gallery.featurepeople.cluster.FaceEmbedding
import nl.atlas.gallery.featurepeople.cluster.FacePair
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt
import kotlin.random.Random

/** Logical identities and invented vectors only; this is not a model accuracy test. */
class SplitCalibrationDiagnosticTest {
    private data class Labelled(val face: SplitFace, val truth: Int)
    private data class Profile(val name: String, val seed: Int, val faces: List<Labelled>,
                               val facts: ClusterReviewFacts = ClusterReviewFacts.NONE)
    private data class Placement(val id: NativeFaceObservationId, val score: Float)
    private data class Counts(
        val n: Int, val placed: Int, val right: Int, val wrong: Int, val unsure: Int,
        val unmatchedGroups: Int, val unmatchedTruth: Int, val k: Int, val truthK: Int,
        val baseline: Int, val proposed: Int, val correctBins: List<Int>, val wrongBins: List<Int>
    )

    // Exact maximum-weight partial bipartite matching. The truth side is small and frozen.
    // Stable group order, ascending truth index; ties retain the first choice (skip first).
    // Only positive edges qualify, so a zero-overlap pair is explicitly unmatched.
    private fun match(weights: List<IntArray>, truthK: Int): IntArray {
        require(truthK in 1..10)
        val states = 1 shl truthK
        val value = Array(weights.size + 1) { IntArray(states) }
        val choice = Array(weights.size) { IntArray(states) { -1 } }
        for (g in weights.indices.reversed()) {
            for (mask in 0 until states) {
                value[g][mask] = value[g + 1][mask]
                for (t in 0 until truthK) {
                    val bit = 1 shl t
                    if (mask and bit != 0 || weights[g][t] == 0) continue
                    val candidate = weights[g][t] + value[g + 1][mask or bit]
                    if (candidate > value[g][mask]) {
                        value[g][mask] = candidate
                        choice[g][mask] = t
                    }
                }
            }
        }
        var used = 0
        return IntArray(weights.size) { g ->
            val t = choice[g][used]
            if (t >= 0) used = used or (1 shl t)
            t
        }
    }

    private fun bin(score: Float): Int {
        require(score.isFinite() && score in 0f..1f)
        return when {
            score < 0.2f -> 0
            score < 0.4f -> 1
            score < 0.6f -> 2
            score < 0.8f -> 3
            else -> 4
        }
    }

    private fun measure(truth: Map<NativeFaceObservationId, Int>,
                        groups: List<List<Placement>>, unsure: List<NativeFaceObservationId>): Counts {
        val placedIds = groups.flatten().map { it.id }
        val all = placedIds + unsure
        assertEquals("duplicate or missing observations", truth.size, all.size)
        assertEquals("partition must cover input exactly", truth.keys, all.toSet())
        assertTrue(groups.all { it.isNotEmpty() })
        val labels = truth.values.distinct().sorted()
        val weights = groups.map { g ->
            IntArray(labels.size) { t -> g.count { truth.getValue(it.id) == labels[t] } }
        }
        val matched = match(weights, labels.size)
        val correctBins = IntArray(5)
        val wrongBins = IntArray(5)
        groups.forEachIndexed { g, placements ->
            placements.forEach { p ->
                val correct = matched[g] >= 0 && truth.getValue(p.id) == labels[matched[g]]
                (if (correct) correctBins else wrongBins)[bin(p.score)]++
            }
        }
        val right = correctBins.sum()
        val wrong = wrongBins.sum()
        val placed = placedIds.size
        val baseline = truth.size - truth.values.groupingBy { it }.eachCount().values.max()
        val proposed = truth.size - right
        assertEquals(placed, right + wrong)
        assertEquals(truth.size, placed + unsure.size)
        assertEquals(proposed, wrong + unsure.size)
        assertEquals(right, matched.indices.sumOf { g -> if (matched[g] < 0) 0 else weights[g][matched[g]] })
        val unmatchedGroups = matched.count { it < 0 }
        val unmatchedTruth = labels.size - matched.count { it >= 0 }
        return Counts(truth.size, placed, right, wrong, unsure.size, unmatchedGroups,
            unmatchedTruth, groups.size, labels.size, baseline, proposed,
            correctBins.toList(), wrongBins.toList())
    }

    private fun id(i: Int) = NativeFaceObservationId("%032x".format(i))
    private fun axis(i: Int) = FloatArray(128) { if (it == i) 1f else 0f }

    // Frozen coordinate construction: identity axes 0..7, pose axes 16..39,
    // small seeded jitter on axes 64..127. No learned vectors or model weights.
    private fun profile(name: String, seed: Int, sizes: List<Int>, alike: Boolean = false,
                        poses: Int = 3, veto: Boolean = false): Profile {
        val rng = Random(seed)
        val out = mutableListOf<Labelled>()
        sizes.forEachIndexed { t, size ->
            repeat(size) { j ->
                val v = FloatArray(128)
                if (alike && t == 1) {
                    v[0] = 0.92f
                    v[1] = sqrt(1f - 0.92f * 0.92f)
                } else v[t] = 1f
                v[16 + t * 3 + j % poses] = 0.65f
                for (d in 64 until 128) v[d] = (rng.nextFloat() - 0.5f) * 0.18f
                val norm = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
                for (d in v.indices) v[d] /= norm
                val shared = if (veto && t in 0..1 && j == 0) 42L else 0L
                out += Labelled(SplitFace(FaceEmbedding(id(out.size + 1), MODEL, v), media = shared), t)
            }
        }
        val pairs = if (veto) {
            val a = out.filter { it.truth == 0 }
            val b = out.filter { it.truth == 1 }
            (1..3).map { j -> FacePair.of(a[j].face.embedding.observation, b[j].face.embedding.observation) }.toSet()
        } else emptySet()
        return Profile(name, seed, out.shuffled(rng), ClusterReviewFacts(cannotLink = pairs))
    }

    private fun grid(): List<Profile> = (310..312).flatMap { seed ->
        listOf(
            profile("clean-multi-pose", seed, listOf(72)),
            profile("two-identities", seed, listOf(48, 36)),
            profile("three-identities", seed, listOf(48, 36, 24)),
            profile("imbalanced", seed, listOf(96, 6)),
            profile("lookalikes", seed, listOf(40, 40), alike = true),
            profile("outlier-identities", seed, listOf(64, 1, 1, 1)),
            profile("hard-veto", seed, listOf(36, 36), alike = true, veto = true)
        )
    } + Profile("clean-disjoint-stress", 0, (0..1).map { j ->
        Labelled(SplitFace(FaceEmbedding(id(j + 1), MODEL, axis(j))), 0)
    })

    private fun evaluate(p: Profile): Counts {
        val result = SplitProposer.propose(SplitRequest(p.faces.map { it.face }, p.facts, crowdWeight = 1f))
        val truth = p.faces.associate { it.face.embedding.observation to it.truth }
        val media = p.faces.associate { it.face.embedding.observation to it.face.media }
        for (g in result.groups) {
            val members = g.members.toSet()
            assertEquals(members, g.confidence.keys)
            assertTrue(members.containsAll(g.exemplars))
            assertTrue(g.keepsName == null)
            for (pair in p.facts.cannotLink) assertFalse(pair.first in members && pair.second in members)
            val knownMedia = g.members.map { media.getValue(it) }.filter { it != 0L }
            assertEquals(knownMedia.size, knownMedia.toSet().size)
        }
        for (u in result.unsure) {
            assertTrue(u.confidence.isFinite() && u.confidence in 0f..1f)
            assertTrue(u.nearestGroup == null || u.nearestGroup in result.groups.indices)
        }
        return measure(truth, result.groups.map { g -> g.members.map { Placement(it, g.confidence.getValue(it)) } },
            result.unsure.map { it.observation })
    }

    @Test fun handComputedMatchingAndBins() {
        // g0 = A4 B3, g1 = A3, g2 = B1, Unsure = A1.
        // Greedy majority would keep 4; exact matching keeps B3 + A3 = 6.
        val truth = (1..12).associate { id(it) to if (it in 1..4 || it in 8..10 || it == 12) 0 else 1 }
        val groups = listOf((1..7).map { Placement(id(it), 0.8f) },
            (8..10).map { Placement(id(it), 0.2f) }, listOf(Placement(id(11), 1f)))
        val c = measure(truth, groups, listOf(id(12)))
        assertEquals(Counts(12, 11, 6, 5, 1, 1, 0, 3, 2, 4, 6,
            listOf(0, 3, 0, 0, 3), listOf(0, 0, 0, 0, 5)), c)
        assertArrayEquals(intArrayOf(1, 0, -1), match(listOf(intArrayOf(4, 3), intArrayOf(3, 0), intArrayOf(0, 1)), 2))
        assertArrayEquals(intArrayOf(0, -1), match(listOf(intArrayOf(2, 0), intArrayOf(1, 0)), 2))
        assertArrayEquals(intArrayOf(-1, 0), match(listOf(intArrayOf(1), intArrayOf(1)), 1))
        assertEquals(listOf(0, 0, 1, 2, 3, 4, 4), listOf(0f, 0.199f, 0.2f, 0.4f, 0.6f, 0.8f, 1f).map(::bin))
        val abstain = measure(mapOf(id(1) to 0, id(2) to 0), emptyList(), listOf(id(1), id(2)))
        assertEquals(Counts(2, 0, 0, 0, 2, 0, 1, 0, 1, 0, 2,
            List(5) { 0 }, List(5) { 0 }), abstain)
    }

    private fun fraction(n: Int, d: Int) = if (d == 0) "N/A" else "$n/$d"
    private fun report(label: String, rows: List<Counts>) {
        val n = rows.sumOf { it.n }
        val placed = rows.sumOf { it.placed }
        val right = rows.sumOf { it.right }
        val wrong = rows.sumOf { it.wrong }
        val unsure = rows.sumOf { it.unsure }
        val clean = rows.filter { it.truthK == 1 }
        val bins = (0..4).joinToString(",") { b ->
            val yes = rows.sumOf { it.correctBins[b] }
            val no = rows.sumOf { it.wrongBins[b] }
            "$b:correct=$yes/wrong=$no/accuracy=" + fraction(yes, yes + no)
        }
        assertEquals(n, placed + unsure)
        assertEquals(placed, right + wrong)
        assertEquals(rows.sumOf { it.proposed }, wrong + unsure)
        println("SPLIT_CALIBRATION $label profiles=${rows.size} N=$n placed=$placed correct=$right wrong=$wrong " +
            "unsure=$unsure accuracy=${fraction(right, placed)} coverage=${fraction(placed, n)} " +
            "matchedCoverage=${fraction(right, n)} kAccuracy=${fraction(rows.count { it.k == it.truthK }, rows.size)} " +
            "cleanFalseSplit=${fraction(clean.count { it.k >= 2 }, clean.size)} " +
            "unmatchedGroups=${rows.sumOf { it.unmatchedGroups }}/${rows.sumOf { it.k }} " +
            "unmatchedTruth=${rows.sumOf { it.unmatchedTruth }}/${rows.sumOf { it.truthK }} " +
            "baseline=${rows.sumOf { it.baseline }} proposed=${rows.sumOf { it.proposed }} " +
            "worsened=${rows.count { it.proposed > it.baseline }}/${rows.size} bins=$bins")
    }

    @Test fun frozenSyntheticGrid() {
        println("SPLIT_CALIBRATION model=$MODEL dim=128 crowdWeight=1 " +
            "edge=${SplitProposer.EDGE_FLOOR} knn=${SplitProposer.KNN} join=${SplitProposer.JOIN_FLOOR} gain=${SplitProposer.GAIN_MIN} " +
            "margin=${SplitProposer.UNSURE_MARGIN} outlier=${SplitProposer.OUTLIER_FLOOR} confident=${SplitProposer.CONFIDENT_MARGIN} " +
            "sessionWeight=${SplitProposer.SESSION_WEIGHT} sessionMillis=${SplitProposer.SESSION_MILLIS} nameShare=${SplitProposer.NAME_SHARE} " +
            "core=${SplitProposer.CORE_MAX} refs=${SplitProposer.REF_MAX} top=${SplitProposer.TOP_M} exemplars=${SplitProposer.EXEMPLARS} max=${SplitProposer.MAX_FACES}")
        val rows = grid().map { p ->
            val c = evaluate(p)
            report("profile=${p.name} seed=${p.seed} k=${c.k} truthK=${c.truthK}", listOf(c))
            p.name to c
        }
        rows.groupBy { it.first }.forEach { (name, cases) -> report("kind=$name", cases.map { it.second }) }
        report("pooled", rows.map { it.second })
    }

    companion object { private const val MODEL = "synthetic-axis-128-v1" }
}
