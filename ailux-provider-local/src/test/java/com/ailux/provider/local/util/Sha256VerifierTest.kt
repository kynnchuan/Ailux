package com.ailux.provider.local.util

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Covers spec §4.4 — three-state opt-in SHA-256:
 *  1. expectedHex == null → skip (return null).
 *  2. expectedHex matches actual hash → pass (return null).
 *  3. expectedHex mismatch → diagnostic message (caller maps to MODEL_FILE_INVALID).
 *
 * Also exercises the streaming-hex implementation against a known vector.
 */
class Sha256VerifierTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun verify_nullHash_skipsAndReturnsNull() {
        val f = tmp.newFile("model.bin").apply { writeText("anything") }
        assertNull(Sha256Verifier.verify(f, null))
        assertNull(Sha256Verifier.verify(f, ""))
        assertNull(Sha256Verifier.verify(f, "   "))
    }

    @Test
    fun verify_matchingHash_returnsNull() {
        val f = tmp.newFile("model.bin").apply { writeText("hello") }
        val expected = sha256Of("hello")
        assertNull(Sha256Verifier.verify(f, expected))
        // Case-insensitive match.
        assertNull(Sha256Verifier.verify(f, expected.uppercase()))
    }

    @Test
    fun verify_mismatchingHash_returnsDiagnostic() {
        val f = tmp.newFile("model.bin").apply { writeText("hello") }
        val wrong = "0".repeat(64)
        val diag = Sha256Verifier.verify(f, wrong)
        assertNotNull(diag)
        assertTrue(diag!!.contains("SHA-256 mismatch"))
        assertTrue(diag.contains("model.bin"))
    }

    @Test
    fun verify_missingFile_returnsDiagnostic() {
        val ghost = File(tmp.root, "does-not-exist.bin")
        val diag = Sha256Verifier.verify(ghost, "0".repeat(64))
        assertNotNull(diag)
        assertTrue(diag!!.contains("Model file not found"))
    }

    @Test
    fun computeHex_emptyInput_matchesKnownVector() {
        // SHA-256("") = e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
        val hex = Sha256Verifier.computeHex(byteArrayOf().inputStream())
        assertTrue(hex == "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
    }

    private fun sha256Of(s: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
