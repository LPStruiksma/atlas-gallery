package nl.atlas.gallery.featurepeople.identity

import nl.atlas.gallery.featurepeople.cluster.FaceClusterId
import nl.atlas.gallery.featurepeople.cluster.FaceClusterState
import nl.atlas.gallery.featurepeople.cluster.PersonKey
import java.util.Locale

/**
 * #580: what a review tap did, counted so the owner can read how often the
 * grouping was wrong. A kind and a number of faces only: never which faces,
 * which person or which name.
 */
enum class ReviewTap(val key: String) {
    /** An unnamed group got a name. */
    NAMED("named"),
    /** An unnamed group or face was added to a named person. */
    ADDED("added"),
    /** Two named people became one. */
    MERGED("merged"),
    /** Faces were taken out of a person or group: the grouping was wrong. */
    TAKEN_OUT("taken_out"),
    /** A suggested person was refused for a group. */
    NOT_THIS_PERSON("not_this_person"),
    /** "Same person?" answered yes. */
    SAME_YES("same_yes"),
    /** "Same person?" answered no. */
    SAME_NO("same_no"),
    /** Undo. The undone tap is taken off its own kind again ([ReviewTally.undoes]). */
    UNDONE("undone"),
    /** Faces the engine had added to a named person on its own were taken out: that add was wrong. */
    AUTO_TAKEN_OUT("auto_taken_out");

    companion object {
        fun ofKey(key: String): ReviewTap? = entries.firstOrNull { it.key == key }
    }
}

/**
 * One committed review tap: its kind and how many faces it moved or judged.
 * An Undo carries the tally of the tap it takes back in [undoes], so that tap
 * stops counting: an undone "take out" was a slip, not a wrong group.
 */
data class ReviewTally(val tap: ReviewTap, val faces: Int, val undoes: ReviewTally? = null) {
    init {
        require(faces >= 0) { "Negative face count" }
        require(undoes == null || (tap == ReviewTap.UNDONE && undoes.undoes == null)) {
            "Only an undo takes a tap back"
        }
    }
}

/** All taps of one kind since the face index was created. */
data class TapCount(val taps: Long, val faces: Long)

/**
 * #580: the People numbers the owner can copy off the phone, to check the
 * Phase 1 target (each of the top 20 people in one group, under 1% of the
 * reviewed faces wrong). Counts only: no face IDs, vectors, crops, file names
 * or person names, so [line] is safe to paste anywhere.
 *
 * - [top]: the [TOP] largest people, named and unnamed together.
 *   [Top.looseGroups] are the unnamed groups with an open "same person?"
 *   question for that person or group ([SamePersonHypotheses]). Zero means: as far as the engine can tell, this
 *   person is in one group.
 * - [autoAdded]: unnamed faces the engine shows under a named person on its
 *   own (FaceClusterState.matched). [autoWrongShare] is how often the owner
 *   took such a face out again.
 * - [wrongShare]: faces the owner took out (grouped or added by the engine),
 *   over those plus the faces now with named people. Null until something
 *   was named or taken out.
 */
class PeopleNumbers private constructor(
    val policyFingerprint: String,
    val faces: Int,
    val namedPeople: Int,
    val namedFaces: Int,
    val groupsOf3: Int,
    val facesInGroupsOf3: Int,
    val pairs: Int,
    val singletons: Int,
    val openQuestions: Int,
    val top: List<Top>,
    val taps: Map<ReviewTap, TapCount>,
    val autoAdded: Int
) {
    data class Top(val named: Boolean, val faces: Int, val looseGroups: Int, val looseFaces: Int)

    val takenOutFaces: Long get() = taps[ReviewTap.TAKEN_OUT]?.faces ?: 0L

    val autoTakenOutFaces: Long get() = taps[ReviewTap.AUTO_TAKEN_OUT]?.faces ?: 0L

    val wrongShare: Double? get() {
        val wrong = takenOutFaces + autoTakenOutFaces
        val reviewed = namedFaces + autoAdded + wrong
        return if (reviewed == 0L) null else wrong.toDouble() / reviewed
    }

    /** Of the faces the engine added on its own, the share the owner took out again. */
    val autoWrongShare: Double? get() {
        val all = autoAdded + autoTakenOutFaces
        return if (all == 0L) null else autoTakenOutFaces.toDouble() / all
    }

    /** How many of [top] have no loose group left. */
    val topInOneGroup: Int get() = top.count { it.looseGroups == 0 }

    /** One line; keys are stable so lines from several days compare. */
    fun line(): String = buildString {
        append("people_numbers v=1 policy=").append(policyFingerprint)
        append(" faces=").append(faces)
        append(" named_people=").append(namedPeople)
        append(" named_faces=").append(namedFaces)
        append(" auto_added=").append(autoAdded)
        append(" groups_3plus=").append(groupsOf3)
        append(" faces_in_3plus=").append(facesInGroupsOf3)
        append(" pairs=").append(pairs)
        append(" singletons=").append(singletons)
        append(" open_questions=").append(openQuestions)
        append(" top").append(TOP).append('=')
        top.forEachIndexed { i, t ->
            if (i > 0) append(',')
            append(if (t.named) 'n' else 'u').append(t.faces)
                .append('+').append(t.looseGroups).append('/').append(t.looseFaces)
        }
        if (top.isEmpty()) append('-')
        append(" taps=")
        ReviewTap.entries.forEachIndexed { i, tap ->
            if (i > 0) append(',')
            val n = taps[tap]
            append(tap.key).append(':').append(n?.taps ?: 0L).append('/').append(n?.faces ?: 0L)
        }
        append(" wrong_share=")
        append(wrongShare?.let { "%.4f".format(Locale.ROOT, it) } ?: "-")
        append(" auto_wrong_share=")
        append(autoWrongShare?.let { "%.4f".format(Locale.ROOT, it) } ?: "-")
    }

    /** The same numbers as short sentences for the screen. */
    fun summary(): List<String> = buildList {
        add("${count(faces, "face")} found")
        add("${count(namedPeople, "named person", "named people")} with ${count(namedFaces, "face")}")
        if (autoAdded > 0 || autoTakenOutFaces > 0) add(
            "Added to named people without asking: ${count(autoAdded, "face")}, " +
                "${count(autoTakenOutFaces, "face")} taken out again")
        add("${count(groupsOf3, "unnamed group")} of 3 or more with ${count(facesInGroupsOf3, "face")}")
        add("${count(singletons + 2 * pairs, "face")} alone or in a pair")
        if (top.isNotEmpty()) add("Largest ${top.size}: $topInOneGroup in one group")
        val share = wrongShare
        val out = takenOutFaces + autoTakenOutFaces
        add(if (share == null) "Taken out as wrong: ${count(out, "face")}"
            else "Taken out as wrong: ${count(out, "face")} (" +
                "%.1f".format(Locale.US, share * 100) + "%)")
        add("Same person? Yes ${taps[ReviewTap.SAME_YES]?.taps ?: 0} · " +
            "No ${taps[ReviewTap.SAME_NO]?.taps ?: 0} · " +
            "${count(openQuestions, "question")} open")
    }

    companion object {
        const val TOP = 20

        private fun count(n: Number, one: String, many: String = one + "s"): String =
            "%,d ".format(Locale.US, n.toLong()) + if (n.toLong() == 1L) one else many

        /**
         * Reads every unnamed group's vectors once for the open questions
         * (as [SamePersonHypotheses.of]); call it off the main thread.
         */
        fun of(
            state: FaceClusterState,
            taps: Map<ReviewTap, TapCount> = emptyMap(),
            questions: List<SamePersonHypothesis> =
                SamePersonHypotheses.of(state, limit = SamePersonHypotheses.MAX_GROUPS)
        ): PeopleNumbers {
            val named = HashMap<PersonKey, Int>()
            var namedFaces = 0
            for ((face, person) in state.reviews.confirmed) {
                if (face !in state) continue
                named.merge(person, 1, Int::plus)
                namedFaces++
            }
            // A person's size in the top list is every face shown under them.
            for (match in state.matched.values) named.merge(match.person, 1, Int::plus)
            var groupsOf3 = 0
            var inGroupsOf3 = 0
            var pairs = 0
            var singletons = 0
            for (c in state.unknownClusters) {
                val n = c.members.size
                when {
                    n >= 3 -> { groupsOf3++; inGroupsOf3 += n }
                    n == 2 -> pairs++
                    else -> singletons++
                }
            }
            val askedGroups = HashMap<FaceClusterId, Int>()
            val askedFaces = HashMap<FaceClusterId, Int>()
            for (q in questions) {
                askedGroups.merge(q.first, 1, Int::plus)
                askedFaces.merge(q.first, q.secondFaces, Int::plus)
                askedGroups.merge(q.second, 1, Int::plus)
                askedFaces.merge(q.second, q.firstFaces, Int::plus)
            }
            // Only the named people that can be in the top are asked about.
            val largestNamed = named.entries.sortedWith(
                compareByDescending<Map.Entry<PersonKey, Int>> { it.value }.thenBy { it.key.value })
                .take(TOP)
            val forNamed = SamePersonHypotheses.forPeople(state,
                largestNamed.mapTo(HashSet()) { it.key }, limit = 10_000)
            val looseGroups = HashMap<PersonKey, Int>()
            val looseFaces = HashMap<PersonKey, Int>()
            for (q in forNamed) {
                looseGroups.merge(q.person, 1, Int::plus)
                looseFaces.merge(q.person, q.groupFaces, Int::plus)
            }
            val top = (largestNamed.map { (person, n) ->
                Top(true, n, looseGroups[person] ?: 0, looseFaces[person] ?: 0)
            } + state.unknownClusters.asSequence().filter { it.members.size >= 3 }.take(TOP)
                .map { Top(false, it.members.size, askedGroups[it.id] ?: 0, askedFaces[it.id] ?: 0) })
                // Stable: named before unnamed at equal size, then by loose counts.
                .sortedWith(compareByDescending<Top> { it.faces }.thenByDescending { it.named }
                    .thenBy { it.looseGroups }.thenBy { it.looseFaces })
                .take(TOP)
            return PeopleNumbers(state.policy.fingerprint, state.faceCount, named.size,
                namedFaces, groupsOf3, inGroupsOf3, pairs, singletons,
                questions.size + forNamed.size, top,
                taps.toMap(), state.matched.size)
        }
    }
}
