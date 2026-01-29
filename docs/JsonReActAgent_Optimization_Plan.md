# JsonReActAgent Optimization Plan

> **Version**: 1.0  
> **Date**: 2026-01-29  
> **Status**: Proposed  
> **Author**: Code Agent Assistant  

## 1. Executive Summary

This document outlines a comprehensive optimization plan for `JsonReActAgent.java`, targeting the architectural limitations identified during recent debugging sessions. The current implementation suffers from **rigid prompt management**, **aggressive context filtering**, and **fragile output parsing**, which directly contribute to issues like "hallucinated file inaccessibility" and "identity confusion".

The proposed solutions draw from state-of-the-art (SOTA) open-source agents (SWE-agent, Aider, OpenDevin) and industry best practices for LLM agent engineering.

---

## 2. Problem Analysis & Solutions

### 2.1 Prompt Engineering Architecture
**Current State**: 
- Static System Prompt hardcoded in `codex_header.txt` (Line 66).
- Identity fixed as "OpenCode".
- Prompt construction relies on complex string concatenation in `buildPrompt` (Lines 587-719).

**Proposed Solution**: **Configuration-Driven Dynamic Prompts**
- **Template Engine**: Adopting a lightweight template engine (e.g., Jinja2 for Java or Mustache) to manage prompts. This allows separating logic from text.
- **Configurable Identity**: Move agent identity (Name, Role, Constraints) to an external YAML/JSON configuration file (similar to SWE-agent's `config/default.yaml`).
- **Dynamic Injection**:
    - Inject `Current Working Directory`, `OS Info`, and `Date` dynamically.
    - Support "Persona Switching" (e.g., switch between "Coder", "Reviewer", "Architect" modes).

**Reference Implementation**:
```yaml
# agent_config.yaml
system_prompt_template: "templates/system_v2.j2"
agent_name: "${AGENT_NAME:OpenCode}"
model_config:
  temperature: 0.2
  stop_sequences: ["OBSERVATION", "USER"]
```

### 2.2 Context Management & Filtering
**Current State**:
- **Aggressive Filtering**: `filterIdeContext` (Line 750) uses simple keyword matching against the User Goal. If a user asks about "Login", files without the exact string "Login" (but relevant logic) are hidden.
- **Result**: Agent claims files are empty or inaccessible because they were filtered out of the prompt.

**Proposed Solution**: **Semantic & Structural Context**
- **Tree-Sitter Skeleton**: Instead of raw text or keyword filtering, provide a "Code Skeleton" (Class names, Method signatures, Docstrings) of active files. This fits more files into context without losing structure.
- **RAG Integration**: Use the existing `SmartRetrievalPipeline` (Vector Search) to select relevant code chunks instead of simple keyword matching.
- **Explicit "Map"**: Implement a "Repo Map" (like Aider) that provides a compressed tree view of the entire project structure in the System Prompt, allowing the Agent to "know" what exists before searching.

### 2.3 Output Robustness (JSON vs. XML)
**Current State**:
- **JSON Fragility**: The Agent demands strict JSON. LLMs often produce Markdown-wrapped JSON (````json ... ````) or malformed JSON (unclosed quotes), causing `Model output protocol error`.
- **Truncation**: `summarizeObservation` (Line 905) simply truncates the end of strings, potentially cutting off valid JSON closing braces or key error details.

**Proposed Solution**: **Hybrid Parsing & Smart Truncation**
- **Robust Parsing**: Implement a "Fuzzy JSON Parser" that can extract JSON objects even if embedded in text or missing closing braces.
- **XML Tags (Alternative)**: Consider supporting XML-style tool calls (e.g., `<tool>READ_FILE</tool>`), which recent research (Anthropic) suggests are more robust for some models than JSON.
- **Smart Truncation**:
    - **Head + Tail**: For long logs/errors, keep the first 1000 chars (error type) and last 1000 chars (stack trace), truncating the middle.
    - **Structured Truncation**: When truncating a file preview, insert a clear placeholder: `... [150 lines hidden] ...`.

### 2.4 Loop Detection & Stagnation
**Current State**:
- **Semantic Loop**: `detectLoop` (Line 346) checks if the *exact same tool output* occurs twice.
- **Forced Final Answer**: `forceFinalAnswer` (Line 568) forces a hallucinated response when `MAX_TURNS` is reached.

**Proposed Solution**: **Adaptive Strategy & "Give Up" Protocol**
- **History Analysis**: Instead of just checking the last output, analyze the sliding window of the last 5 turns. If the Agent performs `READ -> LIST -> READ` cycles repeatedly, interrupt it.
- **Hint Injection**: When a loop is detected, do NOT just fail. Inject a **System Hint**: *"You seem stuck. You have read this file 3 times. Try using GREP to find usages instead."*
- **Honest Failure**: Modify `forceFinalAnswer` to explicitly allow "I cannot complete the task" as a valid outcome, preventing misleading hallucinations.

---

## 2.5 Turn Limit Strategy (MAX_TURNS)
**Current State**:
- Hardcoded `MAX_TURNS = 30`.
- When limit is reached, `forceFinalAnswer` is called, often leading to hallucinations as the model tries to "guess" the answer without sufficient data.

**SOTA Analysis**:
- **Industry Standard**: All major frameworks (SWE-agent, OpenDevin, LangChain) enforce hard limits (typically 30-50 steps) to prevent infinite loops and cost overruns.
- **Handling**: 
    - **LangChain**: Uses `early_stopping_method="generate"`, prompting the model to "give your best answer based on what you know".
    - **OpenDevin**: Introduces `GLOBAL_MAX_ITERATIONS` to track budget across nested agents.
    - **Research**: Suggests "Budget Awareness" — informing the Agent of remaining steps (e.g., "Step 25/30") so it can wrap up investigation and focus on concluding.

**Proposed Solution**: **Budget-Aware Graceful Exit**
- **Configurable Limit**: Move `MAX_TURNS` to `agent_config.yaml`.
- **Step Countdown**: Inject "Steps Remaining: X" into the System Prompt.
- **Graceful Termination**: 
    - Instead of a silent `forceFinalAnswer`, inject a final prompt: *"You have exhausted your step budget. Please summarize what you have verified so far and explicitly state what is still unknown. Do NOT guess."*

---

## 2.6 Large Scale Context & Memory Strategy
**Current State**:
- No specific mechanism for large repositories.
- `filterIdeContext` uses simple keyword matching, which is lossy and unreliable.
- Relies on raw file reading, which quickly consumes token budget.

**SOTA Analysis**:
How SOTA agents (SWE-agent, Aider, OpenDevin, MemGPT) handle infinite context:

1.  **Repo Map (Repository Skeleton)**:
    *   **Technique**: Instead of reading full files, generate a **Compressed AST (Abstract Syntax Tree)** summary.
    *   **Implementation**: Use Tree-Sitter to extract only `Class Names`, `Method Signatures`, and `Docstrings`.
    *   **Effect**: A 10,000-line file is compressed to ~500 tokens of structure. The Agent sees *where* things are without reading details. (Source: Aider).

2.  **Vector Memory (RAG)**:
    *   **Technique**: Long-term storage of code chunks in a Vector DB (e.g., Chroma/Faiss).
    *   **Implementation**: When Agent asks "Where is auth logic?", query the Vector DB to retrieve top-k relevant snippets instead of reading all files.
    *   **Effect**: Decouples "Knowledge" from "Context Window".

3.  **Context Pruning & Sliding Window**:
    *   **Technique**: **SWE-Pruner** or similar logic.
    *   **Implementation**:
        *   Keep the **System Prompt** (Identity) fixed.
        *   Keep the **Task Description** (User Goal) fixed.
        *   **Slide/Prune** the History: Retain only the last N turns.
        *   **Summarize** old turns: "Turns 1-10: Explored utils package, found nothing relevant."

4.  **Hierarchical Agents (Divide & Conquer)**:
    *   **Technique**: Use a "Manager Agent" to break tasks into sub-tasks.
    *   **Implementation**:
        *   Manager: "Plan: 1. Fix backend. 2. Fix frontend."
        *   Worker 1: Receives only backend files.
        *   Worker 2: Receives only frontend files.
    *   **Effect**: Isolates context per sub-task.

**Proposed Solution**: **Repo Map + Semantic Search**
- **Immediate**: Implement a `RepoStructureService` that generates a file-tree-like map with class/method signatures.
- **Mid-term**: Enhance `SmartRetrievalPipeline` to support semantic code search (already partially present in project).
- **Long-term**: Implement `MemoryManager` that summarizes past turns when context > 80% full.

---

## 2.7 RAG Strategy Re-evaluation (Long-Term Memory)
**Current State**:
- **Role**: RAG (`SmartRetrievalPipeline`) is currently a "One-Shot" fast path.
- **Problem**: If the initial retrieval fails (returns < 0.5 efficiency), the Agent is explicitly instructed to "explore manually" (see `Phase 2 Search Efficiency Policy`). This causes the Agent to treat RAG as a "failed step" rather than a persistent tool, leading to **"Context Fragmentation"** (seeing only snippets, never the whole).

**SOTA Analysis**:
- **MemGPT**: Uses a tiered memory architecture (Core Memory vs. Recall Memory). RAG is the "Recall Memory" that the Agent *actively* queries when it forgets details or needs to look up facts.
- **RAG as Tool**: In advanced agents, RAG is not just a pre-filter but a **first-class tool** (`SEARCH_KNOWLEDGE` or `consult_knowledge_base`) available throughout the session.

**Proposed Solution**: **Interactive RAG & Memory Tiering**
- **First-Class Tool**: Expose `SmartRetrieval` as a tool (`SEARCH_KNOWLEDGE`) that the Agent can call *at any time*, not just at the start.
- **Interactive Refinement**: Allow the Agent to re-query if the first result is poor (e.g., "Search failed, I will try broader keywords").
- **Long-Term Memory Role**: Explicitly frame RAG in the System Prompt as "access to the project's long-term knowledge base" rather than just "search".

---

## 3. Implementation Roadmap

### Phase 1: Robustness (Low Effort, High Impact)
- [x] **Fix JSON Parser**: Add logic to strip Markdown code blocks (```json) before parsing in `JsonReActAgent`.
- [x] **Smart Truncation**: Modify `summarizeObservation` to use "Head + Tail" strategy for Error outputs.
- [x] **Disable Aggressive Filtering**: Temporarily disable or relax `filterIdeContext` to ensure the Agent sees all open files (fixing the "Main.java not found" issue).

### Phase 2: Architecture (Medium Effort)
- [x] **Extract Prompts**: Move `codex_header.txt` content to a `resources/prompts/` directory with proper template variables.
- [x] **Repo Map**: Integrate a lightweight "Structure Map" into the initial context.

### Phase 3: SOTA Features (High Effort)
- [ ] **Tree-Sitter Integration**: Replace text-based context with AST-based skeletons.
- [x] **Dynamic Strategy**: Implement a "Manager Agent" or "Reflection Loop" that monitors the ReAct loop and intervenes if the Agent gets stuck.

---

## 4. References to Codebase

- **Loop Logic**: [`JsonReActAgent.java:L238`](file:///d:/plugin_dev/code-agent/src/main/java/com/zzf/codeagent/core/agent/JsonReActAgent.java#L238)
- **Prompt Construction**: [`JsonReActAgent.java:L587`](file:///d:/plugin_dev/code-agent/src/main/java/com/zzf/codeagent/core/agent/JsonReActAgent.java#L587)
- **Context Filtering**: [`JsonReActAgent.java:L750`](file:///d:/plugin_dev/code-agent/src/main/java/com/zzf/codeagent/core/agent/JsonReActAgent.java#L750)
- **Observation Summary**: [`JsonReActAgent.java:L905`](file:///d:/plugin_dev/code-agent/src/main/java/com/zzf/codeagent/core/agent/JsonReActAgent.java#L905)
