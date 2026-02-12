# Code Agent 重构计划与目录树

## 1. 重构目标
将 `code-agent` 彻底重构为与 `openCode` 架构对齐。实现 ReAct Loop、Prompt 分层拼接、上下文剪枝、Todo 系统等核心功能，同时保持与 IntelliJ 插件侧的 SSE 兼容性。

## 2. 预期目录树 (与 OpenCode 对齐)

```text
src/main/java/com/zzf/codeagent/
├── agent/                  # Agent 定义与管理 (src/agent)
│   ├── AgentInfo.java      # Agent 配置模型
│   └── AgentService.java   # Agent 加载与发现
├── bus/                    # 全局事件总线 (src/bus)
│   ├── AgentBus.java       # 事件发布订阅中心
│   └── BusEvent.java       # 事件定义
├── config/                 # 配置管理 (src/config)
│   ├── ConfigInfo.java     # 配置模型
│   └── ConfigManager.java  # 配置文件加载
├── controller/             # 接口层
│   └── AgentChatController.java # SSE 适配接口
├── core/                   # 核心公共组件
│   ├── event/              # 事件模型
│   │   └── EventStream.java # 事件流处理
│   ├── tool/               # 基础工具框架
│   │   ├── PendingChangesManager.java # 变更管理
│   │   └── ToolRegistry.java # 工具注册
│   └── tools/              # 基础系统工具
│       └── FileSystemToolService.java # 文件系统工具
├── id/                     # 标识符生成 (src/id)
│   └── Identifier.java     # ULID/ID 生成
├── llm/                    # LLM 核心调用 (src/session/llm.ts)
│   └── LLMService.java     # 串流输出与消息转换
├── project/                # 项目上下文 (src/project)
│   └── ProjectContext.java # Git/工作目录识别
├── provider/               # 模型供应商管理 (src/provider)
│   ├── ModelInfo.java      # 模型信息
│   ├── ProviderManager.java# 供应商管理
│   └── ProviderTransform.java # 模型参数/消息归一化
├── session/                # 会话与 Loop 核心 (src/session)
│   ├── model/              # 消息模型 (src/session/message-v2.ts)
│   │   ├── MessageV2.java  # 结构化消息
│   │   ├── PromptPart.java # 消息分量
│   │   └── TodoInfo.java   # Todo 模型
│   ├── ContextCompactionService.java # 上下文剪枝 (src/session/compaction.ts)
│   ├── InstructionPrompt.java # 指令解析
│   ├── PromptReminderService.java # 提示词注入
│   ├── PromptResolver.java    # 提示词解析 (@引用)
│   ├── SessionInfo.java    # 会话元数据
│   ├── SessionLoop.java    # ReAct Loop 核心
│   ├── SessionProcessor.java # 串流处理器
│   ├── SessionProcessorFactory.java # 处理器工厂
│   ├── SessionRetry.java   # 重试策略
│   ├── SessionService.java # 会话存储与管理
│   ├── SessionStatus.java  # 会话状态管理
│   ├── SessionSummaryService.java # 摘要与 Diff
│   ├── SystemPrompt.java   # 系统提示词生成
│   └── TodoManager.java    # Todo 管理服务
├── shell/                  # 终端执行 (src/shell)
│   └── ShellService.java   # 跨平台命令执行
├── tool/                   # 工具系统 (src/tool)
│   ├── impl/               # 具体工具实现
│   ├── BashTool.java       # 终端执行工具
│   ├── TodoReadTool.java   # Todo 读取
│   ├── TodoWriteTool.java  # Todo 写入
│   ├── Tool.java           # 工具接口
│   ├── ToolContext.java    # 执行上下文
│   └── ToolRegistry.java   # 工具注册中心
└── util/                   # 工具类
    └── FilesystemUtil.java # 文件操作封装
```

## 3. 已完成重构细项 [x]

- [x] **核心总线 (Bus)**: 实现 `AgentBus`，支持全局事件分发。
- [x] **消息体系 (MessageV2)**: 建立 `MessageV2` 与 `PromptPart` 的结构化体系，支持 `StepStart`, `StepFinish`, `Reasoning` 等分量。
- [x] **ReAct Loop (SessionLoop)**: 实现与 OpenCode 一致的 `loop` 逻辑，支持 `Subtask` 和 `Compaction` 调度。
- [x] **串流处理 (SessionProcessor)**: 实现 LLM 输出实时解析、工具调用触发、死循环检测。
- [x] **模型适配 (ProviderTransform)**: 对齐 OpenCode 的模型参数转换逻辑，支持 `reasoning`, `thinking` 等高级参数。
- [x] **状态管理 (SessionStatus)**: 实现 `idle`, `busy`, `retry` 状态机与事件通知。
- [x] **指令解析 (InstructionPrompt)**: 支持 `.txt` 模板加载与项目变量注入。
- [x] **提示词解析 (PromptResolver)**: 实现 `@文件` 和 `@Agent` 的分层引用解析。
- [x] **上下文剪枝 (ContextCompaction)**: 实现 Token 溢出检测、工具输出裁剪 (Prune) 与摘要压缩 (Summary)。
- [x] **Todo 系统**: 实现会话级别的 Todo 追踪、`TodoWriteTool` 和 `TodoReadTool` 及其 UI 同步。
- [x] **插件兼容 (SSE)**: `AgentChatController` 已适配插件侧所需的 `message_part`, `status`, `error` 等事件流，并修复了 `PendingChange` 的兼容性问题。
- [x] **重试逻辑 (SessionRetry)**: 实现指数退避重试，对齐 OpenCode 逻辑。
- [x] **摘要服务 (SessionSummaryService)**: 实现消息标题生成与基础 Diff 统计架构。

## 4. 后续待办 [ ]

- [x] **工具系统 (Tool System)**: 统一 Tool 接口与 Context，实现与 opencode 对齐的权限检查、元数据更新机制以及 LSP 诊断集成。
- [x] **Snapshot 系统**: 实现 Snapshot 追踪与 cleanup 定时任务 (对齐 opencode/src/snapshot 的 Scheduler 逻辑)。
- [x] **存储系统 (Storage)**: 实现持久化存储系统的 Migration 逻辑 (对齐 opencode/src/storage 的 MIGRATIONS 逻辑)。
- [x] **工具集迁移**: 已迁移 BashTool, EditTool, TaskTool (子任务支持), ReadTool, GlobTool, GrepTool, ListTool, WebSearchTool, CodeSearchTool。
- [x] **异常与超时机制**: 完善工具系统的异常处理与 60s 超时机制。

## 5. 持续校验测试项 (始终保留)

为了确保重构后的代码在 Java 侧和 IntelliJ 插件侧全链路打通，每次重大更改后需依次执行以下校验：

- [x] **核心编译检查**: 执行 `.\gradlew.bat clean compileJava` 确保 code-agent 服务端无语法错误。
- [x] **插件兼容性检查**: 执行 `.\gradlew.bat idea-plugin:compileJava` 确保插件侧引用的 API 与重构后的服务端模型一致。
- [x] **全量构建校验**: 执行 `.\gradlew.bat build -x test` 验证项目整体构建链路。
- [ ] **单元测试验证**: 针对 `SessionLoop`, `Processor`, `Compaction` 等核心逻辑编写并运行 JUnit 测试。
- [ ] **SSE 链路实测**: 启动服务端后，通过插件侧 `ChatPanel` 发起对话，验证 `message_part` 和 `status` 事件流正常。
- [x] **build 校验**: 执行 `.\gradlew.bat build` 确保所有模块编译通过，无警告或错误。
