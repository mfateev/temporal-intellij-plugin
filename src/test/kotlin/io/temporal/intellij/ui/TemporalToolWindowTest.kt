package io.temporal.intellij.ui

import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.fixtures.ContainerFixture
import com.intellij.remoterobot.search.locators.byXpath
import com.intellij.remoterobot.stepsProcessing.step
import com.intellij.remoterobot.utils.waitFor
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertTrue
import java.time.Duration

/**
 * UI tests for the Temporal Tool Window using detailed XPath locators.
 *
 * Run with: ./gradlew uiTest
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class TemporalToolWindowTest : BaseUiTest() {

    @Test
    @Order(1)
    fun `test Temporal tool window can be opened`() = step("Open Temporal Tool Window") {
        val ideFrame = remoteRobot.ideFrame()

        // Check if tool window is already open
        val alreadyOpen = ideFrame.findTemporalToolWindow() != null

        if (!alreadyOpen) {
            // Find and click the Temporal stripe button to open
            val stripeButton = ideFrame.find<ComponentFixture>(
                byXpath("//div[@class='StripeButton' and @text='Temporal']"),
                Duration.ofSeconds(10)
            )
            stripeButton.click()

            // Verify tool window opens
            waitFor(Duration.ofSeconds(5)) {
                ideFrame.findTemporalToolWindow() != null
            }
        }

        val toolWindow = ideFrame.findTemporalToolWindow()
        assertTrue(toolWindow != null) { "Temporal tool window should be visible" }
    }

    @Test
    @Order(2)
    fun `test connection settings are displayed`() = step("Verify Connection Settings Display") {
        val ideFrame = remoteRobot.ideFrame()

        // Ensure tool window is open
        val toolWindow = ideFrame.findTemporalToolWindow() ?: ideFrame.openTemporalToolWindow()

        // Verify Test Connection button exists
        val testConnectionButton = toolWindow.testConnectionButton
        assertTrue(testConnectionButton.isShowing) { "Test Connection button should be visible" }

        // Verify Settings button exists
        val settingsButton = toolWindow.settingsButton
        assertTrue(settingsButton.isShowing) { "Settings button should be visible" }
    }

    @Test
    @Order(3)
    fun `test clicking Settings opens settings dialog`() = step("Open Settings via Button") {
        val ideFrame = remoteRobot.ideFrame()
        val toolWindow = ideFrame.findTemporalToolWindow() ?: ideFrame.openTemporalToolWindow()

        // Click Settings button
        toolWindow.settingsButton.click()

        // Wait for IDE Settings dialog (uses different class names than custom dialogs)
        // The IDE Settings uses SettingsDialog or similar wrapper
        waitFor(Duration.ofSeconds(10)) {
            try {
                // Look for any dialog with Settings or Temporal in title/name
                remoteRobot.find<ContainerFixture>(
                    byXpath("//div[contains(@class, 'Dialog') or contains(@class, 'SettingsDialog') or @class='IdeFrameImpl.FrameHelper']//div[contains(@accessiblename, 'Settings') or contains(@accessiblename, 'Temporal')]"),
                    Duration.ofSeconds(1)
                )
                true
            } catch (e: Exception) {
                // Fallback: just check if any new window appeared
                try {
                    remoteRobot.find<ContainerFixture>(
                        byXpath("//div[@class='JDialog']"),
                        Duration.ofSeconds(1)
                    )
                    true
                } catch (e2: Exception) {
                    false
                }
            }
        }

        // Give settings dialog time to fully load
        Thread.sleep(1000)

        // Close dialog with Escape key (most reliable)
        remoteRobot.runJs("robot.pressAndReleaseKey(java.awt.event.KeyEvent.VK_ESCAPE)")
        Thread.sleep(500)
    }
}
