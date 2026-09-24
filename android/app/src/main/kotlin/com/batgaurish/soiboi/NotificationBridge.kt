package com.batgaurish.soiboi

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/**
 * Posts, updates and dismisses the app's own notifications for
 * `notification_service.dart`.
 *
 * Playback has its own notification, owned by audio_service; this is for
 * everything else. Each kind of notification is its own channel, so the user
 * can silence download progress in Android's settings and still hear about
 * finished downloads and updates.
 *
 * Button presses come back as a broadcast to a receiver registered here and
 * are passed to Dart as a "tap" call. A tap on the notification itself just
 * opens the app.
 */
class NotificationBridge(engine: FlutterEngine, private val context: Context) {

    companion object {
        private const val METHOD = "com.batgaurish.soiboi/notifications"
        private const val ACTION = "com.batgaurish.soiboi.NOTIFICATION_ACTION"
        private const val EXTRA_ID = "id"
        private const val EXTRA_ACTION = "action"

        const val CHANNEL_PROGRESS = "download_progress"
        const val CHANNEL_RESULTS = "download_results"
        const val CHANNEL_UPDATES = "updates"

        /** The bridge for the current engine; an earlier one is released. */
        private var current: NotificationBridge? = null
    }

    private val manager = context.getSystemService(NotificationManager::class.java)
    private val channel = MethodChannel(engine.dartExecutor.binaryMessenger, METHOD)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            channel.invokeMethod(
                "tap",
                mapOf(
                    "id" to intent.getIntExtra(EXTRA_ID, 0),
                    "action" to (intent.getStringExtra(EXTRA_ACTION) ?: "default"),
                ),
            )
        }
    }

    init {
        // The activity can configure a new engine more than once in a process;
        // only the newest one should receive button presses.
        current?.release()
        current = this

        createChannels()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, IntentFilter(ACTION), Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, IntentFilter(ACTION))
        }
        channel.setMethodCallHandler { call, result ->
            when (call.method) {
                "show" -> {
                    show(call)
                    result.success(null)
                }
                "dismiss" -> {
                    manager.cancel(call.argument<Int>("id") ?: 0)
                    result.success(null)
                }
                "enabled" -> result.success(manager.areNotificationsEnabled())
                else -> result.notImplemented()
            }
        }
    }

    private fun release() {
        try {
            context.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
            // Never registered.
        }
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(
                    CHANNEL_PROGRESS,
                    "Download progress",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "What is downloading right now"
                    setShowBadge(false)
                },
                NotificationChannel(
                    CHANNEL_RESULTS,
                    "Finished downloads",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = "What downloaded, and what failed and why" },
                NotificationChannel(
                    CHANNEL_UPDATES,
                    "App updates",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = "A new version of Soiboi is available" },
            ),
        )
    }

    private fun show(call: MethodCall) {
        val id = call.argument<Int>("id") ?: return
        // Without the permission (Android 13+) the post would only be dropped.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val kind = call.argument<String>("channel") ?: CHANNEL_RESULTS
        val body = call.argument<String>("body") ?: ""
        val ongoing = call.argument<Boolean>("ongoing") ?: false
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, kind)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        builder
            .setSmallIcon(R.drawable.ic_stat_soiboi)
            .setContentTitle(call.argument<String>("title") ?: "")
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(openApp())
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setCategory(
                if (kind == CHANNEL_PROGRESS) Notification.CATEGORY_PROGRESS
                else Notification.CATEGORY_STATUS,
            )
        call.argument<Int>("progress")?.let { progress ->
            builder.setProgress(100, progress.coerceIn(0, 100), progress < 0)
        }
        call.argument<List<List<String>>>("actions")?.forEachIndexed { index, action ->
            if (action.size == 2) {
                builder.addAction(
                    Notification.Action.Builder(null, action[1], actionIntent(id, index, action[0]))
                        .build(),
                )
            }
        }
        manager.notify(id, builder.build())
    }

    private fun openApp(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun actionIntent(id: Int, index: Int, action: String): PendingIntent {
        val intent = Intent(ACTION)
            .setPackage(context.packageName)
            .putExtra(EXTRA_ID, id)
            .putExtra(EXTRA_ACTION, action)
        // A distinct request code per button, or Android hands every button
        // the same PendingIntent and the last one's extras win.
        return PendingIntent.getBroadcast(
            context,
            id * 8 + index,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
