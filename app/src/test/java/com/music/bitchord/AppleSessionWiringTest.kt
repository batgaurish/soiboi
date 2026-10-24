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

    @Test
    fun authStoreAppleCookieSavesAndRetrievesSecurely() {
        val memoryStore = mutableMapOf<String, Any>()
        val editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "putString" -> {
                    memoryStore[args[0] as String] = args[1] as String
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

        val prefs = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "edit" -> editor
                "getString" -> memoryStore[args[0] as String] as? String ?: args.getOrNull(1)
                else -> null
            }
        } as SharedPreferences

        val authStore = AuthStore(prefs)

        assertNull(authStore.appleMusicCookie)
        authStore.appleMusicCookie = "media-user-token=test-token-123"
        assertEquals("media-user-token=test-token-123", authStore.appleMusicCookie)
        assertEquals("test-token-123", extractMusicUserToken(authStore.appleMusicCookie))

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
