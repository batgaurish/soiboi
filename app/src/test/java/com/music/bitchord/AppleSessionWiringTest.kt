package com.music.bitchord

import com.music.bitchord.auth.AuthStore
import com.music.bitchord.data.apple.AppleHomeCatalog
import com.music.bitchord.data.apple.extractMusicUserToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.lang.reflect.Proxy
import android.content.SharedPreferences

class AppleSessionWiringTest {

    private fun memoryPreferences(memoryStore: MutableMap<String, Any>): SharedPreferences {
        val editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "putString" -> {
                    if (args[1] == null) memoryStore.remove(args[0] as String)
                    else memoryStore[args[0] as String] = args[1] as String
                    proxy
                }
                "remove" -> {
                    memoryStore.remove(args[0] as String)
                    proxy
                }
                "apply", "commit" -> proxy
                else -> if (method.returnType == SharedPreferences.Editor::class.java) proxy else null
            }
        } as SharedPreferences.Editor
        return Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "edit" -> editor
                "getString" -> memoryStore[args[0] as String] as? String ?: args.getOrNull(1)
                else -> null
            }
        } as SharedPreferences
    }

    @Test
    fun appleCookieIsNotStoredWhenSecureStoreIsUnavailable() {
        val generalValues = mutableMapOf<String, Any>()
        val prefs = memoryPreferences(generalValues)

        val authStore = AuthStore(prefs)
        authStore.appleMusicCookie = "media-user-token=must-not-fallback-to-plain-prefs"

        assertNull(generalValues["apple_music_cookie"])
        assertNull(authStore.appleMusicCookie)
    }

    @Test
    fun authStoreAppleCookieSavesAndRetrievesSecurely() {
        val legacyValues = mutableMapOf<String, Any>()
        val secureValues = mutableMapOf<String, Any>()
        val authStore = AuthStore(
            memoryPreferences(legacyValues),
            memoryPreferences(secureValues),
        )

        assertNull(authStore.appleMusicCookie)
        authStore.appleMusicCookie = "media-user-token=test-token-123"
        assertEquals("media-user-token=test-token-123", authStore.appleMusicCookie)
        assertEquals("test-token-123", extractMusicUserToken(authStore.appleMusicCookie))
        assertNull(legacyValues["apple_music_cookie"])
        assertEquals("media-user-token=test-token-123", secureValues["apple_music_cookie"])

        authStore.appleMusicCookie = null
        assertNull(authStore.appleMusicCookie)
    }

    @Test
    fun sessionUpdateInvalidatesAndReloadsCatalog() {
        var currentCookie: String? = "media-user-token=initial-token"
        var cookieReadCount = 0
        val catalog = AppleHomeCatalog(
            cookieHeader = {
                cookieReadCount++
                currentCookie
            },
            storefront = { "us" },
        )

        assertEquals("initial-token", extractMusicUserToken(currentCookie))
        catalog.invalidate()

        // Update session
        currentCookie = "media-user-token=updated-token"
        catalog.invalidate()
        assertEquals("updated-token", extractMusicUserToken(currentCookie))
    }
}
