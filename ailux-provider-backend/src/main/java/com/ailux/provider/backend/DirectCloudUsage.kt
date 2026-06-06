package com.ailux.provider.backend

/**
 * Opt-in annotation: marks APIs that connect directly to a cloud LLM vendor
 * (BYOK — Bring Your Own Key).
 *
 * Direct-to-cloud usage is intended for:
 * - Debug / development.
 * - Personal tools.
 * - BYOK scenarios where the end user explicitly supplies their own API key.
 *
 * Production paths should go through a Backend Proxy (the company AI Gateway)
 * so that the long-lived API key never reaches the client.
 *
 * Anyone calling such an API must explicitly opt in with
 * `@OptIn(DirectCloudUsage::class)` to acknowledge the security trade-off.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "Direct-to-cloud LLM access is intended for debug / personal tools / BYOK only. " +
        "Production paths should go through a Backend Proxy (company AI Gateway). " +
        "If this really is a BYOK scenario, opt in explicitly with @OptIn(DirectCloudUsage::class)."
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.TYPEALIAS
)
annotation class DirectCloudUsage
