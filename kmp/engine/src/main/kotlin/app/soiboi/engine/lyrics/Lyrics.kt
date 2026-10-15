package app.soiboi.engine.lyrics

/** One timed word (or fragment) of a line; [endMs] is filled in from the next timestamp. */
class LyricToken(val startMs: Long, val text: String, var endMs: Long? = null)

class LyricLine(val startMs: Long, val text: String, val tokens: List<LyricToken>) {
    /** A second line sharing this line's timestamp is its translation. */
    val translates = ArrayList<String>()
}

class ParsedLyrics {
    var isKaraoke = false

    /** False when the lyrics carry no timestamps; consumers that scroll to the current line must not follow playback then. */
    var isSynced = true
    val lines = ArrayList<LyricLine>()

    /** Index of the line playing at [positionMs], or -1 before the first line. */
    fun lineAt(positionMs: Long): Int {
        if (!isSynced) return -1
        var lo = 0; var hi = lines.size - 1; var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].startMs <= positionMs) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        return found
    }
}

private val timestampPattern = Regex("[\\[<]\\d{1,3}:\\d{2}([.:]\\d{2,3})?[\\]>]")

/**
 * Whether any line carries an [mm:ss] stamp, i.e. these are synced lyrics. "Has lyrics" is not "has
 * synced lyrics": a downloaded track always arrives with Apple's plain words embedded, and only the
 * timestamped kind can follow playback.
 */
fun hasTimestamps(lines: List<String>): Boolean = lines.any { timestampPattern.containsMatchIn(it) }

fun isBlank(lines: List<String>): Boolean = lines.isEmpty() || lines.all { it.isBlank() }

private fun parseTime(m: MatchResult): Long =
    m.groupValues[1].toLong() * 60_000 + m.groupValues[2].toLong() * 1000 + m.groupValues[3].padEnd(3, '0').toLong()

private val lineTime = Regex("^[\\[<](\\d{2}):(\\d{2})[.:](\\d{2,3})[\\]>]")
private val wordTime = Regex("[\\[<](\\d{2}):(\\d{2})[.:](\\d{2,3})[\\]>]([^\\[<]*)")
private val metaHeader = Regex("^\\[[a-z]+:")

/**
 * Parses raw LRC lines: word and line timestamps, karaoke (several timed tokens per line) and
 * translation lines (a second line sharing the previous line's timestamp). With nothing to show it
 * falls back to the given placeholder messages. Pure, so it is testable without a file or network.
 */
fun parseLrc(rawLines: List<String>, noLyricsMessage: String = "No lyrics", parseFailedMessage: String = "Couldn't read these lyrics", songDurationMs: Long? = null): ParsedLyrics {
    val result = ParsedLyrics()
    val lines = rawLines.filter { it.isNotEmpty() }
    if (lines.isEmpty()) { result.lines += LyricLine(0, noLyricsMessage, emptyList()); return result }

    for (line in lines) {
        val lineMatch = lineTime.find(line) ?: continue
        val lineStart = parseTime(lineMatch)
        val last = result.lines.lastOrNull()
        val isTranslate = last?.startMs == lineStart
        if (last != null && last.tokens.isNotEmpty() && last.tokens.last().endMs == null && !isTranslate) last.tokens.last().endMs = lineStart

        val tokens = ArrayList<LyricToken>(); val text = StringBuilder()
        for (m in wordTime.findAll(line)) {
            val start = parseTime(m); val token = m.groupValues[4]
            if (tokens.isNotEmpty()) tokens.last().endMs = start
            if (token.isNotEmpty()) { tokens += LyricToken(start, token); text.append(token) }
        }
        if (tokens.isEmpty()) continue
        if (tokens.size == 1 && tokens[0].text.isBlank()) continue
        if (tokens.size > 1) result.isKaraoke = true
        if (isTranslate) last!!.translates += text.toString() else result.lines += LyricLine(lineStart, text.toString(), tokens)
    }

    if (result.lines.isEmpty()) {
        // Nothing carried a timestamp. That is ordinary unsynced lyrics, not a parse failure; showing them untimed beats
        // telling someone parsing failed while the words sit right there in the file.
        val plain = lines.map { it.trim() }.filter { it.isNotEmpty() && !metaHeader.containsMatchIn(it) }
        if (plain.isEmpty()) { result.lines += LyricLine(0, parseFailedMessage, emptyList()); return result }
        result.isSynced = false
        plain.forEach { result.lines += LyricLine(0, it, emptyList()) }
    } else {
        result.lines.last().tokens.lastOrNull()?.let { if (it.endMs == null) it.endMs = songDurationMs }
    }
    return result
}
