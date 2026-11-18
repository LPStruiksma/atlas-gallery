package nl.atlas.gallery.featurepeople.splitplan

import java.util.Collections
import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featurepeople.cluster.ClusterReviewFacts
import nl.atlas.gallery.featurepeople.cluster.FacePair
import nl.atlas.gallery.featurepeople.cluster.PersonKey
import nl.atlas.gallery.featurepeople.cluster.RejectedPerson
import nl.atlas.gallery.featurepeople.split.SplitProposer
import kotlin.math.ceil

/**
 * SPLIT-1C (#228): value input after the owner has approved an adjusted partition.
 * It does not depend on SPLIT-1B's draft reducer, the UI, or persistence.
 *
 * The caller must provide one complete selected source membership, including
 * the Unsure tray, and all in-scope review facts. Fact counts cover the GLOBAL
 * persisted fact tables, not just this selected source. The planner checks
 * consistency and capacity; #233 must re-read/revalidate those values at commit.
 */
data class SplitAcceptanceGroup(
    val key: String,
    val members: List<NativeFaceObservationId>,
    val exemplars: List<NativeFaceObservationId>
)

data class SplitFactCounts(
    val confirmed: Long,
    val cannotLink: Long,
    val rejectedPeople: Long,
    val sameUnknown: Long
)

data class SplitAcceptanceSnapshot(
    val sourceRevision: String,
    val sourcePerson: PersonKey?,
    val groups: List<SplitAcceptanceGroup>,
    val unsure: List<NativeFaceObservationId>,
    val confirmed: Map<NativeFaceObservationId, PersonKey>,
    val existingCannotLink: Set<FacePair>,
    val existingSameUnknown: Set<FacePair>,
    val existingRejectedPeople: Set<RejectedPerson>,
    val factCounts: SplitFactCounts,
    /** Set only for the explicit Accept event, never on preview or Cancel. */
    val explicitlyApproved: Boolean
)

enum class SplitPlanRejection {
    NOT_APPROVED,
    STALE_OR_INVALID_SOURCE,
    NO_CLEAR_SPLIT,
    TOO_MANY_GROUPS,
    TOO_MANY_FACES,
    INVALID_PARTITION,
    DUPLICATE_FACE,
    INVALID_EXEMPLARS,
    FOREIGN_REVIEW_FACT,
    INVALID_FACT_COUNTS,
    CONTRADICTORY_REVIEW_FACTS,
    AMBIGUOUS_NAME,
    UNSAFE_NAME_RETENTION,
    CROSS_OWNER_GROUP,
    CROSS_OWNER_UNSURE,
    REJECTED_NAMED_MEMBER,
    CONFIRMED_PERSON_EMPTIED,
    FACT_LIMIT_EXCEEDED
}

sealed interface SplitPlanResult {
    data class Ready(val plan: SplitAcceptancePlan) : SplitPlanResult
    data class Rejected(val reason: SplitPlanRejection) : SplitPlanResult
}

/** An existing confirmed owner stays with this group; no new name is invented. */
data class SplitKeptName(
    val groupKey: String,
    val person: PersonKey,
    val members: List<NativeFaceObservationId>
)

/** Members of a newly unnamed identity after explicit unconfirmation, if any. */
data class SplitUnnamedGroup(val groupKey: String, val members: List<NativeFaceObservationId>)

/**
 * This is an intended *explicit* review decision, not a store write.
 * A prior confirmed face outside its retained named group must be deliberately
 * unconfirmed; #233 must also write the corresponding rejection of that name.
 */
data class SplitReleaseConfirmation(
    val observation: NativeFaceObservationId,
    val expectedOwner: PersonKey
)

/**
 * Read-only acceptance plan. Lists and nested member lists are defensive,
 * unmodifiable snapshots. No media, display names, vectors or DB handles.
 *
 * #233 integration contract:
 * - Re-read permission/source generation, membership, confirmed owners, fact
 *   sets and GLOBAL counts in the same serialized review transaction. Re-run
 *   this planner on those values at that boundary and refuse stale approval.
 * - Interpret keptNames (with complete membership) as retained existing identities, unnamedGroups as
 *   new unknown identities, releaseConfirmations as explicit owner decisions,
 *   unsureStayWithSource as NEVER moved, and newCannotLinks as new facts.
 *   A confirmed Unsure face must belong to sourcePerson, or planning rejects.
 *   Do not silently confirm unconfirmed faces or rename any existing person.
 * - Apply all review/membership/name/cannot-link/rejection changes in ONE
 *   persisted transaction with ONE checkpoint/Undo. If a step fails, roll
 *   back all steps; Cancel writes nothing.
 *
 * Those atomicity, rollback, reload persistence, live-grant and Undo properties
 * are NOT proved here. They require #233's SQLite/Android integration tests.
 */
data class SplitAcceptancePlan(
    val sourceRevision: String,
    val sourcePerson: PersonKey?,
    val baselineFactCounts: SplitFactCounts,
    val keptNames: List<SplitKeptName>,
    val unnamedGroups: List<SplitUnnamedGroup>,
    val releaseConfirmations: List<SplitReleaseConfirmation>,
    val unsureStayWithSource: List<NativeFaceObservationId>,
    val newCannotLinks: List<FacePair>
)

/**
 * SPLIT-1C: bounded, deterministic, fail-closed policy over approved values.
 * NAME_SHARE and competing-confirmation rules match SplitProposer.keptNames.
 * The planner does not invoke SplitProposer, touch a draft or mutate facts.
 */
object SplitAcceptancePlanner {
    const val MAX_GROUPS = 32
    const val MAX_EXEMPLARS_PER_SIDE = 2
    private const val MAX_GROUP_KEY_LENGTH = 128

    private fun reject(reason: SplitPlanRejection): SplitPlanResult = SplitPlanResult.Rejected(reason)

    private data class Group(
        val key: String,
        val members: List<NativeFaceObservationId>,
        val exemplars: List<NativeFaceObservationId>
    )

    fun plan(input: SplitAcceptanceSnapshot): SplitPlanResult {
        if (!input.explicitlyApproved) return reject(SplitPlanRejection.NOT_APPROVED)
        if (input.sourceRevision.isBlank() || input.sourceRevision.length > 256 ||
            '\u0000' in input.sourceRevision
        ) return reject(SplitPlanRejection.STALE_OR_INVALID_SOURCE)
        if (input.groups.size < 2) return reject(SplitPlanRejection.NO_CLEAR_SPLIT)
        if (input.groups.size > MAX_GROUPS) return reject(SplitPlanRejection.TOO_MANY_GROUPS)

        val counts = input.factCounts
        val max = ClusterReviewFacts.MAX_FACTS.toLong()
        if (listOf(counts.confirmed, counts.cannotLink, counts.rejectedPeople, counts.sameUnknown)
                .any { it < 0L || it > max }
        ) return reject(SplitPlanRejection.INVALID_FACT_COUNTS)
        if (counts.confirmed < input.confirmed.size ||
            counts.cannotLink < input.existingCannotLink.size ||
            counts.sameUnknown < input.existingSameUnknown.size ||
            counts.rejectedPeople < input.existingRejectedPeople.size
        ) return reject(SplitPlanRejection.INVALID_FACT_COUNTS)

        val inputFaceCount = input.groups.sumOf { it.members.size.toLong() } + input.unsure.size
        if (inputFaceCount > SplitProposer.MAX_FACES) return reject(SplitPlanRejection.TOO_MANY_FACES)
        if (input.groups.any { it.key.isBlank() || it.key.length > MAX_GROUP_KEY_LENGTH ||
                '\u0000' in it.key || it.members.isEmpty() }
        ) return reject(SplitPlanRejection.INVALID_PARTITION)
        val groups = input.groups.sortedBy { it.key }.map { g ->
            Group(g.key, g.members.sortedBy { it.localHex }, g.exemplars.sortedBy { it.localHex })
        }
        if (groups.map { it.key }.toSet().size != groups.size) {
            return reject(SplitPlanRejection.INVALID_PARTITION)
        }
        val groupOf = HashMap<NativeFaceObservationId, Int>((inputFaceCount * 2).toInt())
        for ((index, group) in groups.withIndex()) {
            for (id in group.members) if (groupOf.putIfAbsent(id, index) != null) {
                return reject(SplitPlanRejection.DUPLICATE_FACE)
            }
            if (group.exemplars.isEmpty() || group.exemplars.size > SplitProposer.EXEMPLARS ||
                group.exemplars.distinct().size != group.exemplars.size ||
                !group.members.containsAll(group.exemplars)
            ) return reject(SplitPlanRejection.INVALID_EXEMPLARS)
        }
        for (id in input.unsure) if (groupOf.putIfAbsent(id, -1) != null) {
            return reject(SplitPlanRejection.DUPLICATE_FACE)
        }
        val selected = groupOf.keys
        if (!selected.containsAll(input.confirmed.keys) ||
            input.existingRejectedPeople.any { it.observation !in selected } ||
            input.existingCannotLink.any { it.first !in selected || it.second !in selected } ||
            input.existingSameUnknown.any { it.first !in selected || it.second !in selected }
        ) return reject(SplitPlanRejection.FOREIGN_REVIEW_FACT)
        if (input.existingRejectedPeople.any { input.confirmed[it.observation] == it.person }) {
            return reject(SplitPlanRejection.CONTRADICTORY_REVIEW_FACTS)
        }

        // Unsure stays with the source and has no release or re-home intent.
        // Keeping another owner's confirmation here would be an implicit
        // ownership change, even if that owner wins a different named group.
        if (input.unsure.any { face ->
                val owner = input.confirmed[face]
                owner != null && owner != input.sourcePerson
            }
        ) return reject(SplitPlanRejection.CROSS_OWNER_UNSURE)

        // A same-unknown path through Unsure can connect TWO proposed groups.
        // Test the full transitive closure, not merely direct conflicting pairs.
        val sortedFaces = selected.sortedBy { it.localHex }
        val indexOf = sortedFaces.withIndex().associate { it.value to it.index }
        val union = UnionFind(sortedFaces.size)
        for (pair in input.existingSameUnknown) {
            union.join(indexOf.getValue(pair.first), indexOf.getValue(pair.second))
        }
        val groupsByRoot = HashMap<Int, Int>()
        for ((face, group) in groupOf) if (group >= 0) {
            val root = union.root(indexOf.getValue(face))
            val old = groupsByRoot.putIfAbsent(root, group)
            if (old != null && old != group) {
                return reject(SplitPlanRejection.CONTRADICTORY_REVIEW_FACTS)
            }
        }
        for (pair in input.existingCannotLink) {
            if (union.root(indexOf.getValue(pair.first)) == union.root(indexOf.getValue(pair.second)) ||
                (groupOf[pair.first] == groupOf[pair.second] && groupOf[pair.first] != -1)
            ) return reject(SplitPlanRejection.CONTRADICTORY_REVIEW_FACTS)
        }

        // The proposer policy includes every confirmed face, also Unsure, in
        // the name's denominator. A name needs >=2/3 of its own confirmations
        // and >=1/2 of all confirmations inside the candidate group.
        val k = groups.size
        val confirmedInGroup = IntArray(k)
        val owners = input.confirmed.values.distinct().sortedBy { it.value }
        val perOwner = owners.associateWith { IntArray(k + 1) }
        for ((face, person) in input.confirmed) {
            val group = groupOf.getValue(face)
            perOwner.getValue(person)[if (group < 0) k else group]++
            if (group >= 0) confirmedInGroup[group]++
        }
        val claims = Array(k) { ArrayList<Pair<PersonKey, Int>>() }
        for (owner in owners) {
            val votes = perOwner.getValue(owner)
            val best = (0 until k).maxByOrNull { votes[it] } ?: continue
            val count = votes[best]
            if (count == 0 || count < ceil(votes.sum() * SplitProposer.NAME_SHARE).toInt() ||
                count.toLong() * 2 < confirmedInGroup[best]
            ) continue
            claims[best] += owner to count
        }
        val nameByGroup = HashMap<Int, PersonKey>()
        for (i in 0 until k) {
            val ranked = claims[i].sortedWith(compareByDescending<Pair<PersonKey, Int>> { it.second }
                .thenBy { it.first.value })
            if (ranked.size > 1 && ranked[0].second == ranked[1].second) {
                return reject(SplitPlanRejection.AMBIGUOUS_NAME)
            }
            if (ranked.isNotEmpty()) nameByGroup[i] = ranked[0].first
        }
        if (input.sourcePerson != null && input.sourcePerson !in nameByGroup.values) {
            return reject(SplitPlanRejection.UNSAFE_NAME_RETENTION)
        }

        val released = ArrayList<SplitReleaseConfirmation>()
        // A separate scan per owner would be quadratic for a large library.
        val remaining = input.confirmed.values.groupingBy { it }.eachCount().toMutableMap()
        for ((face, owner) in input.confirmed.entries.sortedBy { it.key.localHex }) {
            val group = groupOf.getValue(face)
            if (group < 0 || nameByGroup[group] == owner) continue
            if (nameByGroup.containsKey(group)) return reject(SplitPlanRejection.CROSS_OWNER_GROUP)
            released += SplitReleaseConfirmation(face, owner)
            remaining[owner] = remaining.getValue(owner) - 1
        }
        if (remaining.values.any { it == 0 }) {
            return reject(SplitPlanRejection.CONFIRMED_PERSON_EMPTIED)
        }
        for ((groupIndex, person) in nameByGroup) {
            if (groups[groupIndex].members.any {
                    RejectedPerson(it, person) in input.existingRejectedPeople
                }
            ) return reject(SplitPlanRejection.REJECTED_NAMED_MEMBER)
        }

        // Existing cannot-links must not retain two confirmations of the same
        // person after the planned explicit releases (including in Unsure).
        val releasedFaces = released.mapTo(HashSet()) { it.observation }
        if (input.existingCannotLink.any {
                it.first !in releasedFaces && it.second !in releasedFaces &&
                    input.confirmed[it.first] != null &&
                    input.confirmed[it.first] == input.confirmed[it.second]
            }
        ) return reject(SplitPlanRejection.CONTRADICTORY_REVIEW_FACTS)

        // Maximum four exemplar barriers per group pair; do not duplicate
        // existing facts. Neither every-face Cartesian products nor unlimited
        // writes are permitted.
        val planned = LinkedHashSet<FacePair>()
        for (a in 0 until k) for (b in a + 1 until k) {
            for (left in groups[a].exemplars.take(MAX_EXEMPLARS_PER_SIDE)) {
                for (right in groups[b].exemplars.take(MAX_EXEMPLARS_PER_SIDE)) {
                    if (union.root(indexOf.getValue(left)) == union.root(indexOf.getValue(right))) {
                        return reject(SplitPlanRejection.CONTRADICTORY_REVIEW_FACTS)
                    }
                    val pair = FacePair.of(left, right)
                    if (pair !in input.existingCannotLink) planned += pair
                }
            }
        }
        val newRejections = released.count {
            RejectedPerson(it.observation, it.expectedOwner) !in input.existingRejectedPeople
        }
        if (counts.cannotLink + planned.size > max ||
            counts.rejectedPeople + newRejections > max
        ) return reject(SplitPlanRejection.FACT_LIMIT_EXCEEDED)

        val kept = nameByGroup.entries.sortedBy { groups[it.key].key }.map {
            SplitKeptName(groups[it.key].key, it.value, readonly(groups[it.key].members))
        }
        val unnamed = groups.indices.filter { it !in nameByGroup }.map {
            SplitUnnamedGroup(groups[it].key, readonly(groups[it].members))
        }
        return SplitPlanResult.Ready(SplitAcceptancePlan(
            sourceRevision = input.sourceRevision,
            sourcePerson = input.sourcePerson,
            baselineFactCounts = counts.copy(),
            keptNames = readonly(kept),
            unnamedGroups = readonly(unnamed),
            releaseConfirmations = readonly(released),
            unsureStayWithSource = readonly(input.unsure.sortedBy { it.localHex }),
            newCannotLinks = readonly(planned.sortedWith(
                compareBy<FacePair> { it.first.localHex }.thenBy { it.second.localHex }))
        ))
    }

    private fun <T> readonly(source: List<T>): List<T> =
        Collections.unmodifiableList(ArrayList(source))

    private class UnionFind(size: Int) {
        private val parent = IntArray(size) { it }
        fun root(i: Int): Int {
            var p = i
            while (parent[p] != p) { parent[p] = parent[parent[p]]; p = parent[p] }
            return p
        }
        fun join(a: Int, b: Int) {
            val first = root(a)
            val second = root(b)
            if (first != second) parent[second] = first
        }
    }
}
