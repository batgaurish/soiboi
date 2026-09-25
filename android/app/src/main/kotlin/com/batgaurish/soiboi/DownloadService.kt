package com.batgaurish.soiboi

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Keeps the app alive while the download queue has work.
 *
 * The downloads themselves run where they always did: the Dart queue drives
 * the Python pipeline in this process. What changes is the process's standing.
 * Without a foreground service Android freezes or kills a backgrounded app
 * within minutes, and a locked phone lets the CPU and Wi-Fi sleep, so a long
 * playlist stopped as soon as the screen went off. The Dart side survives the
 * activity being swiped away already: MainActivity uses audio_service's cached
 * engine.
 *
 * The service's notification is the download progress notification, posted
 * through [NotificationBridge]: an ongoing progress notification starts this
 * service, updates to it just replace the notification, and dismissing it
 * stops the service.
 */
class DownloadService : Service() {

    companion object {
        /** The notification the next start should go foreground with. */
        @Volatile
        private var pending: Pair<Int, Notification>? = null

        /** The id of the notification this service is showing, while running. */
        @Volatile
        private var runningId: Int? = null

        /** Shows [notification] as the foreground notification, starting the
         * service if it is not running. */
        fun show(context: Context, id: Int, notification: Notification) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (runningId == id) {
                manager.notify(id, notification)
                return
            }
            pending = id to notification
            val intent = Intent(context, DownloadService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                // Android 12+ refuses to start one from the background. The
                // queue is started from the app, so this is rare; show the
                // notification anyway and let the download run as long as
                // Android allows.
                pending = null
                manager.notify(id, notification)
            }
        }

        /** Stops the service if it is showing [id]. Returns whether it was. */
        fun stopIfShowing(context: Context, id: Int): Boolean {
            if (runningId != id && pending?.first != id) return false
            pending = null
            context.stopService(Intent(context, DownloadService::class.java))
            return true
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val (id, notification) = pending ?: run {
            // Restarted by the system with nothing to show: nothing to do.
            stopSelf()
            return START_NOT_STICKY
        }
        pending = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(id, notification)
        }
        runningId = id
        holdLocks()
        return START_NOT_STICKY
    }

    private fun holdLocks() {
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "soiboi:downloads")
                .apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }
        if (wifiLock == null) {
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            } else {
                WifiManager.WIFI_MODE_FULL
            }
            wifiLock = applicationContext.getSystemService(WifiManager::class.java)
                ?.createWifiLock(mode, "soiboi:downloads")
                ?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }
    }

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock = null
        runningId = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        super.onDestroy()
    }
}
