package io.temporal.intellij.history

import com.google.protobuf.ByteString
import io.grpc.Deadline
import io.temporal.api.common.v1.WorkflowExecution
import io.temporal.api.enums.v1.EventType
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryRequest
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryResponse
import io.temporal.api.workflowservice.v1.TerminateWorkflowExecutionRequest
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowOptions
import io.temporal.client.WorkflowStub
import io.temporal.serviceclient.WorkflowServiceStubs
import io.temporal.serviceclient.WorkflowServiceStubsOptions
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Test to validate history fetching behavior with long poll and short poll modes.
 */
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class HistoryIteratorPollModeTest {

    companion object {
        private const val NAMESPACE = "default"
        private const val TASK_QUEUE = "test-history-poll-queue"
    }

    private lateinit var serviceStubs: WorkflowServiceStubs
    private lateinit var client: WorkflowClient
    private var execution: WorkflowExecution? = null

    @BeforeEach
    fun setUp() {
        serviceStubs = WorkflowServiceStubs.newServiceStubs(
            WorkflowServiceStubsOptions.newBuilder()
                .setTarget("localhost:7233")
                .build()
        )
        client = WorkflowClient.newInstance(serviceStubs)
    }

    @AfterEach
    fun tearDown() {
        execution?.let { exec ->
            try {
                serviceStubs.blockingStub().terminateWorkflowExecution(
                    TerminateWorkflowExecutionRequest.newBuilder()
                        .setNamespace(NAMESPACE)
                        .setWorkflowExecution(exec)
                        .setReason("test cleanup")
                        .build()
                )
            } catch (e: Exception) {
                // Workflow may already be terminated
            }
        }
        serviceStubs.shutdown()
    }

    @Test
    @Timeout(300)
    fun testOnlyShortPolls() {
        val workflowId = "test-short-poll-${UUID.randomUUID()}"
        val stub = client.newUntypedWorkflowStub(
            "NonExistentWorkflow",
            WorkflowOptions.newBuilder()
                .setWorkflowId(workflowId)
                .setTaskQueue(TASK_QUEUE)
                .build()
        )

        execution = stub.start()
        println("Started workflow: $workflowId")

        // Send 2000 signals to force pagination
        println("\nSending 2000 signals...")
        for (i in 1..2000) {
            stub.signal("testSignal", "signal-$i")
            if (i % 500 == 0) println("  Sent $i signals")
        }
        println("Done sending signals")

        // === Short poll 1: Get first page ===
        println("\n=== Short poll 1 ===")
        val response1 = getHistory(ByteString.EMPTY, waitNewEvent = false)
        val eventIds1 = response1.history.eventsList.map { it.eventId }
        println("Short poll 1 returned ${response1.history.eventsCount} events: ${eventIds1.take(5)}...${eventIds1.takeLast(5)}")
        val token1 = response1.nextPageToken
        println("Token 1: ${token1.size()} bytes")

        // === Short poll 2: Use token1 ===
        println("\n=== Short poll 2 (with token1) ===")
        val response2 = getHistory(token1, waitNewEvent = false)
        val eventIds2 = response2.history.eventsList.map { it.eventId }
        println("Short poll 2 returned ${response2.history.eventsCount} events: ${eventIds2.take(5)}...${eventIds2.takeLast(5)}")
        val token2 = response2.nextPageToken
        println("Token 2: ${token2.size()} bytes")

        // === Short poll 3: Use token2 ===
        println("\n=== Short poll 3 (with token2) ===")
        val response3 = getHistory(token2, waitNewEvent = false)
        val eventIds3 = response3.history.eventsList.map { it.eventId }
        println("Short poll 3 returned ${response3.history.eventsCount} events: ${eventIds3.take(5)}...${eventIds3.takeLast(5)}")
        val token3 = response3.nextPageToken
        println("Token 3: ${token3.size()} bytes")

        // Send one more signal
        println("\nSending signal 2001...")
        stub.signal("testSignal", "signal-2001")

        // === Short poll 4: Use token3 ===
        println("\n=== Short poll 4 (with token3) ===")
        val response4 = getHistory(token3, waitNewEvent = false)
        val eventIds4 = response4.history.eventsList.map { it.eventId }
        println("Short poll 4 returned ${response4.history.eventsCount} events: ${eventIds4.take(5)}...${eventIds4.takeLast(5)}")
        val token4 = response4.nextPageToken
        println("Token 4: ${token4.size()} bytes")

        // Terminate workflow
        println("\nTerminating workflow...")
        val execToTerminate = execution!!
        serviceStubs.blockingStub().terminateWorkflowExecution(
            TerminateWorkflowExecutionRequest.newBuilder()
                .setNamespace(NAMESPACE)
                .setWorkflowExecution(execToTerminate)
                .setReason("test termination")
                .build()
        )
        execution = null

        // === Short poll 5: Use token4 ===
        println("\n=== Short poll 5 (with token4) ===")
        val response5 = getHistory(execToTerminate, token4, waitNewEvent = false)
        val eventIds5 = response5.history.eventsList.map { it.eventId }
        println("Short poll 5 returned ${response5.history.eventsCount} events: ${eventIds5.take(5)}...${eventIds5.takeLast(5)}")
        val token5 = response5.nextPageToken
        println("Token 5: ${token5.size()} bytes")

        // Count total events by paginating through
        println("\n=== Counting all events ===")
        var totalEvents = 0
        var token = ByteString.EMPTY
        var pages = 0
        do {
            val page = getHistory(execToTerminate, token, waitNewEvent = false)
            totalEvents += page.history.eventsCount
            token = page.nextPageToken
            pages++
        } while (!token.isEmpty)
        println("Total: $totalEvents events in $pages pages")

        assertTrue(totalEvents > 2000, "Should have more than 2000 events")
    }

    @Test
    @Timeout(120)
    fun testLongPollThenShortPoll() {
        val workflowId = "test-mixed-poll-${UUID.randomUUID()}"
        val stub = client.newUntypedWorkflowStub(
            "NonExistentWorkflow",
            WorkflowOptions.newBuilder()
                .setWorkflowId(workflowId)
                .setTaskQueue(TASK_QUEUE)
                .build()
        )

        execution = stub.start()
        println("Started workflow: $workflowId")

        // === Long poll 1: Wait for signal 1 ===
        println("\n=== Long poll 1 (waiting for signal 1) ===")
        val longPollStarted = CountDownLatch(1)
        val longPollResult = AtomicReference<GetWorkflowExecutionHistoryResponse>()

        val longPollThread = Thread {
            longPollStarted.countDown()
            longPollResult.set(getHistory(ByteString.EMPTY, waitNewEvent = true))
        }
        longPollThread.start()

        assertTrue(longPollStarted.await(5, TimeUnit.SECONDS))
        Thread.sleep(500)

        println("Sending signal 1...")
        stub.signal("testSignal", "signal-1")

        longPollThread.join(10_000)
        val response1 = longPollResult.get()!!
        val eventIds1 = response1.history.eventsList.map { it.eventId }
        println("Long poll 1 returned ${response1.history.eventsCount} events: $eventIds1")
        val longPollToken = response1.nextPageToken
        println("Long poll token: ${longPollToken.size()} bytes")

        // === Send 500 signals to force pagination ===
        println("\n=== Sending 500 signals ===")
        for (i in 2..501) {
            stub.signal("testSignal", "signal-$i")
            if (i % 100 == 0) println("  Sent $i signals")
        }
        println("Done sending signals")

        // === Short poll with long poll token ===
        println("\n=== Short poll with long poll token ===")
        val response2 = getHistory(longPollToken, waitNewEvent = false)
        val eventIds2 = response2.history.eventsList.map { it.eventId }
        println("Short poll returned ${response2.history.eventsCount} events: ${eventIds2.take(5)}...${eventIds2.takeLast(5)}")
        val token2 = response2.nextPageToken
        println("Token 2: ${token2.size()} bytes")

        // === Continue with short polls ===
        if (!token2.isEmpty) {
            println("\n=== Short poll 2 (with token2) ===")
            val response3 = getHistory(token2, waitNewEvent = false)
            val eventIds3 = response3.history.eventsList.map { it.eventId }
            println("Short poll 2 returned ${response3.history.eventsCount} events: ${eventIds3.take(5)}...${eventIds3.takeLast(5)}")
            val token3 = response3.nextPageToken
            println("Token 3: ${token3.size()} bytes")
        }

        // Count total events
        println("\n=== Counting all events ===")
        var totalEvents = 0
        var token = ByteString.EMPTY
        var pages = 0
        do {
            val page = getHistory(token, waitNewEvent = false)
            totalEvents += page.history.eventsCount
            token = page.nextPageToken
            pages++
        } while (!token.isEmpty)
        println("Total: $totalEvents events in $pages pages")

        assertTrue(totalEvents > 500, "Should have more than 500 events")
    }

    @Test
    @Timeout(120)
    fun testOnlyLongPolls() {
        val workflowId = "test-long-poll-${UUID.randomUUID()}"
        val stub = client.newUntypedWorkflowStub(
            "NonExistentWorkflow",
            WorkflowOptions.newBuilder()
                .setWorkflowId(workflowId)
                .setTaskQueue(TASK_QUEUE)
                .build()
        )

        execution = stub.start()
        println("Started workflow: $workflowId")

        // === Long poll 1: Wait for signal 1 ===
        println("\n=== Long poll 1 (waiting for signal 1) ===")
        val longPoll1Started = CountDownLatch(1)
        val longPoll1Result = AtomicReference<GetWorkflowExecutionHistoryResponse>()

        val longPoll1Thread = Thread {
            longPoll1Started.countDown()
            longPoll1Result.set(getHistory(ByteString.EMPTY, waitNewEvent = true))
        }
        longPoll1Thread.start()

        assertTrue(longPoll1Started.await(5, TimeUnit.SECONDS))
        Thread.sleep(500)

        println("Sending signal 1...")
        stub.signal("testSignal", "signal-1")

        longPoll1Thread.join(10_000)
        val response1 = longPoll1Result.get()!!
        val eventIds1 = response1.history.eventsList.map { it.eventId }
        println("Long poll 1 returned ${response1.history.eventsCount} events: $eventIds1")
        val token1 = response1.nextPageToken
        println("Token 1: ${token1.size()} bytes")

        // === Long poll 2: Use token1, wait for signal 2 ===
        println("\n=== Long poll 2 (with token1, waiting for signal 2) ===")
        val longPoll2Started = CountDownLatch(1)
        val longPoll2Result = AtomicReference<GetWorkflowExecutionHistoryResponse>()

        val longPoll2Thread = Thread {
            longPoll2Started.countDown()
            longPoll2Result.set(getHistory(token1, waitNewEvent = true))
        }
        longPoll2Thread.start()

        assertTrue(longPoll2Started.await(5, TimeUnit.SECONDS))
        Thread.sleep(500)

        println("Sending signal 2...")
        stub.signal("testSignal", "signal-2")

        longPoll2Thread.join(10_000)
        val response2 = longPoll2Result.get()!!
        val eventIds2 = response2.history.eventsList.map { it.eventId }
        println("Long poll 2 returned ${response2.history.eventsCount} events: $eventIds2")
        val token2 = response2.nextPageToken
        println("Token 2: ${token2.size()} bytes")

        // === Long poll 3: Use token2, wait for signal 3 ===
        println("\n=== Long poll 3 (with token2, waiting for signal 3) ===")
        val longPoll3Started = CountDownLatch(1)
        val longPoll3Result = AtomicReference<GetWorkflowExecutionHistoryResponse>()

        val longPoll3Thread = Thread {
            longPoll3Started.countDown()
            longPoll3Result.set(getHistory(token2, waitNewEvent = true))
        }
        longPoll3Thread.start()

        assertTrue(longPoll3Started.await(5, TimeUnit.SECONDS))
        Thread.sleep(500)

        println("Sending signal 3...")
        stub.signal("testSignal", "signal-3")

        longPoll3Thread.join(10_000)
        val response3 = longPoll3Result.get()!!
        val eventIds3 = response3.history.eventsList.map { it.eventId }
        println("Long poll 3 returned ${response3.history.eventsCount} events: $eventIds3")
        val token3 = response3.nextPageToken
        println("Token 3: ${token3.size()} bytes")

        // === Long poll 4: Use token3, wait for termination ===
        println("\n=== Long poll 4 (with token3, waiting for termination) ===")
        val longPoll4Started = CountDownLatch(1)
        val longPoll4Result = AtomicReference<GetWorkflowExecutionHistoryResponse>()

        val execToTerminate = execution!!

        val longPoll4Thread = Thread {
            longPoll4Started.countDown()
            longPoll4Result.set(getHistory(execToTerminate, token3, waitNewEvent = true))
        }
        longPoll4Thread.start()

        assertTrue(longPoll4Started.await(5, TimeUnit.SECONDS))
        Thread.sleep(500)

        println("Terminating workflow...")
        serviceStubs.blockingStub().terminateWorkflowExecution(
            TerminateWorkflowExecutionRequest.newBuilder()
                .setNamespace(NAMESPACE)
                .setWorkflowExecution(execToTerminate)
                .setReason("test termination")
                .build()
        )
        execution = null

        longPoll4Thread.join(10_000)
        val response4 = longPoll4Result.get()!!
        val eventIds4 = response4.history.eventsList.map { it.eventId }
        println("Long poll 4 returned ${response4.history.eventsCount} events: $eventIds4")
        response4.history.eventsList.forEach { event ->
            println("  - Event ${event.eventId}: ${event.eventType}")
        }

        // Fetch full history to verify
        println("\n=== Full history ===")
        val fullHistory = getHistory(execToTerminate, ByteString.EMPTY, waitNewEvent = false)
        fullHistory.history.eventsList.forEach { event ->
            println("  - Event ${event.eventId}: ${event.eventType}")
        }

        val hasTerminated = fullHistory.history.eventsList.any {
            it.eventType == EventType.EVENT_TYPE_WORKFLOW_EXECUTION_TERMINATED
        }
        assertTrue(hasTerminated, "Should have terminated event")
    }

    private fun getHistory(
        nextPageToken: ByteString,
        waitNewEvent: Boolean
    ): GetWorkflowExecutionHistoryResponse {
        return getHistory(execution!!, nextPageToken, waitNewEvent)
    }

    private fun getHistory(
        exec: WorkflowExecution,
        nextPageToken: ByteString,
        waitNewEvent: Boolean
    ): GetWorkflowExecutionHistoryResponse {
        val builder = GetWorkflowExecutionHistoryRequest.newBuilder()
            .setNamespace(NAMESPACE)
            .setExecution(exec)
            .setNextPageToken(nextPageToken)
            .setWaitNewEvent(waitNewEvent)

        return serviceStubs.blockingStub()
            .withDeadline(Deadline.after(30, TimeUnit.SECONDS))
            .getWorkflowExecutionHistory(builder.build())
    }
}
