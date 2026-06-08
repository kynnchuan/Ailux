package com.ailux.api.context

import com.ailux.core.ITokenCounter
import com.ailux.core.message.Message

class EstimatedTokenCounter(
    val safetyMargin: Float = 0.15f
): ITokenCounter {


    override fun count(messages: List<Message>): Int {
        return messages.sumOf { count(it) }
    }

    override fun count(message: Message): Int {
        val text = when(message) {
            is Message.System -> message.content
            is Message.User -> message.content
            is Message.Tool -> message.content
            is Message.Assistant -> {
                (message.content ?: "") + (
                        message.toolCalls?.joinToString {
                            it.name + it.arguments
                        } ?: ""
                        )
            }
        }
        val rawEstimate = estimateTokens(text)
        return (rawEstimate * (1 + safetyMargin)).toInt()
    }

    private fun estimateTokens(text: String): Int {
        var tokens = 0
        for (char in text) {
            tokens += when {
                char.code in 0x4E00..0x9FFF -> 15
                char.code in 0x3000..0x303F -> 15
                char in "{}[]\":," -> 10
                else -> 3
            }
        }
        return tokens / 10 + 4
    }

}