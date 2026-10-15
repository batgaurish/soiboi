package app.soiboi.engine.library

import java.time.Instant

private const val COLS = "id,origin,path,modified,format,title,artist,album,album_artist,genre,year,track,disc,bitrate,samplerate,duration," +
    "lyrics,play_count,last_played,artist_id,album_id,cover_id,energy,danceable,brightness,aggressive,relaxed,bpm,musical_key"

private fun Row.song() = Song(
    id = text(0)!!, origin = SongOrigin.valueOf(text(1)!!), path = text(2), modified = long(3)?.let(Instant::ofEpochMilli),
    format = text(4), title = text(5), artist = text(6), album = text(7), albumArtist = text(8), genre = text(9),
    year = int(10), track = int(11), disc = int(12), bitrate = int(13), samplerate = int(14), durationMs = long(15),
    lyrics = text(16), playCount = int(17) ?: 0, lastPlayed = long(18)?.let(Instant::ofEpochMilli),
    artistId = text(19), albumId = text(20), coverId = text(21),
    energy = double(22), danceable = double(23), brightness = double(24), aggressive = double(25),
    relaxed = double(26), bpm = double(27), musicalKey = text(28),
)

private fun Song.args(): Array<Any?> = arrayOf(
    id, origin.name, path, modified, format, title, artist, album, albumArtist, genre, year, track, disc, bitrate, samplerate,
    durationMs, lyrics, playCount, lastPlayed, artistId, albumId, coverId, energy, danceable, brightness, aggressive, relaxed, bpm, musicalKey,
)

/** Reads and writes songs. Replaced wholesale on a library sync, patched for play counts and tag edits. */
class SongStore(private val db: Database) {
    suspend fun all(origin: SongOrigin? = null): List<Song> = db.run {
        if (origin == null) query("SELECT $COLS FROM songs") { it.song() }
        else query("SELECT $COLS FROM songs WHERE origin=?", origin.name) { it.song() }
    }

    suspend fun get(id: String): Song? = db.run { queryOne("SELECT $COLS FROM songs WHERE id=?", id) { it.song() } }

    /** Replaces every song of [origin] with [songs] in one transaction. */
    suspend fun replaceAll(origin: SongOrigin, songs: List<Song>) = db.transaction {
        exec("DELETE FROM songs WHERE origin=?", origin.name)
        batch("INSERT OR REPLACE INTO songs($COLS) VALUES(${COLS.split(',').joinToString(",") { "?" }})", songs) { it.args() }
    }

    suspend fun upsert(songs: List<Song>) = db.transaction {
        batch("INSERT OR REPLACE INTO songs($COLS) VALUES(${COLS.split(',').joinToString(",") { "?" }})", songs) { it.args() }
    }

    suspend fun deleteMissing(origin: SongOrigin, keep: Set<String>) = db.transaction {
        val existing = query("SELECT id FROM songs WHERE origin=?", origin.name) { it.text(0)!! }
        batch("DELETE FROM songs WHERE id=?", existing.filter { it !in keep }) { arrayOf(it) }
    }

    suspend fun recordPlay(id: String, at: Instant) = db.run {
        exec("UPDATE songs SET play_count=play_count+1, last_played=? WHERE id=?", at, id)
    }

    suspend fun setPlayCount(id: String, count: Int, last: Instant?) = db.run {
        exec("UPDATE songs SET play_count=?, last_played=? WHERE id=?", count, last, id)
    }

    suspend fun updateDuration(id: String, ms: Long) = db.run { exec("UPDATE songs SET duration=? WHERE id=?", ms, id) }

    suspend fun updateTags(song: Song) = db.run {
        exec(
            "UPDATE songs SET title=?, artist=?, album=?, genre=?, lyrics=?, year=?, track=?, disc=? WHERE id=?",
            song.title, song.artist, song.album, song.genre, song.lyrics, song.year, song.track, song.disc, song.id,
        )
    }

    suspend fun remove(id: String) = db.run { exec("DELETE FROM songs WHERE id=?", id) }
}
