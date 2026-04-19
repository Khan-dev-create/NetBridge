package com.netbridge.relay

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import java.time.Duration

/**
 * Install and configure Ktor plugins.
 */
fun Application.configurePlugins() {
    install(WebSockets) {
        pingPeriod = Duration.ofSeconds(15)
        timeout = Duration.ofSeconds(60) // Increased from 30s to 60s to prevent premature timeouts
        maxFrameSize = 65536
        masking = false
    }

    install(CallLogging)

    install(DefaultHeaders) {
        header("X-Server", "NetBridge-Relay/1.0.0")
    }
}
