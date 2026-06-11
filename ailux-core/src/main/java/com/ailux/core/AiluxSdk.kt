package com.ailux.core

/**
 * Build-time metadata for the Ailux SDK.
 *
 * Keep [VERSION] in sync with the `AILUX_VERSION` Gradle property declared in
 * the root `gradle.properties`. The string is used by diagnostic reports
 * ([com.ailux.core.diagnostics.DiagnosticReport.sdkVersion]) so that bug
 * reports always carry an explicit, human-readable version tag.
 */
public object AiluxSdk {
    /**
     * Semantic version of the SDK. Update this when bumping `AILUX_VERSION`.
     */
    public const val VERSION: String = "0.2.5"
}
