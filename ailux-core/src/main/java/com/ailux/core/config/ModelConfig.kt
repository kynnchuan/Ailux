package com.ailux.core.config

data class ModelConfig(
    val name: String,
    val contextWindowSize: Int,
    val reserveForReply: Int = 4096
)