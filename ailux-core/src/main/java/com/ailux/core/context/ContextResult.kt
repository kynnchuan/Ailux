package com.ailux.core.context

import com.ailux.core.message.Message

data class ContextResult(
    val messages: List<Message>,
    val removed: List<Message>,
    val estimatedTokensSaved: Int,
    val warning: String? = null
)