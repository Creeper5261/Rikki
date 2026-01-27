# Evaluation Suite

This repo does not include a live LLM in unit tests. Use the steps below to validate agent behavior across key dimensions.

## Quick Run (Automated)

Start the server, then run:

```powershell
.\scripts\eval\run_eval.ps1 -ServerUrl http://localhost:8080 -WorkspaceRoot <path-to-workspace>
```

Output includes PASS/FAIL/MANUAL with a non-zero exit code on FAIL.

## Scenario 1 - Skill Auto-Load (Log Analyzer)
- Input: Provide a Java stack trace with a "Caused by" section.
- Expected:
  - meta.auto_skills contains `log-analyzer`
  - final answer references root cause location

## Scenario 2 - Skill Auto-Load (Gradle)
- Input: "Gradle dependency conflict in build.gradle.kts"
- Expected:
  - meta.auto_skills contains `gradle-expert`
  - output suggests Kotlin DSL fixes

## Scenario 3 - Tool Budget
- Input: Task that triggers many tool calls (e.g., ask for full repo analysis without constraints)
- Expected:
  - tool_calls hits MAX_TOOL_CALLS
  - answer is forced final (no more tool calls)

## Scenario 4 - Session Isolation
- Start two sessions in parallel, make a pending change in one
- Expected:
  - pendingChanges only shows changes in its own session

## Scenario 5 - RUN_COMMAND Isolation
- Run a command with cwd outside session root
- Expected:
  - If RUN_COMMAND disabled: `tool_disabled`
  - If RUN_COMMAND enabled: `cwd_outside_session_root`

## Notes
- Automated cases live in `scripts/eval/eval_cases.json`.
- Manual cases are tracked in the same file and will show as MANUAL.
- Record results in a test report for regression tracking.
