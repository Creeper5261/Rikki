# Code-Agent 代码对齐进度追踪 (全量)

本文档记录了 `d:\plugin_dev\code-agent\src\main` 目录下所有文件与 `opencode` 项目的对齐进度。

## **目录进度概览**
- [ ] `java/com/zzf/codeagent/`
- [ ] `java/com/zzf/codeagent/agent/`
- [ ] `java/com/zzf/codeagent/bus/`
- [ ] `java/com/zzf/codeagent/config/`
- [ ] `java/com/zzf/codeagent/controller/`
- [ ] `java/com/zzf/codeagent/core/event/`
- [ ] `java/com/zzf/codeagent/core/tool/`
- [ ] `java/com/zzf/codeagent/core/tools/`
- [ ] `java/com/zzf/codeagent/id/`
- [ ] `java/com/zzf/codeagent/llm/`
- [ ] `java/com/zzf/codeagent/project/`
- [ ] `java/com/zzf/codeagent/provider/`
- [ ] `java/com/zzf/codeagent/session/`
- [ ] `java/com/zzf/codeagent/session/model/`
- [ ] `java/com/zzf/codeagent/shell/`
- [ ] `java/com/zzf/codeagent/snapshot/`
- [ ] `java/com/zzf/codeagent/storage/`
- [ ] `java/com/zzf/codeagent/util/`
- [ ] `resources/`
- [ ] `resources/prompts/agent/`
- [ ] `resources/prompts/opencode/`
- [ ] `resources/prompts/session/`
- [ ] `resources/prompts/tool/`

---

## **详细文件进度**

### **1. 根目录 (`java/com/zzf/codeagent/`)**
- [ ] `CodeAgentApplication.java`

### **2. 智能体模块 (java/com/zzf/codeagent/agent/)**
- [x] `AgentInfo.java` -> `opencode/src/agent/agent.ts`
- [x] `AgentService.java` -> `opencode/src/agent/agent.ts`

### **10. 事件总线模块 (java/com/zzf/codeagent/bus/)
- [x] `AgentBus.java` -> `opencode/src/bus/index.ts`
- [x] `SessionEvent.java` (不存在，对应 `opencode/src/session/index.ts` 中的 `Session.Event`)


### **3. 配置模块 (java/com/zzf/codeagent/config/)**
- [x] `ConfigInfo.java` -> `opencode/src/config/config.ts`
- [x] `ConfigManager.java` -> `opencode/src/config/config.ts`

### **5. 控制器模块 (`java/com/zzf/codeagent/controller/`)**
- [ ] `AgentChatController.java`

### **6. 核心事件模块 (`java/com/zzf/codeagent/core/event/`)**
- [ ] `EventStream.java`

### **7. 工具核心接口与实现 (`java/com/zzf/codeagent/core/tool/`)**
- [x] `Context.java` -> `opencode/src/tool/tool.ts` (Context interface)
- [x] `Tool.java` -> `opencode/src/tool/tool.ts` (Tool interface)
- [x] `BashTool.java` -> `opencode/src/tool/bash.ts`
- [x] `CodeSearchTool.java` -> `opencode/src/tool/codesearch.ts`
- [x] `EditTool.java` -> `opencode/src/tool/edit.ts`
- [x] `GlobTool.java` -> `opencode/src/tool/glob.ts`
- [x] `GrepTool.java` -> `opencode/src/tool/grep.ts`
- [x] `ListTool.java` -> `opencode/src/tool/ls.ts`
- [x] `PendingChangesManager.java` -> N/A (IDE specific)
- [x] `ReadTool.java` -> `opencode/src/tool/read.ts`
- [x] `TaskTool.java` -> `opencode/src/tool/task.ts`
- [x] `ToolRegistry.java` -> `opencode/src/tool/registry.ts`
- [x] `WebSearchTool.java` -> `opencode/src/tool/websearch.ts`

### **8. 其他工具服务 (`java/com/zzf/codeagent/core/tools/`)**
- [ ] `FileSystemToolService.java`

### **9. 标识符模块 (java/com/zzf/codeagent/id/)**
- [x] `Identifier.java` -> `opencode/src/id/id.ts`

### **10. LLM 模块 (java/com/zzf/codeagent/llm/)**
- [x] `LLMService.java` -> `opencode/src/session/llm.ts`

### **11. 项目模块 (`java/com/zzf/codeagent/project/`)**
- [ ] `ProjectContext.java`

### **9. 供应商模块 (java/com/zzf/codeagent/provider/)**
- [x] `ModelInfo.java` -> `opencode/src/provider/provider.ts`
- [x] `ProviderManager.java` -> `opencode/src/provider/provider.ts`
- [x] `ProviderTransform.java` -> `opencode/src/provider/transform.ts`

### **13. 会话模块 (`java/com/zzf/codeagent/session/`)**
- [x] `ContextCompactionService.java` -> `opencode/src/session/compaction.ts`
- [x] `InstructionPrompt.java` -> `opencode/src/session/instruction.ts`
- [x] `PromptReminderService.java` -> `opencode/src/session/prompt.ts` (insertReminders)
- [x] `PromptResolver.java` -> `opencode/src/session/prompt.ts` (resolvePromptParts)
- [x] `SessionInfo.java` -> `opencode/src/session/index.ts` (Session.Info)
- [x] `SessionLoop.java` -> `opencode/src/session/prompt.ts`
- [x] `SessionProcessor.java` -> `opencode/src/session/processor.ts`
- [x] `SessionProcessorFactory.java` -> `opencode/src/session/processor.ts` (SessionProcessor.create)
- [x] `SessionRetry.java` -> `opencode/src/session/retry.ts`
- [x] `SessionService.java` -> `opencode/src/session/session.ts` (mapped to session/index.ts)
- [x] `SessionStatus.java` -> `opencode/src/session/status.ts`
- [x] `SessionSummaryService.java` -> `opencode/src/session/summary.ts`
- [x] `SystemPrompt.java` -> `opencode/src/session/system.ts`
- [x] `TodoManager.java` -> `opencode/src/session/todo.ts`

### **18. IDE 插件模块 (`idea-plugin/src/main/java/com/zzf/codeagent/idea/`)**
- [x] `ChatPanel.java` -> `opencode/packages/opencode/src/index.tsx` (前端渲染架构对齐)
- [x] `AgentMessageUI` (内部类) -> `opencode` Parts 渲染体系
- [x] `MarkdownUtils.java` -> `opencode/packages/opencode/src/components/Markdown.tsx`

---

## **任务对齐分析总结 (Analysis Summary)**
- [x] **Parts 物理分离**：后端 `MessageV2` 与前端 `ChatPanel` 已完全支持 Parts 结构。
- [x] **流式分段渲染**：支持 `part.updated` SSE 事件，实现思考、工具、正文的分段显示。
- [x] **ReAct 循环一致性**：`SessionLoop` 已对齐 `opencode` 的多步推理与工具调用逻辑。
- [x] **编译错误修复**：已修复 `BashTool`、`CodeSearchTool` 及 `ChatPanel` 的所有已知编译问题。

### **14. 会话模型 (`java/com/zzf/codeagent/session/model/`)**
- [x] `MessageV2.java`
- [x] `PromptPart.java`
- [x] `TodoInfo.java`

### **15. Shell 模块 (`java/com/zzf/codeagent/shell/`)**
- [x] `ShellService.java`

### **16. 快照模块 (`java/com/zzf/codeagent/snapshot/`)**
- [x] `SnapshotService.java`

### **17. 存储模块 (`java/com/zzf/codeagent/storage/`)**
- [x] `StorageService.java`

### **18. 工具类 (`java/com/zzf/codeagent/util/`)**
- [x] `FilesystemUtil.java`

### **19. 资源文件 (`resources/`)**
- [x] `application-dev.yml`
- [x] `application.yml`

#### **Prompts - Agent**
- [x] `prompts/agent/compaction.txt`
- [x] `prompts/agent/explore.txt`
- [x] `prompts/agent/generate.txt`
- [x] `prompts/agent/summary.txt`
- [x] `prompts/agent/title.txt`

#### **Prompts - Opencode**
- [x] `prompts/opencode/anthropic-20250930.txt`
- [x] `prompts/opencode/anthropic.txt`
- [x] `prompts/opencode/beast.txt`
- [x] `prompts/opencode/build-switch.txt`
- [x] `prompts/opencode/codex_header.txt`
- [x] `prompts/opencode/copilot-gpt-5.txt`
- [x] `prompts/opencode/gemini.txt`
- [x] `prompts/opencode/max-steps.txt`
- [x] `prompts/opencode/plan-reminder-anthropic.txt`
- [x] `prompts/opencode/plan.txt`
- [x] `prompts/opencode/qwen.txt`

#### **Prompts - Session**
- [x] `prompts/session/anthropic-20250930.txt`
- [x] `prompts/session/anthropic.txt`
- [x] `prompts/session/beast.txt`
- [x] `prompts/session/build-switch.txt`
- [x] `prompts/session/codex_header.txt`
- [x] `prompts/session/copilot-gpt-5.txt`
- [x] `prompts/session/gemini.txt`
- [x] `prompts/session/max-steps.txt`
- [x] `prompts/session/plan-reminder-anthropic.txt`
- [x] `prompts/session/plan.txt`
- [x] `prompts/session/qwen.txt`

#### **Prompts - Tool**
- [x] `prompts/tool/apply_patch.txt`
- [x] `prompts/tool/bash.txt`
- [x] `prompts/tool/batch.txt`
- [x] `prompts/tool/codesearch.txt`
- [x] `prompts/tool/edit.txt`
- [x] `prompts/tool/glob.txt`
- [x] `prompts/tool/grep.txt`
- [x] `prompts/tool/ls.txt`
- [x] `prompts/tool/lsp.txt`
- [x] `prompts/tool/multiedit.txt`
- [x] `prompts/tool/plan-enter.txt`
- [x] `prompts/tool/plan-exit.txt`
- [x] `prompts/tool/question.txt`
- [x] `prompts/tool/read.txt`
- [x] `prompts/tool/task.txt`
- [x] `prompts/tool/todoread.txt`
- [x] `prompts/tool/todowrite.txt`
- [x] `prompts/tool/webfetch.txt`
- [x] `prompts/tool/websearch.txt`
- [x] `prompts/tool/write.txt`
