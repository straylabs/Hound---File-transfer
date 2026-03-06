package com.straylabs.hound.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.straylabs.hound.MainActivity
import com.straylabs.hound.R

class ServerForegroundService : Service() {

    companion object {
        private const val TAG = "ServerForegroundService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "server_channel"

        fun start(context: Context, port: Int, rootPath: String?) {
            val intent = Intent(context, ServerForegroundService::class.java).apply {
                putExtra("port", port)
                putExtra("rootPath", rootPath)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ServerForegroundService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val port = intent?.getIntExtra("port", LocalHttpServer.DEFAULT_PORT) ?: LocalHttpServer.DEFAULT_PORT
        val rootPath = intent?.getStringExtra("rootPath")

        val notification = createNotification(port, rootPath)
        startForeground(NOTIFICATION_ID, notification)

        Log.d(TAG, "Server service started on port $port")
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // App was swiped away from recents — stop the service so the
        // notification doesn't linger while the HTTP server is no longer running.
        Log.d(TAG, "Task removed — stopping server service")
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "LAN File Server",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the file server running in the background"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(port: Int, rootPath: String?): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentText = if (rootPath != null) {
            "Serving: $rootPath"
        } else {
            "Server running on port $port"
        }

        val largeIcon = BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Hound")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setLargeIcon(largeIcon)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Cancel the standalone "Server Running" notification posted by NotificationHelper.
        // The foreground notification (ID 1) is removed automatically by the system,
        // but the helper notification (ID 1001) must be cancelled explicitly.
        getSystemService(NotificationManager::class.java)
            ?.cancel(com.straylabs.hound.util.NotificationHelper.NOTIFICATION_ID_SERVER)
        Log.d(TAG, "Server service stopped")
    }
}
