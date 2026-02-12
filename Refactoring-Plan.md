# Code Agent 重构计划书 (参考 OpenCode 架构)

本项目目前存在严重的逻辑耦合（尤其是 `JsonReActAgent` 和 `SessionProcessor`），为了提升系统的可维护性、Prompt 的灵活性以及上下文管理的智能化，现参考 [opencode](file:///d:/plugin_dev/opencode/packages/opencode) 的架构进行彻底重构。

## 1. 预期目录树 (Java Package Structure)

重构后，代码将从原来的 `core` 臃肿结构转向功能自治的包结构：

```text
com.zzf.codeagent
├── api                      # REST 接口层 (保持与插件的兼容性)
│   ├── controller           # Web 控制器 (保留 /api/agent/chat/stream, /pending 等)
│   ├── dto                  # 契约对象 (ChatRequest, PendingChangeRequest)
│   └── websocket            # 实时事件推送 (基于 Bus)
├── bus                      # 事件总线系统 (参考 OpenCode bus/)
│   ├── AgentBus.java        # 核心总线
│   └── event                # 强类型事件定义 (Updated, Compacted, etc.)
├── agent                    # Agent 策略与角色 (参考 OpenCode agent/)
│   ├── AgentRole.java       # 角色定义
│   ├── AgentRegistry.java   # Agent 注册表
│   └── prompt               # 静态 Prompt 模板库 (.txt)
├── session                  # 会话核心 (重构重点, 参考 OpenCode session/)
│   ├── model                # 核心模型
│   │   ├── MessageV2.java   # 结构化消息 (包含 Parts 列表)
│   │   └── PromptPart.java  # Text, File, Tool, Compaction 等分量
│   ├── prompt               # Prompt 构建引擎
│   │   ├── PartResolver.java# 将引用解析为实际内容 (如文件注入)
│   │   └── PromptBuilder.java# 动态拼接 Parts
│   ├── processor            # 简化的 ReAct 循环
│   │   └── ReActLoop.java   # 仅负责迭代，不负责具体工具逻辑
│   ├── compaction           # 上下文压缩策略
│   │   ├── Pruner.java      # 智能剪枝 (移除冗余工具输出)
│   │   └── Summarizer.java  # 总结压缩
│   └── todo                 # 任务追踪 (参考 OpenCode todo.ts)
│       └── TodoManager.java
├── tool                     # 工具系统 (参考 OpenCode tool/)
│   ├── ToolRegistry.java    # 工具注册与 Schema 验证
│   ├── ToolContext.java     # 工具执行上下文
│   └── impl                 # 具体工具实现 (每个工具一个类)
│       ├── FileEditTool.java
│       ├── SearchTool.java
│       └── ShellTool.java
├── provider                 # LLM 供应商抽象 (基于 LangChain4j)
│   ├── ModelProvider.java
│   └── ModelLimit.java      # Token 限制与成本计算
├── project                  # 项目与工作区状态
│   ├── Workspace.java
│   └── ProjectState.java
├── permission               # 权限引擎 (参考 OpenCode permission/)
│   ├── PolicyEngine.java    # 基于规则的过滤
│   └── PermissionStore.java # 异步权限确认
└── util                     # 通用工具
    ├── TokenCounter.java    # 字节/Token 估算
    └── JsonUtils.java
```

## 2. 核心改进点说明

### A. Prompt 分层拼接 (Parts System)
- **现状**: 使用 `StringBuilder` 拼接大字符串，难以管理。
- **预期**: 引入 `MessageV2` 和 `PromptPart`。Prompt 不再是一段文本，而是一个 `List<PromptPart>`。
  - `TextPart`: 基础指令。
  - `FilePart`: 自动读取文件内容并带上行号，支持智能截断。
  - `ToolPart`: 工具调用的历史记录。
  - `CompactionPart`: 之前的对话摘要。

### B. 上下文剪枝与压缩 (Compaction)
- **现状**: 简单按照行数或字符数截断。
- **预期**: 
  - **Pruning**: 自动识别并删除过时的、体积巨大的工具输出（如 `ls` 或 `grep` 的中间结果），保留关键的 `thought`。
  - **Compaction**: 当 Token 接近模型上限时，触发一次“总结”，将历史转化为一个 `CompactionPart`，清空原始历史。

### C. 事件驱动的 ReAct 循环
- **现状**: `SessionProcessor` 硬编码了所有步骤。
- **预期**: `ReActLoop` 只负责向 LLM 发送请求并解析输出。解析出的每一个 `Part` 都会发布到 `Bus`。
  - `ToolHandler` 监听 `ToolPart` 事件并执行。
  - `UI` 监听所有事件进行实时渲染。
  - 权限检查通过 `Bus` 挂起和恢复。

### D. TODO 作为第一类公民
- **现状**: Agent 很难维持长期目标。
- **预期**: 显式的 `TodoManager`。Agent 可以调用 `UPDATE_TODO` 工具，系统会在每个 Turn 的 Prompt 中自动注入当前的任务进度。

## 3. 插件衔接方案 (Plugin Compatibility)

为了确保重构后现有插件 [idea-plugin](file:///d:/plugin_dev/code-agent/idea-plugin) 能够无缝运行，必须维持以下契约：

1. **SSE 事件流**: 
   - 必须保留 `message_part` (用于流式文本和 Reasoning)。
   - 必须保留 `agent_step` (用于显示思考过程和文件变更卡片)。
   - 必须保留 `tool_call` (用于 UI  eyeglasses 图标展示)。
2. **Pending Change 流程**:
   - 保留 `/api/agent/pending` 接口，用于接收插件侧的 Accept/Reject 信号。
   - **关键约束**: 插件 [build.gradle.kts](file:///d:/plugin_dev/code-agent/idea-plugin/build.gradle.kts) 直接依赖了核心项目的 `PendingChangesManager` 类。重构时需保持该类的包路径 (`com.zzf.codeagent.core.tool`) 和结构，或同步更新插件源码。
3. **IDE 上下文**:
   - 接口应继续接收 `ideContextContent`，并由 `PartResolver` 解析为 `IDEContextPart` 注入到 Prompt 中。

## 4. 重构路线图

1. **第一阶段 (基础隔离)**:
   - 建立 `bus` 包，迁移现有的事件逻辑。
   - 引入 `MessageV2` 模型，开始尝试将输出结构化。
   - 实现 `ToolRegistry`，将 `JsonReActAgent` 中的工具调用逻辑剥离。

2. **第二阶段 (Session 升级)**:
   - 实现 `PartResolver`，支持 Prompt 中的动态文件引用。
   - 迁移 `compaction` 逻辑，实现基于 Token 消耗的自动剪枝。

3. **第三阶段 (Agent 解耦)**:
   - 彻底重写 `ReActLoop`，废弃 `JsonReActAgent` 中的臃肿循环。
   - 将 Prompts 迁移到资源文件，通过 `AgentRole` 动态加载。

---
*注：本计划仅作为架构设计参考，实际代码修改将分步进行以确保系统稳定性。*
