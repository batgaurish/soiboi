package app.soiboi.engine.core

/** Where the engine reports things worth keeping in the app log. The shell swaps in a real sink. */
object EngineLog {
    @Volatile var sink: (String) -> Unit = {}
    fun output(line: String) = sink(line)
}
