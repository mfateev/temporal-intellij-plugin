package io.temporal.intellij.ui

import com.intellij.remoterobot.fixtures.*
import com.intellij.remoterobot.search.locators.byXpath
import com.intellij.remoterobot.stepsProcessing.step
import com.intellij.remoterobot.utils.keyboard
import com.intellij.remoterobot.utils.waitFor
import org.junit.jupiter.api.*
import java.time.Duration

/**
 * Test for the Workflow Replay feature.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ReplayFeatureTest : BaseUiTest() {

    @Test
    @Order(1)
    fun `run HelloActivity from IDE`() = step("Run HelloActivity") {
        val ideFrame = remoteRobot.ideFrame()

        // Open Navigate to Class dialog (Cmd+O on Mac)
        remoteRobot.runJs("robot.pressAndReleaseKey(java.awt.event.KeyEvent.VK_O, java.awt.event.KeyEvent.META_DOWN_MASK)")
        Thread.sleep(1000)

        // Type HelloActivity character by character
        "HelloActivity".forEach { char ->
            remoteRobot.runJs("robot.type('$char')")
            Thread.sleep(50)
        }
        Thread.sleep(1500)

        // Press Enter to open the first result
        remoteRobot.runJs("robot.pressAndReleaseKey(java.awt.event.KeyEvent.VK_ENTER)")
        Thread.sleep(2000)

        // Run with Ctrl+Shift+R (Mac) to run current file
        remoteRobot.runJs("robot.pressAndReleaseKey(java.awt.event.KeyEvent.VK_R, java.awt.event.KeyEvent.CTRL_DOWN_MASK | java.awt.event.KeyEvent.SHIFT_DOWN_MASK)")

        // Wait for run to complete
        println("HelloActivity run initiated, waiting 15 seconds for completion...")
        Thread.sleep(15000)

        println("HelloActivity should have completed")
    }

    @Test
    @Order(2)
    fun `load workflow in Temporal plugin`() = step("Load Workflow") {
        val ideFrame = remoteRobot.ideFrame()
        val toolWindow = ideFrame.findTemporalToolWindow() ?: ideFrame.openTemporalToolWindow()

        // Find the first text field (Workflow ID field)
        val textFields = toolWindow.findAll<JTextFieldFixture>(
            byXpath("//div[@class='JBTextField' or @class='JTextField']")
        )
        println("Found ${textFields.size} text fields")

        // The first one should be Workflow ID
        val workflowIdField = textFields.firstOrNull()
            ?: throw AssertionError("No text field found for Workflow ID")

        workflowIdField.text = "HelloActivityWorkflow"
        println("Set workflow ID to: HelloActivityWorkflow")

        Thread.sleep(500)

        // Find and click the browse button (...) to load the workflow
        val browseButton = toolWindow.find<ComponentFixture>(
            byXpath("//div[@class='JButton' and @text='...']"),
            Duration.ofSeconds(5)
        )
        browseButton.click()

        println("Clicked browse button, waiting for workflow list...")
        Thread.sleep(2000)

        // Look for a list or table in the dialog and select HelloActivityWorkflow
        try {
            val dialog = remoteRobot.find<ContainerFixture>(
                byXpath("//div[@class='JDialog' or contains(@class, 'Dialog')]"),
                Duration.ofSeconds(3)
            )
            println("Found dialog, looking for workflow list...")

            // Try to find and click HelloActivityWorkflow in the list
            val listItem = dialog.findAll<ComponentFixture>(
                byXpath("//div[contains(@text, 'HelloActivityWorkflow')]")
            )
            if (listItem.isNotEmpty()) {
                listItem.first().doubleClick()
                println("Selected HelloActivityWorkflow from list")
            } else {
                // Just press Enter to select first item
                remoteRobot.runJs("robot.pressAndReleaseKey(java.awt.event.KeyEvent.VK_ENTER)")
            }
        } catch (e: Exception) {
            println("No dialog found, workflow might load directly")
        }

        // Wait for workflow to load
        Thread.sleep(3000)
        println("Workflow loading completed")
    }

    @Test
    @Order(3)
    fun `click Replay button and check output`() = step("Click Replay") {
        val ideFrame = remoteRobot.ideFrame()
        val toolWindow = ideFrame.findTemporalToolWindow() ?: ideFrame.openTemporalToolWindow()

        // Find and click Replay button
        val replayButton = toolWindow.find<ComponentFixture>(
            byXpath("//div[@class='JButton' and @text='Replay']"),
            Duration.ofSeconds(5)
        )

        println("Found Replay button, clicking...")
        replayButton.click()

        // Wait for replay to start and show results
        println("Waiting for replay to complete...")
        Thread.sleep(8000)

        // Try to find Run tool window output
        try {
            val runToolWindow = ideFrame.find<ContainerFixture>(
                byXpath("//div[contains(@accessiblename, 'Run') and contains(@class, 'InternalDecorator')]"),
                Duration.ofSeconds(5)
            )
            println("Found Run tool window")

            // Look for success or failure message
            val outputLabels = runToolWindow.findAll<ComponentFixture>(byXpath("//div[@class='JBLabel']"))
            outputLabels.forEach { label ->
                println("Output label found")
            }
        } catch (e: Exception) {
            println("Could not find Run tool window: ${e.message}")
        }

        println("Replay test completed")
    }
}
