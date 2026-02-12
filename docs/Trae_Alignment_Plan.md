# Trae UX Alignment Plan (Project Rikki)

## Phase 1: Intelligent Stream & Thinking UI (思维流与展示优化)
**Goal**: Transform the "Wall of Text" raw logs into a structured, engaging, and readable narrative similar to Trae's "Thinking" blocks.

- [x] **Structured Thought Protocol**: Update `JsonReActAgent` prompt to output thoughts in structured sections (e.g., `**Analysis**`, `**Plan**`, `**Coding**`) rather than raw internal logs.
- [x] **Narrative "CoT" Prompt Engineering (New)**:
    - [x] **First-Person Narrative**: Instruct Agent to use "I" statements ("I am checking...", "I suspect that...").
    - [x] **Reflective Reasoning**: Content should reflect *process* (Drafting -> Refining -> Executing), not just *status*.
    - [x] **Sub-Headline Style**: Use bold sub-headers like `**Drafting the Plan**` or `**Refining Logic**` to break up thoughts.
    - [x] **No Internal Jargon**: Ban terms like "tool call", "json schema", "turn limit" from the thought stream.
- [x] **Distinct Thinking Block UI**: Implement a dedicated UI container/renderer in `ChatPanel` for "Thinking Process":
    - [x] **Brain Icon** (🧠) to mark the start of a thought block.
    - [x] **Visual Indentation**: Use visual indentation (vertical line or background color) to distinguish thoughts from final answers.
    - [x] **Markdown Support**: Support Markdown rendering within the thought block (headers, lists).
- [x] **Streaming "Typewriter" Effect**: Ensure thoughts render character-by-character to simulate "live thinking", enhancing the "alive" feeling.
- [x] **Collapsible Thoughts**: Allow users to Expand/Collapse the entire thinking block to focus on the result.

### 🔴 Known Issues (User Feedback)
- [x] **Repetitive Thinking Process**: The agent frequently repeats the high-level user goal ("User asked me to...") in every thought block, causing verbosity.
    - **Cause**: "Chain of Thought" prompt instruction likely too rigid/repetitive.
    - **Remediation**: Refine system prompt to forbid repeating high-level goals; focus strictly on *current step* reasoning.

## Phase 2: Contextual File Cards (上下文文件卡片)
**Goal**: Move file changes from the footer list to the conversation flow, creating a "Timeline" of actions.

- [x] **Inline File Events**: Refactor `AgentService` to push "File Modified" events to the UI stream immediately after a tool execution, interleaving them with thoughts.
- [x] **File Card Component**: Create a modern Swing `JPanel` (or `ListCellRenderer`) for file changes:
    - [x] **File Type Icon**: Dynamic icon based on file extension (Java, XML, YML).
    - [x] **Filename Emphasis**: Bold, high-contrast filename.
    - [x] **Smart Path Display**: Faded, small font for the full path (e.g., `.../src/main/java/...`) below the filename.
    - [x] **Diff Stats**: Show added/removed line counts (e.g., `+12 -3`) in Green/Red colors.
- [x] **Interactive Actions**:
    - [x] **Inline "View Diff"**: Click the card (or a specific "View Change" button) to open the diff dialog.
    - [x] **Quick Revert/Confirm**: Add icon-only buttons (×/√) on the right side of the card (replacing text buttons).

### 🔴 Known Issues (User Feedback)
- [x] **Missing File Cards**: Agent edits code (as seen in final answer) but no File Card appears in the stream.
    - **Cause**: `JsonReActAgent` or `AgentService` likely not emitting `FILE_CHANGE` events correctly, or UI SSE handler failing to parse/render them.
    - **Remediation**: Verify `notifyObservers` in `JsonReActAgent` and `handleSseEvent` in `ChatPanel`.
- [x] **Broken Navigation**: Clicking file paths in "Reading..." stream does not open the file.
    - **Cause**: `ChatPanel` likely uses `LocalFileSystem.findFileByPath` with relative paths (e.g. `src/Main.java`), which fails.
    - **Remediation**: Prepend `workspaceRoot` to relative paths before resolving.

## Phase 3: Visual Polish & Layout (视觉分层与布局)
**Goal**: Reduce visual noise and improve information density and aesthetics.

- [x] **Compact List Layout**: Optimize the `ChatPanel` list to handle variable-height items gracefully (Text vs. Cards vs. Thoughts).
- [x] **Dark Mode Affinity**: Ensure all UI elements (cards, borders, text colors) adapt perfectly to IntelliJ's Darcula/Dark themes.
- [x] **Iconography Upgrade**: Replace all text-based actions with modern icons (Pencil for edits, Link for references, Brain for thoughts).
- [x] **Scrollable Pending List**: (As per previous request) Ensure the "Pending Changes" list is collapsible and scrollable, not squeezing the chat view.

### 🔴 Known Issues (User Feedback)
- [x] **Dark Mode Contrast**: White text on white/light background makes text unreadable in Dark Mode.
    - **Cause**: `JEditorPane` or `JTextArea` text colors not adapting to `JBColor`, or background remaining light while text turns white (or vice versa).
    - **Remediation**: Enforce `JBColor` for all foregrounds/backgrounds; check `JEditorPane` CSS for dark theme support.
- [x] **Explicit Thinking Tags**: `[Hypothesis]`, `[Verification Plan]` tags look mechanical and cluttered.
    - **Cause**: Prompt explicitly instructs Agent to use `[Tag]` format.
    - **Remediation**: Update prompt to use Markdown bolding (e.g. `**Hypothesis**`) or natural language structure.

## Phase 4: Data Structure Refactoring (底层数据支撑)
**Goal**: Ensure the backend supports the granular, event-driven UI.

- [x] **Granular Event Stream**: Refactor `AgentChatController` and `AgentService` to stream events (`THOUGHT_CHUNK`, `TOOL_START`, `TOOL_END`, `FILE_CHANGE`) instead of just updating a monolithic state.
- [x] **Diff Calculation Optimization**: Implement efficient on-the-fly diff calculation (currently in `JsonReActAgent`) to provide the `+N/-M` stats for the cards.
- [x] **Reference Linking**: Ensure code references in text (e.g., `[Main.java]`) are clickable and link to the file in the editor.

## Phase 5: Deep Optimization & Refinement (深度优化与打磨)
**Goal**: Address specific usability and presentation issues to match Trae's high standards.

- [x] **Auto-Collapse Thoughts**: Thinking content should auto-collapse after generation is complete; only expand during streaming.
- [x] **Stream Buffering Safety**: Ensure the frontend receives/renders the *full* thought content before switching to the answer stream (prevent truncation shown in feedback).
- [x] **Flexible Thought Headers**: Provide header *templates* (e.g., `Analysis`, `Plan`) in the prompt but allow the Agent to be flexible/creative with naming.
- [x] **Header Formatting Fix**: Ensure proper line breaks (`\n`) after bold headers in the thinking block so body text doesn't trail on the same line.
- [x] **Non-Repetitive Focus**: Prompt Agent to state *current* focus/action (e.g., "I am checking...") instead of repeating the high-level user request.
- [x] **English Thinking / Native Answer**: Enforce **English** for the internal thinking process (for better LLM logic) but keep the **Final Answer** in the user's language.
- [x] **Prominent Pending UI**:
    - [x] **Top Fixed Panel**: Move "Pending Changes" control to the **top** of the chat (floating or fixed header).
    - [x] **Summary & Bulk Actions**: Show a summary line (e.g., "Pending Changes (3)") with global "Accept All" / "Reject All" buttons.
    - [x] **Expandable List**: Clicking the summary expands to show the file list and details.
- [x] **Remove Redundant "Show Diff"**: Remove the standalone "Show Diff" button from the bottom since the new Pending UI handles it.

## Phase 6: Future Enhancements (未来增强)
**Goal**: Further polish and features for future iterations.

- [ ] **Read/Focus Visualization (The "Eyeglasses" Look)**:
    - [ ] **Iconography**: Use an **Eyeglasses Icon** (👓) for `READ_FILE` and `LIST_FILES` actions to indicate "Agent is looking".
    - [ ] **Clickable Navigation**: Display the file path next to the icon.
        - [ ] **File**: Click to open the file in the left-side editor.
        - [ ] **Directory**: Static text (visual context only).
    - [ ] **Subtle Styling**: Keep "Read" events visually lighter (text-based) than "Edit" events (card-based) to distinguish context gathering from action.
