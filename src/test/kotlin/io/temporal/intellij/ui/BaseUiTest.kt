package io.temporal.intellij.ui

import com.intellij.remoterobot.RemoteRobot
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.time.Duration

/**
 * Base class for UI tests that require a running IDE.
 *
 * Before running tests, start the IDE with:
 *   ./gradlew runIdeForUiTests
 *
 * Then run tests with:
 *   ./gradlew uiTest
 *
 * Tests will FAIL if IDE is not running.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class BaseUiTest {

    protected val remoteRobot: RemoteRobot
        get() = SharedIdeManager.remoteRobot

    protected val inspector: IdeInspector
        get() = SharedIdeManager.inspector

    @BeforeAll
    fun waitForIde() {
        val connected = SharedIdeManager.waitForIde(Duration.ofSeconds(15))
        assertTrue(connected) {
            """
            ═══════════════════════════════════════════════════
            IDE IS NOT RUNNING!

            Start the IDE first:
              ./gradlew runIdeForUiTests

            Then run tests:
              ./gradlew uiTest
            ═══════════════════════════════════════════════════
            """.trimIndent()
        }
    }
}
