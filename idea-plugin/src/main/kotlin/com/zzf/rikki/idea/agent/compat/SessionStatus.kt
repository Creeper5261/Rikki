package com.zzf.rikki.idea.agent.compat

import java.util.concurrent.ConcurrentHashMap

data class SessionStatusInfo(
    val type: String,
    val attempt: Int? = null,
    val message: String? = null,
    val next: Long? = null
)

class SessionStatus {
    private val state = ConcurrentHashMap<String, SessionStatusInfo>()

    fun get(sessionId: String): SessionStatusInfo = state[sessionId] ?: SessionStatusInfo(type = "idle")

    fun set(sessionId: String, info: SessionStatusInfo) {
        if (info.type == "idle") {
            state.remove(sessionId)
        } else {
            state[sessionId] = info
        }
    }
}