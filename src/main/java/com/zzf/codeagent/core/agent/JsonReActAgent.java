package com.zzf.codeagent.core.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzf.codeagent.core.event.AgentEvent;
import com.zzf.codeagent.core.event.EventSource;
import com.zzf.codeagent.core.event.EventStream;
import com.zzf.codeagent.core.event.EventType;
import com.zzf.codeagent.core.pipeline.DynamicContextBuilder;
import com.zzf.codeagent.core.rag.pipeline.IndexingWorker;
import com.zzf.codeagent.core.rag.search.ElasticsearchCodeSearchService;
import com.zzf.codeagent.core.rag.search.HybridCodeSearchService;
import com.zzf.codeagent.core.rag.search.InMemoryCodeSearchService;
import com.zzf.codeagent.core.runtime.RuntimeService;
import com.zzf.codeagent.core.skill.Skill;
import com.zzf.codeagent.core.skill.SkillManager;
import com.zzf.codeagent.core.skill.SkillSelector;
import com.zzf.codeagent.core.tool.PendingChangesManager;
import com.zzf.codeagent.core.tool.ToolExecutionContext;
import com.zzf.codeagent.core.tool.ToolExecutionService;
import com.zzf.codeagent.core.tool.ToolProtocol;
import com.zzf.codeagent.core.tools.FileSystemToolService;
import com.zzf.codeagent.core.util.JsonUtils;
import com.zzf.codeagent.core.util.StringUtils;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

public class JsonReActAgent {
    private static final Logger logger = LoggerFactory.getLogger(JsonReActAgent.class);

    private static final int MAX_TURNS = 30;
    private static final int MAX_TOOL_CALLS = 18;
    private static final int MAX_CONSECUTIVE_TOOL_ERRORS = 3;
    private static final int TOOL_BACKOFF_THRESHOLD = 2;
    private static final int MAX_OBS_CHARS = 4000;
    private static final int MAX_PROMPT_CHARS = 24000;
    private static final int MAX_GOAL_CHARS = 4000;
    private static final int MAX_CHAT_LINES = 8;
    private static final int MAX_CHAT_LINE_CHARS = 600;
    private static final int MAX_CHAT_BLOCK_CHARS = 4000;
    private static final int MAX_FACTS = 120;
    private static final int MAX_FACTS_BLOCK_CHARS = 6000;
    private static final int MAX_PINNED_CHARS = 4000;
    private static final int MAX_IDE_CONTEXT_CHARS = 6000;
    private static final int MAX_HISTORY_LINES = 6;
    private static final int MAX_CLAUDE_MEMORY_CHARS = 6000;
    private static final int MAX_LONG_TERM_MEMORY_CHARS = 8000;
    private static final int MAX_SKILL_CONTENT_CHARS = 2500;
    private static final int MAX_AUTO_SKILLS = 2;
    
    private static final Pattern SENSITIVE_KV = Pattern.compile("(?i)(password|passwd|secret|token|apikey|accesskey|secretkey)\\s*[:=]\\s*([\"']?)([^\"'\\\\\\r\\n\\s]{1,160})\\2");
    private static final Pattern SENSITIVE_JSON_KV = Pattern.compile("(?i)(\"(?:password|passwd|secret|token|apiKey|accessKey|secretKey)\"\\s*:\\s*\")([^\"]{1,160})(\")");
    private static final String IDE_OPENED_FILE_PROMPT = "The user opened the file $filename in the IDE. This may or may not be related to the current task.";
    private static final String CHECK_NEW_TOPIC_PROMPT = "Analyze if this message indicates a new conversation topic. If it does, extract a 2-3 word title that captures the new topic. Format your response as a JSON object with two fields: 'isNewTopic' (boolean) and 'title' (string, or null if isNewTopic is false). Only include these fields, no other text.";

    private static final String SKILL_SYSTEM_PROMPT = 
            "You have access to a set of Skills that provide specialized capabilities. \n" +
            "Skills are loaded from built-in classpath resources and from the workspace (.agent/skills, .claude/skills). \n" +
            "To use a skill, you must first load it using LOAD_SKILL. \n" +
            "Once loaded, the skill will provide detailed instructions and context. \n" +
            "Below are the currently available skills:\n";

    private final ObjectMapper mapper;
    private final OpenAiChatModel model;
    private final ElasticsearchCodeSearchService search;
    private final HybridCodeSearchService hybridSearch;
    private final String traceId;
    private final String workspaceRoot;
    private final String sessionId;
    private final String sessionRoot;
    private final IndexingWorker indexingWorker;
    private final String kafkaBootstrapServers;
    private final FileSystemToolService fs;
    private final ToolExecutionService toolExecutionService;
    private final RuntimeService runtimeService;
    private final SkillManager skillManager;
    private final List<String> chatHistory;
    private final String ideContextPath;
    private final EventStream eventStream;
    
    private InMemoryCodeSearchService memory;
    private boolean memoryIndexed;
    private final List<String> history = new ArrayList<>();
    private String lastObsLine;
    private final Map<String, String> knownFacts = new LinkedHashMap<>();
    private final Map<String, Integer> factPriority = new HashMap<>();
    private final Map<String, Long> factSeq = new HashMap<>();
    private final Map<String, String> factFingerprints = new HashMap<>();
    private final Set<String> evidenceSources = new LinkedHashSet<>();
    private long factSeqCounter;
    private boolean hasEvidence;
    private boolean hasCodeEvidence;
    private boolean hasTodoFact;
    private boolean ideContextLoaded;
    private String ideContextContent;
    private boolean claudeMemoryLoaded;
    private String claudeMemoryContent;
    private boolean longTermMemoryLoaded;
    private String longTermMemoryContent;
    private boolean agentsMdLoaded;
    private String agentsMdContent;
    private final Set<String> globalSearchHits = new HashSet<>();
    private final Set<String> readFiles = new HashSet<>();
    private int lowEfficiencySearchCount = 0;
    private int toolCallCount = 0;
    private int toolErrorCount = 0;
    private int consecutiveToolErrors = 0;
    private int turnsUsed = 0;
    private int editsApplied = 0;
    private int editsRejected = 0;
    private final List<String> autoSkillsLoaded = new ArrayList<>();
    private String lastFailedTool;
    private int toolBudgetLimit = MAX_TOOL_CALLS;
    private PlanExecutionState planState;
    private final List<Map<String, Object>> pendingChanges = new ArrayList<>();

    public JsonReActAgent(ObjectMapper mapper, OpenAiChatModel model, ElasticsearchCodeSearchService search, HybridCodeSearchService hybridSearch, String traceId, String workspaceRoot, IndexingWorker indexingWorker, String kafkaBootstrapServers, List<String> chatHistory, String ideContextPath, ToolExecutionService toolExecutionService, RuntimeService runtimeService, EventStream eventStream, SkillManager skillManager) {
        this(mapper, model, search, hybridSearch, traceId, workspaceRoot, workspaceRoot, traceId, workspaceRoot, indexingWorker, kafkaBootstrapServers, chatHistory, ideContextPath, toolExecutionService, runtimeService, eventStream, skillManager);
    }

    public JsonReActAgent(ObjectMapper mapper, OpenAiChatModel model, ElasticsearchCodeSearchService search, HybridCodeSearchService hybridSearch, String traceId, String workspaceRoot, String fileSystemRoot, String sessionId, String publicWorkspaceRoot, IndexingWorker indexingWorker, String kafkaBootstrapServers, List<String> chatHistory, String ideContextPath, ToolExecutionService toolExecutionService, RuntimeService runtimeService, EventStream eventStream, SkillManager skillManager) {
        this.mapper = mapper;
        this.model = model;
        this.search = search;
        this.hybridSearch = hybridSearch;
        this.traceId = traceId;
        this.workspaceRoot = workspaceRoot == null ? "" : workspaceRoot.trim();
        this.sessionId = sessionId == null ? "" : sessionId.trim();
        this.sessionRoot = fileSystemRoot == null ? "" : fileSystemRoot.trim();
        this.indexingWorker = indexingWorker;
        this.kafkaBootstrapServers = kafkaBootstrapServers == null ? "" : kafkaBootstrapServers.trim();
        this.chatHistory = chatHistory == null ? new ArrayList<>() : new ArrayList<>(chatHistory);
        this.ideContextPath = ideContextPath == null ? "" : ideContextPath.trim();
        this.toolExecutionService = toolExecutionService;
        this.runtimeService = runtimeService;
        this.eventStream = eventStream;
        this.skillManager = skillManager;
        
        Path rootPath;
        try {
            String fsRoot = fileSystemRoot == null ? "" : fileSystemRoot.trim();
            rootPath = fsRoot.isEmpty() ? null : Paths.get(fsRoot);
        } catch (Exception e) {
            rootPath = null;
        }
        this.fs = new FileSystemToolService(rootPath, this.sessionId, publicWorkspaceRoot);
        this.memory = null;
        this.memoryIndexed = false;
        this.lastObsLine = null;
        this.ideContextLoaded = false;
        this.ideContextContent = "";
        this.claudeMemoryLoaded = false;
        this.claudeMemoryContent = "";
        this.longTermMemoryLoaded = false;
        this.longTermMemoryContent = "";
        this.agentsMdLoaded = false;
        this.agentsMdContent = "";
        this.toolCallCount = 0;
        this.toolErrorCount = 0;
        this.consecutiveToolErrors = 0;
        this.lastFailedTool = null;
        this.toolBudgetLimit = MAX_TOOL_CALLS;
    }

    public List<Map<String, Object>> getPendingChanges() {
        return pendingChanges;
    }

    public int getToolCallCount() {
        return toolCallCount;
    }

    public int getToolErrorCount() {
        return toolErrorCount;
    }

    public int getTurnsUsed() {
        return turnsUsed;
    }

    public int getEditsApplied() {
        return editsApplied;
    }

    public int getEditsRejected() {
        return editsRejected;
    }

    public List<String> getAutoSkillsLoaded() {
        return new ArrayList<>(autoSkillsLoaded);
    }

    public String run(String goal) {
        ensureIdeContextLoaded();
        ensureClaudeMemoryLoaded();
        ensureLongTermMemoryLoaded();
        ensureAgentsMdLoaded();
        this.planState = PlanExecutionState.fromGoal(goal);
        logger.info("agent.run.start traceId={} goalChars={} workspaceRoot={} ideContextPath={} chatHistorySize={}", traceId, goal == null ? 0 : goal.length(), workspaceRoot, ideContextPath, chatHistory.size());
        emitAgentStepEvent("run_start", goal, null, null);
        ObjectNode initState = mapper.createObjectNode();
        initState.put("traceId", traceId == null ? "" : traceId);
        initState.put("workspaceRoot", workspaceRoot == null ? "" : workspaceRoot);
        initState.put("goalChars", goal == null ? 0 : goal.length());
        initState.put("agentState", "running");
        initState.put("startedAt", Instant.now().toString());
        updateSessionState(initState, null);
        
        // Dynamic Context Injection (SOTA Phase 2)
        // If we have an ideContextPath (root), try to build a dynamic tree instead of using the static one.
        refreshIdeContext();

        String lastSearchQuery = null;
        int sameSearchQueryCount = 0;
        String lastToolSignature = null;
        int sameToolSignatureCount = 0;
        Map<String, Integer> signatureCounts = new HashMap<>();
        Map<String, String> signatureToObs = new HashMap<>();
        
        for (int i = 0; i < MAX_TURNS; i++) {
            turnsUsed = i + 1;
            logger.info("agent.turn.start traceId={} turn={} goalChars={} requestHistorySize={} agentHistorySize={}", traceId, i, goal == null ? 0 : goal.length(), chatHistory.size(), history.size());
            emitAgentStepEvent("turn_start", goal, i, null);
            String prompt = buildPrompt(goal);
            logger.info("llm.prompt traceId={} chars={}", traceId, prompt.length());
            logModelRequest("turn", prompt);
            String raw = model.chat(prompt);
            logger.info("llm.raw traceId={} raw={}", traceId, StringUtils.truncate(raw, 2000));
            JsonNode node = parseWithRetry(prompt, raw);
            String type = node.path("type").asText();
            // Phase 2.1: Cognitive Auto-Correction
            // If agent outputs type="final" but includes a tool call, assume it meant to use the tool.
            // RELAXED: Only auto-correct if "tool" is explicitly provided and not empty. Ignore "args" alone.
            if ("final".equalsIgnoreCase(type) && node.hasNonNull("tool") && !node.path("tool").asText().trim().isEmpty()) {
                logger.warn("agent.correction traceId={} reason=final_type_with_tool_intent", traceId);
                type = "tool"; // Auto-correct intent
            }
            logger.info("llm.parsed traceId={} type={}", traceId, type);
            String thought = node.path("thought").asText();
            if (thought != null && thought.trim().length() > 0) {
                String thoughtText = StringUtils.truncate(thought, 300);
                history.add("THOUGHT " + thoughtText);
                emitAgentStepEvent("thought", thought, i, null);
            }
            if ("final".equalsIgnoreCase(type)) {
                captureFacts(node, false);
                String fa = node.path("finalAnswer").asText(raw);
                String finalAnswer = finalizeAnswer(node, fa);
                emitAgentStepEvent("final", finalAnswer, i, null);
                history.add("FINAL " + StringUtils.truncate(finalAnswer, 400));
                return finalAnswer;
            }
            if (!"tool".equalsIgnoreCase(type)) {
                return "Model output protocol error: type=" + String.valueOf(type) + " raw=" + StringUtils.truncate(raw, 800);
            }
            if (node.hasNonNull("tool") == false || node.has("args") == false) {
                return "Model output protocol error: type=tool but tool/args missing";
            }
            captureFacts(node, true);
            String tool = node.path("tool").asText();
            JsonNode args = node.path("args");
            String toolVersion = node.path("version").asText("");
            if (toolVersion == null || toolVersion.isBlank()) {
                toolVersion = node.path("toolVersion").asText("");
            }
            String sig = toolSignature(tool, toolVersion, args);
            long toolCallEventId = emitToolCallEvent(tool, toolVersion, sig, args, i);
            logToolRequest(tool, args);
            toolCallCount++;
            if (toolCallCount > toolBudgetLimit) {
                String obs = mapper.createObjectNode()
                        .put("tool", tool)
                        .put("error", "tool_budget_exceeded")
                        .put("maxToolCalls", toolBudgetLimit)
                        .put("hint", "Tool call budget exceeded. Stop calling tools and provide a final answer based on current facts.")
                        .set("args", args == null ? MissingNode.getInstance() : args)
                        .toString();
            logToolResult(tool, obs);
            trackEditMetrics(tool, obs);
            trackEditMetrics(tool, obs);
            emitToolResultEvent(tool, toolVersion, sig, obs, i, toolCallEventId);
            updateToolState(tool, toolVersion, sig, args, obs, i, toolCallEventId);
                String obsLine = "OBS " + StringUtils.truncate(obs, MAX_OBS_CHARS);
                history.add(obsLine);
                lastObsLine = obsLine;
                if (planState != null) {
                    planState.recordObservation(obs);
                }
                String forced = forceFinalAnswer(goal);
                history.add("FINAL " + StringUtils.truncate(forced, 400));
                return forced;
            }
            if (planState != null) {
                planState.recordAction(tool, sig, false);
            }
            if (lastToolSignature != null && lastToolSignature.equals(sig)) {
                sameToolSignatureCount++;
            } else {
                sameToolSignatureCount = 0;
                lastToolSignature = sig;
            }
            Integer seen = signatureCounts.get(sig);
            signatureCounts.put(sig, seen == null ? 1 : (seen.intValue() + 1));
            int sigCount = signatureCounts.get(sig).intValue();
            if ("READ_FILE".equals(tool) && sigCount >= 3) {
                String obs = mapper.createObjectNode()
                        .put("tool", tool)
                        .put("error", "repeated_read_range")
                        .put("hint", "Repeated READ_FILE on the same file range more than twice. Change range or use another tool.")
                        .set("args", args == null ? MissingNode.getInstance() : args)
                        .toString();
            logToolResult(tool, obs);
            trackEditMetrics(tool, obs);
            emitToolResultEvent(tool, toolVersion, sig, obs, i, toolCallEventId);
            updateToolState(tool, toolVersion, sig, args, obs, i, toolCallEventId);
                String obsLine = "OBS " + StringUtils.truncate(obs, MAX_OBS_CHARS);
                history.add(obsLine);
                lastObsLine = obsLine;
                if (planState != null) {
                    planState.recordObservation(obs);
                }
                continue;
            }
            String preCalculatedObs = null;
            boolean isLoop = (sameToolSignatureCount >= 1 || sigCount >= 2);

            // SOTA: Semantic Loop Detection for READ_FILE
            // If the content is different (e.g. paging), it's NOT a loop even if signature (path) is same.
            if (isLoop && "READ_FILE".equals(tool)) {
                try {
                    ToolExecutionContext ctx = new ToolExecutionContext(traceId, workspaceRoot, sessionRoot, mapper, fs, search, hybridSearch, indexingWorker, kafkaBootstrapServers, eventStream, runtimeService, skillManager);
                    String newObs = toolExecutionService.execute(tool, toolVersion, args, ctx);
                    String cached = signatureToObs.get(sig);
                    // Compare JSON-serialized observations. 
                    // If content changed, the JSON string will differ.
                    boolean contentChanged = true;
                    if (cached != null) {
                        try {
                            JsonNode cNode = mapper.readTree(cached);
                            JsonNode nNode = mapper.readTree(newObs);
                            // Compare only 'result' and 'error' fields, ignoring 'tookMs' which changes every call.
                            JsonNode cRes = cNode.path("result");
                            JsonNode nRes = nNode.path("result");
                            JsonNode cErr = cNode.path("error");
                            JsonNode nErr = nNode.path("error");
                            if (cRes.equals(nRes) && cErr.equals(nErr)) {
                                contentChanged = false;
                            }
                        } catch (Exception ignore) {
                            // Fallback to strict string equality if parsing fails
                            contentChanged = !cached.equals(newObs);
                        }
                    }

                    if (contentChanged) {
                        isLoop = false;
                        preCalculatedObs = newObs;
                        logger.info("agent.loop_detection.semantic_pass traceId={} tool={} path={}", traceId, tool, args.path("path").asText());
                    }
                } catch (Exception e) {
                    logger.warn("agent.loop_check.fail traceId={} tool={} err={}", traceId, tool, e.toString());
                }
            }

            if (isLoop) {
                if (planState != null) {
                    planState.recordAction(tool, sig, true);
                }
                String cached = signatureToObs.get(sig);
                String obs;
                if (cached != null && !cached.trim().isEmpty()) {
                    ObjectNode on = mapper.createObjectNode();
                    on.put("tool", tool);
                    on.put("signature", sig);
                    on.put("cached", true);
                    on.set("args", args == null ? MissingNode.getInstance() : args);
                    try {
                        JsonNode cachedNode = mapper.readTree(cached);
                        on.set("cachedResult", cachedNode);
                        if (cachedNode.has("result")) {
                            on.set("result", cachedNode.get("result"));
                        }
                        if (cachedNode.has("error")) {
                            on.set("error", cachedNode.get("error"));
                        }
                    } catch (Exception ex) {
                        on.put("cachedResult", StringUtils.truncate(cached, 1200));
                    }
                    on.put("hint", "Repeated call with same tool+args, cached result returned. Please use another tool or output type=final");
                    obs = on.toString();
                } else {
                    obs = mapper.createObjectNode()
                            .put("tool", tool)
                            .put("signature", sig)
                            .put("error", "repeated_tool_call")
                            .put("hint", "Do not repeat the same tool+args call. Please use another tool or output type=final")
                            .set("args", args == null ? MissingNode.getInstance() : args)
                            .toString();
                }
                logToolResult(tool, obs);
                trackEditMetrics(tool, obs);
                emitToolResultEvent(tool, toolVersion, sig, obs, i, toolCallEventId);
                updateToolState(tool, toolVersion, sig, args, obs, i, toolCallEventId);
                String obsLine = "OBS " + StringUtils.truncate(obs, MAX_OBS_CHARS);
                history.add(obsLine);
                lastObsLine = obsLine;
                if ("READ_FILE".equals(tool) && sigCount >= 3) {
                    String forced = forceFinalAnswer(goal);
                    history.add("FINAL " + StringUtils.truncate(forced, 400));
                    return forced;
                }
                if (i == MAX_TURNS - 1) {
                    logger.warn("agent.turn.last_used_by_repeat traceId={} tool={} sig={}", traceId, tool, StringUtils.truncate(sig, 300));
                    String forced = forceFinalAnswer(goal);
                    history.add("FINAL " + StringUtils.truncate(forced, 400));
                    return forced;
                }
                continue;
            }

            if (shouldBackoffTool(tool)) {
                String obs = mapper.createObjectNode()
                        .put("tool", tool)
                        .put("error", "tool_backoff")
                        .put("hint", "Recent attempts failed. Adjust args, switch tools, or provide final answer.")
                        .set("args", args == null ? MissingNode.getInstance() : args)
                        .toString();
            logToolResult(tool, obs);
            trackEditMetrics(tool, obs);
            emitToolResultEvent(tool, toolVersion, sig, obs, i, toolCallEventId);
            updateToolState(tool, toolVersion, sig, args, obs, i, toolCallEventId);
                String obsLine = "OBS " + StringUtils.truncate(obs, MAX_OBS_CHARS);
                history.add(obsLine);
                lastObsLine = obsLine;
                if (planState != null) {
                    planState.recordObservation(obs);
                }
                if (i == MAX_TURNS - 1) {
                    String forced = forceFinalAnswer(goal);
                    history.add("FINAL " + StringUtils.truncate(forced, 400));
                    return forced;
                }
                continue;
            }

            ToolExecutionContext ctx = new ToolExecutionContext(traceId, workspaceRoot, sessionRoot, mapper, fs, search, hybridSearch, indexingWorker, kafkaBootstrapServers, eventStream, runtimeService, skillManager);
            String obs;
            if (preCalculatedObs != null) {
                obs = preCalculatedObs;
            } else {
                obs = toolExecutionService.execute(tool, toolVersion, args, ctx);
            }
            logToolResult(tool, obs);
            
            if ("EDIT_FILE".equals(tool) || "CREATE_FILE".equals(tool) || "DELETE_FILE".equals(tool) || "APPLY_PATCH".equals(tool) || "BATCH_REPLACE".equals(tool) || "INSERT_LINE".equals(tool)) {
                try {
                    JsonNode obsNode = mapper.readTree(obs);
                    if (obsNode.has("result")) {
                        JsonNode res = obsNode.get("result");
                        if (res.has("preview") && res.get("preview").asBoolean()) {
                            Map<String, Object> change = mapper.convertValue(res, Map.class);
                            if (!change.containsKey("type")) {
                                if ("EDIT_FILE".equals(tool)) change.put("type", "EDIT");
                                else if ("CREATE_FILE".equals(tool)) change.put("type", "CREATE");
                                else if ("DELETE_FILE".equals(tool)) change.put("type", "DELETE");
                                else if ("INSERT_LINE".equals(tool)) change.put("type", "INSERT");
                            }
                            pendingChanges.add(change);
                        }
                    }
                } catch (Exception ignored) {}
            }

            emitToolResultEvent(tool, toolVersion, sig, obs, i, toolCallEventId);
            updateToolState(tool, toolVersion, sig, args, obs, i, toolCallEventId);
            
            boolean toolError = isToolError(obs);

            // Phase F: Real-time IDEContext Update
            if (isModificationTool(tool) && !toolError) {
                refreshIdeContext();
            }

            // Sync back state
            this.memory = ctx.memory;
            this.memoryIndexed = ctx.memoryIndexed;

            recordEvidenceSource(tool, args, obs);
            signatureToObs.put(sig, obs);
            if (toolError) {
                consecutiveToolErrors++;
                toolErrorCount++;
                lastFailedTool = tool;
            } else {
                consecutiveToolErrors = 0;
                lastFailedTool = null;
            }
            
            if ("SEARCH_KNOWLEDGE".equals(tool)) {
                String q = args.path("query").asText("");
                if (lastSearchQuery != null && lastSearchQuery.equals(q)) {
                    sameSearchQueryCount++;
                } else {
                    sameSearchQueryCount = 0;
                    lastSearchQuery = q;
                }
                if (sameSearchQueryCount >= 2) {
                    logger.warn("tool.loop traceId={} q={} sameQueryCount={}", traceId, StringUtils.truncate(q, 200), sameSearchQueryCount);
                    String loopObs = mapper.createObjectNode()
                            .put("tool", tool)
                            .put("query", q)
                            .put("error", "repeated_query")
                            .put("hint", "Too many repeated search queries. Please change keywords/use LIST_FILES/GREP/READ_FILE, or output type=final")
                            .toString();
                    logToolResult(tool, loopObs);
                    String loopObsLine = "OBS " + StringUtils.truncate(loopObs, MAX_OBS_CHARS);
                    history.add(loopObsLine);
                    lastObsLine = loopObsLine;
                    if (hasSufficientEvidence()) {
                        logger.info("agent.tool.repeat_query.force_final traceId={} q={}", traceId, StringUtils.truncate(q, 200));
                        String forced = forceFinalAnswer(goal);
                        history.add("FINAL " + StringUtils.truncate(forced, 400));
                        return forced;
                    }
                    if (i == MAX_TURNS - 1) {
                        logger.warn("agent.turn.last_used_by_repeat_query traceId={} q={}", traceId, StringUtils.truncate(q, 200));
                        String forced = forceFinalAnswer(goal);
                        history.add("FINAL " + StringUtils.truncate(forced, 400));
                        return forced;
                    }
                    continue;
                }
                
                // SOTA Phase 2: Search Efficiency Check
                // Calculate new information ratio
                double newRatio = calculateSearchEfficiency(obs);
                logger.info("agent.search.efficiency traceId={} q={} newRatio={}", traceId, StringUtils.truncate(q, 50), String.format("%.2f", newRatio));
                
                if (newRatio < 0.5) {
                    lowEfficiencySearchCount++;
                    logger.warn("agent.search.low_efficiency traceId={} count={}", traceId, lowEfficiencySearchCount);
                } else {
                    lowEfficiencySearchCount = 0; // Reset on efficient search
                }
                
                if (lowEfficiencySearchCount >= 3) {
                    logger.warn("agent.search.inefficient_stop traceId={} count={}", traceId, lowEfficiencySearchCount);
                    String stopObs = mapper.createObjectNode()
                            .put("tool", tool)
                            .put("query", q)
                            .put("error", "low_search_efficiency")
                            .put("hint", "Search efficiency is too low (new info < 50% for 3 consecutive turns). You have reached the limit of ineffective searches. Stop searching and use other tools (LIST_FILES, GREP, READ_FILE) or provide Final Answer.")
                            .toString();
                    logToolResult(tool, stopObs);
                    String stopObsLine = "OBS " + StringUtils.truncate(stopObs, MAX_OBS_CHARS);
                    history.add(stopObsLine);
                    lastObsLine = stopObsLine;
                    
                    // Relaxed policy: Allow agent to recover using other tools, do not force final answer immediately.
                    // Only if it's the very last turn, then force final answer.
                    if (i == MAX_TURNS - 1) {
                        String forced = forceFinalAnswer(goal);
                        history.add("FINAL " + StringUtils.truncate(forced, 400));
                        return forced;
                    }
                    continue;
                }
            }
            String obsLine = "OBS " + StringUtils.truncate(obs, MAX_OBS_CHARS);
            history.add(obsLine);
            lastObsLine = obsLine;
            if (planState != null) {
                planState.recordObservation(obs);
            }

            if (toolError && consecutiveToolErrors >= MAX_CONSECUTIVE_TOOL_ERRORS) {
                String errObs = mapper.createObjectNode()
                        .put("tool", tool)
                        .put("error", "too_many_tool_errors")
                        .put("count", consecutiveToolErrors)
                        .put("hint", "Too many tool errors. Switch strategy or provide final answer based on available facts.")
                        .toString();
                logToolResult(tool, errObs);
                String errLine = "OBS " + StringUtils.truncate(errObs, MAX_OBS_CHARS);
                history.add(errLine);
                lastObsLine = errLine;
                if (planState != null) {
                    planState.recordObservation(errObs);
                }
                if (hasSufficientEvidence() || i == MAX_TURNS - 1) {
                    String forced = forceFinalAnswer(goal);
                    history.add("FINAL " + StringUtils.truncate(forced, 400));
                    return forced;
                }
                continue;
            }

            if (i == MAX_TURNS - 1) {
                logger.warn("agent.turn.last_used_by_tool traceId={} tool={} sig={}", traceId, tool, StringUtils.truncate(sig, 300));
                String forced = forceFinalAnswer(goal);
                history.add("FINAL " + StringUtils.truncate(forced, 400));
                return forced;
            }
        }
        logger.warn("agent.turn.exceeded traceId={} maxTurns={}", traceId, MAX_TURNS);
        String forced = forceFinalAnswer(goal);
        history.add("FINAL " + StringUtils.truncate(forced, 400));
        return forced;
    }

    private String forceFinalAnswer(String goal) {
        try {
            // Reuse buildPrompt to get full context (Facts, IDE Context, History)
            String basePrompt = buildPrompt(goal);
            
            // Append the forcing instruction
            StringBuilder sb = new StringBuilder(basePrompt);
            sb.append("\n\nSYSTEM_INSTRUCTION: Provide a final answer now. ");
            sb.append("Do not mention tools, tool limits, system errors, or internal protocol. ");
            sb.append("Based on the 'Facts' and 'History' provided above, synthesize a Final Answer to the User Goal. ");
            sb.append("If facts are missing, admit what you don't know rather than hallucinating. ");
            sb.append("Output strictly in JSON format: {\"type\":\"final\", \"finalAnswer\":\"...\"}");

            return model.chat(sb.toString());
        } catch (Exception e) {
            return "Failed to generate final answer: " + e.getMessage();
        }
    }

    private String buildPrompt(String g) {
        StringBuilder staticSystem = new StringBuilder();
        // 1. Static System Prompt (Role & Tool Protocol)
        staticSystem.append("You are an interactive CLI tool that helps users with software engineering tasks. Use the instructions below and the tools available to you to assist the user.\n\n");
        staticSystem.append("IMPORTANT: Always respond to the user in the same language as the user's input, or as explicitly requested by the user. If the user speaks Chinese, your final answer and thought process should be in Chinese (except for code and technical terms). If the user speaks English, use English.\n\n");
        staticSystem.append(buildToolProtocolPrompt());
        staticSystem.append(buildSkillsPrompt());
        staticSystem.append(buildAutoSkillPrompt(g));

        // 2. Static Context (IDEContext & ProjectMemory) - Always included for Prefix Caching stability
        StringBuilder staticContext = new StringBuilder();
        if (ideContextContent != null && !ideContextContent.isEmpty()) {
            // Apply filtering to IDEContext to focus on relevant parts
            staticContext.append("\nIDEContext:\n").append(filterIdeContext(ideContextContent, g)).append("\n");
        }
        if (!claudeMemoryContent.isEmpty()) {
            staticContext.append("\nProjectMemory:\n").append(StringUtils.truncate(claudeMemoryContent, MAX_CLAUDE_MEMORY_CHARS)).append("\n");
        }
        if (!longTermMemoryContent.isEmpty()) {
            staticContext.append("\nLongTermMemory:\n").append(StringUtils.truncate(longTermMemoryContent, MAX_LONG_TERM_MEMORY_CHARS)).append("\n");
        }
        if (!agentsMdContent.isEmpty()) {
            staticContext.append("\nAGENTS.md (Context):\n").append(StringUtils.truncate(agentsMdContent, MAX_LONG_TERM_MEMORY_CHARS)).append("\n");
        }

        // 3. Dynamic User Goal & Facts
        StringBuilder dynamicContext = new StringBuilder();
        dynamicContext.append("\nUserGoal: ").append(g);

        if (ideContextPath != null && !ideContextPath.isEmpty()) {
            dynamicContext.append("\nProjectStructurePath: ").append(ideContextPath);
            dynamicContext.append("\n").append(IDE_OPENED_FILE_PROMPT.replace("$filename", ideContextPath));
            if (ideContextContent.isEmpty()) {
                dynamicContext.append("\nFailed to read IDE structure context. If you need to understand the architecture or class relationships, you can READ_FILE the path above.");
            } else {
                dynamicContext.append("\nIDE structure context provided (see Static Context above), ready to use.");
            }
        }

        String factsBlock = formatKnownFacts();
        if (!factsBlock.isEmpty()) {
            dynamicContext.append(factsBlock);
        }

        List<PendingChangesManager.PendingChange> pending = PendingChangesManager.getInstance().getChanges(workspaceRoot, sessionId);
        if (!pending.isEmpty()) {
            dynamicContext.append("\n[Pending Changes]\n");
            int limit = Math.min(pending.size(), 8);
            for (int i = 0; i < limit; i++) {
                PendingChangesManager.PendingChange change = pending.get(i);
                dynamicContext.append("- ").append(change.path).append(" (").append(change.type).append(")\n");
            }
            if (pending.size() > limit) {
                dynamicContext.append("- ... (").append(pending.size() - limit).append(" more)\n");
            }
        }

        if (!readFiles.isEmpty()) {
            dynamicContext.append("\n[Read Files]\n");
            for (String rf : readFiles) {
                dynamicContext.append("- ").append(rf).append("\n");
            }
        }
        if (planState != null) {
            String planBlock = planState.renderForPrompt();
            if (planBlock != null && !planBlock.isEmpty()) {
                dynamicContext.append("\nPlan:\n").append(planBlock);
            }
        }
        StringBuilder chatBlock = new StringBuilder();
        chatBlock.append("\nChatHistory (User/Assistant):\n");
        for (String h : chatHistory) {
            chatBlock.append(StringUtils.truncate(h, MAX_CHAT_LINE_CHARS)).append("\n");
            if (chatBlock.length() > MAX_CHAT_BLOCK_CHARS) {
                break;
            }
        }
        
        dynamicContext.append(chatBlock);

        String pinned = "";
        if (lastObsLine != null && !lastObsLine.trim().isEmpty()) {
            pinned = "\nPinnedObservation:\n" + StringUtils.truncate(renderObservation(lastObsLine), MAX_PINNED_CHARS) + "\n";
            logger.info("prompt.pinned traceId={} content={}", traceId, StringUtils.truncate(pinned.trim(), 200));
        }

        // Phase 2.1: Reflection Mechanism
        // Analyze history to generate dynamic advice/warnings
        StringBuilder base = new StringBuilder();
        base.append(staticSystem);
        base.append(staticContext);
        base.append(dynamicContext);
        base.append("\nHistory:\n");

        String baseText = base.toString();
        if (baseText.length() > MAX_PROMPT_CHARS) {
            baseText = StringUtils.truncate(baseText, Math.max(0, MAX_PROMPT_CHARS - pinned.length()));
        }
        int budget = MAX_PROMPT_CHARS - baseText.length() - pinned.length();
        if (budget < 0) {
            budget = 0;
        }

        StringBuilder out = new StringBuilder();
        out.append(baseText);
        int added = 0;
        int used = 0;
        for (int i = Math.max(0, history.size() - MAX_HISTORY_LINES); i < history.size(); i++) {
            String h = history.get(i);
            if (lastObsLine != null && lastObsLine.equals(h)) {
                continue;
            }
            if (h == null || h.trim().isEmpty()) {
                continue;
            }
            
            // History Compression (Render observations to Markdown)
            if (h.startsWith("OBS ")) {
                // Use compact mode for history to reduce token usage
                h = renderObservation(h, true);
                // If rendered content is still too long, truncate safely
                if (h.length() > 2000) {
                     h = h.substring(0, 2000) + "\n... (Content truncated, see [Verified Facts] or read file again)";
                }
            }

            String line = "- " + h + "\n";
            if (used + line.length() > budget) {
                break;
            }
            out.append(line);
            used += line.length();
            added++;
            if (added >= MAX_HISTORY_LINES) {
                break;
            }
        }
        if (!pinned.isEmpty() && out.length() + pinned.length() <= MAX_PROMPT_CHARS) {
            out.append(pinned);
        }
        return out.toString();
    }

    // Package-private for testing
    String filterIdeContext(String context, String goal) {
        if (context == null || context.isEmpty()) return "";
        // Phase G: Bypass filtering for Dynamic File Tree
        if (context.startsWith("Project Structure (Focused View):")) {
            return StringUtils.truncate(context, MAX_IDE_CONTEXT_CHARS);
        }
        if (goal == null || goal.trim().isEmpty()) return StringUtils.truncate(context, MAX_IDE_CONTEXT_CHARS);

        // 1. Extract simple keywords from goal
        Set<String> keywords = new HashSet<>();
        for (String w : goal.split("[\\s,;?!.\"]+")) {
            if (w.length() > 3) {
                keywords.add(w.toLowerCase());
            }
        }
        if (keywords.isEmpty()) {
            return StringUtils.truncate(context, MAX_IDE_CONTEXT_CHARS);
        }

        StringBuilder sb = new StringBuilder();
        String[] lines = context.split("\n");
        boolean inClassStructure = false;
        boolean inCallGraph = false;
        boolean currentClassMatched = false;

        for (String line : lines) {
            String trim = line.trim();
            if (trim.equals("ClassStructure:")) {
                inClassStructure = true;
                inCallGraph = false;
                sb.append(line).append("\n");
                continue;
            }
            if (trim.equals("CallGraph:")) {
                inClassStructure = false;
                inCallGraph = true;
                sb.append(line).append("\n");
                continue;
            }

            if (inClassStructure) {
                if (trim.startsWith("- ")) { 
                    // Always keep Class names as the "Map Index"
                    sb.append(line).append("\n");
                    
                    // Check if this class is relevant to the goal
                    currentClassMatched = false;
                    String className = trim.substring(2).toLowerCase();
                    // Simple check: does class name contain any keyword?
                    for (String k : keywords) {
                        if (className.contains(k)) {
                            currentClassMatched = true;
                            break;
                        }
                    }
                } else if (trim.startsWith("* ")) {
                    // Method line: Keep only if Class matched OR Method name matches keyword
                    boolean keep = currentClassMatched;
                    if (!keep) {
                        String methodName = trim.substring(2).toLowerCase();
                        for (String k : keywords) {
                            if (methodName.contains(k)) {
                                keep = true;
                                break;
                            }
                        }
                    }
                    if (keep) {
                        sb.append(line).append("\n");
                    }
                } else {
                    sb.append(line).append("\n"); // Keep other lines (e.g. empty lines)
                }
            } else if (inCallGraph) {
                // Only keep CallGraph lines that contain keywords
                boolean keep = false;
                String lower = trim.toLowerCase();
                for (String k : keywords) {
                    if (lower.contains(k)) {
                        keep = true;
                        break;
                    }
                }
                if (keep) {
                    sb.append(line).append("\n");
                }
            } else {
                sb.append(line).append("\n");
            }
        }
        
        return StringUtils.truncate(sb.toString(), MAX_IDE_CONTEXT_CHARS);
    }

    // Package-private for testing
    String generateReflection() {
        StringBuilder advice = new StringBuilder();
        int historySize = history.size();
        if (historySize < 2) return "";

        // 1. Check for recent protocol errors
        long errorCount = history.stream()
                .filter(h -> h.contains("Model output protocol error"))
                .count();
        if (errorCount > 0) {
            advice.append("- CRITICAL: You have triggered protocol errors recently. Ensure your JSON is valid and 'type' matches your intent (use 'tool' for actions, 'final' only for answers).\n");
        }

        // 2. Check for redundant READ_FILE in recent turns (last 6)
        Set<String> recentReads = new HashSet<>();
        for (int i = Math.max(0, historySize - 6); i < historySize; i++) {
            String line = history.get(i);
            if (line.startsWith("OBS ") && line.contains("READ_FILE")) {
                try {
                    String json = line.substring(4);
                    // Use simple string matching first to avoid expensive parsing if possible, 
                    // but parsing is safer for exact args.
                    if (json.contains("\"tool\":\"READ_FILE\"") || json.contains("\"tool\": \"READ_FILE\"")) {
                         JsonNode node = mapper.readTree(json);
                         String path = node.path("args").path("path").asText();
                         if (!path.isEmpty()) {
                             if (recentReads.contains(path)) {
                                 advice.append("- REFLECTION: You recently read '").append(path).append("'. Avoid reading it again unless you are targeting different lines. Use the context you already have.\n");
                             }
                             recentReads.add(path);
                         }
                    }
                } catch (Exception ignored) {}
            }
        }
        
        // 3. Check for stagnation (High turn count, low facts)
        if (historySize > 10 && knownFacts.size() < 3) {
            advice.append("- STRATEGY: You have used many turns but gathered few facts. Try using LIST_FILES to explore the directory structure or GREP to find keywords globally.\n");
        }
        if (consecutiveToolErrors >= 2) {
            advice.append("- STRATEGY: Recent tool calls failed repeatedly. Change tools or adjust arguments instead of retrying.\n");
        }
        if (toolBudgetLimit > 0 && toolCallCount >= Math.max(0, toolBudgetLimit - 2)) {
            advice.append("- STRATEGY: Tool budget is nearly exhausted. Consolidate evidence and prepare to answer.\n");
        }

        return advice.toString();
    }

    private String renderObservation(String obsLine) {
        return renderObservation(obsLine, false);
    }

    private String renderObservation(String obsLine, boolean compact) {
        if (obsLine == null || !obsLine.startsWith("OBS ")) {
            return obsLine;
        }
        String content = obsLine.substring(4);
        try {
            JsonNode node = mapper.readTree(content);
            if (!node.has("tool")) {
                return obsLine;
            }
            String tool = node.get("tool").asText();
            StringBuilder sb = new StringBuilder();
            
            if ("SEARCH_KNOWLEDGE".equals(tool)) {
                sb.append("Tool Output (SEARCH_KNOWLEDGE):\n");
                if (node.has("hint")) {
                    sb.append("Hint: ").append(node.get("hint").asText()).append("\n");
                }
                if (node.has("result") && node.get("result").has("hits")) {
                    JsonNode hits = node.get("result").get("hits");
                    if (hits.isArray()) {
                        sb.append("Hits: ").append(hits.size()).append("\n");
                        int count = 0;
                        int limit = compact ? 3 : 20;
                        for (JsonNode hit : hits) {
                            if (count++ >= limit) {
                                sb.append("... (").append(hits.size() - limit).append(" more)\n");
                                break;
                            }
                            String fp = hit.path("filePath").asText();
                            int sl = hit.path("startLine").asInt();
                            sb.append("- ").append(fp).append(":").append(sl);
                            String sn = hit.path("symbolName").asText();
                            if (!sn.isEmpty()) sb.append(" (").append(sn).append(")");
                            sb.append("\n");
                        }
                    }
                }
            } else if ("READ_FILE".equals(tool)) {
                String path = node.path("args").path("path").asText("unknown");
                sb.append("Tool Output (READ_FILE ").append(path).append("):\n");
                if (node.has("result")) {
                    JsonNode result = node.get("result");
                    String fileContent = result.path("content").asText("");
                    String error = result.path("error").asText("");
                    if (!error.isEmpty()) {
                        sb.append("Error: ").append(error);
                    } else {
                        if (compact && fileContent.length() > 500) {
                            sb.append("```\n").append(fileContent.substring(0, 500)).append("\n... (truncated for brevity, see previous full output or file view)\n```");
                        } else {
                            sb.append("```\n").append(fileContent).append("\n```");
                        }
                    }
                }
            } else if ("REPO_MAP".equals(tool)) {
                 sb.append("Tool Output (REPO_MAP):\n");
                 if (node.has("result")) {
                     JsonNode result = node.get("result");
                     String mapContent = result.path("content").asText("");
                     String error = result.path("error").asText("");
                     if (!error.isEmpty()) {
                         sb.append("Error: ").append(error);
                     } else {
                         if (compact && mapContent.length() > 500) {
                             sb.append("```\n").append(mapContent.substring(0, 500)).append("\n... (truncated)\n```");
                         } else {
                             sb.append("```\n").append(mapContent).append("\n```");
                         }
                     }
                 }
            } else if ("LIST_FILES".equals(tool)) {
                String path = node.path("args").path("path").asText("root");
                sb.append("Tool Output (LIST_FILES ").append(path).append("):\n");
                if (node.has("result")) {
                     JsonNode files = node.get("result").path("files");
                     if (files.isArray()) {
                         int count = 0;
                         int limit = compact ? 10 : 200;
                         for (JsonNode f : files) {
                             if (count++ >= limit) {
                                 sb.append("... (").append(files.size() - limit).append(" more)\n");
                                 break;
                             }
                             sb.append("- ").append(f.asText()).append("\n");
                         }
                     }
                }
            } else if ("GREP".equals(tool)) {
                String pattern = node.path("args").path("pattern").asText();
                sb.append("Tool Output (GREP \"").append(pattern).append("\"):\n");
                if (node.has("result")) {
                    JsonNode matches = node.get("result").path("matches");
                     if (matches.isArray()) {
                         int count = 0;
                         int limit = compact ? 5 : 50;
                         for (JsonNode m : matches) {
                             if (count++ >= limit) {
                                 sb.append("... (").append(matches.size() - limit).append(" more)\n");
                                 break;
                             }
                             sb.append("- ").append(m.path("filePath").asText())
                               .append(":").append(m.path("lineNumber").asText())
                               .append(" ").append(m.path("lineContent").asText().trim())
                               .append("\n");
                         }
                     }
                }
            } else if ("EDIT_FILE".equals(tool) || "CREATE_FILE".equals(tool) || "DELETE_FILE".equals(tool) ||
                    "INSERT_LINE".equals(tool) || "APPLY_PATCH".equals(tool) || "BATCH_REPLACE".equals(tool) ||
                    "REPLACE_LINES".equals(tool) || "MOVE_PATH".equals(tool) || "CREATE_DIRECTORY".equals(tool) ||
                    "APPLY_PENDING_DIFF".equals(tool)) {
                String path = node.path("result").path("filePath").asText("");
                if (path.isEmpty()) {
                    path = node.path("args").path("path").asText("");
                }
                if (path.isEmpty()) {
                    path = node.path("args").path("sourcePath").asText("");
                }
                if (path.isEmpty()) {
                    path = "unknown";
                }
                String err = node.path("result").path("error").asText("");
                if (err.isEmpty()) {
                    err = node.path("error").asText("");
                }
                boolean preview = node.path("result").path("preview").asBoolean(false);
                sb.append("Tool Output (").append(tool).append(" ").append(path).append("): ");
                if (!err.isEmpty()) {
                    sb.append("Error: ").append(err);
                } else if ("APPLY_PENDING_DIFF".equals(tool)) {
                    boolean rejected = node.path("result").path("rejected").asBoolean(false);
                    JsonNode applied = node.path("applied");
                    int count = applied.isArray() ? applied.size() : 0;
                    sb.append(rejected ? "Rejected pending changes" : "Applied pending changes");
                    if (count > 0) {
                        sb.append(" (").append(count).append(" file");
                        if (count != 1) sb.append("s");
                        sb.append(")");
                    }
                } else {
                    sb.append(preview ? "Preview staged (pending apply)" : "Applied");
                }
            } else {
                // Default fallback for other tools
                if (compact && content.length() > 500) {
                    sb.append("Tool Output (").append(tool).append("): ").append(content.substring(0, 500)).append("...");
                } else {
                    sb.append("Tool Output (").append(tool).append("): ").append(content);
                }
            }
            
            return sb.toString();
        } catch (Exception e) {
            return obsLine; // Fallback to raw if parsing fails
        }
    }

    private boolean shouldBackoffTool(String tool) {
        if (tool == null || tool.trim().isEmpty()) {
            return false;
        }
        if (consecutiveToolErrors < TOOL_BACKOFF_THRESHOLD) {
            return false;
        }
        return tool.equals(lastFailedTool);
    }

    private boolean isToolError(String obs) {
        if (obs == null || obs.trim().isEmpty()) {
            return true;
        }
        try {
            JsonNode node = mapper.readTree(obs);
            String status = node.path("status").asText("");
            if ("error".equalsIgnoreCase(status)) {
                return true;
            }
            String topError = node.path("error").asText("");
            if (topError != null && !topError.trim().isEmpty()) {
                return true;
            }
            JsonNode result = node.path("result");
            String resultError = result.path("error").asText("");
            if (resultError != null && !resultError.trim().isEmpty()) {
                return true;
            }
            if (result.has("success") && !result.path("success").asBoolean(true)) {
                return true;
            }
            return false;
        } catch (Exception e) {
            return obs.contains("\"error\"");
        }
    }

    private void trackEditMetrics(String tool, String obs) {
        if (tool == null || !"APPLY_PENDING_DIFF".equals(tool)) {
            return;
        }
        if (obs == null || obs.trim().isEmpty()) {
            return;
        }
        try {
            JsonNode node = mapper.readTree(obs);
            JsonNode applied = node.path("applied");
            int count = applied.isArray() ? applied.size() : 0;
            boolean rejected = node.path("result").path("rejected").asBoolean(false);
            if (count <= 0) {
                return;
            }
            if (rejected) {
                editsRejected += count;
            } else {
                editsApplied += count;
            }
        } catch (Exception ignored) {
        }
    }

    private double calculateSearchEfficiency(String obs) {
        try {
            JsonNode node = mapper.readTree(obs);
            JsonNode result = node.path("result");
            JsonNode hits = result.path("hits");
            if (!hits.isArray() || hits.isEmpty()) {
                return 0.0; // No hits is 0 efficiency
            }
            int total = hits.size();
            int newCount = 0;
            for (JsonNode hit : hits) {
                String fp = hit.path("filePath").asText("");
                String sn = hit.path("symbolName").asText("");
                int sl = hit.path("startLine").asInt(0);
                // Signature: filePath:startLine:symbolName
                String sig = fp + ":" + sl + ":" + sn;
                if (!globalSearchHits.contains(sig)) {
                    newCount++;
                    globalSearchHits.add(sig);
                }
            }
            return (double) newCount / total;
        } catch (Exception e) {
            return 1.0; // Assume efficient on error to avoid false positives
        }
    }

    private String executeHybridSearch(JsonNode args) {
        String query = args.path("query").asText("");
        if (query.isEmpty()) return "{\"error\":\"query is empty\"}";
        int topK = args.path("topK").asInt(10);
        long t0 = System.nanoTime();
        try {
            logger.info("agent.tool.exec traceId={} tool=SEARCH_KNOWLEDGE q={} topK={}", traceId, sanitizeLogText(query, 200), topK);
            // Use Unified HybridCodeSearchService
            List<com.zzf.codeagent.core.rag.search.CodeSearchHit> hits = hybridSearch.search(workspaceRoot, query, topK);
            
            // Format result JSON
            ObjectNode res = mapper.createObjectNode();
            // Existing format is: {"result": {"hits": [...]}}
            
            List<ObjectNode> hitNodes = new ArrayList<>();
            for (com.zzf.codeagent.core.rag.search.CodeSearchHit h : hits) {
                ObjectNode hn = mapper.createObjectNode();
                hn.put("filePath", h.getFilePath());
                hn.put("startLine", h.getStartLine());
                hn.put("symbolName", h.getSymbolName());
                hn.put("score", 0.0);
                hn.put("snippet", h.getSnippet()); // Snippet is now hydrated!
                hitNodes.add(hn);
            }
            
            ObjectNode resultObj = mapper.createObjectNode();
            resultObj.putPOJO("hits", hitNodes);
            
            ObjectNode root = mapper.createObjectNode();
            root.set("result", resultObj);
            logger.info("agent.tool.exec.result traceId={} tool=SEARCH_KNOWLEDGE hits={} error={} tookMs={}", traceId, hits.size(), "", (System.nanoTime() - t0) / 1_000_000L);
            return root.toString();
            
        } catch (Exception e) {
            logger.info("agent.tool.exec.result traceId={} tool=SEARCH_KNOWLEDGE hits=0 error={} tookMs={}", traceId, sanitizeLogText(e.getMessage(), 200), (System.nanoTime() - t0) / 1_000_000L);
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    // Phase F: Real-time Context Refresh
    private void refreshIdeContext() {
        if (workspaceRoot != null && !workspaceRoot.isEmpty()) {
             try {
                 DynamicContextBuilder contextBuilder = new DynamicContextBuilder(workspaceRoot);
                 List<String> activeFiles = new ArrayList<>();
                 if (ideContextPath != null && !ideContextPath.isEmpty() && !ideContextPath.equals(workspaceRoot)) {
                     activeFiles.add(ideContextPath);
                 }
                 this.ideContextContent = contextBuilder.build(activeFiles);
                 this.ideContextLoaded = true;
                 logger.info("agent.context.dynamic.refresh traceId={} chars={}", traceId, ideContextContent.length());
             } catch (Exception e) {
                 logger.warn("agent.context.dynamic.fail err={}", e.toString());
                 // Fallback: if dynamic build fails, try to reload from file if it was a file path
                 this.ideContextLoaded = false;
                 ensureIdeContextLoaded();
             }
        } else {
             ensureIdeContextLoaded();
        }
    }

    private boolean isModificationTool(String tool) {
        return "EDIT_FILE".equals(tool) || "CREATE_FILE".equals(tool) || 
               "DELETE_FILE".equals(tool) || "APPLY_PATCH".equals(tool) || 
               "BATCH_REPLACE".equals(tool) || "INSERT_LINE".equals(tool) ||
               "WRITE_FILE".equals(tool) || "MOVE_FILE".equals(tool) || 
               "COPY_FILE".equals(tool) || "REPLACE_IN_FILE".equals(tool);
    }

    private void ensureIdeContextLoaded() {
        if (ideContextLoaded) {
            return;
        }
        ideContextLoaded = true;
        if (ideContextPath == null || ideContextPath.trim().isEmpty()) {
            return;
        }
        FileSystemToolService.ReadFileResult r = fs.readFile(ideContextPath, 1, 800, MAX_IDE_CONTEXT_CHARS);
        if (r != null && (r.error == null || r.error.trim().isEmpty())) {
            String c = r.content == null ? "" : r.content.trim();
            if (!c.isEmpty()) {
                ideContextContent = c;
                hasEvidence = true;
                evidenceSources.add("IDE_CONTEXT:" + ideContextPath);
                putFact("IDEContext", "Loaded " + ideContextPath + " (full content provided in System Prompt prefix)");
            }
        }
    }

    private void ensureClaudeMemoryLoaded() {
        if (claudeMemoryLoaded) {
            return;
        }
        claudeMemoryLoaded = true;
        if (workspaceRoot == null || workspaceRoot.trim().isEmpty()) {
            return;
        }
        String root = workspaceRoot.trim();
        StringBuilder sb = new StringBuilder();
        String[] candidates = new String[] { "CLAUDE.md", "CLAUDE.local.md" };
        for (String name : candidates) {
            try {
                Path p = Paths.get(root, name);
                FileSystemToolService.ReadFileResult r = fs.readFile(p.toString(), 1, 400, MAX_CLAUDE_MEMORY_CHARS);
                if (r != null && (r.error == null || r.error.trim().isEmpty())) {
                    String c = r.content == null ? "" : r.content.trim();
                    if (!c.isEmpty()) {
                        if (sb.length() > 0) {
                            sb.append("\n\n");
                        }
                        sb.append("[").append(name).append("]\n").append(c);
                        logger.info("agent.memory.loaded traceId={} file={}", traceId, name);
                    }
                }
            } catch (Exception e) {
                logger.warn("agent.memory.load_fail traceId={} file={} err={}", traceId, name, e.toString());
            }
        }
        claudeMemoryContent = sb.toString().trim();
        if (!claudeMemoryContent.isEmpty()) {
            hasEvidence = true;
            evidenceSources.add("PROJECT_MEMORY");
        }
    }

    private void ensureLongTermMemoryLoaded() {
        if (longTermMemoryLoaded) {
            return;
        }
        longTermMemoryLoaded = true;
        if (workspaceRoot == null || workspaceRoot.trim().isEmpty()) {
            return;
        }
        String root = workspaceRoot.trim();
        StringBuilder sb = new StringBuilder();
        String[] candidates = new String[] { "LONG_TERM_MEMORY.md", "LONG_TERM_MEMORY.local.md", ".codeagent/long_term_memory.md" };
        for (String name : candidates) {
            try {
                Path p = Paths.get(root, name);
                FileSystemToolService.ReadFileResult r = fs.readFile(p.toString(), 1, 600, MAX_LONG_TERM_MEMORY_CHARS);
                if (r != null && (r.error == null || r.error.trim().isEmpty())) {
                    String c = r.content == null ? "" : r.content.trim();
                    if (!c.isEmpty()) {
                        if (sb.length() > 0) {
                            sb.append("\n\n");
                        }
                        sb.append("[").append(name).append("]\n").append(c);
                        logger.info("agent.memory.loaded traceId={} file={}", traceId, name);
                    }
                }
            } catch (Exception e) {
                logger.warn("agent.memory.load_fail traceId={} file={} err={}", traceId, name, e.toString());
            }
        }
        longTermMemoryContent = sb.toString().trim();
        if (!longTermMemoryContent.isEmpty()) {
            hasEvidence = true;
            evidenceSources.add("LONG_TERM_MEMORY");
            putFact("LongTermMemory", "Loaded long term memory files (content provided in System Prompt prefix)");
        }
    }

    private void ensureAgentsMdLoaded() {
        if (agentsMdLoaded) {
            return;
        }
        agentsMdLoaded = true;
        if (workspaceRoot == null || workspaceRoot.trim().isEmpty()) {
            return;
        }
        String root = workspaceRoot.trim();
        String name = "AGENTS.md";
        try {
            Path p = Paths.get(root, name);
            FileSystemToolService.ReadFileResult r = fs.readFile(p.toString(), 1, 600, MAX_LONG_TERM_MEMORY_CHARS);
            if (r != null && (r.error == null || r.error.trim().isEmpty())) {
                String c = r.content == null ? "" : r.content.trim();
                if (!c.isEmpty()) {
                    agentsMdContent = c;
                    logger.info("agent.memory.loaded traceId={} file={}", traceId, name);
                    hasEvidence = true;
                    evidenceSources.add("AGENTS_MD");
                    putFact("AGENTS.md", "Loaded AGENTS.md (contains skill context)");
                }
            }
        } catch (Exception e) {
            logger.warn("agent.memory.load_fail traceId={} file={} err={}", traceId, name, e.toString());
        }
    }

    private void captureFacts(JsonNode node, boolean required) {
        JsonNode facts = node.path("facts");
        if (facts == null || !facts.isObject()) {
            if (required) {
                logger.warn("llm.protocol_violation traceId={} reason=missing_facts", traceId);
            }
            return;
        }
        facts.fields().forEachRemaining(entry -> {
            String k = entry.getKey() == null ? "" : entry.getKey().trim();
            String v = entry.getValue() == null ? "" : entry.getValue().asText("").trim();
            if (!k.isEmpty() && !v.isEmpty()) {
                putFact(k, v);
                if (!hasTodoFact) {
                    String lk = k.toLowerCase();
                    if (lk.contains("todo") || lk.contains("done") || k.contains("待办") || k.contains("已完成") || k.contains("进度")) {
                        hasTodoFact = true;
                    }
                }
            }
        });
    }

    private String buildToolProtocolPrompt() {
        StringBuilder sb = new StringBuilder();
        // SOTA Optimization: Condensed instructions, strict JSON enforcement, hidden thought.
        sb.append("Role: Expert Software Engineer. Goal: Solve user task using available tools. ");
        sb.append("Protocol: ");
        sb.append("1. Evidence: Use the most direct tool. If IDEContext already shows relevant files, skip REPO_MAP/STRUCTURE_MAP and READ_FILE directly. ");
        sb.append("2. Search: If SEARCH_KNOWLEDGE hits=0, fallback to LIST_FILES -> GREP. Stop searching if inefficient (<50% new info). ");
        sb.append("3. No Redundancy: Avoid reading the same file range repeatedly (paging is OK). ");
        sb.append("4. Constraints: Prioritize ProjectMemory/LongTermMemory/IDEContext when relevant. ");
        sb.append("5. Facts: Only record facts supported by tool output or explicit user input. Previewed edits are pending. Do NOT claim changes are applied unless APPLY_PENDING_DIFF succeeded. ");
        sb.append("6. Edits: Use preview/dry-run for file changes when available. Do NOT ask the user to confirm in chat; rely on UI confirmation or call APPLY_PENDING_DIFF only if the user explicitly authorizes apply. ");
        sb.append("7. Output: STRICT JSON only. Do not include markdown outside JSON. finalAnswer may include markdown/code fences. ");
        sb.append("8. FinalAnswer must be user-facing and omit tool/system details. ");
        sb.append("9. Thought: Provide a brief, non-sensitive, one-sentence thought in the user's language. ");
        sb.append("10. Code Output: Show only core logic or changed lines unless the user requests the full file. ");
        
        String toolList = buildToolListLine();
        if (!toolList.isEmpty()) {
            sb.append("\nTools: ").append(toolList);
        }

        String specLine = buildToolSpecLine();
        if (!specLine.isEmpty()) {
            sb.append("\nTool Versions: ").append(specLine);
        }
        if (!isRunCommandEnabled()) {
            sb.append("\nNote: RUN_COMMAND is currently disabled for safety.");
        }

        sb.append("\nFormat: {\"thought\":\"One short sentence (required, no sensitive details)\",\"type\":\"tool\"|\"final\",\"tool\":\"NAME\",\"version\":\"v1\",\"args\":{...},\"facts\":{\"Title\":\"Detail\"},\"finalAnswer\":\"...\"}");
        return sb.toString();
    }

    private String buildSkillsPrompt() {
        // If AGENTS.md defines the skills system, don't duplicate it.
        if (agentsMdContent != null && (agentsMdContent.contains("<skills_system>") || agentsMdContent.contains("<available_skills>"))) {
            return ""; 
        }

        if (skillManager == null) return "";
        try {
            List<Skill> skills = skillManager.listSkills(workspaceRoot);
            if (skills.isEmpty()) return "";

            StringBuilder sb = new StringBuilder();
            sb.append("\n\n").append(SKILL_SYSTEM_PROMPT);
            for (Skill skill : skills) {
                sb.append("- ").append(skill.getName()).append(": ").append(skill.getDescription()).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            logger.warn("Failed to list skills", e);
            return "";
        }
    }

    private String buildAutoSkillPrompt(String goal) {
        if (agentsMdContent != null && (agentsMdContent.contains("<skills_system>") || agentsMdContent.contains("<available_skills>"))) {
            return "";
        }
        if (skillManager == null) return "";

        List<Skill> skills;
        try {
            skills = skillManager.listSkills(workspaceRoot);
        } catch (Exception e) {
            logger.warn("Failed to list skills for auto-load", e);
            return "";
        }
        if (skills == null || skills.isEmpty()) return "";

        String triggerText = buildSkillTriggerText(goal);
        List<String> selected = SkillSelector.selectSkillNames(triggerText, skills, MAX_AUTO_SKILLS);
        if (selected.isEmpty()) return "";

        Map<String, Skill> skillMap = new HashMap<>();
        for (Skill s : skills) {
            if (s != null && s.getName() != null) {
                skillMap.put(s.getName().toLowerCase(), s);
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n\nAuto-loaded skills (use these instructions with priority if relevant):\n");
        autoSkillsLoaded.clear();
        for (String name : selected) {
            Skill s = skillMap.get(name.toLowerCase());
            if (s == null) continue;
            autoSkillsLoaded.add(s.getName());
            sb.append("- ").append(s.getName()).append(": ").append(s.getDescription() == null ? "" : s.getDescription()).append("\n");
            sb.append(StringUtils.truncate(s.getContent() == null ? "" : s.getContent(), MAX_SKILL_CONTENT_CHARS)).append("\n");
        }
        logger.info("skills.auto_load traceId={} names={}", traceId, selected);
        return sb.toString();
    }

    private String buildSkillTriggerText(String goal) {
        StringBuilder sb = new StringBuilder();
        if (goal != null && !goal.trim().isEmpty()) {
            sb.append(goal).append(" ");
        }
        int start = Math.max(0, chatHistory.size() - 4);
        for (int i = start; i < chatHistory.size(); i++) {
            String line = chatHistory.get(i);
            if (line == null) continue;
            sb.append(line).append(" ");
        }
        return sb.toString();
    }

    private boolean isRunCommandEnabled() {
        if (toolExecutionService == null) {
            return false;
        }
        return toolExecutionService.isRunCommandEnabled();
    }

    private boolean isToolAllowed(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        if ("RUN_COMMAND".equalsIgnoreCase(name)) {
            return isRunCommandEnabled();
        }
        return true;
    }

    private String buildToolListLine() {
        if (toolExecutionService == null) {
            return "";
        }
        List<ToolProtocol.ToolSpec> specs = toolExecutionService.listToolSpecs();
        if (specs == null || specs.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ToolProtocol.ToolSpec spec : specs) {
            if (spec == null || spec.getName().isEmpty()) {
                continue;
            }
            String name = spec.getName();
            if (!isToolAllowed(name)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append(name);
        }
        return sb.toString();
    }

    private String buildToolSpecLine() {
        if (toolExecutionService == null) {
            return "";
        }
        List<ToolProtocol.ToolSpec> specs = toolExecutionService.listToolSpecs();
        if (specs == null || specs.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ToolProtocol.ToolSpec spec : specs) {
            if (spec == null || spec.getName().isEmpty()) {
                continue;
            }
            if (!isToolAllowed(spec.getName())) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append(spec.getName()).append("@").append(spec.getVersion());
        }
        return sb.toString();
    }

    private String formatKnownFacts() {
        if (knownFacts.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[Verified Facts]\n");
        List<String> keys = new ArrayList<>(knownFacts.keySet());
        keys.sort((a, b) -> {
            int pa = factPriority.getOrDefault(a, 0);
            int pb = factPriority.getOrDefault(b, 0);
            if (pa != pb) {
                return Integer.compare(pb, pa);
            }
            long sa = factSeq.getOrDefault(a, 0L);
            long sbv = factSeq.getOrDefault(b, 0L);
            return Long.compare(sbv, sa);
        });
        for (String k : keys) {
            String v = knownFacts.get(k);
            sb.append("- ").append(k).append(": ").append(v).append("\n");
            if (sb.length() >= MAX_FACTS_BLOCK_CHARS) {
                break;
            }
        }
        sb.append("\n");
        return StringUtils.truncate(sb.toString(), MAX_FACTS_BLOCK_CHARS);
    }

    private void putFact(String key, String value) {
        String trimmed = StringUtils.truncate(value, 600);
        String fingerprint = normalizeFingerprint(trimmed);
        if (fingerprint.isEmpty()) {
            return;
        }
        String existingKey = factFingerprints.get(fingerprint);
        if (existingKey != null && !existingKey.equals(key)) {
            return;
        }
        knownFacts.put(key, trimmed);
        factFingerprints.put(fingerprint, key);
        int priority = estimateFactPriority(key, trimmed);
        factPriority.put(key, priority);
        factSeq.put(key, ++factSeqCounter);
        trimFacts();
    }

    private void trimFacts() {
        if (knownFacts.size() <= MAX_FACTS) {
            return;
        }
        List<String> keys = new ArrayList<>(knownFacts.keySet());
        keys.sort((a, b) -> {
            int pa = factPriority.getOrDefault(a, 0);
            int pb = factPriority.getOrDefault(b, 0);
            if (pa != pb) {
                return Integer.compare(pa, pb);
            }
            long sa = factSeq.getOrDefault(a, 0L);
            long sbv = factSeq.getOrDefault(b, 0L);
            return Long.compare(sa, sbv);
        });
        int drop = knownFacts.size() - MAX_FACTS;
        if (drop > 0) {
            logger.info("agent.facts.trim traceId={} dropCount={}", traceId, drop);
        }
        for (int i = 0; i < drop && i < keys.size(); i++) {
            String k = keys.get(i);
            knownFacts.remove(k);
            factPriority.remove(k);
            Long seq = factSeq.remove(k);
            if (seq != null) {
                String v = factFingerprints.entrySet().stream()
                        .filter(e -> e.getValue().equals(k))
                        .map(Map.Entry::getKey)
                        .findFirst()
                        .orElse(null);
                if (v != null) {
                    factFingerprints.remove(v);
                }
            }
        }
    }

    private String normalizeFingerprint(String value) {
        if (value == null) {
            return "";
        }
        String s = value.trim().toLowerCase();
        if (s.length() < 6) {
            return "";
        }
        return s.replaceAll("\\s+", " ");
    }

    private int estimateFactPriority(String key, String value) {
        String text = (key + " " + value).toLowerCase();
        int score = 1;
        if (key.equalsIgnoreCase("Plan") || key.contains("计划") || key.contains("Step")) {
            score += 5;
        }
        if (text.contains(".java") || text.contains(".kt") || text.contains(".xml") || text.contains("/")) {
            score += 2;
        }
        if (text.contains("error") || text.contains("exception") || text.contains("失败")) {
            score += 1;
        }
        return score;
    }

    private void recordEvidenceSource(String tool, JsonNode args, String obs) {
        if (tool == null || tool.trim().isEmpty()) {
            return;
        }
        String src = tool;
        if ("READ_FILE".equals(tool)) {
            src = tool + ":" + args.path("path").asText("");
        } else if ("LIST_FILES".equals(tool)) {
            src = tool + ":" + args.path("path").asText("");
        } else if ("GREP".equals(tool)) {
            src = tool + ":" + args.path("root").asText("");
        } else if ("SEARCH_KNOWLEDGE".equals(tool)) {
            src = tool + ":" + args.path("query").asText("");
        } else if ("TRIGGER_INDEX".equals(tool)) {
            src = tool + ":" + args.path("mode").asText("kafka");
        }
        if (src.length() > 300) {
            src = src.substring(0, 300);
        }
        evidenceSources.add(src);
        if (evidenceSources.size() > 20) {
            List<String> list = new ArrayList<>(evidenceSources);
            evidenceSources.clear();
            for (int i = Math.max(0, list.size() - 20); i < list.size(); i++) {
                evidenceSources.add(list.get(i));
            }
        }
        updateEvidenceFromObs(tool, obs);
    }

    private void updateEvidenceFromObs(String tool, String obs) {
        if (obs == null || obs.trim().isEmpty()) {
            return;
        }
        try {
            JsonNode node = mapper.readTree(obs);
            JsonNode result = node.path("result");
            if ("READ_FILE".equals(tool)) {
                String content = result.path("content").asText("");
                String error = result.path("error").asText("");
                if (!content.trim().isEmpty() && error.trim().isEmpty()) {
                    hasEvidence = true;
                    hasCodeEvidence = true;
                }
                return;
            }
            if ("GREP".equals(tool)) {
                JsonNode matches = result.path("matches");
                String error = result.path("error").asText("");
                if (matches.isArray() && matches.size() > 0 && error.trim().isEmpty()) {
                    hasEvidence = true;
                    hasCodeEvidence = true;
                }
                return;
            }
            if ("SEARCH_KNOWLEDGE".equals(tool)) {
                int hits = node.path("hits").asInt(0);
                String error = node.path("error").asText("");
                if (hits > 0 && error.trim().isEmpty()) {
                    hasEvidence = true;
                    addFactsFromSearchHits(result);
                }
                return;
            }
            if ("LIST_FILES".equals(tool)) {
                JsonNode files = result.path("files");
                String error = result.path("error").asText("");
                if (files.isArray() && files.size() > 0 && error.trim().isEmpty()) {
                    hasEvidence = true;
                }
                return;
            }
            if ("TRIGGER_INDEX".equals(tool)) {
                int total = node.path("totalFiles").asInt(0);
                String error = node.path("error").asText("");
                if (total > 0 && error.trim().isEmpty()) {
                    hasEvidence = true;
                }
            }
        } catch (Exception ignore) {
            hasEvidence = hasEvidence || (obs.length() > 0);
        }
    }

    private boolean hasSufficientEvidence() {
        if (hasCodeEvidence) {
            return true;
        }
        if (hasEvidence && !knownFacts.isEmpty()) {
            return true;
        }
        return false;
    }

    private void addFactsFromSearchHits(JsonNode result) {
        if (result == null || !result.has("hits")) {
            return;
        }
        JsonNode hits = result.path("hits");
        if (!hits.isArray()) {
            return;
        }
        int limit = Math.min(5, hits.size());
        for (int i = 0; i < limit; i++) {
            JsonNode hit = hits.get(i);
            if (hit == null || hit.isNull()) {
                continue;
            }
            String filePath = hit.path("filePath").asText("");
            String symbolKind = hit.path("symbolKind").asText("");
            String symbolName = hit.path("symbolName").asText("");
            if (filePath.isEmpty() && symbolName.isEmpty()) {
                continue;
            }
            StringBuilder sb = new StringBuilder();
            if (!filePath.isEmpty()) {
                sb.append(filePath);
            }
            if (!symbolName.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append(" ");
                }
                if (!symbolKind.isEmpty()) {
                    sb.append(symbolKind).append(":");
                }
                sb.append(symbolName);
            }
            String summary = StringUtils.truncate(sb.toString(), 300);
            if (!summary.isEmpty()) {
                putFact("Hit" + (i + 1), summary);
            }
        }
    }

    private String finalizeAnswer(JsonNode node, String rawAnswer) {
        String answer = rawAnswer == null ? "" : rawAnswer.trim();
        if (answer.isEmpty()) {
            answer = "Final answer not generated.";
        }
        String thought = node.path("thought").asText("");
        // Relaxed protocol check: Allow extra fields if type is final.
        // if (node.hasNonNull("tool") || node.hasNonNull("args")) { ... }
        String prefix = buildEvidencePrefix(answer);
        if (!prefix.isEmpty()) {
            answer = prefix + answer;
        }
        return answer;
    }

    private String buildEvidencePrefix(String answer) {
        if (!hasEvidence && !hasCodeEvidence && evidenceSources.isEmpty()) {
            return "";
        }
        boolean hasCode = hasCodeEvidence || evidenceSources.stream().anyMatch(s -> s.startsWith("READ_FILE") || s.startsWith("GREP"));
        boolean hasSearch = evidenceSources.stream().anyMatch(s -> s.startsWith("SEARCH_KNOWLEDGE"));
        boolean hasMemory = evidenceSources.contains("PROJECT_MEMORY") || evidenceSources.contains("LONG_TERM_MEMORY") || evidenceSources.stream().anyMatch(s -> s.startsWith("IDE_CONTEXT"));
        boolean chinese = containsCjk(answer);
        if (hasCode) {
            return chinese ? "基于代码证据，" : "Based on code evidence, ";
        }
        if (hasSearch) {
            return chinese ? "基于检索结果，" : "Based on retrieved results, ";
        }
        if (hasMemory) {
            return chinese ? "基于项目记忆，" : "Based on project memory, ";
        }
        return chinese ? "基于当前上下文，" : "Based on available context, ";
    }

    private boolean containsCjk(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) {
                return true;
            }
        }
        return false;
    }

    private String formatEvidenceSources() {
        if (evidenceSources.isEmpty()) {
            return "Evidence sources: None";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Evidence sources: ");
        int count = 0;
        for (String s : evidenceSources) {
            if (count > 0) {
                sb.append("; ");
            }
            sb.append(s);
            count++;
            if (sb.length() >= 600) {
                break;
            }
        }
        return sb.toString();
    }

    private void logToolRequest(String tool, JsonNode args) {
        String argsText = args == null ? "" : args.toString();
        logger.info("agent.tool.call traceId={} tool={} args={}", traceId, tool, sanitizeLogText(argsText, 24000));
    }

    private void logToolResult(String tool, String obs) {
        String err = extractErrorField(obs);
        logger.info("agent.tool.result traceId={} tool={} error={} obsChars={}", traceId, tool, sanitizeLogText(err, 200), obs == null ? 0 : obs.length());
        logger.info("agent.tool.obs traceId={} tool={} obs={}", traceId, tool, sanitizeLogText(obs, 24000));
    }

    private String extractErrorField(String obs) {
        if (obs == null || obs.trim().isEmpty()) {
            return "";
        }
        try {
            JsonNode node = mapper.readTree(obs);
            String top = node.path("error").asText("");
            if (top != null && !top.trim().isEmpty()) {
                return top;
            }
            JsonNode result = node.path("result");
            return result.path("error").asText("");
        } catch (Exception e) {
            return "";
        }
    }

    private String sanitizeLogText(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        String out = text;
        out = SENSITIVE_JSON_KV.matcher(out).replaceAll("$1******$3");
        out = SENSITIVE_KV.matcher(out).replaceAll("$1:******");
        if (out.length() > maxChars) {
            out = out.substring(0, maxChars);
        }
        return out;
    }

    private void logModelRequest(String stage, String prompt) {
        try {
            ObjectNode node = mapper.createObjectNode();
            node.put("traceId", traceId);
            node.put("stage", stage);
            node.put("promptChars", prompt == null ? 0 : prompt.length());
            node.put("prompt", sanitizeLogText(prompt, 24000));
            logger.info("llm.request {}", node.toString());
        } catch (Exception e) {
            logger.info("llm.request traceId={} stage={} promptChars={} err={}", traceId, stage, prompt == null ? 0 : prompt.length(), e.toString());
        }
    }

    private JsonNode parseWithRetry(String prompt, String raw) {
        int retries = 0;
        final int maxRetries = 2;
        String currentRaw = raw;
        while (retries <= maxRetries) {
            try {
                return parseJsonFromRaw(currentRaw);
            } catch (Exception e) {
                if (retries == maxRetries) {
                    logger.warn("llm.parse.fail traceId={} final_attempt err={} raw={}", traceId, e.toString(), StringUtils.truncate(currentRaw, 2000));
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "model output not json after retries");
                }
                logger.warn("llm.parse.fail traceId={} retry={} err={} raw={}", traceId, retries, e.toString(), StringUtils.truncate(currentRaw, 2000));
                String retryPrompt = prompt + "\n\nYour output does not match JSON format. Error:" + e.getMessage()
                        + "\nPlease re-output pure JSON (no markdown, no explanatory text, just a JSON object).";
                currentRaw = model.chat(retryPrompt);
                logger.info("llm.retry.raw traceId={} raw={}", traceId, StringUtils.truncate(currentRaw, 2000));
                retries++;
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "model output not json");
    }

    private JsonNode parseJsonFromRaw(String raw) throws Exception {
        String candidate = JsonUtils.extractFirstJsonObject(raw);
        logger.info("llm.json.candidate traceId={} chars={}", traceId, candidate == null ? 0 : candidate.length());
        return mapper.readTree(candidate);
    }

    public static TopicResult detectNewTopic(OpenAiChatModel model, String goal, ObjectMapper mapper) {
        if (goal == null || goal.trim().isEmpty()) {
            return TopicResult.empty();
        }
        try {
            String prompt = CHECK_NEW_TOPIC_PROMPT + "\n\nMessage:\n" + StringUtils.truncate(goal, 1000);
            logger.info("topic.detect.request goalChars={} promptChars={}", goal.length(), prompt.length());
            String raw = model.chat(prompt);
            logger.info("topic.detect.raw chars={} raw={}", raw == null ? 0 : raw.length(), StringUtils.truncate(raw, 800));
            String json = JsonUtils.extractFirstJsonObject(raw);
            JsonNode node = mapper.readTree(json);
            boolean isNew = node.path("isNewTopic").asBoolean(false);
            String title = node.path("title").asText(null);
            logger.info("topic.detect.parsed isNewTopic={} title={}", isNew, StringUtils.truncate(title, 60));
            return new TopicResult(isNew, title);
        } catch (Exception e) {
            logger.warn("detectNewTopic failed err={}", e.toString());
            return TopicResult.empty();
        }
    }

    private static String toolSignature(String tool, String version, JsonNode args) {
        String t = tool == null ? "" : tool.trim();
        String v = version == null || version.isBlank() ? ToolProtocol.DEFAULT_VERSION : version.trim();
        String head = (t.isEmpty() ? "UNKNOWN_TOOL" : t) + "@" + v;
        JsonNode a = args == null ? MissingNode.getInstance() : args;
        if ("SEARCH_KNOWLEDGE".equals(t)) {
            String q = a.path("query").asText("");
            int topK = a.path("topK").asInt(5);
            return head + "|query=" + StringUtils.truncate(q == null ? "" : q.trim(), 200) + "|topK=" + topK;
        }
        if ("LIST_FILES".equals(t)) {
            String path = a.path("path").asText("");
            String glob = a.path("glob").asText("");
            Integer maxResults = JsonUtils.intOrNull(a, "maxResults", "max_results");
            Integer maxDepth = JsonUtils.intOrNull(a, "maxDepth", "max_depth");
            return head + "|path=" + StringUtils.truncate(path == null ? "" : path.trim(), 200)
                    + "|glob=" + StringUtils.truncate(glob == null ? "" : glob.trim(), 200)
                    + "|maxResults=" + String.valueOf(maxResults)
                    + "|maxDepth=" + String.valueOf(maxDepth);
        }
        if ("GREP".equals(t)) {
            String pattern = a.path("pattern").asText("");
            String root = a.path("root").asText("");
            String fileGlob = JsonUtils.textOrFallback(a, "file_glob", "fileGlob");
            Integer maxMatches = JsonUtils.intOrNull(a, "maxMatches", "max_matches");
            Integer maxFiles = JsonUtils.intOrNull(a, "maxFiles", "max_files");
            Integer contextLines = JsonUtils.intOrNull(a, "contextLines", "context_lines");
            return head + "|root=" + StringUtils.truncate(root == null ? "" : root.trim(), 200)
                    + "|file_glob=" + StringUtils.truncate(fileGlob == null ? "" : fileGlob.trim(), 200)
                    + "|maxMatches=" + String.valueOf(maxMatches)
                    + "|maxFiles=" + String.valueOf(maxFiles)
                    + "|contextLines=" + String.valueOf(contextLines)
                    + "|pattern=" + StringUtils.truncate(pattern == null ? "" : pattern.trim(), 200);
        }
        if ("READ_FILE".equals(t)) {
            String path = a.path("path").asText("");
            Integer startLine = JsonUtils.intOrNull(a, "startLine", "start_line");
            Integer endLine = JsonUtils.intOrNull(a, "endLine", "end_line");
            return head + "|path=" + StringUtils.truncate(path == null ? "" : path.trim(), 200)
                    + "|startLine=" + String.valueOf(startLine)
                    + "|endLine=" + String.valueOf(endLine);
        }
        if ("TRIGGER_INDEX".equals(t)) {
            String mode = a.path("mode").asText("kafka");
            return head + "|mode=" + StringUtils.truncate(mode == null ? "" : mode.trim(), 50);
        }
        if ("EDIT_FILE".equals(t)) {
            String path = a.path("path").asText("");
            String oldStr = a.path("old_str").asText("");
            String newStr = a.path("new_str").asText("");
            // Truncate content to avoid huge signature keys, but keep enough to distinguish
            return head + "|path=" + StringUtils.truncate(path, 200) 
                 + "|old_len=" + oldStr.length() 
                 + "|new_len=" + newStr.length()
                 + "|old_head=" + StringUtils.truncate(oldStr, 50);
        }
        return head + "|args=" + StringUtils.truncate(a.toString(), 200);
    }

    private void emitAgentStepEvent(String stage, String text, Integer turn, Long cause) {
        if (eventStream == null) {
            return;
        }
        ObjectNode payload = mapper.createObjectNode();
        payload.put("stage", stage == null ? "" : stage);
        if (turn != null) {
            payload.put("turn", turn.intValue());
        }
        if (text != null && !text.isEmpty()) {
            payload.put("text", StringUtils.truncate(text, 800));
        }
        AgentEvent event = new AgentEvent(EventType.AGENT_STEP, stage, payload);
        if (cause != null && cause.longValue() >= 0) {
            event.setCause(cause);
        }
        eventStream.addEvent(event, EventSource.AGENT);
    }

    private void updateSessionState(ObjectNode update, Long cause) {
        if (eventStream == null) {
            return;
        }
        eventStream.updateSessionState(update, EventSource.AGENT, cause);
    }

    private void updateWorkspaceState(ObjectNode update, Long cause) {
        if (eventStream == null) {
            return;
        }
        eventStream.updateWorkspaceState(update, EventSource.AGENT, cause);
    }

    private void updateToolState(String tool, String version, String signature, JsonNode args, String obs, Integer turn, long causeId) {
        ObjectNode sessionUpdate = mapper.createObjectNode();
        sessionUpdate.put("lastTool", tool == null ? "" : tool);
        sessionUpdate.put("lastToolVersion", version == null ? "" : version);
        sessionUpdate.put("lastToolSignature", signature == null ? "" : signature);
        if (turn != null) {
            sessionUpdate.put("turn", turn.intValue());
        }
        sessionUpdate.put("lastToolObsChars", obs == null ? 0 : obs.length());
        String err = extractErrorField(obs);
        if (err != null && !err.trim().isEmpty()) {
            sessionUpdate.put("lastToolError", err);
        }
        sessionUpdate.put("updatedAt", Instant.now().toString());
        updateSessionState(sessionUpdate, causeId >= 0 ? causeId : null);

        String t = tool == null ? "" : tool.trim();
        JsonNode safeArgs = args == null ? MissingNode.getInstance() : args;
        ObjectNode workspaceUpdate = mapper.createObjectNode();
        boolean workspaceDirty = false;
        if ("READ_FILE".equals(t)) {
            String path = safeArgs.path("path").asText("");
            if (!path.isEmpty()) {
                workspaceUpdate.put("lastReadFile", path);
                workspaceDirty = true;

                if (!isToolError(obs)) {
                    String range = "all";
                    Integer sl = JsonUtils.intOrNull(safeArgs, "startLine", "start_line");
                    Integer el = JsonUtils.intOrNull(safeArgs, "endLine", "end_line");
                    if (sl != null || el != null) {
                        range = (sl != null ? sl : 1) + "-" + (el != null ? el : "EOF");
                    }
                    readFiles.add(path + " " + range);
                }
            }
            Integer startLine = JsonUtils.intOrNull(safeArgs, "startLine", "start_line");
            Integer endLine = JsonUtils.intOrNull(safeArgs, "endLine", "end_line");
            if (startLine != null) {
                workspaceUpdate.put("lastReadStart", startLine.intValue());
                workspaceDirty = true;
            }
            if (endLine != null) {
                workspaceUpdate.put("lastReadEnd", endLine.intValue());
                workspaceDirty = true;
            }
        } else if ("EDIT_FILE".equals(t)) {
            String path = safeArgs.path("path").asText("");
            if (!path.isEmpty()) {
                workspaceUpdate.put("lastEditedFile", path);
                workspaceDirty = true;
            }
        } else if ("CREATE_FILE".equals(t)) {
            String path = safeArgs.path("path").asText("");
            if (!path.isEmpty()) {
                workspaceUpdate.put("lastCreatedFile", path);
                workspaceUpdate.put("lastEditedFile", path);
                workspaceDirty = true;
            }
        } else if ("DELETE_FILE".equals(t)) {
            String path = safeArgs.path("path").asText("");
            if (!path.isEmpty()) {
                workspaceUpdate.put("lastDeletedPath", path);
                workspaceDirty = true;
            }
        } else if ("INSERT_LINE".equals(t)) {
            String path = safeArgs.path("path").asText("");
            Integer lineNumber = JsonUtils.intOrNull(safeArgs, "lineNumber", "line_number");
            if (!path.isEmpty()) {
                workspaceUpdate.put("lastInsertedFile", path);
                workspaceUpdate.put("lastEditedFile", path);
                workspaceDirty = true;
            }
            if (lineNumber != null) {
                workspaceUpdate.put("lastInsertedLine", lineNumber.intValue());
                workspaceDirty = true;
            }
        } else if ("UNDO_EDIT".equals(t)) {
            String path = safeArgs.path("path").asText("");
            if (!path.isEmpty()) {
                workspaceUpdate.put("lastUndoFile", path);
                workspaceDirty = true;
            }
        } else if ("APPLY_PATCH".equals(t)) {
            String diff = JsonUtils.textOrFallback(safeArgs, "diff", "patch");
            if (diff != null && !diff.isEmpty()) {
                workspaceUpdate.put("lastPatchApplyDiffChars", diff.length());
                workspaceDirty = true;
            }
            workspaceUpdate.put("lastPatchApplyAt", Instant.now().toString());
            workspaceDirty = true;
        } else if ("BATCH_REPLACE".equals(t)) {
            String path = safeArgs.path("path").asText("");
            String glob = safeArgs.path("glob").asText("");
            if (!path.isEmpty()) {
                workspaceUpdate.put("lastBatchReplacePath", path);
                workspaceDirty = true;
            }
            if (!glob.isEmpty()) {
                workspaceUpdate.put("lastBatchReplaceGlob", glob);
                workspaceDirty = true;
            }
            String oldStr = safeArgs.path("old_str").asText("");
            String newStr = safeArgs.path("new_str").asText("");
            if (!oldStr.isEmpty()) {
                workspaceUpdate.put("lastBatchReplaceOldStr", StringUtils.truncate(oldStr, 200));
                workspaceDirty = true;
            }
            if (!newStr.isEmpty()) {
                workspaceUpdate.put("lastBatchReplaceNewStr", StringUtils.truncate(newStr, 200));
                workspaceDirty = true;
            }
            if (!safeArgs.path("preview").isMissingNode()) {
                workspaceUpdate.put("lastBatchReplacePreview", safeArgs.path("preview").asBoolean());
                workspaceDirty = true;
            }
            workspaceUpdate.put("lastBatchReplaceAt", Instant.now().toString());
            workspaceDirty = true;
        } else if ("LIST_FILES".equals(t)) {
            String path = safeArgs.path("path").asText("");
            String glob = safeArgs.path("glob").asText("");
            if (!path.isEmpty()) {
                workspaceUpdate.put("lastListPath", path);
                workspaceDirty = true;
            }
            if (!glob.isEmpty()) {
                workspaceUpdate.put("lastListGlob", glob);
                workspaceDirty = true;
            }
        } else if ("GREP".equals(t)) {
            String root = safeArgs.path("root").asText("");
            String pattern = safeArgs.path("pattern").asText("");
            if (!root.isEmpty()) {
                workspaceUpdate.put("lastGrepRoot", root);
                workspaceDirty = true;
            }
            if (!pattern.isEmpty()) {
                workspaceUpdate.put("lastGrepPattern", StringUtils.truncate(pattern, 200));
                workspaceDirty = true;
            }
        } else if ("SEARCH_KNOWLEDGE".equals(t)) {
            String query = safeArgs.path("query").asText("");
            if (!query.isEmpty()) {
                workspaceUpdate.put("lastSearchQuery", StringUtils.truncate(query, 200));
                workspaceDirty = true;
            }
        } else if ("TRIGGER_INDEX".equals(t)) {
            String mode = safeArgs.path("mode").asText("");
            if (!mode.isEmpty()) {
                workspaceUpdate.put("lastIndexMode", mode);
                workspaceDirty = true;
            }
        }
        if (workspaceDirty) {
            workspaceUpdate.put("updatedAt", Instant.now().toString());
            workspaceUpdate.put("lastTraceId", traceId == null ? "" : traceId);
            updateWorkspaceState(workspaceUpdate, causeId >= 0 ? causeId : null);
        }
    }

    private long emitToolCallEvent(String tool, String version, String signature, JsonNode args, Integer turn) {
        if (eventStream == null) {
            return -1;
        }
        ObjectNode payload = mapper.createObjectNode();
        payload.put("tool", tool == null ? "" : tool);
        payload.put("version", version == null ? "" : version);
        payload.put("signature", signature == null ? "" : signature);
        if (turn != null) {
            payload.put("turn", turn.intValue());
        }
        String argsText = args == null ? "" : args.toString();
        payload.put("args", maskSensitive(StringUtils.truncate(argsText, 6000)));
        AgentEvent event = new AgentEvent(EventType.TOOL_CALL, "tool_call", payload);
        long lastId = eventStream.getLatestEventId();
        if (lastId >= 0) {
            event.setCause(lastId);
        }
        AgentEvent stored = eventStream.addEvent(event, EventSource.AGENT);
        return stored == null ? -1 : stored.getId();
    }

    private void emitToolResultEvent(String tool, String version, String signature, String obs, Integer turn, long causeId) {
        if (eventStream == null) {
            return;
        }
        ObjectNode payload = mapper.createObjectNode();
        payload.put("tool", tool == null ? "" : tool);
        payload.put("version", version == null ? "" : version);
        payload.put("signature", signature == null ? "" : signature);
        if (turn != null) {
            payload.put("turn", turn.intValue());
        }
        payload.put("observation", maskSensitive(StringUtils.truncate(obs == null ? "" : obs, 8000)));
        AgentEvent event = new AgentEvent(EventType.TOOL_RESULT, "tool_result", payload);
        if (causeId >= 0) {
            event.setCause(causeId);
        }
        eventStream.addEvent(event, EventSource.AGENT);
    }

    private String maskSensitive(String text) {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        String out = text;
        out = SENSITIVE_JSON_KV.matcher(out).replaceAll("$1******$3");
        out = SENSITIVE_KV.matcher(out).replaceAll("$1:******");
        return out;
    }

    static final class PlanExecutionState {
        enum Phase {
            INIT, PLANNED, EXECUTING, VERIFYING, DONE, FAILED
        }

        enum StepStatus {
            PENDING, ACTIVE, DONE, BLOCKED
        }

        static final class PlanStep {
            private final String name;
            private StepStatus status;
            private int progress;

            PlanStep(String name, StepStatus status) {
                this.name = name;
                this.status = status;
            }
        }

        private final List<PlanStep> steps;
        private int currentIndex;
        private int stallCount;
        private Phase phase;
        private String lastToolSignature;
        private int sameToolCount;
        private String lastObservation;

        static PlanExecutionState fromGoal(String goal) {
            String g = goal == null ? "" : goal.trim().toLowerCase();
            List<PlanStep> steps = new ArrayList<PlanStep>();
            if (g.contains("解释") || g.contains("分析") || g.contains("说明") || g.contains("介绍") || g.contains("describe") || g.contains("explain")) {
                steps.add(new PlanStep("检索与理解", StepStatus.ACTIVE));
                steps.add(new PlanStep("总结输出", StepStatus.PENDING));
            } else {
                steps.add(new PlanStep("检索与理解", StepStatus.ACTIVE));
                steps.add(new PlanStep("实施修改", StepStatus.PENDING));
                steps.add(new PlanStep("验证与总结", StepStatus.PENDING));
            }
            PlanExecutionState state = new PlanExecutionState(steps);
            state.phase = Phase.PLANNED;
            return state;
        }

        private PlanExecutionState(List<PlanStep> steps) {
            this.steps = steps == null ? new ArrayList<PlanStep>() : steps;
            this.currentIndex = 0;
            this.phase = Phase.INIT;
        }

        void recordAction(String tool, String signature, boolean isLoop) {
            if (tool == null) {
                return;
            }
            if (signature != null && signature.equals(lastToolSignature)) {
                sameToolCount++;
            } else {
                sameToolCount = 0;
                lastToolSignature = signature;
            }
            if (isLoop || sameToolCount >= 1) {
                stallCount++;
            } else if (stallCount > 0) {
                stallCount--;
            }
            Phase nextPhase = phase;
            StepCategory category = mapToolToCategory(tool);
            if (category == StepCategory.SEARCH) {
                activateStep(0);
                nextPhase = Phase.EXECUTING;
            } else if (category == StepCategory.MODIFY) {
                activateStep(1);
                nextPhase = Phase.EXECUTING;
            } else if (category == StepCategory.VERIFY) {
                activateStep(2);
                nextPhase = Phase.VERIFYING;
            }
            phase = nextPhase;
        }

        void recordObservation(String obs) {
            if (obs == null) {
                return;
            }
            lastObservation = obs;
        }

        String renderForPrompt() {
            if (steps.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("Phase: ").append(phase).append("\n");
            for (int i = 0; i < steps.size(); i++) {
                PlanStep step = steps.get(i);
                String prefix = (i == currentIndex) ? "*" : "-";
                sb.append(prefix)
                        .append(" ")
                        .append(step.name)
                        .append(" [")
                        .append(step.status)
                        .append("]");
                if (step.progress > 0) {
                    sb.append(" p=").append(step.progress);
                }
                sb.append("\n");
            }
            if (stallCount >= 2) {
                sb.append("Stall: ").append(stallCount).append(" (change tool or adjust plan)\n");
            }
            if (lastObservation != null && lastObservation.length() > 0) {
                sb.append("LastObs: ").append(StringUtils.truncate(lastObservation, 200)).append("\n");
            }
            return sb.toString();
        }

        List<PlanStep> steps() {
            return steps;
        }

        int currentIndex() {
            return currentIndex;
        }

        private void activateStep(int idx) {
            if (steps.isEmpty()) {
                return;
            }
            int target = Math.max(0, Math.min(idx, steps.size() - 1));
            for (int i = 0; i < steps.size(); i++) {
                PlanStep step = steps.get(i);
                if (i < target) {
                    step.status = StepStatus.DONE;
                } else if (i == target) {
                    step.status = StepStatus.ACTIVE;
                    step.progress++;
                }
            }
            currentIndex = target;
        }

        private StepCategory mapToolToCategory(String tool) {
            String t = tool.trim().toUpperCase();
            if ("SEARCH_KNOWLEDGE".equals(t) || "LIST_FILES".equals(t) || "GREP".equals(t) || "READ_FILE".equals(t)) {
                return StepCategory.SEARCH;
            }
            if ("EDIT_FILE".equals(t) || "WRITE_FILE".equals(t) || "APPLY_PATCH".equals(t) || "BATCH_REPLACE".equals(t) || "CREATE_FILE".equals(t) || "DELETE_FILE".equals(t) || "INSERT_LINE".equals(t) || "UNDO_EDIT".equals(t)) {
                return StepCategory.MODIFY;
            }
            if ("RUN_COMMAND".equals(t)) {
                return StepCategory.VERIFY;
            }
            return StepCategory.OTHER;
        }

        enum StepCategory {
            SEARCH, MODIFY, VERIFY, OTHER
        }
    }
}
