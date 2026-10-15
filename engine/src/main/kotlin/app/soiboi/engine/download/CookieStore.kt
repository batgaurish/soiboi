package app.soiboi.engine.download

import app.soiboi.engine.core.EngineLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.time.Instant

/**
 * Apple Music credentials, stored on-device in the Netscape file gamdl reads.
 *
 * Android harvests the cookies from an in-app WebView after signing in on Apple's own page;
 * desktop imports a cookies.txt exported from a browser. Either way the file lives in the app's
 * private directory, never the music library.
 */
private val requiredCookies = setOf("media-user-token")

/** gamdl finds media-user-token by exact domain, so the domain matters, not just the name. */
private val hostScopedCookies = mapOf("media-user-token" to ".music.apple.com")

fun domainForCookie(name: String): String = hostScopedCookies[name] ?: ".apple.com"

private val relevantDomains = setOf(
    "apple.com", ".apple.com", "music.apple.com", ".music.apple.com", "itunes.apple.com", ".itunes.apple.com",
)

data class Cookie(
    val domain: String,
    val name: String,
    val value: String,
    val path: String = "/",
    val secure: Boolean = true,
    val expires: Instant? = null,
    val httpOnly: Boolean = false,
    val includeSubdomains: Boolean = true,
) {
    fun toNetscapeLine(): String {
        val prefix = if (httpOnly) "#HttpOnly_" else ""
        val epoch = expires?.epochSecond ?: 0
        return listOf(
            "$prefix$domain", if (includeSubdomains) "TRUE" else "FALSE", path,
            if (secure) "TRUE" else "FALSE", "$epoch", name, value,
        ).joinToString("\t")
    }

    val isExpired: Boolean get() = expires?.isBefore(Instant.now()) == true

    companion object {
        fun fromNetscapeLine(line: String): Cookie? {
            var raw = line.trimEnd()
            if (raw.isEmpty()) return null
            var httpOnly = false
            if (raw.startsWith("#HttpOnly_")) {
                httpOnly = true
                raw = raw.removePrefix("#HttpOnly_")
            } else if (raw.startsWith("#")) return null
            val parts = raw.split('\t')
            if (parts.size < 7) return null
            val epoch = parts[4].toLongOrNull() ?: 0
            return Cookie(
                domain = parts[0],
                includeSubdomains = parts[1].equals("TRUE", true),
                path = parts[2],
                secure = parts[3].equals("TRUE", true),
                expires = if (epoch > 0) Instant.ofEpochSecond(epoch) else null,
                name = parts[5],
                value = parts.drop(6).joinToString("\t"),
                httpOnly = httpOnly,
            )
        }
    }
}

private fun hasWithRequiredDomain(cookies: Iterable<Cookie>, name: String): Boolean {
    val required = hostScopedCookies[name]
    return cookies.any { it.name == name && (required == null || it.domain == required) }
}

private fun isRelevant(cookie: Cookie): Boolean {
    val domain = cookie.domain.lowercase()
    return relevantDomains.any { domain == it || domain.endsWith(it) }
}

class CookieStore(val file: File) {
    private val _signedIn = MutableStateFlow(false)
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    private val _expiry = MutableStateFlow<Instant?>(null)
    val sessionExpiry: StateFlow<Instant?> = _expiry.asStateFlow()

    val path: String get() = file.path

    /** Writes [cookies] in Netscape format. Returns false if the set lacks what Apple Music needs. */
    fun save(cookies: List<Cookie>): Boolean {
        val relevant = cookies.filter(::isRelevant)
        val missing = requiredCookies.filterNot { hasWithRequiredDomain(relevant, it) }
        if (missing.isNotEmpty()) {
            EngineLog.output("cookies: rejected, missing $missing")
            return false
        }
        return try {
            file.parentFile?.mkdirs()
            file.writeText(
                "# Netscape HTTP Cookie File\n# Written by Soiboi. Do not edit.\n" +
                    relevant.joinToString("\n", postfix = "\n") { it.toNetscapeLine() },
            )
            // Owner-only: this file grants access to the account.
            file.setReadable(false, false); file.setReadable(true, true)
            file.setWritable(false, false); file.setWritable(true, true)
            refresh()
            _signedIn.value
        } catch (e: Exception) {
            EngineLog.output("cookies: write failed: $e")
            false
        }
    }

    /** Imports a browser-exported cookies.txt, dropping unrelated sites' cookies. */
    fun importFile(source: File): Boolean = try {
        save(source.readLines().mapNotNull(Cookie::fromNetscapeLine))
    } catch (e: Exception) {
        EngineLog.output("cookies: import failed: $e")
        false
    }

    fun load(): List<Cookie> {
        if (!file.exists()) return emptyList()
        return try {
            file.readLines().mapNotNull(Cookie::fromNetscapeLine)
        } catch (e: Exception) {
            EngineLog.output("cookies: read failed: $e")
            emptyList()
        }
    }

    /**
     * Re-reads the stored session. A present file is not a valid session: every required cookie
     * must be present, on the right domain and unexpired, so a stale export prompts a fresh
     * sign-in instead of an opaque server error.
     */
    fun refresh() {
        val required = migrateDomains(load()).filter { it.name in requiredCookies }
        val complete = requiredCookies.all { hasWithRequiredDomain(required, it) }
        _signedIn.value = complete && required.none { it.isExpired }
        _expiry.value = required.mapNotNull { it.expires }.minOrNull()
    }

    /** Sessions captured before domains were pinned put media-user-token where gamdl cannot see it. */
    private fun migrateDomains(cookies: List<Cookie>): List<Cookie> {
        var changed = false
        val fixed = cookies.map { cookie ->
            val required = hostScopedCookies[cookie.name]
            if (required == null || cookie.domain == required) cookie
            else { changed = true; cookie.copy(domain = required) }
        }
        if (!changed) return cookies
        EngineLog.output("cookies: migrated host-scoped domains")
        save(fixed)
        return fixed
    }

    fun signOut() {
        try { if (file.exists()) file.delete() } catch (e: Exception) { EngineLog.output("cookies: delete failed: $e") }
        _signedIn.value = false
        _expiry.value = null
    }
}
