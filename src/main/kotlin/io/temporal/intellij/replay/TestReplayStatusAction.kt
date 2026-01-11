package io.temporal.intellij.replay

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Test action to demonstrate replay status updates.
 * Simulates a replay starting and completing after 2 seconds.
 */
class TestReplayStatusAction : AnAction("Test Replay Status") {

    private val executor = Executors.newSingleThreadScheduledExecutor()

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val publisher = project.messageBus.syncPublisher(ReplayProgressListener.TOPIC)

        // Publish "started" event
        publisher.onReplayStarted("test-workflow-123", "MyWorkflow")

        // Publish "finished" event after 2 seconds
        executor.schedule({
            ApplicationManager.getApplication().invokeLater {
                publisher.onReplayFinished("test-workflow-123", success = true)
            }
        }, 2, TimeUnit.SECONDS)
    }
}
