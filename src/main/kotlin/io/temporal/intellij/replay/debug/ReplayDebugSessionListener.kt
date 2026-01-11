package io.temporal.intellij.replay.debug

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerManagerListener
import com.intellij.xdebugger.frame.XStackFrame
import io.temporal.intellij.replay.ReplayProgressListener

/**
 * Listens for debug sessions and tracks replay progress by observing
 * when execution pauses on DebugReplayMarker methods.
 *
 * This uses XDebugSessionListener to detect when execution pauses,
 * then checks if we're in a DebugReplayMarker method and extracts arguments.
 */
class ReplayDebugSessionListener(private val project: Project) : XDebuggerManagerListener, Disposable {

    companion object {
        private const val MARKER_CLASS = "io.temporal.intellij.replay.DebugReplayMarker"
        private const val METHOD_ON_STARTED = "onReplayStarted"
        private const val METHOD_ON_FINISHED = "onReplayFinished"
        private const val METHOD_ON_FAILED = "onReplayFailed"
    }

    private val publisher: ReplayProgressListener
        get() = project.messageBus.syncPublisher(ReplayProgressListener.TOPIC)

    init {
        // Subscribe to debugger manager events
        project.messageBus.connect(this).subscribe(
            XDebuggerManager.TOPIC,
            this
        )
    }

    override fun processStarted(debugProcess: XDebugProcess) {
        val session = debugProcess.session

        // Add session listener to track when debugger pauses
        session.addSessionListener(object : XDebugSessionListener {
            override fun sessionPaused() {
                checkForReplayMarker(session)
            }

            override fun stackFrameChanged() {
                // Could also check here if needed
            }
        }, this)
    }

    private fun checkForReplayMarker(session: XDebugSession) {
        val frame = session.currentStackFrame ?: return

        // Get location info from frame
        // The frame's evaluator can be used to check method/class info
        frame.sourcePosition?.let { position ->
            // For now, publish a simple event when any pause happens during replay
            // A more sophisticated implementation would check the actual method
            ApplicationManager.getApplication().invokeLater {
                // This is a simplified check - real implementation would
                // inspect the Java stack frame for method name
                checkJavaFrame(session, frame)
            }
        }
    }

    private fun checkJavaFrame(session: XDebugSession, frame: XStackFrame) {
        // Try to get the Java-specific frame info
        try {
            // Cast to JavaStackFrame to get method details
            val javaFrameClass = Class.forName("com.intellij.debugger.engine.JavaStackFrame")
            if (javaFrameClass.isInstance(frame)) {
                val getStackFrameProxy = javaFrameClass.getMethod("getStackFrameProxy")
                val proxy = getStackFrameProxy.invoke(frame)

                val locationMethod = proxy.javaClass.getMethod("location")
                val location = locationMethod.invoke(proxy)

                val methodMethod = location.javaClass.getMethod("method")
                val method = methodMethod.invoke(location)

                val nameMethod = method.javaClass.getMethod("name")
                val methodName = nameMethod.invoke(method) as String

                val declaringTypeMethod = method.javaClass.getMethod("declaringType")
                val declaringType = declaringTypeMethod.invoke(method)
                val typeNameMethod = declaringType.javaClass.getMethod("name")
                val typeName = typeNameMethod.invoke(declaringType) as String

                // Check if this is a replay marker method
                if (typeName == MARKER_CLASS) {
                    handleMarkerMethod(session, methodName, proxy)
                }
            }
        } catch (e: Exception) {
            // Reflection failed - not a Java frame or API changed
        }
    }

    private fun handleMarkerMethod(session: XDebugSession, methodName: String, stackProxy: Any) {
        when (methodName) {
            METHOD_ON_STARTED -> {
                // Extract workflowId and workflowType arguments
                try {
                    val getArgumentValues = stackProxy.javaClass.getMethod("getArgumentValues")
                    val args = getArgumentValues.invoke(stackProxy) as List<*>
                    if (args.size >= 2) {
                        val workflowId = extractStringValue(args[0])
                        val workflowType = extractStringValue(args[1])
                        publisher.onReplayStarted(workflowId, workflowType)
                    } else {
                        publisher.onReplayStarted("workflow", "Replay started")
                    }
                } catch (e: Exception) {
                    publisher.onReplayStarted("workflow", "Replay started")
                }
            }
            METHOD_ON_FINISHED -> {
                publisher.onReplayFinished("workflow", true, null)
            }
            METHOD_ON_FAILED -> {
                // Extract error message
                try {
                    val getArgumentValues = stackProxy.javaClass.getMethod("getArgumentValues")
                    val args = getArgumentValues.invoke(stackProxy) as List<*>
                    val errorMessage = if (args.size >= 2) extractStringValue(args[1]) else "Unknown error"
                    publisher.onReplayFinished("workflow", false, errorMessage)
                } catch (e: Exception) {
                    publisher.onReplayFinished("workflow", false, "Replay failed")
                }
            }
        }
    }

    private fun extractStringValue(value: Any?): String {
        return try {
            val valueMethod = value?.javaClass?.getMethod("value")
            valueMethod?.invoke(value) as? String ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    override fun dispose() {
        // Cleanup handled by Disposer
    }
}
