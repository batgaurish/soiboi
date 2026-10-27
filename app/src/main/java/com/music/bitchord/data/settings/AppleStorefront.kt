package com.music.bitchord.data.settings

import java.util.Locale

/** Apple catalog storefronts are ISO 3166-1 alpha-2 country codes. */
internal fun normalizeAppleStorefront(value: String?): String? = value
    ?.trim()
    ?.takeIf { it.matches(Regex("[A-Za-z]{2}")) }
    ?.takeIf { it.uppercase(Locale.ROOT) in Locale.getISOCountries() }
    ?.lowercase(Locale.ROOT)