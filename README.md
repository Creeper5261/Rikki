# Rikki Code Agent

A lightweight AI coding assistant plugin for JetBrains IDEs. Connects directly to LLM APIs — no backend server required.

## Features

**Chat Agent**

An AI panel embedded in the IDE sidebar. The agent can read files, write and edit code, run terminal commands, and perform Git operations. Commands classified as high-risk (file deletion, force-push, etc.) are always paused for explicit **Approve / Skip** confirmation before execution.

**Inline TAB Completion**

Ghost-text suggestions appear as you type; press TAB to accept.

- **FIM mode** (Fill-In-Middle) for DeepSeek and Ollama — sends prefix and suffix to the model's dedicated completions endpoint for accurate in-place suggestions.
- **Chat-format fallback** for OpenAI, Gemini, Moonshot, and other providers.
- Completion can use a **different provider, model, and API key** than the chat agent — suitable for pairing a fast/cheap model for completions with a powerful model for chat.

**Multi-Provider Support**

Built-in presets for DeepSeek, OpenAI, Google Gemini, Moonshot / Kimi, Ollama (local, no key required), and fully custom endpoints. Each provider stores its own API key independently.

## Requirements

- IntelliJ IDEA 2024.1 or later
- An API key from a supported LLM provider, or a locally running Ollama instance

## Setup

1. Download `idea-plugin-0.1.0.zip` from the [Releases](https://github.com/Creeper5261/Rikki/releases) page.
2. In IntelliJ IDEA, go to **Settings → Plugins → ⚙ → Install Plugin from Disk**, and select the downloaded file.
3. Open **Settings → Tools → Rikki Code Agent**.
4. Select a provider, enter the API key, and choose a model.
5. For local use, select **Ollama** — no API key is required.

## License

[MIT](LICENSE)
