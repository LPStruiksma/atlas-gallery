package nl.atlas.gallery.featurealbums

import nl.atlas.gallery.MediaRecord
import org.junit.Assert.*
import org.junit.Test

class AlbumCoverPreferencesTest {
    private fun media(
        id: Long, path: String = "DCIM/Camera/", volume: String = "external_primary",
        date: Long = id
    ) = MediaRecord(
        id = id, uri = "content://atlas/images/$volume/$id", name = "Fictional $id",
        relativePath = path, sizeBytes = 2048L, takenAtMillis = date,
        isVideo = false, volumeName = volume
    )

    private val route = AlbumRoute(
        "DCIM/Camera/", AlbumMediaFilter.ALL, "external_primary"
    )


    @Test fun staleTypedReceiptCannotBeReplayedAgainstNewGrantProjection() {
        val item = media(63)
        // An Android selected-media revoke/regrant can happen before a fresh
        // catalogue snapshot publishes. The same list AND same epoch survive.
        val source = listOf(item)
        val oldGrant = AlbumCoverPermissionScope(400L)
        val newGrant = AlbumCoverPermissionScope(401L)
        val oldPage = buildAlbumCoverChoices(
            source, route, 90L, permissionScope = oldGrant)
        val oldReceipt = chooseAlbumCoverOrNull(
            oldPage, source, 90L, route, item.uri,
            permissionScope = oldGrant)!!
        assertEquals(oldGrant, oldReceipt.permissionScope)
        val newPage = buildAlbumCoverChoices(
            source, route, 90L, permissionScope = newGrant)
        val newSession = AlbumCoverPreferences.start(source, 90L, newGrant)

        // Previously the old typed receipt passed against newPage because
        // media identity, bucket and publication epoch happened to match.
        assertNull(newSession.chooseOrNull(
            newPage, oldReceipt, source, 90L, route, newGrant))
        val newReceipt = chooseAlbumCoverOrNull(
            newPage, source, 90L, route, item.uri,
            permissionScope = newGrant)!!
        assertEquals(newGrant, newReceipt.permissionScope)
        assertNotNull(newSession.chooseOrNull(
            newPage, newReceipt, source, 90L, route, newGrant))
        assertNull(newSession.chooseOrNull(
            oldPage, oldReceipt, source, 90L, route, newGrant))
    }

    @Test fun choosingWithCustomPermissionScopeRequiresExactlyThatScope() {
        val source = listOf(media(9))
        val live = AlbumCoverPermissionScope(66L)
        val oldGrant = AlbumCoverPermissionScope(65L)
        val projection = buildAlbumCoverChoices(
            source, route, 20L, permissionScope = live)
        val choice = chooseAlbumCoverOrNull(
            projection, source, 20L, route, source[0].uri,
            permissionScope = live)!!
        val original = AlbumCoverPreferences.start(source, 20L, live)
        assertNull(original.chooseOrNull(
            projection, choice, source, 20L, route, oldGrant))
        assertNull(original.chooseOrNull(
            projection, choice, source, 20L, route))
        val selected = original.chooseOrNull(
            projection, choice, source, 20L, route, live)!!
        assertEquals(choice.coverUri, selected.preferredCoverByBucket[choice.bucket])
        assertNull(selected.clearOrNull(choice.bucket, source, 20L, oldGrant))
        assertTrue(selected.clearOrNull(
            choice.bucket, source, 20L, live)!!.preferredCoverByBucket.isEmpty())
    }

    @Test fun onlyCurrentExactChoiceCanChangeAPreference() {
        val source = (1L..40L).map { media(it) }
        val original = AlbumCoverPreferences.start(source, 11L)
        val projected = buildAlbumCoverChoices(source, route, 11L, limit = 8)
        val choice = chooseAlbumCoverOrNull(
            projected, source, 11L, route, projected.candidates.first().uri
        )!!
        val updated = original.chooseOrNull(projected, choice, source, 11L, route)!!
        assertEquals(1L, updated.revision)
        assertEquals(choice.coverUri, updated.preferredCoverByBucket[choice.bucket])
        assertTrue(original.preferredCoverByBucket.isEmpty())
        assertSame(updated, updated.chooseOrNull(
            projected, choice, source, 11L, route))
        val differentIdentity = source.toList()
        assertNull(original.chooseOrNull(
            projected, choice, differentIdentity, 11L, route))
        assertNull(original.chooseOrNull(
            projected, choice, source, 12L, route))
        assertNull(original.chooseOrNull(
            projected, choice, source, 11L, route.copy(mediaFilter = AlbumMediaFilter.VIDEOS)))
        assertNull(original.chooseOrNull(
            projected, choice.copy(media = choice.media.copy(name = "stale")),
            source, 11L, route))
        assertNull(original.chooseOrNull(
            projected, choice.copy(bucket = AlbumBucketKey("ABCD-1234", route.relativePath)),
            source, 11L, route))
    }

    @Test fun rebaseRetainsOnlyNewlyAuthorizedExactFolderAndVolumeChoices() {
        val primary = media(1)
        val sd = media(2, volume = "ABCD-1234")
        val moved = media(3, path = "Pictures/Moved/")
        val source = listOf(primary, sd, moved)
        val baseline = AlbumCoverPreferences.start(source, 14L)
        fun select(session: AlbumCoverPreferences, record: MediaRecord): AlbumCoverPreferences {
            val r = AlbumRoute(record.relativePath, AlbumMediaFilter.ALL, record.volumeName)
            val p = buildAlbumCoverChoices(source, r, 14L)
            val picked = chooseAlbumCoverOrNull(p, source, 14L, r, record.uri)!!
            return session.chooseOrNull(p, picked, source, 14L, r)!!
        }
        val session = select(select(select(baseline, primary), sd), moved)
        assertEquals(3, session.preferredCoverByBucket.size)
        val refresh = listOf(primary.copy(), moved.copy(),
            sd.copy(relativePath = "DCIM/Camera/Renamed/"))
        val rebased = session.rebase(refresh, 14L)
        assertSame(rebased, rebased.currentOrNull(refresh, 14L))
        assertEquals(2, rebased.preferredCoverByBucket.size)
        assertEquals(primary.uri, rebased.preferredCoverByBucket[
            AlbumBucketKey("external_primary", "DCIM/Camera/")])
        assertNull(rebased.preferredCoverByBucket[
            AlbumBucketKey("ABCD-1234", "DCIM/Camera/")])
        assertTrue(rebased.revision == session.revision)
        assertNull(session.currentOrNull(refresh, 14L))
        assertTrue(session.rebase(refresh, 15L).preferredCoverByBucket.isEmpty())
        assertEquals(0L, session.rebase(refresh, 15L).revision)
    }

    @Test fun clearIsReversibleAndOnlyWorksUnderTheLiveGrant() {
        val source = listOf(media(1))
        val p = buildAlbumCoverChoices(source, route, 3L)
        val choice = chooseAlbumCoverOrNull(p, source, 3L, route, source[0].uri)!!
        val chosen = AlbumCoverPreferences.start(source, 3L).chooseOrNull(
            p, choice, source, 3L, route)!!
        val cleared = chosen.clearOrNull(choice.bucket, source, 3L)!!
        assertEquals(2L, cleared.revision)
        assertTrue(cleared.preferredCoverByBucket.isEmpty())
        assertSame(cleared, cleared.clearOrNull(choice.bucket, source, 3L))
        assertNull(chosen.clearOrNull(choice.bucket, source, 4L))
        assertNull(chosen.clearOrNull(choice.bucket, source.toList(), 3L))
    }

    @Test fun verifiedSameGrantMayKeepCoversAcrossNewPublicationEpoch() {
        val first = media(41)
        val source = listOf(first)
        val verifiedGrant = AlbumCoverPermissionScope(900L)
        val initial = AlbumCoverPreferences.start(source, 101L, verifiedGrant)
        // The page AND its typed receipt must be minted under the same
        // continuously verified grant generation as the preference session.
        val projection = buildAlbumCoverChoices(
            source, route, 101L, permissionScope = verifiedGrant)
        val selected = chooseAlbumCoverOrNull(
            projection, source, 101L, route, first.uri,
            permissionScope = verifiedGrant
        )!!
        assertEquals(verifiedGrant, projection.permissionScope)
        assertEquals(verifiedGrant, selected.permissionScope)
        val chosen = initial.chooseOrNull(
            projection, selected, source, 101L, route,
            currentPermissionScope = verifiedGrant
        )!!
        assertSame(chosen, chosen.currentOrNull(source, 101L, verifiedGrant))
        assertNull(chosen.currentOrNull(source, 101L))
        assertNull(chosen.currentOrNull(
            source, 101L, AlbumCoverPermissionScope(901L)))
        assertNull(chosen.chooseOrNull(
            projection, selected, source, 101L, route,
            AlbumCoverPermissionScope(901L)))
        assertNull(chosen.clearOrNull(
            selected.bucket, source, 101L, AlbumCoverPermissionScope(901L)))
        val nextSource = listOf(first.copy())
        val rebased = chosen.rebase(
            nextSource, 102L, newPermissionScope = verifiedGrant
        )
        assertEquals(1, rebased.preferredCoverByBucket.size)
        assertEquals(first.uri, rebased.preferredCoverByBucket[selected.bucket])
        assertEquals(102L, rebased.accessEpoch)
        assertEquals(1L, rebased.revision)
        assertNull(rebased.currentOrNull(nextSource, 102L))
        assertSame(rebased,
            rebased.currentOrNull(nextSource, 102L, verifiedGrant))
        assertNull(chosen.currentOrNull(nextSource, 102L))
        assertNull(chosen.chooseOrNull(
            projection, selected, source, 102L, route
        ))

        val rolledBack = rebased.rebase(
            source, 100L, newPermissionScope = verifiedGrant
        )
        assertTrue(rolledBack.preferredCoverByBucket.isEmpty())
        assertEquals(0L, rolledBack.revision)
        assertEquals(100L, rolledBack.accessEpoch)

        // Permission revocation (or change) invalidates choices even when the
        // media URI and source list remain equal and publication epoch does not.
        val revoked = rebased.rebase(
            nextSource, 102L,
            newPermissionScope = AlbumCoverPermissionScope(901L)
        )
        assertTrue(revoked.preferredCoverByBucket.isEmpty())
        assertEquals(0L, revoked.revision)
        assertEquals(AlbumCoverPermissionScope(901L), revoked.permissionScope)
        assertSame(nextSource, revoked.source)

        // Without a verified stable token, the default is conservative:
        // a new publication epoch drops all cover preferences.
        val defaultScopeClears = chosen.rebase(nextSource, 102L)
        assertTrue(defaultScopeClears.preferredCoverByBucket.isEmpty())
        assertEquals(0L, defaultScopeClears.revision)

        val missing = chosen.rebase(
            listOf(media(42)), 102L, newPermissionScope = verifiedGrant
        )
        assertTrue(missing.preferredCoverByBucket.isEmpty())
        try {
            AlbumCoverPermissionScope(-1L)
            fail("Accepted invalid permission generation")
        } catch (_: IllegalArgumentException) {}
    }

    @Test fun rebaseCancellationRefusesToPublishPartialRetainedChoices() {
        val source = (1L..4096L).map { media(it) }
        val projection = buildAlbumCoverChoices(source, route, 8L, limit = 4)
        val picked = chooseAlbumCoverOrNull(
            projection, source, 8L, route, projection.candidates.first().uri
        )!!
        val old = AlbumCoverPreferences.start(source, 8L).chooseOrNull(
            projection, picked, source, 8L, route
        )!!
        var checks = 0
        try {
            old.rebase(source.toList(), 8L, checkActive = {
                checks++
                if (checks == 3) error("cancel")
            })
            fail("Cancelled scan should throw")
        } catch (expected: IllegalStateException) {
            assertEquals("cancel", expected.message)
            assertEquals(3, checks)
        }
        assertEquals(1L, old.revision)
    }

    @Test fun emptyPreferenceRebaseDoesNotRescan150kMedia() {
        val source = (0L until 150_000L).map { media(it) }
        val empty = AlbumCoverPreferences.start(source, 40L)
        val refreshed = source.toList()
        var checkpoints = 0
        val rebased = empty.rebase(refreshed, 40L, checkActive = {
            checkpoints++
        })
        assertEquals(1, checkpoints)
        assertSame(refreshed, rebased.source)
        assertTrue(rebased.preferredCoverByBucket.isEmpty())
        assertSame(rebased, rebased.rebase(refreshed, 40L, checkActive = {
            fail("No scan should occur for same source and epoch")
        }))
    }

    @Test fun sourceIdentityAndEpochChecksAreConstantTimeFor150kRecords() {
        val records = (0L until 150_000L).map { media(it) }
        val session = AlbumCoverPreferences.start(records, 51L)
        assertSame(session, session.currentOrNull(records, 51L))
        assertNull(session.currentOrNull(records.toList(), 51L))
        assertNull(session.currentOrNull(records, 52L))
        val projection = buildAlbumCoverChoices(records, route, 51L, limit = 36)
        val choice = chooseAlbumCoverOrNull(
            projection, records, 51L, route, projection.candidates.first().uri
        )!!
        val chosen = session.chooseOrNull(
            projection, choice, records, 51L, route)!!
        assertEquals(1, chosen.preferredCoverByBucket.size)
        val equalNewSource = records.toList()
        val restored = chosen.rebase(equalNewSource, 51L)
        assertEquals(1, restored.preferredCoverByBucket.size)
        assertSame(equalNewSource, restored.source)
        assertEquals(1L, restored.revision)
    }

    @Test fun onlyExactCurrentScopeCanAppearInRebasedPreferences() {
        val phone = media(1)
        val sd = media(2, volume = "ABCD-1234")
        val source = listOf(phone, sd)
        var session = AlbumCoverPreferences.start(source, 9L)
        for (record in source) {
            val r = AlbumRoute(record.relativePath, AlbumMediaFilter.ALL, record.volumeName)
            val p = buildAlbumCoverChoices(source, r, 9L)
            val choice = chooseAlbumCoverOrNull(p, source, 9L, r, record.uri)!!
            session = session.chooseOrNull(p, choice, source, 9L, r)!!
        }
        assertEquals(2, session.preferredCoverByBucket.size)
        val wrongVolumeSameUri = listOf(
            phone.copy(volumeName = "ABCD-1234"),
            sd.copy(volumeName = "external_primary")
        )
        val rebased = session.rebase(wrongVolumeSameUri, 9L)
        assertTrue(rebased.preferredCoverByBucket.isEmpty())
    }
}
