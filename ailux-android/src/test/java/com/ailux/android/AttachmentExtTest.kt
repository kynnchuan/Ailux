package com.ailux.android

import com.ailux.core.request.AttachmentSource
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.Base64 as JdkBase64

/**
 * Unit tests for the JVM-pure pieces of [AttachmentExt] — the embedded Base64
 * encoder and the size-bounded `InputStream` reader. The Android-specific
 * `Attachment.fromLocalUri(Context, Uri, ...)` path is exercised by an
 * instrumented test (it requires a real `ContentResolver`).
 */
class AttachmentExtTest {

    // --- base64Encode ---

    @Test
    fun `base64 empty input encodes to empty string`() {
        assertEquals("", base64Encode(ByteArray(0)))
    }

    @Test
    fun `base64 matches JDK encoder for ascii sample`() {
        val bytes = "hello world".toByteArray(Charsets.UTF_8)
        assertEquals(JdkBase64.getEncoder().encodeToString(bytes), base64Encode(bytes))
    }

    @Test
    fun `base64 matches JDK encoder for binary sample`() {
        val bytes = ByteArray(257) { (it - 128).toByte() } // covers full byte range
        assertEquals(JdkBase64.getEncoder().encodeToString(bytes), base64Encode(bytes))
    }

    @Test
    fun `base64 has no line wrapping`() {
        val bytes = ByteArray(1024) { 0x42 }
        val encoded = base64Encode(bytes)
        assertTrue("encoded must not contain newlines", !encoded.contains('\n'))
        assertEquals(1368, encoded.length) // ceil(1024/3)*4 = 1368
    }

    @Test
    fun `base64 padding is correct for length mod 3`() {
        // 1 byte -> 2 chars + "=="
        assertEquals("QQ==", base64Encode(byteArrayOf('A'.code.toByte())))
        // 2 bytes -> 3 chars + "="
        assertEquals("QUI=", base64Encode(byteArrayOf('A'.code.toByte(), 'B'.code.toByte())))
        // 3 bytes -> 4 chars, no padding
        assertEquals(
            "QUJD",
            base64Encode(
                byteArrayOf('A'.code.toByte(), 'B'.code.toByte(), 'C'.code.toByte()),
            ),
        )
    }

    // --- encodeBytesToAttachment ---

    @Test
    fun `encodeBytesToAttachment wraps bytes as Base64 source`() {
        val bytes = "ailux".toByteArray()
        val attachment = encodeBytesToAttachment(bytes, "image/jpeg")

        assertEquals("image/jpeg", attachment.mimeType)
        val source = attachment.source
        assertTrue("source must be Base64", source is AttachmentSource.Base64)
        source as AttachmentSource.Base64
        assertEquals(JdkBase64.getEncoder().encodeToString(bytes), source.data)
    }

    // --- readUpTo ---

    @Test
    fun `readUpTo returns full payload when below the cap`() {
        val payload = ByteArray(1024) { (it and 0xFF).toByte() }
        val read = readUpTo(ByteArrayInputStream(payload), maxBytes = 4096, uri = "test://x")
        assertArrayEquals(payload, read)
    }

    @Test
    fun `readUpTo returns exactly the cap when payload equals cap`() {
        val payload = ByteArray(64) { 1 }
        val read = readUpTo(ByteArrayInputStream(payload), maxBytes = 64, uri = "test://x")
        assertEquals(64, read.size)
    }

    @Test
    fun `readUpTo throws when payload exceeds cap`() {
        val payload = ByteArray(128) { 1 }
        try {
            readUpTo(ByteArrayInputStream(payload), maxBytes = 64, uri = "test://big")
            fail("expected IllegalArgumentException for oversized payload")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "error must mention maxBytes",
                e.message?.contains("maxBytes=64") == true,
            )
        }
    }
}
