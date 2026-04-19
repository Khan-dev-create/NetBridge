package com.netbridge.relay

import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("Routing")

/**
 * Configure all HTTP and WebSocket routes.
 */
fun Application.configureRouting() {
    routing {

        // ============================================
        // Health check endpoint
        // ============================================
        get("/health") {
            val sessionCount = RelaySession.sessions.size
            val totalClients = RelaySession.sessions.values.sumOf { it.clientCount }
            call.respondText("NetBridge Relay OK | Sessions: $sessionCount | Clients: $totalClients")
        }

        // ============================================
        // SESSION GROUP
        // ============================================
        route("/session/{sessionId}") {
            
            // HOST WebSocket endpoint
            webSocket("/host") {
                val sessionId = call.parameters["sessionId"] ?: run {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Missing session ID"))
                    return@webSocket
                }

                val session = RelaySession.getOrCreate(sessionId)

                // Check if host already exists
                if (session.hostSocket != null) {
                    logger.warn("[RELAY] Session $sessionId: HOST rejected — already connected")
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Host already connected"))
                    return@webSocket
                }

                // Register host
                session.hostSocket = this
                logger.info("[RELAY] Session $sessionId: HOST connected")

                // Send ACK to host confirming connection
                try {
                    val ackFrame = byteArrayOf(0x06) + "host_ack".toByteArray()
                    send(Frame.Binary(true, ackFrame))
                    logger.info("[RELAY] Session $sessionId: HOST ACK sent")
                } catch (e: Exception) {
                    logger.error("[RELAY] Session $sessionId: Failed to send HOST ACK", e)
                }

                // Notify all existing clients that host connected
                val hostConnectedFrame = byteArrayOf(0x04) + "host_connected".toByteArray()
                session.clients.forEach { (clientId, clientSocket) ->
                    try {
                        clientSocket.send(Frame.Binary(true, hostConnectedFrame))
                    } catch (e: Exception) {
                        logger.warn("[RELAY] Session $sessionId: Failed to notify client $clientId of host connect")
                    }
                }

                try {
                    // Receive loop — forward frames from host to clients
                    for (frame in incoming) {
                        if (frame is Frame.Binary) {
                            val data = frame.readBytes()
                            if (data.isEmpty()) continue

                            when (data[0]) {
                                0x01.toByte() -> {
                                    // Data frame: [0x01][36-byte clientId][payload]
                                    if (data.size > 37) {
                                        val clientId = String(data, 1, 36)
                                        val clientSocket = session.clients[clientId]
                                        if (clientSocket != null) {
                                            try {
                                                // Forward payload to specific client (strip clientId, keep header)
                                                val clientFrame = ByteArray(data.size - 36)
                                                clientFrame[0] = 0x01
                                                System.arraycopy(data, 37, clientFrame, 1, data.size - 37)
                                                clientSocket.send(Frame.Binary(true, clientFrame))
                                            } catch (e: Exception) {
                                                logger.warn("[RELAY] Session $sessionId: Failed to forward to client $clientId")
                                                session.clients.remove(clientId)
                                            }
                                        }
                                    } else {
                                        // Broadcast to all clients if no clientId specified
                                        session.clients.forEach { (cid, cSocket) ->
                                            try {
                                                cSocket.send(Frame.Binary(true, data))
                                            } catch (e: Exception) {
                                                logger.warn("[RELAY] Session $sessionId: Failed to broadcast to $cid")
                                                session.clients.remove(cid)
                                            }
                                        }
                                    }
                                }
                                0x02.toByte() -> {
                                    // Ping — respond with pong
                                    send(Frame.Binary(true, byteArrayOf(0x02)))
                                }
                            }
                        }
                    }
                } catch (e: ClosedReceiveChannelException) {
                    logger.info("[RELAY] Session $sessionId: HOST channel closed")
                } catch (e: Exception) {
                    logger.error("[RELAY] Session $sessionId: HOST error", e)
                } finally {
                    // Host disconnected
                    session.hostSocket = null
                    logger.info("[RELAY] Session $sessionId: HOST disconnected")

                    // Notify all clients
                    val hostDisconnectedFrame = byteArrayOf(0x05) + "host_disconnected".toByteArray()
                    session.clients.forEach { (clientId, clientSocket) ->
                        try {
                            clientSocket.send(Frame.Binary(true, hostDisconnectedFrame))
                        } catch (e: Exception) {
                            logger.warn("[RELAY] Session $sessionId: Failed to notify client $clientId of host disconnect")
                        }
                    }
                }
            }

            // CLIENT WebSocket endpoint
            webSocket("/client/{clientId}") {
                val sessionId = call.parameters["sessionId"] ?: run {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Missing session ID"))
                    return@webSocket
                }
                val clientId = call.parameters["clientId"] ?: run {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Missing client ID"))
                    return@webSocket
                }

                val session = RelaySession.getOrCreate(sessionId)

                // Register client regardless of host status
                session.clients[clientId] = this
                logger.info("[RELAY] Session $sessionId: CLIENT $clientId connected (total: ${session.clientCount})")

                // Send ACK to client confirming connection to relay
                try {
                    val hostActive = session.hostSocket != null
                    val ackPayload = if (hostActive) "connected_host_active" else "connected_host_inactive"
                    val ackFrame = byteArrayOf(0x06) + ackPayload.toByteArray()
                    send(Frame.Binary(true, ackFrame))
                    logger.info("[RELAY] Session $sessionId: CLIENT $clientId ACK sent (hostActive=$hostActive)")
                } catch (e: Exception) {
                    logger.error("[RELAY] Session $sessionId: Failed to send CLIENT $clientId ACK", e)
                }

                // Notify host that client connected (if host is online)
                val hostSocket = session.hostSocket
                if (hostSocket != null) {
                    try {
                        val clientConnectedFrame = byteArrayOf(0x04) + clientId.toByteArray()
                        hostSocket.send(Frame.Binary(true, clientConnectedFrame))
                    } catch (e: Exception) {
                        logger.warn("[RELAY] Session $sessionId: Failed to notify host of client $clientId connect")
                    }
                }

                try {
                    // Receive loop — forward frames from client to host
                    for (frame in incoming) {
                        if (frame is Frame.Binary) {
                            val data = frame.readBytes()
                            if (data.isEmpty()) continue

                            when (data[0]) {
                                0x01.toByte() -> {
                                    // Data frame from client — prepend clientId and forward to host
                                    val currentHost = session.hostSocket
                                    if (currentHost != null) {
                                        try {
                                            // Build host frame: [0x01][36-byte clientId][payload]
                                            val clientIdBytes = clientId.toByteArray()
                                            val paddedClientId = ByteArray(36)
                                            System.arraycopy(
                                                clientIdBytes, 0, paddedClientId, 0,
                                                minOf(clientIdBytes.size, 36)
                                            )

                                            val hostFrame = ByteArray(1 + 36 + data.size - 1)
                                            hostFrame[0] = 0x01
                                            System.arraycopy(paddedClientId, 0, hostFrame, 1, 36)
                                            System.arraycopy(data, 1, hostFrame, 37, data.size - 1)

                                            currentHost.send(Frame.Binary(true, hostFrame))
                                        } catch (e: Exception) {
                                            logger.warn("[RELAY] Session $sessionId: Failed to forward client $clientId data to host")
                                        }
                                    }
                                }
                                0x02.toByte() -> {
                                    // Ping — respond with pong
                                    send(Frame.Binary(true, byteArrayOf(0x02)))
                                }
                            }
                        }
                    }
                } catch (e: ClosedReceiveChannelException) {
                    logger.info("[RELAY] Session $sessionId: CLIENT $clientId channel closed")
                } catch (e: Exception) {
                    logger.error("[RELAY] Session $sessionId: CLIENT $clientId error", e)
                } finally {
                    // Client disconnected
                    session.clients.remove(clientId)
                    logger.info("[RELAY] Session $sessionId: CLIENT $clientId disconnected (remaining: ${session.clientCount})")

                    // Notify host
                    val currentHost = session.hostSocket
                    if (currentHost != null) {
                        try {
                            val clientDisconnectedFrame = byteArrayOf(0x05) + clientId.toByteArray()
                            currentHost.send(Frame.Binary(true, clientDisconnectedFrame))
                        } catch (e: Exception) {
                            logger.warn("[RELAY] Session $sessionId: Failed to notify host of client $clientId disconnect")
                        }
                    }
                }
            }
        }

        /**
         * Fallback route to log 404s and provide feedback.
         */
        route("{...}") {
            handle {
                val path = call.request.uri
                logger.warn("[RELAY] 404 Not Found: $path")
                call.respondText("404: NetBridge Relay doesn't recognize this path: $path", status = io.ktor.http.HttpStatusCode.NotFound)
            }
        }
    }
}
