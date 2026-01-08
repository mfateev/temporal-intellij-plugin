package io.temporal.intellij.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout

class TemporalToolWindowPanel(private val project: Project) : JBPanel<TemporalToolWindowPanel>(BorderLayout()) {
    init {
        val label = JBLabel("Temporal Tool Window")
        label.border = JBUI.Borders.empty(10)
        add(label, BorderLayout.NORTH)

        val contentLabel = JBLabel("<html><body style='padding: 10px;'>" +
            "<h3>Welcome to Temporal</h3>" +
            "<p>This tool window will provide:</p>" +
            "<ul>" +
            "<li>Workflow visualization</li>" +
            "<li>Activity inspection</li>" +
            "<li>Server connection status</li>" +
            "</ul>" +
            "</body></html>")
        add(contentLabel, BorderLayout.CENTER)
    }
}
