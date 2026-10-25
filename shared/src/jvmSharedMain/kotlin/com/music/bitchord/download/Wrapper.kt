package com.music.bitchord.download

import java.io.File
import java.net.ServerSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

const val APPLE_MUSIC_APK_URL =
    "https://www.apkmirror.com/apk/apple/apple-music/apple-music-3-6-0-beta-release/" +
        "apple-music-3-6-0-beta-4-android-apk-download/"
const val APPLE_MUSIC_APK_LABEL = "Apple Music 3.6.0-beta, build 1109 (arm64-v8a + x86_64)"

enum class WrapperStage {
    Unsupported, NeedsLibraries, Stopped, Starting, SignedOut, NeedsCode, Ready, Failed
}

data class WrapperState(
    val stage: WrapperStage,
    val message: String? = null,
    val account: String? = null
)

class WrapperPlatform(
    val isAndroid: Boolean,
    val abi: String,
    val nativeLibraryDir: File? = null,
    val bundleCandidates: List<File> = emptyList(),
)

/**
 * The bundled lossless wrapper (glomatico wrapper-v2).
 * Used for ALAC decryption when configured.
 */
class WrapperService(
    private val installDir: File,
    private val platform: WrapperPlatform,
) {
    private val _state = MutableStateFlow(WrapperState(WrapperStage.Stopped))
    val state: StateFlow<WrapperState> = _state.asStateFlow()

    private val _signedIn = MutableStateFlow(false)
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    private var process: Process? = null
    private var httpPort: Int? = null
    private var decryptPort: Int? = null

    val supported: Boolean get() = bundleDir() != null || platform.isAndroid

    private val libDir: File
        get() = if (platform.isAndroid) File(installDir, "lib") else File(installDir, "rootfs/system/lib64")
    val baseUrl: String get() = "http://127.0.0.1:$httpPort"
    private val marker: File get() = File(installDir, "signed_in")

    val librariesInstalled: Boolean get() = File(libDir, "libandroidappmusic.so").exists()

    private fun bundleDir(): File? {
        if (platform.isAndroid) {
            val dir = platform.nativeLibraryDir
            return dir?.takeIf { File(it, "libwrapperd.so").exists() }
        }
        return platform.bundleCandidates.firstOrNull { File(it, "wrapperd").exists() }
    }

    fun refresh() {
        _signedIn.value = supported && librariesInstalled && marker.exists()
        when {
            !supported || bundleDir() == null -> _state.value = WrapperState(
                WrapperStage.Unsupported, "The lossless wrapper is not part of this build."
            )
            !librariesInstalled -> _state.value = WrapperState(WrapperStage.NeedsLibraries)
            process == null -> _state.value = WrapperState(WrapperStage.Stopped)
        }
    }

    val downloadPayload: Map<String, Any>?
        get() = if (_state.value.stage == WrapperStage.Ready && httpPort != null && decryptPort != null) mapOf(
            "use_wrapper" to true,
            "wrapper_url" to baseUrl,
            "wrapper_decrypt_port" to decryptPort!!
        ) else null

    suspend fun stop() {
        val p = process
        process = null
        if (p == null) return
        withContext(Dispatchers.IO) {
            p.destroy()
        }
        refresh()
    }
}
