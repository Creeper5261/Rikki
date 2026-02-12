# 深度分析报告：Agent 交互与执行问题的根因及修复

## 1. 核心问题根因分析 (Root Cause Analysis)

### 1.1 "乱序"与"吞字" (Scrambled Text)
**现象**：思考栏或正文出现字符级乱序（如“我创让建这j个vaa文件”）。
**根本原因**：**并发竞争 (Race Condition)**。
- 后端 `SessionProcessor` 中的 `xmlBuffer` 是一个 `StringBuilder`。
- `onTextDelta` 方法在处理流式响应时，可能被 LLM 服务的异步回调线程并发调用。
- 由于 `StringBuilder` 非线程安全，且 `onTextDelta` 未加锁，导致多个线程同时写入缓冲区时，字符索引错乱，产生了交织的文本流。

### 1.2 UI 呈现非线性 (Non-Linear UI)
**现象**：正文在上，思考过程堆叠在下方，不符合时序。
**根本原因**：**对象复用 (Object Reuse)**。
- 后端 `SessionProcessor` 在检测到 `<thought>` 或 `<tool>` 标签时，虽然会切换状态，但没有显式关闭当前的 `TextPart`。
- 当思考或工具调用结束后，Agent 继续输出文本时，后端复用了**同一个** `TextPart` 对象（ID 不变）。
- 前端 `ChatPanel` 根据 ID 更新组件。由于该组件在对话开始时就已创建并添加到了顶部，后续追加的文本依然显示在顶部组件中，而中间产生的思考/工具卡片被添加到了底部，导致视觉上的“倒挂”。

### 1.3 工具调用无效 (Tool Execution Failure)
**现象**：Agent 声称创建了文件，但工作区无变化，无 Diff 视图。
**根本原因**：**工具缺失与别名未处理 (Missing Tool Alias)**。
- DeepSeek/Gemini 等模型倾向于使用 `write` 工具来创建文件。
- 当前项目仅实现了 `EditTool`（ID 为 `edit`），未实现 `WriteTool`。
- `SessionProcessor` 在解析工具标签时，会校验 `tools.containsKey(name)`。由于 `write` 不在注册表中，解析器将其判定为无效工具，导致 `<write>...</write>` 被当作普通文本直接输出到会话中（即“自嗨”），而未触发任何后端逻辑。

### 1.4 会话卡顿 (Performance Lag)
**现象**：`cat` 文件内容导致界面卡死。
**根本原因**：**全量渲染 (Full Rendering)**。
- 当 `read_file` 返回大文件内容（如 2000 行代码）时，后端将完整内容作为 `tool_result` 发送给前端。
- 前端 `ChatPanel.summarizeToolOutput` 方法在处理输出时，先对全量字符串执行了 `replace("\n", " ")` 操作，产生巨大的临时字符串对象，导致内存飙升和 UI 线程阻塞。

---

## 2. 补救方案与修复 (Implemented Fixes)

### 2.1 修复并发乱序
- **措施**：在 `SessionProcessor.onTextDelta` 方法上添加 `synchronized` 关键字。
- **效果**：确保同一时刻只有一个线程能写入缓冲区，杜绝字符交织。

### 2.2 修复 UI 时序
- **措施**：在 `SessionProcessor` 状态机中（进入/退出 `<thought>` 或 `<tool>` 时），强制将 `currentText` 置为 `null`。
- **效果**：迫使后续文本输出创建全新的 `TextPart`（分配新 ID）。前端会将其渲染为一个新的文本块，追加在思考/工具卡片之后，从而实现 `[文本] -> [思考] -> [文本]` 的正确线性布局。

### 2.3 修复工具调用
- **措施**：在 `SessionProcessor` 中实现 **动态别名映射**。
  - 解析时：若遇到 `write` 标签且无 `write` 工具但有 `edit` 工具，自动识别为有效工具。
  - 执行时：将 `write` 调用重定向到 `EditTool` 的执行逻辑。
- **效果**：Agent 使用 `<write>` 也能正确触发文件编辑/创建逻辑，生成 Pending Changes。

### 2.4 优化性能
- **措施**：在 `ChatPanel.summarizeToolOutput` 中添加预截断逻辑。
  - 在执行耗时的字符串替换前，先判断长度，若超过 1000 字符直接截断。
- **效果**：即使 Agent 读取了超大文件，UI 仅渲染摘要，保持流畅。

## 3. 验证建议 (Verification)
请重新运行会话，预期行为如下：
1. **流畅性**：不再出现乱序文字。
2. **顺序**：思考块和文本块交替出现，符合直觉。
3. **有效性**：Agent 说“创建文件”后，立即弹出 Diff 卡片，点击 Show Diff 可见内容。
4. **响应**：读取大文件时 UI 依然响应迅速。
