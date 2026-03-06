package com.straylabs.hound.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.straylabs.hound.MainActivity
import com.straylabs.hound.R

class NotificationHelper(private val context: Context) {

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createChannels()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Server status channel
            val serverChannel = NotificationChannel(
                CHANNEL_SERVER,
                "Server Status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifications about server status"
                setShowBadge(false)
            }

            // Transfer channel
            val transferChannel = NotificationChannel(
                CHANNEL_TRANSFERS,
                "File Transfers",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifications about file uploads and downloads"
                setShowBadge(false)
            }

            notificationManager.createNotificationChannels(listOf(serverChannel, transferChannel))
        }
    }

    fun showServerRunningNotification(url: String, port: Int) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_SERVER)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Server Running")
            .setContentText("http://${url}:${port}")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Server running at http://${url}:${port}"))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        notificationManager.notify(NOTIFICATION_ID_SERVER, notification)
    }

    fun showServerStoppedNotification() {
        notificationManager.cancel(NOTIFICATION_ID_SERVER)
    }

    fun showDownloadNotification(fileName: String, progress: Int) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_TRANSFERS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Downloading")
            .setContentText("$fileName - $progress%")
            .setProgress(100, progress, false)
            .setContentIntent(pendingIntent)
            .setOngoing(progress < 100)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()

        notificationManager.notify(NOTIFICATION_ID_DOWNLOAD, notification)

        if (progress >= 100) {
            showDownloadCompleteNotification(fileName)
        }
    }

    private fun showDownloadCompleteNotification(fileName: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_TRANSFERS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Download Complete")
            .setContentText(fileName)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()

        notificationManager.notify(NOTIFICATION_ID_DOWNLOAD_COMPLETE, notification)
    }

    fun showUploadNotification(fileName: String, progress: Int) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_TRANSFERS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Uploading")
            .setContentText("$fileName - $progress%")
            .setProgress(100, progress, false)
            .setContentIntent(pendingIntent)
            .setOngoing(progress < 100)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()

        notificationManager.notify(NOTIFICATION_ID_UPLOAD, notification)

        if (progress >= 100) {
            showUploadCompleteNotification(fileName)
        }
    }

    private fun showUploadCompleteNotification(fileName: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_TRANSFERS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Upload Complete")
            .setContentText(fileName)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()

        notificationManager.notify(NOTIFICATION_ID_UPLOAD_COMPLETE, notification)
    }

    fun cancelDownloadNotification() {
        notificationManager.cancel(NOTIFICATION_ID_DOWNLOAD)
    }

    fun cancelUploadNotification() {
        notificationManager.cancel(NOTIFICATION_ID_UPLOAD)
    }

    companion object {
        const val CHANNEL_SERVER = "server_status"
        const val CHANNEL_TRANSFERS = "file_transfers"

        const val NOTIFICATION_ID_SERVER = 1001
        const val NOTIFICATION_ID_DOWNLOAD = 1002
        const val NOTIFICATION_ID_DOWNLOAD_COMPLETE = 1003
        const val NOTIFICATION_ID_UPLOAD = 1004
        const val NOTIFICATION_ID_UPLOAD_COMPLETE = 1005
    }
}
