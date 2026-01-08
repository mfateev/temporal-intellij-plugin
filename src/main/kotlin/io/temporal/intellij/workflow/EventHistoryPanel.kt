package io.temporal.intellij.workflow

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/**
 * Panel displaying workflow event history in a table format.
 */
class EventHistoryPanel : JBPanel<EventHistoryPanel>(BorderLayout()) {

    private val tableModel = EventHistoryTableModel()
    private val table = JBTable(tableModel)
    private val headerLabel = JBLabel("<html><b>▼ EVENT HISTORY (0)</b></html>")
    private val filterComboBox = JComboBox(arrayOf("All", "Workflow", "Activity", "Timer", "Signal", "Child Workflow"))
    private var allEvents: List<WorkflowHistoryEvent> = emptyList()
    private var currentFilter = "All"

    init {
        border = JBUI.Borders.empty(5)

        // Header panel with filter
        val headerPanel = JBPanel<JBPanel<*>>(BorderLayout())
        headerPanel.add(headerLabel, BorderLayout.WEST)

        val filterPanel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.RIGHT, 5, 0))
        filterPanel.add(JBLabel("Filter:"))
        filterComboBox.addActionListener {
            currentFilter = filterComboBox.selectedItem as String
            applyFilter()
        }
        filterPanel.add(filterComboBox)
        headerPanel.add(filterPanel, BorderLayout.EAST)

        add(headerPanel, BorderLayout.NORTH)

        // Configure table
        table.setShowGrid(false)
        table.rowHeight = 24
        table.autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
        table.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION

        // Set column widths
        table.columnModel.getColumn(0).preferredWidth = 40  // #
        table.columnModel.getColumn(0).maxWidth = 60
        table.columnModel.getColumn(1).preferredWidth = 200 // Event Type
        table.columnModel.getColumn(2).preferredWidth = 80  // Time
        table.columnModel.getColumn(2).maxWidth = 100
        table.columnModel.getColumn(3).preferredWidth = 200 // Details

        // Custom renderers
        table.columnModel.getColumn(1).cellRenderer = EventTypeCellRenderer()

        val scrollPane = JBScrollPane(table)
        scrollPane.border = JBUI.Borders.empty()
        add(scrollPane, BorderLayout.CENTER)
    }

    fun update(events: List<WorkflowHistoryEvent>) {
        allEvents = events
        applyFilter()
    }

    private fun applyFilter() {
        val filteredEvents = if (currentFilter == "All") {
            allEvents
        } else {
            val category = when (currentFilter) {
                "Workflow" -> EventCategory.WORKFLOW
                "Activity" -> EventCategory.ACTIVITY
                "Timer" -> EventCategory.TIMER
                "Signal" -> EventCategory.SIGNAL
                "Child Workflow" -> EventCategory.CHILD_WORKFLOW
                else -> null
            }
            if (category != null) {
                allEvents.filter { it.eventCategory == category }
            } else {
                allEvents
            }
        }

        headerLabel.text = "<html><b>▼ EVENT HISTORY (${filteredEvents.size})</b></html>"
        tableModel.updateEvents(filteredEvents)
    }

    fun clear() {
        allEvents = emptyList()
        tableModel.updateEvents(emptyList())
        headerLabel.text = "<html><b>▼ EVENT HISTORY (0)</b></html>"
    }
}

/**
 * Table model for event history.
 */
private class EventHistoryTableModel : AbstractTableModel() {

    private val columns = arrayOf("#", "Event Type", "Time", "Details")
    private var events: List<WorkflowHistoryEvent> = emptyList()
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun updateEvents(newEvents: List<WorkflowHistoryEvent>) {
        events = newEvents
        fireTableDataChanged()
    }

    override fun getRowCount(): Int = events.size
    override fun getColumnCount(): Int = columns.size
    override fun getColumnName(column: Int): String = columns[column]

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val event = events[rowIndex]
        return when (columnIndex) {
            0 -> event.eventId
            1 -> event
            2 -> event.timestamp?.atZone(ZoneId.systemDefault())?.format(timeFormatter) ?: ""
            3 -> formatDetails(event.details)
            else -> ""
        }
    }

    private fun formatDetails(details: Map<String, String>): String {
        if (details.isEmpty()) return ""
        return details.entries.take(2).joinToString(", ") { "${it.key}: ${truncate(it.value, 30)}" }
    }

    private fun truncate(text: String, maxLength: Int): String {
        return if (text.length > maxLength) text.take(maxLength - 3) + "..." else text
    }
}

/**
 * Custom renderer for event type column with color coding.
 */
private class EventTypeCellRenderer : DefaultTableCellRenderer() {

    override fun getTableCellRendererComponent(
        table: JTable,
        value: Any?,
        isSelected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int
    ): Component {
        val component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)

        if (value is WorkflowHistoryEvent) {
            text = formatEventType(value.eventType)

            if (!isSelected) {
                foreground = when (value.eventCategory) {
                    EventCategory.WORKFLOW -> JBColor(0x4CAF50, 0x81C784) // Green
                    EventCategory.ACTIVITY -> JBColor(0x2196F3, 0x64B5F6) // Blue
                    EventCategory.TIMER -> JBColor(0xFF9800, 0xFFB74D) // Orange
                    EventCategory.SIGNAL -> JBColor(0x9C27B0, 0xBA68C8) // Purple
                    EventCategory.CHILD_WORKFLOW -> JBColor(0x00BCD4, 0x4DD0E1) // Cyan
                    EventCategory.UPDATE -> JBColor(0xE91E63, 0xF06292) // Pink
                    EventCategory.WORKFLOW_TASK -> JBColor(0x607D8B, 0x90A4AE) // Gray
                    EventCategory.OTHER -> JBColor.foreground()
                }
            }
        }

        return component
    }

    private fun formatEventType(eventType: String): String {
        // Remove EVENT_TYPE_ prefix and convert to readable format
        return eventType
            .removePrefix("EVENT_TYPE_")
            .split("_")
            .joinToString(" ") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }
    }
}
