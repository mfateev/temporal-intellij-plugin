package io.temporal.intellij.replay.runconfig

import com.intellij.execution.ui.CommonJavaParametersPanel
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.ButtonGroup
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Settings editor UI for the workflow replay run configuration.
 */
class WorkflowReplaySettingsEditor(
    private val project: Project
) : SettingsEditor<WorkflowReplayRunConfiguration>() {

    private val fileSourceRadio = JBRadioButton("From JSON file", true)
    private val serverSourceRadio = JBRadioButton("From server (current workflow)")

    private val historyFileField = TextFieldWithBrowseButton()
    private val workflowIdField = JBTextField()
    private val runIdField = JBTextField()
    private val workflowClassField = JBTextField()

    private val mainPanel: JPanel

    init {
        // Group radio buttons
        val sourceGroup = ButtonGroup()
        sourceGroup.add(fileSourceRadio)
        sourceGroup.add(serverSourceRadio)

        // Configure file chooser
        historyFileField.addBrowseFolderListener(
            "Select History File",
            "Select the JSON file containing workflow history",
            project,
            FileChooserDescriptorFactory.createSingleFileDescriptor("json")
        )

        // Enable/disable fields based on source selection
        fileSourceRadio.addActionListener { updateFieldStates() }
        serverSourceRadio.addActionListener { updateFieldStates() }

        mainPanel = createPanel()
        updateFieldStates()
    }

    private fun createPanel(): JPanel {
        val panel = JPanel(GridBagLayout())
        panel.border = JBUI.Borders.empty(10)

        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.WEST
            insets = JBUI.insets(4)
        }

        var row = 0

        // History Source section
        gbc.gridx = 0
        gbc.gridy = row
        gbc.gridwidth = 2
        panel.add(JBLabel("<html><b>History Source</b></html>"), gbc)
        row++

        gbc.gridy = row
        gbc.gridwidth = 1
        panel.add(fileSourceRadio, gbc)
        row++

        gbc.gridy = row
        panel.add(serverSourceRadio, gbc)
        row++

        // File path field
        gbc.gridy = row
        gbc.gridx = 0
        gbc.gridwidth = 1
        gbc.weightx = 0.0
        panel.add(JBLabel("History file:"), gbc)
        gbc.gridx = 1
        gbc.weightx = 1.0
        panel.add(historyFileField, gbc)
        row++

        // Server fields (disabled by default)
        gbc.gridy = row
        gbc.gridx = 0
        gbc.weightx = 0.0
        panel.add(JBLabel("Workflow ID:"), gbc)
        gbc.gridx = 1
        gbc.weightx = 1.0
        panel.add(workflowIdField, gbc)
        row++

        gbc.gridy = row
        gbc.gridx = 0
        gbc.weightx = 0.0
        panel.add(JBLabel("Run ID:"), gbc)
        gbc.gridx = 1
        gbc.weightx = 1.0
        panel.add(runIdField, gbc)
        row++

        // Workflow Class section
        gbc.gridy = row
        gbc.gridx = 0
        gbc.gridwidth = 2
        gbc.weightx = 0.0
        panel.add(JBLabel("<html><br/><b>Workflow Implementation</b></html>"), gbc)
        row++

        gbc.gridy = row
        gbc.gridx = 0
        gbc.gridwidth = 1
        gbc.weightx = 0.0
        panel.add(JBLabel("Class name:"), gbc)
        gbc.gridx = 1
        gbc.weightx = 1.0
        panel.add(workflowClassField, gbc)
        row++

        // Filler
        gbc.gridy = row
        gbc.gridx = 0
        gbc.gridwidth = 2
        gbc.weighty = 1.0
        gbc.fill = GridBagConstraints.BOTH
        panel.add(JPanel(), gbc)

        return panel
    }

    private fun updateFieldStates() {
        val isFileSource = fileSourceRadio.isSelected
        historyFileField.isEnabled = isFileSource
        workflowIdField.isEnabled = !isFileSource
        runIdField.isEnabled = !isFileSource
    }

    override fun resetEditorFrom(config: WorkflowReplayRunConfiguration) {
        when (config.historySource) {
            "file" -> fileSourceRadio.isSelected = true
            "server" -> serverSourceRadio.isSelected = true
        }
        historyFileField.text = config.historyFilePath
        workflowIdField.text = config.workflowId
        runIdField.text = config.runId
        workflowClassField.text = config.workflowClassName
        updateFieldStates()
    }

    override fun applyEditorTo(config: WorkflowReplayRunConfiguration) {
        config.historySource = if (fileSourceRadio.isSelected) "file" else "server"
        config.historyFilePath = historyFileField.text
        config.workflowId = workflowIdField.text
        config.runId = runIdField.text
        config.workflowClassName = workflowClassField.text
    }

    override fun createEditor(): JComponent = mainPanel
}
