---
name: git-workflow
description: Standardized Git workflow for committing, pushing, and managing branches.
---

# Git Workflow Skill

Use this skill when the user asks to perform Git operations or manage the repository.
This skill enforces a standardized workflow to ensure clean history and safe operations.

## Workflow Rules

1.  **Status Check**: Always run `git status` first to understand the current state.
2.  **Branching**:
    - Avoid working directly on `main` or `master`.
    - Create feature branches: `git checkout -b feature/your-feature-name`.
3.  **Commits**:
    - Use conventional commits format: `type(scope): description`.
    - Types: `feat`, `fix`, `docs`, `style`, `refactor`, `test`, `chore`.
    - Example: `feat(auth): implement login endpoint`.
4.  **Pushing**:
    - Push to the origin with upstream tracking: `git push -u origin feature/your-feature-name`.
5.  **Pull Requests**:
    - (If applicable) Describe how to open a PR (usually via link provided by git push).

## Common Commands

- Check status: `git status`
- View diff: `git diff`
- Add files: `git add <file>` (Avoid `git add .` unless sure)
- Commit: `git commit -m "feat: message"`
- Log: `git log --oneline -n 10`
