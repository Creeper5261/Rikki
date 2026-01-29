# SOTA Agent 稳定化与可用性修复计划（业界链路对标版）

目标：基于业界公开链路（OpenHands/Continue/Aider/OpenCode 的共识做法）建立“可验证、可回归、可收敛”的修复流程，保证：
- 首次固定 repo map；
- 已读文件/已列目录不重复读取；
- 关键记忆可追溯且不臆想；
- 代理修改先落在 Shadow Workspace，用户确认后再合并；
- 工具错误不再导致无法修改工作区文件。

---
## 进度
- [x] Phase 0
- [x] Phase 1
- [x] Phase 2
- [x] Phase 3
- [x] Phase 4
- [ ] Phase 5

---

## 总体原则（业界对标）
1) **Repo Map 先行**：首次进入会话必须生成结构化地图（Aider 的 repo map 思路）。
2) **上下文精确供给**：只注入必要文件/片段（Continue 的 context provider 思路）。
3) **Action/Observation 闭环**：工具结果必须能解释且可回放（OpenHands 的 tool loop 思路）。
4) **Workspace 隔离 + Commit 合并**：代理只写 shadow workspace，用户确认后才落地（OpenCode/OpenHands 的 workspace 抽象思路）。
5) **最小可行提示词**：工具说明只注入一次，严格体积预算（避免膨胀导致慢/循环）。

---

## Phase 0：基线与可观测性（锁定问题，不再靠感觉）
**目标**：让每次失败都能被准确归因，避免“改了但不知道有没有生效”。

**任务**
- 建立最小复现用例（空工程 + 单文件 Main.java + 单请求）。
- 将工具调用序列与结果形成结构化“执行链路记录”。
- 固化 3 个关键日志指标：
  - tool_budget_exceeded 次数
  - 重复 LIST_FILES/READ_FILE 次数
  - WRITE/EDIT 成功率

**验收标准**
- 单次请求的工具链路可完整回放（无缺失事件）。
- 能明确区分“工具失败”与“模型重复决策”。

---

## Phase 1：强制 Repo Map + 去重策略
**目标**：第一次固定 repo map，杜绝重复 LIST/READ。

**任务**
- 会话开始强制生成 REPO_MAP/STRUCTURE_MAP（无 map 则禁止 LIST_FILES/READ_FILE）。
- 目录列举、文件读取建立 **Read/List Index**：
  - path + range + hash 作为键；
  - 未变化则返回 cached，不计入工具预算；
  - 文件内容/时间戳变化才允许重新 READ。
- LIST_FILES 仅允许 1 次 root + 多次子目录（需要新子树时）。

**验收标准**
- 单次会话中：root LIST_FILES <= 1。
- 已读文件不会再次 READ（除非文件内容变化或扩大范围）。

---

## Phase 2：记忆链路重建（防止臆想与污染）
**目标**：关键记忆可追溯、不幻觉、不反复驱动错误行为。

**任务**
- 事实记忆（Facts）必须绑定证据来源（READ_FILE/GREP/REPO_MAP）。
- Session Summary 只写“已确认事实”，不写“下一步建议”。
- 关键文件的内容摘要建立“证据快照”，并在后续 prompt 中引用快照而不是重新读取。

**验收标准**
- Summary 中的每条事实都有工具来源标记。
- Summary 不再触发“已读文件仍反复读取”。

---

## Phase 3：Shadow Workspace + 用户确认合并
**目标**：代理修改只发生在自己的视图；用户确认后才写入真实工作区。

**任务**
- 所有写操作进入 Shadow Workspace（PendingChangesManager）。
- Diff/Preview 仅针对 Shadow Workspace。
- 用户确认后触发 Apply（原子合并到真实工作区）。

**验收标准**
- 未确认时，真实工作区无任何变更。
- 确认后，所有变更一次性原子落地。

---

## Phase 4：写入可靠性修复（避免“改不了文件”）
**目标**：工具错误不再阻断修改。

**任务**
- EDIT_FILE 失败（old_text_required）时自动 fallback：
  - 若已 READ，则直接使用完整内容生成替换；
  - 若没 READ，先 READ 再写入。
- 工具参数统一：path/filePath 统一解析，避免空参失败。
- tool_budget 只拦“无效重复调用”，不拦必要读写。

**验收标准**
- 简单改动（如替换 main 输出）一次完成。
- 不再出现“EDIT_FILE old_text_required 无限失败”。

---

## Phase 5：回归与性能验证
**目标**：保证修复不引入新的循环和超时。

**任务**
- 回归脚本：
  - 1+1 小任务 ≤ 3 次工具调用。
  - 无重复 LIST_FILES/READ_FILE。
  - 无 tool_budget_exceeded。
- 压测：大仓库 repo map 生成时间可控。

**验收标准**
- 所有回归用例通过。
- 平均响应时间下降 50% 以上。

---

## 产出与里程碑
- M1：Repo map 强制 + 去重完成
- M2：记忆链路与 Summary 可靠化
- M3：Shadow Workspace + Commit 确认闭环
- M4：写入可靠性修复
- M5：回归验证通过

---

## 风险与对策
- **风险**：repo map 过大导致信息稀释
  - 对策：token 上限 + 分层 map
- **风险**：去重过严导致必要 READ 被拒绝
  - 对策：hash 变更检测 + 明确 range 扩展规则
- **风险**：工具预算仍误伤关键操作
  - 对策：预算只针对“重复无效调用”

---

## 交付方式
- 每个 Phase 完成后输出：
  - 变更清单
  - 回归结果
  - 验收对比

备注：
- 本计划仅对标“业界公开可验证链路”，不做额外臆想。
- 若你确认，按 Phase 逐步执行，不跳步。

