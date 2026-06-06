package com.ailux.core

/**
 * Marker interface for provider configuration.
 *
 * Each [LLMProvider] implementation may define its own configuration class that
 * implements this interface, e.g. `BackendProxyConfig : ProviderConfig`.
 *
 * `AiluxConfig` in the [com.ailux.api] layer holds a `ProviderConfig` reference,
 * which lets it pass configuration through without depending on any concrete
 * provider module directly.
 */
interface ProviderConfig
