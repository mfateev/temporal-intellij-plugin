package io.temporal.intellij.ui

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.fixtures.ContainerFixture
import com.intellij.remoterobot.fixtures.JLabelFixture
import com.intellij.remoterobot.search.locators.byXpath
import com.intellij.remoterobot.utils.waitFor
import java.time.Duration

/**
 * Utility class to inspect IDE state without human intervention.
 * Connect to a running IDE with Robot Server and query its UI state.
 *
 * Usage:
 *   1. Start IDE: ./gradlew runIdeForUiTests
 *   2. Run: ./gradlew test --tests "io.temporal.intellij.ui.IdeInspectorTest"
 */
class IdeInspector(private val robotUrl: String = "http://127.0.0.1:8082") {

    private val robot: RemoteRobot by lazy { RemoteRobot(robotUrl) }

    fun isIdeReady(): Boolean {
        return try {
            robot.callJs<Boolean>("true")
        } catch (e: Exception) {
            false
        }
    }

    fun waitForIde(timeout: Duration = Duration.ofMinutes(2)): Boolean {
        return try {
            waitFor(timeout, Duration.ofSeconds(2)) { isIdeReady() }
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get all tool window stripe buttons (right sidebar).
     */
    fun getToolWindowButtons(): List<String> {
        return try {
            val frame = robot.find<ContainerFixture>(byXpath("//div[@class='IdeFrameImpl']"), Duration.ofSeconds(5))
            frame.findAll<ComponentFixture>(byXpath("//div[@class='StripeButton']"))
                .mapNotNull { it.callJs<String>("component.getText()") }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Check if Temporal tool window button exists.
     */
    fun hasTemporalToolWindowButton(): Boolean {
        return getToolWindowButtons().any { it.contains("Temporal", ignoreCase = true) }
    }

    /**
     * Check if Temporal tool window is currently open.
     */
    fun isTemporalToolWindowOpen(): Boolean {
        return try {
            robot.find<ContainerFixture>(
                byXpath("//div[@class='InternalDecoratorImpl' and contains(@accessiblename, 'Temporal')]"),
                Duration.ofSeconds(2)
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Open the Temporal tool window if not already open.
     */
    fun openTemporalToolWindow(): Boolean {
        if (isTemporalToolWindowOpen()) return true

        return try {
            // Find and click the Temporal stripe button (using full class path)
            val stripeButton = robot.find<ComponentFixture>(
                byXpath("//div[contains(@class, 'StripeButton') and @text='Temporal']"),
                Duration.ofSeconds(5)
            )
            stripeButton.click()
            Thread.sleep(1000) // Give UI time to render
            waitFor(Duration.ofSeconds(5)) { isTemporalToolWindowOpen() }
            true
        } catch (e: Exception) {
            println("Error opening tool window: ${e.message}")
            false
        }
    }

    /**
     * Get all visible labels in the Temporal tool window.
     */
    fun getTemporalToolWindowLabels(): List<String> {
        if (!isTemporalToolWindowOpen()) return emptyList()

        return try {
            // Find the TemporalToolWindowPanel and use JS to get label texts
            val panel = robot.find<ContainerFixture>(
                byXpath("//div[@class='TemporalToolWindowPanel']"),
                Duration.ofSeconds(2)
            )
            // Use simple Java-style code that Nashorn can execute
            val labelsJson = panel.callJs<String>("""
                var labels = new java.util.ArrayList();
                var queue = new java.util.LinkedList();
                queue.add(component);
                while (!queue.isEmpty()) {
                    var comp = queue.poll();
                    var className = comp.getClass().getSimpleName();
                    if (className.equals("JBLabel")) {
                        var text = comp.getText();
                        if (text != null && text.length() > 0 && !text.startsWith("<html>")) {
                            labels.add(text);
                        }
                    }
                    try {
                        var children = comp.getComponents();
                        if (children != null) {
                            for (var i = 0; i < children.length; i++) {
                                queue.add(children[i]);
                            }
                        }
                    } catch (e) {}
                }
                labels.toString()
            """)
            // Parse "[item1, item2]" format
            labelsJson.trim('[', ']').split(", ").filter { it.isNotEmpty() }
        } catch (e: Exception) {
            println("Error finding labels: ${e.message}")
            emptyList()
        }
    }

    /**
     * Get all visible buttons in the Temporal tool window.
     */
    fun getTemporalToolWindowButtons(): List<String> {
        if (!isTemporalToolWindowOpen()) return emptyList()

        return try {
            // Find the TemporalToolWindowPanel and use JS to get button texts
            val panel = robot.find<ContainerFixture>(
                byXpath("//div[@class='TemporalToolWindowPanel']"),
                Duration.ofSeconds(2)
            )
            // Use simple Java-style code that Nashorn can execute
            val buttonsJson = panel.callJs<String>("""
                var buttons = new java.util.ArrayList();
                var queue = new java.util.LinkedList();
                queue.add(component);
                while (!queue.isEmpty()) {
                    var comp = queue.poll();
                    var className = comp.getClass().getSimpleName();
                    if (className.equals("JButton")) {
                        var text = comp.getText();
                        if (text != null && text.length() > 0) {
                            buttons.add(text);
                        }
                    }
                    try {
                        var children = comp.getComponents();
                        if (children != null) {
                            for (var i = 0; i < children.length; i++) {
                                queue.add(children[i]);
                            }
                        }
                    } catch (e) {}
                }
                buttons.toString()
            """)
            // Parse "[item1, item2]" format
            buttonsJson.trim('[', ']').split(", ").filter { it.isNotEmpty() }
        } catch (e: Exception) {
            println("Error finding buttons: ${e.message}")
            emptyList()
        }
    }

    /**
     * Click a button in the Temporal tool window by its text.
     */
    fun clickTemporalButton(buttonText: String): Boolean {
        if (!isTemporalToolWindowOpen()) return false

        return try {
            val toolWindow = robot.find<ContainerFixture>(
                byXpath("//div[@class='InternalDecoratorImpl' and contains(@accessiblename, 'Temporal')]"),
                Duration.ofSeconds(2)
            )
            val button = toolWindow.find<ComponentFixture>(
                byXpath("//div[@class='JButton' and @text='$buttonText']"),
                Duration.ofSeconds(2)
            )
            button.click()
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get a comprehensive snapshot of the current IDE state.
     */
    fun getIdeSnapshot(): IdeSnapshot {
        return IdeSnapshot(
            isReady = isIdeReady(),
            toolWindowButtons = getToolWindowButtons(),
            hasTemporalButton = hasTemporalToolWindowButton(),
            isTemporalOpen = isTemporalToolWindowOpen(),
            temporalLabels = getTemporalToolWindowLabels(),
            temporalButtons = getTemporalToolWindowButtons()
        )
    }

    /**
     * Print a human-readable report of the IDE state.
     */
    fun printReport() {
        val snapshot = getIdeSnapshot()
        println("""
            |=== IDE Inspector Report ===
            |IDE Ready: ${snapshot.isReady}
            |
            |Tool Window Buttons: ${snapshot.toolWindowButtons.joinToString(", ")}
            |Has Temporal Button: ${snapshot.hasTemporalButton}
            |
            |Temporal Tool Window Open: ${snapshot.isTemporalOpen}
            |Temporal Labels: ${snapshot.temporalLabels.joinToString(", ")}
            |Temporal Buttons: ${snapshot.temporalButtons.joinToString(", ")}
            |=============================
        """.trimMargin())
    }

    data class IdeSnapshot(
        val isReady: Boolean,
        val toolWindowButtons: List<String>,
        val hasTemporalButton: Boolean,
        val isTemporalOpen: Boolean,
        val temporalLabels: List<String>,
        val temporalButtons: List<String>
    )
}

// Simple main function for quick testing
fun main() {
    val inspector = IdeInspector()

    println("Waiting for IDE...")
    if (!inspector.waitForIde(Duration.ofSeconds(30))) {
        println("ERROR: IDE not available. Start it with: ./gradlew runIdeForUiTests")
        return
    }

    inspector.printReport()

    if (inspector.hasTemporalToolWindowButton() && !inspector.isTemporalToolWindowOpen()) {
        println("\nOpening Temporal tool window...")
        inspector.openTemporalToolWindow()
        Thread.sleep(1000)
        inspector.printReport()
    }
}
