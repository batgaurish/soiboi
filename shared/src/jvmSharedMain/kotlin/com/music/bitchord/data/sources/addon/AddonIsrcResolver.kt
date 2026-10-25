package com.music.bitchord.data.sources.addon

import com.music.bitchord.data.Http
import com.music.bitchord.data.TrackLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

@Serializable
data class AddonIsrcResponse(
    @SerialName("trackId") val trackId: String? = null,
)

open class AddonResolutionException(message: String) : Exception(message)
class AddonResolutionUnavailable(message: String) : AddonResolutionException(message)

/**
 * Resolves recording ISRC against an addon server using the verified `/resolve-isrc` endpoint.
 *
 * Implements SOI-SRC-004 catalog resolution. Responses are references only and do not
 * create streaming playback.
 */
class AddonIsrcResolver(
    rawBaseUrl: String,
    private val client: OkHttpClient = defaultClient,
) {
    val baseUrl: String = normalizeBase(rawBaseUrl)

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    /**
     * Resolves [isrc] to an addon track id, or returns null if not held or endpoint not found.
     *
     * Exact contract verified from original Soiboi:
     * - Endpoint: GET `/resolve-isrc?isrc={isrc}`
     * - Returns 200 with JSON `{"trackId": "..."}`
     * - 404 is treated as a miss (returns null), not an error
     * - Timeouts (20s) and cancellation are handled cleanly
     * - Sensitive URLs/tokens are redacted in all log output
     */
    suspend fun resolveIsrc(
        isrc: String,
        settings: Map<String, String> = emptyMap(),
    ): Result<String?> = withContext(Dispatchers.IO) {
        val trimmed = isrc.trim()
        if (trimmed.isEmpty()) return@withContext Result.success(null)

        val targetUrl = try {
            endpoint("resolve-isrc", settings + ("isrc" to trimmed))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val safeMessage = sanitizeMessage(e.message, trimmed)
            return@withContext Result.failure(AddonResolutionException(safeMessage))
        }

        try {
            val request = Request.Builder()
                .url(targetUrl)
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .build()

            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> {
                        val body = response.body?.string()?.takeIf { it.isNotBlank() }
                            ?: return@withContext Result.failure(AddonResolutionException("Empty response"))
                        val parsed = try {
                            json.decodeFromString<AddonIsrcResponse>(body)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            val safeReason = sanitizeMessage(e.message, trimmed)
                            TrackLog.w(TAG, "  ✗ addon JSON parse failed for ${redact(targetUrl)}: $safeReason")
                            return@withContext Result.failure(AddonResolutionException("The addon sent something that is not JSON"))
                        }
                        Result.success(parsed.trackId?.trim()?.ifEmpty { null })
                    }
                    response.code == 404 -> {
                        // 404 is a miss (not held by addon or no resolver), never a failure
                        Result.success(null)
                    }
                    response.code >= 500 -> {
                        TrackLog.w(TAG, "  ✗ addon unavailable ${redact(targetUrl)}: HTTP ${response.code}")
                        Result.failure(AddonResolutionUnavailable("HTTP ${response.code}"))
                    }
                    else -> {
                        TrackLog.w(TAG, "  ✗ addon error ${redact(targetUrl)}: HTTP ${response.code}")
                        Result.failure(AddonResolutionException("HTTP ${response.code}"))
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val safeDiagnostic = sanitizeMessage(e.message, trimmed)
            TrackLog.w(TAG, "  ✗ addon resolve-isrc call failed ${redact(targetUrl)}: $safeDiagnostic")
            val sanitized = when (e) {
                is AddonResolutionUnavailable -> AddonResolutionUnavailable(safeDiagnostic)
                is AddonResolutionException -> AddonResolutionException(safeDiagnostic)
                is IOException -> AddonResolutionUnavailable(safeDiagnostic)
                else -> AddonResolutionException(safeDiagnostic)
            }
            Result.failure(sanitized)
        }
    }

    private fun sanitizeMessage(message: String?, isrc: String? = null): String {
        val raw = message?.takeIf { it.isNotBlank() } ?: "Request failed"
        var out = redactMessage(raw)
        if (!isrc.isNullOrBlank() && out.contains(isrc)) {
            out = out.replace(isrc, maskIsrc(isrc))
        }
        val basePath = baseUrl.toHttpUrlOrNull()?.encodedPath?.trim('/')?.takeIf { it.isNotBlank() }
        if (basePath != null && out.contains(basePath)) {
            out = out.replace(basePath, "***")
        }
        baseUrl.toHttpUrlOrNull()?.pathSegments?.filter { it.isNotBlank() }?.forEach { segment ->
            if (segment.length >= 4 && out.contains(segment)) {
                out = out.replace(segment, "***")
            }
        }
        return out
    }

    private fun endpoint(pathSegment: String, params: Map<String, String>): String {
        val base = baseUrl.toHttpUrlOrNull()
            ?: throw AddonResolutionException("That is not a usable address")
        val builder = base.newBuilder()
        builder.addPathSegment(pathSegment)
        params.forEach { (key, value) ->
            if (value.isNotEmpty()) builder.addQueryParameter(key, value)
        }
        return builder.build().toString()
    }

    companion object {
        private const val TAG = "BitChord"
        private const val USER_AGENT = "BitChord/1.0"
        private val URL_REGEX = Regex("""https?://[^\s)"',;>]+""")
        private val SENSITIVE_QUERY_REGEX = Regex("""(?i)\b(isrc|token|auth|api_?key|secret|password|access_token|refresh_token|session)=[^&\s)"',;>]+""")
        private val ISRC_REGEX = Regex("""\b[A-Z]{2}-?[A-Z0-9]{3}-?[0-9]{2}-?[0-9]{5}\b""", RegexOption.IGNORE_CASE)

        val defaultClient: OkHttpClient by lazy {
            Http.client.newBuilder()
                .callTimeout(20, TimeUnit.SECONDS)
                .build()
        }

        fun maskIsrc(isrc: String?): String {
            if (isrc.isNullOrBlank()) return "***"
            val clean = isrc.trim()
            return if (clean.length < 6) "***" else "${clean.take(2)}***${clean.takeLast(2)}"
        }

        fun normalizeBase(raw: String): String {
            val trimmed = raw.trim().trimEnd('/')
            val lastSegment = trimmed.substringAfterLast('/', "")
            return if (lastSegment.endsWith(".json", ignoreCase = true) && trimmed.contains("://")) {
                trimmed.dropLast(lastSegment.length).trimEnd('/')
            } else {
                trimmed
            }
        }

        fun redact(url: String): String =
            url.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}/***" } ?: "***"

        fun redactMessage(message: String?): String {
            if (message.isNullOrBlank()) return "no reason given"
            var out = URL_REGEX.replace(message) { match -> redact(match.value) }
            out = SENSITIVE_QUERY_REGEX.replace(out, "$1=***")
            out = ISRC_REGEX.replace(out) { match -> maskIsrc(match.value) }
            return out
        }
    }
}
