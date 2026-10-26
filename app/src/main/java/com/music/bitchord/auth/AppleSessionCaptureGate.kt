package com.music.bitchord.auth

/**
 * Prevents a sign-in WebView from completing from a media-user-token that was already in its
 * shared cookie jar when the flow began. The caller creates this after clearing old Apple cookies.
 */
internal class AppleSessionCaptureGate(initialCookieHeader: String?) {
    private val initialToken = extractToken(initialCookieHeader)

    fun newSessionToken(cookieHeader: String?): String? {
        val token = extractToken(cookieHeader) ?: return null
        return token.takeUnless { it == initialToken }
    }

    private fun extractToken(cookieHeader: String?): String? = cookieHeader
        ?.split(';')
        ?.asSequence()
        ?.map(String::trim)
        ?.firstOrNull { it.substringBefore('=').equals("media-user-token", ignoreCase = true) }
        ?.substringAfter('=', "")
        ?.trim()
        ?.takeIf(String::isNotBlank)
}
