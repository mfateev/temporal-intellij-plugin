package io.temporal.intellij

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages

class HelloWorldAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        Messages.showMessageDialog(
            e.project,
            "Hello from Temporal Plugin!\n\nThis is a placeholder for future Temporal.io integrations.",
            "Temporal",
            Messages.getInformationIcon()
        )
    }
}
