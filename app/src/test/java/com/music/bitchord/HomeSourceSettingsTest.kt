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
            "putBoolean" -> {
                store[args[0] as String] = args[1] as Boolean
                proxy
            }
            "putInt" -> {
                store[args[0] as String] = args[1] as Int
                proxy
            }
            "putLong" -> {
                store[args[0] as String] = args[1] as Long
                proxy
            }
            "putFloat" -> {
                store[args[0] as String] = args[1] as Float
                proxy
            }
            "remove" -> {
                store.remove(args[0] as String)
                proxy
            }
            "clear" -> {
                store.clear()
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
            "getString" -> store[args[0] as String] as? String ?: args.getOrNull(1)
            "getBoolean" -> store[args[0] as String] as? Boolean ?: (args.getOrNull(1) as? Boolean ?: false)
            "getInt" -> store[args[0] as String] as? Int ?: (args.getOrNull(1) as? Int ?: 0)
            "getLong" -> store[args[0] as String] as? Long ?: (args.getOrNull(1) as? Long ?: 0L)
            "getFloat" -> store[args[0] as String] as? Float ?: (args.getOrNull(1) as? Float ?: 0f)
            "contains" -> (args[0] as String) in store
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
        AppSettings.reload()
        assertEquals(HomeSource.YOUTUBE, AppSettings.homeSource.value)
    }

    @Test
    fun selectingApplePersistsAndRestoresRoundTripOnReload() {
        AppSettings.setHomeSource(HomeSource.APPLE)
        assertEquals(HomeSource.APPLE, AppSettings.homeSource.value)
        assertEquals("APPLE", store["home_source"])

        // Reset in-memory state to verify actual reload/read from backing storage
        AppSettings.homeSource.value = HomeSource.YOUTUBE

        // Exercise real read/load path
        AppSettings.reload()
        assertEquals(HomeSource.APPLE, AppSettings.homeSource.value)
    }

    @Test
    fun corruptOrInvalidValueFallsBackToYouTubeDefaultOnReload() {
        store["home_source"] = "INVALID_CHOICE"
        AppSettings.homeSource.value = HomeSource.APPLE
        AppSettings.reload()
        assertEquals(HomeSource.YOUTUBE, AppSettings.homeSource.value)
    }
}
