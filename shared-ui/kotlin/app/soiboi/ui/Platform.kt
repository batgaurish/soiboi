package app.soiboi.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import app.soiboi.engine.Engine

/** What the UI needs from the operating system. The desktop and Android apps each supply one. */
interface PlatformUi {
    val isAndroid: Boolean
    fun decodeImage(bytes: ByteArray): ImageBitmap?
    suspend fun pickFolder(): String?
    suspend fun pickFile(extensions: List<String>): String?
    suspend fun pickSavePath(suggestedName: String): String?
    fun openUrl(url: String)
    fun copyToClipboard(text: String)
    /** Tells Android to keep the process alive while downloads run; a no-op on desktop. */
    /** The in-app browser used for sign-ins and guided downloads. */
    val web: app.soiboi.ui.web.WebPaneFactory
    fun setDownloading(active: Boolean) {}
}

val LocalPlatform = compositionLocalOf<PlatformUi> { error("PlatformUi missing") }
val LocalEngine = compositionLocalOf<Engine> { error("Engine missing") }
