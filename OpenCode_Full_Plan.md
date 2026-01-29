# OpenCode Full Adoption Plan

Goal: Fully align code-agent behavior with OpenCode (prompting, session loop, tools, diff workflow, and performance).

Working rule: Each session targets completion of one phase. Reporting is only at phase end.

## Phase A - Session Loop & Prompt Alignment
Status: done
**Scope**
- Unify system/tool/plan/build prompts to `src/main/resources/opencode/**`.
- Implement plan/build switching (PLAN_ENTER/PLAN_EXIT + build-switch prompt injection).
- Reminder injection strategy (deduped, only when needed).
- MAX_STEPS enforcement (text-only exit).
- Summary/Compaction trigger logic aligned to OpenCode.

**Deliverables**
- Prompt sources fully swapped to OpenCode resources.
- Plan/build switching fully functional.
- Summary/compaction behavior stable with large contexts.

**Acceptance**
- Long context still responds correctly, no tool calls after MAX_STEPS.
- Plan mode blocks writes/commands until PLAN_EXIT.
- Reminder appears only when user message changes.

## Phase B - Tool Policy & Permissions
Status: done
**Scope**
- Tool alias mapping (OpenCode tool names -> local tool names).
- Read-before-edit enforcement with new-file exceptions.
- Allow/deny rule support (env/system properties) enforced in execution layer.
- Soft guidance for low-efficiency tool loops (no hard blocking).

**Deliverables**
- Tool permission rules applied at execution layer.
- Read-before-edit enforced; exceptions documented (CREATE_FILE/patch add file).

**Acceptance**
- Disallowed tools are rejected with clear error.
- Edit/patch without prior read is rejected unless new file.
- Tool alias calls succeed (e.g., Read/Edit/Bash).

## Phase C - Shadow Workspace & Diff/History
Status: done
**Scope**
- Ensure pending overlay is the default edit path when enabled.
- Diff/preview/confirm UI stable across sessions.
- History persistence and session isolation verified.

**Deliverables**
- Pending changes always populate Changed Files panel.
- Accept/Reject banner works per-file and Commit All applies atomically.

**Acceptance**
- Diff appears for edits in typical workflows.
- History persists across workspace reopen (when enabled).
- No cross-session leakage of workspace state.

## Phase D - Performance & Stability
Status: done
**Scope**
- Prompt length management (IDEContext/history/memory trimming).
- Tool call budget/loop control (soft nudges, cached results).
- Fast-model fallback and error recovery behavior.

**Deliverables**
- Reduced redundant tool calls.
- Consistent latency for simple tasks.

**Acceptance**
- No repeated read loops on simple requests.
- End-to-end response time improves measurably.

## Phase E - Verification & Regression
Status: pending
**Scope**
- Minimal end-to-end test scripts or manual checklist.
- Full build/test runs for regressions.

**Deliverables**
- Verified key flows (read/edit/diff/confirm/history).
- Build/tests pass.

**Acceptance**
- `:idea-plugin:build` or `test` completes without regressions.
- All critical UI workflows validated.
