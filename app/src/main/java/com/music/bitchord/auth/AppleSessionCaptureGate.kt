package com.music.bitchord.auth

import java.net.URI

/**
 * Build provider-scoped cookie expiration strings. Android's CookieManager only exposes
 * name=value pairs, so expire host-only cookies and every valid Apple parent-domain scope.
 */
internal fun appleCookieExpirationHeaders(cookieHeader: String?, url: String): List<String> {
    val uri = runCatching { URI(url) }.getOrNull() ?: return emptyList()
    val host = uri.host?.lowercase()
        ?.takeIf { it == "apple.com" || it.endsWith(".apple.com") }
        ?: return emptyList()
    val names = cookieHeader.orEmpty().split(';')
        .map { it.substringBefore('=').trim() }
        .filter(String::isNotEmpty)
        .distinct()
    val labels = host.split('.')
    val domains = labels.indices
        .map { labels.drop(it).joinToString(".") }
        .filter { it.count { ch -> ch == '.' } >= 1 }
        .map { ".${it}" }
    val paths = buildList {
        var path = uri.rawPath?.takeIf { it.startsWith("/") }.orEmpty().ifBlank { "/" }
        while (path != "/") {
            add(path)
            val parentSlash = path.lastIndexOf('/')
            path = if (parentSlash <= 0) "/" else path.substring(0, parentSlash)
        }
        add("/")
    }.distinct()
    return names.flatMap { name ->
        paths.flatMap { path ->
            val base = "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=$path"
            listOf(base) + domains.map { domain -> "$base; Domain=$domain" }
        }
    }.distinct()
}

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

/**
 * Deduplicate cookies from the provider's host/path snapshots without dropping the token
 * already observed at the browse URL. The same cookie name can be returned with different
 * values for different paths; first-wins deduplication can otherwise discard the new token.
 */
internal fun selectAppleCaptureCookies(
    cookiePairs: List<Triple<String, String, String>>,
    sessionToken: String,
): List<Triple<String, String, String>> {
    val sessionCookie = cookiePairs.firstOrNull {
        it.first.equals("media-user-token", ignoreCase = true) && it.second == sessionToken
    } ?: return emptyList()
    val otherCookies = cookiePairs.asSequence()
        .filterNot { it.first.equals("media-user-token", ignoreCase = true) }
        .distinctBy { it.first }
        .toList()
    return listOf(sessionCookie) + otherCookies
}
