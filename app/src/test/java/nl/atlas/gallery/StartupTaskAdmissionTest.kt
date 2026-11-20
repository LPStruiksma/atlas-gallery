package nl.atlas.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupTaskAdmissionTest {
    @Test fun onlyFirstGridDependenciesRunInCriticalLane() {
        val critical = listOf(
            StartupTask.CRASH_HANDLER,
            StartupTask.MICRO_THUMBNAIL_STORE,
            StartupTask.THUMBNAIL_FLAGS,
            StartupTask.MEDIA_GRANT,
            StartupTask.IMAGE_LOADER_FACTORY
        )
        assertTrue(StartupTaskAdmission.verifyCriticalTasks(critical))
        assertEquals(critical.toSet(), StartupTask.entries
            .filter { StartupTaskAdmission.admission(it) == StartupAdmission.CRITICAL }.toSet())
    }

    @Test fun costlySecondaryServicesCannotJoinCriticalLane() {
        val deferred = listOf(
            StartupTask.MICRO_THUMBNAIL_DISK_ACCOUNTING,
            StartupTask.PEOPLE_STORE,
            StartupTask.ONNX_SESSION,
            StartupTask.VISUAL_INDEX,
            StartupTask.WORK_SCHEDULING,
            StartupTask.PHOTO_DNA,
            StartupTask.STORIES
        )
        deferred.forEach {
            assertEquals("$it", StartupAdmission.DEFERRED, StartupTaskAdmission.admission(it))
            assertFalse(StartupTaskAdmission.verifyCriticalTasks(listOf(StartupTask.MEDIA_GRANT, it)))
        }
    }
}
