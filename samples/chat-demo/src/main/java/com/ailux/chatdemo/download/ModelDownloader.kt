package com.ailux.chatdemo.download

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Simple model downloader for the demo app layer.
 *
 * This is **NOT** part of the Ailux SDK — it lives in the demo app to demonstrate
 * how a business app might acquire models (ADR-0005 active teaching material).
 * Your app can use any download mechanism: OkHttp, WorkManager, your own CDN, etc.
 *
 * ## Strategy
 *
 * "Check local → download if missing → SHA-256 verify → load"
 *
 * This is just ONE possible source-resolution strategy. Your business can be
 * completely different. The SDK only cares about receiving a valid local path.
 *
 * ## Authentication
 *
 * Some models (e.g. Gemma) are **gated** on HuggingFace and require a token.
 * Open models (e.g. Qwen2-1.5B-Instruct) can be downloaded without authentication.
 * Set [hfToken] when downloading gated models.
 *
 * @param appContext Application context for resolving internal storage paths.
 */
class ModelDownloader(private val appContext: Context) {

    companion object {
        /** Default model directory under app's internal files. */
        private const val MODEL_DIR = "models"

        /** Default model filename. */
        private const val MODEL_FILENAME = "Qwen2_1.5B_Instruct.litertlm"

        /**
         * Expected full model size in bytes (from HuggingFace API: usedStorage).
         * Used to detect truncated/incomplete downloads.
         * Qwen2-1.5B-Instruct: 1,802,843,056 bytes (~1.68 GB)
         */
        private const val EXPECTED_MODEL_SIZE = 1_802_843_056L

        /**
         * Minimum acceptable model file size (90% of expected).
         * Allows for minor variations but catches clearly truncated files.
         */
        private const val MIN_MODEL_SIZE = (EXPECTED_MODEL_SIZE * 0.9).toLong()
    }

    /**
     * Available mirror sources for model download.
     * Default: hf-mirror (China-friendly, no proxy needed), fallback: official HuggingFace.
     *
     * Current default model: Qwen2-1.5B-Instruct (INT8, ~1.7GB, open access, no token needed).
     * Chosen for LiteRT-LM 0.13.1 chat-template compatibility (Qwen3 uses strip() which
     * is unsupported by the embedded miniJinja engine).
     */
    enum class MirrorSource(
        val label: String,
        val baseUrl: String,
    ) {
        HF_MIRROR(
            label = "hf-mirror.com (推荐/国内镜像)",
            baseUrl = "https://hf-mirror.com/litert-community/Qwen2-1.5B-Instruct/resolve/main/Qwen2_1.5B_Instruct.litertlm",
        ),
        HUGGINGFACE_OFFICIAL(
            label = "huggingface.co (官方/需代理)",
            baseUrl = "https://huggingface.co/litert-community/Qwen2-1.5B-Instruct/resolve/main/Qwen2_1.5B_Instruct.litertlm",
        ),
    }

    /**
     * HuggingFace Access Token for downloading gated models.
     *
     * Required because litert-community/Gemma3-1B-IT is a gated model.
     * Users must first accept the Gemma license on HuggingFace, then provide
     * their token here. Without a valid token, downloads will fail with HTTP 403.
     *
     * @see <a href="https://huggingface.co/settings/tokens">HuggingFace Tokens</a>
     */
    var hfToken: String? = null

    /**
     * Expected SHA-256 hash of the model file (from the model card).
     * Set to null to skip verification (not recommended for production).
     *
     * TODO: Update with actual hash from litert-community/Gemma3-1B-IT model card.
     */
    var expectedSha256: String? = null

    /** The directory where models are stored. */
    val modelDir: File
        get() = File(appContext.filesDir, MODEL_DIR).also { it.mkdirs() }

    /** The expected model file path after download. */
    val modelFile: File
        get() = File(modelDir, MODEL_FILENAME)

    /**
     * Check if the model already exists locally AND is not truncated.
     *
     * A truncated model file (e.g. from an interrupted download) will pass
     * a simple exists() check but fail at Engine.initialize() with
     * "TF_LITE_PREFILL_DECODE not found in the model".
     */
    fun isModelAvailable(): Boolean =
        modelFile.exists() && modelFile.length() >= MIN_MODEL_SIZE

    /** Get the absolute path of the downloaded model. */
    fun getModelPath(): String = modelFile.absolutePath

    /**
     * Download the model file, emitting [DownloadProgress] events.
     *
     * Flow semantics:
     * - Emits [DownloadProgress.Started] once at the beginning
     * - Emits [DownloadProgress.InProgress] periodically with bytes/total
     * - Emits [DownloadProgress.Verifying] when SHA-256 check begins
     * - Emits [DownloadProgress.Completed] on success
     * - Emits [DownloadProgress.Failed] on any error
     *
     * Supports resume: if a `.part` file exists, sends Range header to continue.
     *
     * @param source Which mirror to download from.
     */
    fun download(source: MirrorSource = MirrorSource.HF_MIRROR): Flow<DownloadProgress> = flow {
        val partFile = File(modelDir, "$MODEL_FILENAME.part")
        modelDir.mkdirs()

        emit(DownloadProgress.Started(source))

        try {
            val existingBytes = if (partFile.exists()) partFile.length() else 0L

            val connection = openConnectionWithRedirects(
                url = source.baseUrl,
                existingBytes = existingBytes,
            )

            val responseCode = connection.responseCode
            val totalBytes: Long
            val startOffset: Long

            when (responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    // Full download (server doesn't support range or fresh start)
                    totalBytes = connection.contentLengthLong
                    startOffset = 0L
                    // Truncate any existing partial file
                    if (partFile.exists()) partFile.delete()
                }
                HttpURLConnection.HTTP_PARTIAL -> {
                    // Resume download
                    val contentRange = connection.getHeaderField("Content-Range")
                    totalBytes = contentRange?.substringAfter("/")?.toLongOrNull()
                        ?: (existingBytes + connection.contentLengthLong)
                    startOffset = existingBytes
                }
                else -> {
                    throw IOException("HTTP $responseCode: ${connection.responseMessage}")
                }
            }

            var downloadedBytes = startOffset
            val buffer = ByteArray(8192)

            connection.inputStream.use { input ->
                java.io.FileOutputStream(partFile, startOffset > 0).use { output ->
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        emit(DownloadProgress.InProgress(
                            downloadedBytes = downloadedBytes,
                            totalBytes = totalBytes,
                        ))
                    }
                }
            }

            connection.disconnect()

            // Integrity check: verify file size meets minimum threshold.
            // Catches truncated downloads (e.g. network interruption where the
            // server did not return Content-Length, so the loop ends cleanly).
            val actualSize = partFile.length()
            if (actualSize < MIN_MODEL_SIZE) {
                // Don't delete the .part file — allow resume on next attempt
                throw IOException(
                    "Download appears incomplete: got ${actualSize / (1024 * 1024)} MB, " +
                        "expected ~${EXPECTED_MODEL_SIZE / (1024 * 1024)} MB. " +
                        "Please retry to resume download."
                )
            }

            // Verify SHA-256 if expected hash is provided
            val hash = expectedSha256
            if (hash != null) {
                emit(DownloadProgress.Verifying)
                val actualHash = computeSha256(partFile)
                if (!actualHash.equals(hash, ignoreCase = true)) {
                    partFile.delete()
                    throw SecurityException(
                        "SHA-256 mismatch!\n" +
                            "Expected: $hash\n" +
                            "Actual:   $actualHash\n" +
                            "File deleted. Please retry or switch mirror source."
                    )
                }
            }

            // Atomic rename: .part → final filename
            if (modelFile.exists()) modelFile.delete()
            if (!partFile.renameTo(modelFile)) {
                throw IOException("Failed to rename .part file to final path")
            }

            emit(DownloadProgress.Completed(modelFile.absolutePath))

        } catch (e: Exception) {
            emit(DownloadProgress.Failed(
                error = e,
                canRetry = e is IOException,
                suggestion = when {
                    e is SecurityException -> "SHA-256 verification failed. The file may be corrupted or tampered. Try switching mirror source."
                    e.message?.contains("403") == true ->
                        "Access denied (HTTP 403). If this is a gated model, please:\n" +
                            "1. Visit the model page on huggingface.co\n" +
                            "2. Accept the license agreement\n" +
                            "3. Set your HF token in local.properties: hf.token=hf_xxx"
                    e.message?.contains("401") == true ->
                        "Authentication failed (HTTP 401). Your HF token may be invalid or expired. " +
                            "Please check your token at https://huggingface.co/settings/tokens"
                    e.message?.contains("timeout", ignoreCase = true) == true ->
                        "Connection timed out. Check your network and retry."
                    source == MirrorSource.HF_MIRROR ->
                        "Download failed. Try switching to official HuggingFace (may need proxy)."
                    else ->
                        "Download failed. Try switching to hf-mirror (China-friendly) or use SAF file picker."
                }
            ))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Delete the downloaded model and any partial files.
     */
    suspend fun deleteModel() = withContext(Dispatchers.IO) {
        modelFile.delete()
        File(modelDir, "$MODEL_FILENAME.part").delete()
    }

    /**
     * Open a connection to [url], manually following up to [maxRedirects] redirects.
     *
     * Java's HttpURLConnection auto-redirect does NOT follow cross-scheme (HTTP→HTTPS)
     * or cross-host redirects. HuggingFace / hf-mirror always 302-redirect to a CDN host,
     * so we must handle this manually to get correct Content-Length from the final response.
     */
    private fun openConnectionWithRedirects(
        url: String,
        existingBytes: Long,
        maxRedirects: Int = 5,
    ): HttpURLConnection {
        var currentUrl = url
        var redirectCount = 0

        while (true) {
            val connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                instanceFollowRedirects = false // We handle redirects manually
                setRequestProperty("User-Agent", "Ailux-Demo/0.3.0")
                val token = hfToken
                if (!token.isNullOrEmpty()) {
                    setRequestProperty("Authorization", "Bearer $token")
                }
                if (existingBytes > 0) {
                    setRequestProperty("Range", "bytes=$existingBytes-")
                }
            }

            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                    ?: throw IOException("HTTP $code redirect with no Location header")
                connection.disconnect()

                redirectCount++
                if (redirectCount > maxRedirects) {
                    throw IOException("Too many redirects (>$maxRedirects)")
                }

                // Resolve relative redirects
                currentUrl = if (location.startsWith("http")) {
                    location
                } else {
                    URL(URL(currentUrl), location).toString()
                }
            } else {
                return connection
            }
        }
    }

    /**
     * Compute SHA-256 hash of a file using streaming (memory-efficient for large files).
     */
    private fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        file.inputStream().use { input ->
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Get a human-readable file size string.
     */
    private fun File.sizeLabel(): String {
        val mb = length().toDouble() / (1024 * 1024)
        return "%.1f MB".format(mb)
    }
}

/**
 * Sealed class representing download progress events.
 */
sealed class DownloadProgress {
    data class Started(val source: ModelDownloader.MirrorSource) : DownloadProgress()

    data class InProgress(
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : DownloadProgress() {
        val progressFraction: Float
            get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes) else 0f
        val downloadedMb: String
            get() = "%.1f".format(downloadedBytes.toDouble() / (1024 * 1024))
        val totalMb: String
            get() = "%.1f".format(totalBytes.toDouble() / (1024 * 1024))
    }

    data object Verifying : DownloadProgress()

    data class Completed(val modelPath: String) : DownloadProgress()

    data class Failed(
        val error: Exception,
        val canRetry: Boolean,
        val suggestion: String,
    ) : DownloadProgress()
}
