package com.ailux.provider.local.util

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Opt-in SHA-256 integrity verifier for model files.
 *
 * Spec §4.4. The SDK only **computes and compares** — it never looks up "what the
 * correct hash should be". The expected hash [H] is provided by the business
 * caller via [com.ailux.core.config.LocalRuntimeConfig.verifySha256], typically
 * sourced from the model card (HuggingFace / ModelScope) or `shasum -a 256`.
 *
 * **When to use** (after model-downloader was rejected per ADR-0005):
 *
 * | Acquisition path                | Need SHA-256? |
 * |---------------------------------|---------------|
 * | Packaged into APK / assets      | ❌ APK signature already covers integrity |
 * | App self-downloads a big model  | ✅ This is the **only** real home — guard against corruption / tampering |
 * | Copied in from external storage | ✅ When the source doesn't guarantee integrity |
 * | User-picked (no known hash)     | Leave [H] null — verification is skipped |
 *
 * Streams the file in 64 KiB chunks so that loading is not blocked on whole-file
 * I/O and large models don't pressure the heap.
 */
object Sha256Verifier {

    private const val BUFFER_SIZE = 64 * 1024
    private const val ALGORITHM = "SHA-256"

    /**
     * Verify [file] against expected hash [expectedHex].
     *
     * @param expectedHex Expected hex-encoded SHA-256 (case-insensitive). `null`
     *                    means "skip verification" — returns immediately.
     * @return `null` if verification passed (or was skipped). A short human-readable
     *         diagnostic when the hash mismatches — the caller is expected to wrap
     *         it into [com.ailux.core.error.ErrorCode.MODEL_FILE_INVALID].
     */
    fun verify(file: File, expectedHex: String?): String? {
        if (expectedHex.isNullOrBlank()) return null
        if (!file.exists() || !file.isFile) {
            return "Model file not found: ${file.absolutePath}"
        }
        val actual = file.inputStream().use { computeHex(it) }
        val expected = expectedHex.lowercase()
        return if (actual.equals(expected, ignoreCase = true)) {
            null
        } else {
            "SHA-256 mismatch for ${file.name}: expected=$expected actual=$actual"
        }
    }

    /** Streaming SHA-256 → lowercase hex. Exposed for tests. */
    internal fun computeHex(input: InputStream): String {
        val digest = MessageDigest.getInstance(ALGORITHM)
        val buf = ByteArray(BUFFER_SIZE)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            digest.update(buf, 0, n)
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4])
            sb.append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
