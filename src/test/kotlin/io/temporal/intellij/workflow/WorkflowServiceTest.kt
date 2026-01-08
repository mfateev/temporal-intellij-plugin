package io.temporal.intellij.workflow

import io.temporal.intellij.settings.TemporalSettings
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.Socket

/**
 * Integration tests for WorkflowService.
 * These tests require a running Temporal server at localhost:7233.
 */
class WorkflowServiceTest {

    private lateinit var settings: TemporalSettings.State
    private lateinit var service: WorkflowService

    @BeforeEach
    fun setup() {
        // Skip tests if Temporal server is not running
        assumeTrue(isTemporalServerRunning(), "Temporal server not running at localhost:7233")

        settings = TemporalSettings.State().apply {
            address = "localhost:7233"
            namespace = "default"
            tlsEnabled = false
        }
        service = WorkflowService(settings)
    }

    @Test
    fun `connect to local Temporal server`() {
        val result = service.connect()
        assert(result.isSuccess) { "Failed to connect: ${result.exceptionOrNull()?.message}" }
        service.disconnect()
    }

    @Test
    fun `list workflows returns results without error`() {
        val connectResult = service.connect()
        assert(connectResult.isSuccess) { "Failed to connect: ${connectResult.exceptionOrNull()?.message}" }

        val result = service.listWorkflows(10)

        // Should succeed even if no workflows exist
        assert(result.isSuccess) { "Failed to list workflows: ${result.exceptionOrNull()?.message}" }

        val workflows = result.getOrNull()!!
        println("Found ${workflows.size} workflows:")
        workflows.forEach { workflow ->
            println("  - ${workflow.workflowId} (${workflow.workflowType}) [${workflow.status}] started: ${workflow.startTime}")
        }

        service.disconnect()
    }

    @Test
    fun `describe workflow returns info for existing workflow`() {
        val connectResult = service.connect()
        assert(connectResult.isSuccess) { "Failed to connect: ${connectResult.exceptionOrNull()?.message}" }

        // First list workflows to get a valid ID
        val listResult = service.listWorkflows(1)
        if (listResult.isSuccess && listResult.getOrNull()!!.isNotEmpty()) {
            val firstWorkflow = listResult.getOrNull()!!.first()

            val describeResult = service.describeWorkflow(firstWorkflow.workflowId, firstWorkflow.runId)
            assert(describeResult.isSuccess) { "Failed to describe workflow: ${describeResult.exceptionOrNull()?.message}" }

            val info = describeResult.getOrNull()!!
            println("Workflow details:")
            println("  ID: ${info.workflowId}")
            println("  Run ID: ${info.runId}")
            println("  Type: ${info.workflowType}")
            println("  Status: ${info.status}")
            println("  Task Queue: ${info.taskQueue}")
            println("  History Length: ${info.historyLength}")
            println("  Pending Activities: ${info.pendingActivities.size}")
        } else {
            println("No workflows found to describe - skipping describe test")
        }

        service.disconnect()
    }

    private fun isTemporalServerRunning(): Boolean {
        return try {
            Socket("localhost", 7233).use { true }
        } catch (e: Exception) {
            false
        }
    }
}
