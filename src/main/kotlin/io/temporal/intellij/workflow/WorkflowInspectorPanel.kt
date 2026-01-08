package io.temporal.intellij.workflow

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import io.temporal.intellij.settings.TemporalSettings
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.*

/**
 * Panel for inspecting workflow executions.
 */
class WorkflowInspectorPanel(private val project: Project) : JBPanel<WorkflowInspectorPanel>(BorderLayout()) {

    private val settings = TemporalSettings.getInstance(project)
    private var workflowService: WorkflowService? = null

    // Input components
    private val workflowIdField = JBTextField(30)
    private val runIdField = JBTextField(20)
    private val watchButton = JButton("Watch")
    private val refreshButton = JButton(AllIcons.Actions.Refresh)

    // Display components
    private val statusLabel = JBLabel()
    private val executionInfoPanel = ExecutionInfoPanel()
    private val pendingActivitiesPanel = PendingActivitiesPanel()
    private val pendingChildrenPanel = PendingChildrenPanel()

    // State
    private var currentWorkflowId: String? = null
    private var currentRunId: String? = null

    init {
        border = JBUI.Borders.empty(5)

        // Input panel
        val inputPanel = createInputPanel()
        add(inputPanel, BorderLayout.NORTH)

        // Status label
        statusLabel.border = JBUI.Borders.empty(5, 0)
        statusLabel.isVisible = false

        // Content panel with scroll
        val contentPanel = JBPanel<JBPanel<*>>(BorderLayout())
        contentPanel.add(statusLabel, BorderLayout.NORTH)

        val detailsPanel = JBPanel<JBPanel<*>>()
        detailsPanel.layout = BoxLayout(detailsPanel, BoxLayout.Y_AXIS)
        detailsPanel.add(executionInfoPanel)
        detailsPanel.add(Box.createVerticalStrut(10))
        detailsPanel.add(pendingActivitiesPanel)
        detailsPanel.add(Box.createVerticalStrut(10))
        detailsPanel.add(pendingChildrenPanel)
        detailsPanel.add(Box.createVerticalGlue())

        val scrollPane = JBScrollPane(detailsPanel)
        scrollPane.border = JBUI.Borders.empty()
        contentPanel.add(scrollPane, BorderLayout.CENTER)

        add(contentPanel, BorderLayout.CENTER)

        // Initially hide details until a workflow is loaded
        setDetailsVisible(false)
    }

    private fun createInputPanel(): JPanel {
        val panel = JPanel(GridBagLayout())
        panel.border = JBUI.Borders.emptyBottom(10)
        val gbc = GridBagConstraints()

        // Workflow ID label and field
        gbc.gridx = 0
        gbc.gridy = 0
        gbc.anchor = GridBagConstraints.WEST
        gbc.insets = JBUI.insets(2)
        panel.add(JBLabel("Workflow ID:"), gbc)

        gbc.gridx = 1
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.weightx = 1.0
        workflowIdField.toolTipText = "Enter the workflow ID to inspect"
        panel.add(workflowIdField, gbc)

        // Run ID label and field (optional)
        gbc.gridx = 2
        gbc.fill = GridBagConstraints.NONE
        gbc.weightx = 0.0
        panel.add(JBLabel("Run ID:"), gbc)

        gbc.gridx = 3
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.weightx = 0.3
        runIdField.toolTipText = "Optional: specific run ID"
        panel.add(runIdField, gbc)

        // Buttons
        val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT, 5, 0))
        watchButton.toolTipText = "Load workflow execution info"
        watchButton.addActionListener { loadWorkflow() }
        buttonPanel.add(watchButton)

        refreshButton.toolTipText = "Refresh workflow info"
        refreshButton.addActionListener { refreshWorkflow() }
        refreshButton.isEnabled = false
        buttonPanel.add(refreshButton)

        gbc.gridx = 4
        gbc.fill = GridBagConstraints.NONE
        gbc.weightx = 0.0
        panel.add(buttonPanel, gbc)

        // Enter key triggers watch
        workflowIdField.addActionListener { loadWorkflow() }

        return panel
    }

    private fun loadWorkflow() {
        val workflowId = workflowIdField.text.trim()
        if (workflowId.isEmpty()) {
            showError("Please enter a Workflow ID")
            return
        }

        val runId = runIdField.text.trim().ifEmpty { null }
        currentWorkflowId = workflowId
        currentRunId = runId

        fetchWorkflowInfo(workflowId, runId)
    }

    private fun refreshWorkflow() {
        currentWorkflowId?.let { workflowId ->
            fetchWorkflowInfo(workflowId, currentRunId)
        }
    }

    private fun fetchWorkflowInfo(workflowId: String, runId: String?) {
        statusLabel.text = "<html><i>Loading workflow...</i></html>"
        statusLabel.isVisible = true
        setDetailsVisible(false)
        watchButton.isEnabled = false
        refreshButton.isEnabled = false

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Loading Workflow", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Connecting to Temporal..."
                indicator.isIndeterminate = true

                // Get or create service
                val service = getOrCreateService()
                val connectResult = service.connect()
                if (connectResult.isFailure) {
                    showError("Connection failed: ${connectResult.exceptionOrNull()?.message}")
                    return
                }

                indicator.text = "Fetching workflow info..."
                val result = service.describeWorkflow(workflowId, runId)

                ApplicationManager.getApplication().invokeLater {
                    watchButton.isEnabled = true
                    if (result.isSuccess) {
                        displayWorkflowInfo(result.getOrNull()!!)
                        refreshButton.isEnabled = true
                    } else {
                        showError(result.exceptionOrNull()?.message ?: "Unknown error")
                    }
                }
            }
        })
    }

    private fun getOrCreateService(): WorkflowService {
        return workflowService ?: WorkflowService(settings.state).also { workflowService = it }
    }

    private fun displayWorkflowInfo(info: WorkflowExecutionInfo) {
        statusLabel.isVisible = false
        setDetailsVisible(true)

        executionInfoPanel.update(info)
        pendingActivitiesPanel.update(info.pendingActivities)
        pendingChildrenPanel.update(info.pendingChildren)
    }

    private fun showError(message: String) {
        ApplicationManager.getApplication().invokeLater {
            statusLabel.text = "<html><font color='red'>$message</font></html>"
            statusLabel.isVisible = true
            setDetailsVisible(false)
            watchButton.isEnabled = true
            refreshButton.isEnabled = currentWorkflowId != null
        }
    }

    private fun setDetailsVisible(visible: Boolean) {
        executionInfoPanel.isVisible = visible
        pendingActivitiesPanel.isVisible = visible
        pendingChildrenPanel.isVisible = visible
    }

    fun dispose() {
        workflowService?.disconnect()
        workflowService = null
    }
}

/**
 * Panel showing workflow execution info.
 */
class ExecutionInfoPanel : JBPanel<ExecutionInfoPanel>(BorderLayout()) {
    private val contentLabel = JBLabel()

    init {
        border = JBUI.Borders.empty(5)
        add(contentLabel, BorderLayout.CENTER)
    }

    fun update(info: WorkflowExecutionInfo) {
        val statusColor = when (info.status) {
            WorkflowStatus.RUNNING -> "#4CAF50"
            WorkflowStatus.COMPLETED -> "#2196F3"
            WorkflowStatus.FAILED -> "#f44336"
            WorkflowStatus.CANCELED -> "#FF9800"
            WorkflowStatus.TERMINATED -> "#9C27B0"
            WorkflowStatus.TIMED_OUT -> "#FF5722"
            else -> "#757575"
        }

        val startedAgo = info.startTime?.let { formatTimeAgo(it) } ?: "Unknown"
        val historySize = formatBytes(info.historySizeBytes)

        contentLabel.text = """
            <html>
            <body style='font-family: sans-serif;'>
            <table cellpadding='3'>
                <tr>
                    <td colspan='2'><b style='font-size: 1.1em;'>▼ EXECUTION INFO</b>
                    <span style='background-color: $statusColor; color: white; padding: 2px 6px; border-radius: 3px; margin-left: 10px;'>
                        ${info.status}
                    </span></td>
                </tr>
                <tr><td><b>Type:</b></td><td>${info.workflowType}</td></tr>
                <tr><td><b>Run ID:</b></td><td><code>${info.runId}</code></td></tr>
                <tr><td><b>Task Queue:</b></td><td>${info.taskQueue}</td></tr>
                <tr><td><b>Started:</b></td><td>${formatTime(info.startTime)} ($startedAgo)</td></tr>
                <tr><td><b>History:</b></td><td>${info.historyLength} events ($historySize)</td></tr>
            </table>
            </body>
            </html>
        """.trimIndent()
    }

    private fun formatTime(instant: Instant?): String {
        return instant?.atZone(ZoneId.systemDefault())
            ?.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            ?: "N/A"
    }

    private fun formatTimeAgo(instant: Instant): String {
        val duration = Duration.between(instant, Instant.now())
        return when {
            duration.toMinutes() < 1 -> "just now"
            duration.toMinutes() < 60 -> "${duration.toMinutes()} min ago"
            duration.toHours() < 24 -> "${duration.toHours()} hours ago"
            else -> "${duration.toDays()} days ago"
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "${bytes / (1024 * 1024)} MB"
        }
    }
}

/**
 * Panel showing pending activities.
 */
class PendingActivitiesPanel : JBPanel<PendingActivitiesPanel>(BorderLayout()) {
    private val contentLabel = JBLabel()
    private val headerLabel = JBLabel("<html><b>▼ PENDING ACTIVITIES (0)</b></html>")

    init {
        border = JBUI.Borders.empty(5)
        add(headerLabel, BorderLayout.NORTH)
        add(contentLabel, BorderLayout.CENTER)
    }

    fun update(activities: List<PendingActivityInfo>) {
        headerLabel.text = "<html><b>▼ PENDING ACTIVITIES (${activities.size})</b></html>"

        if (activities.isEmpty()) {
            contentLabel.text = "<html><i style='color: gray;'>No pending activities</i></html>"
            return
        }

        val sb = StringBuilder("<html><body style='font-family: sans-serif;'>")
        for (activity in activities) {
            val stateColor = when {
                activity.state.contains("STARTED") -> "#4CAF50"
                activity.state.contains("SCHEDULED") -> "#2196F3"
                activity.state.contains("CANCEL") -> "#FF9800"
                else -> "#757575"
            }

            sb.append("""
                <div style='border: 1px solid #ddd; padding: 8px; margin: 5px 0; border-radius: 4px;'>
                    <b>${activity.activityType}</b><br/>
                    <span style='color: $stateColor;'>State: ${activity.state}</span> |
                    Attempt: ${activity.attempt}/${if (activity.maxAttempts > 0) activity.maxAttempts else "∞"}<br/>
            """)

            if (activity.lastStartedTime != null) {
                sb.append("Started: ${formatTimeAgo(activity.lastStartedTime)}<br/>")
            }

            if (activity.lastHeartbeatTime != null) {
                sb.append("Last Heartbeat: ${formatTimeAgo(activity.lastHeartbeatTime)}")
                if (activity.heartbeatDetails != null) {
                    sb.append(" - <i>\"${activity.heartbeatDetails}\"</i>")
                }
                sb.append("<br/>")
            }

            if (activity.lastFailureMessage.isNotEmpty()) {
                sb.append("<span style='color: red;'>Last Failure: ${activity.lastFailureMessage}</span><br/>")
            }

            sb.append("</div>")
        }
        sb.append("</body></html>")

        contentLabel.text = sb.toString()
    }

    private fun formatTimeAgo(instant: Instant): String {
        val duration = Duration.between(instant, Instant.now())
        return when {
            duration.seconds < 60 -> "${duration.seconds}s ago"
            duration.toMinutes() < 60 -> "${duration.toMinutes()}m ago"
            else -> "${duration.toHours()}h ago"
        }
    }
}

/**
 * Panel showing pending child workflows.
 */
class PendingChildrenPanel : JBPanel<PendingChildrenPanel>(BorderLayout()) {
    private val contentLabel = JBLabel()
    private val headerLabel = JBLabel("<html><b>▼ PENDING CHILD WORKFLOWS (0)</b></html>")

    init {
        border = JBUI.Borders.empty(5)
        add(headerLabel, BorderLayout.NORTH)
        add(contentLabel, BorderLayout.CENTER)
    }

    fun update(children: List<PendingChildWorkflowInfo>) {
        headerLabel.text = "<html><b>▼ PENDING CHILD WORKFLOWS (${children.size})</b></html>"

        if (children.isEmpty()) {
            contentLabel.text = "<html><i style='color: gray;'>No pending child workflows</i></html>"
            return
        }

        val sb = StringBuilder("<html><body style='font-family: sans-serif;'>")
        for (child in children) {
            sb.append("""
                <div style='border: 1px solid #ddd; padding: 8px; margin: 5px 0; border-radius: 4px;'>
                    <b>${child.workflowType}</b><br/>
                    ID: <code>${child.workflowId}</code><br/>
                    Run ID: <code>${child.runId}</code>
                </div>
            """)
        }
        sb.append("</body></html>")

        contentLabel.text = sb.toString()
    }
}
