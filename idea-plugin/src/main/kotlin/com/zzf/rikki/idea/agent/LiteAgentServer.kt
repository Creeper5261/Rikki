package com.zzf.rikki.idea.agent

import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.openapi.project.Project
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/**
 * Embedded JDK HTTP server that serves the same SSE API as the Spring Boot backend.
 * ChatPanel connects to this instead of localhost:18080.
 */
class LiteAgentServer(private val project: Project) {

    private val mapper = ObjectMapper()
    private var server: HttpServer? = null
    var port: Int = 0
        private set

    fun start() {
        val srv = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 32)
        srv.createContext("/api/agent/chat/stream", ::handleStream)
        srv.executor = Executors.newCachedThreadPool { r ->
            Thread(r, "rikki-lite-agent").also { it.isDaemon = true }
        }
        srv.start()
        server = srv
        port = srv.address.port
        // Point ChatPanel to this local server; disable pending diff workflow
        System.setProperty("rikki.endpoint", "http://127.0.0.1:$port/api/agent/chat/stream")
        System.setProperty("rikki.pending.enabled", "false")
    }

    fun stop() {
        server?.stop(0)
        server = null
    }

    private fun handleStream(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("POST", ignoreCase = true)) {
            exchange.sendResponseHeaders(405, -1)
            return
        }

        val body = try { exchange.requestBody.readAllBytes() } catch (_: Exception) { return }
        val request = try { mapper.readTree(body) } catch (_: Exception) { return }

        val goal          = request.path("goal").asText("")
        val workspaceRoot = request.path("workspaceRoot").asText(project.basePath ?: "")
        val ideContext    = request.path("ideContext")
        val history       = request.path("history")
        val sessionId     = request.path("sessionID").asText("")
        val settings      = request.path("settings")

        exchange.responseHeaders.add("Content-Type", "text/event-stream; charset=UTF-8")
        exchange.responseHeaders.add("Cache-Control", "no-cache")
        exchange.responseHeaders.add("Connection", "keep-alive")
        exchange.sendResponseHeaders(200, 0)

        val sseWriter = LiteSseWriter(exchange.responseBody)
        try {
            val engine = LiteAgentEngine(project, mapper)
            runBlocking {
                engine.run(goal, workspaceRoot, ideContext, history, settings, sessionId, sseWriter)
            }
        } catch (_: Exception) {
            // client disconnected or cancelled
        } finally {
            try { exchange.responseBody.close() } catch (_: Exception) {}
        }
    }
}
