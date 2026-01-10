package io.temporal.intellij.replay

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
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
     * Custom renderer for workflow implementation list items.
     */
    private inner class WorkflowImplementationRenderer : javax.swing.DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: javax.swing.JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): java.awt.Component {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)

            if (value is WorkflowClassFinder.WorkflowImplementation) {
                text = "<html><b>${value.psiClass.name}</b><br/>" +
                       "<font color='gray' size='-1'>${value.qualifiedName}</font></html>"
                border = JBUI.Borders.empty(4, 8)
            }

            return this
        }
    }
}
