package nl.atlas.gallery.featurecuration

import org.junit.Test

class CurationReviewPlanTest {
    private val digest = "a".repeat(64)
    private fun media(id: String, hash: String? = digest) = CurationAsset(
        "content://fictional/$id", "revision-1", 100L, "Fictional $id",
        hash, if (hash == null) null else "revision-1"
    )
    private fun pair(a: CurationAsset, b: CurationAsset, kind: CurationPairKind,
                     scope: String = "grant1") =
        CurationPairEvidence(a.uri, b.uri, kind, scope, a.revision, b.revision)
    private fun reject(block: () -> Unit) {
        try { block() } catch (_: IllegalArgumentException) { return }
        error("Must fail closed")
    }

    @Test fun geometricSuggestionsRemainIndependentPairs() {
        val a = media("a")
        val b = media("b", "b".repeat(64))
        val c = media("c", "c".repeat(64))
        val plan = CurationReviewPlan.build("grant1", listOf(a,b,c), listOf(
            pair(a,b,CurationPairKind.GEOMETRIC_CANDIDATE),
            pair(b,c,CurationPairKind.GEOMETRIC_CANDIDATE)))
        check(plan.exactGroups.isEmpty())
        check(plan.visualPairs.size == 2)
        check(plan.visualPairs.none { it.left == a && it.right == c })
    }

    @Test fun exactCopiesHaveDeterministicKeeperAndNoAutoStage() {
        val a = media("a")
        val b = media("b")
        val plan = CurationReviewPlan.build("grant1", listOf(b,a), emptyList())
        val group = plan.exactGroups.single()
        check(group.suggestedKeeperUri == a.uri)
        check(group.potentialBytes == 100L)
        reject { plan.prepareManualTrashReview(digest,a.uri, emptySet(), "grant1", listOf(a,b)) }
        val pending = plan.prepareManualTrashReview(digest,a.uri,setOf(b.uri),"grant1",listOf(a,b))
        check(pending.selectedUris == listOf(b.uri))
        check(pending.requiresFreshFullByteVerification)
    }

    @Test fun favoritesPeopleKeepAndProtectedFolderNeverStage() {
        val a = media("a")
        val b = media("b")
        for (protected in listOf(b.copy(favorite=true), b.copy(peopleProtected=true),
            b.copy(userKept=true), b.copy(protectedFolder=true))) {
            val plan = CurationReviewPlan.build("grant1",listOf(a,protected),emptyList())
            check(plan.exactGroups.single().suggestedKeeperUri == protected.uri)
            reject { plan.prepareManualTrashReview(digest,a.uri,setOf(b.uri),"grant1",listOf(a,protected)) }
        }
    }

    @Test fun permissionRevisionAndKeeperStalenessReject() {
        val a = media("a")
        val b = media("b")
        val plan = CurationReviewPlan.build("oldGrant",listOf(a,b),emptyList())
        reject { plan.prepareManualTrashReview(digest,a.uri,setOf(b.uri),"newGrant",listOf(a,b)) }
        reject { plan.prepareManualTrashReview(digest,a.uri,setOf(b.uri),"oldGrant",listOf(a,b.copy(revision="changed"))) }
        reject { plan.prepareManualTrashReview(digest,a.uri,setOf(b.uri),"oldGrant",listOf(b)) }
        reject { plan.prepareManualTrashReview(digest,a.uri,setOf(a.uri),"oldGrant",listOf(a,b)) }
    }

    @Test fun malformedAndDuplicateSourcesRejectInsteadOfTruncation() {
        val a = media("a")
        val b = media("b")
        reject { CurationReviewPlan.build("grant1",listOf(a,a),emptyList()) }
        reject { CurationReviewPlan.build("",listOf(a,b),emptyList()) }
        reject { CurationReviewPlan.build("grant1",listOf(a,b),listOf(
            pair(a,b,CurationPairKind.UNKNOWN),
            pair(b,a,CurationPairKind.GEOMETRIC_REVIEW))) }
        reject { CurationReviewPlan.build("grant1",listOf(a),listOf(
            pair(a,b,CurationPairKind.UNKNOWN))) }
    }

    @Test fun staleAndInvalidHashesNeverBecomeExactGroups() {
        val a = media("a")
        val stale = media("b").copy(sha256AtRevision="old")
        val invalid = media("c", "g".repeat(64))
        check(CurationReviewPlan.build("g",listOf(a,stale,invalid),emptyList()).exactGroups.isEmpty())
    }

    @Test fun stagingIsCappedAt200AndChecksArithmeticOverflow() {
        val items = (0..201).map { media("item-$it") }
        val plan = CurationReviewPlan.build("g",items,emptyList())
        reject { plan.prepareManualTrashReview(digest,items.first().uri,
            items.drop(1).map { it.uri }.toSet(),"g",items) }
        reject { CurationReviewPlan.build("g",listOf(
            media("a").copy(bytes=Long.MAX_VALUE),media("b").copy(bytes=Long.MAX_VALUE),
            media("c")),emptyList()) }
    }

    @Test fun staleGeometricEvidenceCannotCrossGrantOrRevision() {
        val a = media("a")
        val b = media("b", "b".repeat(64))
        reject { CurationReviewPlan.build("grant1",listOf(a,b),listOf(
            pair(a,b,CurationPairKind.GEOMETRIC_CANDIDATE,"oldGrant"))) }
        reject { CurationReviewPlan.build("grant1",listOf(a.copy(revision="revision-2"),b),listOf(
            pair(a,b,CurationPairKind.GEOMETRIC_CANDIDATE))) }
        check(CurationReviewPlan.build("grant1",listOf(a,b),listOf(
            pair(a,b,CurationPairKind.GEOMETRIC_CANDIDATE))).visualPairs.size == 1)
    }

    @Test fun largeCatalogueWithNoHashNeverInfersDuplication() {
        val records = (0 until 150_000).map { media("$it",null) }
        val plan = CurationReviewPlan.build("g",records,emptyList())
        check(plan.exactGroups.isEmpty() && plan.visualPairs.isEmpty())
    }
}
