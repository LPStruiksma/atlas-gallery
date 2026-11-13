package nl.atlas.gallery

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Manual two-phase CI instrumentation, intentionally NOT run together by
 * connectedDebugAndroidTest: a real adb install -r occurs between phases.
 * All content is invented test data in the emulator's app-private SQLite.
 */
@RunWith(AndroidJUnit4::class)
class PeopleUpgradeTest {
    private val sentinelId = 7041L
    private val sentinelName = "ATLAS upgrade preservation sentinel"
    private val sentinelPath = "/storage/emulated/0/DCIM/atlas-upgrade-fixture.png"
    private val settingsName = "atlas-ci-upgrade-settings"
    private val settingsKey = "synthetic-selected-collection"
    private val settingsValue = "people"
    // Existing production SharedPreferences contract in GalleryViewModel.
    // Persist only the real digest key; do NOT create a fake SAF 'uri'.
    private val realSettingsName = "atlas_v4_live_sync"
    private val realSettingsKey = "sha256"
    private val realSettingsValue = "fictional-digest-for-upgrade-proof"
    private val mismatchRestoreKey = "synthetic-signer-mismatch-restored"

    @Test fun seedBeforeUpgrade() {
        assumeTrue("Only dedicated upgrade phase runs this",
            InstrumentationRegistry.getArguments().getString("atlasUpgradePhase") == "seed")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PeopleRepository(context).use { repository ->
            val csv = "person_id,person_name,image_path,face_id,confidence\n" +
                "$sentinelId,$sentinelName,$sentinelPath,9071,1.0\n"
            assertEquals(1, repository.replaceFromV4Export(csv))
            val person = repository.people().single { it.id == sentinelId }
            assertEquals(sentinelName, person.name)
            assertTrue(person.imagePaths.contains(sentinelPath))
        }
        // Dedicated fictional app-private preference, independent of People SQLite.
        // .commit() must finish before the test process exits and adb install -r runs.
        assertTrue("Failed to persist synthetic settings before the APK upgrade",
            context.getSharedPreferences(settingsName, android.content.Context.MODE_PRIVATE)
                .edit()
                .putString(settingsKey, settingsValue)
                .putBoolean(mismatchRestoreKey, false)
                .commit())
        // This is an actual ATLAS preference used by GalleryViewModel V4
        // synchronization. Using the digest key alone avoids any fictitious
        // SAF URI or attempted access to real documents.
        assertTrue("Failed to persist the real ATLAS V4 sync setting",
            context.getSharedPreferences(realSettingsName, android.content.Context.MODE_PRIVATE)
                .edit().putString(realSettingsKey, realSettingsValue).commit())
    }

    /**
     * Generate a consistent standalone SQLite snapshot while the test app is
     * still installed. VACUUM INTO includes committed WAL records and needs no
     * real device files, credentials or row-by-row adb access.
     *
     * Caller MUST be isolated emulator CI; never invoke on the user's phone.
     */
    @Test fun snapshotBeforeSyntheticSignerMismatch() {
        assumeTrue("Emulator-only synthetic migration phase",
            InstrumentationRegistry.getArguments().getString("atlasUpgradePhase") == "snapshot")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val snapshot = java.io.File(context.filesDir, "atlas-ci-people-snapshot.sqlite3")
        check(!snapshot.exists()) { "Refusing to overwrite an existing synthetic snapshot" }
        PeopleRepository(context).use { repository ->
            val person = repository.people().single { it.id == sentinelId }
            assertEquals(sentinelName, person.name)
            val sqlPath = snapshot.absolutePath.replace("'", "''")
            repository.writableDatabase.execSQL("VACUUM INTO '$sqlPath'")
        }
        assertTrue("Standalone synthetic snapshot was not created", snapshot.length() > 100)
    }

    /**
     * Only after CI has confirmed Android REJECTS an incompatible signer,
     * explicitly erased its isolated emulator fixture and independently
     * validated + exported the backed-up synthetic database.
     */
    @Test fun restoreAfterSyntheticSignerMismatch() {
        assumeTrue("Emulator-only synthetic restore phase",
            InstrumentationRegistry.getArguments().getString("atlasUpgradePhase") == "restore")
        val encoded = requireNotNull(
            InstrumentationRegistry.getArguments().getString("atlasRecoveryCsvB64")
        ) { "No synthetic offline recovery CSV supplied" }
        val csv = String(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT),
            Charsets.UTF_8)
        PeopleRepository(InstrumentationRegistry.getInstrumentation().targetContext).use { repository ->
            assertTrue("New signer must start with an empty synthetic People DB",
                repository.people().isEmpty())
            assertEquals(1, repository.replaceFromV4Export(csv))
            val person = repository.people().single { it.id == sentinelId }
            assertEquals(sentinelName, person.name)
            assertEquals(setOf(sentinelPath), person.imagePaths)
        }
        // An incompatible signer forces an emulator-only uninstall; that cannot
        // preserve SharedPreferences. Explicitly flag this DIFFERENT recovery path
        // rather than falsely claiming ordinary install -r settings persistence.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue("Failed to mark synthetic signer-mismatch recovery",
            context.getSharedPreferences(settingsName, android.content.Context.MODE_PRIVATE)
                .edit().putBoolean(mismatchRestoreKey, true).commit())
    }

    @Test fun verifyAfterUpgrade() {
        assumeTrue("Only dedicated upgrade phase runs this",
            InstrumentationRegistry.getArguments().getString("atlasUpgradePhase") == "verify")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PeopleRepository(context).use { repository ->
            // The second phase MUST NOT write/seed the database. A missing row
            // means that the upgrade lost the user's synthetic private People data.
            val person = repository.people().single { it.id == sentinelId }
            assertEquals(sentinelName, person.name)
            assertEquals(setOf(sentinelPath), person.imagePaths)
        }
        val settings = context.getSharedPreferences(settingsName, android.content.Context.MODE_PRIVATE)
        if (!settings.getBoolean(mismatchRestoreKey, false)) {
            // Read-only after genuine same-signer adb install -r: no re-seeding.
            assertEquals("App-private synthetic setting lost across APK upgrade",
                settingsValue, settings.getString(settingsKey, null))
            // Verify the existing application setting, not just the test-only
            // sentinel. No production values or documents are involved.
            assertEquals("Existing ATLAS V4 sync digest setting lost across APK upgrade",
                realSettingsValue,
                context.getSharedPreferences(realSettingsName, android.content.Context.MODE_PRIVATE)
                    .getString(realSettingsKey, null))
        }
        // The opt-in forced-uninstall/mismatched-signer path is NOT accepted as
        // settings-retaining; it is a separate synthetic People-only restoration.
    }

    /**
     * Last phase of the isolated emulator proof. The seed must survive both
     * install -r and (when enabled) the signer-mismatch snapshot/restore, but
     * must not be visible to the subsequent People screenshot tests.
     *
     * Delete the exact fictional assignment, never the whole app database.
     */
    @Test fun cleanupAfterUpgradeForPeopleEvidence() {
        assumeTrue("Only final isolated-emulator cleanup runs this",
            InstrumentationRegistry.getArguments().getString("atlasUpgradePhase") == "cleanup")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PeopleRepository(context).use { repository ->
            val removed = repository.writableDatabase.delete(
                "assignments",
                "face_id=? AND person_id=? AND person_name=? AND image_path=?",
                arrayOf("9071", sentinelId.toString(), sentinelName, sentinelPath))
            assertEquals("Exactly the invented upgrade row must be removed", 1, removed)
            assertTrue("People evidence would inherit another test's confirmed people",
                repository.people().isEmpty())
        }
        // Remove only test-created settings. The real preference namespace may
        // contain other fields, so remove its digest only if it is our fixture.
        assertTrue("Synthetic upgrade settings cleanup failed",
            context.getSharedPreferences(settingsName, android.content.Context.MODE_PRIVATE)
                .edit().clear().commit())
        val live = context.getSharedPreferences(
            realSettingsName, android.content.Context.MODE_PRIVATE)
        if (live.getString(realSettingsKey, null) == realSettingsValue) {
            assertTrue("Synthetic V4 digest cleanup failed",
                live.edit().remove(realSettingsKey).commit())
        }
    }
}
