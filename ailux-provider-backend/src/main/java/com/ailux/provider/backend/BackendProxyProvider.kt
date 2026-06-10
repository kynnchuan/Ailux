package com.ailux.provider.backend

import com.ailux.core.LLMProvider
import com.ailux.core.error.ErrorCode
import com.ailux.core.error.LLMError
import com.ailux.core.error.LLMException
import com.ailux.core.request.LLMRequest
import com.ailux.core.response.LLMResponse
import com.ailux.core.event.LLMEvent
import com.ailux.core.response.UsageInfo
import com.ailux.provider.backend.config.BackendProxyConfig
import com.ailux.provider.backend.mapper.DefaultErrorMapper
import com.ailux.provider.backend.mapper.DefaultRequestMapper
import com.ailux.provider.backend.mapper.ErrorMapper
import com.ailux.provider.backend.mapper.RequestMapper
import com.ailux.provider.backend.parser.OpenAIStreamResponseParser
import com.ailux.provider.backend.parser.StreamResponseParser
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Backend Proxy Provider: integrates with an LLM through the company's own Backend Proxy.
 *
 * This is the default Provider implementation in Ailux SDK v0.1. Its design philosophy:
 * **unify the LLM calling experience on the Android client side, without forcing
 * a uniform server-side protocol on the company.**
 *
 * Streaming generation is implemented with OkHttp SSE (Server-Sent Events);
 * non-streaming generation uses a standard HTTP POST.
 *
 * ## Three-tier Integration Modes
 *
 * 1. **Default recommended protocol**: pass nothing custom — speak the SSE protocol
 *    recommended by Ailux out of the box.
 * 2. **Configurable mappers**: adapt to small differences via [BackendProxyConfig]'s
 *    `requestMapper` / `streamResponseParser` / `errorMapper`.
 * 3. **Fully custom Adapter**: planned for v0.2+; not implemented in v0.1.
 *
 * ## Usage
 *
 * ```kotlin
 * val provider = BackendProxyProvider(
 *     config = BackendProxyConfig(
 *         baseUrl = "https://api.company.com",
 *         authProvider = AuthProvider { "Bearer ${getToken()}" },
 *     )
 * )
 *
 * val ailuxConfig = AiluxConfig.Builder()
 *     .setProvider(provider)
 *     .build()
 * ```
 *
 * @param config Backend proxy configuration.
 */
class BackendProxyProvider(
    private val config: BackendProxyConfig,
) : LLMProvider {

    /** Resolved extension components (fall back to defaults when null). */
    private val requestMapper: RequestMapper = config.requestMapper ?: DefaultRequestMapper()
    private val errorMapper: ErrorMapper = config.errorMapper ?: DefaultErrorMapper()

    /**
     * Creates a fresh [StreamResponseParser] for each streaming request.
     *
     * Built-in parsers (OpenAI, Anthropic) are stateful — they accumulate tool call
     * deltas internally. A new instance per request prevents state leakage between requests.
     *
     * When the user provides a custom parser via [BackendProxyConfig.streamResponseParser]:
     * - **Stateless parsers** (e.g. a SAM lambda): safe to reuse the same instance.
     * - **Stateful parsers**: the user is responsible for state management, or should
     *   provide a factory-style config (planned for a future version).
     */
    private fun createParser(): StreamResponseParser {
        return config.streamResponseParser ?: OpenAIStreamResponseParser()
    }

    /** JSON parser (used for non-streaming response parsing). */
    private val json = Json { ignoreUnknownKeys = true }

    /** Shared OkHttpClient instance, built using the timeouts defined in config. */
    private val httpClient: OkHttpClient by lazy { buildHttpClient() }

    /** SSE EventSource factory. */
    private val eventSourceFactory: EventSource.Factory by lazy {
        EventSources.createFactory(httpClient)
    }

    // ──────────────────────────────────────────
    // LLMProvider interface implementation
    // ──────────────────────────────────────────

    /**
     * Streaming generation: emits a token-by-token event stream over SSE.
     *
     * Internal flow:
     * 1. Build the HTTP request (POST + SSE headers + auth).
     * 2. Bridge OkHttp SSE callbacks to a Kotlin Flow via [callbackFlow].
     * 3. SSE `onEvent` → [StreamResponseParser.parse] → emit [LLMEvent].
     * 4. SSE `onFailure` → [ErrorMapper.map] → emit [LLMEvent.Error] + [LLMEvent.Done].
     * 5. Cancelling the coroutine automatically closes the SSE connection.
     *
     * If [BackendProxyConfig.retryCount] > 0, retriable errors are retried automatically.
     */
    override fun streamGenerate(request: LLMRequest): Flow<LLMEvent> {
        val baseFlow = callbackFlow {
            val httpRequest = buildStreamRequest(request)
            val responseParser = createParser()

            val eventSource = eventSourceFactory.newEventSource(
                httpRequest,
                object : EventSourceListener() {

                    override fun onOpen(eventSource: EventSource, response: Response) {
                        trySendBlocking(LLMEvent.Connected)
                    }

                    override fun onEvent(
                        eventSource: EventSource,
                        id: String?,
                        type: String?,
                        data: String,
                    ) {
                        val eventType = type ?: "message"
                        val events = responseParser.parse(eventType, data)
                        if (events.isEmpty()) return

                        for (event in events) {
                            trySendBlocking(event)
                            // Close the Flow after a Done event
                            if (event is LLMEvent.Done) {
                                close()
                                return
                            }
                        }
                    }

                    override fun onFailure(
                        eventSource: EventSource,
                        t: Throwable?,
                        response: Response?,
                    ) {
                        // OkHttp also calls onFailure(t=null) on a clean close
                        if (t == null && response == null) {
                            close()
                            return
                        }

                        val error = errorMapper.map(
                            throwable = t,
                            httpCode = response?.code,
                            responseBody = runCatching { response?.body?.string() }.getOrNull(),
                        )

                        trySendBlocking(LLMEvent.Error(error))
                        trySendBlocking(LLMEvent.Done())

                        // For retriable errors, surface an exception so retryWhen can fire
                        if (error.isRetriable) {
                            close(BackendRetriableException(error))
                        } else {
                            close()
                        }
                    }

                    override fun onClosed(eventSource: EventSource) {
                        close()
                    }
                },
            )

            awaitClose {
                eventSource.cancel()
            }
        }

        // Apply the retry policy
        return if (config.retryCount > 0) {
            var attempt = 0
            baseFlow.retryWhen { cause, _ ->
                if (cause is BackendRetriableException && attempt < config.retryCount) {
                    attempt++
                    true
                } else {
                    false
                }
            }
        } else {
            baseFlow
        }
    }

    /**
     * Non-streaming generation: sends an HTTP POST and waits for the full response.
     *
     * @throws LLMException when the HTTP request fails or the response cannot be parsed.
     */
    override suspend fun generate(request: LLMRequest): LLMResponse {
        val httpRequest = buildGenerateRequest(request)

        return suspendCancellableCoroutine { continuation ->
            val call = httpClient.newCall(httpRequest)

            continuation.invokeOnCancellation {
                call.cancel()
            }

            try {
                val response = call.execute()

                if (!response.isSuccessful) {
                    val body = response.body?.string()
                    val error = errorMapper.map(
                        throwable = null,
                        httpCode = response.code,
                        responseBody = body,
                    )
                    continuation.resumeWithException(LLMException(error))
                    return@suspendCancellableCoroutine
                }

                val body = response.body?.string()
                    ?: throw LLMException(
                        LLMError(
                            code = ErrorCode.UNKNOWN,
                            message = "Empty response body",
                        )
                    )

                val llmResponse = parseGenerateResponse(body)
                continuation.resume(llmResponse)
            } catch (e: LLMException) {
                continuation.resumeWithException(e)
            } catch (e: Exception) {
                val error = errorMapper.map(
                    throwable = e,
                    httpCode = null,
                    responseBody = null,
                )
                continuation.resumeWithException(LLMException(error))
            }
        }
    }

    // ──────────────────────────────────────────
    // Internal helpers
    // ──────────────────────────────────────────

    /**
     * Builds the HTTP Request for streaming.
     */
    private suspend fun buildStreamRequest(request: LLMRequest): Request {
        val jsonBody = requestMapper.map(request, stream = true)
        return buildBaseRequest(config.streamEndpoint, jsonBody)
    }

    /**
     * Builds the HTTP Request for non-streaming.
     */
    private suspend fun buildGenerateRequest(request: LLMRequest): Request {
        val jsonBody = requestMapper.map(request, stream = false)
        return buildBaseRequest(config.generateEndpoint, jsonBody)
    }

    /**
     * Builds the base HTTP Request (shared logic).
     */
    private suspend fun buildBaseRequest(endpoint: String, jsonBody: String): Request {
        val url = "${config.baseUrl}$endpoint"

        val builder = Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")

        // Auth header
        config.authProvider?.let { auth ->
            val authHeaderValue = auth.getAuthToken()
            if (authHeaderValue.isNotBlank()) {
                builder.header("Authorization", authHeaderValue)
            }
        }

        // Custom headers
        config.headers.forEach { (key, value) ->
            builder.header(key, value)
        }

        return builder.build()
    }

    /**
     * Parses the non-streaming response JSON into an [LLMResponse].
     *
     * Expected format:
     * ```json
     * {
     *   "text": "Hi there! Happy to help. What can I do for you?",
     *   "model": "gpt-4",
     *   "usage": {"inputTokens": 12, "outputTokens": 20}
     * }
     * ```
     */
    private fun parseGenerateResponse(body: String): LLMResponse {
        return try {
            val jsonObj = json.parseToJsonElement(body).jsonObject
            val text = jsonObj["text"]?.jsonPrimitive?.content ?: ""
            val model = jsonObj["model"]?.jsonPrimitive?.content

            val usage = jsonObj["usage"]?.let { usageElement ->
                val usageObj = usageElement.jsonObject
                UsageInfo(
                    inputTokens = usageObj["inputTokens"]?.jsonPrimitive?.content?.toIntOrNull()
                        ?: 0,
                    outputTokens = usageObj["outputTokens"]?.jsonPrimitive?.content?.toIntOrNull()
                        ?: 0,
                )
            }

            LLMResponse(text = text, usage = usage, model = model)
        } catch (e: Exception) {
            // JSON parsing failed; treat the raw body as a plain-text response
            LLMResponse(text = body)
        }
    }

    /**
     * Builds the OkHttpClient with the configured timeouts.
     */
    private fun buildHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(config.connectTimeoutMillis, TimeUnit.MILLISECONDS)
            .readTimeout(config.readTimeoutMillis, TimeUnit.MILLISECONDS)
            .apply {
                if (config.callTimeoutMillis > 0) {
                    callTimeout(config.callTimeoutMillis, TimeUnit.MILLISECONDS)
                }
            }
            .build()
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

// ──────────────────────────────────────────
// Auxiliary types
// ──────────────────────────────────────────

/**
 * Internal marker exception: a retriable error was encountered in the SSE stream.
 *
 * Used solely to trigger the Flow's [retryWhen] — not exposed externally.
 */
internal class BackendRetriableException(
    val error: LLMError,
) : Exception(error.message, error.cause)
