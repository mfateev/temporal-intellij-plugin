package io.temporal.intellij.ui

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.fixtures.ContainerFixture
import com.intellij.remoterobot.search.locators.byXpath
import com.intellij.remoterobot.utils.waitFor
import java.time.Duration

/**
 * Helper to navigate from Welcome Screen to a project.
 */
class WelcomeScreenHelper(private val robot: RemoteRobot) {

    fun isWelcomeScreenShowing(): Boolean {
        return try {
            robot.find<ContainerFixture>(
                byXpath("//div[@class='FlatWelcomeFrame']"),
                Duration.ofSeconds(2)
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    fun isProjectOpen(): Boolean {
        return try {
            robot.find<ContainerFixture>(
                byXpath("//div[@class='IdeFrameImpl']"),
                Duration.ofSeconds(2)
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Open a project from the Welcome screen.
     * Click Open -> Navigate to path -> Select folder
     */
    fun openProject(projectPath: String): Boolean {
        if (!isWelcomeScreenShowing()) {
            println("Not on Welcome screen")
            return isProjectOpen()
        }

        return try {
            val welcomeFrame = robot.find<ContainerFixture>(
                byXpath("//div[@class='FlatWelcomeFrame']"),
                Duration.ofSeconds(5)
            )

            // Click "Open" button
            val openButton = welcomeFrame.find<ComponentFixture>(
                byXpath("//div[@class='JButton' and @text='Open']"),
                Duration.ofSeconds(5)
            )
            openButton.click()

            // Wait for file chooser dialog
            Thread.sleep(1000)

            // Type the path in the dialog
            // This uses the native file chooser, which is tricky to automate
            // Instead, let's try using keyboard shortcuts

            // On Mac, Cmd+Shift+G opens "Go to folder" in file dialogs
            robot.runJs("""
                robot.pressAndReleaseKey(java.awt.event.KeyEvent.VK_G, java.awt.event.InputEvent.META_DOWN_MASK | java.awt.event.InputEvent.SHIFT_DOWN_MASK)
            """)
            Thread.sleep(500)

            // Type the path
            robot.runJs("""
                robot.type("$projectPath")
            """)
            Thread.sleep(300)

            // Press Enter to go to folder
            robot.runJs("""
                robot.pressAndReleaseKey(java.awt.event.KeyEvent.VK_ENTER, 0)
            """)
            Thread.sleep(500)

            // Press Enter again to open
            robot.runJs("""
                robot.pressAndReleaseKey(java.awt.event.KeyEvent.VK_ENTER, 0)
            """)

            // Wait for project to open
            waitFor(Duration.ofSeconds(60)) {
                isProjectOpen()
            }

            true
        } catch (e: Exception) {
            println("Error opening project: ${e.message}")
            false
        }
    }

    /**
     * Create a new empty project.
     */
    fun createNewProject(): Boolean {
        if (!isWelcomeScreenShowing()) {
            return isProjectOpen()
        }

        return try {
            val welcomeFrame = robot.find<ContainerFixture>(
                byXpath("//div[@class='FlatWelcomeFrame']"),
                Duration.ofSeconds(5)
            )

            // Click "New Project" button
            val newProjectButton = welcomeFrame.find<ComponentFixture>(
                byXpath("//div[@class='JButton' and @text='New Project']"),
                Duration.ofSeconds(5)
            )
            newProjectButton.click()

            // In the New Project dialog, just click Create with defaults
            Thread.sleep(2000)

            // Find the dialog
            val dialog = robot.find<ContainerFixture>(
                byXpath("//div[@class='MyDialog' or contains(@title, 'New Project')]"),
                Duration.ofSeconds(10)
            )

            // Click Create button
            val createButton = dialog.find<ComponentFixture>(
                byXpath("//div[@class='JButton' and @text='Create']"),
                Duration.ofSeconds(5)
            )
            createButton.click()

            // Wait for project to open
            waitFor(Duration.ofSeconds(60)) {
                isProjectOpen()
            }

            true
        } catch (e: Exception) {
            println("Error creating project: ${e.message}")
            false
        }
    }
}
