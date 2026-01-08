package io.temporal.intellij.toolwindow

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import io.temporal.intellij.settings.TemporalConnectionTester
import io.temporal.intellij.settings.TemporalSettings
import io.temporal.intellij.workflow.WorkflowInspectorPanel
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JPanel

class TemporalToolWindowPanel(private val project: Project) : JBPanel<TemporalToolWindowPanel>(BorderLayout()), Disposable {
    private val settings = TemporalSettings.getInstance(project)
    private val statusLabel = JBLabel()
    private val connectionStatusLabel = JBLabel()
    private val workflowInspectorPanel = WorkflowInspectorPanel(project)

    init {
        val headerPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        headerPanel.add(JBLabel("Temporal"))

        val testButton = JButton("Test Connection")
        testButton.addActionListener { testConnection() }
        headerPanel.add(testButton)

        val settingsButton = JButton("Settings")
        settingsButton.addActionListener {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, "Temporal")
            updateStatusLabel() // Refresh after settings close
        }
        headerPanel.add(settingsButton)
        add(headerPanel, BorderLayout.NORTH)

        // Create tabbed pane
        val tabbedPane = JBTabbedPane()

        // Workflow Inspector tab (default)
        tabbedPane.addTab("Workflow Inspector", workflowInspectorPanel)

        // Connection tab
        val connectionPanel = createConnectionPanel()
        tabbedPane.addTab("Connection", connectionPanel)

        add(tabbedPane, BorderLayout.CENTER)
    }

    private fun createConnectionPanel(): JPanel {
        val contentPanel = JBPanel<JBPanel<*>>(BorderLayout())
        contentPanel.border = JBUI.Borders.empty(10)

        updateStatusLabel()
        contentPanel.add(statusLabel, BorderLayout.CENTER)

        connectionStatusLabel.border = JBUI.Borders.emptyTop(10)
        contentPanel.add(connectionStatusLabel, BorderLayout.SOUTH)

        return contentPanel
    }

    private fun updateStatusLabel() {
        val state = settings.state
        val tlsStatus = if (state.tlsEnabled) "Enabled" else "Disabled"
        statusLabel.text = """
            <html><body style='padding: 5px;'>
            <h3>Connection Settings</h3>
            <table>
                <tr><td><b>Address:</b></td><td>${state.address}</td></tr>
                <tr><td><b>Namespace:</b></td><td>${state.namespace}</td></tr>
                <tr><td><b>API Key:</b></td><td>${if (state.apiKey.isNotEmpty()) "••••••••" else "(not set)"}</td></tr>
                <tr><td><b>TLS:</b></td><td>$tlsStatus</td></tr>
            </table>
            </body></html>
        """.trimIndent()
    }

    private fun testConnection() {
        connectionStatusLabel.text = "<html><i>Testing connection...</i></html>"

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Testing Temporal Connection", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Connecting to ${settings.state.address}..."
                indicator.isIndeterminate = true

                val result = TemporalConnectionTester.testConnection(settings.state)

                ApplicationManager.getApplication().invokeLater {
                    if (result.success) {
                        connectionStatusLabel.text = "<html><font color='green'>✓ Connected</font>" +
                            (result.serverInfo?.let { "<br/><small>$it</small>" } ?: "") +
                            "</html>"
                    } else {
                        connectionStatusLabel.text = "<html><font color='red'>✗ ${result.message}</font></html>"
                    }
                }
            }
        })
    }

    override fun dispose() {
        workflowInspectorPanel.dispose()
    }
}
