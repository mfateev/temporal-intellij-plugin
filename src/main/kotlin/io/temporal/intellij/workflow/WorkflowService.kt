package io.temporal.intellij.workflow

import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import io.temporal.api.common.v1.WorkflowExecution
import io.temporal.api.enums.v1.WorkflowExecutionStatus
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionResponse
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc
import io.temporal.intellij.settings.TemporalSettings
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Service for interacting with Temporal Workflow APIs.
 */
class WorkflowService(private val settings: TemporalSettings.State) {

    private var channel: ManagedChannel? = null
    private var stub: WorkflowServiceGrpc.WorkflowServiceBlockingStub? = null

    /**
     * Connect to the Temporal server.
     */
    fun connect(): Result<Unit> {
        return try {
            channel?.shutdown()

            val builder = if (settings.tlsEnabled) {
                val sslContextBuilder = GrpcSslContexts.forClient()

                if (settings.clientCertPath.isNotEmpty()) {
                    sslContextBuilder.keyManager(
                        File(settings.clientCertPath),
                        File(settings.clientKeyPath)
                    )
                }

                if (settings.serverCACertPath.isNotEmpty()) {
                    sslContextBuilder.trustManager(File(settings.serverCACertPath))
                }

                NettyChannelBuilder.forTarget(settings.address)
                    .sslContext(sslContextBuilder.build())
                    .also { builder ->
                        if (settings.serverName.isNotEmpty()) {
                            builder.overrideAuthority(settings.serverName)
                        }
                    }
            } else {
                ManagedChannelBuilder.forTarget(settings.address)
                    .usePlaintext()
            }

            channel = builder.build()
            stub = WorkflowServiceGrpc.newBlockingStub(channel)
                .withDeadlineAfter(30, TimeUnit.SECONDS)

            // Add API key if configured
            if (settings.apiKey.isNotEmpty()) {
                stub = stub?.withCallCredentials(ApiKeyCredentials(settings.apiKey))
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Disconnect from the Temporal server.
     */
    fun disconnect() {
        channel?.shutdown()
        channel = null
        stub = null
    }

    /**
     * Describe a workflow execution.
     */
    fun describeWorkflow(
        workflowId: String,
        runId: String? = null
    ): Result<WorkflowExecutionInfo> {
        val stub = this.stub ?: return Result.failure(IllegalStateException("Not connected"))

        return try {
            val executionBuilder = WorkflowExecution.newBuilder()
                .setWorkflowId(workflowId)
            if (!runId.isNullOrEmpty()) {
                executionBuilder.setRunId(runId)
            }

            val request = DescribeWorkflowExecutionRequest.newBuilder()
                .setNamespace(settings.namespace)
                .setExecution(executionBuilder.build())
                .build()

            val response = stub.describeWorkflowExecution(request)
            Result.success(parseWorkflowInfo(response))
        } catch (e: StatusRuntimeException) {
            val message = when (e.status.code) {
                Status.Code.NOT_FOUND -> "Workflow not found: $workflowId"
                Status.Code.UNAVAILABLE -> "Server unavailable"
                Status.Code.DEADLINE_EXCEEDED -> "Request timed out"
                Status.Code.PERMISSION_DENIED -> "Permission denied"
                Status.Code.UNAUTHENTICATED -> "Authentication failed"
                else -> "Error: ${e.status.description ?: e.message}"
            }
            Result.failure(WorkflowServiceException(message, e))
        } catch (e: Exception) {
            Result.failure(WorkflowServiceException("Error: ${e.message}", e))
        }
    }

    private fun parseWorkflowInfo(response: DescribeWorkflowExecutionResponse): WorkflowExecutionInfo {
        val info = response.workflowExecutionInfo
        val config = response.executionConfig

        val pendingActivities = response.pendingActivitiesList.map { activity ->
            PendingActivityInfo(
                activityId = activity.activityId,
                activityType = activity.activityType.name,
                state = activity.state.name,
                attempt = activity.attempt,
                maxAttempts = activity.maximumAttempts,
                scheduledTime = if (activity.hasScheduledTime())
                    Instant.ofEpochSecond(activity.scheduledTime.seconds, activity.scheduledTime.nanos.toLong())
                    else null,
                lastStartedTime = if (activity.hasLastStartedTime())
                    Instant.ofEpochSecond(activity.lastStartedTime.seconds, activity.lastStartedTime.nanos.toLong())
                    else null,
                lastHeartbeatTime = if (activity.hasLastHeartbeatTime())
                    Instant.ofEpochSecond(activity.lastHeartbeatTime.seconds, activity.lastHeartbeatTime.nanos.toLong())
                    else null,
                lastFailureMessage = activity.lastFailure?.message ?: "",
                heartbeatDetails = if (activity.hasHeartbeatDetails() && activity.heartbeatDetails.payloadsCount > 0)
                    tryDecodePayload(activity.heartbeatDetails.getPayloads(0))
                    else null
            )
        }

        val pendingChildren = response.pendingChildrenList.map { child ->
            PendingChildWorkflowInfo(
                workflowId = child.workflowId,
                runId = child.runId,
                workflowType = child.workflowTypeName,
                initiatedId = child.initiatedId
            )
        }

        // Note: pendingTimers is not directly available in DescribeWorkflowExecutionResponse
        // It would need to be derived from event history

        return WorkflowExecutionInfo(
            workflowId = info.execution.workflowId,
            runId = info.execution.runId,
            workflowType = info.type.name,
            status = mapStatus(info.status),
            taskQueue = info.taskQueue,
            startTime = if (info.hasStartTime())
                Instant.ofEpochSecond(info.startTime.seconds, info.startTime.nanos.toLong())
                else null,
            closeTime = if (info.hasCloseTime())
                Instant.ofEpochSecond(info.closeTime.seconds, info.closeTime.nanos.toLong())
                else null,
            historyLength = info.historyLength,
            historySizeBytes = info.historySizeBytes,
            pendingActivities = pendingActivities,
            pendingChildren = pendingChildren,
            pendingTimers = emptyList() // Would need event history to populate
        )
    }

    private fun mapStatus(status: WorkflowExecutionStatus): WorkflowStatus {
        return when (status) {
            WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING -> WorkflowStatus.RUNNING
            WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_COMPLETED -> WorkflowStatus.COMPLETED
            WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_FAILED -> WorkflowStatus.FAILED
            WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_CANCELED -> WorkflowStatus.CANCELED
            WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_TERMINATED -> WorkflowStatus.TERMINATED
            WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_CONTINUED_AS_NEW -> WorkflowStatus.CONTINUED_AS_NEW
            WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_TIMED_OUT -> WorkflowStatus.TIMED_OUT
            else -> WorkflowStatus.UNKNOWN
        }
    }

    private fun tryDecodePayload(payload: io.temporal.api.common.v1.Payload): String? {
        return try {
            payload.data.toStringUtf8()
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * API key credentials for gRPC.
 */
private class ApiKeyCredentials(private val apiKey: String) : io.grpc.CallCredentials() {
    override fun applyRequestMetadata(
        requestInfo: RequestInfo,
        appExecutor: java.util.concurrent.Executor,
        applier: MetadataApplier
    ) {
        val metadata = io.grpc.Metadata()
        metadata.put(
            io.grpc.Metadata.Key.of("authorization", io.grpc.Metadata.ASCII_STRING_MARSHALLER),
            "Bearer $apiKey"
        )
        applier.apply(metadata)
    }

    override fun thisUsesUnstableApi() {}
}

class WorkflowServiceException(message: String, cause: Throwable? = null) : Exception(message, cause)

// Data classes for workflow information

enum class WorkflowStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELED,
    TERMINATED,
    CONTINUED_AS_NEW,
    TIMED_OUT,
    UNKNOWN
}

data class WorkflowExecutionInfo(
    val workflowId: String,
    val runId: String,
    val workflowType: String,
    val status: WorkflowStatus,
    val taskQueue: String,
    val startTime: Instant?,
    val closeTime: Instant?,
    val historyLength: Long,
    val historySizeBytes: Long,
    val pendingActivities: List<PendingActivityInfo>,
    val pendingChildren: List<PendingChildWorkflowInfo>,
    val pendingTimers: List<PendingTimerInfo>
)

data class PendingActivityInfo(
    val activityId: String,
    val activityType: String,
    val state: String,
    val attempt: Int,
    val maxAttempts: Int,
    val scheduledTime: Instant?,
    val lastStartedTime: Instant?,
    val lastHeartbeatTime: Instant?,
    val lastFailureMessage: String,
    val heartbeatDetails: String?
)

data class PendingChildWorkflowInfo(
    val workflowId: String,
    val runId: String,
    val workflowType: String,
    val initiatedId: Long
)

data class PendingTimerInfo(
    val timerId: String,
    val startToFireDuration: Duration?,
    val firesAt: Instant?
)
