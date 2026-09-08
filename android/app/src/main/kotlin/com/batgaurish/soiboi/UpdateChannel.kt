package com.batgaurish.soiboi

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.io.File

/**
 * Hands a downloaded APK to Android's package installer.
 *
 * Distribution here is private and sideloaded, so there is no store to install
 * an update. Android will not open an APK from a `file://` URI across an app
 * boundary since API 24, so the file is exposed through this app's own
 * FileProvider as a `content://` URI and the read permission is granted to the
 * installer for the life of the intent.
 *
 * This only *opens* the installer. The user still confirms the install in
 * Android's own UI, and on API 26+ they must have granted this app permission
 * to install unknown apps — the settings screen for which is what the
 * `canRequestPackageInstalls` branch sends them to, rather than failing with
 * an error they cannot act on.
 */
class UpdateChannel(engine: FlutterEngine, private val context: android.content.Context) {

    companion object {
        private const val METHOD = "com.batgaurish.soiboi/update"
    }

    init {
        MethodChannel(engine.dartExecutor.binaryMessenger, METHOD)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "install" -> {
                        val path = call.argument<String>("path")
                        if (path == null) {
                            result.success("No file to install")
                        } else {
                            result.success(install(path))
                        }
                    }
                    else -> result.notImplemented()
                }
            }
    }

    /** Returns null on success, or a message explaining what stopped it. */
    private fun install(path: String): String? {
        val file = File(path)
        if (!file.exists()) return "The downloaded file is gone: $path"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            // Sent to the right settings page rather than reported as a dead
            // end: the permission is per-app and only the user can grant it.
            return try {
                val settings = Intent(
                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(settings)
                "Allow Soiboi to install apps, then tap Install again."
            } catch (e: Exception) {
                "Allow installing unknown apps for Soiboi in Android settings."
            }
        }

        return try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            null
        } catch (e: Exception) {
            "Could not open the installer: ${e.message}"
        }
    }
}
