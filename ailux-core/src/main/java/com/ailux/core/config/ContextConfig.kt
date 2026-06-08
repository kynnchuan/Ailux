package com.ailux.core.config

import com.ailux.core.context.TrimAggressiveness

data class ContextConfig(
    val budget: Int,
    val aggressiveness: TrimAggressiveness = TrimAggressiveness.CONSERVATIVE
)