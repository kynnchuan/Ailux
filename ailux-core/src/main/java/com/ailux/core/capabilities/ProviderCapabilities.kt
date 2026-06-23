package com.ailux.core.capabilities

data class ProviderCapabilities(
    val supportsTool: Boolean,
    val supportsStream: Boolean,
    val supportsVision: Boolean,
    val maxContextToken: Int?,
    val supportsInterruptibleCancellation: Boolean,
)