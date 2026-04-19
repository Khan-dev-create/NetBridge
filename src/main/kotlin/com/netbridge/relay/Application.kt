package com.netbridge.relay

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("Application")

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val host = "0.0.0.0"

    logger.info("Starting NetBridge Relay Server on $host:$port")

    // Launch cleanup job
    CoroutineScope(Dispatchers.Default).launch {
        cleanupLoop()
    }

    embeddedServer(Netty, port = port, host = host) {
        configurePlugins()
        configureRouting()
    }.start(wait = true)
}

/**
 * Periodically clean up expired sessions.
 * Runs every 10 minutes, removes sessions that have:
 * - No host connected
 * - No clients connected
 * - Age > 30 minutes
 */
private suspend fun cleanupLoop() {
    while (true) {
        delay(10 * 60 * 1000L) // 10 minutes

        try {
            val removedCount = RelaySession.cleanupExpired()
            if (removedCount > 0) {
                logger.info("[RELAY] Cleanup: removed $removedCount expired session(s)")
            }

            val activeSessions = RelaySession.sessions.size
            val totalClients = RelaySession.sessions.values.sumOf { it.clientCount }
            val hostsActive = RelaySession.sessions.values.count { it.hasHost }
            logger.info("[RELAY] Status: $activeSessions sessions, $hostsActive hosts, $totalClients clients")
        } catch (e: Exception) {
            logger.error("[RELAY] Cleanup error", e)
        }
    }
}
