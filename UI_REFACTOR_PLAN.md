# UI Reuse and Refactoring Plan

## 1. Overview
The goal is to reuse the robust frontend UI logic from the `Rikki` project (specifically `ChatPanel.java` and `DiffService.java`) in the current `code-agent` project. This ensures a polished user experience with streaming visual effects, clear diff views, and proper backend integration.

## 2. Gap Analysis

### 2.1. Streaming Compatibility (Critical)
- **Issue**: The current backend (`AgentChatController.java`) emits a `message` event with a `delta` field for text streaming.
- **Current Frontend**: The `ChatPanel.java` listens for `text_delta` or `content` fields in `handleMessagePartEvent`.
- **Result**: Streaming text may not appear or will be delayed until the full content is available.
- **Fix**: Update `ChatPanel.java` to check for the `delta` field.

### 2.2. Visual Effects (Typewriter)
- **Rikki Project**: Uses a `Timer` based `typewriterEffect` to smooth out the rendering of the agent's answer.
- **Current Project**: Directly appends text to the `JEditorPane`, which can feel jerky or "mechanical".
- **Action**: Port the `typewriterEffect` logic from Rikki to the `updatePartComponent` method in the current `ChatPanel`.

### 2.3. Event Handling (`finish` Event)
- **Rikki Project**: The `finish` event handler parses the final response, updates the answer pane, logs the chat history, and explicitly renders a "Modified Files" summary panel.
- **Current Project**: The `finish` event handler is currently empty (`// Finalize answer`). It relies on `artifact_update` events for pending changes, but lacks the final summary and history update confirmation.
- **Action**: Port the logic from Rikki's `finish` handler to ensure the conversation state is finalized and changes are summarized.

### 2.4. Diff Service & Pending Changes
- **Status**: Both projects have nearly identical `DiffService.java`.
- **Integration**: The current project handles `artifact_update` events to trigger pending changes, which is a modern, event-driven approach. This is good and should be kept, but we should ensure the UI reflects these changes immediately (which it seems to do via `updatePartComponent`).

## 3. Implementation Plan

### Step 1: Fix Streaming Protocol
- Modify `handleMessagePartEvent` in `d:\plugin_dev\code-agent\idea-plugin\src\main\java\com\zzf\codeagent\idea\ChatPanel.java` to accept the `delta` field.

### Step 2: Implement Typewriter Effect
- Copy the `typewriterEffect` method from Rikki.
- Integrate it into `updatePartComponent` when updating `text` parts. Note: This requires careful state management to avoid conflict with incoming high-speed SSE events. A simpler approach might be to buffer the incoming text and let the timer consume it.

### Step 3: Restore `finish` Logic
- Populate the `finish` block in `handleSseEvent`.
- Ensure it handles:
    - Final answer text update (if different from stream).
    - Chat history appending (`history.appendLine`).
    - "Modified Files" summary panel (if `meta.pendingChanges` is present).

### Step 4: Verify DiffService
- Ensure `DiffService` is correctly instantiated and passed to `ChatPanel`.
- Confirm `showDiffExplicit` is working.

## 4. Next Steps
- Execute the code changes defined above.
- Verify with a test chat session.
