package com.ailux.core.session

import com.ailux.core.message.Message
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Snapshot serialization round-trip is the contract for the v0.3.0 "persistable
 * sessions" promise. Anything that survives `encode → decode` is implicitly part
 * of the on-disk format; if a field is *added* to [SessionSnapshot] without a
 * default, this test will fail to compile (the constructor call below would no
 * longer match), which is exactly the early warning we want before the
 * `SCHEMA_VERSION` bump becomes mandatory.
 */
class SessionSnapshotSerializationTest {

    private val json = Json {
        // We deliberately do NOT set ignoreUnknownKeys/encodeDefaults — the
        // production code should round-trip cleanly with stock settings.
        prettyPrint = false
    }

    @Test
    fun `snapshot survives encode-decode round trip with all fields preserved`() {
        val original = SessionSnapshot(
            messages = listOf(
                Message.System("be concise"),
                Message.User("hi"),
                Message.Assistant("hello"),
            ),
            systemInstruction = "be concise",
            samplerOverrides = JsonObject(mapOf("temperature" to JsonPrimitive(0.7))),
            providerHint = JsonObject(mapOf("tenant" to JsonPrimitive("acme"))),
            createdAtEpochMs = 1_700_000_000_000L,
            snapshotAtEpochMs = 1_700_000_123_456L,
        )

        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<SessionSnapshot>(encoded)

        assertEquals("messages preserved", original.messages, decoded.messages)
        assertEquals(
            "systemInstruction preserved",
            original.systemInstruction,
            decoded.systemInstruction,
        )
        assertEquals(
            "samplerOverrides preserved",
            original.samplerOverrides,
            decoded.samplerOverrides,
        )
        assertEquals("providerHint preserved", original.providerHint, decoded.providerHint)
        assertEquals(
            "createdAtEpochMs preserved",
            original.createdAtEpochMs,
            decoded.createdAtEpochMs,
        )
        assertEquals(
            "snapshotAtEpochMs preserved",
            original.snapshotAtEpochMs,
            decoded.snapshotAtEpochMs,
        )
        assertEquals("schemaVersion preserved", original.schemaVersion, decoded.schemaVersion)
    }

    @Test
    fun `default schemaVersion equals SCHEMA_VERSION constant`() {
        // Triggers human attention every time the constant is bumped — the
        // matching production rollout must also extend backward-compat decoding.
        val snap = SessionSnapshot(
            messages = emptyList(),
            createdAtEpochMs = 0,
            snapshotAtEpochMs = 0,
        )
        assertEquals(
            "default schemaVersion must equal the public constant",
            SessionSnapshot.SCHEMA_VERSION,
            snap.schemaVersion,
        )
        assertEquals("v0.3.0 ships schemaVersion=1", 1, SessionSnapshot.SCHEMA_VERSION)
    }

    @Test
    fun `encoded form contains schemaVersion key (forward-compat marker)`() {
        // Defensive: if some future @Transient slips onto schemaVersion, restore
        // would silently fall back to the default and break upgrade paths.
        val snap = SessionSnapshot(
            messages = emptyList(),
            createdAtEpochMs = 0,
            snapshotAtEpochMs = 0,
        )
        val encoded = json.encodeToString(snap)
        assertTrue(
            "schemaVersion must be serialized; encoded JSON was: $encoded",
            encoded.contains("\"schemaVersion\""),
        )
    }

    @Test
    fun `empty-history snapshot round-trips`() {
        val snap = SessionSnapshot(
            messages = emptyList(),
            createdAtEpochMs = 0,
            snapshotAtEpochMs = 0,
        )
        val decoded = json.decodeFromString<SessionSnapshot>(json.encodeToString(snap))
        assertEquals(snap, decoded)
        // The default JsonObjects must equal each other.
        assertNotNull("samplerOverrides default present", decoded.samplerOverrides)
        assertNotNull("providerHint default present", decoded.providerHint)
    }
}
