# Code-Agent SOTA Gap Closure Plan

## Stage 1 - Skills Reliability (P0)
### Goals
- Make skills reliably effective without relying on the model to remember `LOAD_SKILL`.
- Ensure skill scope/source is accurate and observable.

### Tasks
- Fix skill system prompt to reflect actual sources (classpath + workspace skills).
- Add auto skill selection based on user goal/history keywords and explicit skill mentions.
- Auto-inject selected skill content into the prompt with size limits.
- Add minimal tests to verify skill selection logic.

### Acceptance Criteria
- If user goal contains stack trace / error log keywords, `log-analyzer` is auto-loaded in the prompt.
- If user goal contains Gradle/dependency keywords, `gradle-expert` is auto-loaded in the prompt.
- If user goal contains JUnit/Mockito/unit-test keywords, `java-test-gen` is auto-loaded in the prompt.
- Auto-loading is capped (e.g., top 1-2 skills) and does not exceed prompt budget.
- Unit tests validate skill selection for at least 3 common intents.

## Stage 2 - Full-Chain Isolation & State Consistency (P1)
### Goals
- Ensure all execution paths (file ops + shell commands) are isolated to the session workspace.
- Eliminate cross-session state contamination.

### Tasks
- Route RUN_COMMAND cwd to session workspace root by default.
- Move workspace_state to session scope (per session root or per session file).
- Enforce path checks to block operations outside session root.
- Align apply/reject flows to session-based diffs only.

### Acceptance Criteria
- RUN_COMMAND executes only within session workspace (verified by tests/logs).
- Pending changes/state from one session never appears in another session.
- Applying or rejecting changes never touches files outside session workspace.

## Stage 3 - Budgets, Metrics, and Evaluation (P2)
### Goals
- Improve reliability by enforcing tool budgets and tracking task quality.
- Enable repeatable evaluation against a baseline.

### Tasks
- Add hard tool-call budgets and auto-finalization on overrun.
- Record task metrics: tool count, error count, edits applied/rejected, latency.
- Define a small evaluation suite (fixed repo + expected diff + expected answer).

### Acceptance Criteria
- Tool-call hard limit is enforced with a clear final response.
- Metrics are emitted per task in logs or JSON response meta.
- Evaluation suite can run and report pass/fail.
