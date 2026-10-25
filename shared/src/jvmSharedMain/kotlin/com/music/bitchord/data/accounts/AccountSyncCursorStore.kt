package com.music.bitchord.data.accounts

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Storage for account playlist synchronization cursors.
 * Keeps track of seen track IDs per playlist so only newly added tracks are detected.
 * Strictly persists only metadata (IDs and timestamps); credentials are never stored here.
 */
interface AccountSyncCursorStore {
    fun load(sessionId: String, playlistId: String): SyncCursor
    fun save(sessionId: String, playlistId: String, cursor: SyncCursor)
    fun clear(sessionId: String? = null)
    fun lastSyncTime(sessionId: String, playlistId: String): Long? =
        load(sessionId, playlistId).takeIf { it.lastSyncMs > 0L }?.lastSyncMs
}

class FileAccountSyncCursorStore(private val cursorDir: File) : AccountSyncCursorStore {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    init {
        cursorDir.mkdirs()
    }

    private fun cursorFile(sessionId: String, playlistId: String): File {
        val safeSession = sessionId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val safePlaylist = playlistId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return File(cursorDir, "${safeSession}_${safePlaylist}.json")
    }

    override fun load(sessionId: String, playlistId: String): SyncCursor {
        val file = cursorFile(sessionId, playlistId)
        if (!file.exists()) return SyncCursor(playlistId, 0L, emptySet())
        return runCatching {
            json.decodeFromString<SyncCursor>(file.readText())
        }.getOrElse { SyncCursor(playlistId, 0L, emptySet()) }
    }

    override fun save(sessionId: String, playlistId: String, cursor: SyncCursor) {
        val file = cursorFile(sessionId, playlistId)
        runCatching {
            val content = json.encodeToString(cursor)
            val temp = File(cursorDir, ".${file.name}.tmp")
            temp.writeText(content)
            if (!temp.renameTo(file)) {
                file.writeText(content)
                temp.delete()
            }
        }
    }

    override fun clear(sessionId: String?) {
        val files = cursorDir.listFiles() ?: return
        val prefix = sessionId?.let { "${it.replace(Regex("[^a-zA-Z0-9_-]"), "_")}_" }
        files.forEach { file ->
            if (prefix == null || file.name.startsWith(prefix)) {
                file.delete()
            }
        }
    }
}

class InMemoryAccountSyncCursorStore : AccountSyncCursorStore {
    private val cursors = ConcurrentHashMap<String, SyncCursor>()

    private fun key(sessionId: String, playlistId: String) = "$sessionId:$playlistId"

    override fun load(sessionId: String, playlistId: String): SyncCursor =
        cursors[key(sessionId, playlistId)] ?: SyncCursor(playlistId, 0L, emptySet())

    override fun save(sessionId: String, playlistId: String, cursor: SyncCursor) {
        cursors[key(sessionId, playlistId)] = cursor
    }

    override fun clear(sessionId: String?) {
        if (sessionId == null) {
            cursors.clear()
        } else {
            cursors.keys.removeIf { it.startsWith("$sessionId:") }
        }
    }
}
