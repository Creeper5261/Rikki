package com.zzf.rikki.idea.agent.tools

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.openapi.project.Project
import com.zzf.rikki.idea.IdeBridgeServer
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets

/**
 * IDE tools for the lite agent.
 *
 *  - ide_context    : returns the ideContext that was sent with the request (stored in [ideContextHolder])
 *  - ide_action     : delegates to IdeBridgeServer (already running in the plugin JVM)
 *  - ide_capabilities: delegates to IdeBridgeServer
 */
class LiteIdeTools(private val project: Project, private val mapper: ObjectMapper) {

    /** The latest ideContext payload received from ChatPanel — set by LiteAgentEngine before each run. */
    @Volatile var ideContextNode: JsonNode = mapper.createObjectNode()

    // ── ide_context ───────────────────────────────────────────────────────────

    fun context(args: JsonNode): String {
        val ctx = ideContextNode
        if (ctx.isNull || ctx.isMissingNode || ctx.size() == 0) {
            return "No IDE context is available. Ensure the plugin is loaded and project is indexed."
        }

        val query      = args.path("query").asText("all")
        val maxItems   = args.path("maxItems").asInt(20).coerceIn(1, 100)
        val keys       = buildList<String> {
            if (args.has("keys") && args.path("keys").isArray)
                args.path("keys").forEach { add(it.asText("")) }
        }.filter { it.isNotBlank() }

        val filtered = mutableMapOf<String, JsonNode>()
        ctx.fields().forEach { (k, v) ->
            when {
                keys.isNotEmpty() && k in keys -> filtered[k] = v
                keys.isEmpty() && matchesQuery(k, query) -> filtered[k] = v
            }
        }

        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(filtered)
    }

    private fun matchesQuery(key: String, query: String): Boolean {
        if (query == "all") return true
        val k = key.lowercase()
        return when (query.lowercase()) {
            "project"  -> k.contains("project") || k.contains("workspace")
            "sdk"      -> k.contains("sdk") || k.contains("java") || k.contains("language")
            "build"    -> k.contains("gradle") || k.contains("mvn") || k.contains("build")
            "modules"  -> k.contains("module")
            else       -> true
        }
    }

    // ── ide_action ────────────────────────────────────────────────────────────

    fun action(args: JsonNode): String = callBridge(args)

    fun capabilities(): String = callBridge(mapper.createObjectNode().put("action", "capabilities"))

    // ── bridge call ───────────────────────────────────────────────────────────

    private fun callBridge(payload: JsonNode): String {
        val bridge = try {
            project.getService(IdeBridgeServer::class.java)
        } catch (_: Exception) { null }
            ?: return "IDE bridge unavailable. Ensure the plugin is running."

        val bridgeUrl = bridge.getBaseUrl().ifBlank {
            return "IDE bridge not started."
        }

        return try {
            val body = mapper.writeValueAsString(payload)
            val conn = (URI.create("$bridgeUrl/execute").toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
                connectTimeout = 5_000
                readTimeout    = 120_000
            }
            conn.outputStream.use { out ->
                OutputStreamWriter(out, StandardCharsets.UTF_8).use { it.write(body) }
            }
            val responseBody = conn.inputStream.use { it.readBytes().toString(StandardCharsets.UTF_8) }
            conn.disconnect()
            responseBody
        } catch (e: Exception) {
            "IDE bridge call failed: ${e.message}"
        }
    }
}
