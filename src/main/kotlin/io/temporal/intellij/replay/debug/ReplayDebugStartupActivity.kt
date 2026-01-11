package io.temporal.intellij.replay.debug

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Startup activity that initializes the replay debug session listener.
 * This ensures the listener is created when the project opens.
 */
class ReplayDebugStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        // Access the service to trigger initialization
        project.service<ReplayDebugSessionListener>()
    }
}
