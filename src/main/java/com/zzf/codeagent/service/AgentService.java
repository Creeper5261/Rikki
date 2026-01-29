package com.zzf.codeagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzf.codeagent.api.AgentChatController.ChatRequest;
import com.zzf.codeagent.api.AgentChatController.ChatResponse;
import com.zzf.codeagent.api.AgentChatController.CompressHistoryRequest;
import com.zzf.codeagent.api.AgentChatController.PendingChangeRequest;
import com.zzf.codeagent.config.AgentConfig;
import com.zzf.codeagent.core.agent.JsonReActAgent;
import com.zzf.codeagent.core.agent.TopicResult;
import com.zzf.codeagent.core.event.AgentEvent;
import com.zzf.codeagent.core.event.EventSource;
import com.zzf.codeagent.core.event.EventStream;
import com.zzf.codeagent.core.event.EventType;
import com.zzf.codeagent.core.pipeline.IntentClassifier;
import com.zzf.codeagent.core.pipeline.SmartRetrievalPipeline;
import com.zzf.codeagent.core.rag.index.ElasticsearchIndexNames;
import com.zzf.codeagent.core.rag.pipeline.IndexingWorker;
import com.zzf.codeagent.core.rag.search.ElasticsearchCodeSearchService;
import com.zzf.codeagent.core.rag.search.HybridCodeSearchService;
import com.zzf.codeagent.core.runtime.RuntimeService;
import com.zzf.codeagent.core.session.SessionWorkspace;
import com.zzf.codeagent.core.skill.SkillManager;
import com.zzf.codeagent.core.tool.AppliedChangesManager;
import com.zzf.codeagent.core.tool.PendingChangesManager;
import com.zzf.codeagent.core.tool.ToolExecutionService;
import com.zzf.codeagent.core.tools.FileSystemToolService;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public final class AgentService {
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private static final Logger logger = LoggerFactory.getLogger(AgentService.class);
    private static final String JSON_UTF8 = "application/json;charset=UTF-8";
    private static final Pattern SENSITIVE_KV = Pattern.compile("(?i)(password|passwd|secret|token|apikey|accesskey|secretkey)\\s*[:=]\\s*([\"']?)([^\"'\\\\\\r\\n\\s]{1,160})\\2");
    private static final Pattern SENSITIVE_JSON_KV = Pattern.compile("(?i)(\"(?:password|passwd|secret|token|apiKey|accessKey|secretKey)\"\\s*:\\s*\")([^\"]{1,160})(\")");
    private static final String SYSTEM_COMPACT_PROMPT = "You are a helpful AI assistant tasked with summarizing conversations.";
    private static final String COMPACT_PROMPT = "Your task is to create a detailed summary of the conversation so far, paying close attention to the user's explicit requests and your previous actions.\nThis summary should be thorough in capturing technical details, code patterns, and architectural decisions that would be essential for continuing development work without losing context.\n\nBefore providing your final summary, wrap your analysis in <analysis> tags to organize your thoughts and ensure you've covered all necessary points. In your analysis process:\n\n1. Chronologically analyze each message and section of the conversation. For each section thoroughly identify:\n   - The user's explicit requests and intents\n   - Your approach to addressing the user's requests\n   - Key decisions, technical concepts and code patterns\n   - Specific details like:\n     - file names\n     - full code snippets\n     - function signatures\n     - file edits\n\n- Errors that you ran into and how you fixed them\n- Pay special attention to specific user feedback that you received, especially if the user told you to do something differently.\n\n2. Double-check for technical accuracy and completeness, addressing each required element thoroughly.\n\nYour summary should include the following sections:\n\n1. Primary Request and Intent: Capture all of the user's explicit requests and intents in detail\n2. Key Technical Concepts: List all important technical concepts, technologies, and frameworks discussed.\n3. Files and Code Sections: Enumerate specific files and code sections examined, modified, or created. Pay special attention to the most recent messages and include full code snippets where applicable and include a summary of why this file read or edit is important.\n4. Errors and fixes: List all errors that you ran into, and how you fixed them. Pay special attention to specific user feedback that you received, especially if the user told you to do something differently.\n5. Problem Solving: Summarize the key technical problems you solved\n6. All user messages: A complete list of user messages in order\n7. Pending Tasks: Clearly state any pending tasks or the statement that there are no pending tasks\n8. Current Work: Describe in detail the work currently being done in the codebase\n9. Optional Next Step: Suggest a possible next step if applicable\n10. Conversation Language: Identify the language used in the conversation\n\nIf you are asked to summarize more than once, ensure you comply with the above requirements each time.\n";

    private final ObjectMapper mapper;
    private final HybridCodeSearchService hybridCodeSearchService;
    private final IndexingWorker indexingWorker;
    private final ToolExecutionService toolExecutionService;
    private final RuntimeService runtimeService;
    private final RetrievalService retrievalService;
    private final ContextService contextService;
    private final SkillManager skillManager;
    private final AgentConfig agentConfig;

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    public AgentService(ObjectMapper mapper, HybridCodeSearchService hybridCodeSearchService, IndexingWorker indexingWorker, ToolExecutionService toolExecutionService, RuntimeService runtimeService, RetrievalService retrievalService, ContextService contextService, SkillManager skillManager, AgentConfig agentConfig) {
        this.mapper = mapper;
        this.hybridCodeSearchService = hybridCodeSearchService;
        this.indexingWorker = indexingWorker;
        this.toolExecutionService = toolExecutionService;
        this.runtimeService = runtimeService;
        this.retrievalService = retrievalService;
        this.contextService = contextService;
        this.skillManager = skillManager;
        this.agentConfig = agentConfig;
    }

    public ResponseEntity<ChatResponse> chat(ChatRequest req) {
        String traceId = resolveTraceId("trace-");
        MDC.put("traceId", traceId);
        EventStream eventStream = null;
        SessionWorkspace sessionWorkspace = null;
        String sessionStatus = "ok";
        String sessionError = "";
        String workspaceRoot = "";
        try {
            long t0 = System.nanoTime();
            logger.info("chat.recv traceId={} reqNull={}", traceId, req == null);
            if (req == null || req.goal == null || req.goal.trim().isEmpty()) {
                logger.info("chat.reject traceId={} reason=goal_blank", traceId);
                return jsonStatus(HttpStatus.BAD_REQUEST, new ChatResponse(traceId, "goal is blank"));
            }
            workspaceRoot = req.workspaceRoot == null ? "" : req.workspaceRoot.trim();
            String workspaceName = req.workspaceName == null ? "" : req.workspaceName.trim();
            List<String> chatHistory = normalizeChatHistory(req.history);
            chatHistory = maybeCompressHistory(chatHistory, req.goal);
            String ideContextPath = req.ideContextPath == null ? "" : req.ideContextPath.trim();
            int rawHistorySize = req.history == null ? 0 : req.history.size();
            int ideContextChars = req.ideContextContent == null ? 0 : req.ideContextContent.length();
            int ideOpenedFilesCount = req.ideOpenedFiles == null ? 0 : req.ideOpenedFiles.size();
            String indexName = ElasticsearchIndexNames.codeAgentV2IndexForWorkspaceRoot(workspaceRoot);
            logger.info("chat.in traceId={} workspaceName={} index={} goal={}", traceId, workspaceName, indexName, contextService.truncate(req.goal.trim(), 300));
            logger.info("chat.meta traceId={} requestHistorySize={} idePath={} goalChars={}", traceId, chatHistory.size(), ideContextPath, req.goal.trim().length());
            logger.info("chat.payload traceId={} workspaceRoot={} rawHistorySize={} ideContextChars={} ideOpenedFiles={}", traceId, workspaceRoot, rawHistorySize, ideContextChars, ideOpenedFilesCount);
            logChatRequest(traceId, req);
            sessionWorkspace = SessionWorkspace.create(workspaceRoot, traceId);
            String sessionRoot = sessionWorkspace.getSessionRoot() != null ? sessionWorkspace.getSessionRoot().toString() : workspaceRoot;
            eventStream = new EventStream(mapper, traceId, sessionRoot);

            // Reset applied change log for this session
            AppliedChangesManager.getInstance().clear(workspaceRoot, traceId);

            AgentEvent sessionStart = new AgentEvent(EventType.SESSION_START, "session_start", mapper.createObjectNode()
                    .put("traceId", traceId)
                    .put("workspaceRoot", workspaceRoot)
                    .put("workspaceName", workspaceName)
                    .put("goalChars", req.goal == null ? 0 : req.goal.trim().length()));
            AgentEvent storedSessionStart = eventStream.addEvent(sessionStart, EventSource.USER);
            ObjectNode workspaceUpdate = mapper.createObjectNode();
            workspaceUpdate.put("lastTraceId", traceId);
            workspaceUpdate.put("workspaceRoot", workspaceRoot);
            workspaceUpdate.put("workspaceName", workspaceName);
            workspaceUpdate.put("lastGoal", contextService.truncate(req.goal == null ? "" : req.goal.trim(), 300));
            workspaceUpdate.put("lastStartedAt", Instant.now().toString());
            eventStream.updateWorkspaceState(workspaceUpdate, EventSource.USER, storedSessionStart == null ? null : storedSessionStart.getId());

            String apiKey = contextService.resolveDeepSeekApiKey();
            if (apiKey == null || apiKey.trim().isEmpty()) {
                logger.info("chat.reject traceId={} reason=api_key_missing", traceId);
                return jsonStatus(HttpStatus.NOT_IMPLEMENTED, new ChatResponse(traceId, "deepseek api-key not set"));
            }

            HttpClient http = HttpClient.newHttpClient();
            retrievalService.ensureIndexExists(http, indexName);
            ElasticsearchCodeSearchService search = retrievalService.createSearchService(http, indexName);

            OpenAiChatModel model = contextService.createChatModel(apiKey);
            OpenAiChatModel fastModel = contextService.createFastChatModel(apiKey);

            TopicResult topic = JsonReActAgent.detectNewTopic(model, req.goal, mapper, agentConfig, traceId);
            logger.info("chat.topic traceId={} isNewTopic={} title={}", traceId, topic.isNewTopic, contextService.truncate(topic.title, 100));

            IntentClassifier classifier = new IntentClassifier(fastModel, mapper);
            long tClassify = System.nanoTime();
            IntentClassifier.Intent intent = classifier.classify(req.goal);
            long classifyMs = (System.nanoTime() - tClassify) / 1_000_000L;
            String answer;
            String route;
            long pipelineMs = 0L;
            long agentMs = 0L;
            List<Map<String, Object>> appliedChanges = new ArrayList<>();
            JsonReActAgent agentUsed = null;

            if (intent == IntentClassifier.Intent.SEARCH || intent == IntentClassifier.Intent.EXPLAIN) {
                route = "SmartRetrieval";
                logger.info("chat.route traceId={} intent={} pipeline=SmartRetrieval", traceId, intent);
                SmartRetrievalPipeline pipeline = new SmartRetrievalPipeline(hybridCodeSearchService, fastModel, workspaceRoot);
                logger.info("chat.pipeline.start traceId={} pipeline=SmartRetrieval goalChars={}", traceId, req.goal.trim().length());
                long tPipeline = System.nanoTime();
                answer = pipeline.run(req.goal);
                pipelineMs = (System.nanoTime() - tPipeline) / 1_000_000L;
                logger.info("chat.pipeline.done traceId={} pipeline=SmartRetrieval answerChars={}", traceId, answer == null ? 0 : answer.length());

                if (isInsufficientAnswer(answer)) {
                    route = "SmartRetrieval->JsonReActAgent";
                    logger.info("chat.fallback traceId={} reason=InsufficientRetrieval", traceId);
                    JsonReActAgent agent = new JsonReActAgent(mapper, model, fastModel, search, hybridCodeSearchService, traceId, workspaceRoot, sessionRoot, traceId, workspaceRoot, indexingWorker, bootstrapServers, chatHistory, ideContextPath, toolExecutionService, runtimeService, eventStream, skillManager, agentConfig);
                    agentUsed = agent;
                    String augmentedGoal = req.goal + "\n\n(Note: Automatic search failed to find enough context. Please use tools like LIST_FILES or READ_FILE to explore the project structure and key config files explicitly.)";
                    logger.info("agent.run.start traceId={} route=SmartRetrievalFallback goalChars={}", traceId, augmentedGoal.length());
                    long tAgent = System.nanoTime();
                    answer = agent.run(augmentedGoal);
                    // Applied changes tracked separately; no action here.
                    agentMs = (System.nanoTime() - tAgent) / 1_000_000L;
                    logger.info("agent.run.done traceId={} route=SmartRetrievalFallback answerChars={}", traceId, answer == null ? 0 : answer.length());
                }
            } else {
                route = "JsonReActAgent";
                logger.info("chat.route traceId={} intent={} pipeline=JsonReActAgent", traceId, intent);
                JsonReActAgent agent = new JsonReActAgent(mapper, model, fastModel, search, hybridCodeSearchService, traceId, workspaceRoot, sessionRoot, traceId, workspaceRoot, indexingWorker, bootstrapServers, chatHistory, ideContextPath, toolExecutionService, runtimeService, eventStream, skillManager, agentConfig);
                agentUsed = agent;
                logger.info("agent.run.start traceId={} route=JsonReActAgent goalChars={}", traceId, req.goal.trim().length());
                long tAgent = System.nanoTime();
                answer = agent.run(req.goal);
                agentMs = (System.nanoTime() - tAgent) / 1_000_000L;
                logger.info("agent.run.done traceId={} route=JsonReActAgent answerChars={}", traceId, answer == null ? 0 : answer.length());
            }

            // Collect applied changes for UI
            appliedChanges.clear();
            for (AppliedChangesManager.AppliedChange pc : AppliedChangesManager.getInstance().getChanges(workspaceRoot, traceId)) {
                Map<String, Object> map = new HashMap<>();
                map.put("id", pc.id);
                map.put("path", pc.path);
                map.put("type", pc.type);
                map.put("oldContent", pc.oldContent);
                map.put("newContent", pc.newContent);
                map.put("timestamp", pc.timestamp);
                map.put("workspaceRoot", pc.workspaceRoot);
                map.put("sessionId", pc.sessionId);
                appliedChanges.add(map);
            }

            // Collect pending changes for UI
            List<Map<String, Object>> pendingChangesList = new ArrayList<>();
            for (PendingChangesManager.PendingChange pc : PendingChangesManager.getInstance().getChanges(workspaceRoot, traceId)) {
                Map<String, Object> map = new HashMap<>();
                map.put("id", pc.id);
                map.put("path", pc.path);
                map.put("type", pc.type);
                map.put("oldContent", pc.oldContent);
                map.put("newContent", pc.newContent);
                map.put("timestamp", pc.timestamp);
                map.put("workspaceRoot", pc.workspaceRoot);
                map.put("sessionId", pc.sessionId);
                pendingChangesList.add(map);
            }

            answer = contextService.fixMojibakeIfNeeded(answer);
            logger.info("chat.out traceId={} answer={}", traceId, contextService.truncate(answer, 500));
            long totalMs = (System.nanoTime() - t0) / 1_000_000L;
            Map<String, Object> meta = new HashMap<String, Object>();
            meta.put("intent", intent.name());
            meta.put("route", route);
            if (!appliedChanges.isEmpty()) {
                meta.put("appliedChanges", appliedChanges);
            }
            if (!pendingChangesList.isEmpty()) {
                meta.put("pendingChanges", pendingChangesList);
            }
            meta.put("latency_ms_total", totalMs);
            meta.put("latency_ms_pipeline", pipelineMs);
            meta.put("latency_ms_agent", agentMs);
            if (agentUsed != null) {
                meta.put("tool_calls", agentUsed.getToolCallCount());
                meta.put("tool_errors", agentUsed.getToolErrorCount());
                meta.put("turns_used", agentUsed.getTurnsUsed());
                meta.put("auto_skills", agentUsed.getAutoSkillsLoaded());
                meta.put("edits_applied", agentUsed.getEditsApplied());
                meta.put("edits_rejected", agentUsed.getEditsRejected());
            }
            meta.put("total_ms", totalMs);
            meta.put("classify_ms", classifyMs);
            if (pipelineMs > 0) {
                meta.put("pipeline_ms", pipelineMs);
            }
            if (agentMs > 0) {
                meta.put("agent_ms", agentMs);
            }
            logger.info("chat.metrics traceId={} intent={} route={} totalMs={} classifyMs={} pipelineMs={} agentMs={}",
                    traceId, intent, route, totalMs, classifyMs, pipelineMs, agentMs);
            return jsonOk(new ChatResponse(traceId, answer, topic.isNewTopic, topic.title, meta));
        } catch (Exception e) {
            logger.warn("chat.fail traceId={} err={}", traceId, e.toString());
            sessionStatus = "error";
            sessionError = contextService.rootMessage(e);
            return jsonStatus(HttpStatus.BAD_GATEWAY, new ChatResponse(traceId, "chat failed: " + contextService.rootMessage(e)));
        } finally {
            if (eventStream != null) {
                AgentEvent sessionEnd = new AgentEvent(EventType.SESSION_END, "session_end", mapper.createObjectNode()
                        .put("traceId", traceId)
                        .put("status", sessionStatus)
                        .put("error", sessionError));
                AgentEvent storedSessionEnd = eventStream.addEvent(sessionEnd, EventSource.ENVIRONMENT);
                ObjectNode sessionUpdate = mapper.createObjectNode();
                sessionUpdate.put("agentState", "finished");
                sessionUpdate.put("status", sessionStatus);
                if (sessionError != null && !sessionError.isEmpty()) {
                    sessionUpdate.put("error", sessionError);
                }
                sessionUpdate.put("endedAt", Instant.now().toString());
                eventStream.updateSessionState(sessionUpdate, EventSource.ENVIRONMENT, storedSessionEnd == null ? null : storedSessionEnd.getId());
                eventStream.close();
            }
            if (sessionWorkspace != null) {
                sessionWorkspace.cleanup();
            }
            AppliedChangesManager.getInstance().clear(workspaceRoot, traceId);
            MDC.remove("traceId");
        }
    }



    public ResponseEntity<Map<String, Object>> compressHistory(CompressHistoryRequest req) {
        String traceId = resolveTraceId("trace-compress-");
        MDC.put("traceId", traceId);
        try {
            String apiKey = contextService.resolveDeepSeekApiKey();
            if (apiKey == null || apiKey.trim().isEmpty()) {
                Map<String, Object> out = new HashMap<String, Object>();
                out.put("traceId", traceId);
                out.put("error", "deepseek api-key not set");
                return jsonStatus(HttpStatus.NOT_IMPLEMENTED, out);
            }
            List<String> history = req == null ? null : req.history;
            List<String> h = normalizeHistoryForCompression(history);
            String joined = String.join("\n", h);
            String goalHint = req == null ? "" : (req.goalHint == null ? "" : req.goalHint.trim());
            goalHint = contextService.truncate(goalHint, 800);

            StringBuilder p = new StringBuilder();
            p.append(SYSTEM_COMPACT_PROMPT);
            p.append("\n\n");
            p.append(COMPACT_PROMPT);
            if (!goalHint.isEmpty()) {
                p.append("\n\nFocus:\n").append(goalHint);
            }
            p.append("\n\nConversation:\n");
            p.append(joined);

            OpenAiChatModel model = contextService.createChatModel(apiKey);
            String summary = model.chat(p.toString());
            summary = summary == null ? "" : summary.trim();
            if (summary.length() > 1500) {
                summary = summary.substring(0, 1500);
            }

            Map<String, Object> out = new HashMap<String, Object>();
            out.put("traceId", traceId);
            out.put("summary", contextService.fixMojibakeIfNeeded(summary));
            out.put("inputLines", h.size());
            out.put("inputChars", joined.length());
            return jsonOk(out);
        } catch (Exception e) {
            Map<String, Object> out = new HashMap<String, Object>();
            out.put("traceId", traceId);
            out.put("error", contextService.rootMessage(e));
            return jsonStatus(HttpStatus.BAD_GATEWAY, out);
        } finally {
            MDC.remove("traceId");
        }
    }

    public ResponseEntity<Map<String, Object>> resolvePending(PendingChangeRequest req) {
        String traceId = req == null || req.traceId == null ? "" : req.traceId.trim();
        String workspaceRoot = req == null || req.workspaceRoot == null ? "" : req.workspaceRoot.trim();
        Map<String, Object> out = new HashMap<>();
        out.put("traceId", traceId);
        if (workspaceRoot.isEmpty()) {
            out.put("status", "error");
            out.put("error", "workspace_root_required");
            return jsonStatus(HttpStatus.BAD_REQUEST, out);
        }
        Set<String> requestedPaths = extractRequestedPaths(req);
        List<PendingChangesManager.PendingChange> pending = PendingChangesManager.getInstance().getChanges(workspaceRoot, traceId);
        if (pending.isEmpty()) {
            out.put("status", "error");
            out.put("error", "no_pending_changes");
            return jsonOk(out);
        }
        List<PendingChangesManager.PendingChange> selected = new ArrayList<>();
        if (requestedPaths.isEmpty()) {
            selected.addAll(pending);
        } else {
            for (PendingChangesManager.PendingChange change : pending) {
                if (requestedPaths.contains(normalizePendingPath(change.path))) {
                    selected.add(change);
                }
            }
        }
        if (selected.isEmpty()) {
            out.put("status", "error");
            out.put("error", "no_matching_pending_changes");
            out.put("available_paths", pending.stream().map(c -> c.path).collect(Collectors.toList()));
            return jsonOk(out);
        }
        Path rootPath;
        try {
            rootPath = Path.of(workspaceRoot).toAbsolutePath().normalize();
        } catch (Exception e) {
            out.put("status", "error");
            out.put("error", "invalid_workspace_root");
            return jsonOk(out);
        }
        FileSystemToolService fs = new FileSystemToolService(rootPath);
        boolean reject = req != null && Boolean.TRUE.equals(req.reject);
        List<String> applied = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (PendingChangesManager.PendingChange change : selected) {
            if (reject) {
                PendingChangesManager.getInstance().removeChange(change.id);
                rejected.add(change.path);
                continue;
            }
            boolean delete = "DELETE".equalsIgnoreCase(change.type);
            FileSystemToolService.EditFileResult result = fs.applyToFile(change.path, change.newContent == null ? "" : change.newContent, delete);
            if (result.success) {
                PendingChangesManager.getInstance().removeChange(change.id);
                applied.add(change.path);
                AppliedChangesManager.getInstance().addChange(new AppliedChangesManager.AppliedChange(
                        change.id, change.path, change.type, change.oldContent, change.newContent, System.currentTimeMillis(), workspaceRoot, traceId));
            } else {
                errors.add(change.path + ":" + (result.error == null ? "apply_failed" : result.error));
            }
        }
        boolean success = errors.isEmpty();
        out.put("status", success ? "ok" : "error");
        out.put("success", success);
        if (!applied.isEmpty()) {
            out.put("applied", applied);
        }
        if (!rejected.isEmpty()) {
            out.put("rejected", rejected);
        }
        if (!errors.isEmpty()) {
            out.put("error", errors.get(0));
            out.put("errors", errors);
        }
        return jsonOk(out);
    }

    private static Set<String> extractRequestedPaths(PendingChangeRequest req) {
        Set<String> out = new HashSet<>();
        if (req == null) {
            return out;
        }
        if (req.path != null && !req.path.trim().isEmpty()) {
            out.add(normalizePendingPath(req.path));
        }
        if (req.paths != null) {
            for (String path : req.paths) {
                if (path != null && !path.trim().isEmpty()) {
                    out.add(normalizePendingPath(path));
                }
            }
        }
        return out;
    }

    private static String normalizePendingPath(String path) {
        if (path == null) {
            return "";
        }
        return path.replace('\\', '/').trim();
    }

    private ResponseEntity<Map<String, Object>> jsonOk(Map<String, Object> body) {
        return ResponseEntity.ok().header("Content-Type", JSON_UTF8).body(body);
    }

    private ResponseEntity<ChatResponse> jsonOk(ChatResponse body) {
        return ResponseEntity.ok().header("Content-Type", JSON_UTF8).body(body);
    }

    private ResponseEntity<ChatResponse> jsonStatus(HttpStatus status, ChatResponse body) {
        return ResponseEntity.status(status).header("Content-Type", JSON_UTF8).body(body);
    }

    private ResponseEntity<Map<String, Object>> jsonStatus(HttpStatus status, Map<String, Object> body) {
        return ResponseEntity.status(status).header("Content-Type", JSON_UTF8).body(body);
    }

    private static List<String> normalizeChatHistory(List<String> history) {
        if (history == null || history.isEmpty()) {
            return new ArrayList<String>();
        }
        int maxLines = 60;
        int maxLineChars = 800;
        int maxTotalChars = 6000;
        List<String> out = new ArrayList<String>();
        int start = Math.max(0, history.size() - maxLines);
        int total = 0;
        for (int i = start; i < history.size(); i++) {
            String s = history.get(i);
            if (s == null) {
                continue;
            }
            String t = s.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (isInternalHistoryLine(t)) {
                continue;
            }
            if (t.length() > maxLineChars) {
                t = t.substring(0, maxLineChars);
            }
            if (isInternalHistoryLine(t)) {
                continue;
            }
            if (total + t.length() + 1 > maxTotalChars) {
                break;
            }
            out.add(t);
            total += t.length() + 1;
        }
        return out;
    }

    private List<String> maybeCompressHistory(List<String> history, String goal) {
        if (!isHistoryCompressionEnabled()) {
            return history;
        }
        if (history == null || history.isEmpty()) {
            return history;
        }
        if (hasSummaryLine(history)) {
            return history;
        }
        int maxLines = resolveIntEnv("CODEAGENT_HISTORY_COMPRESS_MAX_LINES", "codeagent.history.compress.max_lines", 24);
        int maxChars = resolveIntEnv("CODEAGENT_HISTORY_COMPRESS_MAX_CHARS", "codeagent.history.compress.max_chars", 4000);
        int keepTail = resolveIntEnv("CODEAGENT_HISTORY_COMPRESS_KEEP_TAIL", "codeagent.history.compress.keep_tail", 8);
        int totalChars = countChars(history);
        if (history.size() <= maxLines && totalChars <= maxChars) {
            return history;
        }

        List<String> tail = new ArrayList<>();
        int start = Math.max(0, history.size() - Math.max(2, keepTail));
        for (int i = start; i < history.size(); i++) {
            tail.add(history.get(i));
        }

        String apiKey = contextService.resolveDeepSeekApiKey();
        if (apiKey == null || apiKey.trim().isEmpty()) {
            return tail;
        }

        try {
            List<String> h = normalizeHistoryForCompression(history);
            String summary = summarizeHistory(h, goal == null ? "" : goal.trim(), apiKey);
            if (summary == null || summary.isBlank()) {
                return tail;
            }
            summary = contextService.fixMojibakeIfNeeded(summary.trim());
            if (summary.length() > 800) {
                summary = summary.substring(0, 800);
            }
            List<String> out = new ArrayList<>();
            out.add("Summary: " + summary);
            out.addAll(tail);
            return out;
        } catch (Exception e) {
            return tail;
        }
    }

    private String summarizeHistory(List<String> history, String goalHint, String apiKey) {
        String joined = String.join("\n", history);
        if (joined.length() > 24000) {
            joined = joined.substring(0, 24000);
        }
        StringBuilder p = new StringBuilder();
        p.append("Summarize the conversation into <= 800 characters. ");
        p.append("Preserve user requests, constraints, file paths, and decisions. ");
        p.append("Plain text only, no markdown or bullets.\n");
        if (goalHint != null && !goalHint.isEmpty()) {
            p.append("Goal: ").append(contextService.truncate(goalHint, 200)).append("\n");
        }
        p.append("Conversation:\n").append(joined);
        OpenAiChatModel model = contextService.createFastChatModel(apiKey);
        String summary = model.chat(p.toString());
        return summary == null ? "" : summary.trim();
    }

    private boolean hasSummaryLine(List<String> history) {
        for (String line : history) {
            if (line == null) continue;
            String t = line.trim();
            if (t.startsWith("Summary:")) {
                return true;
            }
        }
        return false;
    }

    private int countChars(List<String> history) {
        int total = 0;
        for (String s : history) {
            if (s == null) continue;
            total += s.length();
        }
        return total;
    }

    private boolean isHistoryCompressionEnabled() {
        String prop = System.getProperty("codeagent.history.compress.enabled");
        if (prop != null && !prop.trim().isEmpty()) {
            return "true".equalsIgnoreCase(prop.trim());
        }
        String env = System.getenv("CODEAGENT_HISTORY_COMPRESS_ENABLED");
        if (env != null && !env.trim().isEmpty()) {
            return "true".equalsIgnoreCase(env.trim());
        }
        return true;
    }

    private int resolveIntEnv(String envKey, String propKey, int fallback) {
        String prop = System.getProperty(propKey);
        if (prop != null && !prop.trim().isEmpty()) {
            try {
                return Integer.parseInt(prop.trim());
            } catch (Exception ignored) {}
        }
        String env = System.getenv(envKey);
        if (env != null && !env.trim().isEmpty()) {
            try {
                return Integer.parseInt(env.trim());
            } catch (Exception ignored) {}
        }
        return fallback;
    }

    private void logChatRequest(String traceId, ChatRequest req) {
        try {
            String json = mapper.writeValueAsString(req);
            logger.info("chat.request traceId={} json={}", traceId, maskSensitive(json));
        } catch (Exception e) {
            logger.info("chat.request traceId={} err={}", traceId, e.toString());
        }
    }

    private String maskSensitive(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = text;
        out = SENSITIVE_JSON_KV.matcher(out).replaceAll("$1******$3");
        out = SENSITIVE_KV.matcher(out).replaceAll("$1:******");
        return out;
    }

    private static String resolveTraceId(String prefix) {
        String existing = MDC.get("traceId");
        if (existing != null && !existing.trim().isEmpty()) {
            return existing;
        }
        return prefix + UUID.randomUUID();
    }

    private static List<String> normalizeHistoryForCompression(List<String> history) {
        if (history == null || history.isEmpty()) {
            return new ArrayList<String>();
        }
        int maxLines = 240;
        int maxLineChars = 1200;
        int maxTotalChars = 24000;
        List<String> out = new ArrayList<String>();
        int start = Math.max(0, history.size() - maxLines);
        int total = 0;
        for (int i = start; i < history.size(); i++) {
            String s = history.get(i);
            if (s == null) {
                continue;
            }
            String t = s.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (isInternalHistoryLine(t)) {
                continue;
            }
            if (t.length() > maxLineChars) {
                t = t.substring(0, maxLineChars);
            }
            if (isInternalHistoryLine(t)) {
                continue;
            }
            if (total + t.length() + 1 > maxTotalChars) {
                break;
            }
            out.add(t);
            total += t.length() + 1;
        }
        return out;
    }

    private static boolean isInternalHistoryLine(String text) {
        if (text == null || text.isEmpty()) {
            return true;
        }
        String t = text.trim();
        if (t.isEmpty()) {
            return true;
        }
        String lower = t.toLowerCase();
        if (t.startsWith("THOUGHT") || t.startsWith("OBS ") || t.startsWith("FINAL ") || t.startsWith("Plan:") || t.startsWith("History:")
                || t.startsWith("PinnedObservation") || t.startsWith("LastObs") || t.startsWith("SYSTEM_INSTRUCTION")) {
            return true;
        }
        if (lower.contains("tool output") || lower.contains("tool_budget_exceeded") || lower.contains("tool call budget") || lower.contains("tool_backoff")) {
            return true;
        }
        if (lower.contains("\"type\": \"tool\"") || lower.contains("\"type\":\"tool\"")) {
            return true;
        }
        return t.contains("工具调用次数") || t.contains("工具预算");
    }

    public SseEmitter chatStream(ChatRequest req) {
        long timeoutMs = resolveStreamTimeoutMs();
        SseEmitter emitter = new SseEmitter(timeoutMs);
        executor.submit(() -> {
            String traceId = resolveTraceId("trace-");
            MDC.put("traceId", traceId);
            EventStream eventStream = null;
            SessionWorkspace sessionWorkspace = null;
            String workspaceRoot = "";
            try {
                long t0 = System.nanoTime();
                if (req == null || req.goal == null || req.goal.trim().isEmpty()) {
                    emitter.send(SseEmitter.event().name("error").data("goal is blank"));
                    emitter.complete();
                    return;
                }
                workspaceRoot = req.workspaceRoot == null ? "" : req.workspaceRoot.trim();
                List<String> chatHistory = normalizeChatHistory(req.history);
                chatHistory = maybeCompressHistory(chatHistory, req.goal);
                String ideContextPath = req.ideContextPath == null ? "" : req.ideContextPath.trim();
                String indexName = ElasticsearchIndexNames.codeAgentV2IndexForWorkspaceRoot(workspaceRoot);
                logChatRequest(traceId, req);

                sessionWorkspace = SessionWorkspace.create(workspaceRoot, traceId);
                String sessionRoot = sessionWorkspace.getSessionRoot() != null ? sessionWorkspace.getSessionRoot().toString() : workspaceRoot;
                eventStream = new EventStream(mapper, traceId, sessionRoot);
                eventStream.subscribe("sse-" + traceId, event -> {
                    try {
                        // Forward all events to SSE
                        // Payload is ObjectNode, write as string
                        emitter.send(SseEmitter.event().name(event.getType().name().toLowerCase()).data(mapper.writeValueAsString(event.getPayload())));
                    } catch (Exception e) {
                        logger.error("sse.send.fail traceId={} err={}", traceId, e.getMessage());
                    }
                });

                // Reset applied change log for this session
                AppliedChangesManager.getInstance().clear(workspaceRoot, traceId);

                String apiKey = contextService.resolveDeepSeekApiKey();
                if (apiKey == null || apiKey.trim().isEmpty()) {
                    emitter.send(SseEmitter.event().name("error").data("deepseek api-key not set"));
                    emitter.complete();
                    return;
                }

                HttpClient http = HttpClient.newHttpClient();
                retrievalService.ensureIndexExists(http, indexName);
                ElasticsearchCodeSearchService search = retrievalService.createSearchService(http, indexName);
                OpenAiChatModel model = contextService.createChatModel(apiKey);
                OpenAiChatModel fastModel = contextService.createFastChatModel(apiKey);

                TopicResult topic = JsonReActAgent.detectNewTopic(model, req.goal, mapper);
                IntentClassifier classifier = new IntentClassifier(fastModel, mapper);
                IntentClassifier.Intent intent = classifier.classify(req.goal);
                
                String answer;
                String route;
                long pipelineMs = 0L;
                long agentMs = 0L;
                List<Map<String, Object>> appliedChanges = new ArrayList<>();
                JsonReActAgent agentUsed = null;

                if (intent == IntentClassifier.Intent.SEARCH || intent == IntentClassifier.Intent.EXPLAIN) {
                    route = "SmartRetrieval";
                    // Manually emit a thought event for pipeline start
                    ObjectNode thoughtPayload = mapper.createObjectNode().put("text", "Executing SmartRetrievalPipeline...");
                    emitter.send(SseEmitter.event().name("agent_step").data(mapper.writeValueAsString(thoughtPayload)));

                    SmartRetrievalPipeline pipeline = new SmartRetrievalPipeline(hybridCodeSearchService, fastModel, workspaceRoot);
                    long tPipeline = System.nanoTime();
                    answer = pipeline.run(req.goal);
                    pipelineMs = (System.nanoTime() - tPipeline) / 1_000_000L;

                    if (isInsufficientAnswer(answer)) {
                        route = "SmartRetrieval->JsonReActAgent";
                        ObjectNode fallbackPayload = mapper.createObjectNode().put("text", "SmartRetrieval insufficient, falling back to Agent...");
                        emitter.send(SseEmitter.event().name("agent_step").data(mapper.writeValueAsString(fallbackPayload)));

                        JsonReActAgent agent = new JsonReActAgent(mapper, model, fastModel, search, hybridCodeSearchService, traceId, workspaceRoot, sessionRoot, traceId, workspaceRoot, indexingWorker, bootstrapServers, chatHistory, ideContextPath, toolExecutionService, runtimeService, eventStream, skillManager);
                        agentUsed = agent;
                        String augmentedGoal = req.goal + "\n\n(Note: Automatic search failed to find enough context. Please use tools like LIST_FILES or READ_FILE to explore the project structure and key config files explicitly.)";
                        long tAgent = System.nanoTime();
                        answer = agent.run(augmentedGoal);
                        agentMs = (System.nanoTime() - tAgent) / 1_000_000L;
                        // Applied changes tracked separately; no action here.
                    }
                } else {
                    route = "JsonReActAgent";
                    JsonReActAgent agent = new JsonReActAgent(mapper, model, fastModel, search, hybridCodeSearchService, traceId, workspaceRoot, sessionRoot, traceId, workspaceRoot, indexingWorker, bootstrapServers, chatHistory, ideContextPath, toolExecutionService, runtimeService, eventStream, skillManager);
                    agentUsed = agent;
                    long tAgent = System.nanoTime();
                    answer = agent.run(req.goal);
                    agentMs = (System.nanoTime() - tAgent) / 1_000_000L;
                }

                // Collect applied changes
                appliedChanges.clear();
                for (AppliedChangesManager.AppliedChange pc : AppliedChangesManager.getInstance().getChanges(workspaceRoot, traceId)) {
                    Map<String, Object> map = new HashMap<>();
                    map.put("id", pc.id);
                    map.put("path", pc.path);
                    map.put("type", pc.type);
                    map.put("oldContent", pc.oldContent);
                    map.put("newContent", pc.newContent);
                    map.put("timestamp", pc.timestamp);
                    map.put("workspaceRoot", pc.workspaceRoot);
                    map.put("sessionId", pc.sessionId);
                    appliedChanges.add(map);
                }

                // Collect pending changes for UI
                List<Map<String, Object>> pendingChangesList = new ArrayList<>();
                for (PendingChangesManager.PendingChange pc : PendingChangesManager.getInstance().getChanges(workspaceRoot, traceId)) {
                    Map<String, Object> map = new HashMap<>();
                    map.put("id", pc.id);
                    map.put("path", pc.path);
                    map.put("type", pc.type);
                    map.put("oldContent", pc.oldContent);
                    map.put("newContent", pc.newContent);
                    map.put("timestamp", pc.timestamp);
                    map.put("workspaceRoot", pc.workspaceRoot);
                    map.put("sessionId", pc.sessionId);
                    pendingChangesList.add(map);
                }

                answer = contextService.fixMojibakeIfNeeded(answer);
                
                long totalMs = (System.nanoTime() - t0) / 1_000_000L;
                Map<String, Object> meta = new HashMap<>();
                meta.put("intent", intent.name());
                meta.put("route", route);
                if (!appliedChanges.isEmpty()) {
                    meta.put("appliedChanges", appliedChanges);
                }
                if (!pendingChangesList.isEmpty()) {
                    meta.put("pendingChanges", pendingChangesList);
                }
                meta.put("latency_ms_total", totalMs);
                meta.put("latency_ms_pipeline", pipelineMs);
                meta.put("latency_ms_agent", agentMs);
                if (agentUsed != null) {
                    meta.put("tool_calls", agentUsed.getToolCallCount());
                    meta.put("tool_errors", agentUsed.getToolErrorCount());
                    meta.put("turns_used", agentUsed.getTurnsUsed());
                    meta.put("auto_skills", agentUsed.getAutoSkillsLoaded());
                    meta.put("edits_applied", agentUsed.getEditsApplied());
                    meta.put("edits_rejected", agentUsed.getEditsRejected());
                }
                
                ChatResponse resp = new ChatResponse(traceId, answer, topic.isNewTopic, topic.title, meta);
                emitter.send(SseEmitter.event().name("finish").data(mapper.writeValueAsString(resp)));
                emitter.complete();

            } catch (Exception e) {
                logger.error("chatStream.fail traceId={} err={}", traceId, e.toString());
                try {
                    emitter.send(SseEmitter.event().name("error").data(contextService.rootMessage(e)));
                } catch (Exception ignored) {}
                emitter.completeWithError(e);
            } finally {
                if (eventStream != null) {
                    eventStream.close();
                }
                if (sessionWorkspace != null) {
                    sessionWorkspace.cleanup();
                }
                AppliedChangesManager.getInstance().clear(workspaceRoot, traceId);
                MDC.remove("traceId");
            }
        });
        return emitter;
    }

    private boolean isInsufficientAnswer(String answer) {
        if (answer == null) {
            return true;
        }
        String lower = answer.toLowerCase();
        return lower.contains("i could not find") ||
                lower.contains("search returned 0 hits") ||
                lower.contains("无法回答") ||
                lower.contains("无法确定") ||
                lower.contains("不足以") ||
                lower.contains("缺少") ||
                lower.contains("need more context");
    }


    private long resolveStreamTimeoutMs() {
        String prop = System.getProperty("codeagent.stream.timeout.ms");
        if (prop != null && !prop.trim().isEmpty()) {
            try {
                long v = Long.parseLong(prop.trim());
                if (v > 0) return v;
            } catch (Exception ignored) {}
        }
        String env = System.getenv("CODEAGENT_STREAM_TIMEOUT_MS");
        if (env != null && !env.trim().isEmpty()) {
            try {
                long v = Long.parseLong(env.trim());
                if (v > 0) return v;
            } catch (Exception ignored) {}
        }
        return 900_000L; // 15 mins
    }
}
