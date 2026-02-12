# Agent Operating Guide (code-agent)

This file summarizes how to work in this repo. It is meant for agentic coding tools.

## Repository snapshot
- Language: Java 17 (Spring Boot)
- Build system: Gradle (Kotlin DSL)
- Modules: root app + :idea-plugin
- Tests: JUnit 5 + Mockito

## Build, lint, test
Use the Gradle wrapper from repo root.

Build
- `./gradlew build`
- `./gradlew :idea-plugin:build`

Run
- `./gradlew bootRun`
- `./gradlew :idea-plugin:runIde`

Test (all)
- `./gradlew test`
- `./gradlew :idea-plugin:test`

Test (single)
- Class: `./gradlew test --tests "com.zzf.codeagent.core.rag.search.CodeTokenizerTest"`
- Method: `./gradlew test --tests "com.zzf.codeagent.core.rag.search.CodeTokenizerTest.testTokenize"`
- By package: `./gradlew test --tests "com.zzf.codeagent.core.rag.search.*"`

Coverage
- `./gradlew jacocoTestReport`
- `./gradlew jacocoTestCoverageVerification`

Dependency checks
- `./gradlew dependencyCheckAnalyze`

Notes
- `check` depends on jacoco coverage verification.
- No formatter or lint task is configured; follow existing code style.

## Config and environment
Key settings are in `src/main/resources/application.yml`.

Common env vars (non-exhaustive)
- `DEEPSEEK_API_KEY`, `DEEPSEEK_BASE_URL`, `DEEPSEEK_MODEL_NAME`
- `EMBEDDING_API_KEY`, `EMBEDDING_API_URL`, `EMBEDDING_API_MODEL`
- `ES_HOST`, `ES_PORT`, `ES_SCHEME`
- `KAFKA_BOOTSTRAP_SERVERS`
- `CODEAGENT_RUNTIME_TYPE`, `CODEAGENT_RUNTIME_DOCKER_IMAGE`, `CODEAGENT_RUNTIME_TIMEOUT_MS`

Runtime note
- App guards against IDE `out/production` classpaths; prefer Gradle runs.

## Agent runtime (Processor-first)
- Primary execution path is `SessionProcessor` (processor-first loop) instead of calling `JsonReActAgent.run` directly.
- `JsonReActAgent.run` remains for compatibility, but new code should route through `SessionProcessor`.
- Tool outputs do not enter prompts; all tool events stream as `message_part` entries.
- Tool policy is enforced at runtime (doom-loop, budget, allow/deny); prompt stays minimal.
- Tool schema validation is enforced before execution using ToolSpec input schema.

Config (AgentConfig, prefix `codeagent.agent`)
- `toolAllowList`: allowlist of tool names (optional, empty = allow all)
- `toolDenyList`: denylist of tool names

## Code style guidelines
Follow existing patterns in the file you edit. Use the closest file as reference.

General
- Java 17, no Lombok usage.
- Prefer `final` classes where inheritance is not intended.
- Use constructor injection for Spring services/controllers.
- Keep public API methods small and focused; push helpers to private methods.
- Use explicit types; no `var` is used in this codebase.

Formatting
- 4-space indentation.
- One class per file.
- Keep lines reasonably short; wrap long argument lists on new lines.
- Blank line between class members when it improves readability.

Imports
- No wildcard imports.
- Keep imports grouped and ordered as in the current file.
- When adding imports, align with local ordering (often third-party, then java, then static).
- Static imports are grouped together in tests.

Naming
- Packages are all-lowercase (`com.zzf.codeagent...`).
- Classes and enums use `UpperCamelCase`.
- Methods and variables use `lowerCamelCase`.
- Constants use `UPPER_SNAKE_CASE`.
- Keep log event keys short and stable (e.g., `chat.recv`, `tool.fail`).

Types and null handling
- Prefer primitives where null is not meaningful.
- For nullable inputs, guard with `null` checks and `trim()` as seen in services.
- Avoid returning `null`; use empty strings/lists/maps when appropriate.

Error handling
- Use `CustomException` for domain-specific failures and map them via `GlobalExceptionHandler`.
- For unexpected errors, log with class + message and return a generic error response.
- Preserve error codes and keep messages user-safe (mask secrets in logs).

Logging
- Use SLF4J (`LoggerFactory.getLogger`).
- Log structured key/value pairs (`traceId`, `route`, `intent`, etc.).
- Mask sensitive values before logging (see `maskSensitive` patterns).

Spring conventions
- Controllers are `@RestController` with explicit request mapping.
- Services are `@Service`, typically `final`.
- Use `ResponseEntity` for explicit status + headers.

Collections and streams
- Prefer `List`, `Map`, `Set` interfaces in signatures.
- Use streams only when they improve clarity; loops are common in this repo.

Concurrency
- When using `ExecutorService`, ensure tasks clean up MDC/session state in `finally`.

Tests
- JUnit 5 (`org.junit.jupiter.api.Test`).
- Mockito for dependencies (`mock`, `when`).
- Keep tests focused on one behavior and assert status + payload.

## Cursor / Copilot rules
- No `.cursor/rules`, `.cursorrules`, or `.github/copilot-instructions.md` found.
