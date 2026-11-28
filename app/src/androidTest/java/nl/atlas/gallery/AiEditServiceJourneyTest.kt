package nl.atlas.gallery

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import nl.atlas.gallery.featureaiedit.engine.AiEditRequestRecord
import nl.atlas.gallery.featureaiedit.engine.AiEditServices
import nl.atlas.gallery.featureaiedit.engine.AndroidRequestJournal
import nl.atlas.gallery.featureaiedit.engine.KeyFingerprint
import nl.atlas.gallery.featureaiedit.network.EditHttpResult
import nl.atlas.gallery.featureaiedit.provider.RemoteHandle
import nl.atlas.gallery.featureaiedit.provider.RemoteRecovery
import nl.atlas.gallery.featureaiedit.provider.hosted.fal.FalCall
import nl.atlas.gallery.featureaiedit.provider.hosted.fal.FalExchange
import nl.atlas.gallery.featureaiedit.provider.hosted.fal.FalImageEditProvider
import nl.atlas.gallery.featureaiedit.provider.hosted.fal.FalMethod
import nl.atlas.gallery.featureaiedit.provider.hosted.fal.FalModel
import nl.atlas.gallery.featureaiedit.provider.hosted.fal.FalTiming
import nl.atlas.gallery.featureaiedit.save.AiEditLineageStore
import nl.atlas.gallery.featureaiedit.settings.ProviderAuthType
import nl.atlas.gallery.featureaiedit.settings.ProviderCredentialVault
import nl.atlas.gallery.featureaiedit.settings.ProviderKind
import nl.atlas.gallery.featureaiedit.settings.ProviderProfile
import nl.atlas.gallery.featureaiedit.settings.ProviderSettingsStore
import nl.atlas.gallery.featureaiedit.ui.AiEditScreen
import nl.atlas.gallery.featureaiedit.ui.AiEditWorkingStore
import nl.atlas.gallery.featuredesign.AtlasGalleryTheme
import nl.atlas.gallery.testkit.KeyboardRule
import nl.atlas.gallery.testkit.TestKeyboard
import nl.atlas.gallery.testkit.awaitThat
import nl.atlas.gallery.testkit.typeText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AIE-20 (#431) end to end on the emulator: the real editor, view model, request journal, photo
 * preparation and fal adapter, with only fal's HTTP exchange replaced by an in-memory fake. No
 * network, no real key, no personal photo: one locally drawn MediaStore PNG, isolated preferences,
 * an isolated key vault and a synthetic key assembled at runtime.
 */
@RunWith(AndroidJUnit4::class)
class AiEditServiceJourneyTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(KeyboardRule(TestKeyboard.Fixed))
        .around(S24DisplayRule()).around(compose)

    /** Scripted fal queue for request id req-1; records every call it sees. */
    private class FakeFal : FalExchange {
        val calls = CopyOnWriteArrayList<String>()
        val bodies = CopyOnWriteArrayList<String>()
        @Volatile var holdSubmit: CountDownLatch? = null
        /** fal's safety checker flags the result: a decline after fal accepted (and may have billed) the job. */
        @Volatile var flagNsfw = false
        val submitStarted = AtomicBoolean(false)
        override fun execute(call: FalCall, key: String, cancellation: nl.atlas.gallery.featureaiedit.network.EditRequestCancellation): EditHttpResult {
            calls += "${call.method} ${call.path}"
            fun json(text: String) = EditHttpResult.Success(200, text.toByteArray())
            return when {
                call.method == FalMethod.POST -> {
                    bodies += String(call.json ?: byteArrayOf())
                    submitStarted.set(true)
                    val hold = holdSubmit
                    if (hold != null) {
                        while (!hold.await(25, TimeUnit.MILLISECONDS)) {
                            if (cancellation.isCancelled) return EditHttpResult.Cancelled
                        }
                    }
                    json("""{"request_id":"req-1","status":"IN_QUEUE","queue_position":0}""")
                }
                call.method == FalMethod.PUT -> json("""{"status":"CANCELLATION_REQUESTED"}""")
                call.downloadTo != null -> {
                    call.downloadTo.writeBytes(png(Color.rgb(230, 120, 40)))
                    EditHttpResult.Success(200, file = call.downloadTo)
                }
                call.path.endsWith("/requests/req-1/status") ->
                    json("""{"status":"COMPLETED","request_id":"req-1"}""")
                call.path.endsWith("/requests/req-1") ->
                    json("""{"images":[{"url":"https://v3b.fal.media/files/synthetic/out.png",""" +
                        """"content_type":"image/png","width":160,"height":120}],"has_nsfw_concepts":[$flagNsfw],"seed":1}""")
                else -> EditHttpResult.Failed(nl.atlas.gallery.featureaiedit.network.FailureCode.INVALID_REQUEST, false, 404)
            }
        }
        fun posts() = calls.count { it.startsWith("POST") }
    }

    private fun shown(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    /** Answers every confirmation (first use, unknown price) the way the user would. */
    private fun confirmGates() {
        fun present(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
        repeat(3) {
            // The next gate opens only after the off-main price check, which waitForIdle does not
            // track: wait for a gate, or for the request to have started, before deciding.
            compose.waitUntil(15_000L) {
                present("atlas-ai-edit-gate-confirm") || present("atlas-ai-edit-cancel") ||
                    present("atlas-ai-edit-accept") || present("atlas-ai-edit-slab").not()
            }
            if (!present("atlas-ai-edit-gate-confirm")) return
            compose.onNodeWithTag("atlas-ai-edit-gate-confirm").performClick()
            compose.waitForIdle()
        }
    }

    @Test fun generateSaveCancelAndRestoreAgainstAFakeService() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.grantRuntimePermission(app.packageName, Manifest.permission.READ_MEDIA_IMAGES)
        val tag = UUID.randomUUID().toString().take(8)
        val prefsPrefix = "aie20-journey-$tag-"
        val isolatedDir = File(app.cacheDir, "aie20-journey-$tag").apply { mkdirs() }
        val synthetic: Context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                app.getSharedPreferences(prefsPrefix + name, mode)
            override fun getNoBackupFilesDir(): File = isolatedDir
        }
        val settings = ProviderSettingsStore(synthetic)
        val vault = ProviderCredentialVault(synthetic)
        val profile = ProviderProfile("aie20-fal", "fal.ai", FalImageEditProvider.QUEUE_BASE,
            ProviderAuthType.KEY_SCHEME, headerName = "Authorization", kind = ProviderKind.FAL)
        val key = listOf("synthetic", "fal", "journey", tag).joinToString("-") // guard: allow-synthetic-secret
        val fake = FakeFal()
        val inserted = mutableListOf<Uri>()
        val sessions = mutableListOf<String>()
        var session by mutableStateOf("aie20-$tag-a")
        var savedUri: Uri? = null
        try {
            settings.save(profile)
            settings.setDefault(profile.id)
            vault.put(profile.id, key)
            AiEditServices.onlineOverride = { true }
            AiEditServices.providerOverride = { p, inputs ->
                FalImageEditProvider(model = FalModel.CATALOG.firstOrNull { it.endpoint == p.modelId } ?: FalModel.DEFAULT,
                    apiKey = inputs.apiKey, images = inputs.images, outputDirectory = inputs.outputDirectory,
                    exchange = fake, timing = FalTiming(pollWaitMs = 20, statusIntervalMs = 20))
            }
            val source = insertPng(app, "AIE20_SYNTHETIC_SOURCE_$tag.png").also { inserted += it }
            compose.setContent {
                CompositionLocalProvider(LocalContext provides synthetic) {
                    AtlasGalleryTheme(dark = true) {
                        key(session) {
                            AiEditScreen(sourceUri = source, sessionId = session, onRefresh = {},
                                onSaved = { savedUri = it }, onView = {}, onExit = {})
                        }
                    }
                }
            }
            sessions += session

            // 1. Nothing is sent before Generate.
            compose.awaitThat("composer", 30_000L) { shown(hasTestTag("atlas-ai-edit-prompt")) }
            compose.onNodeWithTag("atlas-ai-edit-provider").assertExists()
            assertEquals("no call before Generate", 0, fake.calls.size)
            compose.typeText(hasTestTag("atlas-ai-edit-prompt"), "Brighten the sky")
            compose.onNodeWithTag("atlas-ai-edit-generate").performClick()
            compose.awaitThat("first-use sheet", 30_000L) { shown(hasTestTag("atlas-ai-edit-gate-FIRST_USE")) }
            assertEquals("no call while the first-use sheet is open", 0, fake.calls.size)
            confirmGates()

            // 2. One submit, then a result; Save copy writes a copy with lineage.
            compose.awaitThat("result", 30_000L) { shown(hasTestTag("atlas-ai-edit-accept")) }
            assertEquals(1, fake.posts())
            val body = fake.bodies.single()
            assertTrue("prompt sent", body.contains("Brighten the sky"))
            assertTrue("photo sent inline, never a device path", body.contains("data:image/") && !body.contains("content://"))
            assertFalse("no request journal after a stored result",
                File(AiEditWorkingStore(synthetic, session).sessionDirectory(), "request.json").exists())
            compose.onNodeWithTag("atlas-ai-edit-accept").performClick()
            compose.awaitThat("saved", 30_000L) { shown(hasTestTag("atlas-ai-edit-saved")) }
            val saved = requireNotNull(savedUri) { "Save copy reported no uri" }
            inserted += saved
            val lineage = AiEditLineageStore(synthetic).all().single { it.editedUri == saved.toString() }
            assertEquals(source.toString(), lineage.originalUri)
            assertEquals("fal.ai", lineage.provider)
            assertEquals("Brighten the sky", lineage.prompt)

            // 3. Cancel while the submit is in flight: the toast says it may still charge, nothing is replayed.
            val hold = CountDownLatch(1)
            fake.holdSubmit = hold
            fake.submitStarted.set(false)
            compose.runOnIdle { session = "aie20-$tag-b" }
            sessions += session
            compose.awaitThat("second composer", 30_000L) { shown(hasTestTag("atlas-ai-edit-prompt")) }
            compose.typeText(hasTestTag("atlas-ai-edit-prompt"), "Warmer light")
            compose.onNodeWithTag("atlas-ai-edit-generate").performClick()
            confirmGates()
            compose.waitUntil(10_000L) { fake.submitStarted.get() }
            compose.awaitThat("working", 30_000L) { shown(hasTestTag("atlas-ai-edit-cancel")) }
            compose.waitForIdle()
            compose.onNodeWithTag("atlas-ai-edit-cancel").performClick()
            compose.awaitThat("cancel toast", 30_000L) { shown(hasTestTag("atlas-ai-edit-toast")) }
            compose.awaitThat("composer after cancel", 30_000L) { shown(hasTestTag("atlas-ai-edit-generate")) }
            hold.countDown()
            fake.holdSubmit = null
            compose.waitForIdle()
            assertEquals("cancel never resubmits", 2, fake.posts())
            assertFalse("cancel clears the journal",
                File(AiEditWorkingStore(synthetic, session).sessionDirectory(), "request.json").exists())

            // 4. Reopened after process death with a saved queue id: status/result only, never a POST.
            val salt = AndroidRequestJournal.salt(synthetic.getSharedPreferences("atlas-ai-edit", Context.MODE_PRIVATE))
            val providerId = "fal:" + FalModel.DEFAULT.endpoint
            fun record(stage: AiEditRequestRecord.Stage, fingerprint: String?) = AiEditRequestRecord(
                generation = 7L, profileId = profile.id, providerId = providerId, serviceName = "fal.ai",
                recovery = RemoteRecovery.QUERY_BY_PERSISTED_ID, stage = stage,
                handle = if (stage == AiEditRequestRecord.Stage.ACCEPTED) RemoteHandle(providerId,
                    FalModel.DEFAULT.endpoint, "req-1", 1, fingerprint) else null,
                prompt = "Restore me")
            fun reopen(name: String, journal: AiEditRequestRecord) {
                val id = "aie20-$tag-$name"
                AndroidRequestJournal(AiEditWorkingStore(synthetic, id).sessionDirectory()).write(journal)
                compose.runOnIdle { session = id }
                sessions += id
            }
            val postsBefore = fake.posts()
            reopen("resume", record(AiEditRequestRecord.Stage.ACCEPTED, KeyFingerprint.of(salt, key)))
            compose.awaitThat("resumed result", 30_000L) { shown(hasTestTag("atlas-ai-edit-accept")) }
            assertEquals("resume never resubmits", postsBefore, fake.posts())
            assertTrue("resume looked up the saved id", fake.calls.any { it.endsWith("/requests/req-1/status") })

            // 5. Sent but no saved id: outcome unknown, nothing can be checked, nothing is sent.
            val callsBefore = fake.calls.size
            reopen("sent", record(AiEditRequestRecord.Stage.SENT, null))
            compose.awaitThat("unknown card", 30_000L) { shown(hasTestTag("atlas-ai-edit-issue-UNKNOWN_NONRECOVERABLE")) }
            compose.onNodeWithTag("atlas-ai-edit-action-TRY_AGAIN_CHARGED").assertExists()
            assertEquals(callsBefore, fake.calls.size)

            // 6. The key was replaced since the request: Check again can't run, nothing is sent.
            val other = listOf("synthetic", "fal", "other", tag).joinToString("-") // guard: allow-synthetic-secret
            reopen("rekeyed", record(AiEditRequestRecord.Stage.ACCEPTED, KeyFingerprint.of(salt, other)))
            compose.awaitThat("cannot check card", 30_000L) { shown(hasTestTag("atlas-ai-edit-issue-UNKNOWN_CANNOT_CHECK")) }
            assertEquals(callsBefore, fake.calls.size)

            // 7. No service connected: the editor offers Connect and never builds a provider.
            settings.delete(profile.id)
            compose.runOnIdle { session = "aie20-$tag-none" }
            sessions += session
            compose.awaitThat("no service", 30_000L) { shown(hasTestTag("atlas-ai-edit-connect")) }
            assertEquals(callsBefore, fake.calls.size)

            // 8. Connect a service from there: back in the editor, Generate uses the new service.
            compose.onNodeWithTag("atlas-ai-edit-connect").performClick()
            compose.awaitThat("settings", 30_000L) { shown(hasTestTag("aie-settings-screen")) }
            settings.save(profile)
            settings.setDefault(profile.id)
            vault.put(profile.id, key)
            compose.onNodeWithTag("aie-back").performClick()
            compose.awaitThat("editor with the new service", 30_000L) {
                !shown(hasTestTag("aie-settings-screen")) && shown(hasTestTag("atlas-ai-edit-generate")) &&
                    shown(hasTestTag("atlas-ai-edit-provider"))
            }
            val postsBeforeConnect = fake.posts()
            compose.typeText(hasTestTag("atlas-ai-edit-prompt"), "Soften the shadows")
            compose.onNodeWithTag("atlas-ai-edit-generate").performClick()
            confirmGates()
            compose.awaitThat("result from the newly connected service", 30_000L) {
                shown(hasTestTag("atlas-ai-edit-accept"))
            }
            assertEquals("Generate sent exactly one request", postsBeforeConnect + 1, fake.posts())

            // 9. fal declines after accepting: Change prompt or typing never skips "may charge again".
            fake.flagNsfw = true
            compose.runOnIdle { session = "aie20-$tag-decline" }
            sessions += session
            compose.awaitThat("decline composer", 30_000L) { shown(hasTestTag("atlas-ai-edit-prompt")) }
            compose.typeText(hasTestTag("atlas-ai-edit-prompt"), "Make it night")
            compose.onNodeWithTag("atlas-ai-edit-generate").performClick()
            confirmGates()
            compose.awaitThat("declined, may have charged", 30_000L) {
                shown(hasTestTag("atlas-ai-edit-issue-DECLINED_MAY_HAVE_CHARGED"))
            }
            fake.flagNsfw = false
            val postsAfterDecline = fake.posts()
            compose.onNodeWithTag("atlas-ai-edit-action-CHANGE_PROMPT").performClick()
            compose.awaitThat("composer after Change prompt", 30_000L) { shown(hasTestTag("atlas-ai-edit-generate")) }
            compose.onNodeWithTag("atlas-ai-edit-generate").performClick()
            compose.awaitThat("charge-again gate after Change prompt", 30_000L) {
                shown(hasTestTag("atlas-ai-edit-gate-CHARGE_AGAIN"))
            }
            assertEquals("nothing sent while the gate is open", postsAfterDecline, fake.posts())
            compose.onNodeWithTag("atlas-ai-edit-gate-dismiss").performClick()
            compose.typeText(hasTestTag("atlas-ai-edit-prompt"), "Make it dusk")
            compose.onNodeWithTag("atlas-ai-edit-generate").performClick()
            compose.awaitThat("charge-again gate after typing", 30_000L) {
                shown(hasTestTag("atlas-ai-edit-gate-CHARGE_AGAIN"))
            }
            assertEquals("Cancel and typing sent nothing", postsAfterDecline, fake.posts())
            compose.onNodeWithTag("atlas-ai-edit-gate-confirm").performClick()
            compose.awaitThat("result after confirming", 30_000L) { shown(hasTestTag("atlas-ai-edit-accept")) }
            assertEquals("one request after Try again", postsAfterDecline + 1, fake.posts())
        } finally {
            AiEditServices.providerOverride = null
            AiEditServices.onlineOverride = null
            inserted.forEach { runCatching { app.contentResolver.delete(it, null, null) } }
            sessions.forEach { File(app.cacheDir, "atlas-ai-edit/$it").deleteRecursively() }
            runCatching { vault.delete(profile.id) }
            for (name in listOf("atlas_ai_edit_endpoints_v1", "atlas-ai-edit")) app.deleteSharedPreferences(prefsPrefix + name)
            isolatedDir.deleteRecursively()
        }
    }

    private fun insertPng(context: Context, name: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ATLAS-AIE20-Fixture/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        requireNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(png(Color.rgb(24, 98, 196))) }
        check(context.contentResolver.update(uri, ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }, null, null) == 1)
        return uri
    }

    private companion object {
        fun png(color: Int): ByteArray {
            val bitmap = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(color)
            return try {
                ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
            } finally { bitmap.recycle() }
        }
    }
}
