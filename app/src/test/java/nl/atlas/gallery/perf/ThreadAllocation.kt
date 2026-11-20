package nl.atlas.gallery.perf

/**
 * Current-thread allocated bytes on HotSpot, via reflection because Android
 * unit tests compile against android.jar (no java.lang.management). Null
 * elsewhere: report n/a rather than manufacture an allocation number.
 */
internal fun threadAllocatedBytes(): Long? = runCatching {
    val bean = Class.forName("java.lang.management.ManagementFactory")
        .getMethod("getThreadMXBean").invoke(null)
    val type = Class.forName("com.sun.management.ThreadMXBean")
    if (!type.isInstance(bean)) return null
    if (type.getMethod("isThreadAllocatedMemorySupported").invoke(bean) != true) return null
    if (type.getMethod("isThreadAllocatedMemoryEnabled").invoke(bean) != true) return null
    (type.getMethod("getThreadAllocatedBytes", java.lang.Long.TYPE)
        .invoke(bean, Thread.currentThread().id) as Number).toLong()
}.getOrNull()
