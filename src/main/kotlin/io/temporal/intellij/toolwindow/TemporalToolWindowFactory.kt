package io.temporal.intellij.toolwindow

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class TemporalToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = TemporalToolWindowPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)

        // Add settings icon to tool window title bar
        val actionManager = ActionManager.getInstance()
        val settingsAction = actionManager.getAction("Temporal.OpenSettings")
        if (settingsAction != null) {
            toolWindow.setTitleActions(listOf(settingsAction))
        }
    }
}
