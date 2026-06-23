package com.ailux.provider.mock

import com.ailux.core.message.Message
import com.ailux.core.request.LLMRequest
import com.ailux.core.session.SessionConfig
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end test that proves the v0.3.0 Session contract holds for a
 * stateless provider: open → 2 turns → snapshot → restore → 1 turn,
 * with the restored session inheriting the full conversation history.
 *
 * Why MockProvider: it's the simplest [com.ailux.core.LLMProvider] that
 * routes through `StatelessProviderSession`, so this test exercises the
 * production code path without needing a network or a native engine.
 */
class MockProviderSessionE2ETest {

    @Test
    fun `open then two turns then snapshot then restore then one turn yields full history`() = runBlocking {
        // MockProvider has a default rule that replies with placeholder text;
        // we just need ANY token-emitting reply for the test.
        val provider = MockProvider()

        // Step 1: open a session with a system instruction.
        val session = provider.openSession(SessionConfig(systemInstruction = "you are a test bot"))

        // Step 2: turn 1 — "hello".
        session.streamGenerate(
            LLMRequest(messages = listOf(Message.User("hello")))
        ).toList()

        // Step 3: turn 2 — "again".
        session.streamGenerate(
            LLMRequest(messages = listOf(Message.User("again")))
        ).toList()

        // Step 4: snapshot.
        val snap = session.snapshot()
        // History should be: [System, User1, Assistant1, User2, Assistant2] — 5 entries.
        assertEquals(
            "snapshot history should contain system + 2 user + 2 assistant",
            5,
            snap.messages.size,
        )
        assertTrue(snap.messages[0] is Message.System)
        assertEquals("hello", (snap.messages[1] as Message.User).content)
        assertTrue(snap.messages[2] is Message.Assistant)
        assertEquals("again", (snap.messages[3] as Message.User).content)
        assertTrue(snap.messages[4] is Message.Assistant)
        session.close()

        // Step 5: restore from snapshot.
        val restored = provider.restoreSession(snap)
        val restoredSnap = restored.snapshot()
        assertEquals(
            "restored session must start with the same history",
            snap.messages,
            restoredSnap.messages,
        )

        // Step 6: turn 3 on the restored session — "third".
        restored.streamGenerate(
            LLMRequest(messages = listOf(Message.User("third")))
        ).toList()
        val finalSnap = restored.snapshot()
        assertEquals(
            "after one more turn the history should grow by 2 (user + assistant)",
            7,
            finalSnap.messages.size,
        )
        assertEquals("third", (finalSnap.messages[5] as Message.User).content)
        assertTrue(finalSnap.messages[6] is Message.Assistant)

        restored.close()
    }

    @Test
    fun `provider reports unbounded concurrent sessions in capabilities`() {
        // Mock is in-memory; it claims no upper bound so the cap-degradation
        // path in ConcurrencyCoordinator never triggers under tests.
        val provider = MockProvider()
        assertEquals(
            Int.MAX_VALUE,
            provider.capabilities.maxConcurrentSessions,
        )
        assertNotNull(provider.capabilities)
    }
}
