package nl.atlas.gallery

/**
 * Startup admission fence. Only tasks needed to present the first, grant-correct
 * cached grid may execute synchronously in Application.onCreate.
 *
 * Do not place media-store queries, model/session creation, People indexing,
 * background scheduling or secondary disk accounting in the critical lane.
 */
internal enum class StartupTask {
    CRASH_HANDLER,
    MICRO_THUMBNAIL_STORE,
    THUMBNAIL_FLAGS,
    MEDIA_GRANT,
    IMAGE_LOADER_FACTORY,
    MICRO_THUMBNAIL_DISK_ACCOUNTING,
    PEOPLE_STORE,
    ONNX_SESSION,
    VISUAL_INDEX,
    WORK_SCHEDULING,
    PHOTO_DNA,
    STORIES
}

internal enum class StartupAdmission { CRITICAL, DEFERRED }

internal object StartupTaskAdmission {
    private val critical = setOf(
        StartupTask.CRASH_HANDLER,
        StartupTask.MICRO_THUMBNAIL_STORE,
        StartupTask.THUMBNAIL_FLAGS,
        StartupTask.MEDIA_GRANT,
        StartupTask.IMAGE_LOADER_FACTORY
    )

    fun admission(task: StartupTask): StartupAdmission =
        if (task in critical) StartupAdmission.CRITICAL else StartupAdmission.DEFERRED

    /** Fails closed if any unrecognised or newly added startup task is admitted. */
    fun verifyCriticalTasks(tasks: Iterable<StartupTask>): Boolean =
        tasks.all { admission(it) == StartupAdmission.CRITICAL }
}
