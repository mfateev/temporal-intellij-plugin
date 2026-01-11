package io.temporal.intellij.replay

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

private val LOG = Logger.getInstance(ReplayStatusServer::class.java)

/**
 * Server that receives replay status updates from the replay process.
 *
 * Protocol: Each line is a JSON message with format:
 * {"type": "STARTED|FINISHED|FAILED|EVENT", "workflowId": "...", "workflowType": "...", "eventId": N, "error": "..."}
 */
class ReplayStatusServer(private val project: Project) : Disposable {

    private var serverSocket: ServerSocket? = null
    private var listenerThread: Thread? = null
    private val running = AtomicBoolean(false)
    private val clientConnected = AtomicBoolean(false)

    val port: Int
        get() = serverSocket?.localPort ?: -1

    /**
     * Start the server on a random available port.
     */
    fun start(): Int {
        if (running.get()) {
            return port
        }

        // SECURITY: Bind to loopback only - prevents remote connections
        serverSocket = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).apply {
            soTimeout = 1000 // 1 second timeout for accept()
        }
        running.set(true)

        listenerThread = Thread({
            while (running.get()) {
                try {
                    val client = serverSocket?.accept()
                    if (client != null) {
                        // SECURITY: Accept only one client connection
                        if (clientConnected.compareAndSet(false, true)) {
                            handleClient(client)
                        } else {
                            LOG.warn("Rejecting additional connection from ${client.inetAddress}")
                            client.close()
                        }
                    }
                } catch (e: SocketTimeoutException) {
                    // Expected - just retry accept()
                } catch (e: Exception) {
                    if (running.get()) {
                        LOG.warn("Error accepting connection", e)
                    }
                }
            }
        }, "ReplayStatusServer")
        listenerThread?.isDaemon = true
        listenerThread?.start()

        LOG.info("ReplayStatusServer started on port $port")
        return port
    }

    private fun handleClient(client: Socket) {
        Thread({
            try {
                client.use { socket ->
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        line?.let { processMessage(it) }
                    }
                }
            } catch (e: Exception) {
                LOG.warn("Error handling client", e)
            }
        }, "ReplayStatusClient").start()
    }

    private fun processMessage(json: String) {
        try {
            // Simple JSON parsing without external dependencies
            val type = extractJsonString(json, "type")
            val workflowId = extractJsonString(json, "workflowId") ?: ""
            val workflowType = extractJsonString(json, "workflowType") ?: ""
            val eventId = extractJsonLong(json, "eventId")
            val error = extractJsonString(json, "error")

            val publisher = project.messageBus.syncPublisher(ReplayProgressListener.TOPIC)

            ApplicationManager.getApplication().invokeLater {
                when (type) {
                    "STARTED" -> publisher.onReplayStarted(workflowId, workflowType)
                    "FINISHED" -> publisher.onReplayFinished(workflowId, true, null)
                    "FAILED" -> publisher.onReplayFinished(workflowId, false, error)
                    "EVENT" -> {
                        // Future: publisher.onEventProcessed(workflowId, eventId)
                        // For now, just log
                        LOG.debug("Replay processing event $eventId")
                    }
                }
            }
        } catch (e: Exception) {
            LOG.warn("Failed to process message: $json", e)
        }
    }

    private fun extractJsonString(json: String, key: String): String? {
        val pattern = """"$key"\s*:\s*"([^"]*?)"""".toRegex()
        return pattern.find(json)?.groupValues?.get(1)
    }

    private fun extractJsonLong(json: String, key: String): Long? {
        val pattern = """"$key"\s*:\s*(\d+)""".toRegex()
        return pattern.find(json)?.groupValues?.get(1)?.toLongOrNull()
    }

    fun stop() {
        running.set(false)
        clientConnected.set(false)
        serverSocket?.close()
        serverSocket = null
        listenerThread?.interrupt()
        listenerThread = null
        LOG.info("ReplayStatusServer stopped")
    }

    override fun dispose() {
        stop()
    }
}
