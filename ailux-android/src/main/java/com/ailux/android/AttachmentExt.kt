package com.ailux.android

import android.content.Context
import android.net.Uri
import com.ailux.core.request.Attachment
import com.ailux.core.request.AttachmentSource

/**
 * Creates an [Attachment] from a local Android `content://` or `file://` URI.
 *
 * This is a convenience factory for use on Android where attachments originate
 * from the device (e.g. camera captures, gallery picks via `MediaStore`).
 *
 * **Important**: [BackendProxyProvider] cannot send [AttachmentSource.LocalUri]
 * directly (the backend has no access to local files). Before sending over a
 * backend proxy, callers should read the content and convert to
 * [AttachmentSource.Base64]. A future version of this helper will perform the
 * Base64 conversion automatically using [context]'s `ContentResolver`.
 *
 * @param context  Android context used to resolve the URI (reserved for future
 *                 automatic Base64 conversion).
 * @param uri      The local content or file URI pointing to the resource.
 * @param mimeType The IANA media type of the content (e.g. `"image/jpeg"`).
 * @return An [Attachment] wrapping the URI as [AttachmentSource.LocalUri].
 */
fun Attachment.Companion.fromLocalUri(context: Context, uri: Uri, mimeType: String): Attachment {
    return Attachment(
        source = AttachmentSource.LocalUri(uri.toString()),
        mimeType = mimeType
    )
}
