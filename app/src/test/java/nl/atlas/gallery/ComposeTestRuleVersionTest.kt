package nl.atlas.gallery

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guard for #530: every instrumented Compose test must use the v2 rules
 * (`androidx.compose.ui.test.junit4.v2.*`).
 *
 * The v1 `createComposeRule()` / `createAndroidComposeRule()` run composition
 * effects on an UnconfinedTestDispatcher. An effect that suspends in
 * `withContext(Dispatchers.Default/IO)` (or awaits Coil) then resumes on that
 * worker thread instead of Main, and the test framework's
 * ApplyingContinuationInterceptor calls `Snapshot.sendApplyNotifications()`
 * there. That drives SnapshotStateObserver off Main while Main is laying out,
 * which flaked emulator-ui as "null cannot be cast to DerivedState" and
 * "Detected multithreaded access to SnapshotStateObserver". The v2 rules use a
 * StandardTestDispatcher, so those continuations resume on Main as in the app.
 */
class ComposeTestRuleVersionTest {
    @Test fun instrumentedTestsUseOnlyV2ComposeRules() {
        val root = listOf(File("src/androidTest"), File("app/src/androidTest"))
            .firstOrNull { it.isDirectory }
            ?: error("androidTest sources not found from ${File(".").absolutePath}")
        val v1 = Regex(
            """^\s*import\s+androidx\.compose\.ui\.test\.junit4\.""" +
                """(createComposeRule|createAndroidComposeRule|createEmptyComposeRule|\*)\s*$"""
        )
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { i, line ->
                    if (v1.matches(line)) "${file.path}:${i + 1}" else null
                }
            }
            .toList()
        assertTrue(
            "Use androidx.compose.ui.test.junit4.v2.* rules (see #530): $offenders",
            offenders.isEmpty()
        )
    }
}
