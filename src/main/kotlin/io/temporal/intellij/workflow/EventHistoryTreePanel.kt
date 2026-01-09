package io.temporal.intellij.workflow

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.*
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel

/**
 * Panel displaying workflow event history in an expandable tree format.
 */
class EventHistoryTreePanel : JBPanel<EventHistoryTreePanel>(BorderLayout()) {

    private val rootNode = DefaultMutableTreeNode("Events")
    private val treeModel = DefaultTreeModel(rootNode)
    private val tree = Tree(treeModel)
    private val headerLabel = JBLabel("<html><b>Event History (0 events)</b></html>")
    private val filterComboBox = JComboBox(arrayOf("All", "Workflow", "Activity", "Timer", "Signal", "Child Workflow"))
    private var allEvents: List<WorkflowHistoryEvent> = emptyList()
    private var currentFilter = "All"

    init {
        border = JBUI.Borders.empty(5)

        // Header panel with filter and expand/collapse buttons
        val headerPanel = JBPanel<JBPanel<*>>(BorderLayout())
        headerPanel.add(headerLabel, BorderLayout.WEST)

        val controlsPanel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.RIGHT, 5, 0))

        // Expand/Collapse buttons
        val expandAllButton = JButton("Expand All")
        expandAllButton.addActionListener { expandAll() }
        controlsPanel.add(expandAllButton)

        val collapseAllButton = JButton("Collapse All")
        collapseAllButton.addActionListener { collapseAll() }
        controlsPanel.add(collapseAllButton)

        // Separator
        controlsPanel.add(JBLabel("  |  "))

        // Filter dropdown
        controlsPanel.add(JBLabel("Filter:"))
        filterComboBox.addActionListener {
            currentFilter = filterComboBox.selectedItem as String
            applyFilter()
        }
        controlsPanel.add(filterComboBox)
        headerPanel.add(controlsPanel, BorderLayout.EAST)
        headerPanel.border = JBUI.Borders.emptyBottom(5)

        add(headerPanel, BorderLayout.NORTH)

        // Configure tree
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = EventTreeCellRenderer()

        val scrollPane = JBScrollPane(tree)
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

        headerLabel.text = "<html><b>Event History (${filteredEvents.size} events)</b></html>"
        rebuildTree(filteredEvents)
    }

    private fun rebuildTree(events: List<WorkflowHistoryEvent>) {
        rootNode.removeAllChildren()

        for (event in events) {
            val eventNode = DefaultMutableTreeNode(event)

            // Add detail nodes for expandable content
            for ((key, value) in event.details) {
                val detailNode = DefaultMutableTreeNode(EventDetail(key, value))
                eventNode.add(detailNode)
            }

            rootNode.add(eventNode)
        }

        treeModel.reload()

        // Optionally expand first few nodes
        if (events.size <= 20) {
            for (i in 0 until minOf(5, tree.rowCount)) {
                tree.expandRow(i)
            }
        }
    }

    fun clear() {
        allEvents = emptyList()
        rootNode.removeAllChildren()
        treeModel.reload()
        headerLabel.text = "<html><b>Event History (0 events)</b></html>"
    }

    fun expandAll() {
        var row = 0
        while (row < tree.rowCount) {
            tree.expandRow(row)
            row++
        }
    }

    fun collapseAll() {
        var row = tree.rowCount - 1
        while (row >= 0) {
            tree.collapseRow(row)
            row--
        }
    }
}

/**
 * Data class for event details shown as child nodes.
 */
data class EventDetail(val key: String, val value: String)

/**
 * Custom tree cell renderer for event nodes.
 */
private class EventTreeCellRenderer : DefaultTreeCellRenderer() {

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    override fun getTreeCellRendererComponent(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean
    ): Component {
        super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus)

        val node = value as? DefaultMutableTreeNode ?: return this
        val userObject = node.userObject

        when (userObject) {
            is WorkflowHistoryEvent -> {
                val timeStr = userObject.timestamp?.atZone(ZoneId.systemDefault())?.format(timeFormatter) ?: ""
                val eventName = formatEventType(userObject.eventType)
                text = "#${userObject.eventId} [$timeStr] $eventName"

                if (!selected) {
                    foreground = getColorForCategory(userObject.eventCategory)
                }

                // Set icon based on event category
                icon = null // Could add custom icons here
            }
            is EventDetail -> {
                text = "${userObject.key}: ${truncate(userObject.value, 100)}"
                if (!selected) {
                    foreground = JBColor.GRAY
                }
                icon = null
            }
        }

        return this
    }

    private fun formatEventType(eventType: String): String {
        return eventType
            .removePrefix("EVENT_TYPE_")
            .split("_")
            .joinToString(" ") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }
    }

    private fun getColorForCategory(category: EventCategory): JBColor {
        return when (category) {
            EventCategory.WORKFLOW -> JBColor(0x4CAF50, 0x81C784) // Green
            EventCategory.ACTIVITY -> JBColor(0x2196F3, 0x64B5F6) // Blue
            EventCategory.TIMER -> JBColor(0xFF9800, 0xFFB74D) // Orange
            EventCategory.SIGNAL -> JBColor(0x9C27B0, 0xBA68C8) // Purple
            EventCategory.CHILD_WORKFLOW -> JBColor(0x00BCD4, 0x4DD0E1) // Cyan
            EventCategory.UPDATE -> JBColor(0xE91E63, 0xF06292) // Pink
            EventCategory.WORKFLOW_TASK -> JBColor(0x607D8B, 0x90A4AE) // Gray
            EventCategory.OTHER -> JBColor(JBColor.foreground().rgb, JBColor.foreground().rgb)
        }
    }

    private fun truncate(text: String, maxLength: Int): String {
        return if (text.length > maxLength) text.take(maxLength - 3) + "..." else text
    }
}
