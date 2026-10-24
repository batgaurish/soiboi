package com.music.bitchord

import android.content.SharedPreferences
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.HomeSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

class HomeSourceSettingsTest {
    private val store = mutableMapOf<String, Any>()

    private val editor: SharedPreferences.Editor = Proxy.newProxyInstance(
        SharedPreferences.Editor::class.java.classLoader,
        arrayOf(SharedPreferences.Editor::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "putString" -> {
                store[args[0] as String] = args[1] as String
                proxy
            }
            "apply", "commit" -> proxy
            else -> if (method.returnType == SharedPreferences.Editor::class.java) proxy else null
        }
    } as SharedPreferences.Editor

    private val prefs: SharedPreferences = Proxy.newProxyInstance(
        SharedPreferences::class.java.classLoader,
        arrayOf(SharedPreferences::class.java),
    ) { _, method, args ->
        when (method.name) {
            "edit" -> editor
            "getString" -> store[args[0] as String] as? String ?: args[1]
            "all" -> store
            else -> null
        }
    } as SharedPreferences

    @Before
    fun setUp() {
        store.clear()
        val field = AppSettings::class.java.getDeclaredField("prefs")
        field.isAccessible = true
        field.set(AppSettings, prefs)
        AppSettings.homeSource.value = HomeSource.YOUTUBE
    }

    @After
    fun tearDown() {
        AppSettings.homeSource.value = HomeSource.YOUTUBE
        store.clear()
    }

    @Test
    fun defaultHomeSourceIsYouTube() {
        assertEquals(HomeSource.YOUTUBE, AppSettings.homeSource.value)
    }

    @Test
    fun selectingApplePersistsAndRestoresRoundTrip() {
        AppSettings.setHomeSource(HomeSource.APPLE)
        assertEquals(HomeSource.APPLE, AppSettings.homeSource.value)
        assertEquals("APPLE", store["home_source"])

        // Simulate reload
        val readMethod = AppSettings::class.java.getDeclaredMethod("readHomeSource")
        readMethod.isAccessible = true
        val restored = readMethod.invoke(AppSettings) as HomeSource
        assertEquals(HomeSource.APPLE, restored)
    }
}
