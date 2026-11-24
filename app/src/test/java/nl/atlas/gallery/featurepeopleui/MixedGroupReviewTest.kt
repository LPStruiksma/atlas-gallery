package nl.atlas.gallery.featurepeopleui

import nl.atlas.gallery.featureface.NativeFaceObservationId
import nl.atlas.gallery.featurepeople.cluster.ClusterPolicy
import nl.atlas.gallery.featurepeople.cluster.ClusterReviewFacts
import nl.atlas.gallery.featurepeople.cluster.FaceClusterId
import nl.atlas.gallery.featurepeople.cluster.FaceClusterState
import nl.atlas.gallery.featurepeople.cluster.FaceClusterer
import nl.atlas.gallery.featurepeople.cluster.FaceEmbedding
import nl.atlas.gallery.featurepeople.cluster.MixedGroup
import nl.atlas.gallery.featurepeople.cluster.MixedGroupMarks
import nl.atlas.gallery.featurepeople.cluster.PersonKey
import nl.atlas.gallery.featurepersistence.ClusterPublication
import nl.atlas.gallery.featurepersistence.PeopleReviewRecords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * HUB-1B (#211): "Not one person" on one or both sides of a Same person?
 * question. Synthetic vectors only. The queue fixture is the one of
 * SamePersonQueueTest: groups of 8, 6, 5, 4 and 3 faces, where 6+5 and 8+3
 * are asked about.
 */
class MixedGroupReviewTest {
    private val dim = 128
    private var nextId = 1
    private var rng = Random(211)
    private val policy = ClusterPolicy.SFACE_MEASURED.copy(metric = null)

    private fun Random.gaussian(): Double {
        val u1 = nextDouble().coerceAtLeast(1e-12)
        return sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(2 * Math.PI * nextDouble())
    }
    private fun unit(): DoubleArray {
        val v = DoubleArray(dim) { rng.gaussian() }
        val n = sqrt(v.sumOf { it * it })
        return DoubleArray(dim) { v[it] / n }
    }
    private fun mix(a: DoubleArray, s: Double): DoubleArray {
        val b = unit()
        val along = (0 until dim).sumOf { a[it] * b[it] }
        val o = DoubleArray(dim) { b[it] - along * a[it] }
        val n = sqrt(o.sumOf { it * it })
        return DoubleArray(dim) { s * a[it] + sqrt(1 - s * s) * o[it] / n }
    }
    private fun face(look: DoubleArray): FaceEmbedding {
        val f = mix(look, 0.95)
        val id = nextId++
        return FaceEmbedding(NativeFaceObservationId("%032x".format(id)), "m",
            FloatArray(dim) { f[it].toFloat() }, 0f, id.toLong())
    }

    private val fixtureFaces = ArrayList<FaceEmbedding>()

    private fun state(): PeopleClusterReviewState {
        rng = Random(580) // SamePersonQueueTest's seed, so the same five groups
        val a = unit(); val a2 = mix(a, 0.37)
        val c = unit(); val c2 = mix(c, 0.37)
        val stranger = unit()
        val faces = List(6) { face(a) } + List(5) { face(a2) } + List(8) { face(c) } +
            List(3) { face(c2) } + List(4) { face(stranger) }
        fixtureFaces.clear(); fixtureFaces += faces
        val clusters = FaceClusterer.update(FaceClusterState.empty("m", dim, policy), add = faces)
        assertEquals(listOf(8, 6, 5, 4, 3), clusters.unknownClusters.map { it.members.size })
        return PeopleClusterReviewState("epoch", "generation", clusters)
    }
    private fun PeopleClusterReviewState.group(size: Int) =
        clusters.unknownClusters.single { it.members.size == size }
    private fun SamePersonQueue.sizes() = items.map { it.subjectFaces to it.otherFaces }
    private fun mark(s: PeopleClusterReviewState, groups: List<FaceClusterId>) =
        reviewPeopleCluster(s, PeopleReviewAction.MarkMixedGroups(groups.toSet()))

    @Test fun markingOneSideDropsItsQuestionAndLeavesTheOthersAsTheyWere() {
        val s = state()
        val before = samePersonQueue(s)
        assertEquals(2, before.open)
        assertEquals(listOf(6 to 5, 8 to 3), before.sizes())
        val question = before.items[0]
        val action = requireNotNull(samePersonQueueMixed(question, subject = false, other = true))
        assertEquals(setOf(s.group(5).id), action.groups)

        val marked = requireNotNull(reviewPeopleCluster(s, action))
        val after = samePersonQueue(marked)
        assertEquals(1, after.open)
        assertEquals(listOf(before.items[1]), after.items) // unaffected question unchanged
        // Nothing joined, split or moved: the very same grouping, one new fact.
        assertSame(s.clusters.unknownClusters, marked.clusters.unknownClusters)
        assertEquals(setOf(MixedGroup.of(s.group(5).members)),
            marked.clusters.reviews.mixedGroups)
        assertEquals(s.clusters.reviews.copy(mixedGroups = marked.clusters.reviews.mixedGroups),
            marked.clusters.reviews)
        assertEquals(listOf(NeedsSplittingGroup(s.group(5).id, 5, s.group(5).id.anchor)),
            needsSplitting(marked))
        assertEquals(PersonPageTarget.Unnamed(s.group(5).id), needsSplitting(marked)[0].target)
        assertEquals("u:" + s.group(5).id.anchor.localHex, needsSplitting(marked)[0].key)
        assertEquals(emptyList<NeedsSplittingGroup>(), needsSplitting(s))
        assertEquals(emptyList<NeedsSplittingGroup>(), needsSplitting(null))
    }

    @Test fun markingBothSidesIsOneActionAndUndoRestoresTheQuestion() {
        val s = state()
        val question = samePersonQueue(s).items[0]
        val both = requireNotNull(samePersonQueueMixed(question, subject = true, other = true))
        assertEquals(setOf(s.group(6).id, s.group(5).id), both.groups)
        val marked = requireNotNull(reviewPeopleCluster(s, both))
        assertEquals(1, marked.undoHistory.size) // one review intent, one checkpoint
        assertEquals(1, samePersonQueue(marked).open)
        assertEquals(listOf(6, 5), needsSplitting(marked).map { it.faces })
        assertEquals(2, marked.clusters.reviews.mixedGroups.size)

        val undone = requireNotNull(reviewPeopleCluster(marked, PeopleReviewAction.Undo))
        assertEquals(samePersonQueue(s), samePersonQueue(undone))
        assertTrue(undone.clusters.reviews.mixedGroups.isEmpty())
        assertNull(samePersonQueueMixed(question, subject = false, other = false))
    }

    @Test fun aNamedSideIsNeverMarkedAndMarkingTheOtherDropsTheNamedQuestion() {
        val fresh = state()
        val ada = PersonKey("native-ada")
        val s = requireNotNull(reviewPeopleCluster(fresh,
            PeopleReviewAction.NameCluster(fresh.group(6).id, ada, "Ada")))
        val asked = samePersonQueue(s).items[0]
        assertEquals(PersonPageTarget.Named(ada), asked.subject)
        assertEquals(setOf(asked.other),
            requireNotNull(samePersonQueueMixed(asked, subject = true, other = true)).groups)
        assertNull(samePersonQueueMixed(asked, subject = true, other = false))
        val marked = requireNotNull(reviewPeopleCluster(s,
            requireNotNull(samePersonQueueMixed(asked, subject = false, other = true))))
        assertEquals(listOf(8 to 3), samePersonQueue(marked).sizes())
        assertEquals(1, samePersonQueue(marked).open)
    }

    @Test fun typedNamedAndBothSideMarksAreAtomicAndPrivacyPartitioned() {
        val fresh = state()
        val person = PersonKey("native-person")
        val named = requireNotNull(reviewPeopleCluster(fresh,
            PeopleReviewAction.NameCluster(fresh.group(6).id, person, "Test person")))
        val question = samePersonQueue(named).items.first {
            it.subject == PersonPageTarget.Named(person)
        }
        val namedOnly = requireNotNull(samePersonQueueMixedSides(question,
            subject = true, other = false))
        assertEquals(setOf(PersonPageTarget.Named(person)), namedOnly.sides)
        val marked = requireNotNull(reviewPeopleCluster(named, namedOnly))
        assertEquals(named.undoHistory.size + 1, marked.undoHistory.size)
        assertTrue(samePersonQueue(marked).items.none {
            it.subject == PersonPageTarget.Named(person)
        })
        val row = needsSplittingLists(marked).visible.single {
            it.target == PersonPageTarget.Named(person)
        }
        assertEquals(6, row.faces)
        assertTrue(needsSplittingLists(marked).hidden.isEmpty())

        val hidden = requireNotNull(reviewPeopleCluster(marked,
            PeopleReviewAction.SetPersonHidden(person, true)))
        assertTrue(needsSplittingLists(hidden).visible.none {
            it.target == PersonPageTarget.Named(person)
        })
        assertEquals(listOf(row), needsSplittingLists(hidden).hidden)
        val restored = requireNotNull(reviewPeopleCluster(marked, PeopleReviewAction.Undo))
        assertTrue(needsSplittingLists(restored).visible.none {
            it.target == PersonPageTarget.Named(person)
        })

        val both = requireNotNull(samePersonQueueMixedSides(question,
            subject = true, other = true))
        assertEquals(2, both.sides.size)
        val twice = requireNotNull(reviewPeopleCluster(named, both))
        assertEquals(named.undoHistory.size + 1, twice.undoHistory.size)
        assertEquals(2, needsSplittingLists(twice).visible.size)
        assertNull(samePersonQueueMixedSides(question, subject = false, other = false))
        // One stale target refuses the complete intent.
        val stale = PersonPageTarget.Unnamed(FaceClusterId(
            NativeFaceObservationId("f".repeat(32))))
        assertNull(reviewPeopleCluster(named, PeopleReviewAction.MarkMixedSides(
            setOf(PersonPageTarget.Named(person), stale))))
    }

    @Test fun sameInputIsIdempotentAndBadInputMarksNothing() {
        val s = state()
        val once = requireNotNull(mark(s, listOf(s.group(5).id)))
        // The same input again changes nothing: no new fact, no new checkpoint.
        assertNull(mark(once, listOf(s.group(5).id)))
        // Both sides where one is already marked adds only the other.
        val both = requireNotNull(mark(once, listOf(s.group(5).id, s.group(6).id)))
        assertEquals(2, both.clusters.reviews.mixedGroups.size)
        assertEquals(once.undoHistory.size + 1, both.undoHistory.size)

        // All or nothing: one stale side refuses the whole action.
        val stale = FaceClusterId(NativeFaceObservationId("f".repeat(32)))
        assertNull(mark(s, listOf(s.group(5).id, stale)))
        assertNull(mark(s, emptyList()))
        assertNull(mark(s, listOf(s.group(8).id, s.group(6).id, s.group(5).id)))
        // A confirmed face's id is no unnamed group.
        val ada = PersonKey("native-ada")
        val named = requireNotNull(reviewPeopleCluster(s,
            PeopleReviewAction.NameCluster(s.group(4).id, ada, "Ada")))
        assertNull(mark(named, listOf(s.group(4).id)))
        assertTrue(s.clusters.reviews.mixedGroups.isEmpty())
    }

    @Test fun aSingleFaceCannotBeMarkedMixed() {
        val clusters = grouped(emptySet(), ids(1, 2))
        val s = PeopleClusterReviewState("epoch", "generation", clusters)
        val single = requireNotNull(clusters.clusterOf(strangers[5].observation))
        assertEquals(1, clusters.unknownClusters.single { it.id == single }.members.size)
        assertNull(mark(s, listOf(single)))
        val pair = requireNotNull(clusters.clusterOf(strangers[1].observation))
        assertNull(mark(s, listOf(pair, single))) // all or nothing
    }

    @Test fun marksSurviveARoundTripThroughStoredRows() {
        val s = state()
        val marked = requireNotNull(mark(s, listOf(s.group(5).id, s.group(6).id)))
        val rows = PeopleReviewRecords.rows(marked.clusters.reviews)
        assertEquals(2, rows.mixedGroups.size)
        val reloaded = FaceClusterer.restore("m", policy, fixtureFaces,
            marked.clusters.unknownClusters.map { it.members },
            PeopleReviewRecords.facts(rows))
        val again = PeopleClusterReviewState("epoch", "generation-2", reloaded)
        assertEquals(needsSplitting(marked), needsSplitting(again))
        assertEquals(samePersonQueue(marked), samePersonQueue(again))
    }

    @Test fun aSplitClearsTheMarkAndAnUnrelatedReviewKeepsIt() {
        val s = state()
        val marked = requireNotNull(mark(s, listOf(s.group(8).id)))
        // Joining two other groups regroups, but the marked group is untouched.
        val joined = requireNotNull(reviewPeopleCluster(marked,
            PeopleReviewAction.JoinUnknownClusters(s.group(6).id, s.group(5).id)))
        assertEquals(listOf(s.group(8).id), needsSplitting(joined).map { it.cluster })
        // Splitting half of the marked group off: the mark has done its job.
        val eight = s.group(8).members
        val split = requireNotNull(reviewPeopleCluster(joined,
            PeopleReviewAction.SplitUnknownCluster(s.group(8).id, eight.take(4).toSet())))
        assertEquals(emptyList<NeedsSplittingGroup>(), needsSplitting(split))
        assertTrue(split.clusters.reviews.mixedGroups.isEmpty())
    }

    /** Twelve unrelated faces with an exact grouping, so each case is a known membership. */
    private val strangers: List<FaceEmbedding> by lazy {
        List(12) { i ->
            val v = unit()
            FaceEmbedding(NativeFaceObservationId("%032x".format(1000 + i)), "m",
                FloatArray(dim) { v[it].toFloat() }, 0f, 1000L + i)
        }
    }
    private fun ids(vararg n: Int) = n.map { strangers[it].observation }
    private fun grouped(facts: Set<MixedGroup>, vararg groups: List<NativeFaceObservationId>) =
        FaceClusterer.restore("m", policy, strangers, groups.toList(),
            ClusterReviewFacts(mixedGroups = facts)).also { state ->
            for (g in groups) assertTrue(state.unknownClusters.any { it.members.toSet() == g.toSet() })
        }

    @Test fun membershipChangesAreJudgedByJaccard() {
        val group = ids(1, 2, 3, 4)
        val fact = MixedGroup.of(group)
        val before = grouped(setOf(fact), group, ids(5, 6))
        assertEquals(1, MixedGroupMarks.marked(before).size)

        fun carried(vararg after: List<NativeFaceObservationId>): Set<MixedGroup> =
            MixedGroupMarks.carry(before, grouped(setOf(fact), *after)).reviews.mixedGroups

        // Same members in another order: the very same fact, the same state back.
        val same = grouped(setOf(fact), group.reversed(), ids(5, 6))
        assertSame(same, MixedGroupMarks.carry(before, same))
        assertEquals(fact, MixedGroup.of(group.reversed()))
        // One face joins (J 0.8) or one leaves (J 0.75): the mark follows the group.
        assertEquals(setOf(MixedGroup.of(ids(1, 2, 3, 4, 7))), carried(ids(1, 2, 3, 4, 7)))
        assertEquals(setOf(MixedGroup.of(ids(1, 2, 3))), carried(ids(1, 2, 3)))
        // A face below the old anchor joins: the mark moves to the new anchor.
        val moved = carried(ids(0, 1, 2, 3, 4)).single()
        assertEquals(strangers[0].observation, moved.anchor)
        // Split in halves (J 0.5), joined with a group of two (J 0.67), or one
        // face in and one out (J 0.6): substantial, the mark clears itself.
        assertEquals(emptySet<MixedGroup>(), carried(ids(1, 2), ids(3, 4)))
        assertEquals(emptySet<MixedGroup>(), carried(ids(1, 2, 3, 4, 5, 6)))
        assertEquals(emptySet<MixedGroup>(), carried(ids(1, 2, 3, 8)))
        // A fact that named no group before is left alone.
        val other = MixedGroup.of(ids(9, 10))
        assertEquals(setOf(other), MixedGroupMarks.carry(before.withFacts(setOf(other)),
            grouped(setOf(other), ids(9, 10, 11))).reviews.mixedGroups)
        assertEquals(0.75, MixedGroupMarks.jaccard(ids(1, 2, 3).toSet(), group.toSet()), 1e-9)
        assertEquals(0.0, MixedGroupMarks.jaccard(emptySet(), emptySet()), 1e-9)
    }

    private fun FaceClusterState.withFacts(facts: Set<MixedGroup>) =
        FaceClusterer.restore("m", policy, strangers, unknownClusters.map { it.members },
            ClusterReviewFacts(mixedGroups = facts))

    @Test fun aPublicationCarriesTheMarkLikeAReview() {
        // One look in four faces, published and marked; a fifth face of that
        // look arrives: J 0.8, the grown group keeps the mark.
        val look = unit()
        val four = List(4) { face(look) }
        val seed = FaceClusterer.restore("m", policy, four, listOf(four.map { it.observation }),
            ClusterReviewFacts(mixedGroups = setOf(MixedGroup.of(four.map { it.observation }))))
        assertEquals(1, MixedGroupMarks.marked(seed).size)
        val published = ClusterPublication.publish(seed, listOf(face(look)))
        val grown = published.unknownClusters.single()
        assertEquals(5, grown.members.size)
        assertEquals(setOf(MixedGroup.of(grown.members)), published.reviews.mixedGroups)
        assertEquals(setOf(grown.id), MixedGroupMarks.marked(published))
        // Three more faces of the look (J 4/8): the group changed substantially.
        val big = ClusterPublication.publish(seed, List(4) { face(look) })
        assertEquals(8, big.unknownClusters.single().members.size)
        assertTrue(big.reviews.mixedGroups.isEmpty())
        // No pending faces: the seed itself, marks untouched.
        assertSame(seed, ClusterPublication.publish(seed, emptyList()))
    }
    @Test fun skipIdentityChangesWithEitherMembershipOrPermissionScope() {
        val original = state()
        val question = samePersonQueue(original).items.first()
        val fingerprint = samePersonSkipFingerprint(original, question)
        assertNotNull(fingerprint)
        assertEquals(fingerprint, samePersonSkipFingerprint(original, question))
        assertNotEquals(fingerprint, samePersonSkipFingerprint(
            original.copy(permissionEpoch = "another-current-grant"), question))
        val subject = (question.subject as PersonPageTarget.Unnamed).cluster
        val other = question.other

        fun swappedMember(target: FaceClusterId): PeopleClusterReviewState {
            val group = original.clusters.unknownClusters.single { it.id == target }
            val spare = original.group(4)
            val out = group.members.maxBy { it.localHex }
            val incoming = spare.members.maxBy { it.localHex }
            val groups = original.clusters.unknownClusters.map { g ->
                when (g.id) {
                    target -> (g.members - out) + incoming
                    spare.id -> (g.members - incoming) + out
                    else -> g.members
                }
            }
            val rebuilt = FaceClusterer.restore("m", policy, fixtureFaces,
                groups, original.clusters.reviews)
            return original.copy(clusters = rebuilt)
        }

        val changedSubject = samePersonSkipFingerprint(swappedMember(subject), question)
        val changedOther = samePersonSkipFingerprint(swappedMember(other), question)
        assertNotEquals(fingerprint, changedSubject)
        assertNotEquals(fingerprint, changedOther)
        // The open count remains an all-question count; Skip is only a preference.
        assertEquals(2, samePersonQueue(original).open)
    }

    @Test fun stableFullGrantSkipSurvivesFaceEpochChangesButNotMembershipChanges() {
        val original = state()
        val question = samePersonQueue(original).items.first()
        val stableScope = "full-photo-grant:images"
        val token = requireNotNull(samePersonSkipFingerprint(original, question, stableScope))
        val reissued = original.copy(
            permissionEpoch = "new-random-face-publication-epoch",
            sourceGeneration = "new-source-generation"
        )
        // A publication epoch is not the owner's grant or a changed group.
        assertEquals(token, samePersonSkipFingerprint(reissued, question, stableScope))
        // Selected-photos or changed full-grant scopes cannot reuse the token.
        assertNotEquals(token, samePersonSkipFingerprint(reissued, question))
        assertNotEquals(token, samePersonSkipFingerprint(
            reissued, question, "full-photo-grant:images+videos"))

        // Actual membership, not merely the question's key/count, defines Skip.
        val first = original.clusters.unknownClusters.single {
            it.id == (question.subject as PersonPageTarget.Unnamed).cluster
        }
        val replacement = original.group(4)
        val displaced = first.members.maxBy { it.localHex }
        val incoming = replacement.members.maxBy { it.localHex }
        val changed = FaceClusterer.restore("m", policy, fixtureFaces,
            original.clusters.unknownClusters.map { group ->
                when (group.id) {
                    first.id -> (group.members - displaced) + incoming
                    replacement.id -> (group.members - incoming) + displaced
                    else -> group.members
                }
            }, original.clusters.reviews)
        assertNotEquals(token, samePersonSkipFingerprint(
            original.copy(clusters = changed), question, stableScope))
    }

}
