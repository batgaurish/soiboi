package app.soiboi.engine.library

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** One row, read by column index. */
class Row(private val s: SQLiteStatement) {
    fun text(i: Int): String? = if (s.isNull(i)) null else s.getText(i)
    fun long(i: Int): Long? = if (s.isNull(i)) null else s.getLong(i)
    fun int(i: Int): Int? = if (s.isNull(i)) null else s.getLong(i).toInt()
    fun double(i: Int): Double? = if (s.isNull(i)) null else s.getDouble(i)
}

/**
 * The library database: songs, playlists, folders and small key-value blobs, in one SQLite file.
 * The bundled driver runs the same SQLite on Linux and Android. One connection, guarded by a mutex.
 */
class Database(file: File) {
    private val connection: SQLiteConnection
    private val lock = Mutex()

    init {
        file.parentFile?.mkdirs()
        connection = BundledSQLiteDriver().open(file.path)
        connection.execSQL("PRAGMA journal_mode=WAL")
        connection.execSQL("PRAGMA foreign_keys=ON")
        migrate()
    }

    private fun migrate() {
        connection.execSQL("""CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT)""")
        connection.execSQL(
            """CREATE TABLE IF NOT EXISTS songs (
                id TEXT PRIMARY KEY, origin TEXT NOT NULL, path TEXT, modified INTEGER, format TEXT,
                title TEXT, artist TEXT, album TEXT, album_artist TEXT, genre TEXT,
                year INTEGER, track INTEGER, disc INTEGER, bitrate INTEGER, samplerate INTEGER, duration INTEGER,
                lyrics TEXT, play_count INTEGER NOT NULL DEFAULT 0, last_played INTEGER,
                artist_id TEXT, album_id TEXT, cover_id TEXT,
                energy REAL, danceable REAL, brightness REAL, aggressive REAL, relaxed REAL, bpm REAL, musical_key TEXT)""",
        )
        connection.execSQL("""CREATE INDEX IF NOT EXISTS songs_origin ON songs(origin)""")
        connection.execSQL(
            """CREATE TABLE IF NOT EXISTS playlists (
                name TEXT PRIMARY KEY, position INTEGER NOT NULL, pinned INTEGER NOT NULL DEFAULT 0, remote_id TEXT, cover TEXT)""",
        )
        connection.execSQL(
            """CREATE TABLE IF NOT EXISTS playlist_songs (
                playlist TEXT NOT NULL REFERENCES playlists(name) ON DELETE CASCADE ON UPDATE CASCADE,
                position INTEGER NOT NULL, song_id TEXT NOT NULL, PRIMARY KEY (playlist, position))""",
        )
        connection.execSQL("""CREATE TABLE IF NOT EXISTS folders (path TEXT PRIMARY KEY, position INTEGER NOT NULL)""")
        connection.execSQL("""CREATE TABLE IF NOT EXISTS kv (key TEXT PRIMARY KEY, value TEXT NOT NULL)""")
        connection.execSQL("""INSERT OR IGNORE INTO playlists(name, position) VALUES ('Favorite', 0)""")
    }

    suspend fun <T> run(block: Tx.() -> T): T = withContext(Dispatchers.IO) { lock.withLock { Tx(connection).block() } }

    /** Several statements as one transaction: all or nothing. */
    suspend fun <T> transaction(block: Tx.() -> T): T = run {
        connection.execSQL("BEGIN")
        try { block().also { connection.execSQL("COMMIT") } } catch (e: Throwable) { connection.execSQL("ROLLBACK"); throw e }
    }

    suspend fun getKv(key: String): String? = run { queryOne("SELECT value FROM kv WHERE key=?", key) { it.text(0) } }
    suspend fun putKv(key: String, value: String) = run { exec("INSERT OR REPLACE INTO kv(key,value) VALUES(?,?)", key, value) }

    fun close() = connection.close()

    class Tx(private val c: SQLiteConnection) {
        private fun bind(s: SQLiteStatement, args: Array<out Any?>) {
            args.forEachIndexed { i, a ->
                val n = i + 1
                when (a) {
                    null -> s.bindNull(n)
                    is String -> s.bindText(n, a)
                    is Int -> s.bindLong(n, a.toLong())
                    is Long -> s.bindLong(n, a)
                    is Boolean -> s.bindLong(n, if (a) 1 else 0)
                    is Double -> s.bindDouble(n, a)
                    is java.time.Instant -> s.bindLong(n, a.toEpochMilli())
                    else -> s.bindText(n, a.toString())
                }
            }
        }

        fun exec(sql: String, vararg args: Any?) {
            c.prepare(sql).use { bind(it, args); it.step() }
        }

        fun <T> query(sql: String, vararg args: Any?, map: (Row) -> T): List<T> =
            c.prepare(sql).use { s ->
                bind(s, args)
                val out = ArrayList<T>()
                val row = Row(s)
                while (s.step()) out += map(row)
                out
            }

        fun <T> queryOne(sql: String, vararg args: Any?, map: (Row) -> T): T? = query(sql, *args, map = map).firstOrNull()

        /** Runs [sql] once per element of [rows], reusing one prepared statement. */
        fun <R> batch(sql: String, rows: Iterable<R>, args: (R) -> Array<out Any?>) {
            c.prepare(sql).use { s ->
                for (r in rows) { s.reset(); bind(s, args(r)); s.step() }
            }
        }
    }
}
