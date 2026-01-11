package io.temporal.intellij.workflow

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import io.temporal.api.history.v1.History
import io.temporal.api.history.v1.HistoryEvent
import io.temporal.intellij.replay.ReplayProgressListener
import io.temporal.intellij.replay.WorkflowReplayService
import io.temporal.intellij.settings.TemporalSettings
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.Box
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.*

/**
 * Display mode for event history.
 * - LIVE: Show all events and auto-update on new events
 * - PAUSED: Buffer events but don't update display (long poll continues)
 * - FROZEN: Show snapshot at refresh time, ignore new events until refresh or live mode
 */
enum class DisplayMode {
    LIVE,
    PAUSED,
    FROZEN
}

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
    private val refreshButton = JButton("Refresh")
    private val replayButton = JButton("Replay")
    private val debugReplayButton = JButton("Debug")
    private val importHistoryButton = JButton("Import JSON...")

    // Display components
    private val statusLabel = JBLabel()
    private val tabbedPane = JTabbedPane()
    private val executionInfoPanel = ExecutionInfoPanel()
    private val replayStatusPanel = ReplayStatusPanel(project)
    private val pendingActivitiesPanel = PendingActivitiesPanel()
    private val pendingChildrenPanel = PendingChildrenPanel()
    private val eventHistoryTreePanel = EventHistoryTreePanel()
    private val queryPanel = QueryPanel(project)

    // Auto-refresh components (long polling)
    private val autoRefreshCheckbox = JCheckBox("Live updates")
    @Volatile private var longPollActive = false
    private var longPollThread: Thread? = null
    private val lastRefreshLabel = JBLabel()

    // State - these are accessed from both EDT and long poll thread
    @Volatile private var currentWorkflowId: String? = null
    @Volatile private var currentRunId: String? = null

    // Lock for coordinating access to event state between EDT and long poll thread
    private val stateLock = Object()

    // Cached events and token for incremental refresh (thread-safe for access from long poll thread and EDT)
    // cachedEvents: ALL events fetched from long poll (always accumulating)
    // cachedRawEvents: Raw HistoryEvent protos for replay export
    // displayedEvents: Events currently shown on screen (may be frozen snapshot)
    // Access to these should be synchronized on stateLock when reading/writing multiple together
    private val cachedEvents = mutableListOf<WorkflowHistoryEvent>()
    private val cachedRawEvents = mutableListOf<HistoryEvent>()
    private val displayedEvents = mutableListOf<WorkflowHistoryEvent>()
    @Volatile private var lastNextToken = com.google.protobuf.ByteString.EMPTY
    @Volatile private var displayMode = DisplayMode.PAUSED

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
        val overviewContainer = JBPanel<JBPanel<*>>(BorderLayout())

        // Refresh button header for Overview tab
        val overviewHeader = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.RIGHT, 5, 2))
        val overviewRefreshButton = JButton(AllIcons.Actions.Refresh)
        overviewRefreshButton.toolTipText = "Refresh workflow info"
        overviewRefreshButton.preferredSize = java.awt.Dimension(28, 28)
        overviewRefreshButton.addActionListener { refreshOverviewInfo() }
        overviewHeader.add(overviewRefreshButton)
        overviewContainer.add(overviewHeader, BorderLayout.NORTH)

        val overviewPanel = JBPanel<JBPanel<*>>()
        overviewPanel.layout = BoxLayout(overviewPanel, BoxLayout.Y_AXIS)
        overviewPanel.add(executionInfoPanel)
        overviewPanel.add(Box.createVerticalStrut(10))
        overviewPanel.add(replayStatusPanel)
        overviewPanel.add(Box.createVerticalStrut(10))
        overviewPanel.add(pendingActivitiesPanel)
        overviewPanel.add(Box.createVerticalStrut(10))
        overviewPanel.add(pendingChildrenPanel)
        overviewPanel.add(Box.createVerticalGlue())

        val overviewScrollPane = JBScrollPane(overviewPanel)
        overviewScrollPane.border = JBUI.Borders.empty()
        overviewContainer.add(overviewScrollPane, BorderLayout.CENTER)

        // Add tabs
        tabbedPane.addTab("Overview", overviewContainer)
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
        refreshButton.toolTipText = "Fetch and display workflow execution details"
        refreshButton.addActionListener { loadWorkflow() }
        row3.add(refreshButton)

        replayButton.toolTipText = "Replay this workflow against local implementation"
        replayButton.isEnabled = false
        replayButton.addActionListener { startReplay(debug = false) }
        row3.add(replayButton)

        debugReplayButton.toolTipText = "Replay with debugger attached - set breakpoints in your workflow code"
        debugReplayButton.isEnabled = false
        debugReplayButton.addActionListener { startReplay(debug = true) }
        row3.add(debugReplayButton)

        importHistoryButton.toolTipText = "Import workflow history from JSON file and replay"
        importHistoryButton.addActionListener { importHistoryFile() }
        row3.add(importHistoryButton)

        // Live updates controls (long polling)
        row3.add(Box.createHorizontalStrut(10))
        autoRefreshCheckbox.toolTipText = "Watch for new events in real-time using long polling"
        autoRefreshCheckbox.isEnabled = false
        autoRefreshCheckbox.addActionListener { toggleAutoRefresh() }
        row3.add(autoRefreshCheckbox)

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

        // Same workflow - handle based on display mode
        if (currentWorkflowId == workflowId && currentRunId == runId) {
            handleRefreshForCurrentWorkflow()
            return
        }

        // Different workflow - full cleanup and reload
        cleanupCurrentWorkflow()

        currentWorkflowId = workflowId
        currentRunId = runId

        fetchWorkflowInfo(workflowId, runId)
    }

    /**
     * Handle refresh button press for the current workflow based on display mode.
     */
    private fun handleRefreshForCurrentWorkflow() {
        when (displayMode) {
            DisplayMode.LIVE -> {
                // Already showing everything, just refresh workflow info
                refreshWorkflowInfoSilently()
                eventHistoryTreePanel.scrollToBottom()
            }
            DisplayMode.PAUSED, DisplayMode.FROZEN -> {
                // Copy all cached events to display, then freeze
                val eventsSnapshot: List<WorkflowHistoryEvent>
                synchronized(stateLock) {
                    displayedEvents.clear()
                    displayedEvents.addAll(cachedEvents)
                    eventsSnapshot = displayedEvents.toList()
                    displayMode = DisplayMode.FROZEN
                }
                eventHistoryTreePanel.update(eventsSnapshot)
                eventHistoryTreePanel.scrollToBottom()
                refreshWorkflowInfoSilently()
                updateLastRefreshLabel()
            }
        }
    }

    /**
     * Clean up resources when switching to a different workflow.
     */
    private fun cleanupCurrentWorkflow() {
        // Stop long polling thread
        stopLongPolling()

        // Clear all event caches (synchronized)
        synchronized(stateLock) {
            cachedEvents.clear()
            cachedRawEvents.clear()
            displayedEvents.clear()
            lastNextToken = com.google.protobuf.ByteString.EMPTY
            displayMode = DisplayMode.PAUSED
        }

        // Clear UI
        eventHistoryTreePanel.clear()

        // Reset checkbox and button state
        autoRefreshCheckbox.isSelected = false
        autoRefreshCheckbox.isEnabled = false
        replayButton.isEnabled = false
        debugReplayButton.isEnabled = false
        lastRefreshLabel.text = ""
    }

    private fun toggleAutoRefresh() {
        if (autoRefreshCheckbox.isSelected) {
            // Switching to LIVE mode - show all buffered events and continue updating
            val eventsSnapshot: List<WorkflowHistoryEvent>
            synchronized(stateLock) {
                displayMode = DisplayMode.LIVE
                displayedEvents.clear()
                displayedEvents.addAll(cachedEvents)
                eventsSnapshot = displayedEvents.toList()
            }
            eventHistoryTreePanel.update(eventsSnapshot)
            eventHistoryTreePanel.scrollToBottom()
            updateLastRefreshLabel()
            // Long polling should already be running, but ensure it is
            if (!longPollActive) {
                startLongPolling()
            }
        } else {
            // Switching to PAUSED mode - keep buffering but stop updating display
            synchronized(stateLock) {
                displayMode = DisplayMode.PAUSED
            }
            updateLastRefreshLabel()
            // Note: long polling continues in background
        }
    }

    private fun startLongPolling() {
        stopLongPolling()
        val workflowId = currentWorkflowId ?: return
        val runId = currentRunId
        val service = workflowService ?: return

        longPollActive = true
        updateLastRefreshLabel()

        // Capture the starting token to continue from where we left off
        val startToken: com.google.protobuf.ByteString
        synchronized(stateLock) {
            startToken = lastNextToken
        }

        longPollThread = Thread {
            try {
                // Create the iterator - it handles pagination and long polling internally
                // Start from last token if we have cached events, otherwise start fresh
                val iterator = service.getHistoryIterator(workflowId, runId, startToken = startToken)

                while (longPollActive && currentWorkflowId == workflowId) {
                    if (!iterator.hasNext()) {
                        break
                    }
                    if (!longPollActive || currentWorkflowId != workflowId) {
                        break
                    }

                    val rawEvent = iterator.next()
                    val event = service.parseEvent(rawEvent)

                    // Always add to cached events and check display mode atomically
                    val (isNewEvent, shouldUpdateUi, eventsSnapshot) = synchronized(stateLock) {
                        val isNew = if (cachedEvents.none { it.eventId == event.eventId }) {
                            cachedEvents.add(event)
                            cachedRawEvents.add(rawEvent)
                            true
                        } else {
                            false
                        }

                        // Save token immediately after each event
                        lastNextToken = iterator.getNextToken()

                        // Check if we should update UI (only in LIVE mode)
                        val shouldUpdate = isNew && displayMode == DisplayMode.LIVE
                        val snapshot = if (shouldUpdate) {
                            displayedEvents.clear()
                            displayedEvents.addAll(cachedEvents)
                            displayedEvents.toList()
                        } else {
                            emptyList()
                        }

                        Triple(isNew, shouldUpdate, snapshot)
                    }

                    // Update UI outside the lock
                    if (shouldUpdateUi) {
                        ApplicationManager.getApplication().invokeLater {
                            // Re-check conditions on EDT
                            if (longPollActive && currentWorkflowId == workflowId && displayMode == DisplayMode.LIVE) {
                                eventHistoryTreePanel.update(eventsSnapshot)
                                eventHistoryTreePanel.scrollToBottom()
                                updateLastRefreshLabel()
                                refreshWorkflowInfoSilently()
                            }
                        }
                    }

                    // Check if workflow completed
                    if (isTerminalEvent(event.eventType)) {
                        ApplicationManager.getApplication().invokeLater {
                            // Keep long poll active flag true but disable checkbox
                            autoRefreshCheckbox.isEnabled = false
                            if (displayMode == DisplayMode.LIVE) {
                                lastRefreshLabel.text = "Workflow completed"
                            }
                        }
                        break
                    }
                }
            } catch (e: InterruptedException) {
                // Thread interrupted, exit gracefully
            } catch (e: Exception) {
                // On error, show error (but only if we were actively polling)
                if (longPollActive) {
                    val errorMsg = e.message?.take(50) ?: "Unknown error"
                    ApplicationManager.getApplication().invokeLater {
                        lastRefreshLabel.text = "Error: $errorMsg"
                    }
                }
            }

            ApplicationManager.getApplication().invokeLater {
                if (!longPollActive) {
                    lastRefreshLabel.text = ""
                }
            }
        }
        longPollThread?.name = "Temporal-LongPoll-$workflowId"
        longPollThread?.isDaemon = true
        longPollThread?.start()
    }

    private fun isTerminalEvent(eventType: String): Boolean {
        // Only check for WORKFLOW_EXECUTION terminal events, not activity/task events
        return eventType.contains("WORKFLOW_EXECUTION_COMPLETED") ||
               eventType.contains("WORKFLOW_EXECUTION_FAILED") ||
               eventType.contains("WORKFLOW_EXECUTION_CANCELED") ||
               eventType.contains("WORKFLOW_EXECUTION_TERMINATED") ||
               eventType.contains("WORKFLOW_EXECUTION_TIMED_OUT") ||
               eventType.contains("WORKFLOW_EXECUTION_CONTINUED_AS_NEW")
    }

    private fun stopLongPolling() {
        longPollActive = false
        longPollThread?.interrupt()
        longPollThread = null
        lastRefreshLabel.text = ""
    }

    private fun refreshWorkflowInfoSilently() {
        val workflowId = currentWorkflowId ?: return
        val service = workflowService ?: return

        ApplicationManager.getApplication().executeOnPooledThread {
            val result = service.describeWorkflow(workflowId, currentRunId)
            ApplicationManager.getApplication().invokeLater {
                if (result.isSuccess) {
                    val info = result.getOrNull()!!
                    executionInfoPanel.update(info)
                    pendingActivitiesPanel.update(info.pendingActivities)
                    pendingChildrenPanel.update(info.pendingChildren)
                }
            }
        }
    }

    private fun refreshOverviewInfo() {
        val workflowId = currentWorkflowId ?: return
        val service = workflowService ?: return

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Refreshing...", false) {
            override fun run(indicator: ProgressIndicator) {
                val result = service.describeWorkflow(workflowId, currentRunId)
                ApplicationManager.getApplication().invokeLater {
                    if (result.isSuccess) {
                        val info = result.getOrNull()!!
                        executionInfoPanel.update(info)
                        pendingActivitiesPanel.update(info.pendingActivities)
                        pendingChildrenPanel.update(info.pendingChildren)
                    }
                }
            }
        })
    }

    private fun updateLastRefreshLabel() {
        val now = java.time.LocalTime.now()
        val timeStr = now.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))

        val (mode, bufferedCount) = synchronized(stateLock) {
            Pair(displayMode, cachedEvents.size - displayedEvents.size)
        }

        when (mode) {
            DisplayMode.LIVE -> {
                lastRefreshLabel.text = "Live: $timeStr"
            }
            DisplayMode.PAUSED -> {
                if (bufferedCount > 0) {
                    lastRefreshLabel.text = "Paused (+$bufferedCount buffered)"
                } else {
                    lastRefreshLabel.text = "Paused"
                }
            }
            DisplayMode.FROZEN -> {
                if (bufferedCount > 0) {
                    lastRefreshLabel.text = "Frozen (+$bufferedCount new)"
                } else {
                    lastRefreshLabel.text = "Frozen at $timeStr"
                }
            }
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
                        val dialog = WorkflowChooserDialog(project, workflows, service)
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
                    refreshButton.isEnabled = true
                    if (result.isSuccess) {
                        displayWorkflowInfo(result.getOrNull()!!)
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

        // Enable live updates checkbox only for running workflows
        val isRunning = info.status == WorkflowStatus.RUNNING
        autoRefreshCheckbox.isEnabled = isRunning

        // Enable replay buttons when workflow is loaded
        replayButton.isEnabled = true
        debugReplayButton.isEnabled = true

        // Set up query panel context
        queryPanel.setWorkflowContext(currentWorkflowId, currentRunId, workflowService)

        if (isRunning) {
            // For running workflows: start in LIVE mode with long polling
            synchronized(stateLock) {
                displayMode = DisplayMode.LIVE
            }
            autoRefreshCheckbox.isSelected = true
            startLongPolling()
        } else {
            // For completed workflows: load full history without long polling
            synchronized(stateLock) {
                displayMode = DisplayMode.PAUSED
            }
            autoRefreshCheckbox.isSelected = false
            loadEventHistory()
        }
    }

    private fun loadEventHistory() {
        val workflowId = currentWorkflowId ?: return
        val service = workflowService ?: return

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Loading Event History", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Fetching event history..."
                indicator.isIndeterminate = true

                // Get raw history (includes all events)
                val historyResult = service.getRawHistory(workflowId, currentRunId)
                if (historyResult.isFailure) {
                    ApplicationManager.getApplication().invokeLater {
                        eventHistoryTreePanel.clear()
                        showError(historyResult.exceptionOrNull()?.message ?: "Failed to load history")
                    }
                    return
                }

                val history = historyResult.getOrNull()!!
                val rawEvents = history.eventsList
                val parsedEvents = rawEvents.map { service.parseEvent(it) }

                indicator.text = "Loaded ${rawEvents.size} events"

                // Populate all caches (synchronized)
                synchronized(stateLock) {
                    cachedEvents.clear()
                    cachedEvents.addAll(parsedEvents)
                    cachedRawEvents.clear()
                    cachedRawEvents.addAll(rawEvents)
                    displayedEvents.clear()
                    displayedEvents.addAll(parsedEvents)
                    lastNextToken = com.google.protobuf.ByteString.EMPTY
                }

                ApplicationManager.getApplication().invokeLater {
                    eventHistoryTreePanel.update(parsedEvents)
                }
            }
        })
    }

    private fun showError(message: String) {
        ApplicationManager.getApplication().invokeLater {
            statusLabel.text = "<html><font color='red'>$message</font></html>"
            statusLabel.isVisible = true
            setDetailsVisible(false)
            refreshButton.isEnabled = true

            // Disable live updates and replay on error
            stopLongPolling()
            autoRefreshCheckbox.isSelected = false
            autoRefreshCheckbox.isEnabled = false
            replayButton.isEnabled = false
            debugReplayButton.isEnabled = false
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

    /**
     * Start workflow replay for the currently loaded workflow.
     * Uses the already-cached history instead of fetching again.
     */
    private fun startReplay(debug: Boolean = false) {
        val workflowId = currentWorkflowId ?: return

        // Get workflow type and raw history from cache
        val (workflowType, rawHistory) = synchronized(stateLock) {
            val type = cachedEvents.firstOrNull()?.details?.get("workflowType")
            val history = if (cachedRawEvents.isNotEmpty()) {
                History.newBuilder().addAllEvents(cachedRawEvents).build()
            } else null
            Pair(type, history)
        }

        if (workflowType == null) {
            showError("Could not determine workflow type. Please load the workflow first.")
            return
        }

        if (rawHistory == null || rawHistory.eventsCount == 0) {
            showError("No history loaded. Please refresh the workflow first.")
            return
        }

        WorkflowReplayService(project).replayWithCachedHistory(rawHistory, workflowType, workflowId, debug)
    }

    /**
     * Import workflow history from a JSON file and replay it.
     */
    private fun importHistoryFile() {
        WorkflowReplayService(project).replayFromFile()
    }

    fun dispose() {
        stopLongPolling()
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

    private var lastWorkflowId: String = ""
    private var lastWorkflowType: String = ""
    private var lastErrorMessage: String? = null

    init {
        border = JBUI.Borders.empty(5)

        // Layout
        val contentPanel = JBPanel<JBPanel<*>>(java.awt.GridBagLayout())
        val gbc = java.awt.GridBagConstraints().apply {
            anchor = java.awt.GridBagConstraints.WEST
            insets = java.awt.Insets(2, 5, 2, 5)
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
                    lastWorkflowId = workflowId
                    lastWorkflowType = workflowType
                    lastErrorMessage = null
                    ApplicationManager.getApplication().invokeLater { updateDisplay() }
                }

                override fun onReplayFinished(workflowId: String, success: Boolean, errorMessage: String?) {
                    currentStatus = if (success) ReplayState.SUCCESS else ReplayState.FAILED
                    lastWorkflowId = workflowId
                    lastErrorMessage = errorMessage
                    ApplicationManager.getApplication().invokeLater { updateDisplay() }
                }
            }
        )
    }

    private fun navigateToClass(className: String) {
        if (className.isEmpty()) return
        com.intellij.openapi.application.ReadAction.run<RuntimeException> {
            val psiClass = JavaPsiFacade.getInstance(project)
                .findClass(className, GlobalSearchScope.allScope(project))

            if (psiClass == null) {
                return@run
            }

            val navigatable = psiClass.navigationElement as? com.intellij.pom.Navigatable
            if (navigatable != null && navigatable.canNavigate()) {
                navigatable.navigate(true)
                return@run
            }

            // Fallback to OpenFileDescriptor
            val vFile = psiClass.containingFile?.virtualFile
            if (vFile != null) {
                com.intellij.openapi.fileEditor.OpenFileDescriptor(project, vFile).navigate(true)
            }
        }
    }

    private fun updateDisplay() {
        val (statusText, statusColor) = when (currentStatus) {
            ReplayState.READY -> "Ready" to JBColor.GRAY
            ReplayState.REPLAYING -> "Replaying..." to JBColor.BLUE
            ReplayState.SUCCESS -> "Success" to JBColor(0x4CAF50, 0x4CAF50)
            ReplayState.FAILED -> "Failed" to JBColor.RED
        }

        statusLabel.text = "<html><b style='font-size: 1.1em;'>▼ REPLAY STATUS</b></html>"
        statusLabel.foreground = statusColor

        // Update workflow link
        if (lastWorkflowType.isNotEmpty() && currentStatus != ReplayState.READY) {
            val simpleName = lastWorkflowType.substringAfterLast('.')
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
