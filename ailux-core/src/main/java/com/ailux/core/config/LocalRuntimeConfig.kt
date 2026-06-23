package com.ailux.core.config

data class LocalRuntimeConfig(
    val modelSource: ModelSource,
    val verifySha256: String? = null,
    val minRamMb: Int? = null,
): ProviderConfig


sealed interface ModelSource {

    data class LocalPath(val absolutePath: String): ModelSource

}