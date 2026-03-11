package com.zzf.rikki.idea.agent

import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.openapi.project.Project
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.zzf.rikki.idea.agent.compat.InMemoryPendingApprovalService
import com.zzf.rikki.idea.agent.compat.LiteToolExecutor
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/**
 * Embedded JDK HTTP server that serves the same SSE API as the Spring Boot backend.
 * ChatPanel connects to this instead of localhost:18080.
 */
class LiteAgentServer(private val project: Project) {
    private val mapper = ObjectMapper()
    private val pendingApprovalService = InMemoryPendingApprovalService()
    private val toolExecutor = LiteToolExecutor(project, mapper, pendingApprovalService)
    private var server: HttpServer? = null
    var port: Int = 0
        private set

    fun start() {
        val srv = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 32)
        srv.createContext("/api/agent/chat/stream", ::handleStream)
        srv.createContext("/api/agent/skip", ::handleSkip)
        srv.createContext("/api/agent/confirm", ::handleConfirm)
        srv.createContext("/api/agent/pending-command", ::handlePendingCommand)
        srv.createContext("/api/agent/todos", ::handleTodos)
        srv.executor = Executors.newCachedThreadPool { r ->
            Thread(r, "rikki-lite-agent").also { it.isDaemon = true }
        }
        srv.start()
        server = srv
        port = srv.address.port
        System.setProperty("rikki.endpoint", "http://127.0.0.1:$port/api/agent/chat/stream")
        System.setProperty("rikki.skip.endpoint", "http://127.0.0.1:$port/api/agent/skip")
        System.setProperty("rikki.confirm.endpoint", "http://127.0.0.1:$port/api/agent/confirm")
        System.setProperty("rikki.pending.command.endpoint", "http://127.0.0.1:$port/api/agent/pending-command")
        System.setProperty("rikki.pending.enabled", "false")
    }

    fun stop() {
        server?.stop(0)
        server = null
    }

    private fun handleSkip(exchange: HttpExchange) {
        pendingApprovalService.skipCurrentExecution()
        exchange.sendResponseHeaders(200, -1)
        exchange.close()
    }

    private fun handleConfirm(exchange: HttpExchange) {
        val query = exchange.requestURI.rawQuery ?: ""
        val approved = query.split("&").any { it.equals("decision=approve", ignoreCase = true) }
        pendingApprovalService.resolveLatestPending(approved)
        exchange.sendResponseHeaders(200, -1)
        exchange.close()
    }

    private fun handlePendingCommand(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("POST", ignoreCase = true)) {
            exchange.sendResponseHeaders(405, -1)
            exchange.close()
            return
        }
        val request = try {
            mapper.readTree(exchange.requestBody.readAllBytes())
        } catch (_: Exception) {
            writeJson(exchange, 400, mapOf("status" to "error", "error" to "Invalid JSON payload"))
            return
        }
        val commandId = request.path("commandId").asText("")
        val reject = request.path("reject").asBoolean(false)
        val decisionMode = request.path("decisionMode").asText("")
        val result = pendingApprovalService.resolve(commandId, reject, decisionMode)
        writeJson(exchange, 200, result)
    }

    private fun handleTodos(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("GET", ignoreCase = true)) {
            exchange.sendResponseHeaders(405, -1)
            exchange.close()
            return
        }
        val workspaceRoot = queryParams(exchange.requestURI.rawQuery)["workspaceRoot"].orEmpty()
        val json = if (workspaceRoot.isBlank()) {
            "[]"
        } else {
            toolExecutor.todosAsListJson(workspaceRoot)
        }
        val bytes = json.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json; charset=UTF-8")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun handleStream(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("POST", ignoreCase = true)) {
            exchange.sendResponseHeaders(405, -1)
            return
        }

        val body = try {
            exchange.requestBody.readAllBytes()
        } catch (_: Exception) {
            return
        }
        val request = try {
            mapper.readTree(body)
        } catch (_: Exception) {
            return
        }

        val goal = request.path("goal").asText("")
        val workspaceRoot = request.path("workspaceRoot").asText(project.basePath ?: "")
        val ideContext = request.path("ideContext")
        val history = request.path("history")
        val sessionId = request.path("sessionID").asText("")
        val settings = request.path("settings")

        exchange.responseHeaders.add("Content-Type", "text/event-stream; charset=UTF-8")
        exchange.responseHeaders.add("Cache-Control", "no-cache")
        exchange.responseHeaders.add("Connection", "keep-alive")
        exchange.sendResponseHeaders(200, 0)

        val writer = LiteSseWriter(exchange.responseBody)
        try {
            val engine = LiteAgentEngine(project, mapper, pendingApprovalService, toolExecutor)
            runBlocking {
                engine.run(goal, workspaceRoot, ideContext, history, settings, sessionId, writer)
            }
        } catch (_: Exception) {
        } finally {
            try {
                exchange.responseBody.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun writeJson(exchange: HttpExchange, status: Int, payload: Any) {
        val bytes = mapper.writeValueAsBytes(payload)
        exchange.responseHeaders.add("Content-Type", "application/json; charset=UTF-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun queryParams(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) {
            return emptyMap()
        }
        return rawQuery.split('&')
            .mapNotNull { pair ->
                val idx = pair.indexOf('=')
                if (idx < 0) {
                    null
                } else {
                    pair.substring(0, idx) to java.net.URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8)
                }
            }
            .toMap()
    }
}