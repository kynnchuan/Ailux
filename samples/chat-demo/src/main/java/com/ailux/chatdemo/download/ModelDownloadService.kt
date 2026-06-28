package com.ailux.chatdemo.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ailux.chatdemo.MainActivity
import com.ailux.chatdemo.R

/**
 * Foreground service that shows model download progress in the notification tray.
 *
 * This service does NOT perform the actual download — that is handled by
 * [ModelDownloadViewModel] / [ModelDownloader]. This service is purely a
 * notification host to keep the process alive and show progress when the
 * user navigates away from the app.
 *
 * ## Lifecycle
 *
 * 1. Started by [ModelDownloadViewModel] when download begins.
 * 2. Updated via [updateProgress] as download progresses.
 * 3. Stopped when download completes, fails, or is cancelled.
 */
class ModelDownloadService : Service() {

    companion object {
        private const val CHANNEL_ID = "model_download"
        private const val NOTIFICATION_ID = 1001

        /** Start the foreground service. */
        fun start(context: Context) {
            val intent = Intent(context, ModelDownloadService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Stop the foreground service. */
        fun stop(context: Context) {
            context.stopService(Intent(context, ModelDownloadService::class.java))
        }
    }

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): ModelDownloadService = this@ModelDownloadService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification(
            title = "Downloading model...",
            text = "Preparing download",
            progress = 0,
            indeterminate = true,
        )
        startForeground(NOTIFICATION_ID, notification)
        return START_NOT_STICKY
    }

    /** Update notification with current download progress. */
    fun updateProgress(progressPercent: Int, statusText: String) {
        val notification = buildNotification(
            title = "Downloading model",
            text = "$statusText ($progressPercent%)",
            progress = progressPercent,
            indeterminate = false,
        )
        getNotificationManager().notify(NOTIFICATION_ID, notification)
    }

    /** Show download complete notification and stop service. */
    fun notifyComplete() {
        val notification = buildNotification(
            title = "Model download complete",
            text = "Qwen2-1.5B-Instruct is ready to use",
            progress = 100,
            indeterminate = false,
            ongoing = false,
        )
        getNotificationManager().notify(NOTIFICATION_ID, notification)
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    /** Show download failed notification and stop service. */
    fun notifyFailed(errorMessage: String) {
        val notification = buildNotification(
            title = "Model download failed",
            text = errorMessage,
            progress = 0,
            indeterminate = false,
            ongoing = false,
        )
        getNotificationManager().notify(NOTIFICATION_ID, notification)
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    /** Show cancelled state and stop service. */
    fun notifyCancelled() {
        getNotificationManager().cancel(NOTIFICATION_ID)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(
        title: String,
        text: String,
        progress: Int,
        indeterminate: Boolean,
        ongoing: Boolean = true,
    ): Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(100, progress, indeterminate)
            .setOngoing(ongoing)
            .setContentIntent(pendingIntent)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Model Download",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows download progress for on-device AI models"
                setShowBadge(false)
            }
            getNotificationManager().createNotificationChannel(channel)
        }
    }

    private fun getNotificationManager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
}
