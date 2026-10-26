package com.music.bitchord.data.playlistimport

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

object ApplePlaylistMirrorStore {
    private const val PREFS = "bitchord_apple_playlist_mirrors"
    private const val KEY_MIRRORS = "mirrors_json"
    private val json = Json { ignoreUnknownKeys = true }

    fun all(context: Context): List<ApplePlaylistMirror> = synchronized(this) {
        val raw = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MIRRORS, null)
            ?: return@synchronized emptyList()
        runCatching { json.decodeFromString<List<ApplePlaylistMirror>>(raw) }.getOrDefault(emptyList())
    }

    fun put(context: Context, mirror: ApplePlaylistMirror) = synchronized(this) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val updated = all(context).filterNot { it.targetId == mirror.targetId } + mirror
        prefs.edit().putString(KEY_MIRRORS, json.encodeToString(updated)).apply()
    }

    fun remove(context: Context, targetId: String) = synchronized(this) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val updated = all(context).filterNot { it.targetId == targetId }
        prefs.edit().putString(KEY_MIRRORS, json.encodeToString(updated)).apply()
    }
}
