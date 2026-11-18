package nl.atlas.gallery

/**
 * A lightweight holder for an IO-only resource. No file, asset or native session is
 * touched by construction. Explicit admission avoids hiding a first-open disk read
 * in a Compose remember() or ViewModel initializer.
 *
 * Caller owns lifecycle/close of the returned resource. Failures are not cached,
 * and successful publication is serialized so two workers cannot double-open.
 */
internal class LazyBackgroundHolder<T : Any>(
    private val name: String,
    private val initialize: () -> T,
    private val isMainThread: () -> Boolean = {
        android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
    }
) {
    @Volatile private var opened: T? = null
    val isOpened: Boolean get() = opened != null

    fun get(): T {
        check(!isMainThread()) { "$name must be opened on a background thread" }
        opened?.let { return it }
        return synchronized(this) {
            opened ?: initialize().also { opened = it }
        }
    }
}
