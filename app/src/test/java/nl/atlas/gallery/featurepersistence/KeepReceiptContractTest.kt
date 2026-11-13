package nl.atlas.gallery.featurepersistence

import org.junit.Assert.*
import org.junit.Test

class KeepReceiptContractTest {
    private val hash = "a".repeat(64)
    private val f = VerifiedKeepFamily("sha256:1024:$hash",
        listOf(
            VerifiedKeepMember("content://synthetic/a", "revision:1", 1024,hash,true,true),
            VerifiedKeepMember("content://synthetic/b", "revision:1", 1024,hash,true,true)
        ))

    @Test fun exactMemberAllowsOnePinnedKeeperButNeverTrash() {
        val receipt = KeepReceiptContract.validate(f,"content://synthetic/a")
        assertEquals("content://synthetic/a", receipt.keeperKey)
        assertEquals("revision:1", receipt.keeperVersion)
        assertTrue(KeepReceiptContract.remainsValid(receipt,f))
    }

    @Test fun revocationRevisionChangesAndUnverifiedContentInvalidate() {
        val receipt = KeepReceiptContract.validate(f,"content://synthetic/a")
        val changed = f.copy(members=f.members.map { it.copy(contentVersion="revision:2") })
        val revoked = f.copy(members=f.members.map { it.copy(readableNow=false) })
        val unverified = f.copy(members=f.members.map { it.copy(digestVerified=false) })
        assertFalse(KeepReceiptContract.remainsValid(receipt,changed))
        assertFalse(KeepReceiptContract.remainsValid(receipt,revoked))
        assertFalse(KeepReceiptContract.remainsValid(receipt,unverified))
        assertFalse(KeepReceiptContract.remainsValid(receipt,f.copy(members=f.members.take(1))))
    }

    @Test fun otherFamilyMemberRevisionOrReplacementInvalidatesSavedKeeper() {
        val saved = KeepReceiptContract.validate(f,"content://synthetic/a")
        val siblingEdited = f.copy(members=f.members.map { member ->
            if (member.mediaKey == "content://synthetic/b")
                member.copy(contentVersion="revision:2") else member
        })
        assertFalse(KeepReceiptContract.remainsValid(saved, siblingEdited))
        val siblingReplaced = f.copy(members=listOf(
            f.members.first(), f.members.last().copy(mediaKey="content://synthetic/c")
        ))
        assertFalse(KeepReceiptContract.remainsValid(saved,siblingReplaced))
        assertEquals(saved.familyMembersSha256,
            KeepReceiptContract.validate(f.copy(members=f.members.reversed()),
                "content://synthetic/a").familyMembersSha256)
        assertEquals(64,saved.familyMembersSha256.length)
    }

    @Test fun malformedUnicodeCannotCollapseDistinctMemberRevisionsIntoSameDigest() {
        val saved = KeepReceiptContract.validate(f,"content://synthetic/a")
        val malformed = listOf("\uD800", "\uD801", "\uDC00", "prefix\uD800suffix")
        for (text in malformed) {
            val versionChanged = f.copy(members = listOf(
                f.members.first(), f.members.last().copy(contentVersion="revision:" + text)
            ))
            reject { KeepReceiptContract.validate(versionChanged,"content://synthetic/a") }
            assertFalse(KeepReceiptContract.remainsValid(saved,versionChanged))
            val keyChanged = f.copy(members=listOf(
                f.members.first(),f.members.last().copy(mediaKey="content://synthetic/" + text)
            ))
            reject { KeepReceiptContract.validate(keyChanged,"content://synthetic/a") }
            assertFalse(KeepReceiptContract.remainsValid(saved,keyChanged))
        }
    }

    @Test fun wellFormedSupplementaryUnicodeRetainsStableVersionBoundMembership() {
        val valid=f.copy(members=listOf(
            f.members.first().copy(mediaKey="content://synthetic/📸",contentVersion="rev:𝄞"),
            f.members.last().copy(mediaKey="content://synthetic/🥳",contentVersion="rev:🎵")
        ))
        val receipt=KeepReceiptContract.validate(valid,"content://synthetic/📸")
        assertTrue(KeepReceiptContract.remainsValid(receipt,valid))
        assertEquals(receipt.familyMembersSha256,
            KeepReceiptContract.validate(valid.copy(members=valid.members.reversed()),
                "content://synthetic/📸").familyMembersSha256)
        val edited=valid.copy(members=listOf(
            valid.members.first(), valid.members.last().copy(contentVersion="rev:🎶")
        ))
        assertFalse(KeepReceiptContract.remainsValid(receipt,edited))
    }

    @Test fun rejectsWrongFamilyShaSizeDuplicateKeyAndUnownedKeeper() {
        val bad = listOf(
            f.copy(familyKey="sha256:1025:$hash"),
            f.copy(familyKey="sha256:1024:" + "b".repeat(64)),
            f.copy(members=f.members.map { it.copy(mediaKey="content://synthetic/a") }),
            f.copy(members=f.members.map { it.copy(byteSize=2048) }),
            f.copy(members=f.members.map { it.copy(sha256="b".repeat(64)) })
        )
        bad.forEach { candidate -> reject { KeepReceiptContract.validate(candidate,"content://synthetic/a") } }
        reject { KeepReceiptContract.validate(f,"content://synthetic/not-in-family") }
    }

    private fun reject(block: () -> Unit) {
        try { block(); fail("Untrusted Keep receipt was accepted") }
        catch (_: IllegalArgumentException) { }
    }
}
