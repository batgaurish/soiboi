package app.soiboi.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/** Keeps the process alive while downloads run, so Android does not stop a fifty-song playlist when the screen turns off. */
class KeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("downloads", "Downloads", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, "downloads").setContentTitle("Soiboi is downloading").setContentText("Open the app to see progress.").setSmallIcon(android.R.drawable.stat_sys_download).setOngoing(true).build()
        if (android.os.Build.VERSION.SDK_INT >= 29) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(1, notification)
        return START_NOT_STICKY
    }

    companion object {
        fun set(context: Context, active: Boolean) {
            val i = Intent(context, KeepAliveService::class.java)
            if (active) context.startForegroundService(i) else context.stopService(i)
        }
    }
}
