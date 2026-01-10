package io.temporal.intellij.workflow

import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import com.google.protobuf.ByteString
import io.temporal.api.common.v1.Payloads
import io.temporal.api.common.v1.WorkflowExecution
import io.temporal.api.enums.v1.EventType
import io.temporal.api.enums.v1.HistoryEventFilterType
import io.temporal.api.enums.v1.WorkflowExecutionStatus
import io.temporal.api.history.v1.History
import io.temporal.api.history.v1.HistoryEvent
import io.temporal.api.query.v1.WorkflowQuery
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionResponse
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryRequest
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryResponse
import io.temporal.api.workflowservice.v1.ListWorkflowExecutionsRequest
import io.temporal.api.workflowservice.v1.QueryWorkflowRequest
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc
import io.temporal.intellij.codec.CodecClient
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
    private val codecClient: CodecClient = CodecClient(settings)

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

    /**
     * List recent workflow executions.
     * @param pageSize Maximum number of workflows to return
     * @param excludeChildren If true, only returns root workflows (those without a parent)
     */
    fun listWorkflows(pageSize: Int = 20, excludeChildren: Boolean = true): Result<List<WorkflowListItem>> {
        val stub = this.stub ?: return Result.failure(IllegalStateException("Not connected"))

        return try {
            val requestBuilder = ListWorkflowExecutionsRequest.newBuilder()
                .setNamespace(settings.namespace)
                .setPageSize(pageSize)

            // Filter to exclude child workflows if requested
            if (excludeChildren) {
                requestBuilder.setQuery("ParentWorkflowId IS NULL")
            }

            val request = requestBuilder.build()

            val response = stub.listWorkflowExecutions(request)

            val workflows = response.executionsList.map { info ->
                WorkflowListItem(
                    workflowId = info.execution.workflowId,
                    runId = info.execution.runId,
                    workflowType = info.type.name,
                    status = mapStatus(info.status),
                    startTime = if (info.hasStartTime())
                        Instant.ofEpochSecond(info.startTime.seconds, info.startTime.nanos.toLong())
                    else null
                )
            }
            Result.success(workflows)
        } catch (e: StatusRuntimeException) {
            val message = when (e.status.code) {
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

    /**
     * Get workflow execution history with optional pagination.
     * @param waitNewEvent If true, uses long-poll semantics (blocks until new events or timeout).
     *                     Required when using a token from a previous long-poll call.
     */
    fun getWorkflowHistory(
        workflowId: String,
        runId: String? = null,
        pageSize: Int = 100,
        nextPageToken: ByteString = ByteString.EMPTY,
        waitNewEvent: Boolean = false
    ): Result<WorkflowHistoryPage> {
        return getWorkflowHistoryInternal(workflowId, runId, pageSize, nextPageToken, waitNewEvent = waitNewEvent)
    }

    /**
     * Long-poll for new workflow history events.
     * This call blocks until a new event is available or the workflow completes.
     * Returns the full history including any new events.
     */
    fun waitForNewHistoryEvents(
        workflowId: String,
        runId: String? = null,
        pageSize: Int = 100,
        nextPageToken: ByteString = ByteString.EMPTY
    ): Result<WorkflowHistoryPage> {
        return getWorkflowHistoryInternal(workflowId, runId, pageSize, nextPageToken, waitNewEvent = true)
    }

    private fun getWorkflowHistoryInternal(
        workflowId: String,
        runId: String?,
        pageSize: Int,
        nextPageToken: ByteString,
        waitNewEvent: Boolean
    ): Result<WorkflowHistoryPage> {
        val stub = this.stub ?: return Result.failure(IllegalStateException("Not connected"))

        return try {
            val executionBuilder = WorkflowExecution.newBuilder()
                .setWorkflowId(workflowId)
            if (!runId.isNullOrEmpty()) {
                executionBuilder.setRunId(runId)
            }

            val request = GetWorkflowExecutionHistoryRequest.newBuilder()
                .setNamespace(settings.namespace)
                .setExecution(executionBuilder.build())
                .setMaximumPageSize(pageSize)
                .setNextPageToken(nextPageToken)
                .setWaitNewEvent(waitNewEvent)
                .setHistoryEventFilterType(HistoryEventFilterType.HISTORY_EVENT_FILTER_TYPE_ALL_EVENT)
                .build()

            // Use a longer timeout for long polling
            val stubToUse = if (waitNewEvent) {
                stub.withDeadlineAfter(70, TimeUnit.SECONDS) // Long poll timeout
            } else {
                stub
            }

            val response = stubToUse.getWorkflowExecutionHistory(request)

            val events = response.history.eventsList.map { event ->
                parseHistoryEvent(event)
            }

            Result.success(WorkflowHistoryPage(
                events = events,
                nextPageToken = response.nextPageToken
            ))
        } catch (e: StatusRuntimeException) {
            val message = when (e.status.code) {
                Status.Code.NOT_FOUND -> "Workflow not found: $workflowId"
                Status.Code.UNAVAILABLE -> "Server unavailable"
                Status.Code.DEADLINE_EXCEEDED -> if (waitNewEvent) "Long poll timeout" else "Request timed out"
                Status.Code.PERMISSION_DENIED -> "Permission denied"
                Status.Code.UNAUTHENTICATED -> "Authentication failed"
                Status.Code.CANCELLED -> "Request cancelled"
                else -> "Error: ${e.status.description ?: e.message}"
            }
            Result.failure(WorkflowServiceException(message, e))
        } catch (e: Exception) {
            Result.failure(WorkflowServiceException("Error: ${e.message}", e))
        }
    }

    /**
     * Query a workflow to retrieve its current state.
     */
    fun queryWorkflow(
        workflowId: String,
        runId: String? = null,
        queryType: String,
        queryArgs: String? = null
    ): Result<String> {
        val stub = this.stub ?: return Result.failure(IllegalStateException("Not connected"))

        return try {
            val executionBuilder = WorkflowExecution.newBuilder()
                .setWorkflowId(workflowId)
            if (!runId.isNullOrEmpty()) {
                executionBuilder.setRunId(runId)
            }

            val queryBuilder = WorkflowQuery.newBuilder()
                .setQueryType(queryType)

            // Add query arguments if provided
            if (!queryArgs.isNullOrEmpty()) {
                val payload = io.temporal.api.common.v1.Payload.newBuilder()
                    .putMetadata("encoding", ByteString.copyFromUtf8("json/plain"))
                    .setData(ByteString.copyFromUtf8(queryArgs))
                    .build()
                queryBuilder.setQueryArgs(Payloads.newBuilder().addPayloads(payload).build())
            }

            val request = QueryWorkflowRequest.newBuilder()
                .setNamespace(settings.namespace)
                .setExecution(executionBuilder.build())
                .setQuery(queryBuilder.build())
                .build()

            val response = stub.queryWorkflow(request)

            // Decode the result
            val result = if (response.hasQueryResult() && response.queryResult.payloadsCount > 0) {
                response.queryResult.payloadsList.joinToString("\n") { payload ->
                    tryDecodePayload(payload) ?: "<binary data>"
                }
            } else {
                "<no result>"
            }

            Result.success(result)
        } catch (e: StatusRuntimeException) {
            val message = when (e.status.code) {
                Status.Code.NOT_FOUND -> "Workflow not found: $workflowId"
                Status.Code.UNAVAILABLE -> "Server unavailable"
                Status.Code.DEADLINE_EXCEEDED -> "Request timed out"
                Status.Code.PERMISSION_DENIED -> "Permission denied"
                Status.Code.UNAUTHENTICATED -> "Authentication failed"
                Status.Code.INVALID_ARGUMENT -> "Query failed: ${e.status.description}"
                else -> "Error: ${e.status.description ?: e.message}"
            }
            Result.failure(WorkflowServiceException(message, e))
        } catch (e: Exception) {
            Result.failure(WorkflowServiceException("Error: ${e.message}", e))
        }
    }

    /**
     * Get workflow stack trace using the built-in __stack_trace query.
     */
    fun getStackTrace(workflowId: String, runId: String? = null): Result<String> {
        return queryWorkflow(workflowId, runId, "__stack_trace")
    }

    /**
     * Get the raw workflow execution history as a History proto object.
     * This is useful for exporting history to JSON format for replay.
     */
    fun getRawHistory(workflowId: String, runId: String? = null): Result<History> {
        val stub = this.stub ?: return Result.failure(IllegalStateException("Not connected"))

        return try {
            val executionBuilder = WorkflowExecution.newBuilder()
                .setWorkflowId(workflowId)
            if (!runId.isNullOrEmpty()) {
                executionBuilder.setRunId(runId)
            }

            val allEvents = mutableListOf<HistoryEvent>()
            var nextPageToken = ByteString.EMPTY

            // Fetch all pages of history
            do {
                val request = GetWorkflowExecutionHistoryRequest.newBuilder()
                    .setNamespace(settings.namespace)
                    .setExecution(executionBuilder.build())
                    .setMaximumPageSize(1000)
                    .setNextPageToken(nextPageToken)
                    .build()

                val response = stub.getWorkflowExecutionHistory(request)
                allEvents.addAll(response.history.eventsList)
                nextPageToken = response.nextPageToken
            } while (!nextPageToken.isEmpty)

            Result.success(History.newBuilder().addAllEvents(allEvents).build())
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

    /**
     * Create an iterator for workflow history events with long polling support.
     * The iterator handles pagination internally and blocks waiting for new events
     * when caught up with the history.
     *
     * @param workflowId The workflow ID
     * @param runId Optional run ID (uses latest if not specified)
     * @param pageSize Number of events per page
     * @param startToken Optional token to continue from a previous position
     * @return A HistoryEventIterator that yields raw HistoryEvent objects
     * @throws IllegalStateException if not connected
     */
    fun getHistoryIterator(
        workflowId: String,
        runId: String? = null,
        pageSize: Int = 100,
        startToken: ByteString = ByteString.EMPTY
    ): HistoryEventIterator {
        val stub = this.stub ?: throw IllegalStateException("Not connected")

        val executionBuilder = WorkflowExecution.newBuilder()
            .setWorkflowId(workflowId)
        if (!runId.isNullOrEmpty()) {
            executionBuilder.setRunId(runId)
        }
        val execution = executionBuilder.build()

        return HistoryEventIterator(startToken) { nextToken ->
            val request = GetWorkflowExecutionHistoryRequest.newBuilder()
                .setNamespace(settings.namespace)
                .setExecution(execution)
                .setMaximumPageSize(pageSize)
                .setNextPageToken(nextToken)
                .setWaitNewEvent(true)
                .setHistoryEventFilterType(HistoryEventFilterType.HISTORY_EVENT_FILTER_TYPE_ALL_EVENT)
                .build()

            // Use a longer timeout for long polling
            val longPollStub = stub.withDeadlineAfter(70, TimeUnit.SECONDS)
            longPollStub.getWorkflowExecutionHistory(request)
        }
    }

    /**
     * Parse a raw HistoryEvent to a WorkflowHistoryEvent with extracted details.
     */
    fun parseEvent(event: HistoryEvent): WorkflowHistoryEvent {
        return parseHistoryEvent(event)
    }

    private fun parseHistoryEvent(event: HistoryEvent): WorkflowHistoryEvent {
        val timestamp = if (event.hasEventTime()) {
            Instant.ofEpochSecond(event.eventTime.seconds, event.eventTime.nanos.toLong())
        } else null

        // Extract event-specific details
        val details = extractEventDetails(event)

        return WorkflowHistoryEvent(
            eventId = event.eventId,
            eventType = event.eventType.name,
            eventCategory = categorizeEvent(event.eventType),
            timestamp = timestamp,
            details = details
        )
    }

    private fun categorizeEvent(eventType: EventType): EventCategory {
        return when (eventType) {
            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED,
            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED,
            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_FAILED,
            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_TIMED_OUT,
            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_CANCELED,
            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_TERMINATED,
            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_CONTINUED_AS_NEW -> EventCategory.WORKFLOW

            EventType.EVENT_TYPE_WORKFLOW_TASK_SCHEDULED,
            EventType.EVENT_TYPE_WORKFLOW_TASK_STARTED,
            EventType.EVENT_TYPE_WORKFLOW_TASK_COMPLETED,
            EventType.EVENT_TYPE_WORKFLOW_TASK_FAILED,
            EventType.EVENT_TYPE_WORKFLOW_TASK_TIMED_OUT -> EventCategory.WORKFLOW_TASK

            EventType.EVENT_TYPE_ACTIVITY_TASK_SCHEDULED,
            EventType.EVENT_TYPE_ACTIVITY_TASK_STARTED,
            EventType.EVENT_TYPE_ACTIVITY_TASK_COMPLETED,
            EventType.EVENT_TYPE_ACTIVITY_TASK_FAILED,
            EventType.EVENT_TYPE_ACTIVITY_TASK_TIMED_OUT,
            EventType.EVENT_TYPE_ACTIVITY_TASK_CANCEL_REQUESTED,
            EventType.EVENT_TYPE_ACTIVITY_TASK_CANCELED -> EventCategory.ACTIVITY

            EventType.EVENT_TYPE_TIMER_STARTED,
            EventType.EVENT_TYPE_TIMER_FIRED,
            EventType.EVENT_TYPE_TIMER_CANCELED -> EventCategory.TIMER

            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED -> EventCategory.SIGNAL

            EventType.EVENT_TYPE_START_CHILD_WORKFLOW_EXECUTION_INITIATED,
            EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_STARTED,
            EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_COMPLETED,
            EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_FAILED,
            EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_CANCELED,
            EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_TIMED_OUT,
            EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_TERMINATED -> EventCategory.CHILD_WORKFLOW

            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_ACCEPTED,
            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_COMPLETED,
            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_REJECTED -> EventCategory.UPDATE

            else -> EventCategory.OTHER
        }
    }

    private fun extractEventDetails(event: HistoryEvent): Map<String, String> {
        val details = mutableMapOf<String, String>()

        when (event.eventType) {
            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED -> {
                if (event.hasWorkflowExecutionStartedEventAttributes()) {
                    val attrs = event.workflowExecutionStartedEventAttributes
                    details["workflowType"] = attrs.workflowType.name
                    details["taskQueue"] = attrs.taskQueue.name
                    if (attrs.hasInput() && attrs.input.payloadsCount > 0) {
                        details["input"] = decodeAllPayloads(attrs.input)
                    }
                    if (attrs.parentWorkflowExecution != null && attrs.parentWorkflowExecution.workflowId.isNotEmpty()) {
                        details["parentWorkflowId"] = attrs.parentWorkflowExecution.workflowId
                    }
                }
            }
            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED -> {
                if (event.hasWorkflowExecutionCompletedEventAttributes()) {
                    val attrs = event.workflowExecutionCompletedEventAttributes
                    if (attrs.hasResult() && attrs.result.payloadsCount > 0) {
                        details["result"] = decodeAllPayloads(attrs.result)
                    }
                }
            }
            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_FAILED -> {
                if (event.hasWorkflowExecutionFailedEventAttributes()) {
                    val attrs = event.workflowExecutionFailedEventAttributes
                    if (attrs.hasFailure()) {
                        details["failure"] = attrs.failure.message
                        if (attrs.failure.stackTrace.isNotEmpty()) {
                            details["stackTrace"] = attrs.failure.stackTrace
                        }
                    }
                }
            }
            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_TIMED_OUT -> {
                if (event.hasWorkflowExecutionTimedOutEventAttributes()) {
                    val attrs = event.workflowExecutionTimedOutEventAttributes
                    details["retryState"] = attrs.retryState.name
                }
            }
            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_CANCELED -> {
                if (event.hasWorkflowExecutionCanceledEventAttributes()) {
                    val attrs = event.workflowExecutionCanceledEventAttributes
                    if (attrs.hasDetails() && attrs.details.payloadsCount > 0) {
                        details["details"] = decodeAllPayloads(attrs.details)
                    }
                }
            }
            EventType.EVENT_TYPE_ACTIVITY_TASK_SCHEDULED -> {
                if (event.hasActivityTaskScheduledEventAttributes()) {
                    val attrs = event.activityTaskScheduledEventAttributes
                    details["activityType"] = attrs.activityType.name
                    details["activityId"] = attrs.activityId
                    details["taskQueue"] = attrs.taskQueue.name
                    if (attrs.hasInput() && attrs.input.payloadsCount > 0) {
                        details["input"] = decodeAllPayloads(attrs.input)
                    }
                    if (attrs.hasScheduleToCloseTimeout()) {
                        details["scheduleToCloseTimeout"] = formatProtoDuration(attrs.scheduleToCloseTimeout)
                    }
                    if (attrs.hasStartToCloseTimeout()) {
                        details["startToCloseTimeout"] = formatProtoDuration(attrs.startToCloseTimeout)
                    }
                }
            }
            EventType.EVENT_TYPE_ACTIVITY_TASK_STARTED -> {
                if (event.hasActivityTaskStartedEventAttributes()) {
                    val attrs = event.activityTaskStartedEventAttributes
                    details["scheduledEventId"] = attrs.scheduledEventId.toString()
                    details["attempt"] = attrs.attempt.toString()
                    if (attrs.hasLastFailure() && attrs.lastFailure.message.isNotEmpty()) {
                        details["lastFailure"] = attrs.lastFailure.message
                    }
                }
            }
            EventType.EVENT_TYPE_ACTIVITY_TASK_COMPLETED -> {
                if (event.hasActivityTaskCompletedEventAttributes()) {
                    val attrs = event.activityTaskCompletedEventAttributes
                    details["scheduledEventId"] = attrs.scheduledEventId.toString()
                    if (attrs.hasResult() && attrs.result.payloadsCount > 0) {
                        details["result"] = decodeAllPayloads(attrs.result)
                    }
                }
            }
            EventType.EVENT_TYPE_ACTIVITY_TASK_FAILED -> {
                if (event.hasActivityTaskFailedEventAttributes()) {
                    val attrs = event.activityTaskFailedEventAttributes
                    details["scheduledEventId"] = attrs.scheduledEventId.toString()
                    if (attrs.hasFailure()) {
                        details["failure"] = attrs.failure.message
                        if (attrs.failure.stackTrace.isNotEmpty()) {
                            details["stackTrace"] = attrs.failure.stackTrace
                        }
                    }
                    details["retryState"] = attrs.retryState.name
                }
            }
            EventType.EVENT_TYPE_ACTIVITY_TASK_TIMED_OUT -> {
                if (event.hasActivityTaskTimedOutEventAttributes()) {
                    val attrs = event.activityTaskTimedOutEventAttributes
                    details["scheduledEventId"] = attrs.scheduledEventId.toString()
                    if (attrs.hasFailure()) {
                        details["failure"] = attrs.failure.message
                    }
                    details["retryState"] = attrs.retryState.name
                }
            }
            EventType.EVENT_TYPE_ACTIVITY_TASK_CANCELED -> {
                if (event.hasActivityTaskCanceledEventAttributes()) {
                    val attrs = event.activityTaskCanceledEventAttributes
                    details["scheduledEventId"] = attrs.scheduledEventId.toString()
                    if (attrs.hasDetails() && attrs.details.payloadsCount > 0) {
                        details["details"] = decodeAllPayloads(attrs.details)
                    }
                }
            }
            EventType.EVENT_TYPE_TIMER_STARTED -> {
                if (event.hasTimerStartedEventAttributes()) {
                    val attrs = event.timerStartedEventAttributes
                    details["timerId"] = attrs.timerId
                    if (attrs.hasStartToFireTimeout()) {
                        details["duration"] = formatProtoDuration(attrs.startToFireTimeout)
                    }
                }
            }
            EventType.EVENT_TYPE_TIMER_FIRED -> {
                if (event.hasTimerFiredEventAttributes()) {
                    val attrs = event.timerFiredEventAttributes
                    details["timerId"] = attrs.timerId
                    details["startedEventId"] = attrs.startedEventId.toString()
                }
            }
            EventType.EVENT_TYPE_TIMER_CANCELED -> {
                if (event.hasTimerCanceledEventAttributes()) {
                    val attrs = event.timerCanceledEventAttributes
                    details["timerId"] = attrs.timerId
                    details["startedEventId"] = attrs.startedEventId.toString()
                }
            }
            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED -> {
                if (event.hasWorkflowExecutionSignaledEventAttributes()) {
                    val attrs = event.workflowExecutionSignaledEventAttributes
                    details["signalName"] = attrs.signalName
                    if (attrs.hasInput() && attrs.input.payloadsCount > 0) {
                        details["input"] = decodeAllPayloads(attrs.input)
                    }
                }
            }
            EventType.EVENT_TYPE_START_CHILD_WORKFLOW_EXECUTION_INITIATED -> {
                if (event.hasStartChildWorkflowExecutionInitiatedEventAttributes()) {
                    val attrs = event.startChildWorkflowExecutionInitiatedEventAttributes
                    details["workflowType"] = attrs.workflowType.name
                    details["workflowId"] = attrs.workflowId
                    details["taskQueue"] = attrs.taskQueue.name
                    if (attrs.hasInput() && attrs.input.payloadsCount > 0) {
                        details["input"] = decodeAllPayloads(attrs.input)
                    }
                }
            }
            EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_STARTED -> {
                if (event.hasChildWorkflowExecutionStartedEventAttributes()) {
                    val attrs = event.childWorkflowExecutionStartedEventAttributes
                    details["workflowId"] = attrs.workflowExecution.workflowId
                    details["runId"] = attrs.workflowExecution.runId
                    details["workflowType"] = attrs.workflowType.name
                }
            }
            EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_COMPLETED -> {
                if (event.hasChildWorkflowExecutionCompletedEventAttributes()) {
                    val attrs = event.childWorkflowExecutionCompletedEventAttributes
                    details["workflowId"] = attrs.workflowExecution.workflowId
                    if (attrs.hasResult() && attrs.result.payloadsCount > 0) {
                        details["result"] = decodeAllPayloads(attrs.result)
                    }
                }
            }
            EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_FAILED -> {
                if (event.hasChildWorkflowExecutionFailedEventAttributes()) {
                    val attrs = event.childWorkflowExecutionFailedEventAttributes
                    details["workflowId"] = attrs.workflowExecution.workflowId
                    if (attrs.hasFailure()) {
                        details["failure"] = attrs.failure.message
                    }
                }
            }
            EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_CANCELED -> {
                if (event.hasChildWorkflowExecutionCanceledEventAttributes()) {
                    val attrs = event.childWorkflowExecutionCanceledEventAttributes
                    details["workflowId"] = attrs.workflowExecution.workflowId
                    if (attrs.hasDetails() && attrs.details.payloadsCount > 0) {
                        details["details"] = decodeAllPayloads(attrs.details)
                    }
                }
            }
            EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_TIMED_OUT -> {
                if (event.hasChildWorkflowExecutionTimedOutEventAttributes()) {
                    val attrs = event.childWorkflowExecutionTimedOutEventAttributes
                    details["workflowId"] = attrs.workflowExecution.workflowId
                }
            }
            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_ACCEPTED -> {
                if (event.hasWorkflowExecutionUpdateAcceptedEventAttributes()) {
                    val attrs = event.workflowExecutionUpdateAcceptedEventAttributes
                    details["updateId"] = attrs.acceptedRequest.meta.updateId
                    if (attrs.acceptedRequest.hasInput() && attrs.acceptedRequest.input.hasArgs()) {
                        details["input"] = decodeAllPayloads(attrs.acceptedRequest.input.args)
                    }
                }
            }
            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_COMPLETED -> {
                if (event.hasWorkflowExecutionUpdateCompletedEventAttributes()) {
                    val attrs = event.workflowExecutionUpdateCompletedEventAttributes
                    if (attrs.hasOutcome() && attrs.outcome.hasSuccess()) {
                        details["result"] = decodeAllPayloads(attrs.outcome.success)
                    }
                    if (attrs.hasOutcome() && attrs.outcome.hasFailure()) {
                        details["failure"] = attrs.outcome.failure.message
                    }
                }
            }
            else -> {
                // No specific details for other events
            }
        }

        return details
    }

    /**
     * Decode all payloads in a Payloads message to a string representation.
     */
    private fun decodeAllPayloads(payloads: Payloads): String {
        return if (payloads.payloadsCount == 1) {
            tryDecodePayload(payloads.getPayloads(0)) ?: "<binary>"
        } else {
            payloads.payloadsList.mapIndexed { index, payload ->
                "[$index]: ${tryDecodePayload(payload) ?: "<binary>"}"
            }.joinToString("\n")
        }
    }

    /**
     * Format a protobuf Duration to a human-readable string.
     */
    private fun formatProtoDuration(duration: com.google.protobuf.Duration): String {
        val javaDuration = Duration.ofSeconds(duration.seconds, duration.nanos.toLong())
        return formatDuration(javaDuration)
    }

    private fun formatDuration(duration: Duration): String {
        return when {
            duration.toHours() > 0 -> "${duration.toHours()}h ${duration.toMinutesPart()}m"
            duration.toMinutes() > 0 -> "${duration.toMinutes()}m ${duration.toSecondsPart()}s"
            else -> "${duration.seconds}s"
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

    /**
     * Attempt to decode a payload, first using the codec server if configured,
     * then falling back to direct UTF-8 decoding.
     */
    private fun tryDecodePayload(payload: io.temporal.api.common.v1.Payload): String? {
        return try {
            // If codec server is configured, try to decode through it first
            val payloadToRead = if (codecClient.isConfigured()) {
                codecClient.decode(payload).getOrNull() ?: payload
            } else {
                payload
            }

            // Check the encoding metadata to determine how to decode
            val encoding = payloadToRead.metadataMap["encoding"]?.toStringUtf8() ?: ""

            when {
                encoding.contains("json") -> payloadToRead.data.toStringUtf8()
                encoding.contains("plain") -> payloadToRead.data.toStringUtf8()
                encoding.contains("protobuf") -> "<protobuf: ${payloadToRead.data.size()} bytes>"
                encoding.contains("binary") -> "<binary: ${payloadToRead.data.size()} bytes>"
                else -> {
                    // Try UTF-8 decoding as fallback
                    val text = payloadToRead.data.toStringUtf8()
                    // Check if it looks like valid text (no control characters except newlines/tabs)
                    if (text.all { it.isWhitespace() || !it.isISOControl() }) {
                        text
                    } else {
                        "<binary: ${payloadToRead.data.size()} bytes>"
                    }
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Get the codec client for external use (e.g., testing connection).
     */
    fun getCodecClient(): CodecClient = codecClient
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

data class WorkflowListItem(
    val workflowId: String,
    val runId: String,
    val workflowType: String,
    val status: WorkflowStatus,
    val startTime: Instant?
)

// Event History data classes

data class WorkflowHistoryPage(
    val events: List<WorkflowHistoryEvent>,
    val nextPageToken: ByteString
)

data class WorkflowHistoryEvent(
    val eventId: Long,
    val eventType: String,
    val eventCategory: EventCategory,
    val timestamp: Instant?,
    val details: Map<String, String>
)

enum class EventCategory {
    WORKFLOW,
    WORKFLOW_TASK,
    ACTIVITY,
    TIMER,
    SIGNAL,
    CHILD_WORKFLOW,
    UPDATE,
    OTHER
}
