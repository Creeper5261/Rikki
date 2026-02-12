# Code Agent UI/UX Repair & Enhancement Plan

## 1. Problem Analysis

### 1.1 Markdown Rendering Failure (`d:\plugin_dev\0.md`)
- **Symptom**: Agent's internal XML tags (e.g., `<bash>`, `<edit>`) are exposed in the raw output and not rendered as UI components. They may also be hidden by Markdown parsers treating them as HTML.
- **Root Cause**: The frontend treats the entire message as a single Markdown block, lacking a mechanism to parse structured "Tool Parts" or "Thought Parts" into distinct UI components.
- **Impact**: Poor readability; users see raw protocol implementation details instead of user-friendly interfaces.

### 1.2 Premature Loop Exit & Missing Final Answer (Terminal#1005-1021)
- **Symptom**: The session loop exits immediately after a "Thought" step without producing a final answer, especially when `finish_reason: "length"`.
- **Root Cause**: The `SessionLoop` logic treats any non-tool-call finish reason (including `length` caused by token limits) as a valid termination signal.
- **Impact**: Responses are truncated, and the agent fails to complete tasks that require long context or extensive reasoning.

### 1.3 Missing Interactive Views (Diff/Commit/File Preview)
- **Symptom**: Even when `edit` or `read` tools are executed, the UI remains static text. No Diff View or File Preview is embedded in the chat stream.
- **Root Cause**: The backend (`PendingChangesManager`) is decoupled from the chat stream (`AgentChatController`). The chat stream only sends text deltas, not "UI Events" or "Artifacts".
- **Impact**: Disconnected user experience; users must manually check files to verify changes.

---

## 2. Repair Plan (Immediate Fixes)

### 2.1 Fix Session Loop Termination Logic
- **Objective**: Prevent the agent from quitting when the output is truncated due to token limits (`finish_reason: "length"`).
- **Action**: Modify `SessionLoop.java`.
  - Detect `finish_reason == "length"`.
  - **Strategy**: Automatically trigger a "Continue" user message or append a system instruction to "Continue generating from where you left off" (or simply do not exit and treat it as a partial step if the model supports continuation).
  - *Refinement*: For now, ensure we DO NOT exit the loop if `finish_reason` is `length`.

### 2.2 Sanitize XML Output for Markdown
- **Objective**: Ensure raw XML tags are visible if they fallback to text.
- **Action**: Modify `SessionProcessor.java` or `Tool` serialization.
  - If the UI cannot render the component, wrap the XML in Markdown code blocks (```xml ... ```) to prevent it from being rendered as invisible HTML.
  - *Note*: This is a fallback. The real fix is Phase 3 (UI Components).

---

## 3. Development Plan (UI/UX Enhancements)

### 3.1 Linear Stream UI (图一 Goal)
- **Concept**: Treat the chat history not as a list of text messages, but as a linear stream of **Events** or **Parts**.
- **Implementation**:
  - **Backend**: Update `AgentChatController` SSE (Server-Sent Events) to support typed events:
    - `event: thought` (Text Delta)
    - `event: tool_call` (Structured: tool name, params)
    - `event: tool_result` (Structured: output, status)
  - **Frontend Integration**: The UI must listen to these events and render distinct cards (Thought Card, Tool Execution Card) sequentially.

### 3.2 Inline File Editing & Diff View (图二 Goal)
- **Concept**: Embed a "Pending Change" card directly in the chat stream when an edit occurs.
- **Implementation**:
  - **Backend**:
    - When `EditTool` is executed, `PendingChangesManager` creates a transaction.
    - `SessionProcessor` should emit a special event: `event: artifact_update` containing `{ type: "diff", filePath: "...", changeId: "..." }`.
  - **UI**: Render a mini Diff Viewer (or a button to open the Diff Dialog) in place of the raw XML `<edit>` tag.

### 3.3 Inline File Viewing (图三 Goal)
- **Concept**: Embed a collapsible "File Preview" card when the agent reads a file.
- **Implementation**:
  - **Backend**:
    - When `ReadTool` is executed, emit `event: artifact_view` containing `{ filePath: "...", content: "...", startLine: 1, endLine: 20 }`.
  - **UI**: Render a code snippet block with syntax highlighting, title bar showing the filename, and line numbers.

---

## 4. Execution Roadmap

1.  **Immediate**: Fix `SessionLoop.java` to handle `length` finish reason. [COMPLETED]
2.  **Immediate**: Update `SessionProcessor` to properly format tool outputs (wrap in code blocks as temporary fix for Markdown). [COMPLETED]
3.  **Phase 3**: Refactor `AgentChatController` to stream structured events (The "Protocol Upgrade"). [COMPLETED]
    - Refactored `AgentChatController` to emit `thought`, `tool_call`, `tool_result`, `artifact_update`, `artifact_view`.
    - Updated `ReadTool` and `ListTool` to emit `file_view` metadata.
    - Verified `EditTool` emits `pending_change` metadata.

