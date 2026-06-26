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
 * @param appContext Application context for resolving internal storage paths.
 */
class ModelDownloader(private val appContext: Context) {

    companion object {
        /** Default model directory under app's internal files. */
        private const val MODEL_DIR = "models"

        /** Default model filename. */
        private const val MODEL_FILENAME = "gemma3-1b-it.task"
    }

    /**
     * Available mirror sources for model download.
     * Default: hf-mirror (China-friendly), fallback: official HuggingFace.
     */
    enum class MirrorSource(
        val label: String,
        val baseUrl: String,
    ) {
        HF_MIRROR(
            label = "hf-mirror.com (推荐/国内镜像)",
            baseUrl = "https://hf-mirror.com/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.task",
        ),
        HUGGINGFACE_OFFICIAL(
            label = "huggingface.co (官方/需代理)",
            baseUrl = "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.task",
        ),
    }

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

    /** Check if the model already exists locally. */
    fun isModelAvailable(): Boolean = modelFile.exists() && modelFile.length() > 0

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

            val connection = (URL(source.baseUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", "Ailux-Demo/0.3.0")
                if (existingBytes > 0) {
                    setRequestProperty("Range", "bytes=$existingBytes-")
                }
            }

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
