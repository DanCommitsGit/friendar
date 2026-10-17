package io.github.dancommitsgit.friendar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder

/**
 * Keeps the process in the foreground while the app searches, so Bluetooth, UWB and
 * Wi-Fi ranging continue when the screen is off or another app is in front.
 */
class LocatorService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        starting = false
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        try {
            startForeground(NOTIFICATION_ID, notification, type)
        } catch (_: RuntimeException) {
            stopSelf()
        }
        // Stopped while this start was still queued.
        if (!wanted) stopSelf()
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL_ID = "searching"
        private const val NOTIFICATION_ID = 1

        // Main thread only. Calling stopService before a started service has called startForeground crashes the app.
        private var wanted = false
        private var starting = false

        fun start(context: Context) {
            wanted = true
            if (starting) return
            starting = true
            val component = runCatching {
                context.startForegroundService(Intent(context, LocatorService::class.java))
            }.getOrNull()
            if (component == null) starting = false
        }

        fun stop(context: Context) {
            wanted = false
            if (!starting) context.stopService(Intent(context, LocatorService::class.java))
        }
    }
}
