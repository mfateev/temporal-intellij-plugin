package io.temporal.intellij.workflow

import com.google.protobuf.ByteString
import io.temporal.api.history.v1.HistoryEvent
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryResponse

/**
 * Iterator for workflow history events that handles pagination and long polling.
 *
 * This is a port of the Go SDK's historyEventIteratorImpl.
 *
 * With isLongPoll=true:
 * - The server returns events immediately if there are events to paginate
 * - When caught up, the server blocks waiting for new events
 * - Returns empty nextPageToken only when workflow completes
 */
class HistoryEventIterator(
    initialToken: ByteString = ByteString.EMPTY,
    private val paginate: (nextToken: ByteString) -> GetWorkflowExecutionHistoryResponse
) {
    // Whether this iterator has fetched at least once
    private var initialized = false

    // Local cached history events and corresponding consuming index
    private var nextEventIndex = 0
    private var events: List<HistoryEvent> = emptyList()

    // Token to get next page of history events
    private var nextToken: ByteString = initialToken

    /**
     * Get the current next page token (for saving state).
     */
    fun getNextToken(): ByteString = nextToken

    // Error when getting next page of history events
    private var error: Exception? = null

    /**
     * Returns true if there are more events to iterate.
     * This may block if long polling is enabled and waiting for new events.
     */
    fun hasNext(): Boolean {
        if (nextEventIndex < events.size || error != null) {
            return true
        } else if (!initialized || !nextToken.isEmpty) {
            initialized = true
            try {
                val response = paginate(nextToken)
                nextEventIndex = 0
                events = response.history.eventsList
                nextToken = response.nextPageToken
                error = null
            } catch (e: Exception) {
                nextEventIndex = 0
                events = emptyList()
                // Don't clear nextToken on error - preserve it so we can resume from where we left off
                error = e
            }

            if (nextEventIndex < events.size || error != null) {
                return true
            }
            return false
        }

        return false
    }

    /**
     * Returns the next history event.
     * @throws IllegalStateException if called without checking hasNext() first
     * @throws Exception if there was an error fetching events
     */
    fun next(): HistoryEvent {
        if (!hasNext()) {
            throw IllegalStateException("HistoryEventIterator next() called without checking hasNext()")
        }

        // We have cached events
        if (nextEventIndex < events.size) {
            val index = nextEventIndex
            nextEventIndex++
            return events[index]
        } else if (error != null) {
            // We have an error, clear it and throw
            val err = error!!
            error = null
            throw err
        }

        throw IllegalStateException("HistoryEventIterator next() should return either a history event or throw an error")
    }
}
