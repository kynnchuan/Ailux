package com.ailux.android

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.ailux.core.request.Attachment
import com.ailux.core.request.AttachmentSource
import java.io.IOException

/**
 * Maximum number of bytes a local attachment is allowed to occupy when read
 * into memory for Base64 conversion. Defaults to 20 MiB, which comfortably
 * covers typical photo and short audio captures while preventing accidental
 * OOM when callers point [fromLocalUri] at a multi-gigabyte video.
 */
private const val DEFAULT_MAX_LOCAL_ATTACHMENT_BYTES: Int = 20 * 1024 * 1024

/**
 * Reads a local Android `content://` or `file://` resource and returns an
 * [Attachment] whose [AttachmentSource] is [AttachmentSource.Base64].
 *
 * On Android, attachments captured by the camera or picked from the gallery
 * arrive as opaque [Uri]s that the user-mode process cannot expose directly to
 * a remote backend. This helper bridges the gap: it opens an `InputStream` via
 * the supplied [Context]'s [ContentResolver], reads up to [maxBytes] bytes,
 * and Base64-encodes the payload so the resulting [Attachment] is safe to send
 * over any provider — including [com.ailux.provider.backend.BackendProxyProvider],
 * which would otherwise reject [AttachmentSource.LocalUri] with
 * [com.ailux.core.error.ErrorCode.UNSUPPORTED_MODALITY].
 *
 * The conversion is performed eagerly on the calling thread — callers that
 * read large media files should invoke this helper from a background
 * dispatcher (e.g. `Dispatchers.IO`).
 *
 * @param context  Android context used to obtain a [ContentResolver].
 * @param uri      The local content or file URI pointing to the resource.
 * @param mimeType The IANA media type of the content (e.g. `"image/jpeg"`).
 * @param maxBytes Hard upper bound on the number of bytes read from [uri].
 *                 If the resource exceeds this size the call fails with
 *                 [IllegalArgumentException]. Defaults to 20 MiB.
 * @return An [Attachment] whose source is [AttachmentSource.Base64].
 * @throws IOException              if the URI cannot be opened or read.
 * @throws IllegalArgumentException if the resource is larger than [maxBytes].
 */
@JvmOverloads
fun Attachment.Companion.fromLocalUri(
    context: Context,
    uri: Uri,
    mimeType: String,
    maxBytes: Int = DEFAULT_MAX_LOCAL_ATTACHMENT_BYTES,
): Attachment {
    require(maxBytes > 0) { "maxBytes must be positive, got $maxBytes" }

    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
        readUpTo(input, maxBytes, uri)
    } ?: throw IOException("Cannot open InputStream for uri=$uri")

    return encodeBytesToAttachment(bytes, mimeType)
}

/**
 * Reads from [input] into a fresh byte array, failing fast if the stream
 * yields more than [maxBytes] bytes. Visible for testing.
 */
internal fun readUpTo(input: java.io.InputStream, maxBytes: Int, uri: Any?): ByteArray {
    val capped = ByteArray(maxBytes + 1)
    var total = 0
    while (true) {
        val read = input.read(capped, total, capped.size - total)
        if (read <= 0) break
        total += read
        if (total > maxBytes) {
            throw IllegalArgumentException(
                "Local attachment at $uri exceeds maxBytes=$maxBytes"
            )
        }
    }
    return capped.copyOf(total)
}

/**
 * Pure helper that encodes [bytes] as Base64 (no line wrapping) and wraps it
 * in an [Attachment] with the given [mimeType]. Exposed at internal visibility
 * so the conversion logic can be unit-tested without standing up an Android
 * [Context]/[ContentResolver].
 *
 * Uses an embedded RFC 4648 Base64 encoder (rather than `android.util.Base64`)
 * so the exact same code path runs on the device and inside JVM unit tests —
 * no Robolectric required.
 */
internal fun encodeBytesToAttachment(bytes: ByteArray, mimeType: String): Attachment {
    return Attachment(
        source = AttachmentSource.Base64(base64Encode(bytes)),
        mimeType = mimeType,
    )
}

/**
 * Minimal RFC 4648 §4 standard Base64 encoder (no line wrapping, with `=`
 * padding). Self-contained so JVM unit tests can exercise the same encoding
 * logic that ships on-device.
 */
internal fun base64Encode(bytes: ByteArray): String {
    if (bytes.isEmpty()) return ""
    val table = BASE64_ALPHABET
    val out = StringBuilder(((bytes.size + 2) / 3) * 4)
    var i = 0
    val len = bytes.size
    while (i + 2 < len) {
        val b0 = bytes[i].toInt() and 0xFF
        val b1 = bytes[i + 1].toInt() and 0xFF
        val b2 = bytes[i + 2].toInt() and 0xFF
        out.append(table[b0 ushr 2])
        out.append(table[((b0 and 0x03) shl 4) or (b1 ushr 4)])
        out.append(table[((b1 and 0x0F) shl 2) or (b2 ushr 6)])
        out.append(table[b2 and 0x3F])
        i += 3
    }
    when (len - i) {
        1 -> {
            val b0 = bytes[i].toInt() and 0xFF
            out.append(table[b0 ushr 2])
            out.append(table[(b0 and 0x03) shl 4])
            out.append('=')
            out.append('=')
        }
        2 -> {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = bytes[i + 1].toInt() and 0xFF
            out.append(table[b0 ushr 2])
            out.append(table[((b0 and 0x03) shl 4) or (b1 ushr 4)])
            out.append(table[(b1 and 0x0F) shl 2])
            out.append('=')
        }
    }
    return out.toString()
}

private val BASE64_ALPHABET: CharArray =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray()
