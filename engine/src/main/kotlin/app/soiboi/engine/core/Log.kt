package app.soiboi.engine.core

import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** The app's own diary: a ring of recent lines in memory, mirrored to a file that survives a crash. */
object EngineLog {
    @Volatile var sink: (String) -> Unit = {}
    private val ring = ArrayDeque<String>()
    private var file: File? = null
    private val stamp = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private const val MAX_LINES = 1500
    private const val MAX_FILE = 512 * 1024L

    fun output(line: String) {
        val text = "${LocalTime.now().format(stamp)} $line"
        synchronized(this) {
            ring.addLast(text); if (ring.size > MAX_LINES) ring.removeFirst()
            runCatching { file?.appendText(text + "\n") }
        }
        sink(line)
    }

    /** Keeps the log in [target], starting a fresh file when the old one grows large. Safe to call twice. */
    @Synchronized fun attach(target: File) {
        if (file == target) return
        runCatching {
            target.parentFile?.mkdirs()
            if (target.length() > MAX_FILE) target.renameTo(File(target.path + ".old"))
            file = target
            target.appendText("\n==== started ${java.time.LocalDateTime.now()} ====\n")
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { target.appendText("${LocalTime.now().format(stamp)} CRASH on ${t.name}\n${e.stackTraceToString()}\n") }
            previous?.uncaughtException(t, e)
        }
    }

    /** Everything worth reading when something went wrong: the file's tail, which includes earlier runs and crashes. */
    @Synchronized fun text(): String = runCatching { file?.readText()?.takeLast(120_000) }.getOrNull() ?: ring.joinToString("\n")

    @Synchronized fun clear() { ring.clear(); runCatching { file?.writeText("") } }
}
