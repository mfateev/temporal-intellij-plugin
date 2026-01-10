package io.temporal.intellij.replay

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/**
 * Dialog for selecting a workflow implementation when multiple are found.
 */
class WorkflowClassChooserDialog(
    project: Project,
    private val implementations: List<WorkflowClassFinder.WorkflowImplementation>,
    workflowTypeName: String
) : DialogWrapper(project) {

    private val listModel = DefaultListModel<WorkflowClassFinder.WorkflowImplementation>()
    private val implementationList = JBList(listModel)

    var selectedImplementation: WorkflowClassFinder.WorkflowImplementation? = null
        private set

    init {
        title = "Select Workflow Implementation"
        setOKButtonText("Select")
        init()

        // Populate list
        implementations.forEach { listModel.addElement(it) }

        // Select first item by default
        if (implementations.isNotEmpty()) {
            implementationList.selectedIndex = 0
        }
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.preferredSize = Dimension(500, 300)

        // Header label
        val headerLabel = JBLabel(
            "<html>Multiple implementations found for workflow type.<br/>" +
            "Select the implementation to use for replay:</html>"
        )
        headerLabel.border = JBUI.Borders.emptyBottom(10)
        panel.add(headerLabel, BorderLayout.NORTH)

        // Configure list
        implementationList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        implementationList.cellRenderer = WorkflowImplementationRenderer()

        // Double-click to select and close
        implementationList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    doOKAction()
                }
            }
        })

        val scrollPane = JBScrollPane(implementationList)
        panel.add(scrollPane, BorderLayout.CENTER)

        return panel
    }

    override fun doOKAction() {
        selectedImplementation = implementationList.selectedValue
        super.doOKAction()
    }

    override fun getPreferredFocusedComponent(): JComponent = implementationList

    /**
     * Computes the longest common package prefix among all implementations.
     */
    private fun computeCommonPackagePrefix(): String {
        if (implementations.size <= 1) return ""

        val packages = implementations.map { impl ->
            val qualifiedName = impl.qualifiedName
            val className = impl.psiClass.name ?: ""
            if (qualifiedName.endsWith(className)) {
                qualifiedName.dropLast(className.length).trimEnd('.')
            } else {
                qualifiedName.substringBeforeLast('.', "")
            }
        }

        if (packages.any { it.isEmpty() }) return ""

        val firstPackage = packages.first()
        val parts = firstPackage.split('.')

        var commonPrefixLength = 0
        for (i in parts.indices) {
            val prefix = parts.subList(0, i + 1).joinToString(".")
            if (packages.all { it == prefix || it.startsWith("$prefix.") }) {
                commonPrefixLength = prefix.length
            } else {
                break
            }
        }

        return if (commonPrefixLength > 0) firstPackage.substring(0, commonPrefixLength) else ""
    }

    /**
     * Custom renderer for workflow implementation list items.
     * Uses ColoredListCellRenderer for proper theme-aware styling.
     */
    private inner class WorkflowImplementationRenderer : ColoredListCellRenderer<WorkflowClassFinder.WorkflowImplementation>() {
        private val commonPrefix = computeCommonPackagePrefix()

        override fun customizeCellRenderer(
            list: JList<out WorkflowClassFinder.WorkflowImplementation>,
            value: WorkflowClassFinder.WorkflowImplementation?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean
        ) {
            if (value == null) return

            border = JBUI.Borders.empty(4, 8)

            val className = value.psiClass.name ?: "Unknown"
            val qualifiedName = value.qualifiedName
            val packageName = if (qualifiedName.endsWith(className)) {
                qualifiedName.dropLast(className.length).trimEnd('.')
            } else {
                qualifiedName.substringBeforeLast('.', "")
            }

            // Class name in bold
            append(className, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)

            // Package name with differentiating part highlighted
            if (packageName.isNotEmpty()) {
                if (commonPrefix.isNotEmpty() && packageName.startsWith(commonPrefix)) {
                    // Show common prefix in gray
                    append(commonPrefix, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    // Show the differentiating part in a distinct color
                    val uniquePart = packageName.substring(commonPrefix.length)
                    if (uniquePart.isNotEmpty()) {
                        append(uniquePart, SimpleTextAttributes(
                            SimpleTextAttributes.STYLE_BOLD,
                            null
                        ))
                    }
                } else {
                    // No common prefix or package doesn't match, show full package
                    append(packageName, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            }
        }
    }
}
