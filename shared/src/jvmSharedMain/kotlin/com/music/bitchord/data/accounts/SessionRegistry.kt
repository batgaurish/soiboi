package com.music.bitchord.data.accounts

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

/**
 * Registry holding all available music account service sessions.
 * Manages active sessions and surfaces their connection and sync states.
 */
class SessionRegistry {
    private val sessions = LinkedHashMap<String, MusicSession>()
    private val _sessions = MutableStateFlow<List<MusicSession>>(emptyList())
    val sessionsFlow: StateFlow<List<MusicSession>> = _sessions.asStateFlow()

    @Synchronized
    fun register(session: MusicSession) {
        sessions[session.id] = session
        _sessions.value = sessions.values.toList()
    }

    @Synchronized
    fun unregister(id: String) {
        sessions.remove(id)
        _sessions.value = sessions.values.toList()
    }

    @Synchronized
    fun get(id: String): MusicSession? = sessions[id]

    @Synchronized
    fun all(): List<MusicSession> = sessions.values.toList()

    @Synchronized
    fun signedIn(): List<MusicSession> = sessions.values.filter {
        it.state.value is SessionState.SignedIn
    }

    fun allStates(): Flow<Map<String, SessionState>> {
        val current = all()
        if (current.isEmpty()) return flowOf(emptyMap())
        return combine(current.map { it.state }) { states ->
            current.map { it.id }.zip(states).toMap()
        }
    }
}
