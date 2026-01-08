package io.temporal.intellij.toolwindow

import com.intellij.openapi.Disposable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import io.temporal.intellij.settings.TemporalSettings
import io.temporal.intellij.workflow.WorkflowInspectorPanel
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JPanel

class TemporalToolWindowPanel(private val project: Project) : JBPanel<TemporalToolWindowPanel>(BorderLayout()), Disposable {
    private val settings = TemporalSettings.getInstance(project)
    private val connectionInfoLabel = JBLabel()
    private val workflowInspectorPanel = WorkflowInspectorPanel(project)

    init {
        // Main content - Workflow Inspector
        add(workflowInspectorPanel, BorderLayout.CENTER)

        // Connection info bar at bottom
        val statusBar = createConnectionStatusBar()
        add(statusBar, BorderLayout.SOUTH)
    }

    private fun createConnectionStatusBar(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(5, 10)

        updateConnectionInfo()
        panel.add(connectionInfoLabel, BorderLayout.CENTER)

        val settingsButton = JButton("Settings")
        settingsButton.addActionListener {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, "Temporal")
            updateConnectionInfo()
        }
        panel.add(settingsButton, BorderLayout.EAST)

        return panel
    }

    private fun updateConnectionInfo() {
        val state = settings.state
        val tlsIcon = if (state.tlsEnabled) "🔒" else ""
        connectionInfoLabel.text = "<html><b>${state.address}</b> · ${state.namespace} $tlsIcon</html>"
        connectionInfoLabel.toolTipText = buildString {
            append("Server: ${state.address}\n")
            append("Namespace: ${state.namespace}\n")
            append("TLS: ${if (state.tlsEnabled) "Enabled" else "Disabled"}\n")
            if (state.apiKey.isNotEmpty()) append("API Key: Configured")
        }
    }

    override fun dispose() {
        workflowInspectorPanel.dispose()
    }
}
