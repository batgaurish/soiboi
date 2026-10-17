package app.soiboi.engine.accounts

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

/**
 * Central registry for all music service sessions (Apple, Spotify, YT Music, SoundCloud).
 * The Engine holds one instance; UI reads session states from here.
 */
class SessionRegistry {
    private val sessions = mutableMapOf<String, MusicSession>()
    private val _sessions = MutableStateFlow<List<MusicSession>>(emptyList())
    val sessionsFlow = _sessions.asStateFlow()

    fun register(session: MusicSession) {
        sessions[session.id] = session
        _sessions.value = sessions.values.toList()
    }

    fun get(id: String): MusicSession? = sessions[id]

    fun all(): List<MusicSession> = sessions.values.toList()

    /** Combined state flow for all registered sessions. */
    fun allStates(): Flow<Map<String, SessionState>> {
        if (sessions.isEmpty()) return flowOf(emptyMap())
        return combine(sessions.values.map { it.state }) { states ->
            sessions.keys.zip(states.toList()).toMap()
        }
    }

    /** Sessions that are currently signed in. */
    fun signedIn(): List<MusicSession> = sessions.values.filter {
        it.state.value is SessionState.SignedIn
    }

    /** Sessions that support streaming (for playback resolution). */
    fun streamable(): List<MusicSession> = signedIn().filter { it.canStream }
}
