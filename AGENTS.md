# AGENTS.md

## Scope
This file applies to the `lite` branch of the `code-agent` repository. The lite branch is a standalone IntelliJ plugin that calls LLM APIs directly — no backend server is involved.

---

## Architecture Overview

```
idea-plugin/src/main/
├── kotlin/com/zzf/rikki/idea/
│   ├── agent/
│   │   ├── LiteAgentEngine.kt       # Agentic LLM loop & tool orchestration
│   │   ├── LiteAgentServer.kt       # Coroutine wrapper; exposes run() to ChatPanel
│   │   ├── LiteSseWriter.kt         # SSE event emission to ChatPanel
│   │   ├── LiteToolRegistry.kt      # Tool dispatcher & high-risk detection
│   │   └── tools/
│   │       ├── LiteBashTool.kt      # Shell execution (timeout, skip, output truncation)
│   │       ├── LiteFileTools.kt     # read, write, edit, delete, glob, grep, ls
│   │       ├── LiteIdeTools.kt      # IDE context query & bridge actions
│   │       └── LiteTodoTools.kt     # File-backed todo list (.rikki/todos.json)
│   ├── completion/
│   │   ├── RikkiInlineCompletionProvider.kt  # IntelliJ 2024.1+ inline completion
│   │   └── RikkiTriggerCompletionAction.kt   # Manual trigger (Alt+\)
│   ├── llm/
│   │   └── LiteLlmClient.kt         # Streaming LLM client (FIM + Chat modes)
│   └── settings/
│       ├── RikkiCredentials.kt      # PasswordSafe-backed API key storage
│       ├── RikkiSettings.kt         # PersistentStateComponent (provider, model, URL)
│       └── RikkiSettingsConfigurable.kt  # Settings UI
└── java/com/zzf/rikki/idea/
    ├── ChatPanel.java               # Primary chat UI (conversation, tool cards, diffs)
    ├── ChatHistoryService.java      # Conversation history persistence
    ├── ChatInputController.java     # Input area, send/stop button
    ├── ChatSseAdapter.java          # Adapts SSE stream to UI events
    ├── DiffService.java             # Diff rendering and apply/revert
    ├── IdeBridgeServer.java         # Local HTTP endpoint for IDE-native actions
    └── ...                          # Supporting UI helpers
```

---

## Key Subsystems

### LLM Client (`LiteLlmClient.kt`)
- Single HTTP streaming client; no SDK dependency.
- **FIM mode**: POST `/completions` with `prompt` + `suffix`; reads `choices[0].text`. Used for DeepSeek and Ollama.
- **Chat mode**: POST `/chat/completions`; reads `choices[0].delta.content`. Used for all other providers.
- Mode is selected by `RikkiSettings.State.completionUsesFim()`.

### Agent Engine (`LiteAgentEngine.kt`)
- Agentic loop: up to 120 steps; 8000-char output truncation per step.
- **Model capability flags** (provider + model name):
  - DeepSeek R1: streams `reasoning_content` → `thought` events.
  - OpenAI o1/o1-mini/o1-preview: `systemRole=developer`, `temperature=1.0`, `max_completion_tokens`, tools disabled.
  - OpenAI o3/o4: `max_completion_tokens`.
  - All others: standard OpenAI-compatible.
- Tool results are appended as `tool` role messages.
- Conversation history is replayed from `ChatHistoryService` on session restore.

### Tool Registry (`LiteToolRegistry.kt`)
13 tools total:

| Tool | High-Risk | Notes |
|---|---|---|
| `bash` | Pattern-based | See risk patterns below |
| `read` | No | Line-numbered; 2000-line, 50 KB limits |
| `write` | No | Creates or overwrites |
| `edit` | No | Fuzzy line-trim match for string replacement |
| `delete_file` | Always | No exceptions |
| `glob` | No | Sorted by mtime |
| `grep` | No | Regex; optional file filter |
| `ls` | No | Optional ignore patterns |
| `todo_read` | No | `.rikki/todos.json` |
| `todo_write` | No | Replaces entire list; auto-assigns UUIDs |
| `ide_context` | No | Project/SDK/build info as JSON |
| `ide_action` | No | build/run/test via IdeBridgeServer |
| `ide_capabilities` | No | Bridge capability query |

**Bash risk patterns**: `sudo`, `su -`, `rm -rf`, `rm -fr`, `rm -r `, `rm -f /`, `mkfs`, `dd if=`, `| bash`, `| sh `, `| zsh `, `| fish `, `chmod 777`, `chmod -R `, `/dev/sd`, `/dev/hd`, `/dev/nvme`, fork bomb.

### High-Risk Confirmation Flow
1. Before executing any tool, `isHighRisk(toolName, args)` is evaluated.
2. If true: emit `tool_confirm` SSE event → UI displays command preview with Approve / Skip buttons.
3. Engine blocks on `CompletableFuture<Boolean>` (timeout: 1 hour).
4. User approves → execution proceeds. User rejects → result message `"(User rejected this command. Do not retry.)"` is added; loop continues.
5. `confirmFutureRef` is cleared after each decision.

### Inline Completion (`RikkiInlineCompletionProvider.kt`)
- Extends `DebouncedInlineCompletionProvider`; implements both `getSuggestionDebounced()` (2024.1) and `getSuggestion()` (2025.1+).
- **Important**: uses `ReadAction.compute<Context, Throwable>{}` — NOT the suspend `readAction{}`. The suspend variant waits for smart mode (indexing) and causes multi-second delays.
- Reads `editor.caretModel.primaryCaret.offset` (not `request.startOffset`) to avoid duplicating the last typed character.
- 350ms debounce; prefix limit 2000 chars; suffix limit 400 chars.
- Single-emit: accumulates all tokens, emits once as `InlineCompletionGrayTextElement`.

### Settings & Credentials
- `RikkiSettings`: PersistentStateComponent storing provider, model, custom URL, and completion overrides.
- `RikkiCredentials`: thread-safe in-memory cache backed by IntelliJ PasswordSafe. Keys: `DEEPSEEK`, `OPENAI`, `GEMINI`, `MOONSHOT`, `OLLAMA`, `CUSTOM`, `COMPLETION_OVERRIDE`. API keys are never written to disk outside PasswordSafe.
- Supported chat providers: DeepSeek, OpenAI, Google Gemini, Moonshot, Ollama, Custom.
- Completion can use a separate provider/model/key from the chat agent.

---

## Build

| Setting | Value |
|---|---|
| IntelliJ SDK | 2024.1 IC (Community) |
| Since-build | 241 (open-ended) |
| Kotlin | 1.9.22 (forced via resolutionStrategy) |
| Java toolchain | 17 |
| Gradle plugin | org.jetbrains.intellij 1.17.4 |
| Jackson | 2.15.2 (compileOnly — bundled by IntelliJ) |
| CommonMark | 0.21.0 (implementation — shipped in plugin) |

**Native Linux build** (required — Windows Gradle is slow):
```bash
# Sync to native filesystem
rsync -a --delete --exclude='.git' --exclude='idea-plugin/build' --exclude='build' \
  /mnt/d/plugin_dev/code-agent/ /tmp/gradle_build_test/

# Create gradle.properties (gitignored)
cat > /tmp/gradle_build_test/gradle.properties << EOF
kotlin.stdlib.default.dependency=false
org.gradle.jvmargs=-Xmx2g
EOF

# Build
cd /tmp/gradle_build_test
GRADLE_USER_HOME=/tmp/gradle_home_native ./gradlew :idea-plugin:buildPlugin --no-daemon

# Deploy
cp idea-plugin/build/distributions/idea-plugin-0.1.0.zip \
   /mnt/d/plugin_dev/code-agent/idea-plugin-0.1.0.zip
```

---

## Engineering Rules

- **Minimal changes**: only modify what is directly required. Avoid broad refactors.
- **ReadAction discipline**: never use the suspend `readAction{}` in completion code — use `ReadAction.compute()`.
- **Provider parity**: when adding or removing a provider, update `RikkiSettingsConfigurable.kt` (enums + `apply()`/`reset()`), `RikkiSettings.kt` (base URL `when` blocks), and `RikkiCredentials.kt` (`PROVIDERS` list). Keep plugin.xml description in sync.
- **SSE event names**: `session`, `status`, `message`, `thought`, `thought_end`, `tool_call`, `tool_confirm`, `tool_result`, `finish`, `todo_updated`. Do not rename without updating `ChatSseAdapter.java`.
- **UI scroll rules**: user messages → `scrollToBottom()` (forced). Assistant/streaming content → `scrollToBottomSmart()` (proximity-based; do not force-scroll if user has scrolled up).
- **Auto-expand/collapse**: `CollapsiblePanel.autoExpand()` / `autoCollapse()` respect `manuallyExpanded` flag. Never force-collapse a panel the user explicitly opened.
- **Jackson is compileOnly**: do not add Jackson as `implementation` — it is provided by the IntelliJ platform bundle.
- **kotlin-stdlib exclusion**: must remain in `idea-plugin/build.gradle.kts` to prevent version conflicts.

## Safety Rules

- High-risk tools (`bash` with risk patterns, `delete_file`) must always go through the confirmation flow. Do not bypass or short-circuit it.
- Never log API keys or credential values.
- `WorkspacePathResolver` scope checks must not be removed or weakened.
- Do not hardcode API keys, base URLs, or model names in source files.

## Delivery Expectations

After each change, report:
- Files modified and what changed.
- Build result (`BUILD SUCCESSFUL` / errors).
- git commit hash and push status.

If a build cannot be validated locally, state it explicitly rather than assuming success.
