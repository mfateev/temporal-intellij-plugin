package io.temporal.intellij.replay

import com.google.protobuf.util.JsonFormat
import io.temporal.api.history.v1.History
import java.io.File

/**
 * Utility class for exporting and importing workflow history in JSON format.
 * The JSON format is compatible with Temporal CLI's `workflow show -o json` output.
 */
class HistoryExporter {

    private val jsonPrinter = JsonFormat.printer()
        .includingDefaultValueFields()
        .preservingProtoFieldNames()

    /**
     * Export a History proto object to JSON string.
     */
    fun exportToJson(history: History): String {
        return jsonPrinter.print(history)
    }

    /**
     * Export workflow history to a temporary JSON file.
     * The file is marked for deletion on JVM exit.
     *
     * @param history The History proto object to export
     * @param prefix Optional prefix for the temp file name
     * @return The created temporary file containing the JSON history
     */
    fun exportToFile(history: History, prefix: String = "temporal-history-"): File {
        val json = exportToJson(history)
        return File.createTempFile(prefix, ".json").apply {
            deleteOnExit()
            writeText(json)
        }
    }

    /**
     * Load workflow history JSON from a file.
     *
     * @param file The file containing JSON history
     * @return The JSON content as a string
     */
    fun loadFromFile(file: File): Result<String> = runCatching {
        file.readText()
    }

    /**
     * Extract the workflow type name from a history JSON string.
     * The workflow type is found in the first WorkflowExecutionStarted event.
     *
     * @param historyJson The JSON history string
     * @return The workflow type name, or null if not found
     */
    fun extractWorkflowType(historyJson: String): String? {
        // Match workflowType.name in the JSON
        // Example: "workflowType": { "name": "MyWorkflow" }
        val regex = """"workflowType"\s*:\s*\{\s*"name"\s*:\s*"([^"]+)"""".toRegex()
        return regex.find(historyJson)?.groupValues?.get(1)
    }

    /**
     * Validate that a JSON string appears to be valid Temporal history.
     *
     * @param json The JSON string to validate
     * @return true if the JSON appears to be valid history format
     */
    fun isValidHistoryJson(json: String): Boolean {
        return try {
            // Check for required structure
            json.contains("\"events\"") &&
            json.contains("\"eventType\"") &&
            json.contains("EVENT_TYPE_WORKFLOW_EXECUTION_STARTED")
        } catch (e: Exception) {
            false
        }
    }
}
