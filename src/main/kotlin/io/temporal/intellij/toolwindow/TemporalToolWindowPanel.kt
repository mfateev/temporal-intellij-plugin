package io.temporal.intellij.toolwindow

import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import io.temporal.intellij.settings.TemporalSettings
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JPanel

class TemporalToolWindowPanel(private val project: Project) : JBPanel<TemporalToolWindowPanel>(BorderLayout()) {
    private val settings = TemporalSettings.getInstance(project)
    private val statusLabel = JBLabel()

    init {
        val headerPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        headerPanel.add(JBLabel("Temporal"))
        val settingsButton = JButton("Settings")
        settingsButton.addActionListener {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, "Temporal")
        }
        headerPanel.add(settingsButton)
        add(headerPanel, BorderLayout.NORTH)

        val contentPanel = JBPanel<JBPanel<*>>(BorderLayout())
        contentPanel.border = JBUI.Borders.empty(10)

        updateStatusLabel()
        contentPanel.add(statusLabel, BorderLayout.CENTER)

        add(contentPanel, BorderLayout.CENTER)
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
            <br/>
            <p><i>Configure settings to connect to your Temporal server.</i></p>
            </body></html>
        """.trimIndent()
    }
}
