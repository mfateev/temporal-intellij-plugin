package io.temporal.intellij.ui

import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * UI tests that verify basic IDE state and plugin integration.
 *
 * Run with: ./gradlew uiTest
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class IdeInspectorTest : BaseUiTest() {

    @Test
    @Order(1)
    fun `verify IDE is ready`() {
        assertTrue(inspector.isIdeReady()) { "IDE should be ready and responsive" }
    }

    @Test
    @Order(2)
    fun `verify Temporal tool window button exists`() {
        val buttons = inspector.getToolWindowButtons()
        println("Available tool window buttons: $buttons")

        assertTrue(inspector.hasTemporalToolWindowButton()) {
            "Temporal tool window button should exist in the sidebar"
        }
    }

    @Test
    @Order(3)
    fun `verify Temporal tool window can be opened`() {
        val opened = inspector.openTemporalToolWindow()
        assertTrue(opened) { "Should be able to open Temporal tool window" }
        assertTrue(inspector.isTemporalToolWindowOpen()) { "Temporal tool window should be open" }
    }

    @Test
    @Order(4)
    fun `verify Temporal tool window has expected buttons`() {
        inspector.openTemporalToolWindow()
        Thread.sleep(500) // Give UI time to render

        val buttons = inspector.getTemporalToolWindowButtons()
        println("Temporal tool window buttons: $buttons")

        assertTrue(buttons.any { it.contains("Test Connection", ignoreCase = true) }) {
            "Should have Test Connection button. Found: $buttons"
        }
        assertTrue(buttons.any { it.contains("Settings", ignoreCase = true) }) {
            "Should have Settings button. Found: $buttons"
        }
    }

    @Test
    @Order(5)
    fun `verify Temporal tool window shows connection info`() {
        inspector.openTemporalToolWindow()
        Thread.sleep(500)

        val labels = inspector.getTemporalToolWindowLabels()
        println("Temporal tool window labels: $labels")

        // Should show server address or connection status
        assertTrue(labels.isNotEmpty()) {
            "Tool window should display some labels (connection info)"
        }
    }

    @Test
    @Order(6)
    fun `print full IDE state report`() {
        inspector.openTemporalToolWindow()
        Thread.sleep(500)
        inspector.printReport()

        // This test always passes - it's for debugging
        val snapshot = inspector.getIdeSnapshot()
        println("\nSnapshot data:")
        println("  Ready: ${snapshot.isReady}")
        println("  Temporal open: ${snapshot.isTemporalOpen}")
        println("  Labels: ${snapshot.temporalLabels}")
        println("  Buttons: ${snapshot.temporalButtons}")
    }
}
