# Refactoring & Optimization Todo List

## 1. Frontend Decoupling & Reuse (Rikki -> Current)
- [x] **Analyze Rikki's ChatPanel**: Studied `d:\plugin_dev\code-agent_pre\Rikki\idea-plugin\src\main\java\com\zzf\codeagent\idea\ChatPanel.java` for reusable patterns.
- [x] **Update ChatPanel.java**:
  - [x] Implement `handleTypewriterUpdate` for smooth text streaming.
  - [x] Update `handleMessagePartEvent` to support `delta` field.
  - [x] Implement `handleThoughtEvent` to render `<thought>` content in `CollapsiblePanel`.
  - [x] Implement `finish` event handler to show "Modified Files" summary.
  - [x] Add `setBusy` method for UI state management (Input blocking, Status bar).
  - [x] Fix `ToolRegistry` instantiation (Use empty list for UI context).

## 2. Backend Integration & Event Flow
- [x] **Fix SSE Subscription**: Updated `AgentChatController.java` to listen to `part.updated` instead of incorrect `session.message.part`.
- [x] **Structured Thinking Parsing**:
  - [x] Updated `SessionProcessor.java` to parse `<thought>...</thought>` tags into `ReasoningPart`.
  - [x] Updated `gemini.txt`, `qwen.txt`, `codex_header.txt` prompts to enforce `<thought>` tag usage.
- [x] **Event Emission**: Verified `SessionService` emits `part.updated` and `message.updated`.

## 3. Core Logic Fixes
- [x] **Fix SessionLoop Premature Exit**:
  - [x] Added `waitForRunningTools` in `SessionLoop.java` to prevent loop exit while tools are running.
  - [x] Added check to prevent stop if tool calls are present in the last message.
- [x] **Fix Compilation Errors**:
  - [x] Fixed `ToolRegistry` constructor mismatch in `ChatPanel`.
  - [x] Added missing `setBusy` method in `ChatPanel`.

## 4. Capabilities Enhancement
- [x] **File Creation**: Updated `EditTool.java` to support `CREATE` mode (empty `oldString`).
- [x] **File Deletion**: Created `DeleteTool.java` (`delete_file`) with `PendingChangesManager` integration.
- [x] **Pending Changes**: Verified `PendingChangesManager` supports `CREATE`, `EDIT`, `DELETE` types.

## 5. Verification
- [x] **Compilation**: Ensured all changes are syntactically correct and imports are resolved.
- [x] **Logic Check**: Verified Event -> Processor -> Controller -> UI flow.
