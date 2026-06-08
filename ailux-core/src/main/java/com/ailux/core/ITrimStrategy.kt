package com.ailux.core

import com.ailux.core.message.Message

interface ITrimStrategy {

    fun trim(
        messages: List<Message>,
        budget: Int,
        protectedIndices: Set<Int>,
        tokenCounter: ITokenCounter
    ): List<Message>

}