package nl.atlas.gallery.featurepeople.split

import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featurepeople.cluster.ClusterReviewFacts
import nl.atlas.gallery.featurepeople.cluster.FaceEmbedding
import nl.atlas.gallery.featurepeople.cluster.FacePair
import nl.atlas.gallery.featurepeople.cluster.PersonKey
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * #96 / SPLIT-0 (#105): "this person is really two (or three) people".
 *
 * Given the faces of one person profile or one unnamed group, proposes how it
 * divides. It only proposes: nothing here writes a fact, a name or a store
 * row, and nothing reads pixels, files or names. The caller shows the
 * proposal; only the owner's accept turns it into review facts.
 *
 * How it decides (all on vectors that already went through the grouping
 * metric, e.g. `ClusterPolicy.DEFAULT.embedding(...)`, so a cosine of 0 is a
 * typical stranger):
 * 1. A k-nearest-neighbour graph over the faces, edge weight the
 *    crowd-corrected cosine above [EDGE_FLOOR]. Cannot-link facts and two
 *    faces from one picture are hard separators: they never share an edge,
 *    a community or a proposed group.
 * 2. Louvain community detection on that graph (deterministic node order):
 *    many small, tight communities (poses, sessions, ages).
 * 3. Communities are joined by mean-pair linkage, the rule grouping itself
 *    uses, while the best pair still scores [JOIN_FLOOR] or more. What is
 *    left are candidate identities.
 * 4. k is chosen by a quality gain: every proposed group must be more
 *    cohesive inside than towards its nearest other group by [GAIN_MIN]; a
 *    group that is not is folded into its nearest partner. k = 1 means "no
 *    clear split".
 * 5. Every face is scored against each group's exemplar set (the mean of
 *    its [TOP_M] best matches among up to [REF_MAX] central members, so a
 *    profile pose still finds its own person), with a weak same-session
 *    tiebreak. Faces without a clear margin, or matching no group, go to
 *    the Unsure tray.
 * 6. A confirmed name stays only with the group that holds clearly most of
 *    that person's confirmed faces ([NAME_SHARE]); it is never given to a
 *    group without confirmed faces of that person.
 *
 * Deterministic: the result depends only on the set of faces and facts, not
 * on their order. Bounded: at most [CORE_MAX] faces enter steps 1-4, the rest
 * are only scored in step 5.
 */
object SplitProposer {
    /** Corrected cosine above which two faces share a graph edge. */
    const val EDGE_FLOOR = 0.10f
    /** Neighbours per face in the graph. */
    const val KNN = 12
    /** Two candidate identities are joined while their mean pair is at least this. */
    const val JOIN_FLOOR = 0.20f
    /** A group must be at least this much more cohesive inside than towards its nearest group. */
    const val GAIN_MIN = 0.12f
    /** Below this margin between the best and second group a face is Unsure. */
    const val UNSURE_MARGIN = 0.06f
    /** A face scoring below this against every group is Unsure (likely someone else). */
    const val OUTLIER_FLOOR = 0.18f
    /** A margin of this much counts as full confidence. */
    const val CONFIDENT_MARGIN = 0.25f
    /** Same-session neighbours add at most this to a face's score for their group. */
    const val SESSION_WEIGHT = 0.03f
    /** Two faces within this time of each other are one session. */
    const val SESSION_MILLIS = 12L * 60 * 60 * 1000
    /** A name stays with a group only if it holds this share of the name's confirmed faces. */
    const val NAME_SHARE = 2f / 3f
    const val CORE_MAX = 1_500
    const val REF_MAX = 48
    const val TOP_M = 7
    const val EXEMPLARS = 6
    const val MAX_FACES = 50_000
    private const val DIVERSITY = 0.5f
    private const val LOUVAIN_PASSES = 32
    private const val LOUVAIN_LEVELS = 12

    fun propose(request: SplitRequest): SplitProposal {
        val input = request.faces
        require(input.size <= MAX_FACES) { "Too many faces for one split" }
        if (input.isEmpty()) return SplitProposal(emptyList(), emptyList(), 0f)
        val faces = input.sortedBy { it.embedding.observation.localHex }
        for (i in 1 until faces.size) require(faces[i].embedding.observation != faces[i - 1].embedding.observation) {
            "A face appears twice"
        }
        val dim = faces[0].embedding.dimension
        require(faces.all { it.embedding.dimension == dim }) { "Mixed embedding dimensions" }
        val n = faces.size
        val cw = request.crowdWeight
        require(cw.isFinite() && cw in 0f..4f) { "Crowd weight must be in [0, 4]" }
        val vec = Array(n) { faces[it].embedding.unit }
        val crowd = FloatArray(n) { faces[it].embedding.crowd * cw }
        val index = HashMap<NativeFaceObservationId, Int>(n * 2)
        faces.forEachIndexed { i, f -> index[f.embedding.observation] = i }
        val veto = vetoPairs(faces, index, request.facts.cannotLink)
        val vetoOf = Array(n) { IntList() }
        for ((a, b) in veto) { vetoOf[a].add(b); vetoOf[b].add(a) }

        // Steps 1-4 on a deterministic core.
        val core = coreOf(n, vetoOf)
        val groups = candidateGroups(core, vec, crowd, vetoOf, dim)
        val minSize = minGroupSize(n)
        var significant = groups.filter { it.size >= minSize }
        if (significant.isEmpty()) significant = listOf(Group(IntList().also { l -> core.forEach(l::add) }))
        significant = foldWeak(significant, vec, crowd, vetoOf, dim)
        val k = significant.size

        // Step 5: score every face against each group's exemplar set.
        val refs = significant.map { referencesOf(it, vec, dim) }
        val scores = Array(n) { i -> FloatArray(k) { g -> topMatch(i, refs[g], vec, crowd) } }
        val first = IntArray(n) { i -> argBest(scores[i], -1) }
        if (k >= 2) addSessionBonus(faces, first, scores, k)
        val assignment = assign(n, k, scores, vetoOf)

        // Step 6 and output.
        val members = Array(k) { IntList() }
        val unsure = ArrayList<UnsureFace>()
        for (i in 0 until n) {
            val a = assignment[i]
            if (a.group >= 0) members[a.group].add(i)
            else unsure += UnsureFace(faces[i].embedding.observation, a.best.takeIf { it >= 0 },
                a.confidence, a.reason!!)
        }
        val keeps = keptNames(faces, assignment, k)
        // A face the owner said is NOT the kept name never stays in that group.
        val rejected = request.facts.rejectedPeople.groupBy({ it.observation }, { it.person })
        for (g in 0 until k) {
            val name = keeps[g] ?: continue
            val kept = IntList()
            for (j in 0 until members[g].size) {
                val i = members[g][j]
                if (rejected[faces[i].embedding.observation]?.contains(name) == true) {
                    unsure += UnsureFace(faces[i].embedding.observation, g, assignment[i].confidence,
                        UnsureReason.REJECTED_FOR_NAME)
                } else kept.add(i)
            }
            members[g] = kept
        }
        val built = ArrayList<Pair<Int, ProposedGroup>>(k)
        for (g in 0 until k) {
            val m = members[g]
            if (m.size == 0) continue
            val ids = (0 until m.size).map { faces[m[it]].embedding.observation }
            val confirmedPeople = HashMap<PersonKey, Int>()
            for (j in 0 until m.size) faces[m[j]].confirmedAs?.let { confirmedPeople.merge(it, 1, Int::plus) }
            built += g to ProposedGroup(
                exemplars = exemplarsOf(m, vec, dim).map { faces[it].embedding.observation },
                members = ids,
                confirmedCount = confirmedPeople.values.sum(),
                confirmedPeople = confirmedPeople,
                keepsName = keeps[g],
                cohesion = cohesionOf(m, vec, crowd, dim),
                confidence = (0 until m.size).associate {
                    faces[m[it]].embedding.observation to assignment[m[it]].confidence
                }
            )
        }
        built.sortWith(compareByDescending<Pair<Int, ProposedGroup>> { it.second.members.size }
            .thenBy { it.second.members.first().localHex })
        val position = IntArray(k) { -1 }
        built.forEachIndexed { pos, (g, _) -> position[g] = pos }
        val out = built.map { it.second }
        val unsureOut = unsure.map { u -> u.copy(nearestGroup = u.nearestGroup?.let { position[it] }?.takeIf { it >= 0 }) }
            .sortedBy { it.observation.localHex }
        val separation = if (out.size < 2) 0f else {
            var worst = -1f
            for (a in 0 until k) for (b in a + 1 until k) {
                if (members[a].size == 0 || members[b].size == 0) continue
                worst = max(worst, meanPair(members[a], members[b], vec, crowd, dim))
            }
            worst
        }
        return SplitProposal(out, unsureOut, separation)
    }

    // ---- constraints -------------------------------------------------------

    private fun vetoPairs(
        faces: List<SplitFace>, index: Map<NativeFaceObservationId, Int>, cannotLink: Set<FacePair>
    ): List<Pair<Int, Int>> {
        val out = LinkedHashSet<Pair<Int, Int>>()
        for (p in cannotLink) {
            val a = index[p.first] ?: continue
            val b = index[p.second] ?: continue
            out += min(a, b) to max(a, b)
        }
        // Two faces in one picture are two people.
        val byMedia = HashMap<Long, IntList>()
        faces.forEachIndexed { i, f -> if (f.media != 0L) byMedia.getOrPut(f.media) { IntList() }.add(i) }
        for (list in byMedia.values) for (x in 0 until list.size) for (y in x + 1 until list.size) {
            out += min(list[x], list[y]) to max(list[x], list[y])
        }
        return out.sortedWith(compareBy({ it.first }, { it.second }))
    }

    private fun vetoed(a: IntList, bSet: BooleanArray): Boolean {
        for (j in 0 until a.size) if (bSet[a[j]]) return true
        return false
    }

    // ---- steps 1-4 --------------------------------------------------------

    /** All faces, or an even stride of them plus every face with a veto fact. */
    private fun coreOf(n: Int, vetoOf: Array<IntList>): IntArray {
        if (n <= CORE_MAX) return IntArray(n) { it }
        val pick = BooleanArray(n)
        val step = n.toDouble() / CORE_MAX
        for (j in 0 until CORE_MAX) pick[(j * step).toInt()] = true
        var extra = 0
        for (i in 0 until n) if (vetoOf[i].size > 0 && !pick[i] && extra < CORE_MAX / 4) { pick[i] = true; extra++ }
        return (0 until n).filter { pick[it] }.toIntArray()
    }

    private class Group(val faces: IntList) {
        val size get() = faces.size
    }

    private fun candidateGroups(
        core: IntArray, vec: Array<FloatArray>, crowd: FloatArray, vetoOf: Array<IntList>, dim: Int
    ): List<Group> {
        val m = core.size
        val local = HashMap<Int, Int>(m * 2).also { map -> core.forEachIndexed { j, i -> map[i] = j } }
        val vetoLocal = Array(m) { IntList() }
        for (j in 0 until m) { val v = vetoOf[core[j]]; for (x in 0 until v.size) local[v[x]]?.let { vetoLocal[j].add(it) } }

        // 1. kNN graph, symmetrised by max.
        val k = min(KNN, m - 1)
        val adj = Array(m) { HashMap<Int, Float>() }
        if (k > 0) {
            val sims = FloatArray(m)
            val vetoMark = BooleanArray(m)
            for (a in 0 until m) {
                val va = vec[core[a]]
                for (b in 0 until m) sims[b] = if (b == a) Float.NEGATIVE_INFINITY else
                    dot(va, vec[core[b]]) - (crowd[core[a]] + crowd[core[b]]) / 2f
                for (x in 0 until vetoLocal[a].size) vetoMark[vetoLocal[a][x]] = true
                for (b in topIndices(sims, k)) {
                    if (vetoMark[b]) continue
                    val w = sims[b] - EDGE_FLOOR
                    if (w <= 0f) continue
                    if ((adj[a][b] ?: 0f) < w) { adj[a][b] = w; adj[b][a] = w }
                }
                for (x in 0 until vetoLocal[a].size) vetoMark[vetoLocal[a][x]] = false
            }
        }
        // 2. Louvain.
        val community = louvain(m, adj)
        val byCommunity = sortedMapOf<Int, IntList>()
        for (j in 0 until m) byCommunity.getOrPut(community[j]) { IntList() }.add(j)
        // A community never holds a vetoed pair: eject the weaker face of such a pair.
        val parts = ArrayList<IntList>()
        for (c in byCommunity.values) parts += splitVetoed(c, vetoLocal, core, vec, dim)

        // 3. Mean-pair joining with cached sums.
        val p = parts.size
        val sums = Array(p) { DoubleArray(dim) }
        val crowdSum = DoubleArray(p)
        val count = IntArray(p)
        val members = Array(p) { IntList() }
        for (c in 0 until p) for (x in 0 until parts[c].size) {
            val i = core[parts[c][x]]
            for (d in 0 until dim) sums[c][d] += vec[i][d].toDouble()
            crowdSum[c] += crowd[i].toDouble()
            count[c]++
            members[c].add(i)
        }
        val alive = BooleanArray(p) { true }
        val vetoM = Array(p) { BooleanArray(p) }
        val owner = IntArray(m)
        for (c in 0 until p) for (x in 0 until parts[c].size) owner[parts[c][x]] = c
        for (j in 0 until m) for (x in 0 until vetoLocal[j].size) {
            val a = owner[j]; val b = owner[vetoLocal[j][x]]
            vetoM[a][b] = true; vetoM[b][a] = true
        }
        fun score(a: Int, b: Int): Float {
            var s = 0.0
            for (d in 0 until dim) s += sums[a][d] * sums[b][d]
            return (s / (count[a].toDouble() * count[b]) -
                (crowdSum[a] / count[a] + crowdSum[b] / count[b]) / 2).toFloat()
        }
        val pair = Array(p) { FloatArray(p) }
        for (a in 0 until p) for (b in a + 1 until p) { val s = score(a, b); pair[a][b] = s; pair[b][a] = s }
        while (true) {
            var bestA = -1; var bestB = -1; var best = JOIN_FLOOR
            for (a in 0 until p) if (alive[a]) for (b in a + 1 until p) {
                if (!alive[b] || vetoM[a][b]) continue
                if (pair[a][b] >= best && (bestA < 0 || pair[a][b] > best)) { best = pair[a][b]; bestA = a; bestB = b }
            }
            if (bestA < 0) break
            // Fold bestB into bestA.
            for (d in 0 until dim) sums[bestA][d] += sums[bestB][d]
            crowdSum[bestA] += crowdSum[bestB]
            count[bestA] += count[bestB]
            for (x in 0 until members[bestB].size) members[bestA].add(members[bestB][x])
            alive[bestB] = false
            for (c in 0 until p) {
                if (vetoM[bestB][c]) { vetoM[bestA][c] = true; vetoM[c][bestA] = true }
                if (alive[c] && c != bestA) { val s = score(bestA, c); pair[bestA][c] = s; pair[c][bestA] = s }
            }
        }
        val out = ArrayList<Group>()
        for (c in 0 until p) if (alive[c]) out += Group(members[c].sorted())
        out.sortWith(compareByDescending<Group> { it.size }.thenBy { it.faces[0] })
        return out
    }

    private fun splitVetoed(
        community: IntList, vetoLocal: Array<IntList>, core: IntArray, vec: Array<FloatArray>, dim: Int
    ): List<IntList> {
        val inside = HashSet<Int>().also { s -> for (x in 0 until community.size) s += community[x] }
        val ejected = ArrayList<IntList>()
        while (true) {
            var conflict: Pair<Int, Int>? = null
            loop@ for (j in inside.sorted()) for (x in 0 until vetoLocal[j].size) {
                val o = vetoLocal[j][x]
                if (o in inside) { conflict = j to o; break@loop }
            }
            val (a, b) = conflict ?: break
            val sum = DoubleArray(dim)
            for (j in inside) for (d in 0 until dim) sum[d] += vec[core[j]][d].toDouble()
            fun fit(j: Int): Double { var s = 0.0; for (d in 0 until dim) s += sum[d] * vec[core[j]][d]; return s }
            val drop = if (fit(a) < fit(b) || (fit(a) == fit(b) && a > b)) a else b
            inside -= drop
            ejected += IntList().also { it.add(drop) }
        }
        val kept = IntList(); for (j in inside.sorted()) kept.add(j)
        return listOf(kept) + ejected
    }

    /** Deterministic Louvain (modularity, resolution 1) on a weighted undirected graph. */
    internal fun louvain(n: Int, adj: Array<HashMap<Int, Float>>): IntArray {
        var label = IntArray(n) { it }
        var nodes = n
        var graph: Array<Map<Int, Double>> = Array(n) { a -> adj[a].mapValues { it.value.toDouble() } }
        repeat(LOUVAIN_LEVELS) {
            val degree = DoubleArray(nodes) { a -> graph[a].values.sum() }
            val total = degree.sum()
            if (total <= 0.0) return compact(label)
            val comm = IntArray(nodes) { it }
            val tot = degree.copyOf()
            var moved = false
            for (pass in 0 until LOUVAIN_PASSES) {
                var any = false
                for (a in 0 until nodes) {
                    val links = HashMap<Int, Double>()
                    for ((b, w) in graph[a]) if (b != a) links.merge(comm[b], w, Double::plus)
                    val own = comm[a]
                    tot[own] -= degree[a]
                    var bestC = own
                    var bestGain = (links[own] ?: 0.0) - tot[own] * degree[a] / total
                    for (c in links.keys.sorted()) {
                        val gain = links[c]!! - tot[c] * degree[a] / total
                        if (gain > bestGain + 1e-12) { bestGain = gain; bestC = c }
                    }
                    tot[bestC] += degree[a]
                    if (bestC != own) { comm[a] = bestC; any = true; moved = true }
                }
                if (!any) break
            }
            if (!moved) return compact(label)
            val renum = compact(comm)
            val next = renum.max() + 1
            label = IntArray(n) { renum[label[it]] }
            val agg = Array(next) { HashMap<Int, Double>() }
            for (a in 0 until nodes) for ((b, w) in graph[a]) agg[renum[a]].merge(renum[b], w, Double::plus)
            graph = Array(next) { agg[it] }
            nodes = next
        }
        return compact(label)
    }

    private fun compact(label: IntArray): IntArray {
        val map = HashMap<Int, Int>()
        return IntArray(label.size) { map.getOrPut(label[it]) { map.size } }
    }

    private fun minGroupSize(n: Int): Int = min(25, max(3, (n * 0.02).roundToInt()))

    /** Step 4: fold groups that are not clearly their own person into their nearest partner. */
    private fun foldWeak(
        start: List<Group>, vec: Array<FloatArray>, crowd: FloatArray, vetoOf: Array<IntList>, dim: Int
    ): List<Group> {
        val groups = start.toMutableList()
        while (groups.size >= 2) {
            var worstMargin = Float.POSITIVE_INFINITY; var worst = -1; var partner = -1
            for (a in groups.indices) {
                val coh = cohesionOf(groups[a].faces, vec, crowd, dim)
                val mark = BooleanArray(vec.size); for (x in 0 until groups[a].size) mark[groups[a].faces[x]] = true
                var near = -1; var nearScore = Float.NEGATIVE_INFINITY
                for (b in groups.indices) if (b != a) {
                    // A fact between the two already says they are different people.
                    var blocked = false
                    for (x in 0 until groups[b].size) if (vetoed(vetoOf[groups[b].faces[x]], mark)) { blocked = true; break }
                    if (blocked) continue
                    val s = meanPair(groups[a].faces, groups[b].faces, vec, crowd, dim)
                    if (s > nearScore) { nearScore = s; near = b }
                }
                if (near < 0) continue
                val margin = coh - nearScore
                if (margin < worstMargin) { worstMargin = margin; worst = a; partner = near }
            }
            if (worst < 0 || worstMargin >= GAIN_MIN) break
            val a = groups[worst]; val b = groups[partner]
            val joined = IntList(); for (x in 0 until a.size) joined.add(a.faces[x]); for (x in 0 until b.size) joined.add(b.faces[x])
            groups.remove(a); groups.remove(b); groups += Group(joined.sorted())
            groups.sortWith(compareByDescending<Group> { it.size }.thenBy { it.faces[0] })
        }
        return groups
    }

    // ---- step 5 -----------------------------------------------------------

    /** Up to [REF_MAX] most central members, evenly spread over the centrality order. */
    private fun referencesOf(group: Group, vec: Array<FloatArray>, dim: Int): IntArray {
        val sum = DoubleArray(dim)
        for (x in 0 until group.size) { val v = vec[group.faces[x]]; for (d in 0 until dim) sum[d] += v[d].toDouble() }
        val order = (0 until group.size).map { group.faces[it] }
            .sortedWith(compareByDescending<Int> { var s = 0.0; val v = vec[it]; for (d in 0 until dim) s += sum[d] * v[d]; s }
                .thenBy { it })
        if (order.size <= REF_MAX) return order.toIntArray()
        // Take the central 2/3 evenly so the set covers poses, not only the densest one.
        val pool = order.subList(0, max(REF_MAX, order.size * 2 / 3))
        return IntArray(REF_MAX) { pool[(it.toLong() * pool.size / REF_MAX).toInt()] }
    }

    private fun topMatch(i: Int, refs: IntArray, vec: Array<FloatArray>, crowd: FloatArray): Float {
        val best = FloatArray(min(TOP_M, refs.size)) { Float.NEGATIVE_INFINITY }
        var used = 0
        for (r in refs) {
            if (r == i) continue
            val s = dot(vec[i], vec[r]) - (crowd[i] + crowd[r]) / 2f
            if (used < best.size) { best[used++] = s; continue }
            var lo = 0
            for (x in 1 until best.size) if (best[x] < best[lo]) lo = x
            if (s > best[lo]) best[lo] = s
        }
        if (used == 0) return 0f
        var s = 0f; for (x in 0 until used) s += best[x]
        return s / used
    }

    private fun addSessionBonus(faces: List<SplitFace>, first: IntArray, scores: Array<FloatArray>, k: Int) {
        val timed = faces.indices.filter { faces[it].capturedAtMillis > 0L }
            .sortedWith(compareBy({ faces[it].capturedAtMillis }, { it }))
        if (timed.size < 2) return
        val times = LongArray(timed.size) { faces[timed[it]].capturedAtMillis }
        val prefix = Array(k) { IntArray(timed.size + 1) }
        for (x in timed.indices) for (g in 0 until k) prefix[g][x + 1] = prefix[g][x] + if (first[timed[x]] == g) 1 else 0
        val byMedia = HashMap<Long, IntList>()
        faces.forEachIndexed { i, f -> if (f.media != 0L) byMedia.getOrPut(f.media) { IntList() }.add(i) }
        for (x in timed.indices) {
            val i = timed[x]
            val lo = lowerBound(times, times[x] - SESSION_MILLIS)
            val hi = upperBound(times, times[x] + SESSION_MILLIS)
            val counts = IntArray(k) { g -> prefix[g][hi] - prefix[g][lo] }
            if (first[i] >= 0) counts[first[i]]--
            // Others in the same picture are other people, not session evidence.
            byMedia[faces[i].media]?.let { same ->
                for (y in 0 until same.size) {
                    val o = same[y]
                    if (o != i && faces[o].capturedAtMillis > 0L && abs(faces[o].capturedAtMillis - times[x]) <= SESSION_MILLIS &&
                        first[o] >= 0) counts[first[o]]--
                }
            }
            val total = counts.sum()
            if (total <= 0) continue
            for (g in 0 until k) scores[i][g] += SESSION_WEIGHT * counts[g] / total
        }
    }

    private class Assignment(val group: Int, val best: Int, val confidence: Float, val reason: UnsureReason?)

    private fun assign(n: Int, k: Int, scores: Array<FloatArray>, vetoOf: Array<IntList>): Array<Assignment> {
        val out = arrayOfNulls<Assignment>(n)
        if (k == 0) {
            for (i in 0 until n) out[i] = Assignment(-1, -1, 0f, UnsureReason.NO_GROUP)
            @Suppress("UNCHECKED_CAST") return out as Array<Assignment>
        }
        val margin = FloatArray(n) { i ->
            val s = scores[i]; val b = argBest(s, -1); val c = argBest(s, b)
            if (c < 0) s[b] else s[b] - s[c]
        }
        // Surest faces first, so a vetoed partner is placed by the stronger evidence.
        val order = (0 until n).sortedWith(compareByDescending<Int> { margin[it] }.thenBy { it })
        val placed = IntArray(n) { -1 }
        for (i in order) {
            val s = scores[i]
            val blocked = BooleanArray(k)
            for (x in 0 until vetoOf[i].size) { val g = placed[vetoOf[i][x]]; if (g >= 0) blocked[g] = true }
            val rawBest = argBest(s, -1)
            var b = -1; var c = -1
            for (g in 0 until k) if (!blocked[g]) {
                if (b < 0 || s[g] > s[b]) { c = b; b = g } else if (c < 0 || s[g] > s[c]) c = g
            }
            if (b < 0) { out[i] = Assignment(-1, rawBest, 0f, UnsureReason.CONFLICT); continue }
            if (s[b] < OUTLIER_FLOOR) {
                out[i] = Assignment(-1, b, clamp01(s[b] / OUTLIER_FLOOR) * 0.5f, UnsureReason.OUTLIER); continue
            }
            if (k == 1) {
                out[i] = Assignment(b, b, clamp01((s[b] - OUTLIER_FLOOR) / CONFIDENT_MARGIN), null)
                placed[i] = b; continue
            }
            val m = if (c < 0) CONFIDENT_MARGIN else s[b] - s[c]
            val conf = clamp01(m / CONFIDENT_MARGIN)
            if (m < UNSURE_MARGIN) { out[i] = Assignment(-1, b, conf, UnsureReason.LOW_MARGIN); continue }
            out[i] = Assignment(b, b, conf, null)
            placed[i] = b
        }
        @Suppress("UNCHECKED_CAST") return out as Array<Assignment>
    }

    // ---- step 6 -----------------------------------------------------------

    private fun keptNames(faces: List<SplitFace>, assignment: Array<Assignment>, k: Int): Array<PersonKey?> {
        val keeps = arrayOfNulls<PersonKey>(k)
        val perName = sortedMapOf<String, IntArray>()
        val keyOf = HashMap<String, PersonKey>()
        val confirmedIn = IntArray(k)
        for (i in faces.indices) {
            val p = faces[i].confirmedAs ?: continue
            keyOf[p.value] = p
            val counts = perName.getOrPut(p.value) { IntArray(k + 1) }
            val g = assignment[i].group
            if (g >= 0) { counts[g]++; confirmedIn[g]++ } else counts[k]++
        }
        val claims = ArrayList<Triple<Int, String, Int>>()
        for ((name, counts) in perName) {
            val total = counts.sum()
            var g = -1
            for (x in 0 until k) if (g < 0 || counts[x] > counts[g]) g = x
            if (g < 0 || counts[g] == 0) continue
            if (counts[g] < ceil(total * NAME_SHARE).toInt()) continue
            if (counts[g] * 2 < confirmedIn[g]) continue
            claims += Triple(g, name, counts[g])
        }
        // At most one name per group: the one with most confirmed faces there; ties keep none.
        for (g in 0 until k) {
            val c = claims.filter { it.first == g }.sortedByDescending { it.third }
            if (c.isEmpty()) continue
            if (c.size >= 2 && c[0].third == c[1].third) continue
            keeps[g] = keyOf[c[0].second]
        }
        return keeps
    }

    private fun exemplarsOf(m: IntList, vec: Array<FloatArray>, dim: Int): List<Int> {
        val sum = DoubleArray(dim)
        for (x in 0 until m.size) { val v = vec[m[x]]; for (d in 0 until dim) sum[d] += v[d].toDouble() }
        val n = m.size.toDouble()
        val central = HashMap<Int, Float>()
        for (x in 0 until m.size) {
            val i = m[x]; var s = 0.0; for (d in 0 until dim) s += sum[d] * vec[i][d]
            central[i] = (if (m.size > 1) (s - 1.0) / (n - 1) else 1.0).toFloat()
        }
        val pool = (0 until m.size).map { m[it] }.sortedWith(compareByDescending<Int> { central[it]!! }.thenBy { it })
            .take(32)
        val chosen = ArrayList<Int>()
        while (chosen.size < min(EXEMPLARS, pool.size)) {
            var best = -1; var bestScore = Float.NEGATIVE_INFINITY
            for (c in pool) {
                if (c in chosen) continue
                var near = 0f
                for (o in chosen) near = max(near, dot(vec[c], vec[o]))
                val s = central[c]!! - DIVERSITY * near
                if (s > bestScore) { bestScore = s; best = c }
            }
            chosen += best
        }
        return chosen
    }

    // ---- maths ------------------------------------------------------------

    private fun cohesionOf(m: IntList, vec: Array<FloatArray>, crowd: FloatArray, dim: Int): Float {
        if (m.size < 2) return 1f
        val sum = DoubleArray(dim); var c = 0.0
        for (x in 0 until m.size) { val v = vec[m[x]]; for (d in 0 until dim) sum[d] += v[d].toDouble(); c += crowd[m[x]] }
        var sq = 0.0; var self = 0.0
        for (d in 0 until dim) sq += sum[d] * sum[d]
        for (x in 0 until m.size) self += dot(vec[m[x]], vec[m[x]]).toDouble()
        val n = m.size.toDouble()
        return ((sq - self) / (n * (n - 1)) - c / n).toFloat()
    }

    private fun meanPair(a: IntList, b: IntList, vec: Array<FloatArray>, crowd: FloatArray, dim: Int): Float {
        val sa = DoubleArray(dim); val sb = DoubleArray(dim); var ca = 0.0; var cb = 0.0
        for (x in 0 until a.size) { val v = vec[a[x]]; for (d in 0 until dim) sa[d] += v[d].toDouble(); ca += crowd[a[x]] }
        for (x in 0 until b.size) { val v = vec[b[x]]; for (d in 0 until dim) sb[d] += v[d].toDouble(); cb += crowd[b[x]] }
        var s = 0.0; for (d in 0 until dim) s += sa[d] * sb[d]
        return (s / (a.size.toDouble() * b.size) - (ca / a.size + cb / b.size) / 2).toFloat()
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (d in a.indices) s += a[d] * b[d]
        return s
    }

    private fun topIndices(values: FloatArray, k: Int): IntArray {
        val idx = IntArray(k) { -1 }
        for (b in values.indices) {
            val v = values[b]
            if (v == Float.NEGATIVE_INFINITY) continue
            var pos = k
            while (pos > 0 && (idx[pos - 1] < 0 || values[idx[pos - 1]] < v)) pos--
            if (pos >= k) continue
            for (x in k - 1 downTo pos + 1) idx[x] = idx[x - 1]
            idx[pos] = b
        }
        return idx.filter { it >= 0 }.toIntArray()
    }

    private fun argBest(s: FloatArray, except: Int): Int {
        var b = -1
        for (g in s.indices) if (g != except && (b < 0 || s[g] > s[b])) b = g
        return b
    }

    private fun lowerBound(a: LongArray, v: Long): Int { var lo = 0; var hi = a.size; while (lo < hi) { val m = (lo + hi) ushr 1; if (a[m] < v) lo = m + 1 else hi = m }; return lo }
    private fun upperBound(a: LongArray, v: Long): Int { var lo = 0; var hi = a.size; while (lo < hi) { val m = (lo + hi) ushr 1; if (a[m] <= v) lo = m + 1 else hi = m }; return lo }
    private fun clamp01(x: Float) = x.coerceIn(0f, 1f)
}

/**
 * One face of the profile being split. [embedding] must already be in the
 * grouping metric's space (`ClusterPolicy.embedding`). [confirmedAs] is the
 * person the owner confirmed it as, if any. [capturedAtMillis] 0 = unknown.
 * [media] is an opaque key of the photo or video frame, 0 = unknown; two
 * faces with the same non-zero key are two people.
 */
class SplitFace(
    val embedding: FaceEmbedding,
    val confirmedAs: PersonKey? = null,
    val capturedAtMillis: Long = 0L,
    val media: Long = 0L
)

class SplitRequest(
    val faces: List<SplitFace>,
    val facts: ClusterReviewFacts = ClusterReviewFacts.NONE,
    /** As `ClusterPolicy.crowdWeight` for the policy that produced the vectors. */
    val crowdWeight: Float = 1f
)

/**
 * One proposed identity. [exemplars] are the most central yet mutually
 * different faces (portraits for the preview). [confidence] per member is in
 * [0, 1]. [keepsName] is the confirmed person this group keeps, or null:
 * then the group becomes a new unnamed person if the owner accepts, and any
 * [confirmedPeople] it holds must be shown as a question, never moved.
 */
data class ProposedGroup(
    val exemplars: List<NativeFaceObservationId>,
    val members: List<NativeFaceObservationId>,
    val confirmedCount: Int,
    val confirmedPeople: Map<PersonKey, Int>,
    val keepsName: PersonKey?,
    val cohesion: Float,
    val confidence: Map<NativeFaceObservationId, Float>
)

enum class UnsureReason {
    /** Close to two groups. */
    LOW_MARGIN,
    /** Resembles no group: likely someone else entirely. */
    OUTLIER,
    /** A cannot-link fact or a shared picture rules out every group it fits. */
    CONFLICT,
    /** The owner said this face is not the name its group keeps. */
    REJECTED_FOR_NAME,
    /** No group could be formed. */
    NO_GROUP
}

data class UnsureFace(
    val observation: NativeFaceObservationId,
    /** Index into [SplitProposal.groups] of the group it fits best, null when none. */
    val nearestGroup: Int?,
    val confidence: Float,
    val reason: UnsureReason
)

/**
 * [groups] largest first. One group means "no clear split" ([isSplit] false).
 * [separation] is the highest mean pair between two proposed groups (lower
 * is a cleaner split; 0 with fewer than two groups).
 */
data class SplitProposal(
    val groups: List<ProposedGroup>,
    val unsure: List<UnsureFace>,
    val separation: Float
) {
    val k: Int get() = groups.size
    val isSplit: Boolean get() = groups.size >= 2
}

private class IntList {
    private var a = IntArray(8)
    var size = 0; private set
    fun add(v: Int) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
    operator fun get(i: Int) = a[i]
    fun sorted(): IntList { val o = IntList(); a.copyOf(size).sorted().forEach(o::add); return o }
}
