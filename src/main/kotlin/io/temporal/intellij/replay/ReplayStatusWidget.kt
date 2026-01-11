package io.temporal.intellij.replay

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.util.Consumer
import java.awt.event.MouseEvent

private val LOG = Logger.getInstance(ReplayStatusWidget::class.java)

/**
 * Status bar widget that displays replay progress.
 * Subscribes to ReplayProgressListener events via MessageBus.
 */
class ReplayStatusWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.TextPresentation {

    companion object {
        const val ID = "TemporalReplayStatus"
    }

    private var statusBar: StatusBar? = null
    private var currentStatus: String = "Temporal: Ready"

    override fun ID(): String = ID

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar

        // Subscribe to replay progress events
        project.messageBus.connect(this).subscribe(
            ReplayProgressListener.TOPIC,
            object : ReplayProgressListener {
                override fun onReplayStarted(workflowId: String, workflowType: String) {
                    updateStatus("▶ Replaying: $workflowId ($workflowType)")
                }

                override fun onReplayFinished(workflowId: String, success: Boolean, errorMessage: String?) {
                    updateStatus(
                        if (success) "✓ Replay complete: $workflowId"
                        else "✗ Replay failed: ${errorMessage ?: "Unknown error"}"
                    )
                }
            }
        )
    }

    private fun updateStatus(status: String) {
        currentStatus = status
        statusBar?.updateWidget(ID)
    }

    override fun dispose() {
        statusBar = null
    }

    // TextPresentation implementation
    override fun getText(): String = currentStatus

    override fun getAlignment(): Float = 0f

    override fun getTooltipText(): String = "Temporal Workflow Replay Status"

    override fun getClickConsumer(): Consumer<MouseEvent>? = null

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this
}

/**
 * Factory to create the status widget for each project.
 */
class ReplayStatusWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = ReplayStatusWidget.ID

    override fun getDisplayName(): String = "Temporal Replay Status"

    override fun isAvailable(project: Project): Boolean = true

    override fun createWidget(project: Project): StatusBarWidget = ReplayStatusWidget(project)

    override fun disposeWidget(widget: StatusBarWidget) {
        Disposer.dispose(widget)
    }

    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}
