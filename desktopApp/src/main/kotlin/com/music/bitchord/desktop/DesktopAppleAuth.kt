package com.music.bitchord.desktop

/** Credential persistence for Apple Music sessions. No preferences fallback is permitted. */
internal class AppleCookieCredentialStore(
    private val lookup: (Map<String, String>) -> ByteArray?,
    private val store: (String, Map<String, String>, ByteArray) -> Boolean,
    private val remove: (Map<String, String>) -> Boolean,
) {
    private val attributes = mapOf("application" to "bitchord", "service" to "apple_music")

    fun cookie(): String? = lookup(attributes)?.toString(Charsets.UTF_8)?.takeIf(String::isNotBlank)

    fun saveCookie(cookie: String): Boolean = store(
        "BitChord Apple Music session",
        attributes,
        cookie.toByteArray(Charsets.UTF_8),
    )

    fun clear(): Boolean = remove(attributes)
}

internal object DesktopAppleAuth {
    private val storage = AppleCookieCredentialStore(
        lookup = DesktopSecretStore::lookup,
        store = DesktopSecretStore::store,
        remove = DesktopSecretStore::remove,
    )

    fun cookie(): String? = storage.cookie()
    fun saveCookie(cookie: String): Boolean = storage.saveCookie(cookie)
    fun clear(): Boolean = storage.clear()
}
