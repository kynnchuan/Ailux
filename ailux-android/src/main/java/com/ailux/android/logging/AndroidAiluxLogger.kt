package com.ailux.android.logging

import android.util.Log
import com.ailux.core.logging.AiluxLogger
import com.ailux.core.logging.LogLevel

/**
 * Android-flavoured [AiluxLogger] that bridges to `android.util.Log`.
 *
 * This is the **default logger** wired into `AiluxConfig` for Android builds.
 * Choosing this default trades a small amount of "secure by default" for the
 * developer experience of seeing SDK diagnostics in logcat without any setup —
 * the privacy guarantee is preserved by the SDK's internal `RedactingLogSink`,
 * which strips prompt / response / overrides bodies **before** they reach this
 * sink, unless the host app explicitly opts in via
 * [com.ailux.core.privacy.PrivacyConfig].
 *
 * If you want the SDK to be completely silent in release builds, swap to
 * [com.ailux.core.logging.NoopAiluxLogger] for that flavour:
 *
 * ```kotlin
 * val config = AiluxConfig(
 *     providerConfig = backendConfig,
 *     logger = if (BuildConfig.DEBUG) AndroidAiluxLogger() else NoopAiluxLogger,
 * )
 * ```
 *
 * ### Tag handling
 *
 * Android limits log tags to 23 characters. Long tags are truncated to keep
 * `Log.x` from throwing on API levels < 26.
 *
 * @param minLevel entries with a lower [LogLevel] are dropped silently.
 *   Defaults to [LogLevel.DEBUG], matching the conventional "release ships
 *   without VERBOSE" behaviour.
 *
 * @since 0.2.5
 */
public class AndroidAiluxLogger(
    private val minLevel: LogLevel = LogLevel.DEBUG,
) : AiluxLogger {

    override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        if (level.ordinal < minLevel.ordinal) return

        val safeTag = if (tag.length <= MAX_TAG_LENGTH) tag else tag.substring(0, MAX_TAG_LENGTH)

        when (level) {
            LogLevel.VERBOSE -> if (throwable != null) Log.v(safeTag, message, throwable) else Log.v(safeTag, message)
            LogLevel.DEBUG -> if (throwable != null) Log.d(safeTag, message, throwable) else Log.d(safeTag, message)
            LogLevel.INFO -> if (throwable != null) Log.i(safeTag, message, throwable) else Log.i(safeTag, message)
            LogLevel.WARN -> if (throwable != null) Log.w(safeTag, message, throwable) else Log.w(safeTag, message)
            LogLevel.ERROR -> if (throwable != null) Log.e(safeTag, message, throwable) else Log.e(safeTag, message)
        }
    }

    private companion object {
        const val MAX_TAG_LENGTH = 23
    }
}
