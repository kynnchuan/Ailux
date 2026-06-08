package com.ailux.core

import com.ailux.core.message.Message

interface ITokenCounter {

    fun count(messages: List<Message>): Int

    fun count(message: Message): Int

}