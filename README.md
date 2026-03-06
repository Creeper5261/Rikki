# Rikki Coding Agent

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

- JetBrains IDE based on platform build `241+`

<details>
<summary>Supported IDE versions</summary>

- Android Studio — Koala \| 2024.1.1+
- AppCode — build 241.0+
- Aqua — build 241.0+
- CLion — 2024.1+
- Code With Me Guest — 1.0+
- DataGrip — 2024.1+
- DataSpell — 2024.1+
- GoLand — 2024.1+
- IntelliJ IDEA — 2024.1+
- IntelliJ IDEA Community — 2024.1+
- JetBrains Client — 1.0+
- JetBrains Gateway — 2024.1+
- MPS — 2024.1+
- PhpStorm — 2024.1+
- PyCharm — 2024.1+
- PyCharm Community — 2024.1+
- Rider — 2024.1+
- RubyMine — 2024.1+
- RustRover — 2024.1+
- WebStorm — 2024.1+
- Writerside — build 241.0+

</details>

- An API key from a supported LLM provider, or a locally running Ollama instance


## Setup

You can install Rikki either directly from the JetBrains Marketplace (recommended) or manually from a downloaded ZIP file.

### Option 1: Install from [JETBRAINS Marketplace](https://plugins.jetbrains.com/plugin/30315-rikki-coding-agent) (Recommended)

1. Open IntelliJ IDEA.
<!-- 1. Open your JetBrains IDE. -->

<!-- <details>
<summary>Supported IDE versions</summary>

- Android Studio — Koala \| 2024.1.1+
- AppCode — build 241.0+
- Aqua — build 241.0+
- CLion — 2024.1+
- Code With Me Guest — 1.0+
- DataGrip — 2024.1+
- DataSpell — 2024.1+
- GoLand — 2024.1+
- IntelliJ IDEA — 2024.1+
- IntelliJ IDEA Community — 2024.1+
- JetBrains Client — 1.0+
- JetBrains Gateway — 2024.1+
- MPS — 2024.1+
- PhpStorm — 2024.1+
- PyCharm — 2024.1+
- PyCharm Community — 2024.1+
- Rider — 2024.1+
- RubyMine — 2024.1+
- RustRover — 2024.1+
- WebStorm — 2024.1+
- Writerside — build 241.0+

</details> -->
2. Go to Settings → Plugins → Marketplace.

3. Search for **Rikki Coding Agent**.

4. Click Install and restart the IDE if prompted.

### Option 2: Install from Disk (Manual Installation)

1. Download `idea-plugin-0.1.1.zip` from the [Releases](https://github.com/Creeper5261/Rikki/releases) or [JETBRAINS Marketplace](https://plugins.jetbrains.com/plugin/30315-rikki-code-agent) .
2. In IntelliJ IDEA, go to **Settings → Plugins → ⚙ → Install Plugin from Disk**, and select the downloaded file.

## Configuration
After installation:
1. Open **Settings → Tools → Rikki Coding Agent**.
2. Select a provider, enter the API key, and choose a model.
3. For local use, select **Ollama** — no API key is required.

## License

[MIT](LICENSE)








