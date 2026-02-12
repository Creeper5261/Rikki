# Code Agent 全量重构清单 (OpenCode 对齐版)

本清单用于追踪项目从“屎山”向 OpenCode 架构演进的进度。所有逻辑、Prompt 和结构将严格对齐 [opencode/src](file:///d:/plugin_dev/opencode/packages/opencode/src)。

## 1. 核心基础设施 [x]
- [x] **Bus 系统**: 实现 Java 版 `AgentBus`，支持事件订阅与发布。
- [x] **Provider 抽象**: 对齐 OpenCode 的 `provider/`，处理模型调用与 Token 计数。
- [x] **Config 管理**: 对齐 OpenCode 的配置读取逻辑。

## 2. 消息与 Prompt 体系 [x]
- [x] **MessageV2 模型**: 实现 `MessageV2` 及其子类（`Assistant`, `User`, `Tool`, `System`）。
- [x] **Parts 体系**:
    - [x] `TextPart`
    - [x] `FilePart` (支持文件内容自动注入)
    - [x] `ToolPart` (工具调用与结果)
    - [x] `ReasoningPart` (思考过程)
    - [x] `AgentPart` (Agent 引用)
- [x] **Prompt Resolver**: 实现对齐 `src/session/prompt.ts` 的 `resolvePromptParts` 逻辑。
- [x] **模板复用**: 复制 OpenCode 的 `.txt` / `.md` 模板到 `resources/prompts`。

## 3. 会话与循环 (Session & Loop) [x]
- [x] **Session Service**: 实现 `SessionService` (对齐 `src/session/session.ts`)。
- [x] **Agent Service**: 实现 `AgentService` (对齐 `src/agent/agent.ts`)。
- [x] **ReAct Loop**: 照搬 `src/session/prompt.ts` 中的 `loop` 异步迭代逻辑 (`SessionLoop`)。
- [x] **Context Compaction**:
    - [x] `isOverflow` Token 溢出检测。
    - [x] `prune` 冗余工具输出剪枝。
    - [ ] `compaction` 自动总结历史 (Todo: 需进一步完善 Prompt 调用)。
- [x] **Instruction Resolver**: 对齐 `src/session/instruction.ts` (Done)。

## 4. 工具与任务系统 [x]
- [x] **Tool 框架**: 对齐 `src/tool/` 的注册、执行与 Schema 校验 (`ToolRegistry`, `Tool`, `ToolContext`)。
- [x] **Todo 系统**: 实现 `TodoManager`，对齐 `src/session/todo.ts`。
- [x] **Shell Tool**: 实现 `ShellService` 与 `BashTool`。
- [x] **Todo Tools**: 实现 `TodoWriteTool` 与 `TodoReadTool`。
- [ ] **Subtask 体系**: 支持 Agent 派生子任务 (Todo)。

## 5. 插件衔接与 API [x]
- [x] **SSE Controller**: 重写 `AgentChatController`，输出对齐 OpenCode 的事件流 (`message`, `tool_call`, `step`)。
- [x] **Pending Manager**: 适配现有的插件侧 `PendingChangesManager` 契约 (通过 Tool 交互)。

---
## 重构日志
- 2026-02-08: 初始化重构清单，确定 OpenCode 对齐策略。
- 2026-02-08: 完成核心基础设施、消息模型、Prompt 迁移。
- 2026-02-08: 完成 Session/Agent 服务、ReAct Loop、SSE Controller、Todo 系统。
