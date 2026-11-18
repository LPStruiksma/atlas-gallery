package nl.atlas.gallery.featureaiedit.network

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import nl.atlas.gallery.featureaiedit.settings.ProviderCredentialVault
import nl.atlas.gallery.featureaiedit.settings.ProviderProfile
import nl.atlas.gallery.featureaiedit.settings.ProviderSettingsStore
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Explicit selected-image handoff only. Inputs and job IDs are private to this app, not in
 * WorkManager Data, backups, the photo library, a diagnostic or a usage export.
 */
object EditJobRunner {
    private const val PREFIX = "atlas-ai-edit-"
    private const val MAX_IMAGE_BYTES = 12_000_000L

    /**
     * Caller must have shown first-use consent and chosen the image; there is no scanner path.
     * Re-encoding pixels removes EXIF/GPS unless the user explicitly opted to keep it.
     */
    fun enqueue(
        context: Context,
        providerId: String,
        selectedImage: Uri,
        instruction: String
    ): String {
        val app = context.applicationContext
        val settings = ProviderSettingsStore(app)
        require(settings.firstUseAcknowledged) { "First-use explanation has not been acknowledged" }
        require(settings.profiles().any { it.id == providerId }) { "Unknown provider" }
        require(instruction.isNotBlank() && instruction.length <= 8_000)
        val id = UUID.randomUUID().toString()
        val directory = EditJobJournal.directory(app, id)
        check(directory.mkdirs())
        try {
            val image = prepareSelectedImage(app, selectedImage, directory, settings.keepLocationInUploads)
            EditJobJournal.write(directory, JSONObject().apply {
                put("id", id)
                put("provider", providerId)
                put("instruction", instruction)
                put("imageName", image.first)
                put("mime", image.second)
                put("state", "ready")
                put("createdAtMs", System.currentTimeMillis())
            })
            schedule(app, id, expedited = true)
            return id
        } catch (failure: Exception) {
            directory.deleteRecursively()
            throw IllegalArgumentException("Selected image could not be prepared")
        }
    }

    /** Never read arbitrary source paths from WorkManager; source only exists inside our job dir. */
    private fun prepareSelectedImage(
        context: Context, selected: Uri, directory: File, keepMetadata: Boolean
    ): Pair<String, String> {
        if (keepMetadata) {
            val bytes = requireNotNull(context.contentResolver.openInputStream(selected)).use {
                it.readBytes(MAX_IMAGE_BYTES)
            }
            val jpeg = bytes.size >= 3 && bytes[0] == 0xff.toByte() &&
                bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte()
            val png = bytes.size >= 8 && bytes.sliceArray(0 until 8).contentEquals(
                byteArrayOf(0x89.toByte(), 80, 78, 71, 13, 10, 26, 10))
            require(jpeg || png)
            val name = if (jpeg) "selected.jpg" else "selected.png"
            File(directory, name).writeBytes(bytes)
            return name to if (jpeg) "image/jpeg" else "image/png"
        }
        // A new pixel encoding has no EXIF, GPS, orientation tags or original filename.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(selected).use {
            requireNotNull(it)
            BitmapFactory.decodeStream(it, null, bounds)
        }
        require(bounds.outWidth > 0 && bounds.outHeight > 0 &&
            bounds.outWidth.toLong() * bounds.outHeight <= 16_777_216L)
        val bitmap = context.contentResolver.openInputStream(selected).use {
            requireNotNull(it)
            BitmapFactory.decodeStream(it) ?: error("Could not decode image")
        }
        try {
            val name = "selected.png"
            FileOutputStream(File(directory, name)).use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                it.fd.sync()
            }
            require(File(directory, name).length() in 1..MAX_IMAGE_BYTES)
            return name to "image/png"
        } finally {
            bitmap.recycle()
        }
    }

    private fun java.io.InputStream.readBytes(max: Long): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var n = 0L
        while (true) {
            val read = read(buffer)
            if (read < 0) return output.toByteArray()
            n += read
            require(n <= max)
            output.write(buffer, 0, read)
        }
    }

    internal fun schedule(context: Context, id: String, expedited: Boolean = false) {
        val builder = OneTimeWorkRequest.Builder(EditJobWorker::class.java)
            .setInputData(Data.Builder().putString("id", id).build())
            .setConstraints(Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
        if (expedited) builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        else builder.setInitialDelay(5, TimeUnit.SECONDS)
        val work = builder.build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(PREFIX + id,
                if (expedited) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.APPEND_OR_REPLACE, work)
    }

    /** A local cancel is immediate; remote DELETE is a separate best-effort scheduled step. */
    fun cancel(context: Context, id: String) {
        val app = context.applicationContext
        val directory = EditJobJournal.directory(app, id)
        EditJobJournal.change(directory) {
            if (it.optString("state") !in setOf("done", "refused", "error", "cancelled")) {
                it.put("state", "cancelled")
            }
        }
        WorkManager.getInstance(app).cancelUniqueWork(PREFIX + id)
        enqueueRemoteCancel(app, id)
    }

    internal fun enqueueRemoteCancel(context: Context, id: String) {
        val work = OneTimeWorkRequest.Builder(EditCancelWorker::class.java)
            .setInputData(Data.Builder().putString("id", id).build())
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueue(work)
    }

    /** Only completed app-private outputs can be opened by the editor/save flow. */
    fun cachedResult(context: Context, id: String): File? {
        val data = EditJobJournal.read(EditJobJournal.directory(context, id)) ?: return null
        if (data.optString("state") != "done" || data.optString("resultName") != id + ".bin")
            return null
        return File(context.cacheDir, "ai_edit_outputs/" + id + ".bin")
            .takeIf { it.isFile && it.length() in 1..32_000_000L }
    }

    fun snapshot(context: Context, id: String): EditJobState? {
        val data = EditJobJournal.read(EditJobJournal.directory(context, id)) ?: return null
        return EditJobState(data.optString("state"), data.optString("failure").takeIf { it.isNotBlank() },
            data.optInt("progress", 0))
    }
}

data class EditJobState(val state: String, val failure: String?, val progress: Int)

/** Atomic app-private journal: remote ID survives process death; never contains an API key. */
internal object EditJobJournal {
    private val idFormat = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    fun directory(context: Context, id: String): File {
        require(idFormat.matches(id))
        return File(context.applicationContext.noBackupFilesDir, "ai_edit_jobs/" + id)
    }
    @Synchronized fun read(directory: File): JSONObject? = try {
        val source = File(directory, "job.json")
        if (!source.isFile || source.length() > 12_000) null else JSONObject(source.readText())
    } catch (_: Exception) { null }

    @Synchronized fun write(directory: File, data: JSONObject) {
        check(directory.isDirectory)
        val temp = File.createTempFile(".journal-", ".tmp", directory)
        try {
            FileOutputStream(temp).use {
                it.write(data.toString().toByteArray(Charsets.UTF_8))
                it.fd.sync()
            }
            check(temp.renameTo(File(directory, "job.json")))
        } finally { temp.delete() }
    }
    @Synchronized fun change(directory: File, apply: (JSONObject) -> Unit): JSONObject? {
        val current = read(directory) ?: return null
        apply(current)
        write(directory, current)
        return current
    }
}

/** Preserves a user's local cancel while publishing the accepted remote ID atomically. */
internal fun recordAcceptedSubmission(folder: File, remoteId: String): Boolean {
    val state = EditJobJournal.change(folder) {
        it.put("remoteId", remoteId)
        if (it.optString("state") != "cancelled") it.put("state", "polling")
        it.remove("instruction")
    }?.optString("state")
    return state == "cancelled"
}

class EditJobWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val cancellation = EditRequestCancellation()

    // Pre-Android 12 expedited WorkManager runs as a foreground service.
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = "atlas-ai-edit-jobs"
        manager.createNotificationChannel(NotificationChannel(channel, "AI image edits",
            NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(applicationContext, channel)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("ATLAS AI Edit")
            .setContentText("Processing your selected image")
            .setOngoing(true).build()
        return ForegroundInfo(26015, notification)
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // WorkManager cancels the coroutine; a blocking HttpURLConnection needs an explicit disconnect.
        val workerJob = currentCoroutineContext().job
        val stopHook = workerJob.invokeOnCompletion { cancellation.cancel() }
        try {
        val id = inputData.getString("id") ?: return@withContext Result.failure()
        val folder = try { EditJobJournal.directory(applicationContext, id) }
            catch (_: Exception) { return@withContext Result.failure() }
        val data = EditJobJournal.read(folder) ?: return@withContext Result.failure()
        if (data.optString("state") in setOf("cancelled", "done", "error", "refused"))
            return@withContext Result.success()
        if (System.currentTimeMillis() - data.optLong("createdAtMs", 0L) > 50L * 60_000L) {
            fail(folder, "JOB_EXPIRED")
            return@withContext Result.failure()
        }
        val settings = ProviderSettingsStore(applicationContext)
        if (!settings.firstUseAcknowledged) {
            fail(folder, "CONSENT_REQUIRED")
            return@withContext Result.failure()
        }
        val profile = settings.profiles().firstOrNull { it.id == data.optString("provider") }
        if (profile == null) {
            fail(folder, "PROVIDER_REMOVED")
            return@withContext Result.failure()
        }
        val auth = profile.authentication(ProviderCredentialVault(applicationContext))
        if (auth == null) {
            fail(folder, "CREDENTIAL_MISSING")
            return@withContext Result.failure()
        }
        val config = profile.transportConfig()
        val transport = EditHttpTransport()
        var remoteId = data.optString("remoteId")
        if (remoteId.isBlank()) {
            // Failed or interrupted POST is never replayed: it may have incurred a bill.
            if (data.optString("state") != "ready") {
                fail(folder, "SUBMISSION_UNCERTAIN")
                return@withContext Result.failure()
            }
            val capability = transport.execute(config,
                EditHttpRequest(EditHttpMethod.GET, "capabilities"), auth, cancellation)
            if (capability !is EditHttpResult.Success ||
                !supportsSingleImage(capability.bytes)) {
                fail(folder, "CAPABILITY_UNAVAILABLE")
                return@withContext Result.failure()
            }
            val imageName = data.optString("imageName")
            if (imageName !in setOf("selected.png", "selected.jpg")) {
                fail(folder, "INVALID_IMAGE")
                return@withContext Result.failure()
            }
            val bytes = try { File(folder, imageName).readBytes() }
                catch (_: Exception) { fail(folder, "INVALID_IMAGE"); return@withContext Result.failure() }
            if (bytes.isEmpty() || bytes.size > 12_000_000) {
                fail(folder, "INVALID_IMAGE")
                return@withContext Result.failure()
            }
            // Cancellation may race capabilities/serialization; never send a cancelled job.
            val canSubmit = EditJobJournal.change(folder) {
                if (it.optString("state") == "ready" && !isStopped && !cancellation.isCancelled)
                    it.put("state", "submitting")
            }?.optString("state") == "submitting"
            if (!canSubmit || isStopped || cancellation.isCancelled)
                return@withContext Result.success()
            val upload = transport.execute(config, EditHttpRequest(EditHttpMethod.POST, "edits",
                EditHttpBody.Multipart(listOf(
                    EditMultipartPart.Binary("image", imageName, data.getString("mime"), bytes),
                    EditMultipartPart.Text("instruction", data.getString("instruction"))
                ))), auth, cancellation)
            if (upload !is EditHttpResult.Success || upload.status != 202) {
                fail(folder, if (upload is EditHttpResult.Failed) upload.code.name else "SUBMISSION_UNCERTAIN")
                return@withContext Result.failure()
            }
            remoteId = try { JSONObject(String(upload.bytes, Charsets.UTF_8)).getString("id") }
                catch (_: Exception) { "" }
            if (!remoteId.matches(Regex("[0-9a-f]{32}"))) {
                fail(folder, "INVALID_JOB_ID")
                return@withContext Result.failure()
            }
            val cancelledAfterSubmit = recordAcceptedSubmission(folder, remoteId)
            File(folder, imageName).delete()
            if (cancelledAfterSubmit || isStopped || cancellation.isCancelled ||
                EditJobJournal.read(folder)?.optString("state") == "cancelled") {
                // A prior cancel worker may already have run without the remote ID.
                EditJobJournal.change(folder) { it.put("state", "cancelled") }
                EditJobRunner.enqueueRemoteCancel(applicationContext, id)
                return@withContext Result.success()
            }
        }
        if (!remoteId.matches(Regex("[0-9a-f]{32}"))) {
            fail(folder, "INVALID_JOB_ID")
            return@withContext Result.failure()
        }
        val polled = transport.execute(config,
            EditHttpRequest(EditHttpMethod.GET, "edits/" + remoteId), auth, cancellation)
        if (polled !is EditHttpResult.Success) {
            if (polled is EditHttpResult.Failed && polled.retryable && !isStopped)
                return@withContext Result.retry()
            fail(folder, if (polled is EditHttpResult.Failed) polled.code.name else "CANCELLED")
            return@withContext Result.failure()
        }
        val status = try { JSONObject(String(polled.bytes, Charsets.UTF_8)) }
            catch (_: Exception) { fail(folder, "INVALID_REPLY"); return@withContext Result.failure() }
        when (status.optString("status")) {
            "queued", "running" -> {
                val progress = status.optInt("progress", 0).coerceIn(0, 100)
                EditJobJournal.change(folder) {
                    if (it.optString("state") != "cancelled") {
                        it.put("state", "polling")
                        it.put("progress", progress)
                    }
                }
                if (!isStopped && !cancellation.isCancelled) EditJobRunner.schedule(applicationContext, id)
                Result.success()
            }
            "succeeded" -> {
                val outputFolder = File(applicationContext.cacheDir, "ai_edit_outputs")
                outputFolder.mkdirs()
                val output = File(outputFolder, id + ".bin")
                if (output.exists()) output.delete()
                val result = transport.execute(config,
                    EditHttpRequest(EditHttpMethod.GET, "edits/" + remoteId + "/result"),
                    auth, cancellation, downloadTo = output)
                if (result is EditHttpResult.Success && imageSignatureValid(output) &&
                    !cancellation.isCancelled && !isStopped &&
                    EditJobJournal.read(folder)?.optString("state") != "cancelled") {
                    EditJobJournal.change(folder) {
                        it.put("state", "done")
                        it.put("progress", 100)
                        it.put("resultName", id + ".bin")
                    }
                    Result.success()
                } else {
                    output.delete()
                    fail(folder, "DOWNLOAD_FAILED")
                    Result.failure()
                }
            }
            "refused" -> {
                EditJobJournal.change(folder) {
                    it.put("state", "refused")
                    it.put("failure", "PROVIDER_REFUSED") // never store provider free-text or prompt
                }
                Result.success()
            }
            "cancelled" -> {
                EditJobJournal.change(folder) { it.put("state", "cancelled") }
                Result.success()
            }
            else -> { fail(folder, "REMOTE_ERROR"); Result.failure() }
        }
        } finally { stopHook?.dispose() }
    }

    private fun supportsSingleImage(bytes: ByteArray): Boolean = try {
        val envelope = JSONObject(String(bytes, Charsets.UTF_8))
        val features = envelope.getJSONObject("features")
        envelope.getInt("schema_version") == 1 &&
            envelope.getString("protocol_version") == "1.0" &&
            features.getBoolean("instruction_edit") &&
            features.getJSONArray("input_formats").toString().contains("image/png")
    } catch (_: Exception) { false }

    private fun imageSignatureValid(file: File): Boolean {
        return try {
        if (!file.isFile || file.length() !in 1..32_000_000L) return false
        val header = ByteArray(8)
        val read = file.inputStream().use { it.read(header) }
        (read >= 3 && header[0] == 0xff.toByte() &&
            header[1] == 0xd8.toByte() && header[2] == 0xff.toByte()) ||
            (read == 8 && header.contentEquals(byteArrayOf(
                0x89.toByte(), 80, 78, 71, 13, 10, 26, 10)))
    } catch (_: Exception) { false }
    }

    private fun fail(folder: File, code: String) {
        EditJobJournal.change(folder) {
            if (it.optString("state") != "cancelled") {
                it.put("state", "error")
                it.put("failure", code)
            }
            it.remove("instruction")
        }
        File(folder, "selected.png").delete()
        File(folder, "selected.jpg").delete()
    }
}

/** Best-effort DELETE of one known remote job; never cancels other users' jobs. */
class EditCancelWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getString("id") ?: return@withContext Result.success()
        val data = try { EditJobJournal.read(EditJobJournal.directory(applicationContext, id)) }
            catch (_: Exception) { null } ?: return@withContext Result.success()
        val remoteId = data.optString("remoteId")
        if (!remoteId.matches(Regex("[0-9a-f]{32}"))) return@withContext Result.success()
        val profile = ProviderSettingsStore(applicationContext).profiles()
            .firstOrNull { it.id == data.optString("provider") } ?: return@withContext Result.success()
        val auth = profile.authentication(ProviderCredentialVault(applicationContext))
            ?: return@withContext Result.success()
        EditHttpTransport().execute(profile.transportConfig(),
            EditHttpRequest(EditHttpMethod.DELETE, "edits/" + remoteId), auth)
        Result.success()
    }
}
