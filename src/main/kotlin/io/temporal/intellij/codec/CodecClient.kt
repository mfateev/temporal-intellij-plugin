package io.temporal.intellij.codec

import com.google.protobuf.util.JsonFormat
import io.temporal.api.common.v1.Payload
import io.temporal.api.common.v1.Payloads
import io.temporal.intellij.settings.TemporalSettings
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Client for communicating with a remote Codec Server to decode encrypted/compressed payloads.
 *
 * Protocol:
 * - POST to {endpoint}/decode
 * - Content-Type: application/json
 * - X-Namespace: {namespace}
 * - Body: Proto3 JSON serialization of Payloads
 * - Response: Proto3 JSON serialization of decoded Payloads
 */
class CodecClient(private val settings: TemporalSettings.State) {

    companion object {
        private const val DECODE_PATH = "/decode"
        private const val ENCODE_PATH = "/encode"
        private const val CONTENT_TYPE = "application/json"
        private val TIMEOUT = Duration.ofSeconds(30)

        private val jsonParser: JsonFormat.Parser = JsonFormat.parser()
        private val jsonPrinter: JsonFormat.Printer = JsonFormat.printer()
    }

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(TIMEOUT)
        .build()

    /**
     * Check if codec server is configured.
     */
    fun isConfigured(): Boolean {
        return settings.codecEndpoint.isNotBlank()
    }

    /**
     * Decode a list of payloads using the remote codec server.
     *
     * @param payloads List of payloads to decode
     * @return Result containing decoded payloads or error
     */
    fun decode(payloads: List<Payload>): Result<List<Payload>> {
        if (!isConfigured()) {
            return Result.success(payloads) // Return as-is if no codec configured
        }
        return transform(payloads, DECODE_PATH)
    }

    /**
     * Decode a single payload using the remote codec server.
     *
     * @param payload Payload to decode
     * @return Result containing decoded payload or error
     */
    fun decode(payload: Payload): Result<Payload> {
        return decode(listOf(payload)).map { it.firstOrNull() ?: payload }
    }

    /**
     * Decode a Payloads message using the remote codec server.
     *
     * @param payloads Payloads message to decode
     * @return Result containing decoded Payloads or error
     */
    fun decode(payloads: Payloads): Result<Payloads> {
        return decode(payloads.payloadsList).map { decoded ->
            Payloads.newBuilder().addAllPayloads(decoded).build()
        }
    }

    /**
     * Encode a list of payloads using the remote codec server.
     *
     * @param payloads List of payloads to encode
     * @return Result containing encoded payloads or error
     */
    fun encode(payloads: List<Payload>): Result<List<Payload>> {
        if (!isConfigured()) {
            return Result.success(payloads) // Return as-is if no codec configured
        }
        return transform(payloads, ENCODE_PATH)
    }

    private fun transform(payloads: List<Payload>, path: String): Result<List<Payload>> {
        return try {
            val endpoint = settings.codecEndpoint.trimEnd('/')
            val url = endpoint + path

            // Build the request body as Proto3 JSON
            val payloadsProto = Payloads.newBuilder().addAllPayloads(payloads).build()
            val jsonBody = jsonPrinter.print(payloadsProto)

            // Build HTTP request
            val requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(TIMEOUT)
                .header("Content-Type", CONTENT_TYPE)
                .header("X-Namespace", settings.namespace)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))

            // Add authorization header if configured
            if (settings.codecAuth.isNotBlank()) {
                requestBuilder.header("Authorization", settings.codecAuth)
            }

            // Add custom headers
            for (header in settings.codecHeaders) {
                val parts = header.split("=", limit = 2)
                if (parts.size == 2) {
                    requestBuilder.header(parts[0].trim(), parts[1].trim())
                }
            }

            val request = requestBuilder.build()

            // Execute request
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() != 200) {
                return Result.failure(CodecException(
                    "Codec server returned status ${response.statusCode()}: ${response.body()}"
                ))
            }

            // Parse response
            val responseBuilder = Payloads.newBuilder()
            jsonParser.merge(response.body(), responseBuilder)

            Result.success(responseBuilder.build().payloadsList)
        } catch (e: Exception) {
            Result.failure(CodecException("Failed to communicate with codec server: ${e.message}", e))
        }
    }

    /**
     * Test connection to the codec server by sending an empty decode request.
     *
     * @return Result indicating success or failure with error message
     */
    fun testConnection(): Result<String> {
        if (!isConfigured()) {
            return Result.failure(CodecException("Codec server endpoint not configured"))
        }

        return try {
            val endpoint = settings.codecEndpoint.trimEnd('/')
            val url = endpoint + DECODE_PATH

            // Send empty payloads to test connectivity
            val emptyPayloads = Payloads.getDefaultInstance()
            val jsonBody = jsonPrinter.print(emptyPayloads)

            val requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", CONTENT_TYPE)
                .header("X-Namespace", settings.namespace)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))

            if (settings.codecAuth.isNotBlank()) {
                requestBuilder.header("Authorization", settings.codecAuth)
            }

            for (header in settings.codecHeaders) {
                val parts = header.split("=", limit = 2)
                if (parts.size == 2) {
                    requestBuilder.header(parts[0].trim(), parts[1].trim())
                }
            }

            val request = requestBuilder.build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() == 200) {
                Result.success("Connected to codec server at $endpoint")
            } else {
                Result.failure(CodecException(
                    "Codec server returned status ${response.statusCode()}"
                ))
            }
        } catch (e: Exception) {
            Result.failure(CodecException("Failed to connect to codec server: ${e.message}", e))
        }
    }
}

/**
 * Exception thrown when codec operations fail.
 */
class CodecException(message: String, cause: Throwable? = null) : Exception(message, cause)
