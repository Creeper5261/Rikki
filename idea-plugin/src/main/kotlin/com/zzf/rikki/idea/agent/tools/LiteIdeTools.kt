package com.zzf.rikki.idea.agent.tools

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.intellij.openapi.project.Project
import com.zzf.rikki.idea.IdeBridgeServer
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets

/**
 * IDE tools for the lite agent.
 *
 *  - ide_context     : returns context payload captured by ChatPanel
 *  - ide_action      : capability-gated bridge actions (run/test/status/cancel)
 *  - ide_capabilities: probes the current IDE bridge capabilities
 */
class LiteIdeTools(private val project: Project, private val mapper: ObjectMapper) {

    data class CapabilitySnapshot(
        val bridgeAvailable: Boolean = false,
        val actionOperations: List<String> = emptyList()
    )

    /** The latest ideContext payload received from ChatPanel, set by LiteAgentEngine before each run. */
    @Volatile var ideContextNode: JsonNode = mapper.createObjectNode()

    @Volatile private var cachedCapabilitySnapshot = CapabilitySnapshot()

    fun capabilitySnapshot(): CapabilitySnapshot = cachedCapabilitySnapshot

    fun refreshCapabilities(): CapabilitySnapshot {
        val node = callBridgeJson(mapper.createObjectNode().put("action", "capabilities"))
        val snapshot = parseCapabilitySnapshot(node)
        cachedCapabilitySnapshot = snapshot
        return snapshot
    }

    fun context(args: JsonNode): String {
        val ctx = ideContextNode
        if (ctx.isNull || ctx.isMissingNode || ctx.size() == 0) {
            return "No IDE context is available. Ensure the plugin is loaded and project is indexed."
        }

        val query = args.path("query").asText("all")
        val keys = buildList {
            if (args.has("keys") && args.path("keys").isArray) {
                args.path("keys").forEach { add(it.asText("")) }
            }
        }.filter { it.isNotBlank() }

        val filtered = linkedMapOf<String, JsonNode>()
        ctx.fields().forEach { (k, v) ->
            when {
                keys.isNotEmpty() && k in keys -> filtered[k] = v
                keys.isEmpty() && matchesQuery(k, query) -> filtered[k] = v
            }
        }

        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(filtered)
    }

    fun action(args: JsonNode): String {
        val operationRaw = firstNonBlank(args.path("operation").asText(""), args.path("action").asText("")).lowercase()
        if (operationRaw.isBlank()) {
            return prettyJson(errorNode("missing_operation", "Field 'operation' is required for ide_action."))
        }

        if (operationRaw == "capabilities") {
            return capabilities()
        }
        if (operationRaw == "build") {
            return prettyJson(unsupportedBuildNode())
        }

        val snapshot = refreshCapabilities()
        if (!snapshot.bridgeAvailable) {
            return prettyJson(errorNode("bridge_unavailable", "IDE bridge unavailable. Use bash for build/test operations."))
        }

        if (operationRaw !in snapshot.actionOperations) {
            return prettyJson(errorNode("unsupported_operation", "Unsupported ide_action operation: $operationRaw"))
        }

        val payload = mapper.createObjectNode()
        when (operationRaw) {
            "run", "test" -> {
                payload.put("action", "start")
                payload.put("operation", operationRaw)
            }
            "status" -> payload.put("action", "status")
            "cancel" -> payload.put("action", "cancel")
            else -> return prettyJson(errorNode("unsupported_operation", "Unsupported ide_action operation: $operationRaw"))
        }

        copyIfPresent(args, payload, "mode", "configuration", "configurationName", "name", "executor", "jobId", "sinceRevision", "waitMs")

        val firstResponse = callBridgeJson(payload)
        val wait = args.path("wait").asBoolean(false)
        if (!wait || operationRaw !in setOf("run", "test")) {
            return prettyJson(firstResponse)
        }

        val jobId = firstNonBlank(firstResponse.path("jobId").asText(""))
        if (!firstResponse.path("ok").asBoolean(false) || jobId.isBlank()) {
            return prettyJson(firstResponse)
        }

        if ("status" !in snapshot.actionOperations) {
            return prettyJson(firstResponse)
        }

        val timeoutMs = args.path("timeoutMs").asLong(120_000L).coerceIn(1_000L, 900_000L)
        val pollIntervalMs = args.path("pollIntervalMs").asLong(1_000L).coerceIn(200L, 10_000L)
        val deadline = System.currentTimeMillis() + timeoutMs
        var sinceRevision = 0L
        var latest: JsonNode = firstResponse

        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(pollIntervalMs)
            val statusPayload = mapper.createObjectNode()
                .put("action", "status")
                .put("jobId", jobId)
                .put("sinceRevision", sinceRevision)
                .put("waitMs", pollIntervalMs)
            latest = callBridgeJson(statusPayload)
            sinceRevision = maxOf(sinceRevision, latest.path("logRevision").asLong(0L))
            if (isTerminalStatus(latest.path("status").asText(""))) {
                return prettyJson(latest)
            }
        }

        val timeoutNode = if (latest is ObjectNode) latest else mapper.createObjectNode()
        timeoutNode.put("ok", false)
        timeoutNode.put("status", "timeout")
        timeoutNode.put("summary", "Timed out waiting for IDE job completion.")
        timeoutNode.put("output", firstNonBlank(timeoutNode.path("output").asText(""), "Timed out waiting for IDE job completion."))
        return prettyJson(timeoutNode)
    }

    fun capabilities(): String {
        val node = callBridgeJson(mapper.createObjectNode().put("action", "capabilities"))
        cachedCapabilitySnapshot = parseCapabilitySnapshot(node)
        return prettyJson(node)
    }

    private fun parseCapabilitySnapshot(node: JsonNode): CapabilitySnapshot {
        if (!node.path("ok").asBoolean(false)) {
            return CapabilitySnapshot(false, emptyList())
        }
        val allowed = setOf("run", "test", "status", "cancel", "capabilities")
        val operations = linkedSetOf<String>()
        collectOps(node.path("asyncOperations"), operations, allowed)
        collectOps(node.path("directOperations"), operations, allowed)
        return CapabilitySnapshot(true, operations.toList())
    }

    private fun collectOps(source: JsonNode, dest: MutableSet<String>, allowed: Set<String>) {
        if (!source.isArray) return
        source.forEach { n ->
            val op = n.asText("").trim().lowercase()
            if (op in allowed) dest += op
        }
    }

    private fun matchesQuery(key: String, query: String): Boolean {
        if (query == "all") return true
        val k = key.lowercase()
        return when (query.lowercase()) {
            "project" -> k.contains("project") || k.contains("workspace")
            "build" -> k.contains("build") || k.contains("gradle") || k.contains("mvn")
            "modules" -> k.contains("module")
            "run" -> k.contains("run") || k.contains("configuration")
            else -> true
        }
    }

    private fun unsupportedBuildNode(): ObjectNode = mapper.createObjectNode().apply {
        put("ok", false)
        put("action", "build")
        put("status", "unsupported")
        put("code", "unsupported_operation")
        put("summary", "IDE build is disabled in cross-IDE mode. Use bash for build/test.")
        put("output", "IDE build is disabled in cross-IDE mode. Use bash for build/test.")
    }

    private fun errorNode(code: String, message: String): ObjectNode = mapper.createObjectNode().apply {
        put("ok", false)
        put("status", "error")
        put("code", code)
        put("summary", message)
        put("output", message)
    }

    private fun callBridgeJson(payload: JsonNode): JsonNode {
        val bridge = try {
            project.getService(IdeBridgeServer::class.java)
        } catch (_: Exception) { null }
            ?: return errorNode("bridge_unavailable", "IDE bridge unavailable. Ensure the plugin is running.")

        val bridgeUrl = bridge.getBaseUrl().ifBlank {
            return errorNode("bridge_not_started", "IDE bridge not started.")
        }

        return try {
            val body = mapper.writeValueAsString(payload)
            val conn = (URI.create("$bridgeUrl/execute").toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
                connectTimeout = 5_000
                readTimeout = 120_000
            }
            conn.outputStream.use { out ->
                OutputStreamWriter(out, StandardCharsets.UTF_8).use { it.write(body) }
            }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val responseBody = stream?.use { it.readBytes().toString(StandardCharsets.UTF_8) } ?: ""
            conn.disconnect()

            if (responseBody.isBlank()) {
                return errorNode("empty_response", "IDE bridge returned an empty response.")
            }
            try {
                mapper.readTree(responseBody)
            } catch (_: Exception) {
                errorNode("invalid_response", "IDE bridge returned non-JSON response: $responseBody")
            }
        } catch (e: Exception) {
            errorNode("bridge_call_failed", "IDE bridge call failed: ${e.message ?: "unknown error"}")
        }
    }

    private fun prettyJson(node: JsonNode): String =
        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(node)

    private fun firstNonBlank(vararg values: String): String {
        for (value in values) {
            val v = value.trim()
            if (v.isNotBlank()) return v
        }
        return ""
    }

    private fun copyIfPresent(source: JsonNode, target: ObjectNode, vararg keys: String) {
        for (k in keys) {
            if (!source.has(k)) continue
            val n = source.path(k)
            if (n.isMissingNode || n.isNull) continue
            target.set<JsonNode>(k, n.deepCopy())
        }
    }

    private fun isTerminalStatus(status: String): Boolean = when (status.trim().lowercase()) {
        "succeeded", "success", "failed", "aborted", "canceled", "cancelled", "timeout" -> true
        else -> false
    }
}
