package io.temporal.intellij.replay

import com.intellij.util.messages.Topic

/**
 * Topic for replay progress events.
 * Allows communication between replay executor and UI components.
 */
interface ReplayProgressListener {
    fun onReplayStarted(workflowId: String, workflowType: String)
    fun onReplayFinished(workflowId: String, success: Boolean, errorMessage: String? = null)

    companion object {
        @JvmField
        val TOPIC: Topic<ReplayProgressListener> = Topic.create(
            "temporal.replay.progress",
            ReplayProgressListener::class.java
        )
    }
}
