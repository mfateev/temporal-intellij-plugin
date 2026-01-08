package io.temporal.intellij.workflow

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/**
 * Dialog for choosing a workflow from a list of recent executions.
 */
class WorkflowChooserDialog(
    project: Project,
    private val workflows: List<WorkflowListItem>
) : DialogWrapper(project, true) {

    private val tableModel = WorkflowTableModel(workflows)
    private val table = JBTable(tableModel)
    var selectedWorkflow: WorkflowListItem? = null
        private set

    init {
        title = "Select Workflow"
        init()
        setupTable()
    }

    private fun setupTable() {
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        table.rowHeight = JBUI.scale(24)
        table.tableHeader.reorderingAllowed = false

        // Set column widths
        table.columnModel.getColumn(0).preferredWidth = 250  // Workflow ID
        table.columnModel.getColumn(1).preferredWidth = 150  // Type
        table.columnModel.getColumn(2).preferredWidth = 100  // Status
        table.columnModel.getColumn(3).preferredWidth = 150  // Started

        // Custom renderer for status column with colors
        table.columnModel.getColumn(2).cellRenderer = StatusCellRenderer()

        // Double-click to select
        table.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount == 2 && table.selectedRow >= 0) {
                    doOKAction()
                }
            }
        })

        // Pre-select first row if available
        if (workflows.isNotEmpty()) {
            table.setRowSelectionInterval(0, 0)
        }
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(10)

        if (workflows.isEmpty()) {
            val emptyLabel = JBLabel("No recent workflows found")
            emptyLabel.horizontalAlignment = SwingConstants.CENTER
            panel.add(emptyLabel, BorderLayout.CENTER)
        } else {
            val scrollPane = JBScrollPane(table)
            scrollPane.preferredSize = Dimension(700, 300)
            panel.add(scrollPane, BorderLayout.CENTER)

            val infoLabel = JBLabel("Double-click or select and click OK to choose a workflow")
            infoLabel.border = JBUI.Borders.emptyTop(5)
            panel.add(infoLabel, BorderLayout.SOUTH)
        }

        return panel
    }

    override fun doOKAction() {
        val selectedRow = table.selectedRow
        if (selectedRow >= 0) {
            selectedWorkflow = workflows[selectedRow]
        }
        super.doOKAction()
    }

    override fun getPreferredFocusedComponent(): JComponent = table
}

/**
 * Table model for workflow list.
 */
private class WorkflowTableModel(private val workflows: List<WorkflowListItem>) : AbstractTableModel() {

    private val columns = arrayOf("Workflow ID", "Type", "Status", "Started")
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    override fun getRowCount(): Int = workflows.size

    override fun getColumnCount(): Int = columns.size

    override fun getColumnName(column: Int): String = columns[column]

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val workflow = workflows[rowIndex]
        return when (columnIndex) {
            0 -> workflow.workflowId
            1 -> workflow.workflowType
            2 -> workflow.status
            3 -> workflow.startTime?.atZone(ZoneId.systemDefault())
                ?.format(dateFormatter) ?: "N/A"
            else -> ""
        }
    }

    override fun getColumnClass(columnIndex: Int): Class<*> {
        return when (columnIndex) {
            2 -> WorkflowStatus::class.java
            else -> String::class.java
        }
    }
}

/**
 * Custom cell renderer for workflow status with colors.
 */
private class StatusCellRenderer : DefaultTableCellRenderer() {
    override fun getTableCellRendererComponent(
        table: JTable,
        value: Any?,
        isSelected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int
    ): java.awt.Component {
        val component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)

        if (value is WorkflowStatus && !isSelected) {
            foreground = when (value) {
                WorkflowStatus.RUNNING -> java.awt.Color(76, 175, 80)      // Green
                WorkflowStatus.COMPLETED -> java.awt.Color(33, 150, 243)  // Blue
                WorkflowStatus.FAILED -> java.awt.Color(244, 67, 54)       // Red
                WorkflowStatus.CANCELED -> java.awt.Color(255, 152, 0)     // Orange
                WorkflowStatus.TERMINATED -> java.awt.Color(156, 39, 176)  // Purple
                WorkflowStatus.TIMED_OUT -> java.awt.Color(255, 87, 34)    // Deep Orange
                else -> java.awt.Color(117, 117, 117)                       // Gray
            }
        }

        text = value?.toString() ?: ""
        return component
    }
}
