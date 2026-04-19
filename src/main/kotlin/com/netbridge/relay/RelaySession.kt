package com.netbridge.relay

import io.ktor.websocket.WebSocketSession
import java.util.concurrent.ConcurrentHashMap

/**
 * Represents an active relay session between a HOST and its CLIENTs.
 */
data class RelaySession(
    val sessionId: String,
    @Volatile var hostSocket: WebSocketSession? = null,
    val clients: ConcurrentHashMap<String, WebSocketSession> = ConcurrentHashMap(),
    val createdAt: Long = System.currentTimeMillis()
) {
    val isExpired: Boolean
        get() {
            val age = System.currentTimeMillis() - createdAt
            val thirtyMinutes = 30 * 60 * 1000L
            return hostSocket == null && clients.isEmpty() && age > thirtyMinutes
        }

    val clientCount: Int
        get() = clients.size

    val hasHost: Boolean
        get() = hostSocket != null

    companion object {
        /**
         * Global session store — all active relay sessions.
         */
        val sessions = ConcurrentHashMap<String, RelaySession>()

        fun getOrCreate(sessionId: String): RelaySession {
            return sessions.getOrPut(sessionId) {
                RelaySession(sessionId = sessionId)
            }
        }

        fun get(sessionId: String): RelaySession? {
            return sessions[sessionId]
        }

        fun remove(sessionId: String) {
            sessions.remove(sessionId)
        }

        fun cleanupExpired(): Int {
            val expired = sessions.filter { it.value.isExpired }
            expired.keys.forEach { sessions.remove(it) }
            return expired.size
        }
    }
}
