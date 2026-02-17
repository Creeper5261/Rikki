# Security & Stability TODO

> Scope: `code-agent` backend and plugin integration safety hardening.  
> Rule: close critical security/correctness issues first, then test fidelity, then maintainability.
> Status: Completed on February 17, 2026.

## Phase 1 - Critical Security (P0)

- [x] P0-1 `PendingChangesController` scope hardening
  - Remove `path`-only fallback lookup.
  - Resolve by `changeId` (preferred) or strictly scoped (`path + sessionId + workspaceRoot`) only.
  - Ignore client-provided `workspaceRoot` for apply; use stored pending scope.
  - Enforce canonical in-workspace path check before write/delete.
  - **Acceptance**: cross-session/path traversal/absolute path escape blocked.

- [x] P0-2 `PendingCommandController` execution scope hardening
  - Make request scope non-authoritative (do not execute based on request `workspaceRoot`).
  - Require scoped identity (`sessionId`) and verify command scope before resolution.
  - Execute in `pending.workdir` constrained under `pending.workspaceRoot`.
  - Tighten `PendingCommandsManager.scopeMatches` (no empty-scope pass-through).
  - **Acceptance**: workspace spoofing in request cannot alter execution directory.

- [x] P0-3 LLM logging privacy
  - Remove full request-body logging.
  - Replace with redacted summary (provider/model/message count/tool count/body size/hash).
  - Avoid logging raw request body on error paths.
  - **Acceptance**: logs contain no raw user prompt/history/tool args payload by default.

## Phase 2 - Correctness (P1)

- [x] P1-1 `SessionService.fork` deep-copy fix
  - Stop mutating original `PromptPart` objects.
  - Deep clone parts and nested mutable fields (`metadata`, `args`, `state`, `time`, etc.).
  - **Acceptance**: parent session message/part IDs and session IDs remain unchanged after fork.

- [x] P1-2 `SessionProcessorTest` signature alignment
  - Update tests to mock `LLMService.stream(input, callback, cancelSupplier)` 3-arg overload.
  - Ensure mocked method returns a completed `CompletableFuture<Void>`.
  - **Acceptance**: `SessionProcessorTest` passes without timeout.

- [x] P1-3 Global `ProjectContext` mutation removal in request flow
  - Stop mutating singleton `ProjectContext` per incoming chat request.
  - Keep workspace authority at session/tool-context level.
  - **Acceptance**: concurrent sessions do not overwrite each other's workspace context.

## Phase 3 - Maintainability (P2)

- [x] P2-1 Decompose `ChatPanel` (>5k LOC)
  - [x] Extract terminal activity text formatter (`ToolActivityFormatter`) from panel monolith.
  - [x] Extract workspace path resolver (`WorkspacePathResolver`) from panel monolith.
  - [x] Extract text summarization/preview formatter (`ChatUiTextFormatter`) from panel monolith.
  - [x] Extract conversation scroll state/controller (`ConversationScrollController`) from panel monolith.
  - [x] Extract tool status/duration formatter (`ToolStatusFormatter`) from panel monolith.
  - [x] Extract input controller (`ChatInputController`) for send/stop mode and text dispatch.
  - [x] Extract stop client (`ChatStopClient`) for `/stop` endpoint resolution and request execution.
  - [x] Extract SSE adapter for stream event line parse/dispatch (`ChatSseAdapter`).
  - [x] Extract tool metadata parser (`ChatToolMetaExtractor`) for pending change/command/meta output parsing.
  - [x] Extract conversation state manager for per-message/per-tool transitions (`ConversationStateManager` for assistant message state lifecycle).
  - [x] Extract assistant message identity state store (`MessageStateStore`).
  - [x] Extract tool activity transition state machine from `handleToolEvent` (`ToolEventStateMachine`).
  - [x] Extract renderer blocks for activity/read/diff card composition into dedicated renderer block (`ToolActivityRenderer`, first step in-panel).
  - [x] Add component-level regression tests for extracted helpers (`ChatSseAdapterTest`, `MessageStateStoreTest`, `ChatToolMetaExtractorTest`, `ConversationStateManagerTest`).

- [x] P2-2 Decompose `BashTool` (>1.8k LOC)
  - [x] Extract risk assessor + command family classifier (`BashRiskAssessor`).
  - [x] Extract shell/command normalizer (`BashCommandNormalizer`).
  - [x] Extract metadata builder (`BashMetadataBuilder`).
  - [x] Extract command executor (`BashCommandExecutor`).
  - [x] Extract IDE Java override policy/command composer (`BashJavaHomeOverride`).
  - [x] Extract build self-heal model (`BashBuildSelfHealModel`).
  - [x] Extract self-heal strategy (`BashBuildSelfHealService`).
  - [x] Extract IDE hints resolver (`BashIdeHintsResolver`).
  - [x] Add focused unit tests for extracted components.

---

## Final Verification

- [x] `./gradlew.bat :compileJava :idea-plugin:compileJava :test :idea-plugin:test --console=plain`  
  Result: `BUILD SUCCESSFUL`
- [x] `./gradlew.bat :test --tests "com.zzf.codeagent.core.tool.BashTool*" --console=plain`  
  Result: `BUILD SUCCESSFUL`
- [x] `./gradlew.bat :idea-plugin:test --tests "com.zzf.codeagent.idea.*" --console=plain`  
  Result: `BUILD SUCCESSFUL`
