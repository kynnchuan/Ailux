package com.ailux.provider.mock

import com.ailux.core.LLMProvider
import com.ailux.core.capabilities.ProviderCapabilities
import com.ailux.core.event.LLMEvent
import com.ailux.core.message.Message
import com.ailux.core.request.LLMRequest
import com.ailux.core.response.LLMResponse
import com.ailux.core.response.UsageInfo
import com.ailux.core.session.Session
import com.ailux.core.session.SessionConfig
import com.ailux.core.session.SessionSnapshot
import com.ailux.core.session.StatelessProviderSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** in-memory [LLMProvider] for tests, demos and offline usage.
 *
 * Each request is matched against a list of [MockRule]s by keyword. The first
 * rule whose keyword is contained in the last user message wins; if no keyword matches,
 * the first rule with an empty keyword is used as a fallback. When neither
 * succeeds, a placeholder reply is returned.
 *
 * @param rules The rule list, in priority order. Defaults to [defaultRules].
 * @param tokenDelayMillis Per-token delay used by [streamGenerate]
 *                         (0 means no delay).
 * @param reasoningDelayMillis Per-reasoning-character delay used by
 *                             [streamGenerate] (0 means no delay).
 */
class MockProvider(
    private val rules: List<MockRule> = defaultRules(),
    private val tokenDelayMillis: Long = 0L,
    private val reasoningDelayMillis: Long = 0L
) : LLMProvider {

    /** Mock provider — declares full capabilities so downstream `capability` checks don't gate test code. */
    override val capabilities: ProviderCapabilities = ProviderCapabilities(
        supportsTool = true,
        supportsStream = true,
        supportsVision = false,
        maxContextToken = null,
        supportsInterruptibleCancellation = true,
        // Mock is purely in-memory — every session is independent and there is
        // no native handle to contend for. Cap at Int.MAX_VALUE so the Client
        // never gates fan-out for tests.
        maxConcurrentSessions = Int.MAX_VALUE,
    )

    override fun openSession(config: SessionConfig): Session =
        StatelessProviderSession(
            config = config,
            streamGenerateRaw = { req -> streamGenerate(req) },
        )

    override fun restoreSession(snapshot: SessionSnapshot): Session =
        StatelessProviderSession(
            // Carry over sampler / providerHint from the snapshot, but DO NOT
            // re-set systemInstruction here — `snapshot.messages` already
            // contains the original `Message.System` entry, so leaving
            // `SessionConfig.systemInstruction = null` avoids the
            // StatelessProviderSession init block from prepending a second
            // System message to an already-prefixed history.
            config = SessionConfig(
                samplerOverrides = snapshot.samplerOverrides,
                providerHint = snapshot.providerHint,
            ),
            createdAtEpochMs = snapshot.createdAtEpochMs,
            initialHistory = snapshot.messages,
            streamGenerateRaw = { req -> streamGenerate(req) },
        )

    override fun streamGenerate(request: LLMRequest): Flow<LLMEvent> = flow {
        val userPrompt = extractUserPrompt(request.messages)
        val rule = findRule(userPrompt)

        rule.reasoning?.forEach { char ->
            emit(LLMEvent.Reasoning(char.toString()))
            delayIfNeeded(reasoningDelayMillis)
        }

        for (char in rule.reply) {
            emit(LLMEvent.Token(char.toString()))
            delayIfNeeded(tokenDelayMillis)
        }

        emit(LLMEvent.Usage(
            UsageInfo(
                inputTokens = userPrompt.length,
                outputTokens = rule.reply.length,
                estimated = true
            )
        ))

        emit(LLMEvent.Done())
    }

    override suspend fun generate(request: LLMRequest): LLMResponse {
        val userPrompt = extractUserPrompt(request.messages)
        val rule = findRule(userPrompt)
        return LLMResponse(text = rule.reply)
    }

    private fun extractUserPrompt(messages: List<Message>): String {
        val lastUser = messages.findLast { it is Message.User } as? Message.User
        return lastUser?.content ?: ""
    }

    private fun findRule(userPrompt: String): MockRule {
        val matched = rules.firstOrNull { rule ->
            rule.keyword.isNotEmpty() && userPrompt.contains(rule.keyword)
        }
        if (matched != null) {
            return matched
        }
        return rules.firstOrNull { rule ->
            rule.keyword.isEmpty()
        } ?: MockRule(
            keyword = "",
            reply = "..."
        )
    }

    private suspend fun delayIfNeeded(millis: Long) {
        if (millis > 0) {
            delay(millis)
        }
    }

}
