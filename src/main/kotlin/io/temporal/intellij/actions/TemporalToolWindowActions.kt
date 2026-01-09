package io.temporal.intellij.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware

/**
 * Action to open Temporal settings from the tool window title bar.
 */
class OpenTemporalSettingsAction : AnAction(
    "Settings...",
    "Open Temporal settings",
    AllIcons.General.Settings
), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ShowSettingsUtil.getInstance().showSettingsDialog(project, "Temporal")
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
