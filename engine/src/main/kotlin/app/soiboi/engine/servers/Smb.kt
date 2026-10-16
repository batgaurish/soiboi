package app.soiboi.engine.servers

import app.soiboi.engine.core.EngineLog
import app.soiboi.engine.library.Song
import app.soiboi.engine.library.SongOrigin
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.protocol.commons.EnumWithValue
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import java.util.EnumSet
import java.util.concurrent.TimeUnit

private val audioExt = setOf("mp3", "flac", "m4a", "m4b", "mp4", "aac", "wav", "aiff", "aif", "ogg", "opus", "ape", "mpc", "wv", "mp2")

/** `smb://host/share/sub/folder` split into its parts; null when it names no share. */
internal class SmbTarget(val host: String, val share: String, val folder: String)

internal fun parseSmbUrl(url: String): SmbTarget? {
    val rest = url.trim().removePrefix("smb://").removePrefix("//").trim('/')
    val parts = rest.split('/').filter { it.isNotEmpty() }
    if (parts.size < 2) return null
    return SmbTarget(parts[0], parts[1], parts.drop(2).joinToString("/"))
}

/** "03 - Title" or "03 Title" split into the track number and the name. */
internal fun songFromLayout(id: String, origin: SongOrigin, rel: List<String>, modified: java.time.Instant?): Song {
    val name = rel.last(); val file = name.substringBeforeLast('.')
    val album = rel.getOrNull(rel.size - 2)?.takeIf { rel.size >= 2 }
    val artist = rel.getOrNull(rel.size - 3)?.takeIf { rel.size >= 3 }
    val numbered = Regex("^(\\d{1,3})[\\s._-]+(.+)$").find(file)
    return Song(id = id, origin = origin, format = name.substringAfterLast('.').lowercase(), title = numbered?.groupValues?.get(2) ?: file, artist = artist, album = album,
        albumArtist = artist, track = numbered?.groupValues?.get(1)?.toIntOrNull(), modified = modified)
}

/**
 * An SMB 2/3 share as a music library, read straight off the network with no mount. Listing is a directory walk; the
 * artist, album and title come from the `Artist/Album/NN Title.ext` layout, as for WebDAV. Playback goes through a local
 * loopback address that serves the file with Range support, so tracks start at once and seek.
 */
class SmbServer(url: String, private val username: String, private val password: String) : MusicServer {
    override val origin = SongOrigin.Smb
    override val label = "SMB share"

    private val target = parseSmbUrl(url)
    private val client = SMBClient(SmbConfig.builder().withTimeout(20, TimeUnit.SECONDS).withSoTimeout(30, TimeUnit.SECONDS).build())
    private var connection: Connection? = null
    private var session: Session? = null
    private var share: DiskShare? = null

    private val proxy = LocalRangeServer { id -> openRange(id) }

    @Synchronized private fun disk(): DiskShare {
        val t = target ?: throw IllegalStateException("The address must look like smb://host/share")
        share?.takeIf { it.isConnected }?.let { return it }
        runCatching { connection?.close() }
        val c = client.connect(t.host); connection = c
        val s = c.authenticate(if (username.isBlank()) AuthenticationContext.guest() else AuthenticationContext(username.substringAfter('\\'), password.toCharArray(), username.substringBefore('\\', "")))
        session = s
        return (s.connectShare(t.share) as DiskShare).also { share = it }
    }

    private fun idFor(path: String) = "smb://${target!!.host}/${target.share}/$path"
    private fun pathOf(id: String) = id.removePrefix("smb://${target!!.host}/${target.share}/")

    override suspend fun ping(): Boolean = try { disk().list(target!!.folder).let { true } } catch (e: Exception) { EngineLog.output("[SMB] $e"); false }

    override suspend fun allSongs(onProgress: (Int) -> Unit): List<Song> {
        val out = ArrayList<Song>()
        val share = disk(); val root = target!!.folder
        fun walk(dir: String) {
            for (e in share.list(dir)) {
                val name = e.fileName
                if (name == "." || name == "..") continue
                val path = if (dir.isEmpty()) name else "$dir/$name"
                val isDir = EnumWithValue.EnumUtils.isSet(e.fileAttributes, com.hierynomus.msfscc.FileAttributes.FILE_ATTRIBUTE_DIRECTORY)
                if (isDir) walk(path)
                else if (name.substringAfterLast('.', "").lowercase() in audioExt) {
                    val rel = path.removePrefix(root).trim('/').split('/')
                    out += songFromLayout(idFor(path), SongOrigin.Smb, rel, e.lastWriteTime?.toInstant())
                    if (out.size % 25 == 0) onProgress(out.size)
                }
            }
        }
        try { walk(root) } catch (e: Exception) { EngineLog.output("[SMB] walk: $e") }
        onProgress(out.size)
        return out
    }

    private fun openRange(id: String): RangeFile? = try {
        val file = disk().openFile(pathOf(id), EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null)
        object : RangeFile {
            override val size = file.fileInformation.standardInformation.endOfFile
            override fun read(offset: Long, buf: ByteArray, len: Int) = file.read(buf, offset, 0, len)
            override fun close() { runCatching { file.close() } }
        }
    } catch (e: Exception) { EngineLog.output("[SMB] open $id: $e"); null }

    override fun streamUrl(song: Song) = proxy.url(song.id)
}
