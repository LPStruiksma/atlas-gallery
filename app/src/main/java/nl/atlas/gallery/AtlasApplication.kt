package nl.atlas.gallery

import android.app.Application
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.video.VideoFrameDecoder
import nl.atlas.gallery.featuremedia.MicroThumbnails
import nl.atlas.gallery.featuremedia.ThumbnailPipeline
import nl.atlas.gallery.featuremedia.runningUnderInstrumentation
import nl.atlas.gallery.featuremedia.thumbnailMemoryCacheBytes
import kotlinx.coroutines.Dispatchers

class AtlasApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Persist one privacy-redacted trace before Android's original crash handler runs.
        LastCrashRecorder.install(this)
        // #555: ATLAS's micro-thumbnail store; only used under full media access.
        MicroThumbnails.install(this)
        ThumbnailPipeline.autoWarm = !runningUnderInstrumentation()
        readGalleryMediaGrant(this).let { MicroThumbnails.applyGrant(it.images, it.videos, it.selected) }
        SingletonImageLoader.setSafe { context ->
            ImageLoader.Builder(context)
                .components {
                    add(VideoFrameDecoder.Factory())
                }
                // #555: hold several screens of tiles, sized to the device class (Samsung T1).
                .memoryCache {
                    MemoryCache.Builder().maxSizeBytes(thumbnailMemoryCacheBytes(context)).build()
                }
                // Run Coil's interceptor chain on Main, so every caller resumes
                // on Main after fetch/decode (which stay on Dispatchers.IO).
                // In the app every execute() caller and every AsyncImage job is
                // already on Main, so nothing changes there. Under Compose UI
                // tests, the composition's interceptor is not a
                // CoroutineDispatcher, so coil-compose launches AsyncImagePainter
                // jobs on Dispatchers.Unconfined. Without this, they resumed on an
                // IO worker and updated painter/Compose state there, racing
                // SnapshotStateObserver on Main (#530: "null cannot be cast to
                // DerivedState" in recordInvalidation).
                .interceptorCoroutineContext(Dispatchers.Main.immediate)
                .build()
        }
    }
}
