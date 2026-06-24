# Phase 1 Baseline

This branch establishes `rikki-cli` as a reproducible Codex CLI-derived baseline.

## Scope

- Start from a clean remote clone of `Creeper5261/Rikki`.
- Create branch `phase1/rikki-cli`.
- Replace the branch contents with a one-time snapshot of public `openai/codex`.
- Preserve Codex CLI behavior for Phase 1.
- Verify the CLI can build and run against the configured OpenAI-compatible endpoint.

## Build Verification

Environment:

- OS: Windows
- Toolchain: `x86_64-pc-windows-msvc`
- Visual Studio bootstrap: `VsDevCmd.bat -arch=x64`

Command:

```powershell
cmd.exe /c "`"C:\Program Files\Microsoft Visual Studio\2022\Community\Common7\Tools\VsDevCmd.bat`" -arch=x64 && set `"PATH=%USERPROFILE%\.cargo\bin;%PATH%`" && cd /d D:\Projects\kb\rikki-cli\codex-rs && set `"CARGO_TERM_COLOR=never`" && set `"CARGO_NET_GIT_FETCH_WITH_CLI=true`" && cargo build -p codex-cli"
```

Result:

- `cargo build -p codex-cli` completed successfully.
- `D:\Projects\kb\rikki-cli\codex-rs\target\debug\codex.exe --version` printed `codex-cli 0.0.0`.

Note:

- A WSL build attempt reached dependency fetching but failed because WSL could not access `chromium.googlesource.com` for the `libwebrtc`/`libyuv` submodule through the Windows proxy. Windows/MSVC was used for the verified build.

## Endpoint Smoke

Configuration used:

- Base URL: `https://www.bytecatcode.org/v1`
- Wire API: `responses`
- Model requested by user: `gpt5.4`
- Model exposed by endpoint: `gpt-5.4`
- Reasoning effort: `low`

The first smoke attempt with `gpt5.4` reached `https://www.bytecatcode.org/v1/responses` but the server returned `503 No available channel for model gpt5.4 under group codex`. A `/v1/models` probe showed the available model id is `gpt-5.4`.

The second smoke attempt used `gpt-5.4` and completed a Codex CLI `exec` turn with the expected model response:

```text
rikki-cli smoke ok
```

Usage reported by the CLI:

```text
input_tokens=23462
cached_input_tokens=3648
output_tokens=28
reasoning_output_tokens=12
```

The smoke command used a temporary `CODEX_HOME`, `--ephemeral`, `--ignore-user-config`, `--sandbox read-only`, and a process-local API key environment variable. The API key is not stored in this repository.
