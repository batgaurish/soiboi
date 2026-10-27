package com.music.bitchord

import com.music.bitchord.data.settings.normalizeAppleStorefront
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppleStorefrontTest {
    @Test fun storefrontCodeIsNormalizedAndRejectsNonCountryValues() {
        assertEquals("in", normalizeAppleStorefront(" IN "))
        assertEquals("gb", normalizeAppleStorefront("gb"))
        assertNull(normalizeAppleStorefront("usa"))
        assertNull(normalizeAppleStorefront("1n"))
        assertNull(normalizeAppleStorefront(""))
        assertNull(normalizeAppleStorefront(null))
    }
}