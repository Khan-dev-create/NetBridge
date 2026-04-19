package com.netbridge.relay

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.websocket.*
import kotlinx.coroutines.*
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

fun main() {
    val port = System.getenv("PORT")?.toInt() ?: 8080

    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        install(WebSockets) {
            pingPeriod = 15.seconds
            timeout = 30.seconds
            maxFrameSize = 65536
        }
        configureRouting()

        // Cleanup job
        launch {
            while (true) {
                delay(10.minutes)
                val now = System.currentTimeMillis()
                val toRemove = sessions.entries.filter { (_, session) ->
                    session.hostSocket == null &&
                    session.clients.isEmpty() &&
                    (now - session.createdAt) > 30 * 60 * 1000
                }.map { it.key }

                toRemove.forEach {
                    sessions.remove(it)
                    println("[RELAY] Cleanup: removed session $it")
                }
            }
        }
    }.start(wait = true)
}
