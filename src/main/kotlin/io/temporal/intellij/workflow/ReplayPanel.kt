package io.temporal.intellij.workflow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import io.temporal.api.history.v1.History
import io.temporal.api.history.v1.HistoryEvent
import io.temporal.intellij.replay.ReplayProgressListener
import io.temporal.intellij.replay.WorkflowReplayService
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.io.File
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JSeparator

/**
 * Panel for workflow replay functionality.
 * Allows users to replay workflow history against local implementation to detect non-determinism issues.
 */
class ReplayPanel(private val project: Project) : JBPanel<ReplayPanel>(BorderLayout()) {

    // Replay buttons
    private val replayButton = JButton("Replay")
    private val debugReplayButton = JButton("Debug Replay")
    private val importHistoryButton = JButton("Import JSON...")
    private val exportHistoryButton = JButton("Export JSON...")

    // Status panel
    private val replayStatusPanel = ReplayStatusPanel(project)

    // Instructions panel
    private val instructionsLabel = JBLabel()

    // Workflow context (set when a workflow is loaded)
    private var currentWorkflowId: String? = null
    private var currentRunId: String? = null
    private var cachedRawEvents: List<HistoryEvent> = emptyList()
    private var workflowType: String? = null

    init {
        border = JBUI.Borders.empty(10)

        val contentPanel = JBPanel<JBPanel<*>>()
        contentPanel.layout = BoxLayout(contentPanel, BoxLayout.Y_AXIS)

        // Title section
        val titleLabel = JBLabel("<html><b style='font-size: 1.2em;'>Workflow Replay</b></html>")
        titleLabel.alignmentX = LEFT_ALIGNMENT
        contentPanel.add(titleLabel)
        contentPanel.add(Box.createVerticalStrut(10))

        // Description
        val descLabel = JBLabel("<html>Replay workflow history against your local implementation to detect non-determinism issues.</html>")
        descLabel.alignmentX = LEFT_ALIGNMENT
        contentPanel.add(descLabel)
        contentPanel.add(Box.createVerticalStrut(15))

        // Buttons section
        val buttonsPanel = createButtonsPanel()
        buttonsPanel.alignmentX = LEFT_ALIGNMENT
        contentPanel.add(buttonsPanel)
        contentPanel.add(Box.createVerticalStrut(15))

        // Separator
        val separator = JSeparator()
        separator.alignmentX = LEFT_ALIGNMENT
        separator.maximumSize = java.awt.Dimension(Int.MAX_VALUE, 1)
        contentPanel.add(separator)
        contentPanel.add(Box.createVerticalStrut(15))

        // Status section
        replayStatusPanel.alignmentX = LEFT_ALIGNMENT
        contentPanel.add(replayStatusPanel)
        contentPanel.add(Box.createVerticalStrut(15))

        // Instructions section
        val instructionsPanel = createInstructionsPanel()
        instructionsPanel.alignmentX = LEFT_ALIGNMENT
        contentPanel.add(instructionsPanel)

        contentPanel.add(Box.createVerticalGlue())

        val scrollPane = JBScrollPane(contentPanel)
        scrollPane.border = JBUI.Borders.empty()
        add(scrollPane, BorderLayout.CENTER)

        // Initially disable workflow-specific buttons
        updateButtonStates()
    }

    private fun createButtonsPanel(): JPanel {
        val panel = JBPanel<JBPanel<*>>()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)

        // Row 1: Replay loaded workflow
        val row1Label = JBLabel("<html><b>Replay Loaded Workflow:</b></html>")
        row1Label.alignmentX = LEFT_ALIGNMENT
        panel.add(row1Label)
        panel.add(Box.createVerticalStrut(5))

        val row1Buttons = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 5, 0))
        row1Buttons.alignmentX = LEFT_ALIGNMENT

        replayButton.toolTipText = "Replay loaded workflow against local implementation"
        replayButton.addActionListener { startReplay(debug = false) }
        row1Buttons.add(replayButton)

        debugReplayButton.toolTipText = "Replay with debugger attached - set breakpoints in workflow code"
        debugReplayButton.addActionListener { startReplay(debug = true) }
        row1Buttons.add(debugReplayButton)

        panel.add(row1Buttons)
        panel.add(Box.createVerticalStrut(15))

        // Row 2: Import/Export
        val row2Label = JBLabel("<html><b>History File Operations:</b></html>")
        row2Label.alignmentX = LEFT_ALIGNMENT
        panel.add(row2Label)
        panel.add(Box.createVerticalStrut(5))

        val row2Buttons = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 5, 0))
        row2Buttons.alignmentX = LEFT_ALIGNMENT

        importHistoryButton.toolTipText = "Import workflow history from JSON file and replay"
        importHistoryButton.addActionListener { importHistoryFile() }
        row2Buttons.add(importHistoryButton)

        exportHistoryButton.toolTipText = "Export loaded workflow history to JSON file"
        exportHistoryButton.addActionListener { exportHistoryFile() }
        row2Buttons.add(exportHistoryButton)

        panel.add(row2Buttons)

        return panel
    }

    private fun createInstructionsPanel(): JPanel {
        val panel = JBPanel<JBPanel<*>>(BorderLayout())
        panel.border = BorderFactory.createTitledBorder("How to Use")

        val instructions = """
            <html>
            <div style='width: 400px; padding: 5px;'>
            <p><b>Replay</b> - Run the workflow history against your local implementation to verify it produces the same results.</p>
            <p><b>Debug Replay</b> - Same as Replay, but with the debugger attached so you can set breakpoints.</p>
            <p><b>Import JSON</b> - Load history from a JSON file (exported from Temporal CLI or Web UI).</p>
            <p><b>Export JSON</b> - Save the loaded workflow history to a JSON file for sharing or later replay.</p>
            <br/>
            <p><i>Note: The workflow implementation class must be available in your project's test classpath.</i></p>
            </div>
            </html>
        """.trimIndent()

        instructionsLabel.text = instructions
        panel.add(instructionsLabel, BorderLayout.CENTER)

        return panel
    }

    /**
     * Set the workflow context for replay operations.
     * Called when a workflow is loaded in the inspector.
     */
    fun setWorkflowContext(
        workflowId: String?,
        runId: String?,
        rawEvents: List<HistoryEvent>,
        workflowType: String?
    ) {
        this.currentWorkflowId = workflowId
        this.currentRunId = runId
        this.cachedRawEvents = rawEvents
        this.workflowType = workflowType
        updateButtonStates()
    }

    /**
     * Clear the workflow context when switching workflows or on error.
     */
    fun clearWorkflowContext() {
        this.currentWorkflowId = null
        this.currentRunId = null
        this.cachedRawEvents = emptyList()
        this.workflowType = null
        updateButtonStates()
    }

    private fun updateButtonStates() {
        val hasWorkflow = currentWorkflowId != null && cachedRawEvents.isNotEmpty()
        replayButton.isEnabled = hasWorkflow
        debugReplayButton.isEnabled = hasWorkflow
        exportHistoryButton.isEnabled = hasWorkflow
        // Import is always enabled
        importHistoryButton.isEnabled = true
    }

    private fun startReplay(debug: Boolean) {
        val workflowId = currentWorkflowId ?: return
        val type = workflowType ?: return

        if (cachedRawEvents.isEmpty()) {
            return
        }

        val history = History.newBuilder().addAllEvents(cachedRawEvents).build()
        WorkflowReplayService(project).replayWithCachedHistory(history, type, workflowId, debug)
    }

    private fun importHistoryFile() {
        WorkflowReplayService(project).replayFromFile()
    }

    private fun exportHistoryFile() {
        val workflowId = currentWorkflowId ?: return

        if (cachedRawEvents.isEmpty()) {
            return
        }

        val defaultFileName = "workflow-history-$workflowId.json"
        val history = History.newBuilder().addAllEvents(cachedRawEvents).build()

        // Use FileSaverDialog for save
        val descriptor = com.intellij.openapi.fileChooser.FileSaverDescriptor(
            "Export Workflow History",
            "Save workflow history as JSON file",
            "json"
        )

        val baseDir = project.basePath?.let {
            com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(it)
        }

        val dialog = com.intellij.openapi.fileChooser.FileChooserFactory.getInstance()
            .createSaveFileDialog(descriptor, project)

        val wrapper = dialog.save(baseDir, defaultFileName)
        if (wrapper != null) {
            try {
                val file = wrapper.file
                val exporter = io.temporal.intellij.replay.HistoryExporter()
                val json = exporter.exportToJson(history)
                file.writeText(json)
                com.intellij.openapi.ui.Messages.showInfoMessage(
                    project,
                    "History exported to:\n${file.absolutePath}",
                    "Export Successful"
                )
            } catch (e: Exception) {
                com.intellij.openapi.ui.Messages.showErrorDialog(
                    project,
                    "Failed to export history: ${e.message}",
                    "Export Failed"
                )
            }
        }
    }
}

/**
 * Panel showing replay status.
 * Subscribes to ReplayProgressListener to show replay progress.
 */
class ReplayStatusPanel(private val project: Project) : JBPanel<ReplayStatusPanel>(BorderLayout()) {
    private val statusLabel = JBLabel()
    private val workflowLink = com.intellij.ui.components.ActionLink("") { navigateToClass(lastWorkflowType) }
    private val errorLabel = JBLabel()
    private var currentStatus: ReplayState = ReplayState.READY

    private enum class ReplayState {
        READY, REPLAYING, SUCCESS, FAILED
    }

    private var lastWorkflowType: String = ""
    private var lastErrorMessage: String? = null

    init {
        border = JBUI.Borders.empty(5)

        // Layout
        val contentPanel = JBPanel<JBPanel<*>>(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            anchor = GridBagConstraints.WEST
            insets = Insets(2, 5, 2, 5)
        }

        // Status row
        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 2
        contentPanel.add(statusLabel, gbc)

        // Workflow row
        gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 1
        contentPanel.add(JBLabel("<html><b>Workflow:</b></html>"), gbc)
        gbc.gridx = 1
        contentPanel.add(workflowLink, gbc)

        // Error row
        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 2
        contentPanel.add(errorLabel, gbc)

        add(contentPanel, BorderLayout.CENTER)
        updateDisplay()

        // Subscribe to replay progress events
        project.messageBus.connect().subscribe(
            ReplayProgressListener.TOPIC,
            object : ReplayProgressListener {
                override fun onReplayStarted(workflowId: String, workflowType: String) {
                    currentStatus = ReplayState.REPLAYING
                    lastWorkflowType = workflowType
                    lastErrorMessage = null
                    ApplicationManager.getApplication().invokeLater { updateDisplay() }
                }

                override fun onReplayFinished(workflowId: String, success: Boolean, errorMessage: String?) {
                    currentStatus = if (success) ReplayState.SUCCESS else ReplayState.FAILED
                    lastErrorMessage = errorMessage
                    ApplicationManager.getApplication().invokeLater { updateDisplay() }
                }
            }
        )
    }

    private fun navigateToClass(className: String) {
        if (className.isEmpty()) return

        // Convert JVM inner class notation ($) to PSI notation (.)
        val psiClassName = className.replace('$', '.')

        // Run PSI lookup on background thread to avoid blocking EDT
        ApplicationManager.getApplication().executeOnPooledThread {
            com.intellij.openapi.application.ReadAction.run<RuntimeException> {
                val psiClass = JavaPsiFacade.getInstance(project)
                    .findClass(psiClassName, GlobalSearchScope.allScope(project))

                if (psiClass == null) {
                    return@run
                }

                val navigatable = psiClass.navigationElement as? com.intellij.pom.Navigatable
                val vFile = psiClass.containingFile?.virtualFile

                // Navigate on EDT
                ApplicationManager.getApplication().invokeLater {
                    if (navigatable != null && navigatable.canNavigate()) {
                        navigatable.navigate(true)
                    } else if (vFile != null) {
                        com.intellij.openapi.fileEditor.OpenFileDescriptor(project, vFile).navigate(true)
                    }
                }
            }
        }
    }

    private fun updateDisplay() {
        val (statusText, statusColor) = when (currentStatus) {
            ReplayState.READY -> "Ready" to "#757575"
            ReplayState.REPLAYING -> "Replaying..." to "#2196F3"
            ReplayState.SUCCESS -> "Success" to "#4CAF50"
            ReplayState.FAILED -> "Failed" to "#f44336"
        }

        statusLabel.text = "<html><b>Last Replay Status:</b> " +
            "<span style='color: $statusColor;'>$statusText</span></html>"

        // Update workflow link - extract simple name (handles both . and $ separators)
        if (lastWorkflowType.isNotEmpty() && currentStatus != ReplayState.READY) {
            val simpleName = lastWorkflowType.substringAfterLast('.').substringAfterLast('$')
            workflowLink.text = simpleName
            workflowLink.isVisible = true
        } else {
            workflowLink.isVisible = false
        }

        // Update error label
        if (currentStatus == ReplayState.FAILED && lastErrorMessage != null) {
            errorLabel.text = "<html><b>Error:</b> <font color='#f44336'>${escapeHtml(lastErrorMessage!!)}</font></html>"
            errorLabel.isVisible = true
        } else {
            errorLabel.isVisible = false
        }
    }

    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }
}
