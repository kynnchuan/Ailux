package com.ailux.provider.backend

import com.ailux.core.LLMProvider
import com.ailux.core.error.ErrorCode
import com.ailux.core.error.LLMError
import com.ailux.core.error.LLMException
import com.ailux.core.request.LLMRequest
import com.ailux.core.response.LLMResponse
import com.ailux.core.event.LLMEvent
import com.ailux.provider.backend.config.BackendProxyConfig
import com.ailux.provider.backend.config.RetryPolicy
import com.ailux.provider.backend.mapper.DefaultErrorMapper
import com.ailux.provider.backend.mapper.OpenAIRequestMapper
import com.ailux.provider.backend.mapper.ErrorMapper
import com.ailux.provider.backend.mapper.RequestMapper
import com.ailux.provider.backend.parser.stream.OpenAIStreamResponseParser
import com.ailux.provider.backend.parser.stream.StreamResponseParser
import com.ailux.provider.backend.parser.nonstream.NonStreamResponseParser
import com.ailux.provider.backend.parser.nonstream.OpenAINonStreamResponseParser
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
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
    private val requestMapper: RequestMapper = config.requestMapper ?: OpenAIRequestMapper()
    private val errorMapper: ErrorMapper = config.errorMapper ?: DefaultErrorMapper()

    /**
     * Parser for non-streaming (`/chat/completions`-style) responses.
     *
     * Unlike [StreamResponseParser] — which is recreated per request because
     * built-in implementations accumulate streaming tool-call state — the
     * non-streaming parser is **stateless by contract** and safely shared
     * across concurrent requests. Falls back to
     * [OpenAINonStreamResponseParser] (OpenAI Chat Completions schema) when
     * [BackendProxyConfig.nonStreamResponseParser] is `null`.
     */
    private val nonStreamResponseParser: NonStreamResponseParser =
        config.nonStreamResponseParser ?: OpenAINonStreamResponseParser()

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
     * 5. Cancelling the coroutine automatically closes the SSE connection via
     *    `awaitClose { eventSource.cancel() }`.
     *
     * ## Cancellation & Billing Boundary
     *
     * When the collecting coroutine is cancelled, this provider immediately calls
     * [EventSource.cancel] which closes the underlying TCP connection. No further
     * events are emitted. However, the upstream LLM may have already generated (and
     * billed) tokens that were in flight. The SDK makes no attempt to "undo" those
     * tokens — that is a backend-side concern. See the backend-sample for a reference
     * "client-disconnect = abort upstream" implementation.
     *
     * If [BackendProxyConfig.retryPolicy] is configured, retriable errors trigger
     * exponential backoff retries. Cancellation during a backoff `delay` is immediate
     * (the delay is a suspending, cancellable call).
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

                        // For retriable errors, close with an exception so retryWhen can fire.
                        // Do NOT emit Error/Done here — retryWhen will re-subscribe the flow
                        // transparently; emitting would leak spurious terminal events to the UI.
                        if (error.isRetriable) {
                            val retryAfterMillis: Long? = response?.header("Retry-After")
                                ?.toLongOrNull()
                            close(BackendRetriableException(
                                error = error,
                                retryAfterMillis = retryAfterMillis))
                        } else {
                            // Non-retriable: emit Error + Done so the collector sees the failure.
                            trySendBlocking(LLMEvent.Error(error))
                            trySendBlocking(LLMEvent.Done())
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
        val retryPolicy = config.retryPolicy ?: RetryPolicy.NONE
        return if (retryPolicy != RetryPolicy.NONE) {
            var attempt = 0
            baseFlow.retryWhen { cause, _ ->
                if (cause is BackendRetriableException && attempt < retryPolicy.maxRetries) {
                    val retryAfterMillis = cause.retryAfterMillis
                    val delayMs = if (retryAfterMillis != null && retryPolicy.respectRetryAfter) {
                        retryAfterMillis.coerceAtMost(retryPolicy.maxBackoffMillis)
                    } else {
                        expBackoff(attempt, retryPolicy)
                    }
                    delay(delayMs)
                    attempt++
                    true
                } else {
                    false
                }
            }.catch { cause ->
                // All retries exhausted: convert the internal exception into terminal events
                // so the collector sees a proper Error + Done rather than an unhandled exception.
                if (cause is BackendRetriableException) {
                    emit(LLMEvent.Error(cause.error))
                    emit(LLMEvent.Done())
                } else {
                    throw cause
                }
            }
        } else {
            baseFlow
        }
    }

    /**
     * Non-streaming generation: sends an HTTP POST and waits for the full response.
     *
     * Applies the configured [RetryPolicy] for retriable errors (network, timeout,
     * rate-limit). Respects `Retry-After` header when [RetryPolicy.respectRetryAfter]
     * is enabled.
     *
     * ## Cancellation & Billing Boundary
     *
     * When the calling coroutine is cancelled, [invokeOnCancellation] fires and
     * calls [okhttp3.Call.cancel], immediately closing the connection. As with
     * streaming, tokens already generated upstream may still be billed — the SDK
     * only guarantees client-side teardown.
     *
     * @throws LLMException when the HTTP request fails after all retries are exhausted,
     *         or when the error is non-retriable.
     * @throws kotlinx.coroutines.CancellationException if the calling coroutine is
     *         cancelled (e.g. via [com.ailux.core.task.LLMTask.cancel]).
     */
    override suspend fun generate(request: LLMRequest): LLMResponse {
        val retryPolicy = config.retryPolicy ?: RetryPolicy.NONE
        var attempt = 0

        while (true) {
            val httpRequest = buildGenerateRequest(request)
            try {
                return executeGenerateRequest(httpRequest)
            } catch (e: BackendRetriableException) {
                // Retriable error from executeGenerateRequest
                val canRetry = retryPolicy != RetryPolicy.NONE &&
                    attempt < retryPolicy.maxRetries

                if (!canRetry) throw LLMException(e.error)

                // Calculate delay: respect Retry-After header if present
                val retryAfterMillis = e.retryAfterMillis
                val delayMs = if (retryAfterMillis != null && retryPolicy.respectRetryAfter) {
                    retryAfterMillis.coerceAtMost(retryPolicy.maxBackoffMillis)
                } else {
                    expBackoff(attempt, retryPolicy)
                }

                delay(delayMs)
                attempt++
            }
        }
    }

    /**
     * Executes a single non-streaming HTTP request and returns the parsed response.
     *
     * @throws BackendRetriableException when the error is retriable (for retry loop).
     * @throws LLMException when the error is non-retriable.
     */
    private suspend fun executeGenerateRequest(httpRequest: Request): LLMResponse {
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

                    if (error.isRetriable) {
                        val retryAfterMillis = response.header("Retry-After")?.toLongOrNull()
                        continuation.resumeWithException(
                            BackendRetriableException(error, retryAfterMillis)
                        )
                    } else {
                        continuation.resumeWithException(LLMException(error))
                    }
                    return@suspendCancellableCoroutine
                }

                val body = response.body?.string()
                    ?: throw LLMException(
                        LLMError(
                            code = ErrorCode.UNKNOWN,
                            message = "Empty response body",
                        )
                    )

                val llmResponse = nonStreamResponseParser.parse(body)
                continuation.resume(llmResponse)
            } catch (e: LLMException) {
                continuation.resumeWithException(e)
            } catch (e: BackendRetriableException) {
                continuation.resumeWithException(e)
            } catch (e: Exception) {
                val error = errorMapper.map(
                    throwable = e,
                    httpCode = null,
                    responseBody = null,
                )
                // Network/timeout errors are retriable
                if (error.isRetriable) {
                    continuation.resumeWithException(
                        BackendRetriableException(error, retryAfterMillis = null)
                    )
                } else {
                    continuation.resumeWithException(LLMException(error))
                }
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
        return buildBaseRequest(request, config.streamEndpoint, jsonBody)
    }

    /**
     * Builds the HTTP Request for non-streaming.
     */
    private suspend fun buildGenerateRequest(request: LLMRequest): Request {
        val jsonBody = requestMapper.map(request, stream = false)
        return buildBaseRequest(request, config.generateEndpoint, jsonBody)
    }

    /**
     * Builds the base HTTP Request (shared logic).
     */
    private suspend fun buildBaseRequest(
        request: LLMRequest,
        endpoint: String,
        jsonBody: String
    ): Request {
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

        // Idempotency header: injects request.requestId so the server can deduplicate
        // retries. The header name is configurable; null disables injection entirely.
        config.idempotencyHeaderName?.let { name ->
            builder.header(name, request.requestId)
        }

        return builder.build()
    }

    /**
     * Builds the OkHttpClient with the configured timeouts.
     *
     * Priority order:
     * 1. [BackendProxyConfig.baseHttpClient] provides the base builder (connection pool, DNS,
     *    certificate pinning, interceptors etc.).
     * 2. SDK-level timeout overrides are applied (connect / read / call).
     * 3. [BackendProxyConfig.okhttpClientCustomizer] runs last and may override anything above.
     *
     * **Per-request timeout**: Ailux intentionally does NOT support per-request timeout
     * overrides via [LLMRequest] fields. The recommended production pattern is:
     * - `callTimeoutMillis = 0` (default) + stall detection for liveness monitoring.
     * - For tasks that require distinct timeout profiles, use separate Provider instances.
     *
     * @see BackendProxyConfig for full timeout guidance.
     */
    private fun buildHttpClient(): OkHttpClient {
        val builder = config.baseHttpClient?.newBuilder() ?: OkHttpClient.Builder()
        builder
            .connectTimeout(config.connectTimeoutMillis, TimeUnit.MILLISECONDS)
            .readTimeout(config.readTimeoutMillis, TimeUnit.MILLISECONDS)
            .apply {
                if (config.callTimeoutMillis > 0) {
                    callTimeout(config.callTimeoutMillis, TimeUnit.MILLISECONDS)
                }
            }
        config.okhttpClientCustomizer?.invoke(builder)
        return builder.build()
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }


    /**
     * Calculates exponential backoff delay with decorrelated jitter.
     *
     * Formula: baseDelay = initialBackoffMillis * multiplier^attempt
     * Jitter range: [baseDelay * (1 - jitterFactor), baseDelay * (1 + jitterFactor)]
     * Final result is clamped to [0, maxBackoffMillis].
     */
    private fun expBackoff(attempt: Int, retryPolicy: RetryPolicy): Long {
        val baseDelay = retryPolicy.initialBackoffMillis *
            Math.pow(retryPolicy.backoffMultiplier, attempt.toDouble())
        val jitter = baseDelay * retryPolicy.jitterFactor * (Math.random() * 2 - 1)
        return (baseDelay + jitter).toLong().coerceIn(0L, retryPolicy.maxBackoffMillis)
    }
}

// ──────────────────────────────────────────
// Auxiliary types
// ──────────────────────────────────────────

/**
 * Internal marker exception: a retriable error was encountered during generation.
 *
 * In streaming mode, used to trigger the Flow's [retryWhen] operator.
 * In non-streaming mode, used to signal the retry loop in [generate].
 * Not exposed externally.
 */
internal class BackendRetriableException(
    val error: LLMError,
    val retryAfterMillis: Long? = null,
) : Exception(error.message, error.cause)
