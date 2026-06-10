package com.ailux.core.request

import kotlinx.serialization.Serializable

/**
 * A multimodal attachment to be sent alongside a user message.
 *
 * Attachments are intentionally **flat** (no Image/Audio/File subclasses) so that
 * adding new modalities (video, 3D, etc.) requires zero breaking changes — the
 * [mimeType] field drives modality semantics, while [source] describes how to
 * obtain the raw bytes.
 *
 * Serialization into the wire format (e.g. OpenAI `content` parts, Anthropic
 * `image` blocks) is handled by the [RequestMapper] implementation.
 *
 * @property source   Where the attachment data comes from (URL, inline Base64, or local URI).
 * @property mimeType The IANA media type of the content (e.g. `"image/png"`, `"application/pdf"`).
 *                    This is the single source of truth for modality — providers use it to decide
 *                    how to encode the attachment in their protocol.
 */
@Serializable
data class Attachment(
    val source: AttachmentSource,
    val mimeType: String
)

/**
 * Describes how to obtain the raw bytes of an [Attachment].
 *
 * Three sealed variants cover the transport modes that matter on Android:
 * - [Url] — remote resource, fetched by whichever end has network access (backend / connected device).
 * - [Base64] — inline data, universally supported by all providers.
 * - [LocalUri] — local `file://` or `content://` resource, readable only by on-device providers.
 *   For [BackendProxyProvider], use the `Attachment.fromLocalUri()` helper in `ailux-android`
 *   to convert to [Base64] before sending; otherwise the provider will emit
 *   [ErrorCode.UNSUPPORTED_MODALITY].
 */
@Serializable
sealed class AttachmentSource {

    /** Remote resource accessible via HTTP(S). */
    @Serializable data class Url(val url: String) : AttachmentSource()

    /** Inline Base64-encoded data. Universally supported by all providers. */
    @Serializable data class Base64(val data: String) : AttachmentSource()

    /** Local resource (file:// or content:// URI). Only usable by on-device providers. */
    @Serializable data class LocalUri(val uri: String) : AttachmentSource()
}