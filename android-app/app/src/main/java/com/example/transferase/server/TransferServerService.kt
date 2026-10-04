package com.example.transferase.server

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.transferase.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class TransferServerService : Service() {

    companion object {
        const val ACTION_START = "com.example.transferase.ACTION_START"
        const val ACTION_STOP = "com.example.transferase.ACTION_STOP"
        const val CHANNEL_ID = "transferase_server_channel"
        const val NOTIFICATION_ID = 4000

        private val _isServerRunning = MutableStateFlow(false)
        val isServerRunning = _isServerRunning.asStateFlow()

        private val _serverUrl = MutableStateFlow("")
        val serverUrl = _serverUrl.asStateFlow()

        fun startService(context: Context) {
            val intent = Intent(context, TransferServerService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, TransferServerService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private var server: EmbeddedHttpServer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startServer()
            ACTION_STOP -> stopServer()
        }
        return START_NOT_STICKY
    }

    private fun startServer() {
        if (_isServerRunning.value) return

        val netInfo = NetworkUtils.getNetworkInfo(this)
        val url = "http://${netInfo.primaryIp}:4000"
        _serverUrl.value = url

        server = EmbeddedHttpServer(this, 4000) { fileName, size ->
            updateNotification("Received file: $fileName")
        }
        server?.start()

        // Acquire partial wake lock
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Transferase::ServerWakeLock").apply {
                acquire(12 * 60 * 60 * 1000L) // 12 hours max
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val notification = buildNotification(url)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        _isServerRunning.value = true
    }

    private fun stopServer() {
        server?.stop()
        server = null

        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        _isServerRunning.value = false
        _serverUrl.value = ""
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(urlText: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingOpen = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, TransferServerService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Transferase Server Running")
            .setContentText("Access at $urlText")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(pendingOpen)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", pendingStop)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val url = _serverUrl.value
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Transferase Server: $url")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Transferase Local Server",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the local file server active in the background"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
