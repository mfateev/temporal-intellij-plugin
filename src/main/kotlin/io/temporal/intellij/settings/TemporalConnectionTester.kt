package io.temporal.intellij.settings

import io.temporal.serviceclient.WorkflowServiceStubs
import io.temporal.serviceclient.WorkflowServiceStubsOptions
import java.time.Duration

object TemporalConnectionTester {

    data class ConnectionResult(
        val success: Boolean,
        val message: String,
        val serverInfo: String? = null
    )

    fun testConnection(settings: TemporalSettings.State): ConnectionResult {
        return try {
            val optionsBuilder = WorkflowServiceStubsOptions.newBuilder()
                .setTarget(settings.address)

            // Configure TLS if enabled
            if (settings.tlsEnabled) {
                optionsBuilder.setEnableHttps(true)
                // Note: For full TLS with client certs, additional SSL context setup would be needed
            }

            // Set connection timeout
            optionsBuilder.setRpcTimeout(Duration.ofSeconds(10))

            val serviceStubs = WorkflowServiceStubs.newServiceStubs(optionsBuilder.build())

            try {
                // Try to get system info to verify connection
                val response = serviceStubs.blockingStub()
                    .getSystemInfo(io.temporal.api.workflowservice.v1.GetSystemInfoRequest.getDefaultInstance())

                val serverVersion = response.serverVersion
                val capabilities = response.capabilities

                serviceStubs.shutdown()

                ConnectionResult(
                    success = true,
                    message = "Successfully connected to Temporal server",
                    serverInfo = "Server version: $serverVersion"
                )
            } catch (e: Exception) {
                serviceStubs.shutdown()
                throw e
            }
        } catch (e: io.grpc.StatusRuntimeException) {
            val status = e.status
            when (status.code) {
                io.grpc.Status.Code.UNAVAILABLE ->
                    ConnectionResult(false, "Server unavailable: ${status.description ?: "Cannot reach server at ${settings.address}"}")
                io.grpc.Status.Code.UNAUTHENTICATED ->
                    ConnectionResult(false, "Authentication failed: ${status.description ?: "Invalid credentials"}")
                io.grpc.Status.Code.PERMISSION_DENIED ->
                    ConnectionResult(false, "Permission denied: ${status.description ?: "Access not allowed"}")
                io.grpc.Status.Code.DEADLINE_EXCEEDED ->
                    ConnectionResult(false, "Connection timeout: Server did not respond in time")
                else ->
                    ConnectionResult(false, "Connection failed: ${status.code} - ${status.description ?: e.message}")
            }
        } catch (e: Exception) {
            ConnectionResult(false, "Connection failed: ${e.message ?: "Unknown error"}")
        }
    }
}
