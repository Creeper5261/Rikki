---
name: log-analyzer
description: Analyze Java stack traces, identify project-specific errors, and suggest fixes.
---

# Log Analyzer Skill

Use this skill when the user provides a Java stack trace or error log.
Your goal is to pinpoint the exact line of code causing the issue and explain why.

## Analysis Strategy

1.  **Identify the Exception**: Look for the root cause (e.g., `Caused by: java.lang.NullPointerException`).
2.  **Filter Stack Trace**:
    -   Ignore framework lines (`org.springframework.*`, `org.apache.*`, `java.*`).
    -   Focus on lines starting with the project's package name (e.g., `com.example.*`).
    -   Find the **top-most** project-specific frame.
3.  **Locate Code**:
    -   Extract the file name and line number (e.g., `UserService.java:42`).
    -   Use `SEARCH_CODEBASE` or `Glob` to find the full path of the file.
    -   Use `READ_FILE` to examine the code around that line.

## Troubleshooting Common Errors

### NullPointerException (NPE)
-   Check for objects invoked without null checks.
-   Check for `@Autowired` fields that might be null (if injection failed).
-   **Fix**: Add `if (obj != null)` or use `Optional`.

### Database Errors (SQL/JPA)
-   Look for `SQLSyntaxErrorException` or `ConstraintViolationException`.
-   Check Entity annotations (`@Table`, `@Column`).
-   Check repository method names.

### BeanCreationException
-   Usually a dependency injection failure.
-   Check for missing `@Service` / `@Component` annotations.
-   Check for circular dependencies.

## Output Format
1.  **Root Cause**: What happened?
2.  **Location**: File and line number.
3.  **Code Context**: Show the snippet.
4.  **Fix**: Proposed code change.
