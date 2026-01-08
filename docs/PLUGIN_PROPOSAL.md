# Temporal IntelliJ Plugin - Developer Visibility Proposal

## Overview

This document proposes a **developer-focused, read-only** plugin that provides maximum visibility into a **single workflow execution** during development. The goal is to help developers understand what's happening inside their workflow as they build and debug it.

## Target User

A developer actively working on a Temporal workflow who wants to:
- See the current state of their test workflow execution
- Understand what activities are running/pending
- View the event history as it unfolds
- Execute queries to inspect workflow state
- Debug stuck or failing workflows

## Architecture

### Connection Layer

The plugin uses the **Java SDK's gRPC client** to communicate with the Temporal server:

```kotlin
// Using WorkflowServiceStubs for direct gRPC access
val options = WorkflowServiceStubsOptions.newBuilder()
    .setTarget(address)  // e.g., "localhost:7233"
    .build()

val serviceStubs = WorkflowServiceStubs.newServiceStubs(options)
val blockingStub = serviceStubs.blockingStub()
```

### Key gRPC APIs Used

All operations are **read-only** and use the `WorkflowServiceGrpc` blocking stub:

| Feature | gRPC Method | Request Type | Response Type |
|---------|-------------|--------------|---------------|
| Workflow State | `DescribeWorkflowExecution` | `DescribeWorkflowExecutionRequest` | `DescribeWorkflowExecutionResponse` |
| Event History | `GetWorkflowExecutionHistory` | `GetWorkflowExecutionHistoryRequest` | `GetWorkflowExecutionHistoryResponse` |
| Query Workflow | `QueryWorkflow` | `QueryWorkflowRequest` | `QueryWorkflowResponse` |
| List Workflows | `ListWorkflowExecutions` | `ListWorkflowExecutionsRequest` | `ListWorkflowExecutionsResponse` |

---

## Feature 1: Workflow Execution Inspector (Primary Panel)

### Description
The main tool window panel that displays comprehensive information about a single workflow execution.

### UI Mockup
```
┌─────────────────────────────────────────────────────────────────┐
│ Temporal: Workflow Inspector                           [⟳] [⚙]│
├─────────────────────────────────────────────────────────────────┤
│ Workflow ID: [order-12345____________________________] [Watch] │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│ ▼ EXECUTION INFO                                      [RUNNING]│
│   Type: OrderWorkflow                                           │
│   Run ID: abc-def-123-456                                       │
│   Task Queue: order-processing                                  │
│   Started: 2024-01-15 10:30:00 (2 min ago)                     │
│   History: 45 events (2.1 KB)                                   │
│                                                                 │
│ ▼ PENDING ACTIVITIES (2)                                        │
│   ┌──────────────────────────────────────────────────────────┐ │
│   │ ProcessPayment                                           │ │
│   │   State: STARTED | Attempt: 1/3 | Started: 30s ago       │ │
│   │   Last Heartbeat: "Processing card ending 4242"          │ │
│   └──────────────────────────────────────────────────────────┘ │
│   ┌──────────────────────────────────────────────────────────┐ │
│   │ SendConfirmation                                         │ │
│   │   State: SCHEDULED | Waiting for worker                  │ │
│   └──────────────────────────────────────────────────────────┘ │
│                                                                 │
│ ▼ PENDING CHILD WORKFLOWS (1)                                   │
│   └─ InventoryReservation [RUNNING] id: inv-789                │
│                                                                 │
│ ▼ PENDING TIMERS (1)                                            │
│   └─ PaymentTimeout fires in 4m 30s                            │
│                                                                 │
│ ▶ PENDING NEXUS OPERATIONS (0)                                  │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### Java API Implementation

```kotlin
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionResponse
import io.temporal.api.common.v1.WorkflowExecution

fun describeWorkflow(
    stub: WorkflowServiceGrpc.WorkflowServiceBlockingStub,
    namespace: String,
    workflowId: String,
    runId: String? = null
): DescribeWorkflowExecutionResponse {
    val execution = WorkflowExecution.newBuilder()
        .setWorkflowId(workflowId)
        .apply { runId?.let { setRunId(it) } }
        .build()

    val request = DescribeWorkflowExecutionRequest.newBuilder()
        .setNamespace(namespace)
        .setExecution(execution)
        .build()

    return stub.describeWorkflowExecution(request)
}
```

### Response Data Mapping

From `DescribeWorkflowExecutionResponse`:

| Proto Field | UI Display |
|-------------|------------|
| `workflowExecutionInfo.execution.workflowId` | Workflow ID |
| `workflowExecutionInfo.execution.runId` | Run ID |
| `workflowExecutionInfo.type.name` | Type |
| `workflowExecutionInfo.status` | Status badge (RUNNING, COMPLETED, etc.) |
| `workflowExecutionInfo.taskQueue` | Task Queue |
| `workflowExecutionInfo.startTime` | Started timestamp |
| `workflowExecutionInfo.historyLength` | Event count |
| `workflowExecutionInfo.historySizeBytes` | History size |
| `pendingActivities[]` | Pending Activities section |
| `pendingChildren[]` | Pending Child Workflows section |
| `pendingWorkflowTask` | Pending Workflow Task (if any) |

### Pending Activity Details

From `PendingActivityInfo`:

| Proto Field | UI Display |
|-------------|------------|
| `activityId` | Activity ID |
| `activityType.name` | Activity Type |
| `state` | State (SCHEDULED, STARTED, CANCEL_REQUESTED) |
| `attempt` | Current attempt number |
| `maximumAttempts` | Max attempts |
| `scheduledTime` | When scheduled |
| `lastStartedTime` | When last started |
| `lastHeartbeatTime` | Last heartbeat time |
| `heartbeatDetails` | Heartbeat payload (decoded) |
| `lastFailure` | Last failure details |
| `lastWorkerIdentity` | Worker identity |

---

## Feature 2: Event History Timeline

### Description
A chronological view of workflow history events with expandable details.

### UI Mockup
```
┌─ Event History ─────────────────────────────────────────────────┐
│ [Filter: All ▼] [Search: ____________]              [Refresh ⟳]│
├─────────────────────────────────────────────────────────────────┤
│  #  │ Event Type                    │ Time       │ Details      │
│─────┼───────────────────────────────┼────────────┼──────────────│
│  1  │ WorkflowExecutionStarted      │ 10:30:00   │ ▶            │
│  2  │ WorkflowTaskScheduled         │ 10:30:00   │              │
│  3  │ WorkflowTaskStarted           │ 10:30:01   │              │
│  4  │ WorkflowTaskCompleted         │ 10:30:01   │ ▶            │
│  5  │ ActivityTaskScheduled         │ 10:30:01   │ ProcessPayment│
│  6  │ TimerStarted                  │ 10:30:01   │ 5m           │
│  7  │ ActivityTaskStarted           │ 10:30:02   │              │
│     │   └─ Heartbeat                │ 10:30:15   │ "Validating" │
│     │   └─ Heartbeat                │ 10:30:30   │ "Processing" │
│─────┴───────────────────────────────┴────────────┴──────────────│
│ Showing 7 of 45 events                            [Load More...] │
└─────────────────────────────────────────────────────────────────┘
```

### Java API Implementation

```kotlin
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryRequest
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryResponse
import io.temporal.api.history.v1.History
import io.temporal.api.history.v1.HistoryEvent
import com.google.protobuf.ByteString

fun getWorkflowHistory(
    stub: WorkflowServiceGrpc.WorkflowServiceBlockingStub,
    namespace: String,
    workflowId: String,
    runId: String? = null,
    nextPageToken: ByteString = ByteString.EMPTY
): GetWorkflowExecutionHistoryResponse {
    val execution = WorkflowExecution.newBuilder()
        .setWorkflowId(workflowId)
        .apply { runId?.let { setRunId(it) } }
        .build()

    val request = GetWorkflowExecutionHistoryRequest.newBuilder()
        .setNamespace(namespace)
        .setExecution(execution)
        .setNextPageToken(nextPageToken)
        .setWaitNewEvent(false)  // Set to true for long-polling
        .build()

    return stub.getWorkflowExecutionHistory(request)
}
```

### Event Type Categories

Group events by category for filtering:

| Category | Event Types |
|----------|-------------|
| **Workflow** | WorkflowExecutionStarted, WorkflowExecutionCompleted, WorkflowExecutionFailed, WorkflowExecutionTimedOut, WorkflowExecutionCanceled, WorkflowExecutionTerminated, WorkflowExecutionContinuedAsNew |
| **Workflow Task** | WorkflowTaskScheduled, WorkflowTaskStarted, WorkflowTaskCompleted, WorkflowTaskFailed, WorkflowTaskTimedOut |
| **Activity** | ActivityTaskScheduled, ActivityTaskStarted, ActivityTaskCompleted, ActivityTaskFailed, ActivityTaskTimedOut, ActivityTaskCancelRequested, ActivityTaskCanceled |
| **Timer** | TimerStarted, TimerFired, TimerCanceled |
| **Signal** | WorkflowExecutionSignaled |
| **Child Workflow** | StartChildWorkflowExecutionInitiated, ChildWorkflowExecutionStarted, ChildWorkflowExecutionCompleted, ChildWorkflowExecutionFailed |
| **Update** | WorkflowExecutionUpdateAccepted, WorkflowExecutionUpdateCompleted, WorkflowExecutionUpdateRejected |

---

## Feature 3: Query Execution Panel

### Description
Execute workflow queries and display results. Discovers available queries from workflow metadata.

### UI Mockup
```
┌─ Query Workflow ────────────────────────────────────────────────┐
│                                                                 │
│ Available Queries:                                              │
│ ┌─────────────────┐ ┌─────────────────┐ ┌─────────────────┐    │
│ │ getOrderStatus  │ │ getPaymentState │ │ getItems        │    │
│ └─────────────────┘ └─────────────────┘ └─────────────────┘    │
│                                                                 │
│ Query Name: [getOrderStatus_______________▼]                    │
│ Arguments (JSON): [_________________________________]           │
│                                                    [Execute]    │
├─────────────────────────────────────────────────────────────────┤
│ Result:                                                         │
│ ┌─────────────────────────────────────────────────────────────┐│
│ │{                                                            ││
│ │  "status": "PAYMENT_PROCESSING",                           ││
│ │  "orderId": "order-12345",                                 ││
│ │  "items": 3,                                               ││
│ │  "total": 149.99,                                          ││
│ │  "lastUpdated": "2024-01-15T10:32:00Z"                     ││
│ │}                                                            ││
│ └─────────────────────────────────────────────────────────────┘│
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### Java API Implementation

```kotlin
import io.temporal.api.workflowservice.v1.QueryWorkflowRequest
import io.temporal.api.workflowservice.v1.QueryWorkflowResponse
import io.temporal.api.query.v1.WorkflowQuery
import io.temporal.api.common.v1.Payloads

fun queryWorkflow(
    stub: WorkflowServiceGrpc.WorkflowServiceBlockingStub,
    namespace: String,
    workflowId: String,
    runId: String? = null,
    queryType: String,
    queryArgs: Payloads? = null
): QueryWorkflowResponse {
    val execution = WorkflowExecution.newBuilder()
        .setWorkflowId(workflowId)
        .apply { runId?.let { setRunId(it) } }
        .build()

    val query = WorkflowQuery.newBuilder()
        .setQueryType(queryType)
        .apply { queryArgs?.let { setQueryArgs(it) } }
        .build()

    val request = QueryWorkflowRequest.newBuilder()
        .setNamespace(namespace)
        .setExecution(execution)
        .setQuery(query)
        .build()

    return stub.queryWorkflow(request)
}
```

### Built-in Queries

The SDK provides these built-in queries:

| Query Type | Description |
|------------|-------------|
| `__stack_trace` | Returns current stack trace of workflow |
| `__open_sessions` | Returns information about open sessions |

---

## Feature 4: Workflow Selector (Quick Access)

### Description
A dropdown/search panel to quickly select which workflow to watch. Shows recent executions of the same workflow type.

### UI Mockup
```
┌─ Select Workflow ───────────────────────────────────────────────┐
│                                                                 │
│ Workflow ID: [________________________] [🔍 Search]             │
│                                                                 │
│ ─── Recent (OrderWorkflow) ─────────────────────────────────── │
│                                                                 │
│ ● order-12345  [RUNNING]   Started 2 min ago                   │
│   order-12344  [COMPLETED] Completed 5 min ago                 │
│   order-12343  [FAILED]    Failed 10 min ago                   │
│   order-12342  [COMPLETED] Completed 15 min ago                │
│                                                                 │
│ ─── By Query ───────────────────────────────────────────────── │
│                                                                 │
│ [ExecutionStatus = "Running" AND WorkflowType = "OrderWorkflow"]│
│                                                    [Search]     │
└─────────────────────────────────────────────────────────────────┘
```

### Java API Implementation

```kotlin
import io.temporal.api.workflowservice.v1.ListWorkflowExecutionsRequest
import io.temporal.api.workflowservice.v1.ListWorkflowExecutionsResponse

fun listWorkflows(
    stub: WorkflowServiceGrpc.WorkflowServiceBlockingStub,
    namespace: String,
    query: String? = null,
    pageSize: Int = 10
): ListWorkflowExecutionsResponse {
    val requestBuilder = ListWorkflowExecutionsRequest.newBuilder()
        .setNamespace(namespace)
        .setPageSize(pageSize)

    query?.let { requestBuilder.setQuery(it) }

    return stub.listWorkflowExecutions(requestBuilder.build())
}

// Example queries:
// - Recent of type: "WorkflowType = 'OrderWorkflow'"
// - Running only: "ExecutionStatus = 'Running'"
// - Combined: "WorkflowType = 'OrderWorkflow' AND ExecutionStatus = 'Running'"
// - By ID prefix: "WorkflowId STARTS_WITH 'order-'"
```

---

## Feature 5: Stack Trace View (for Debugging)

### Description
Shows the current execution stack trace of a workflow, useful for debugging stuck workflows.

### UI Mockup
```
┌─ Stack Trace ───────────────────────────────────────────────────┐
│                                                       [Refresh] │
├─────────────────────────────────────────────────────────────────┤
│ Coroutine #1 (workflow main):                                   │
│   at OrderWorkflowImpl.processOrder(OrderWorkflowImpl.java:45) →│
│   at Workflow.await(Workflow.java:234)                          │
│   waiting on: Condition (paymentCompleted)                      │
│                                                                 │
│ Coroutine #2 (signal handler: cancelOrder):                     │
│   at OrderWorkflowImpl.cancelOrder(OrderWorkflowImpl.java:78)  →│
│   at Activities.complete(...)                                   │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### Java API Implementation

Uses the built-in `__stack_trace` query:

```kotlin
fun getWorkflowStackTrace(
    stub: WorkflowServiceGrpc.WorkflowServiceBlockingStub,
    namespace: String,
    workflowId: String,
    runId: String? = null
): String {
    val response = queryWorkflow(
        stub = stub,
        namespace = namespace,
        workflowId = workflowId,
        runId = runId,
        queryType = "__stack_trace"
    )

    // Decode the Payloads to String
    return decodePayloads(response.queryResult)
}
```

---

## Implementation Phases

### Phase 1: Foundation (MVP)
**Goal:** Basic workflow visibility

| Feature | Complexity | Priority |
|---------|------------|----------|
| Connection management (reuse existing) | Already done | - |
| Workflow ID input field | Low | P0 |
| Describe Workflow (basic info) | Medium | P0 |
| Pending Activities display | Medium | P0 |
| Manual refresh button | Low | P0 |

### Phase 2: History & Queries
**Goal:** Deep workflow inspection

| Feature | Complexity | Priority |
|---------|------------|----------|
| Event History list | Medium | P1 |
| Event detail expansion | Medium | P1 |
| Query execution panel | Medium | P1 |
| Stack trace query | Low | P1 |

### Phase 3: Workflow Selection
**Goal:** Easy workflow discovery

| Feature | Complexity | Priority |
|---------|------------|----------|
| Recent workflows list | Medium | P2 |
| Workflow type detection from code | High | P2 |
| Search by query | Medium | P2 |

### Phase 4: Polish
**Goal:** Developer experience

| Feature | Complexity | Priority |
|---------|------------|----------|
| Auto-refresh toggle | Low | P3 |
| Event filtering | Medium | P3 |
| Payload decoding (JSON pretty-print) | Medium | P3 |
| Copy to clipboard | Low | P3 |
| Link to code (gutter icons) | High | P3 |

---

## Technical Considerations

### Thread Safety
All gRPC calls must be made off the EDT (Event Dispatch Thread):

```kotlin
ApplicationManager.getApplication().executeOnPooledThread {
    val response = stub.describeWorkflowExecution(request)
    ApplicationManager.getApplication().invokeLater {
        updateUI(response)
    }
}
```

### Connection Pooling
Reuse the same `WorkflowServiceStubs` instance for all operations:

```kotlin
object TemporalServiceManager {
    private var stubs: WorkflowServiceStubs? = null

    fun getStubs(settings: TemporalSettings.State): WorkflowServiceStubs {
        return stubs ?: createStubs(settings).also { stubs = it }
    }

    fun shutdown() {
        stubs?.shutdown()
        stubs = null
    }
}
```

### Error Handling
Handle common gRPC errors gracefully:

```kotlin
try {
    val response = stub.describeWorkflowExecution(request)
} catch (e: StatusRuntimeException) {
    when (e.status.code) {
        Status.Code.NOT_FOUND -> showError("Workflow not found")
        Status.Code.UNAVAILABLE -> showError("Server unavailable")
        Status.Code.DEADLINE_EXCEEDED -> showError("Request timed out")
        else -> showError("Error: ${e.status.description}")
    }
}
```

### Payload Decoding
Use the SDK's DataConverter for payload decoding:

```kotlin
import io.temporal.common.converter.DefaultDataConverter

fun decodePayload(payload: Payload): String {
    return try {
        val converter = DefaultDataConverter.STANDARD_INSTANCE
        converter.fromPayload(payload, String::class.java, String::class.java)
    } catch (e: Exception) {
        // Fallback to raw bytes display
        payload.data.toStringUtf8()
    }
}
```

---

## Dependencies

Add to `build.gradle.kts`:

```kotlin
dependencies {
    // Temporal SDK (already included for connection testing)
    implementation("io.temporal:temporal-sdk:1.x.x")

    // JSON formatting for payload display
    implementation("com.google.code.gson:gson:2.10.1")
}
```

---

## Future Enhancements (Out of Scope for MVP)

- **Gutter icons** on `@WorkflowMethod` to launch/view workflows
- **Code navigation** from events to source code
- **Real-time updates** via long-polling or streaming
- **Multiple workflow tabs** for comparing executions
- **Export history** to JSON for SDK replay testing
- **Integration with debugger** for setting breakpoints on signals/queries
