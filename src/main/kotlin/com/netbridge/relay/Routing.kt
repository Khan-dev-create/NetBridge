package com.netbridge.relay

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

val sessions = ConcurrentHashMap<String, RelaySession>()

fun Application.configureRouting() {
    routing {

        get("/health") {
            call.respondText("NetBridge Relay OK")
        }

        // HOST endpoint
        webSocket("/session/{sessionId}/host") {
            val sessionId = call.parameters["sessionId"] ?: run {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Missing sessionId"))
                return@webSocket
            }

            val session = sessions.getOrPut(sessionId) { RelaySession(sessionId) }

            if (session.hostSocket != null) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Host already connected"))
                return@webSocket
            }

            session.hostSocket = this
            println("[RELAY] Session $sessionId: HOST connected")

            // ✅ HOST ko ACK bhejo — 0x06
            send(Frame.Binary(true, byteArrayOf(0x06)))
            println("[RELAY] Session $sessionId: ACK sent to HOST")

            // Existing clients ko notify karo
            session.clients.values.forEach { clientSocket ->
                try {
                    clientSocket.send(Frame.Binary(true, byteArrayOf(0x04)))
                } catch (e: Exception) { }
            }

            try {
                for (frame in incoming) {
                    if (frame is Frame.Binary) {
                        val data = frame.readBytes()
                        if (data.isEmpty()) continue

                        when (data[0]) {
                            0x02.toByte() -> {
                                // Ping — pong
                                send(Frame.Binary(true, byteArrayOf(0x02)))
                            }
                            0x01.toByte() -> {
                                // Data frame — clientId extract karo (36 bytes) + forward
                                if (data.size > 37) {
                                    val clientId = String(data, 1, 36)
                                    val payload = data.copyOfRange(37, data.size)
                                    session.clients[clientId]?.let { clientSocket ->
                                        try {
                                            clientSocket.send(Frame.Binary(true, payload))
                                        } catch (e: Exception) { }
                                    }
                                }
                            }
                            else -> {
                                // Broadcast to all clients
                                session.clients.values.forEach { clientSocket ->
                                    try {
                                        clientSocket.send(Frame.Binary(true, data))
                                    } catch (e: Exception) { }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                println("[RELAY] Session $sessionId: HOST error — ${e.message}")
            } finally {
                session.hostSocket = null
                println("[RELAY] Session $sessionId: HOST disconnected")

                // Sab clients ko notify karo
                session.clients.values.forEach { clientSocket ->
                    try {
                        clientSocket.send(Frame.Binary(true, byteArrayOf(0x05)))
                    } catch (e: Exception) { }
                }
            }
        }

        // CLIENT endpoint
        webSocket("/session/{sessionId}/client/{clientId}") {
            val sessionId = call.parameters["sessionId"] ?: run {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Missing sessionId"))
                return@webSocket
            }
            val clientId = call.parameters["clientId"] ?: run {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Missing clientId"))
                return@webSocket
            }

            val session = sessions[sessionId]

            if (session == null || session.hostSocket == null) {
                send(Frame.Binary(true, byteArrayOf(0x03)))
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Host not active"))
                return@webSocket
            }

            session.clients[clientId] = this
            println("[RELAY] Session $sessionId: CLIENT $clientId connected (total: ${session.clients.size})")

            // ✅ CLIENT ko bhi ACK bhejo — 0x06
            send(Frame.Binary(true, byteArrayOf(0x06)))
            println("[RELAY] Session $sessionId: ACK sent to CLIENT $clientId")

            // Host ko notify karo
            try {
                val notifyBytes = byteArrayOf(0x04) + clientId.toByteArray()
                session.hostSocket?.send(Frame.Binary(true, notifyBytes))
            } catch (e: Exception) { }

            try {
                for (frame in incoming) {
                    if (frame is Frame.Binary) {
                        val data = frame.readBytes()
                        if (data.isEmpty()) continue

                        // Client data → Host ko forward karo
                        val clientIdBytes = clientId.toByteArray()
                        val payload = ByteArray(1 + clientIdBytes.size + data.size)
                        payload[0] = 0x01
                        clientIdBytes.copyInto(payload, 1)
                        data.copyInto(payload, 1 + clientIdBytes.size)

                        try {
                            session.hostSocket?.send(Frame.Binary(true, payload))
                        } catch (e: Exception) { }
                    }
                }
            } catch (e: Exception) {
                println("[RELAY] Session $sessionId: CLIENT $clientId error — ${e.message}")
            } finally {
                session.clients.remove(clientId)
                println("[RELAY] Session $sessionId: CLIENT $clientId disconnected")

                // Host ko notify karo
                try {
                    val notifyBytes = byteArrayOf(0x05) + clientId.toByteArray()
                    session.hostSocket?.send(Frame.Binary(true, notifyBytes))
                } catch (e: Exception) { }
            }
        }
    }
}
