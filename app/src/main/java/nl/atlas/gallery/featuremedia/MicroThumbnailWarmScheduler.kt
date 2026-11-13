package nl.atlas.gallery.featuremedia

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import nl.atlas.gallery.GalleryRepository
import nl.atlas.gallery.readGalleryMediaGrant

/** Deferred first-fill work carries no photo IDs or grants across processes. */
internal object MicroThumbnailWarmScheduler {
    private const val UNIQUE_NAME = "atlas-micro-warm-charging-v1"

    fun request(): OneTimeWorkRequest = OneTimeWorkRequest.Builder(MicroThumbnailWarmWorker::class.java)
        .setConstraints(Constraints.Builder().setRequiresCharging(true).build())
        .build()

    fun enqueue(context: Context) {
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request())
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_NAME)
    }
}

internal class MicroThumbnailWarmWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val context = applicationContext
        val grant = readGalleryMediaGrant(context)
        if (!microThumbnailsAllowed(grant.images, grant.videos, grant.selected) ||
            !MicroThumbnails.active || !ThumbnailPipeline.autoWarm) return@withContext Result.success()
        try {
            val coroutine = currentCoroutineContext()
            // Re-read the live MediaStore inventory; never trust work inputs or a cached list.
            val media = GalleryRepository(context).load(checkActive = { coroutine.ensureActive() })
            coroutine.ensureActive()
            if (readGalleryMediaGrant(context) != grant || !MicroThumbnails.active)
                return@withContext Result.success()
            MicroThumbnailWarmer.warm(context.contentResolver, media,
                gate = { readGalleryMediaGrant(context) == grant && MicroThumbnails.active })
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            Result.success() // grant revoked; never retry stale media
        } catch (_: Exception) {
            Result.retry() // transient provider snapshot or IO failure
        }
    }
}
