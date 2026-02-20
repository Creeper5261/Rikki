# AGENTS.md

## Scope
This file applies to the `code-agent` repository root and all subdirectories.

## Project Overview
- Backend: Spring Boot service (`src/main/java/com/zzf/codeagent`), default port `18080`.
- IDEA plugin: IntelliJ plugin module (`idea-plugin`), UI and chat interaction live in `idea-plugin/src/main/java/com/zzf/codeagent/idea`.
- Build system: Gradle Kotlin DSL (`build.gradle.kts`, `settings.gradle.kts`).
- Java version: `17` for both backend and plugin.

## Key Modules
- `controller`: HTTP/SSE endpoints, including `/api/agent/chat/stream` and stop/pending related APIs.
- `session`: core session loop, tool-call orchestration, streaming event pipeline.
- `core/tool`: tool implementations (`bash`, `read`, `write`, `edit`, `delete`, ide_* tools, etc.).
- `idea-plugin/.../ChatPanel.java`: primary chat UI rendering, activity cards, diff rows, scrolling behavior.
- `idea-plugin/.../DiffService.java`: diff visualization and apply/revert helpers.

## Local Run & Validation
- Compile backend + plugin:
  - `./gradlew.bat :compileJava :idea-plugin:compileJava --rerun-tasks`
- Run backend:
  - `./gradlew.bat bootRun`
- One-command boot with log file:
  - `./run-bootrun.ps1`
- Plugin packaging/build:
  - `./gradlew.bat :idea-plugin:build`

## Configuration
- Main config: `src/main/resources/application.yml`.
- Dev override: `src/main/resources/application-dev.yml`.
- Sensitive values must come from env vars (for example `DEEPSEEK_API_KEY`), never hardcoded.

## Engineering Rules
- Prefer minimal, focused changes; avoid broad refactors unless explicitly requested.
- Keep behavior consistent across backend events and plugin rendering (SSE field names must stay compatible).
- For tool-related UI changes, verify both:
  - runtime behavior (tool event ordering/state transitions), and
  - rendered output (activity row, diff stat, details panel).
- Any change touching session/tool pipeline should include at least compile validation.

## UI-Specific Rules (idea-plugin)
- Do not break streaming order:
  - assistant text, tool activity, and final answer must remain causally ordered.
- Avoid forced auto-scroll when user manually scrolls up.
- Markdown content must not expand horizontal layout permanently.
- Diff stats should reflect real content changes; if metadata is incomplete, use safe fallback/hydration.

## Safety
- Destructive operations (delete/move/high-risk shell actions) require explicit approval flow.
- Do not bypass scope checks (`workspaceRoot`, `sessionId`) in pending-change/pending-command paths.
- Never add logs that leak secrets by default.

## Debugging Checklist
When user reports "frontend shows nothing" or "tool stats wrong":
1. Check backend log for SSE event emission order (`tool_call`, `tool_result`, `finish`, `status`).
2. Check plugin side parsing/extraction (`ChatToolMetaExtractor`, `ChatPanel#handleToolEvent`).
3. Confirm `pending_change` payload contains usable `old/new` content, else fallback from args/file.
4. Rebuild plugin module and retest with a fresh IDE session.

## Delivery Expectations
- Always report:
  - files changed,
  - what behavior changed,
  - compile/test command executed and result.
- If something cannot be validated locally, state it explicitly.
