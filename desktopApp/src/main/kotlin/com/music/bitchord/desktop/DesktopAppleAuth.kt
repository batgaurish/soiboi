package com.music.bitchord.desktop

/** Credential persistence for Apple Music sessions. No preferences fallback is permitted.
 *
 * Two entries are stored under different SecretService attributes:
 *  - Cookie header  ("apple_music")         – `name=value; …` for catalog API callers
 *  - Netscape jar   ("apple_music_netscape") – Mozilla/Netscape format for archive adapters
 *
 * Archive adapters read the Netscape jar via MozillaCookieJar(path).load(); catalog API
 * callers (AppleHomeCatalog) receive the Cookie header directly. Both are stored encrypted
 * at rest via the platform's Secret Service. If the store is unavailable both return null
 * and callers must fail closed.
 */
internal class AppleCookieCredentialStore(
    private val lookup: (Map<String, String>) -> ByteArray?,
    private val store: (String, Map<String, String>, ByteArray) -> Boolean,
    private val remove: (Map<String, String>) -> Boolean,
) {
    // Separate attribute maps so the two entries never collide in the keyring.
    private val headerAttributes = mapOf("application" to "bitchord", "service" to "apple_music")
    private val jarAttributes = mapOf("application" to "bitchord", "service" to "apple_music_netscape")

    /** The Cookie header (`name=value; …`) for catalog API callers, or null if not stored. */
    fun cookie(): String? = lookup(headerAttributes)?.toString(Charsets.UTF_8)?.takeIf(String::isNotBlank)

    /** Saves the Cookie header. Returns false when the Secret Service is unavailable. */
    fun saveCookie(cookie: String): Boolean = store(
        "BitChord Apple Music session",
        headerAttributes,
        cookie.toByteArray(Charsets.UTF_8),
    )

    /**
     * The Netscape/Mozilla cookie jar for archive adapters (apple_auth.py / MozillaCookieJar),
     * or null if not stored.
     */
    fun netscapeJar(): String? = lookup(jarAttributes)?.toString(Charsets.UTF_8)?.takeIf(String::isNotBlank)

    /**
     * Saves the Netscape jar. Returns false when the Secret Service is unavailable.
     *
     * The jar must start with "# Netscape HTTP Cookie File" and contain tab-separated lines
     * as expected by python's http.cookiejar.MozillaCookieJar.load(). Archive adapters write
     * it verbatim to a temp file and pass the path to apple_auth.open_api().
     */
    fun saveNetscapeJar(jar: String): Boolean = store(
        "BitChord Apple Music cookie jar",
        jarAttributes,
        jar.toByteArray(Charsets.UTF_8),
    )

    /** Clears both the Cookie header and the Netscape jar from the keyring. */
    fun clear(): Boolean = remove(headerAttributes) && remove(jarAttributes)
}

internal object DesktopAppleAuth {
    private val storage = AppleCookieCredentialStore(
        lookup = DesktopSecretStore::lookup,
        store = DesktopSecretStore::store,
        remove = DesktopSecretStore::remove,
    )

    /** Cookie header for catalog API callers, or null when not signed in. */
    fun cookie(): String? = storage.cookie()

    /** Saves the Cookie header. Returns false when SecretService is unavailable. */
    fun saveCookie(cookie: String): Boolean = storage.saveCookie(cookie)

    /** Netscape jar for archive adapters (apple_auth.py), or null when not signed in. */
    fun netscapeJar(): String? = storage.netscapeJar()

    /** Saves the Netscape jar. Returns false when SecretService is unavailable. */
    fun saveNetscapeJar(jar: String): Boolean = storage.saveNetscapeJar(jar)

    /** Clears both representations from the keyring. */
    fun clear(): Boolean = storage.clear()
}
