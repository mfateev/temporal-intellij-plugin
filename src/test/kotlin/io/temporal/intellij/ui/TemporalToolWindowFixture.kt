package io.temporal.intellij.ui

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.data.RemoteComponent
import com.intellij.remoterobot.fixtures.*
import com.intellij.remoterobot.search.locators.byXpath
import com.intellij.remoterobot.stepsProcessing.step
import java.time.Duration

/**
 * Fixture for interacting with the Temporal Tool Window.
 */
@DefaultXpath("TemporalToolWindow", "//div[@class='InternalDecoratorImpl' and contains(@accessiblename, 'Temporal')]")
@FixtureName("Temporal Tool Window")
class TemporalToolWindowFixture(
    remoteRobot: RemoteRobot,
    remoteComponent: RemoteComponent
) : CommonContainerFixture(remoteRobot, remoteComponent) {

    val settingsButton
        get() = button(byXpath("//div[@class='JButton' and @text='Settings']"))

    val connectionInfoLabel
        get() = findAll<JLabelFixture>(byXpath("//div[@class='JBLabel']"))
            .firstOrNull { it.hasText("localhost") || it.hasText("7233") || it.hasText("default") }

    // Workflow Inspector elements (Phase 1)
    val workflowIdField
        get() = textField(byXpath("//div[@class='JBTextField' or @class='JTextField']"), Duration.ofSeconds(2))

    val inspectButton
        get() = button(byXpath("//div[@class='JButton' and @text='Inspect']"), Duration.ofSeconds(2))

    val refreshButton
        get() = actionButton(byXpath("//div[@myicon='refresh.svg' or contains(@tooltiptext, 'Refresh')]"), Duration.ofSeconds(2))

    fun getExecutionInfoPanel(): ContainerFixture? = step("Find Execution Info Panel") {
        try {
            find<ContainerFixture>(byXpath("//div[contains(@accessiblename, 'Execution') or contains(@text, 'EXECUTION')]"), Duration.ofSeconds(2))
        } catch (e: Exception) {
            null
        }
    }

    fun getPendingActivitiesPanel(): ContainerFixture? = step("Find Pending Activities Panel") {
        try {
            find<ContainerFixture>(byXpath("//div[contains(@accessiblename, 'Pending') or contains(@text, 'PENDING')]"), Duration.ofSeconds(2))
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Fixture for the Idea frame (main window).
 */
@DefaultXpath("IdeFrameImpl", "//div[@class='IdeFrameImpl']")
@FixtureName("IDE Frame")
class IdeaFrameFixture(
    remoteRobot: RemoteRobot,
    remoteComponent: RemoteComponent
) : CommonContainerFixture(remoteRobot, remoteComponent) {

    fun openTemporalToolWindow(): TemporalToolWindowFixture = step("Open Temporal Tool Window") {
        // Click on the Temporal stripe button in the right tool window bar
        val stripeButton = find<ComponentFixture>(
            byXpath("//div[@class='StripeButton' and @text='Temporal']"),
            Duration.ofSeconds(10)
        )
        stripeButton.click()

        // Wait for and return the tool window
        find(TemporalToolWindowFixture::class.java, Duration.ofSeconds(5))
    }

    fun findTemporalToolWindow(): TemporalToolWindowFixture? = step("Find Temporal Tool Window (if open)") {
        try {
            find(TemporalToolWindowFixture::class.java, Duration.ofSeconds(2))
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Extension function to find the IDE frame.
 */
fun RemoteRobot.ideFrame(timeout: Duration = Duration.ofSeconds(20)): IdeaFrameFixture {
    return find(IdeaFrameFixture::class.java, timeout)
}
