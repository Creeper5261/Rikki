package com.zzf.rikki.idea.agent.compat

import kotlinx.coroutines.runBlocking

fun streamChatBlocking(
    client: LlmStreamClient,
    request: LlmChatRequest,
    listener: LlmStreamListener
): LlmStreamResult = runBlocking {
    client.streamChat(request, listener)
}

fun awaitDecisionBlocking(
    service: PendingApprovalService,
    commandId: String
): ToolExecutionResult = runBlocking {
    service.awaitDecision(commandId)
}
