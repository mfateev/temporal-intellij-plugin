package io.temporal.intellij.ui

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.utils.waitFor
import java.time.Duration

/**
 * Singleton manager that connects to a running IDE for UI tests.
 *
 * The IDE must be started separately with:
 *   ./gradlew runIdeForUiTests
 *
 * Run tests with:
 *   ./gradlew uiTest
 */
object SharedIdeManager {
    const val ROBOT_PORT = 8082
    const val ROBOT_URL = "http://127.0.0.1:$ROBOT_PORT"

    lateinit var remoteRobot: RemoteRobot
        private set

    lateinit var inspector: IdeInspector
        private set

    private var connected = false

    /**
     * Waits for IDE to be available and connects to it.
     * Returns true if connected, false if timeout.
     */
    @Synchronized
    fun waitForIde(timeout: Duration = Duration.ofMinutes(2)): Boolean {
        if (connected && isIdeResponsive()) {
            return true
        }

        println("====================================")
        println("Waiting for IDE on port $ROBOT_PORT...")
        println("====================================")
        println("Make sure IDE is running: ./gradlew runIdeForUiTests")

        remoteRobot = RemoteRobot(ROBOT_URL)
        inspector = IdeInspector()

        val ready = try {
            waitFor(timeout, Duration.ofSeconds(3)) {
                try {
                    remoteRobot.callJs<Boolean>("true")
                } catch (e: Exception) {
                    print(".")
                    System.err.println("Connection attempt failed: ${e.javaClass.simpleName}: ${e.message}")
                    false
                }
            }
            true
        } catch (e: Exception) {
            println()
            println("Timeout waiting for IDE. Is it running?")
            println("Start it with: ./gradlew runIdeForUiTests")
            System.err.println("Final timeout exception: ${e.javaClass.simpleName}: ${e.message}")
            false
        }

        if (ready) {
            connected = true
            println()
            println("====================================")
            println("Connected to IDE!")
            println("====================================")
        }

        return ready
    }

    private fun isIdeResponsive(): Boolean {
        return try {
            remoteRobot.callJs<Boolean>("true")
        } catch (e: Exception) {
            false
        }
    }
}
