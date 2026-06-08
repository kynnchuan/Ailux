package com.ailux.core

import com.ailux.core.config.ContextConfig
import com.ailux.core.context.ContextResult
import com.ailux.core.message.Message

interface LLMContextManager {

    fun process(messages: List<Message>, config: ContextConfig): ContextResult

}