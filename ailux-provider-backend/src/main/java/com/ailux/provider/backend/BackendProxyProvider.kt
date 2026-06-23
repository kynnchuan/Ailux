package com.ailux.provider.backend

import com.ailux.core.LLMProvider
import com.ailux.core.capabilities.ProviderCapabilities
import com.ailux.core.error.ErrorCode
import com.ailux.core.error.LLMError
import com.ailux.core.error.LLMException
import com.ailux.core.request.LLMRequest
import com.ailux.core.response.LLMResponse
import com.ailux.core.event.LLMEvent
import com.ailux.provider.backend.config.BackendProxyConfig
import com.ailux.provider.backend.config.HttpClientConfig
import com.ailux.provider.backend.config.ProtocolConfig
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
 * Its design philosophy:
 * **unify the LLM calling experience on the Android client side, without forcing
 * a uniform server-side protocol on the company.**
 *
 * Streaming generation is implemented with OkHttp SSE (Server-Sent Events);
 * non-streaming generation uses a standard HTTP POST.
 *
 * ## Configuration (three-part, v0.2.6)
 *
 * | Param | Role | Typical user |
 * |-------|------|-------------|
 * | [config] | Endpoints, auth, retry, headers | Everyone |
 * | [httpConfig] | Timeouts, certificate pinning, interceptors | Enterprise deployments |
 * | [protocolConfig] | Custom mappers/parsers (non-OpenAI protocols) | Anthropic / private formats |
 *
 * ## Usage
 *
 * ```kotlin
 * // OpenAI-compatible backend (default)
 * val provider = BackendProxyProvider(
 *     config = BackendProxyConfig(
 *         baseUrl = "https://api.company.com",
 *         authProvider = AuthProvider { "Bearer ${getToken()}" },
 *     ),
 * )
 *
 * // Anthropic + certificate pinning
 * val provider = BackendProxyProvider(
 *     config = BackendProxyConfig(
 *         baseUrl = "https://api.anthropic.com",
 *         headers = mapOf("anthropic-version" to "2023-06-01"),
 *     ),
 *     httpConfig = HttpClientConfig(baseHttpClient = pinnedClient),
 *     protocolConfig = ProtocolConfig(
 *         requestMapper = AnthropicRequestMapper(),
 *         streamResponseParser = AnthropicStreamResponseParser(),
 *         nonStreamResponseParser = AnthropicNonStreamResponseParser(),
 *     ),
 * )
 * ```
 *
 * @param config        Core business configuration (endpoints, auth, retry, headers).
 * @param httpConfig    Transport-layer configuration (timeouts, client injection). Defaults suffice for most cases.
 * @param protocolConfig Protocol-adaptation configuration (mappers, parsers). Defaults to OpenAI protocol family.
 */
class BackendProxyProvider(
    private val config: BackendProxyConfig,
    private val httpConfig: HttpClientConfig = HttpClientConfig(),
    private val protocolConfig: ProtocolConfig = ProtocolConfig(),
) : LLMProvider {

    /** Resolved extension components (fall back to defaults when null). */
    private val requestMapper: RequestMapper = protocolConfig.requestMapper
        ?: OpenAIRequestMapper(includeUsageInStream = protocolConfig.includeUsageInStream)
    private val errorMapper: ErrorMapper = protocolConfig.errorMapper ?: DefaultErrorMapper()

    /**
     * Parser for non-streaming (`/chat/completions`-style) responses.
     *
     * Unlike [StreamResponseParser] — which is recreated per request because
     * built-in implementations accumulate streaming tool-call state — the
     * non-streaming parser is **stateless by contract** and safely shared
     * across concurrent requests. Falls back to
     * [OpenAINonStreamResponseParser] (OpenAI Chat Completions schema) when
     * [ProtocolConfig.nonStreamResponseParser] is `null`.
     */
    private val nonStreamResponseParser: NonStreamResponseParser =
        protocolConfig.nonStreamResponseParser ?: OpenAINonStreamResponseParser()

    /**
     * Creates a fresh [StreamResponseParser] for each streaming request.
     *
     * Built-in parsers (OpenAI, Anthropic) are stateful — they accumulate tool call
     * deltas internally. A new instance per request prevents state leakage between requests.
     */
    private fun createParser(): StreamResponseParser {
        return protocolConfig.streamResponseParser ?: OpenAIStreamResponseParser()
    }

    /** JSON parser (used for non-streaming response parsing). */
    private val json = Json { ignoreUnknownKeys = true }

    /** Shared OkHttpClient instance, built using the timeouts defined in httpConfig. */
    private val httpClient: OkHttpClient by lazy { buildHttpClient() }

    /** SSE EventSource factory. */
    private val eventSourceFactory: EventSource.Factory by lazy {
        EventSources.createFactory(httpClient)
    }

    /**
     * Backend Proxy capabilities — generic / conservative defaults.
     *
     * Backend-side capabilities are ultimately determined by the chosen upstream
     * model and protocol. The values reported here are the "lowest common
     * denominator" for the OpenAI-compatible default protocol family. Vision
     * support and exact context window are intentionally not declared because
     * they vary per-deployment; advanced callers can compose their own
     * `ProviderCapabilities` and decorate the provider if a stricter contract
     * is needed.
     *
     * `supportsInterruptibleCancellation = true` because [streamGenerate] cancels
     * the underlying SSE connection promptly via `EventSource.cancel()` — the
     * upstream may still bill in-flight tokens, but client-side emit stops at once.
     */
    override val capabilities: ProviderCapabilities = ProviderCapabilities(
        supportsTool = true,
        supportsStream = true,
        supportsVision = false,
        maxContextToken = null,
        supportsInterruptibleCancellation = true,
    )

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
                        //
                        // 401 (AUTH_FAILED) gets a special "maybe-retriable" treatment when
                        // the configured AuthProvider may know how to refresh: we close with
                        // a marker exception that retryWhen will resolve in its suspending
                        // context (where AuthProvider.onUnauthorized() can actually be called).
                        if (error.isRetriable) {
                            val retryAfterMillis: Long? = response?.header("Retry-After")
                                ?.toLongOrNull()
                            close(BackendRetriableException(
                                error = error,
                                retryAfterMillis = retryAfterMillis))
                        } else if (error.code == ErrorCode.AUTH_FAILED && config.authProvider != null) {
                            close(BackendUnauthorizedException(error))
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

        // Apply the retry policy + 401-refresh hook.
        //
        // The 401-refresh path piggybacks on the same retryWhen pipeline as the
        // ordinary RetryPolicy (spec v0.2.6 §3.7 / ADR — "no parallel retry
        // plumbing"). The replay budget for 401 is **one** per task, independent
        // of RetryPolicy.maxRetries (which governs backoff for transient errors).
        val retryPolicy = config.retryPolicy ?: RetryPolicy.NONE
        var attempt = 0
        var authReplayConsumed = false
        // Sticky flag: once onUnauthorized() returned true, we surface
        // AUTH_EXPIRED for any subsequent terminal 401 (the replay itself
        // failed). Lives in the outer closure because each retry attempt
        // receives a *fresh* BackendUnauthorizedException instance — so
        // we cannot track refresh state via the cause object alone.
        var authRefreshAttempted = false
        return baseFlow.retryWhen { cause, _ ->
            when (cause) {
                is BackendUnauthorizedException -> {
                    // Single replay attempt: call AuthProvider.onUnauthorized() in this
                    // suspending context, and replay exactly once if it returns true.
                    if (authReplayConsumed) {
                        false
                    } else {
                        authReplayConsumed = true
                        val refreshed = runCatching {
                            config.authProvider?.onUnauthorized() ?: false
                        }.getOrDefault(false)
                        // Surface AUTH_EXPIRED (instead of AUTH_FAILED) if and only if
                        // the AuthProvider claimed it could refresh — even if the replay
                        // itself later fails. This is the contract that lets UIs branch
                        // "silent re-login" vs "kick to login page".
                        if (refreshed) {
                            cause.markRefreshed()
                            authRefreshAttempted = true
                        }
                        refreshed
                    }
                }
                is BackendRetriableException -> {
                    if (retryPolicy != RetryPolicy.NONE && attempt < retryPolicy.maxRetries) {
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
                }
                else -> false
            }
        }.catch { cause ->
            // All retries exhausted: convert the internal exception into terminal events
            // so the collector sees a proper Error + Done rather than an unhandled exception.
            when (cause) {
                is BackendUnauthorizedException -> {
                    // The cause flowing into .catch{} is the *replay's* failure (a
                    // fresh exception instance). We use authRefreshAttempted (sticky
                    // outer-closure flag) instead of cause.refreshed to decide the
                    // terminal code, otherwise a successful refresh followed by a
                    // second 401 would incorrectly surface as AUTH_FAILED.
                    val finalCode =
                        if (authRefreshAttempted || cause.refreshed) ErrorCode.AUTH_EXPIRED
                        else ErrorCode.AUTH_FAILED
                    emit(LLMEvent.Error(cause.error.copy(code = finalCode)))
                    emit(LLMEvent.Done())
                }
                is BackendRetriableException -> {
                    emit(LLMEvent.Error(cause.error))
                    emit(LLMEvent.Done())
                }
                else -> throw cause
            }
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
        var authReplayConsumed = false
        // Sticky: once a refresh has been claimed successful, any later
        // terminal 401 in this same task surfaces as AUTH_EXPIRED (not
        // AUTH_FAILED) so the UI can distinguish "silent re-login attempted
        // but still failed" from "no refresh path available at all".
        var authRefreshAttempted = false

        while (true) {
            val httpRequest = buildGenerateRequest(request)
            try {
                return executeGenerateRequest(httpRequest)
            } catch (e: BackendUnauthorizedException) {
                // Single 401 replay attempt, threaded through the same retry loop
                // (spec v0.2.6 §3.7 — "no parallel retry plumbing"). The replay budget
                // is independent of RetryPolicy.maxRetries.
                if (authReplayConsumed) {
                    val finalCode = if (authRefreshAttempted) ErrorCode.AUTH_EXPIRED
                        else ErrorCode.AUTH_FAILED
                    throw LLMException(e.error.copy(code = finalCode))
                }
                authReplayConsumed = true
                val refreshed = runCatching {
                    config.authProvider?.onUnauthorized() ?: false
                }.getOrDefault(false)
                if (!refreshed) {
                    throw LLMException(e.error.copy(code = ErrorCode.AUTH_FAILED))
                }
                authRefreshAttempted = true
                // Refreshed — fall through and rebuild the request with the fresh credential.
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
                    } else if (error.code == ErrorCode.AUTH_FAILED && config.authProvider != null) {
                        // 401 + AuthProvider present → may be recoverable via
                        // onUnauthorized() refresh + replay. Resolved in generate().
                        continuation.resumeWithException(BackendUnauthorizedException(error))
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
     *
     * Header injection order is documented on
     * [com.ailux.provider.backend.auth.RequestSigner]: static → Authorization →
     * custom headers → Idempotency-Key → signer (last, so it can overlay
     * anything above).
     */
    private suspend fun buildBaseRequest(
        request: LLMRequest,
        endpoint: String,
        jsonBody: String
    ): Request {
        val url = "${config.baseUrl}$endpoint"

        // Track headers we've put on the builder so we can hand a faithful
        // snapshot to RequestSigner without paying for OkHttp's Request build
        // just to read them back.
        val currentHeaders = LinkedHashMap<String, String>()
        val builder = Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
        fun putHeader(name: String, value: String) {
            builder.header(name, value)
            currentHeaders[name] = value
        }

        // 1. Static headers
        putHeader("Content-Type", "application/json")
        putHeader("Accept", "text/event-stream")

        // 2. Auth header
        config.authProvider?.let { auth ->
            val authHeaderValue = auth.getAuthToken()
            if (authHeaderValue.isNotBlank()) {
                putHeader("Authorization", authHeaderValue)
            }
        }

        // 3. Custom headers
        config.headers.forEach { (key, value) ->
            putHeader(key, value)
        }

        // 4. Idempotency header: injects request.requestId so the server can deduplicate
        //    retries. The header name is configurable; null disables injection entirely.
        config.idempotencyHeaderName?.let { name ->
            putHeader(name, request.requestId)
        }

        // 5. Request signer (runs last; may overlay anything above).
        config.requestSigner?.let { signer ->
            val snapshot = com.ailux.provider.backend.auth.SignableRequest(
                method = "POST",
                url = url,
                body = jsonBody,
                headers = currentHeaders.toMap(),
            )
            val signedHeaders = signer.sign(snapshot)
            signedHeaders.forEach { (key, value) ->
                builder.header(key, value)
            }
        }

        return builder.build()
    }

    /**
     * Builds the OkHttpClient with the configured timeouts.
     *
     * Priority order:
     * 1. [HttpClientConfig.baseHttpClient] provides the base builder (connection pool, DNS,
     *    certificate pinning, interceptors etc.).
     * 2. SDK-level timeout overrides are applied (connect / read / call).
     * 3. [HttpClientConfig.customizer] runs last and may override anything above.
     *
     * **Per-request timeout**: Ailux intentionally does NOT support per-request timeout
     * overrides via [LLMRequest] fields. The recommended production pattern is:
     * - `callTimeoutMillis = 0` (default) + stall detection for liveness monitoring.
     * - For tasks that require distinct timeout profiles, use separate Provider instances.
     *
     * @see HttpClientConfig for full timeout guidance.
     */
    private fun buildHttpClient(): OkHttpClient {
        val builder = httpConfig.baseHttpClient?.newBuilder() ?: OkHttpClient.Builder()
        builder
            .connectTimeout(httpConfig.connectTimeoutMillis, TimeUnit.MILLISECONDS)
            .readTimeout(httpConfig.readTimeoutMillis, TimeUnit.MILLISECONDS)
            .apply {
                if (httpConfig.callTimeoutMillis > 0) {
                    callTimeout(httpConfig.callTimeoutMillis, TimeUnit.MILLISECONDS)
                }
            }
        httpConfig.customizer?.invoke(builder)
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

/**
 * Internal marker exception: a 401/AUTH_FAILED response was received and the
 * configured [com.ailux.provider.backend.auth.AuthProvider] may know how to recover.
 *
 * Carrying this out of the SSE callback / OkHttp call site lets the suspending
 * `retryWhen` (streaming) or the suspending retry loop (non-streaming) call
 * [com.ailux.provider.backend.auth.AuthProvider.onUnauthorized] in a context
 * where suspending functions are legal — which the OkHttp callback is not.
 *
 * The replay budget is **one per task**, independent of [RetryPolicy.maxRetries].
 *
 * [refreshed] is flipped to `true` iff `onUnauthorized()` returned `true`. The
 * `.catch{}` block in streaming and the terminal `throw` in non-streaming use
 * this to decide whether the final terminal event should report
 * [ErrorCode.AUTH_EXPIRED] (refresh was attempted) or [ErrorCode.AUTH_FAILED]
 * (no refresh path / refresh declined). Not exposed externally.
 */
internal class BackendUnauthorizedException(
    val error: LLMError,
) : Exception(error.message, error.cause) {
    @Volatile
    var refreshed: Boolean = false
        private set

    fun markRefreshed() {
        refreshed = true
    }
}
