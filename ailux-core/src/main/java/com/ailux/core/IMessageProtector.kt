package com.ailux.core

import com.ailux.core.context.TrimAggressiveness
import com.ailux.core.message.Message

interface IMessageProtector {

    fun protect(
        messages: List<Message>,
        aggressiveness: TrimAggressiveness
    ): Set<Int>

}