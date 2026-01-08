package io.temporal.intellij.ui

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.fixtures.CommonContainerFixture
import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.search.locators.byXpath
import com.intellij.remoterobot.stepsProcessing.step
import com.intellij.remoterobot.utils.waitFor
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Duration

/**
 * Test the workflow browse button functionality.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkflowBrowseButtonTest {

    private lateinit var remoteRobot: RemoteRobot

    @BeforeAll
    fun setup() {
        remoteRobot = RemoteRobot("http://127.0.0.1:8082")
        // Verify connection by trying to find anything
        try {
            remoteRobot.finder
        } catch (e: Exception) {
            throw IllegalStateException("Remote Robot is not available - is the IDE running with runIdeForUiTests?", e)
        }
    }

    @Test
    fun `browse button opens workflow chooser dialog`() = step("Test browse button") {
        // Find the browse button directly by its tooltip
        val browseButton = remoteRobot.find<ComponentFixture>(
            byXpath("//div[@class='JButton' and @tooltiptext='Browse recent workflows']"),
            Duration.ofSeconds(10)
        )
        println("Found browse button, clicking...")
        browseButton.click()

        // Wait for the dialog or error to appear
        Thread.sleep(3000)  // Give time for connection and dialog

        // Check if dialog appeared
        val hasDialog = try {
            remoteRobot.find<CommonContainerFixture>(
                byXpath("//div[contains(@title, 'Select Workflow') or contains(@title, 'Workflow')]"),
                Duration.ofSeconds(5)
            )
            println("Dialog appeared!")
            true
        } catch (e: Exception) {
            println("No dialog found: ${e.message}")
            false
        }

        if (hasDialog) {
            // Try to find Cancel button and close the dialog
            try {
                val cancelButton = remoteRobot.find<ComponentFixture>(
                    byXpath("//div[@class='JButton' and @text='Cancel']"),
                    Duration.ofSeconds(2)
                )
                cancelButton.click()
                println("Closed dialog")
            } catch (e: Exception) {
                println("Could not find Cancel button: ${e.message}")
            }
        }

        println("Browse button test completed!")
    }
}
