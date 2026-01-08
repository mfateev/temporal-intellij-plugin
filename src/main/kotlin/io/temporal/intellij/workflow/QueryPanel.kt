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
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.*

/**
 * Panel for executing workflow queries and displaying results.
 */
class QueryPanel(private val project: Project) : JBPanel<QueryPanel>(BorderLayout()) {

    private val headerLabel = JBLabel("<html><b>▼ QUERY WORKFLOW</b></html>")
    private val queryTypeField = JBTextField(20)
    private val queryArgsField = JBTextField(30)
    private val executeButton = JButton("Execute")
    private val stackTraceButton = JButton("Stack Trace")
    private val resultArea = JBTextArea()
    private val statusLabel = JBLabel()

    private var workflowService: WorkflowService? = null
    private var currentWorkflowId: String? = null
    private var currentRunId: String? = null

    init {
        border = JBUI.Borders.empty(5)

        add(headerLabel, BorderLayout.NORTH)

        // Query input panel
        val inputPanel = JBPanel<JBPanel<*>>()
        inputPanel.layout = BoxLayout(inputPanel, BoxLayout.Y_AXIS)
        inputPanel.border = JBUI.Borders.empty(5, 0)

        // Row 1: Query type
        val row1 = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 5, 2))
        row1.add(JBLabel("Query Type:"))
        queryTypeField.toolTipText = "Name of the query to execute (e.g., getStatus, getOrderDetails)"
        row1.add(queryTypeField)
        inputPanel.add(row1)

        // Row 2: Query args
        val row2 = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 5, 2))
        row2.add(JBLabel("Arguments (JSON):"))
        queryArgsField.toolTipText = "Optional JSON arguments for the query"
        queryArgsField.emptyText.text = "optional"
        row2.add(queryArgsField)
        inputPanel.add(row2)

        // Row 3: Buttons
        val row3 = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 5, 2))
        executeButton.toolTipText = "Execute the query"
        executeButton.addActionListener { executeQuery() }
        executeButton.isEnabled = false
        row3.add(executeButton)

        stackTraceButton.toolTipText = "Get workflow stack trace (built-in __stack_trace query)"
        stackTraceButton.addActionListener { executeStackTrace() }
        stackTraceButton.isEnabled = false
        row3.add(stackTraceButton)

        statusLabel.border = JBUI.Borders.emptyLeft(10)
        row3.add(statusLabel)
        inputPanel.add(row3)

        add(inputPanel, BorderLayout.CENTER)

        // Result area
        resultArea.isEditable = false
        resultArea.font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        resultArea.lineWrap = true
        resultArea.wrapStyleWord = true
        resultArea.border = JBUI.Borders.empty(5)

        val scrollPane = JBScrollPane(resultArea)
        scrollPane.preferredSize = java.awt.Dimension(400, 150)
        scrollPane.border = BorderFactory.createTitledBorder("Result")
        add(scrollPane, BorderLayout.SOUTH)

        // Enter key triggers execute
        queryTypeField.addActionListener { executeQuery() }
    }

    fun setWorkflowContext(workflowId: String?, runId: String?, service: WorkflowService?) {
        currentWorkflowId = workflowId
        currentRunId = runId
        workflowService = service

        val hasContext = workflowId != null && service != null
        executeButton.isEnabled = hasContext
        stackTraceButton.isEnabled = hasContext

        if (!hasContext) {
            resultArea.text = ""
            statusLabel.text = ""
        }
    }

    private fun executeQuery() {
        val queryType = queryTypeField.text.trim()
        if (queryType.isEmpty()) {
            showError("Please enter a query type")
            return
        }

        val workflowId = currentWorkflowId ?: return
        val service = workflowService ?: return
        val queryArgs = queryArgsField.text.trim().ifEmpty { null }

        executeButton.isEnabled = false
        stackTraceButton.isEnabled = false
        statusLabel.text = "Executing..."
        statusLabel.foreground = JBColor.foreground()
        resultArea.text = ""

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Executing Query", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Executing query: $queryType"
                indicator.isIndeterminate = true

                val result = service.queryWorkflow(workflowId, currentRunId, queryType, queryArgs)

                ApplicationManager.getApplication().invokeLater {
                    executeButton.isEnabled = true
                    stackTraceButton.isEnabled = true

                    if (result.isSuccess) {
                        statusLabel.text = "Success"
                        statusLabel.foreground = JBColor(0x4CAF50, 0x81C784)
                        resultArea.text = formatResult(result.getOrNull() ?: "")
                        resultArea.caretPosition = 0
                    } else {
                        showError(result.exceptionOrNull()?.message ?: "Query failed")
                    }
                }
            }
        })
    }

    private fun executeStackTrace() {
        val workflowId = currentWorkflowId ?: return
        val service = workflowService ?: return

        executeButton.isEnabled = false
        stackTraceButton.isEnabled = false
        statusLabel.text = "Getting stack trace..."
        statusLabel.foreground = JBColor.foreground()
        resultArea.text = ""

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Getting Stack Trace", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Getting workflow stack trace"
                indicator.isIndeterminate = true

                val result = service.getStackTrace(workflowId, currentRunId)

                ApplicationManager.getApplication().invokeLater {
                    executeButton.isEnabled = true
                    stackTraceButton.isEnabled = true

                    if (result.isSuccess) {
                        statusLabel.text = "Success"
                        statusLabel.foreground = JBColor(0x4CAF50, 0x81C784)
                        resultArea.text = result.getOrNull() ?: "<empty>"
                        resultArea.caretPosition = 0
                    } else {
                        showError(result.exceptionOrNull()?.message ?: "Failed to get stack trace")
                    }
                }
            }
        })
    }

    private fun showError(message: String) {
        statusLabel.text = "Error"
        statusLabel.foreground = JBColor(0xf44336, 0xef5350)
        resultArea.text = message
        resultArea.caretPosition = 0
    }

    private fun formatResult(result: String): String {
        // Try to pretty-print JSON
        return try {
            if (result.trim().startsWith("{") || result.trim().startsWith("[")) {
                prettyPrintJson(result)
            } else {
                result
            }
        } catch (e: Exception) {
            result
        }
    }

    private fun prettyPrintJson(json: String): String {
        // Simple JSON pretty-printing without external dependencies
        val sb = StringBuilder()
        var indent = 0
        var inString = false
        var prevChar = ' '

        for (char in json) {
            when {
                char == '"' && prevChar != '\\' -> {
                    inString = !inString
                    sb.append(char)
                }
                !inString && (char == '{' || char == '[') -> {
                    sb.append(char)
                    sb.append('\n')
                    indent += 2
                    sb.append(" ".repeat(indent))
                }
                !inString && (char == '}' || char == ']') -> {
                    sb.append('\n')
                    indent -= 2
                    sb.append(" ".repeat(indent))
                    sb.append(char)
                }
                !inString && char == ',' -> {
                    sb.append(char)
                    sb.append('\n')
                    sb.append(" ".repeat(indent))
                }
                !inString && char == ':' -> {
                    sb.append(": ")
                }
                !inString && char.isWhitespace() -> {
                    // Skip whitespace outside strings
                }
                else -> {
                    sb.append(char)
                }
            }
            prevChar = char
        }

        return sb.toString()
    }

    fun clear() {
        resultArea.text = ""
        statusLabel.text = ""
        queryTypeField.text = ""
        queryArgsField.text = ""
    }
}
