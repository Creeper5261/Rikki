# Phase 0 Baseline and Observability

Purpose:
- Fix a minimal reproducible case so failures are traceable.
- Emit structured metrics for every run.

## Minimal repro
1) Create a small workspace with only `src/Main.java`.
2) Start backend: `./gradlew.bat bootRun`.
3) Send one request:
   - "Write a simple Java example program."
4) Check logs for `agent.run.metrics`.

## Metrics to watch
- `toolBudgetExceeded` (count)
- `repeatedListFiles` (count)
- `repeatedReadFiles` (count)
- `toolErrors` (count)
- `listFilesCalls` / `readFileCalls`

## Phase 0 acceptance
- toolBudgetExceeded = 0
- repeatedListFiles = 0 (root list only once)
- repeatedReadFiles = 0
- toolErrors <= 1

## Quick diagnosis
- toolBudgetExceeded > 0: tool budget is blocking later tool calls.
- repeatedListFiles > 0: repo map / list de-dup is not working.
- repeatedReadFiles > 0: read de-dup or summary pollution.
- toolErrors increasing: bad tool args or file system mapping.
