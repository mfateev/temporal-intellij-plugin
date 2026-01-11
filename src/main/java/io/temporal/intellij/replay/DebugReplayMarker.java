package io.temporal.intellij.replay;

import java.io.PrintWriter;
import java.net.Socket;

/**
 * Marker class for replay progress communication.
 *
 * When the status server port is set (via system property), these methods
 * send JSON messages to the IntelliJ plugin via socket.
 *
 * Protocol: Each message is a JSON line with format:
 * {"type": "HANDSHAKE|STARTED|FINISHED|FAILED|EVENT", "token": "...", "workflowId": "...", "workflowType": "...", "eventId": N, "error": "..."}
 *
 * SECURITY: The first message sent is always a HANDSHAKE with the token from system property.
 */
public final class DebugReplayMarker {

    private static final String PORT_PROPERTY = "temporal.replay.status.port";
    private static final String TOKEN_PROPERTY = "temporal.replay.status.token";
    private static PrintWriter writer;
    private static Socket socket;

    private DebugReplayMarker() {}

    /**
     * Initialize connection to the status server.
     * Called automatically on first status message.
     * Sends HANDSHAKE as the first message for authentication.
     */
    private static synchronized void ensureConnected() {
        if (writer != null) {
            return;
        }

        String portStr = System.getProperty(PORT_PROPERTY);
        if (portStr == null || portStr.isEmpty()) {
            return;
        }

        String token = System.getProperty(TOKEN_PROPERTY);
        if (token == null || token.isEmpty()) {
            System.err.println("No replay status token configured");
            return;
        }

        try {
            int port = Integer.parseInt(portStr);
            socket = new Socket("localhost", port);
            writer = new PrintWriter(socket.getOutputStream(), true);

            // SECURITY: Send handshake with token as first message
            writer.println(String.format(
                "{\"type\":\"HANDSHAKE\",\"token\":\"%s\"}",
                escape(token)
            ));
        } catch (Exception e) {
            System.err.println("Failed to connect to replay status server: " + e.getMessage());
        }
    }

    private static void sendMessage(String json) {
        ensureConnected();
        if (writer != null) {
            writer.println(json);
        }
    }

    /**
     * Called when replay starts.
     *
     * @param workflowId The workflow ID being replayed
     * @param workflowType The workflow type/class name
     */
    public static void onReplayStarted(String workflowId, String workflowType) {
        sendMessage(String.format(
            "{\"type\":\"STARTED\",\"workflowId\":\"%s\",\"workflowType\":\"%s\"}",
            escape(workflowId), escape(workflowType)
        ));
    }

    /**
     * Called when replay finishes successfully.
     *
     * @param workflowId The workflow ID that was replayed
     */
    public static void onReplayFinished(String workflowId) {
        sendMessage(String.format(
            "{\"type\":\"FINISHED\",\"workflowId\":\"%s\"}",
            escape(workflowId)
        ));
        closeConnection();
    }

    /**
     * Called when replay fails.
     *
     * @param workflowId The workflow ID that failed
     * @param errorMessage The error message
     */
    public static void onReplayFailed(String workflowId, String errorMessage) {
        sendMessage(String.format(
            "{\"type\":\"FAILED\",\"workflowId\":\"%s\",\"error\":\"%s\"}",
            escape(workflowId), escape(errorMessage)
        ));
        closeConnection();
    }

    /**
     * Called when processing an event (for future per-event tracking).
     *
     * @param workflowId The workflow ID
     * @param eventId The event ID being processed
     */
    public static void onEventProcessing(String workflowId, long eventId) {
        sendMessage(String.format(
            "{\"type\":\"EVENT\",\"workflowId\":\"%s\",\"eventId\":%d}",
            escape(workflowId), eventId
        ));
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r");
    }

    private static synchronized void closeConnection() {
        try {
            if (writer != null) {
                writer.close();
                writer = null;
            }
            if (socket != null) {
                socket.close();
                socket = null;
            }
        } catch (Exception e) {
            // Ignore
        }
    }
}
