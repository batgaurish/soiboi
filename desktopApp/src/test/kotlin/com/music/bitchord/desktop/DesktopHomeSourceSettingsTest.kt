package com.music.bitchord.desktop

import com.music.bitchord.data.settings.HomeSource
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopHomeSourceSettingsTest {
    private val isolatedNode: Preferences =
        Preferences.userRoot().node("com.music.bitchord.desktop.test.homesource.${UUID.randomUUID()}")
    private val persistence = DesktopPersistence(isolatedNode)

    @AfterTest
    fun tearDown() {
        runCatching { isolatedNode.removeNode() }
    }

    @Test
    fun homeSourceDefaultsToYouTube() {
        assertEquals(HomeSource.YOUTUBE, persistence.homeSource())
    }

    @Test
    fun selectingAppleSurvivesRoundTripAndRestoresChoice() {
        persistence.saveHomeSource(HomeSource.APPLE)
        val freshInstance = DesktopPersistence(isolatedNode)
        assertEquals(HomeSource.APPLE, freshInstance.homeSource())
    }

    @Test
    fun invalidStoredValueFallsBackToYouTubeDefault() {
        isolatedNode.put("home_source", "UNKNOWN_SOURCE")
        val freshInstance = DesktopPersistence(isolatedNode)
        assertEquals(HomeSource.YOUTUBE, freshInstance.homeSource())
    }
}
