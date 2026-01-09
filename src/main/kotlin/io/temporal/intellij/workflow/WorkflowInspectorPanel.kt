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
import javax.swing.Box
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Panel for inspecting workflow executions.
 */
class WorkflowInspectorPanel(private val project: Project) : JBPanel<WorkflowInspectorPanel>(BorderLayout()) {

    private val settings = TemporalSettings.getInstance(project)
    private var workflowService: WorkflowService? = null

    // Input components - use fixed column width for UUID display
    private val workflowIdField = JBTextField(36)  // UUID length
    private val browseButton = JButton("...")
    private val runIdField = JBTextField(36)  // UUID length
    private val inspectButton = JButton("Inspect")
    private val refreshButton = JButton(AllIcons.Actions.Refresh)

    // Display components
    private val statusLabel = JBLabel()
    private val tabbedPane = JTabbedPane()
    private val executionInfoPanel = ExecutionInfoPanel()
    private val pendingActivitiesPanel = PendingActivitiesPanel()
    private val pendingChildrenPanel = PendingChildrenPanel()
    private val eventHistoryTreePanel = EventHistoryTreePanel()
    private val queryPanel = QueryPanel(project)

    // Auto-refresh components
    private val autoRefreshCheckbox = JCheckBox("Auto-refresh")
    private val refreshIntervalCombo = JComboBox(arrayOf("3s", "5s", "10s", "30s"))
    private var autoRefreshTimer: Timer? = null
    private val isRefreshing = AtomicBoolean(false)
    private val lastRefreshLabel = JBLabel()

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

        // Content panel with tabs
        val contentPanel = JBPanel<JBPanel<*>>(BorderLayout())
        contentPanel.add(statusLabel, BorderLayout.NORTH)

        // Overview tab - execution info and pending items
        val overviewPanel = JBPanel<JBPanel<*>>()
        overviewPanel.layout = BoxLayout(overviewPanel, BoxLayout.Y_AXIS)
        overviewPanel.add(executionInfoPanel)
        overviewPanel.add(Box.createVerticalStrut(10))
        overviewPanel.add(pendingActivitiesPanel)
        overviewPanel.add(Box.createVerticalStrut(10))
        overviewPanel.add(pendingChildrenPanel)
        overviewPanel.add(Box.createVerticalGlue())

        val overviewScrollPane = JBScrollPane(overviewPanel)
        overviewScrollPane.border = JBUI.Borders.empty()

        // Add tabs
        tabbedPane.addTab("Overview", overviewScrollPane)
        tabbedPane.addTab("History", eventHistoryTreePanel)
        tabbedPane.addTab("Query", queryPanel)

        contentPanel.add(tabbedPane, BorderLayout.CENTER)

        add(contentPanel, BorderLayout.CENTER)

        // Initially hide details until a workflow is loaded
        setDetailsVisible(false)
    }

    private fun createInputPanel(): JPanel {
        val panel = JBPanel<JBPanel<*>>()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = JBUI.Borders.emptyBottom(10)

        // Row 1: Workflow ID
        val row1 = JBPanel<JBPanel<*>>(BorderLayout(5, 0))
        row1.add(JBLabel("Workflow ID:"), BorderLayout.WEST)
        workflowIdField.toolTipText = "Enter the workflow ID to inspect"
        row1.add(workflowIdField, BorderLayout.CENTER)
        browseButton.toolTipText = "Browse recent workflows"
        browseButton.addActionListener { browseWorkflows() }
        row1.add(browseButton, BorderLayout.EAST)
        panel.add(row1)
        panel.add(Box.createVerticalStrut(5))

        // Row 2: Run ID
        val row2 = JBPanel<JBPanel<*>>(BorderLayout(5, 0))
        row2.add(JBLabel("Run ID (optional):"), BorderLayout.WEST)
        runIdField.toolTipText = "Leave empty to get the latest run"
        runIdField.emptyText.text = "latest"
        row2.add(runIdField, BorderLayout.CENTER)
        panel.add(row2)
        panel.add(Box.createVerticalStrut(5))

        // Row 3: Action buttons
        val row3 = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 5, 0))
        inspectButton.toolTipText = "Fetch and display workflow execution details"
        inspectButton.addActionListener { loadWorkflow() }
        row3.add(inspectButton)
        refreshButton.toolTipText = "Refresh workflow info"
        refreshButton.addActionListener { refreshWorkflow() }
        refreshButton.isEnabled = false
        row3.add(refreshButton)

        // Auto-refresh controls
        row3.add(Box.createHorizontalStrut(15))
        autoRefreshCheckbox.toolTipText = "Automatically refresh workflow info at the specified interval"
        autoRefreshCheckbox.isEnabled = false
        autoRefreshCheckbox.addActionListener { toggleAutoRefresh() }
        row3.add(autoRefreshCheckbox)

        refreshIntervalCombo.toolTipText = "Refresh interval"
        refreshIntervalCombo.selectedIndex = 1  // Default to 5s
        refreshIntervalCombo.isEnabled = false
        refreshIntervalCombo.addActionListener {
            if (autoRefreshCheckbox.isSelected) {
                restartAutoRefreshTimer()
            }
        }
        row3.add(refreshIntervalCombo)

        lastRefreshLabel.foreground = JBColor.GRAY
        lastRefreshLabel.border = JBUI.Borders.emptyLeft(10)
        row3.add(lastRefreshLabel)

        panel.add(row3)

        // Enter key triggers inspect
        workflowIdField.addActionListener { loadWorkflow() }
        runIdField.addActionListener { loadWorkflow() }

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

    private fun toggleAutoRefresh() {
        if (autoRefreshCheckbox.isSelected) {
            startAutoRefreshTimer()
        } else {
            stopAutoRefreshTimer()
        }
    }

    private fun startAutoRefreshTimer() {
        stopAutoRefreshTimer()
        val intervalMs = getRefreshIntervalMs()
        autoRefreshTimer = Timer(intervalMs) { performAutoRefresh() }
        autoRefreshTimer?.isRepeats = true
        autoRefreshTimer?.start()
        updateLastRefreshLabel()
    }

    private fun restartAutoRefreshTimer() {
        if (autoRefreshCheckbox.isSelected) {
            startAutoRefreshTimer()
        }
    }

    private fun stopAutoRefreshTimer() {
        autoRefreshTimer?.stop()
        autoRefreshTimer = null
        lastRefreshLabel.text = ""
    }

    private fun getRefreshIntervalMs(): Int {
        return when (refreshIntervalCombo.selectedItem as String) {
            "3s" -> 3000
            "5s" -> 5000
            "10s" -> 10000
            "30s" -> 30000
            else -> 5000
        }
    }

    private fun performAutoRefresh() {
        if (isRefreshing.get()) return  // Skip if already refreshing
        val workflowId = currentWorkflowId ?: return

        isRefreshing.set(true)
        updateLastRefreshLabel()

        // Run refresh in background without showing progress dialog
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val service = workflowService ?: return@executeOnPooledThread
                val result = service.describeWorkflow(workflowId, currentRunId)

                ApplicationManager.getApplication().invokeLater {
                    if (result.isSuccess) {
                        val info = result.getOrNull()!!
                        executionInfoPanel.update(info)
                        pendingActivitiesPanel.update(info.pendingActivities)
                        pendingChildrenPanel.update(info.pendingChildren)

                        // Also refresh event history
                        refreshEventHistorySilently()
                    }
                    isRefreshing.set(false)
                    updateLastRefreshLabel()
                }
            } catch (e: Exception) {
                isRefreshing.set(false)
                ApplicationManager.getApplication().invokeLater {
                    updateLastRefreshLabel()
                }
            }
        }
    }

    private fun refreshEventHistorySilently() {
        val workflowId = currentWorkflowId ?: return
        val service = workflowService ?: return

        ApplicationManager.getApplication().executeOnPooledThread {
            val result = service.getWorkflowHistory(workflowId, currentRunId)
            ApplicationManager.getApplication().invokeLater {
                if (result.isSuccess) {
                    val historyPage = result.getOrNull()!!
                    eventHistoryTreePanel.update(historyPage.events)
                }
            }
        }
    }

    private fun updateLastRefreshLabel() {
        val now = java.time.LocalTime.now()
        val timeStr = now.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))
        if (isRefreshing.get()) {
            lastRefreshLabel.text = "Refreshing..."
        } else if (autoRefreshCheckbox.isSelected) {
            lastRefreshLabel.text = "Last: $timeStr"
        } else {
            lastRefreshLabel.text = ""
        }
    }

    private fun browseWorkflows() {
        browseButton.isEnabled = false
        statusLabel.text = "<html><i>Loading recent workflows...</i></html>"
        statusLabel.isVisible = true

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Loading Workflows", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Connecting to Temporal..."
                indicator.isIndeterminate = true

                val service = getOrCreateService()
                val connectResult = service.connect()
                if (connectResult.isFailure) {
                    showError("Connection failed: ${connectResult.exceptionOrNull()?.message}")
                    ApplicationManager.getApplication().invokeLater {
                        browseButton.isEnabled = true
                    }
                    return
                }

                indicator.text = "Fetching recent workflows..."
                val result = service.listWorkflows(20)

                ApplicationManager.getApplication().invokeLater {
                    browseButton.isEnabled = true
                    statusLabel.isVisible = false

                    if (result.isSuccess) {
                        val workflows = result.getOrNull()!!
                        val dialog = WorkflowChooserDialog(project, workflows)
                        if (dialog.showAndGet()) {
                            dialog.selectedWorkflow?.let { selected ->
                                workflowIdField.text = selected.workflowId
                                runIdField.text = selected.runId
                                loadWorkflow()
                            }
                        }
                    } else {
                        showError(result.exceptionOrNull()?.message ?: "Failed to load workflows")
                    }
                }
            }
        })
    }

    private fun fetchWorkflowInfo(workflowId: String, runId: String?) {
        statusLabel.text = "<html><i>Loading workflow...</i></html>"
        statusLabel.isVisible = true
        setDetailsVisible(false)
        inspectButton.isEnabled = false
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
                    inspectButton.isEnabled = true
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

        // Enable auto-refresh controls
        autoRefreshCheckbox.isEnabled = true
        refreshIntervalCombo.isEnabled = true

        // Set up query panel context
        queryPanel.setWorkflowContext(currentWorkflowId, currentRunId, workflowService)

        // Load event history in background
        loadEventHistory()
    }

    private fun loadEventHistory() {
        val workflowId = currentWorkflowId ?: return
        val service = workflowService ?: return

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Loading Event History", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Fetching event history..."
                indicator.isIndeterminate = true

                val result = service.getWorkflowHistory(workflowId, currentRunId)

                ApplicationManager.getApplication().invokeLater {
                    if (result.isSuccess) {
                        val historyPage = result.getOrNull()!!
                        eventHistoryTreePanel.update(historyPage.events)
                    } else {
                        // Show error in status but don't fail the whole display
                        eventHistoryTreePanel.clear()
                    }
                }
            }
        })
    }

    private fun showError(message: String) {
        ApplicationManager.getApplication().invokeLater {
            statusLabel.text = "<html><font color='red'>$message</font></html>"
            statusLabel.isVisible = true
            setDetailsVisible(false)
            inspectButton.isEnabled = true
            refreshButton.isEnabled = currentWorkflowId != null

            // Disable auto-refresh on error
            stopAutoRefreshTimer()
            autoRefreshCheckbox.isSelected = false
            autoRefreshCheckbox.isEnabled = false
            refreshIntervalCombo.isEnabled = false
        }
    }

    private fun setDetailsVisible(visible: Boolean) {
        tabbedPane.isVisible = visible
    }

    fun expandAllHistory() {
        eventHistoryTreePanel.expandAll()
    }

    fun collapseAllHistory() {
        eventHistoryTreePanel.collapseAll()
    }

    fun dispose() {
        stopAutoRefreshTimer()
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
