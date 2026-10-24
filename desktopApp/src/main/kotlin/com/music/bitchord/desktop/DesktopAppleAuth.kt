package com.music.bitchord.desktop

/**
 * Encrypted/keyring-backed credential storage for Apple Music web sessions.
 * Follows DesktopAccounts pattern: Linux Secret Service first, preference chunks fallback.
 */
internal object DesktopAppleAuth {
    private val attributes = mapOf("application" to "bitchord", "service" to "apple_music")
    private const val FALLBACK_KEY = "apple_music_cookie"
    private val persistence get() = DesktopPersistence()

    fun cookie(): String? {
        return DesktopSecretStore.lookup(attributes)?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() }
            ?: DesktopPreferenceChunks.read(persistence.preferences, FALLBACK_KEY)?.takeIf { it.isNotBlank() }
    }

    fun saveCookie(cookie: String) {
        val stored = DesktopSecretStore.store(
            label = "BitChord — Apple Music session",
            attributes = attributes,
            secret = cookie.toByteArray(Charsets.UTF_8),
        )
        if (stored) {
            DesktopPreferenceChunks.write(persistence.preferences, FALLBACK_KEY, "")
        } else {
            DesktopPreferenceChunks.write(persistence.preferences, FALLBACK_KEY, cookie)
        }
    }

    fun clear() {
        DesktopSecretStore.remove(attributes)
        DesktopPreferenceChunks.write(persistence.preferences, FALLBACK_KEY, "")
    }
}
