package com.netbridge.relay

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.websocket.*
import kotlinx.coroutines.*
import java.time.Duration

fun main() {
    val port = System.getenv("PORT")?.toInt() ?: 8080

    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        install(WebSockets) {
            pingPeriodMillis = 15_000L
            timeoutMillis = 30_000L
            maxFrameSize = 65536L
        }
        configureRouting()

        // Cleanup job
        launch {
            while (true) {
                delay(10 * 60 * 1000L) // 10 minutes
                val now = System.currentTimeMillis()
                val toRemove = sessions.entries
                    .filter { (_, session) ->
                        session.hostSocket == null &&
                        session.clients.isEmpty() &&
                        (now - session.createdAt) > 30 * 60 * 1000L
                    }
                    .map { it.key }

                toRemove.forEach {
                    sessions.remove(it)
                    println("[RELAY] Cleanup: removed session $it")
                }
            }
        }
    }.start(wait = true)
}
