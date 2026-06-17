package com.ailux.core.logging

/**
 * SPI for SDK logging.
 *
 * Ailux never writes logs through `android.util.Log` directly. Every internal
 * log call goes through an [AiluxLogger] instance held by `AiluxConfig`, which
 * lets host apps:
 *
 *  - silence the SDK entirely (use [NoopAiluxLogger]);
 *  - bridge SDK logs into an existing logging stack (Timber, SLF4J, Sentry…);
 *  - swap implementations per build flavour (verbose in debug, silent in
 *    release).
 *
 * ## Implementing
 *
 * Implementations only need to override the single sink method [log]. The
 * convenience entry points [v] / [d] / [i] / [w] / [e] are `default` methods
 * that delegate to [log], so both Kotlin and Java callers can write
 * `logger.d("Tag", "message")` directly without boilerplate.
 *
 * ```kotlin
 * // Minimal Kotlin implementation
 * object MyLogger : AiluxLogger {
 *     override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
 *         println("[$level] $tag: $message")
 *     }
 * }
 * ```
 *
 * ## Privacy contract
 *
 * Logger implementations receive log messages **after** the SDK's internal
 * `RedactingLogSink` has already applied `PrivacyConfig` rules. By the time a
 * message reaches your [AiluxLogger]:
 *
 *  - prompt / response / overrides content is already redacted unless the user
 *    explicitly opted in via [com.ailux.core.privacy.PrivacyConfig];
 *  - authorisation headers and API keys are always stripped.
 *
 * You do **not** need to re-implement redaction. Just forward the message to
 * your sink.
 *
 * ## Threading
 *
 * [log] may be called from any thread, including from inside coroutines
 * dispatched on `Dispatchers.IO`. Implementations must be thread-safe.
 *
 * ## Example — bridge to Timber
 *
 * ```kotlin
 * object TimberLogger : AiluxLogger {
 *     override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
 *         when (level) {
 *             LogLevel.VERBOSE -> Timber.tag(tag).v(throwable, message)
 *             LogLevel.DEBUG   -> Timber.tag(tag).d(throwable, message)
 *             LogLevel.INFO    -> Timber.tag(tag).i(throwable, message)
 *             LogLevel.WARN    -> Timber.tag(tag).w(throwable, message)
 *             LogLevel.ERROR   -> Timber.tag(tag).e(throwable, message)
 *         }
 *     }
 * }
 *
 * val config = AiluxConfig(
 *     providerConfig = backendConfig,
 *     logger = TimberLogger,
 * )
 * ```
 *
 * @since 0.2.5
 */
public interface AiluxLogger {

    /**
     * Records a single log entry.
     *
     * Implementations override this single method. Call sites should prefer
     * the level-specific helpers ([v], [d], [i], [w], [e]) for readability.
     *
     * @param level severity bucket; sinks may choose to drop lower levels.
     * @param tag short identifier (e.g. "Ailux/BackendProxy"). Caller-supplied,
     *   safe to display.
     * @param message free-form message. **Already redacted** by the SDK against
     *   the active [com.ailux.core.privacy.PrivacyConfig].
     * @param throwable optional cause attached to the log entry.
     */
    public fun log(
        level: LogLevel,
        tag: String,
        message: String,
        throwable: Throwable? = null,
    )

    /** Convenience for `log(LogLevel.VERBOSE, …)`. */
    public fun v(tag: String, message: String, throwable: Throwable? = null): Unit =
        log(LogLevel.VERBOSE, tag, message, throwable)

    /** Convenience for `log(LogLevel.DEBUG, …)`. */
    public fun d(tag: String, message: String, throwable: Throwable? = null): Unit =
        log(LogLevel.DEBUG, tag, message, throwable)

    /** Convenience for `log(LogLevel.INFO, …)`. */
    public fun i(tag: String, message: String, throwable: Throwable? = null): Unit =
        log(LogLevel.INFO, tag, message, throwable)

    /** Convenience for `log(LogLevel.WARN, …)`. */
    public fun w(tag: String, message: String, throwable: Throwable? = null): Unit =
        log(LogLevel.WARN, tag, message, throwable)

    /** Convenience for `log(LogLevel.ERROR, …)`. */
    public fun e(tag: String, message: String, throwable: Throwable? = null): Unit =
        log(LogLevel.ERROR, tag, message, throwable)
}

/**
 * Severity buckets for [AiluxLogger.log].
 *
 * Ordering mirrors `android.util.Log`'s VERBOSE..ERROR scale, but the SDK is
 * platform-neutral and does not depend on the Android log levels directly.
 *
 * @since 0.2.5
 */
public enum class LogLevel {
    VERBOSE,
    DEBUG,
    INFO,
    WARN,
    ERROR,
}

/**
 * Discards every log entry.
 *
 * This is the safe default for **production builds** — no I/O, no allocations
 * beyond the dispatch itself, and no risk of leaking SDK internals into shipped
 * logs.
 *
 * Pair it with `PrivacyConfig()` defaults (all `false`) to opt out of any
 * SDK-side observability.
 *
 * @since 0.2.5
 */
public object NoopAiluxLogger : AiluxLogger {
    override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        // intentionally empty
    }
}
