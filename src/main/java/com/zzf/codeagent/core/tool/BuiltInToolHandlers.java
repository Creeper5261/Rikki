package com.zzf.codeagent.core.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzf.codeagent.core.event.EventSource;
import com.zzf.codeagent.core.event.EventStream;
import com.zzf.codeagent.core.rag.pipeline.FullScanService;
import com.zzf.codeagent.core.rag.pipeline.SynchronousCodeIngestionPipeline;
import com.zzf.codeagent.core.rag.search.CodeSearchQuery;
import com.zzf.codeagent.core.rag.search.CodeSearchResponse;
import com.zzf.codeagent.core.rag.search.InMemoryCodeSearchService;
import com.zzf.codeagent.core.runtime.CommandRequest;
import com.zzf.codeagent.core.runtime.CommandResult;
import com.zzf.codeagent.core.runtime.ExecutionMode;
import com.zzf.codeagent.core.runtime.RuntimeType;
import com.zzf.codeagent.core.skill.Skill;
import com.zzf.codeagent.core.tool.ToolProtocol.ToolEnvelope;
import com.zzf.codeagent.core.tool.ToolProtocol.ToolResult;
import com.zzf.codeagent.core.tool.ToolProtocol.ToolSpec;
import com.zzf.codeagent.core.tools.FileSystemToolService;
import com.zzf.codeagent.core.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class BuiltInToolHandlers {
    private BuiltInToolHandlers() {}

    public static void registerAll(ToolRegistry registry) {
        registry.register(new SearchKnowledgeTool());
        registry.register(new ListFilesTool());
        registry.register(new GrepTool());
        registry.register(new ReadFileTool());
        registry.register(new OpenFileViewTool());
        registry.register(new ScrollFileViewTool());
        registry.register(new GotoFileViewTool());
        registry.register(new SearchFileTool());
        registry.register(new RepoMapTool());
        registry.register(new StructureMapTool());
        registry.register(new StrReplaceEditorTool());
        registry.register(new EditFileTool());
        registry.register(new CreateFileTool());
        registry.register(new InsertLineTool());
        registry.register(new UndoEditTool());
        registry.register(new DeleteFileTool());
        registry.register(new ApplyPatchTool());
        registry.register(new BatchReplaceTool());
        registry.register(new RunCommandTool());
        registry.register(new TriggerIndexTool());
        registry.register(new ApplyPendingDiffTool());
        registry.register(new ReplaceLinesTool());
        registry.register(new CreateDirectoryTool());
        registry.register(new MovePathTool());
        registry.register(new LoadSkillTool());
    }

    private static final class SearchKnowledgeTool implements ToolHandler {
        private static final Logger logger = LoggerFactory.getLogger(SearchKnowledgeTool.class);
        private final ToolSpec spec;

        private SearchKnowledgeTool() {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode input = objectSchema(mapper, Map.of(
                    "query", stringSchema(mapper),
                    "topK", integerSchema(mapper)
            ), new String[] { "query" });
            ObjectNode output = objectSchema(mapper, Map.of(
                    "hits", integerSchema(mapper),
                    "engine", stringSchema(mapper),
                    "error", stringSchema(mapper),
                    "result", objectSchema(mapper, Map.of(
                            "hits", arraySchema(mapper, objectSchema(mapper, Map.of(
                                    "filePath", stringSchema(mapper),
                                    "startLine", integerSchema(mapper),
                                    "endLine", integerSchema(mapper),
                                    "symbolName", stringSchema(mapper),
                                    "symbolKind", stringSchema(mapper),
                                    "score", numberSchema(mapper),
                                    "snippet", stringSchema(mapper)
                            ), new String[] {}))
                    ), new String[] { "hits" })
            ), new String[] { "hits", "engine", "error" });
            this.spec = new ToolSpec("SEARCH_KNOWLEDGE", ToolProtocol.DEFAULT_VERSION, "Search codebase by query", input, output);
        }

        @Override
        public ToolSpec spec() {
            return spec;
        }

        @Override
        public ToolResult execute(ToolEnvelope env, ToolExecutionContext ctx) {
            long t0 = System.nanoTime();
            String q = env.getArgs().path("query").asText("");
            int topK = env.getArgs().path("topK").asInt(5);
            logger.info("tool.call traceId={} tool={} q={} topK={}", ctx.traceId, env.getTool(), truncate(q, 200), topK);
            if (ctx.lastQuery != null && ctx.lastQuery.equals(q) && ctx.sameQueryCount >= 2) {
                return ToolResult.error(env.getTool(), env.getVersion(), "repeated_query")
                        .withExtra("query", ctx.mapper.valueToTree(q))
                        .withTookMs((System.nanoTime() - t0) / 1_000_000L);
            }

            boolean useHybrid = ctx.hybridSearch != null && ctx.workspaceRoot != null && !ctx.workspaceRoot.isEmpty();
            CodeSearchResponse resp;
            String engine;
            if (useHybrid) {
                try {
                    resp = new CodeSearchResponse(ctx.hybridSearch.search(ctx.workspaceRoot, q, topK));
                } catch (Exception ex) {
                    resp = new CodeSearchResponse(Collections.emptyList(), ex.getMessage());
                }
                engine = "hybrid";
                logger.info("tool.result traceId={} tool={} engine={} hits={} error={}", ctx.traceId, env.getTool(), engine, resp.getHits().size(), resp.getError());
            } else {
                resp = ctx.search.search(new CodeSearchQuery(q, topK, 800));
                engine = "elasticsearch";
                logger.info("tool.result traceId={} tool={} engine={} hits={} error={}", ctx.traceId, env.getTool(), engine, resp.getHits().size(), resp.getError());

                boolean hitsEmpty = resp.getHits().isEmpty();
                boolean hasError = resp.getError() != null && !resp.getError().isEmpty();
                boolean shouldFallback = !ctx.workspaceRoot.isEmpty() && (hitsEmpty || hasError);
                logger.info("fallback.check traceId={} shouldFallback={} workspaceRootPresent={} hitsEmpty={} hasError={}",
                        ctx.traceId, shouldFallback, !ctx.workspaceRoot.isEmpty(), hitsEmpty, hasError);
                if (shouldFallback) {
                    try {
                        Path root = Paths.get(ctx.workspaceRoot);
                        if (Files.exists(root) && Files.isDirectory(root)) {
                            if (!ctx.memoryIndexed) {
                                logger.info("fallback.index traceId={} workspaceRoot={}", ctx.traceId, ctx.workspaceRoot);
                                ctx.memory = new InMemoryCodeSearchService();
                                try {
                                    new SynchronousCodeIngestionPipeline(ctx.memory).ingest(root);
                                } catch (Exception ex) {
                                    logger.warn("fallback.index failed traceId={} err={}", ctx.traceId, ex.toString());
                                    ctx.memory = null;
                                } finally {
                                    ctx.memoryIndexed = true;
                                }
                            }
                            if (ctx.memory != null) {
                                CodeSearchResponse memResp = ctx.memory.search(new CodeSearchQuery(q, topK, 800));
                                engine = "memory";
                                logger.info("fallback.search traceId={} hits={} error={}", ctx.traceId, memResp.getHits().size(), memResp.getError());
                                resp = memResp;
                            }
                        }
                    } catch (Exception ex) {
                        logger.warn("fallback.fail traceId={} err={}", ctx.traceId, ex.toString());
                    }
                }
            }
            String error = resp.getError() == null ? "" : resp.getError();
            ObjectNode result = ctx.mapper.valueToTree(resp);
            ToolResult out = error.isEmpty()
                    ? ToolResult.ok(env.getTool(), env.getVersion(), result)
                    : ToolResult.error(env.getTool(), env.getVersion(), error).withExtra("result", result);
            if (resp.getHits().isEmpty()) {
                out = out.withHint("No search results found. Suggest using LIST_FILES/GREP/READ_FILE or adjusting keywords.");
            } else {
                out = out.withHint("Search results obtained (summary truncated to 800 chars). To analyze code logic deeply, you MUST call READ_FILE on key files.");
            }
            out = out.withExtra("query", ctx.mapper.valueToTree(q))
                    .withExtra("engine", ctx.mapper.valueToTree(engine))
                    .withExtra("hits", ctx.mapper.valueToTree(resp.getHits().size()))
                    .withTookMs((System.nanoTime() - t0) / 1_000_000L);
            return out;
        }
    }

    private static final class ListFilesTool implements ToolHandler {
        private static final Logger logger = LoggerFactory.getLogger(ListFilesTool.class);
        private final ToolSpec spec;

        private ListFilesTool() {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode input = objectSchema(mapper, Map.of(
                    "path", stringSchema(mapper),
                    "glob", stringSchema(mapper),
                    "maxResults", integerSchema(mapper),
                    "maxDepth", integerSchema(mapper)
            ), new String[] {});
            ObjectNode output = objectSchema(mapper, Map.of(
                    "files", arraySchema(mapper, stringSchema(mapper)),
                    "truncated", booleanSchema(mapper),
                    "error", stringSchema(mapper)
            ), new String[] { "files", "truncated", "error" });
            this.spec = new ToolSpec("LIST_FILES", ToolProtocol.DEFAULT_VERSION, "List files recursively under a path", input, output);
        }

        @Override
        public ToolSpec spec() {
            return spec;
        }

        @Override
        public ToolResult execute(ToolEnvelope env, ToolExecutionContext ctx) {
            long t0 = System.nanoTime();
            String path = env.getArgs().path("path").asText("");
            String glob = env.getArgs().path("glob").asText("");
            Integer maxResults = JsonUtils.intOrNull(env.getArgs(), "maxResults", "max_results");
            Integer maxDepth = JsonUtils.intOrNull(env.getArgs(), "maxDepth", "max_depth");
            logger.info("tool.call traceId={} tool={} path={} glob={} maxResults={} maxDepth={}", ctx.traceId, env.getTool(), truncate(path, 200), truncate(glob, 200), maxResults, maxDepth);
            FileSystemToolService.ListFilesResult r = ctx.fs.listFiles(path, glob, maxResults, maxDepth);
            logger.info("tool.result traceId={} tool={} files={} truncated={} error={}", ctx.traceId, env.getTool(), r.files == null ? 0 : r.files.size(), r.truncated, r.error);
            ToolResult out = (r.error == null || r.error.trim().isEmpty())
                    ? ToolResult.ok(env.getTool(), env.getVersion(), ctx.mapper.valueToTree(r))
                    : ToolResult.error(env.getTool(), env.getVersion(), r.error).withExtra("result", ctx.mapper.valueToTree(r));
            if (r.error != null && !r.error.trim().isEmpty()) {
                out = out.withHint("LIST_FILES failed. Check if path exists and is within workspace.");
            } else {
                out = out.withHint("File structure listed. If output is truncated or you need a high-level overview, use REPO_MAP (Project Structure) or STRUCTURE_MAP (Simple Tree) instead. Suggestions: 1. Use GREP to find specific code; 2. Use READ_FILE if file located.");
            }
            return out.withTookMs((System.nanoTime() - t0) / 1_000_000L);
        }
    }

    private static final class GrepTool implements ToolHandler {
        private static final Logger logger = LoggerFactory.getLogger(GrepTool.class);
        private final ToolSpec spec;

        private GrepTool() {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode input = objectSchema(mapper, Map.of(
                    "pattern", stringSchema(mapper),
                    "root", stringSchema(mapper),
                    "file_glob", stringSchema(mapper),
                    "maxMatches", integerSchema(mapper),
                    "maxFiles", integerSchema(mapper),
                    "contextLines", integerSchema(mapper)
            ), new String[] { "pattern" });
            ObjectNode output = objectSchema(mapper, Map.of(
                    "matches", arraySchema(mapper, objectSchema(mapper, Map.of(
                            "filePath", stringSchema(mapper),
                            "lineNumber", integerSchema(mapper),
                            "lineContent", stringSchema(mapper),
                            "before", arraySchema(mapper, stringSchema(mapper)),
                            "after", arraySchema(mapper, stringSchema(mapper))
                    ), new String[] {})),
                    "truncated", booleanSchema(mapper),
                    "error", stringSchema(mapper)
            ), new String[] { "matches", "truncated", "error" });
            this.spec = new ToolSpec("GREP", ToolProtocol.DEFAULT_VERSION, "Search file contents by regex", input, output);
        }

        @Override
        public ToolSpec spec() {
            return spec;
        }

        @Override
        public ToolResult execute(ToolEnvelope env, ToolExecutionContext ctx) {
            long t0 = System.nanoTime();
            String pattern = env.getArgs().path("pattern").asText("");
            String root = env.getArgs().path("root").asText("");
            String fileGlob = JsonUtils.textOrFallback(env.getArgs(), "file_glob", "fileGlob");
            Integer maxMatches = JsonUtils.intOrNull(env.getArgs(), "maxMatches", "max_matches");
            Integer maxFiles = JsonUtils.intOrNull(env.getArgs(), "maxFiles", "max_files");
            Integer contextLines = JsonUtils.intOrNull(env.getArgs(), "contextLines", "context_lines");
            logger.info("tool.call traceId={} tool={} root={} fileGlob={} maxMatches={} maxFiles={} pattern={}", ctx.traceId, env.getTool(), truncate(root, 200), truncate(fileGlob, 200), maxMatches, maxFiles, truncate(pattern, 200));
            FileSystemToolService.GrepResult r = ctx.fs.grep(pattern, root, fileGlob, maxMatches, maxFiles, contextLines);
            logger.info("tool.result traceId={} tool={} matches={} truncated={} error={}", ctx.traceId, env.getTool(), r.matches == null ? 0 : r.matches.size(), r.truncated, r.error);
            ToolResult out = (r.error == null || r.error.trim().isEmpty())
                    ? ToolResult.ok(env.getTool(), env.getVersion(), ctx.mapper.valueToTree(r))
                    : ToolResult.error(env.getTool(), env.getVersion(), r.error).withExtra("result", ctx.mapper.valueToTree(r));
            if (r.error != null && !r.error.trim().isEmpty()) {
                out = out.withHint("GREP failed. Check root path and regex.");
            } else {
                out = out.withHint("GREP search completed. Suggestions: 1. Call READ_FILE on interesting matches for context; 2. If too many results, try optimizing pattern or file_glob.");
            }
            return out.withTookMs((System.nanoTime() - t0) / 1_000_000L);
        }
    }

    private static final class ReadFileTool implements ToolHandler {
        private static final Logger logger = LoggerFactory.getLogger(ReadFileTool.class);
        private final ToolSpec spec;

        private ReadFileTool() {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode input = objectSchema(mapper, Map.of(
                    "path", stringSchema(mapper),
                    "startLine", integerSchema(mapper),
                    "endLine", integerSchema(mapper),
                    "maxChars", integerSchema(mapper)
            ), new String[] { "path" });
            ObjectNode output = objectSchema(mapper, Map.of(
                    "filePath", stringSchema(mapper),
                    "startLine", integerSchema(mapper),
                    "endLine", integerSchema(mapper),
                    "truncated", booleanSchema(mapper),
                    "content", stringSchema(mapper),
                    "error", stringSchema(mapper)
            ), new String[] { "filePath", "startLine", "endLine", "truncated", "content", "error" });
            this.spec = new ToolSpec("READ_FILE", ToolProtocol.DEFAULT_VERSION, "Read a file from workspace", input, output);
        }

        @Override
        public ToolSpec spec() {
            return spec;
        }

        @Override
        public ToolResult execute(ToolEnvelope env, ToolExecutionContext ctx) {
            long t0 = System.nanoTime();
            String path = env.getArgs().path("path").asText("");
            Integer start = JsonUtils.intOrNull(env.getArgs(), "startLine", "start_line");
            Integer end = JsonUtils.intOrNull(env.getArgs(), "endLine", "end_line");
            Integer maxChars = JsonUtils.intOrNull(env.getArgs(), "maxChars", "max_chars");
            logger.info("tool.call traceId={} tool={} path={} startLine={} endLine={} maxChars={}", ctx.traceId, env.getTool(), truncate(path, 200), start, end, maxChars);
            FileSystemToolService.ReadFileResult r = ctx.fs.readFile(path, start, end, maxChars);
            logger.info("tool.result traceId={} tool={} filePath={} truncated={} error={}", ctx.traceId, env.getTool(), r.filePath, r.truncated, r.error);
            ToolResult out = (r.error == null || r.error.trim().isEmpty())
                    ? ToolResult.ok(env.getTool(), env.getVersion(), ctx.mapper.valueToTree(r))
                    : ToolResult.error(env.getTool(), env.getVersion(), r.error).withExtra("result", ctx.mapper.valueToTree(r));
            if (r.error != null && !r.error.trim().isEmpty()) {
                out = out.withHint("READ_FILE failed. Check if path exists and is within workspace.");
            } else {
                out = out.withHint("File content read. Suggestions: 1. Extract key facts to facts; 2. Adjust startLine/endLine for more context; 3. If info sufficient, output type=final.");
            }
            return out.withTookMs((System.nanoTime() - t0) / 1_000_000L);
        }
    }

    private static final class OpenFileViewTool implements ToolHandler {
        private static final Logger logger = LoggerFactory.getLogger(OpenFileViewTool.class);
        private final ToolSpec spec;

        private OpenFileViewTool() {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode input = objectSchema(mapper, Map.of(
                    "path", stringSchema(mapper),
                    "lineNumber", integerSchema(mapper),
                    "window", integerSchema(mapper),
                    "maxChars", integerSchema(mapper)
            ), new String[] { "path" });
            ObjectNode output = objectSchema(mapper, Map.of(
                    "filePath", stringSchema(mapper),
                    "startLine", integerSchema(mapper),
                    "endLine", integerSchema(mapper),
                    "totalLines", integerSchema(mapper),
                    "truncated", booleanSchema(mapper),
                    "content", stringSchema(mapper),
                    "error", stringSchema(mapper),
                    "hasMoreAbove", booleanSchema(mapper),
                    "hasMoreBelow", booleanSchema(mapper),
                    "window", integerSchema(mapper)
            ), new String[] { "filePath", "startLine", "endLine", "totalLines", "truncated", "content", "error", "hasMoreAbove", "hasMoreBelow", "window" });
            this.spec = new ToolSpec("OPEN_FILE_VIEW", ToolProtocol.DEFAULT_VERSION, "Open a file window view", input, output);
        }

        @Override
        public ToolSpec spec() {
            return spec;
        }

        @Override
        public ToolResult execute(ToolEnvelope env, ToolExecutionContext ctx) {
            long t0 = System.nanoTime();
            String path = env.getArgs().path("path").asText("");
            Integer line = JsonUtils.intOrNull(env.getArgs(), "lineNumber", "line_number");
            Integer window = JsonUtils.intOrNull(env.getArgs(), "window", "window");
            Integer maxChars = JsonUtils.intOrNull(env.getArgs(), "maxChars", "max_chars");
            logger.info("tool.call traceId={} tool={} path={} line={} window={}", ctx.traceId, env.getTool(), truncate(path, 200), line, window);
            FileSystemToolService.FileViewResult r = ctx.fs.viewFile(path, line, window, maxChars);
            logger.info("tool.result traceId={} tool={} filePath={} truncated={} error={}", ctx.traceId, env.getTool(), r.filePath, r.truncated, r.error);
            ToolResult out = (r.error == null || r.error.trim().isEmpty())
                    ? ToolResult.ok(env.getTool(), env.getVersion(), ctx.mapper.valueToTree(r))
                    : ToolResult.error(env.getTool(), env.getVersion(), r.error).withExtra("result", ctx.mapper.valueToTree(r));
            if (r.error != null && !r.error.trim().isEmpty()) {
                out = out.withHint("OPEN_FILE_VIEW failed. Check path and file size.");
            } else {
                out = out.withHint("File view opened. Suggestions: 1. Use SCROLL_FILE_VIEW or GOTO_FILE_VIEW for navigation; 2. Use SEARCH_FILE for quick定位; 3. Use READ_FILE if you need exact ranges.");
                updateWorkspaceView(ctx.eventStream, ctx.mapper, r);
            }
            return out.withTookMs((System.nanoTime() - t0) / 1_000_000L);
        }
    }

    private static final class ScrollFileViewTool implements ToolHandler {
        private static final Logger logger = LoggerFactory.getLogger(ScrollFileViewTool.class);
        private final ToolSpec spec;

        private ScrollFileViewTool() {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode input = objectSchema(mapper, Map.of(
                    "direction", stringSchema(mapper),
                    "lines", integerSchema(mapper),
                    "overlap", integerSchema(mapper),
                    "path", stringSchema(mapper),
                    "maxChars", integerSchema(mapper)
            ), new String[] {});
            ObjectNode output = objectSchema(mapper, Map.of(
                    "filePath", stringSchema(mapper),
                    "startLine", integerSchema(mapper),
                    "endLine", integerSchema(mapper),
                    "totalLines", integerSchema(mapper),
                    "truncated", booleanSchema(mapper),
                    "content", stringSchema(mapper),
                    "error", stringSchema(mapper),
                    "hasMoreAbove", booleanSchema(mapper),
                    "hasMoreBelow", booleanSchema(mapper),
                    "window", integerSchema(mapper)
            ), new String[] { "filePath", "startLine", "endLine", "totalLines", "truncated", "content", "error", "hasMoreAbove", "hasMoreBelow", "window" });
            this.spec = new ToolSpec("SCROLL_FILE_VIEW", ToolProtocol.DEFAULT_VERSION, "Scroll the current file window", input, output);
        }

        @Override
        public ToolSpec spec() {
            return spec;
        }

        @Override
        public ToolResult execute(ToolEnvelope env, ToolExecutionContext ctx) {
            long t0 = System.nanoTime();
            String direction = env.getArgs().path("direction").asText("down");
            Integer lines = JsonUtils.intOrNull(env.getArgs(), "lines", "lines");
            Integer overlapArg = JsonUtils.intOrNull(env.getArgs(), "overlap", "overlap");
            Integer maxChars = JsonUtils.intOrNull(env.getArgs(), "maxChars", "max_chars");
            String path = env.getArgs().path("path").asText("");
            EventStream stream = ctx.eventStream;
            ObjectNode ws = stream == null ? null : stream.getStore().getWorkspaceState();
            String activeFile = path == null || path.trim().isEmpty() ? (ws == null ? "" : ws.path("activeFile").asText("")) : path;
            if (activeFile == null || activeFile.trim().isEmpty()) {
                return ToolResult.error(env.getTool(), env.getVersion(), "no_active_file")
                        .withTookMs((System.nanoTime() - t0) / 1_000_000L);
            }
            int currentStart = ws == null ? 1 : ws.path("viewStart").asInt(1);
            int currentWindow = ws == null ? 0 : ws.path("viewWindow").asInt(0);
            int delta = lines == null || lines.intValue() <= 0 ? (currentWindow > 0 ? currentWindow : 200) : lines.intValue();
            int overlap = overlapArg == null || overlapArg.intValue() < 0 ? 0 : overlapArg.intValue();
            if (overlap >= delta && delta > 0) {
                overlap = delta - 1;
            }
            int nextStart = currentStart;
            if ("up".equalsIgnoreCase(direction)) {
                nextStart = Math.max(1, currentStart - delta + overlap);
            } else if ("down".equalsIgnoreCase(direction)) {
                nextStart = currentStart + delta - overlap;
            } else {
                return ToolResult.error(env.getTool(), env.getVersion(), "bad_direction")
                        .withTookMs((System.nanoTime() - t0) / 1_000_000L);
            }
            Integer window = currentWindow > 0 ? currentWindow : null;
            logger.info("tool.call traceId={} tool={} path={} direction={} lines={}", ctx.traceId, env.getTool(), truncate(activeFile, 200), direction, delta);
            FileSystemToolService.FileViewResult r = ctx.fs.viewFile(activeFile, nextStart, window, maxChars);
            logger.info("tool.result traceId={} tool={} filePath={} truncated={} error={}", ctx.traceId, env.getTool(), r.filePath, r.truncated, r.error);
            ToolResult out = (r.error == null || r.error.trim().isEmpty())
                    ? ToolResult.ok(env.getTool(), env.getVersion(), ctx.mapper.valueToTree(r))
                    : ToolResult.error(env.getTool(), env.getVersion(), r.error).withExtra("result", ctx.mapper.valueToTree(r));
            if (r.error != null && !r.error.trim().isEmpty()) {
                out = out.withHint("SCROLL_FILE_VIEW failed. Check active file and bounds.");
            } else {
                out = out.withHint("File view scrolled. Suggestions: 1. Continue scroll if needed; 2. Use GOTO_FILE_VIEW for precise line; 3. Use SEARCH_FILE to locate terms.");
                updateWorkspaceView(ctx.eventStream, ctx.mapper, r);
            }
            return out.withTookMs((System.nanoTime() - t0) / 1_000_000L);
        }
    }

    private static final class GotoFileViewTool implements ToolHandler {
        private static final Logger logger = LoggerFactory.getLogger(GotoFileViewTool.class);
        private final ToolSpec spec;

        private GotoFileViewTool() {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode input = objectSchema(mapper, Map.of(
                    "lineNumber", integerSchema(mapper),
                    "path", stringSchema(mapper),
                    "window", integerSchema(mapper),
                    "maxChars", integerSchema(mapper)
            ), new String[] { "lineNumber" });
            ObjectNode output = objectSchema(mapper, Map.of(
                    "filePath", stringSchema(mapper),
                    "startLine", integerSchema(mapper),
                    "endLine", integerSchema(mapper),
                    "totalLines", integerSchema(mapper),
                    "truncated", booleanSchema(mapper),
                    "content", stringSchema(mapper),
                    "error", stringSchema(mapper),
                    "hasMoreAbove", booleanSchema(mapper),
                    "hasMoreBelow", booleanSchema(mapper),
                    "window", integerSchema(mapper)
            ), new String[] { "filePath", "startLine", "endLine", "totalLines", "truncated", "content", "error", "hasMoreAbove", "hasMoreBelow", "window" });
            this.spec = new ToolSpec("GOTO_FILE_VIEW", ToolProtocol.DEFAULT_VERSION, "Jump to a line in file view", input, output);
        }

        @Override
        public ToolSpec spec() {
            return spec;
        }

        @Override
        public ToolResult execute(ToolEnvelope env, ToolExecutionContext ctx) {
            long t0 = System.nanoTime();
            Integer line = JsonUtils.intOrNull(env.getArgs(), "lineNumber", "line_number");
            Integer window = JsonUtils.intOrNull(env.getArgs(), "window", "window");
            Integer maxChars = JsonUtils.intOrNull(env.getArgs(), "maxChars", "max_chars");
            String path = env.getArgs().path("path").asText("");
            EventStream stream = ctx.eventStream;
            ObjectNode ws = stream == null ? null : stream.getStore().getWorkspaceState();
            String activeFile = path == null || path.trim().isEmpty() ? (ws == null ? "" : ws.path("activeFile").asText("")) : path;
            if (activeFile == null || activeFile.trim().isEmpty()) {
                return ToolResult.error(env.getTool(), env.getVersion(), "no_active_file")
                        .withTookMs((System.nanoTime() - t0) / 1_000_000L);
            }
            logger.info("tool.call traceId={} tool={} path={} line={} window={}", ctx.traceId, env.getTool(), truncate(activeFile, 200), line, window);
            FileSystemToolService.FileViewResult r = ctx.fs.viewFile(activeFile, line, window, maxChars);
            logger.info("tool.result traceId={} tool={} filePath={} truncated={} error={}", ctx.traceId, env.getTool(), r.filePath, r.truncated, r.error);
            ToolResult out = (r.error == null || r.error.trim().isEmpty())
                    ? ToolResult.ok(env.getTool(), env.getVersion(), ctx.mapper.valueToTree(r))
                    : ToolResult.error(env.getTool(), env.getVersion(), r.error).withExtra("result", ctx.mapper.valueToTree(r));
            if (r.error != null && !r.error.trim().isEmpty()) {
                out = out.withHint("GOTO_FILE_VIEW failed. Check line and file.");
            } else {
                out = out.withHint("File view moved. Suggestions: 1. Use SCROLL_FILE_VIEW to browse; 2. Use SEARCH_FILE for keywords; 3. Use READ_FILE for exact ranges.");
                updateWorkspaceView(ctx.eventStream, ctx.mapper, r);
            }
            return out.withTookMs((System.nanoTime() - t0) / 1_000_000L);
        }
    }

    private static final class SearchFileTool implements ToolHandler {
        private static final Logger logger = LoggerFactory.getLogger(SearchFileTool.class);
        private final ToolSpec spec;

        private SearchFileTool() {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode input = objectSchema(mapper, Map.of(
                    "pattern", stringSchema(mapper),
                    "path", stringSchema(mapper),
                    "maxMatches", integerSchema(mapper),
                    "maxLines", integerSchema(mapper)
            ), new String[] { "pattern" });
            ObjectNode output = objectSchema(mapper, Map.of(
                    "filePath", stringSchema(mapper),
                    "matches", integerSchema(mapper),
                    "lines", integerSchema(mapper),
                    "hits", arraySchema(mapper, objectSchema(mapper, Map.of(
                            "line", integerSchema(mapper),
                            "text", stringSchema(mapper)
                    ), new String[] {})),
                    "truncated", booleanSchema(mapper),
                    "error", stringSchema(mapper)
            ), new String[] { "filePath", "matches", "lines", "hits", "truncated", "error" });
            this.spec = new ToolSpec("SEARCH_FILE", ToolProtocol.DEFAULT_VERSION, "Search text in a file", input, output);
        }

        @Override
        public ToolSpec spec() {
            return spec;
        }

        @Override
        public ToolResult execute(ToolEnvelope env, ToolExecutionContext ctx) {
            long t0 = System.nanoTime();
            String pattern = env.getArgs().path("pattern").asText("");
            String path = env.getArgs().path("path").asText("");
            Integer maxMatches = JsonUtils.intOrNull(env.getArgs(), "maxMatches", "max_matches");
            Integer maxLines = JsonUtils.intOrNull(env.getArgs(), "maxLines", "max_lines");
            EventStream stream = ctx.eventStream;
            ObjectNode ws = stream == null ? null : stream.getStore().getWorkspaceState();
            String activeFile = path == null || path.trim().isEmpty() ? (ws == null ? "" : ws.path("activeFile").asText("")) : path;
            if (activeFile == null || activeFile.trim().isEmpty()) {
                return ToolResult.error(env.getTool(), env.getVersion(), "no_active_file")
                        .withTookMs((System.nanoTime() - t0) / 1_000_000L);
            }
            logger.info("tool.call traceId={} tool={} path={} pattern={}", ctx.traceId, env.getTool(), truncate(activeFile, 200), truncate(pattern, 200));
            FileSystemToolService.FileSearchResult r = ctx.fs.searchInFile(activeFile, pattern, maxMatches, maxLines);
            logger.info("tool.result traceId={} tool={} matches={} truncated={} error={}", ctx.traceId, env.getTool(), r.matches, r.truncated, r.error);
            ToolResult out = (r.error == null || r.error.trim().isEmpty())
                    ? ToolResult.ok(env.getTool(), env.getVersion(), ctx.mapper.valueToTree(r))
                    : ToolResult.error(env.getTool(), env.getVersion(), r.error).withExtra("result", ctx.mapper.valueToTree(r));
            if (r.error != null && !r.error.trim().isEmpty()) {
                out = out.withHint("SEARCH_FILE failed. Check file and regex.");
            } else if (r.matches == 0) {
                out = out.withHint("No matches found. Try different keywords or SEARCH_KNOWLEDGE/GREP.");
            } else {
                out = out.withHint("Matches found. Use GOTO_FILE_VIEW or READ_FILE for context.");
            }
            return out.withTookMs((System.nanoTime() - t0) / 1_000_000L);
        }
    }

    private static final class RepoMapTool implements ToolHandler {
        private static final Logger logger = LoggerFactory.getLogger(RepoMapTool.class);
        private final ToolSpec spec;

        private RepoMapTool() {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode input = objectSchema(mapper, Map.of(
                    "path", stringSchema(mapper),
                    "maxDepth", integerSchema(mapper),
                    "maxFiles", integerSchema(mapper),
                    "maxChars", integerSchema(mapper)
            ), new String[] {});
            ObjectNode output = objectSchema(mapper, Map.of(
                    "content", stringSchema(mapper),
                    "truncated", booleanSchema(mapper),
                    "totalFiles", integerSchema(mapper),
                    "error", stringSchema(mapper)
            ), new String[] { "content", "truncated", "totalFiles", "error" });
            this.spec = new ToolSpec("REPO_MAP", ToolProtocol.DEFAULT_VERSION, "Efficiently generate a visual tree structure of the repository, prioritizing important files. Use this to understand project layout without listing all files.", input, output);
        }

        @Override
        public ToolSpec spec() {
            return spec;
        }

        @Override
        public ToolResult execute(ToolEnvelope env, ToolExecutionContext ctx) {
            long t0 = System.nanoTime();
            String path = env.getArgs().path("path").asText("");
            Integer maxDepth = JsonUtils.intOrNull(env.getArgs(), "maxDepth", "max_depth");
            Integer maxFiles = JsonUtils.intOrNull(env.getArgs(), "maxFiles", "max_files");
            Integer maxChars = JsonUtils.intOrNull(env.getArgs(), "maxChars", "max_chars");
            EventStream stream = ctx.eventStream;
            ObjectNode ws = stream == null ? null : stream.getStore().getWorkspaceState();
            String activeFile = ws == null ? "" : ws.path("activeFile").asText("");
            String query = extractRepoMapQuery(ws);
            logger.info("tool.call traceId={} tool={} path={} maxDepth={} maxFiles={}", ctx.traceId, env.getTool(), truncate(path, 200), maxDepth, maxFiles);
            Set<String> focusPaths = activeFile == null || activeFile.trim().isEmpty() || !isActiveFileRelevant(activeFile, query)
                    ? null
                    : Set.of(activeFile);
            FileSystemToolService.RepoMapResult r = ctx.fs.repoMap(path, maxDepth, maxFiles, maxChars, focusPaths);
            logger.info("tool.result traceId={} tool={} truncated={} error={}", ctx.traceId, env.getTool(), r.truncated, r.error);
            ToolResult out = (r.error == null || r.error.trim().isEmpty())
                    ? ToolResult.ok(env.getTool(), env.getVersion(), ctx.mapper.valueToTree(r))
                    : ToolResult.error(env.getTool(), env.getVersion(), r.error).withExtra("result", ctx.mapper.valueToTree(r));
            if (r.error != null && !r.error.trim().isEmpty()) {
                out = out.withHint("REPO_MAP failed. Check path.");
            } else {
                out = out.withHint("Repo map generated. Use this structure to guide further exploration. Use SEARCH_KNOWLEDGE/GREP/OPEN_FILE_VIEW to drill down.");
                updateRepoMapState(ctx.eventStream, ctx.mapper, r, path, maxDepth, maxFiles);
            }
            return out.withTookMs((System.nanoTime() - t0) / 1_000_000L);
        }
    }

    private static final class StructureMapTool implements ToolHandler {
        private static final Logger logger = LoggerFactory.getLogger(StructureMapTool.class);
        private final ToolSpec spec;

        private StructureMapTool() {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode input = objectSchema(mapper, Map.of(
                    "path", stringSchema(mapper),
                    "maxDepth", integerSchema(mapper),
                    "maxFiles", integerSchema(mapper),
                    "maxChars", integerSchema(mapper)
            ), new String[] {});
            ObjectNode output = objectSchema(mapper, Map.of(
                    "content", stringSchema(mapper),
                    "truncated", booleanSchema(mapper),
                    "totalFiles", integerSchema(mapper),
                    "error", stringSchema(mapper)
            ), new String[] { "content", "truncated", "totalFiles", "error" });
            this.spec = new ToolSpec("STRUCTURE_MAP", ToolProtocol.DEFAULT_VERSION, "Generate a file tree structure without ranking. Faster than REPO_MAP for simple projects or when you just need a directory listing.", input, output);
        }

        @Override
        public ToolSpec spec() {
            return spec;
        }

        @Override
        public ToolResult execute(ToolEnvelope env, ToolExecutionContext ctx) {
            long t0 = System.nanoTime();
            String path = env.getArgs().path("path").asText("");
            Integer maxDepth = JsonUtils.intOrNull(env.getArgs(), "maxDepth", "max_depth");
            Integer maxFiles = JsonUtils.intOrNull(env.getArgs(), "maxFiles", "max_files");
            Integer maxChars = JsonUtils.intOrNull(env.getArgs(), "maxChars", "max_chars");
            logger.info("tool.call traceId={} tool={} path={} maxDepth={} maxFiles={}", ctx.traceId, env.getTool(), truncate(path, 200), maxDepth, maxFiles);
            FileSystemToolService.RepoMapResult r = ctx.fs.structureMap(path, maxDepth, maxFiles, maxChars);
            logger.info("tool.result traceId={} tool={} truncated={} error={}", ctx.traceId, env.getTool(), r.truncated, r.error);
            ToolResult out = (r.error == null || r.error.trim().isEmpty())
                    ? ToolResult.ok(env.getTool(), env.getVersion(), ctx.mapper.valueToTree(r))
                    : ToolResult.error(env.getTool(), env.getVersion(), r.error).withExtra("result", ctx.mapper.valueToTree(r));
            if (r.error != null && !r.error.trim().isEmpty()) {
                out = out.withHint("STRUCTURE_MAP failed. Check path.");
            } else {
                out = out.withHint("Structure map generated. Use this for navigation. Use REPO_MAP if you need importance ranking.");
                updateRepoMapState(ctx.eventStream, ctx.mapper, r, path, maxDepth, maxFiles);
            }
            return out.withTookMs((System.nanoTime() - t0) / 1_000_000L);
        }
    }

    private static final class EditFileTool implements ToolHandler {
        private static final Logger logger = LoggerFactory.getLogger(EditFileTool.class);
        private final ToolSpec spec;

        private EditFileTool() {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode input = objectSchema(mapper, Map.of(
                    "path", stringSchema(mapper),
                    "old_str", stringSchema(mapper),
                    "new_str", stringSchema(mapper),
                    "dry_run", booleanSchema(mapper)
            ), new String[] { "path", "old_str", "new_str" });
            ObjectNode output = objectSchema(mapper, Map.of(
                    "filePath", stringSchema(mapper),
                    "success", booleanSchema(mapper),
                    "error", stringSchema(mapper),
                    "preview", booleanSchema(mapper),
                    "oldContent", stringSchema(mapper),
                    "newContent", stringSchema(mapper)
            ), new String[] { "filePath", "success", "error", "preview" });
            this.spec = new ToolSpec("EDIT_FILE", ToolProtocol.DEFAULT_VERSION, "Edit a file by exact string replacement", input, output);
        }

        @Override
        public ToolSpec spec() {
            return spec;
        }

        @Override
        public ToolResult execute(ToolEnvelope env, ToolExecutionContext ctx) {
            long t0 = System.nanoTime();
            String path = env.getArgs().path("path").asText();
            String oldStr = env.getArgs().path("old_str").asText();
            String newStr = env.getArgs().path("new_str").asText();
            // Default dry_run to false (apply immediately) unless explicitly set to true
            JsonNode dryRunNode = env.getArgs().path("dry_run");
            boolean dryRun = dryRunNode.isMissingNode() || dryRunNode.isNull() ? false : dryRunNode.asBoolean();
            
            logger.info("tool.call traceId={} tool={} path={} oldLen={} newLen={} dryRun={}", ctx.traceId, env.getTool(), truncate(path, 200), oldStr.length(), newStr.length(), dryRun);
            FileSystemToolService.EditFileResult r = ctx.fs.editFile(path, oldStr, newStr, dryRun);
            logger.info("tool.result traceId={} tool={} success={} error={} preview={}", ctx.traceId, env.getTool(), r.success, r.error, r.preview);
            ToolResult out = r.success
                    ? ToolResult.ok(env.getTool(), env.getVersion(), ctx.mapper.valueToTree(r))
                    : ToolResult.error(env.getTool(), env.getVersion(), r.error == null || r.error.isEmpty() ? "edit_failed" : r.error)
                        .withExtra("result", ctx.mapper.valueToTree(r));
            if (!r.success) {
                out = out.withHint("EDIT_FILE failed. Check if file exists and old_str matches (must be exact). Suggest READ_FILE to confirm content.");
            } else if (r.preview) {
                out = out.withHint("Preview only. No changes applied.");
                updateWorkspaceEdit(ctx.eventStream, ctx.mapper, path, "edit_file", r.oldContent, r.newContent, true, r.prevExist, ctx.workspaceRoot, ctx.traceId);
            } else {
                out = out.withHint("File edited successfully. Suggestions: 1. Run relevant tests to verify; 2. If task done, output type=final.");
                updateWorkspaceEdit(ctx.eventStream, ctx.mapper, path, "edit_file", r.oldContent, r.newContent, false, r.prevExist, ctx.workspaceRoot, ctx.traceId);
            }
            out = out.withExtra("path", ctx.mapper.valueToTree(path));
            return out.withTookMs((System.nanoTime() - t0) / 1_000_000L);
        }
    }

    private static final class CreateFileTool implements ToolHandler {
        private static final Logger logger = LoggerFactory.getLogger(CreateFileTool.class);
        private final ToolSpec spec;

        private CreateFileTool() {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode input = objectSchema(mapper, Map.of(
                    "path", stringSchema(mapper),
                    "content", stringSchema(mapper),
                    "dry_run", booleanSchema(mapper)
            ), new String[] { "path", "content" });
            ObjectNode output = objectSchema(mapper, Map.of(
                    "filePath", stringSchema(mapper),
                    "success", booleanSchema(mapper),
                    "error", stringSchema(mapper),
                    "preview", booleanSchema(mapper),
                    "oldContent", stringSchema(mapper),
                    "newContent", stringSchema(mapper)
            ), new String[] { "filePath", "success", "error", "preview" });
            this.spec = new ToolSpec("CREATE_FILE", ToolProtocol.DEFAULT_VERSION, "Create a new file with content", input, output);
        }

        @Override
        public ToolSpec spec() {
            return spec;
        }

        @Override
        public ToolResult execute(ToolEnvelope env, ToolExecutionContext ctx) {
            long t0 = System.nanoTime();
            String path = env.getArgs().path("path").asText("").trim();
            JsonNode contentNode = env.getArgs().get("content");
            JsonNode contentAlt = env.getArgs().get("file_text");
            JsonNode contentAlt2 = env.getArgs().get("fileText");
            String content = contentNode == null || contentNode.isNull()
                    ? (contentAlt == null || contentAlt.isNull()
                        ? (contentAlt2 == null || contentAlt2.isNull() ? null : contentAlt2.asText(""))
                        : contentAlt.asText(""))
                    : contentNode.asText("");
            // Default dry_run to false (apply to sandbox) unless explicitly set to true
            JsonNode dryRunNode = env.getArgs().path("dry_run");
            boolean dryRun = dryRunNode.isMissingNode() || dryRunNode.isNull() ? false : dryRunNode.asBoolean();
            
            logger.info("tool.call traceId={} tool={} path={} dryRun={}", ctx.traceId, env.getTool(), truncate(path, 200), dryRun);
            if (path.isEmpty()) {
                return ToolResult.error(env.getTool(), env.getVersion(), "path_required")
                        .withTookMs((System.nanoTime() - t0) / 1_000_000L);
            }
            if (content == null) {
                return ToolResult.error(env.getTool(), env.getVersion(), "content_required")
                        .withTookMs((System.nanoTime() - t0) / 1_000_000L);
            }
            FileSystemToolService.EditFileResult r = ctx.fs.createFile(path, content, dryRun);
            logger.info("tool.result traceId={} tool={} success={} error={} preview={}", ctx.traceId, env.getTool(), r.success, r.error, r.preview);
            ToolResult out = r.success
                    ? ToolResult.ok(env.getTool(), env.getVersion(), ctx.mapper.valueToTree(r))
                    : ToolResult.error(env.getTool(), env.getVersion(), r.error == null || r.error.isEmpty() ? "create_failed" : r.error)
                        .withExtra("result", ctx.mapper.valueToTree(r));
            if (r.success) {
                updateWorkspaceEdit(ctx.eventStream, ctx.mapper, path, "create", r.oldContent, r.newContent, r.preview, r.prevExist, ctx.workspaceRoot, ctx.traceId);
                if (!r.preview) {
                    out = out.withHint("File created successfully. Proceed with next steps or output type=final.");
                } else {
                    out = out.withHint("Preview only. No changes applied.");
                }
            } else {
                out = out.withHint("CREATE_FILE failed. Check path and parent directory.");
            }
            return out.withTookMs((System.nanoTime() - t0) / 1_000_000L);
        }
    }

    private static final class InsertLineTool implements ToolHandler {
        private static final Logger logger = LoggerFactory.getLogger(InsertLineTool.class);
        private final ToolSpec spec;

        private InsertLineTool() {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode input = objectSchema(mapper, Map.of(
                    "path", stringSchema(mapper),
                    "lineNumber", integerSchema(mapper),
                    "content", stringSchema(mapper),
                    "dry_run", booleanSchema(mapper)
            ), new String[] { "path", "lineNumber", "content" });
            ObjectNode output = objectSchema(mapper, Map.of(
                    "filePath", stringSchema(mapper),
                    "success", booleanSchema(mapper),
                    "error", stringSchema(mapper),
                    "preview", booleanSchema(mapper),
                    "oldContent", stringSchema(mapper),
                    "newContent", stringSchema(mapper)
            ), new String[] { "filePath", "success", "error", "preview" });
            this.spec = new ToolSpec("INSERT_LINE", ToolProtocol.DEFAULT_VERSION, "Insert content at a line in file", input, output);
        }

        @Override
        public ToolSpec spec() {
            return spec;
        }

        @Override
        public ToolResult execute(ToolEnvelope env, ToolExecutionContext ctx) {
            long t0 = System.nanoTime();
            String path = env.getArgs().path("path").asText("").trim();
            Integer lineNumber = JsonUtils.intOrNull(env.getArgs(), "lineNumber", "line_number");
            if (lineNumber == null) {
                lineNumber = JsonUtils.intOrNull(env.getArgs(), "insert_line", "insertLine");
            }
            JsonNode contentNode = env.getArgs().get("content");
            JsonNode contentAlt = env.getArgs().get("new_str");
            JsonNode contentAlt2 = env.getArgs().get("newStr");
            String content = contentNode == null || contentNode.isNull()
                    ? (contentAlt == null || contentAlt.isNull()
                        ? (contentAlt2 == null || contentAlt2.isNull() ? null : contentAlt2.asText(""))
                        : contentAlt.asText(""))
                    : contentNode.asText("");
            // Default dry_run to false (apply immediately) unless explicitly set to true
            JsonNode dryRunNode = env.getArgs().path("dry_run");
            boolean dryRun = dryRunNode.isMissingNode() || dryRunNode.isNull() ? false : dryRunNode.asBoolean();
            
            logger.info("tool.call traceId={} tool={} path={} line={} dryRun={}", ctx.traceId, env.getTool(), truncate(path, 200), lineNumber, dryRun);
            if (path.isEmpty()) {
                return ToolResult.error(env.getTool(), env.getVersion(), "path_required")
                        .withTookMs((System.nanoTime() - t0) / 1_000_000L);
            }
            if (lineNumber == null || lineNumber.intValue() <= 0) {
                return ToolResult.error(env.getTool(), env.getVersion(), "line_number_invalid")
                        .withTookMs((System.nanoTime() - t0) / 1_000_000L);
            }
            if (content == null) {
                return ToolResult.error(env.getTool(), env.getVersion(), "content_required")
                        .withTookMs((System.nanoTime() - t0) / 1_000_000L);
            }
            FileSystemToolService.EditFileResult r = ctx.fs.insertIntoFile(path, lineNumber, content, dryRun);
            logger.info("tool.result traceId={} tool={} success={} error={} preview={}", ctx.traceId, env.getTool(), r.success, r.error, r.preview);
            ToolResult out = r.success
                    ? ToolResult.ok(env.getTool(), env.getVersion(), ctx.mapper.valueToTree(r))
                    : ToolResult.error(env.getTool(), env.getVersion(), r.error == null || r.error.isEmpty() ? "insert_failed" : r.error)
                        .withExtra("result", ctx.mapper.valueToTree(r));
            if (r.success) {
                updateWorkspaceEdit(ctx.eventStream, ctx.mapper, path, "insert", r.oldContent, r.newContent, r.preview, r.prevExist, ctx.workspaceRoot, ctx.traceId);
                if (!r.preview) {
                    out = out.withHint("Line inserted successfully. Suggestions: 1. Continue editing; 2. Verify if needed.");
                } else {
                    out = out.withHint("Preview only. No changes applied.");
                }
            } else {
                out = out.withHint("INSERT_LINE failed. Check lineNumber and file existence.");
            }
            return out.withTookMs((System.nanoTime() - t0) / 1_000_000L);
        }
    }

    private static final class UndoEditTool implements ToolHandler {
        private static final Logger logger = LoggerFactory.getLogger(UndoEditTool.class);
        private final ToolSpec spec;

        private UndoEditTool() {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode input = objectSchema(mapper, Map.of(
                    "path", stringSchema(mapper)
            ), new String[] { "path" });
            ObjectNode output = objectSchema(mapper, Map.of(
                    "filePath", stringSchema(mapper),
                    "success", booleanSchema(mapper),
                    "error", stringSchema(mapper)
            ), new String[] { "filePath", "success", "error" });
            this.spec = new ToolSpec("UNDO_EDIT", ToolProtocol.DEFAULT_VERSION, "Undo last edit on a file", input, output);
        }

        @Override
        public ToolSpec spec() {
            return spec;
        }

        @Override
        public ToolResult execute(ToolEnvelope env, ToolExecutionContext ctx) {
            long t0 = System.nanoTime();
            String path = env.getArgs().path("path").asText("").trim();
            logger.info("tool.call traceId={} tool={} path={}", ctx.traceId, env.getTool(), truncate(path, 200));
            if (path.isEmpty()) {
                return ToolResult.error(env.getTool(), env.getVersion(), "path_required")
                        .withTookMs((System.nanoTime() - t0) / 1_000_000L);
            }
            FileSystemToolService.EditFileResult r = ctx.fs.undoEdit(path);
            logger.info("tool.result traceId={} tool={} success={} error={}", ctx.traceId, env.getTool(), r.success, r.error);
            ToolResult out = r.success
                    ? ToolResult.ok(env.getTool(), env.getVersion(), ctx.mapper.valueToTree(r))
                    : ToolResult.error(env.getTool(), env.getVersion(), r.error == null || r.error.isEmpty() ? "undo_failed" : r.error)
                        .withExtra("result", ctx.mapper.valueToTree(r));
            if (r.success) {
                updateWorkspaceEdit(ctx.eventStream, ctx.mapper, path, "undo_edit", r.oldContent, r.newContent, r.preview, r.prevExist, ctx.workspaceRoot, ctx.traceId);
                out = out.withHint("Undo applied. Review the file state.");
            } else {
                out = out.withHint("UNDO_EDIT failed. Check if there is edit history.");
            }
            return out.withTookMs((System.nanoTime() - t0) / 1_000_000L);
        }
    }

    private static final class StrReplaceEditorTool implements ToolHandler {
        private static final Logger logger = LoggerFactory.getLogger(StrReplaceEditorTool.class);
        private final ToolSpec spec;

        private StrReplaceEditorTool() {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode input = objectSchema(mapper, Map.of(
                    "command", stringSchema(mapper),
                    "path", stringSchema(mapper),
                    "file_text", stringSchema(mapper),
                    "old_str", stringSchema(mapper),
                    "new_str", stringSchema(mapper),
                    "insert_line", integerSchema(mapper),
                    "startLine", integerSchema(mapper),
                    "window", integerSchema(mapper),
                    "maxChars", integerSchema(mapper),
                    "dry_run", booleanSchema(mapper)
            ), new String[] { "command", "path" });
            ObjectNode viewSchema = objectSchema(mapper, Map.of(
                    "filePath", stringSchema(mapper),
                    "startLine", integerSchema(mapper),
                    "endLine", integerSchema(mapper),
                    "totalLines", integerSchema(mapper),
                    "truncated", booleanSchema(mapper),
                    "content", stringSchema(mapper),
                    "error", stringSchema(mapper),
                    "hasMoreAbove", booleanSchema(mapper),
                    "hasMoreBelow", booleanSchema(mapper),
                    "window", integerSchema(mapper)
            ), new String[] { "filePath", "startLine", "endLine", "totalLines", "truncated", "content", "error", "hasMoreAbove", "hasMoreBelow", "window" });
            ObjectNode output = objectSchema(mapper, Map.of(
                    "command", stringSchema(mapper),
                    "path", stringSchema(mapper),
                    "success", booleanSchema(mapper),
                    "error", stringSchema(mapper),
                    "view", viewSchema,
                    "files", arraySchema(mapper, stringSchema(mapper)),
                    "listTruncated", booleanSchema(mapper),
                    "preview", booleanSchema(mapper),
                    "oldContent", stringSchema(mapper),
                    "newContent", stringSchema(mapper)
            ), new String[] { "command", "path", "success", "error" });
            this.spec = new ToolSpec("STR_REPLACE_EDITOR", ToolProtocol.DEFAULT_VERSION, "View, create, and edit files", input, output);
        }

        @Override
        public ToolSpec spec() {
            return spec;
        }

        @Override
        public ToolResult execute(ToolEnvelope env, ToolExecutionContext ctx) {
            long t0 = System.nanoTime();
            String command = env.getArgs().path("command").asText("").trim();
            String path = env.getArgs().path("path").asText("").trim();
            JsonNode fileTextNode = env.getArgs().get("file_text");
            JsonNode fileTextAlt = env.getArgs().get("fileText");
            String fileText = fileTextNode == null || fileTextNode.isNull()
                    ? (fileTextAlt == null || fileTextAlt.isNull() ? null : fileTextAlt.asText(""))
                    : fileTextNode.asText("");
            JsonNode oldStrNode = env.getArgs().get("old_str");
            JsonNode oldStrAlt = env.getArgs().get("oldStr");
            String oldStr = oldStrNode == null || oldStrNode.isNull()
                    ? (oldStrAlt == null || oldStrAlt.isNull() ? null : oldStrAlt.asText(""))
                    : oldStrNode.asText("");
            JsonNode newStrNode = env.getArgs().get("new_str");
            JsonNode newStrAlt = env.getArgs().get("newStr");
            String newStr = newStrNode == null || newStrNode.isNull()
                    ? (newStrAlt == null || newStrAlt.isNull() ? null : newStrAlt.asText(""))
                    : newStrNode.asText("");
            Integer insertLine = JsonUtils.intOrNull(env.getArgs(), "insert_line", "insertLine");
            Integer startLine = JsonUtils.intOrNull(env.getArgs(), "startLine", "start_line");
            Integer window = JsonUtils.intOrNull(env.getArgs(), "window", "view_window");
            Integer maxChars = JsonUtils.intOrNull(env.getArgs(), "maxChars", "max_chars");
            JsonNode dryRunNode = env.getArgs().path("dry_run");
            boolean dryRun = dryRunNode.isMissingNode() || dryRunNode.isNull() ? false : dryRunNode.asBoolean();
            logger.info("tool.call traceId={} tool={} cmd={} path={} dryRun={}", ctx.traceId, env.getTool(), command, truncate(path, 200), dryRun);
            if (command.isEmpty()) {
                return ToolResult.error(env.getTool(), env.getVersion(), "command_required")
                        .withTookMs((System.nanoTime() - t0) / 1_000_000L);
            }
            if (path.isEmpty()) {
                return ToolResult.error(env.getTool(), env.getVersion(), "path_required")
                        .withTookMs((System.nanoTime() - t0) / 1_000_000L);
            }
            if ("view".equalsIgnoreCase(command)) {
                FileSystemToolService.FileViewResult view = ctx.fs.viewFile(path, startLine, window, maxChars);
                if ("not_a_file".equals(view.error)) {
                    FileSystemToolService.ListFilesResult list = ctx.fs.listFiles(path, "", null, 2);
                    ObjectNode result = ctx.mapper.createObjectNode();
                    result.put("command", command);
                    result.put("path", path);
                    result.put("success", list.error == null || list.error.trim().isEmpty());
                    result.put("error", list.error == null ? "" : list.error);
                    ArrayNode files = result.putArray("files");
                    if (list.files != null) {
                        for (String f : list.files) {
                            files.add(f);
                        }
                    }
                    result.put("listTruncated", list.truncated);
                    ToolResult out = (list.error == null || list.error.trim().isEmpty())
                            ? ToolResult.ok(env.getTool(), env.getVersion(), result)
                            : ToolResult.error(env.getTool(), env.getVersion(), list.error).withExtra("result", result);
                    if (list.error == null || list.error.trim().isEmpty()) {
                        out = out.withHint("Directory listed. Use STR_REPLACE_EDITOR view to open files or READ_FILE for exact ranges.");
                    } else {
                        out = out.withHint("STR_REPLACE_EDITOR view failed. Check if path exists and is within workspace.");
                    }
                    return out.withTookMs((System.nanoTime() - t0) / 1_000_000L);
                }
                ObjectNode result = ctx.mapper.createObjectNode();
                result.put("command", command);
                result.put("path", path);
                result.put("success", view.error == null || view.error.trim().isEmpty());
                result.put("error", view.error == null ? "" : view.error);
                result.set("view", ctx.mapper.valueToTree(view));
                ToolResult out = (view.error == null || view.error.trim().isEmpty())
                        ? ToolResult.ok(env.getTool(), env.getVersion(), result)
                        : ToolResult.error(env.getTool(), env.getVersion(), view.error).withExtra("result", result);
                if (view.error == null || view.error.trim().isEmpty()) {
                    updateWorkspaceView(ctx.eventStream, ctx.mapper, view);
                    out = out.withHint("File view opened. Use STR_REPLACE_EDITOR commands to edit.");
                } else {
                    out = out.withHint("STR_REPLACE_EDITOR view failed. Check path and file type.");
                }
                return out.withTookMs((System.nanoTime() - t0) / 1_000_000L);
            }
            FileSystemToolService.EditFileResult op;
            if ("create".equalsIgnoreCase(command)) {
                op = ctx.fs.createFile(path, fileText, dryRun);
            } else if ("str_replace".equalsIgnoreCase(command)) {
                if (oldStr == null || oldStr.isEmpty()) {
                    return ToolResult.error(env.getTool(), env.getVersion(), "old_str_required")
                            .withTookMs((System.nanoTime() - t0) / 1_000_000L);
                }
                if (newStr == null) {
                    return ToolResult.error(env.getTool(), env.getVersion(), "new_str_required")
                            .withTookMs((System.nanoTime() - t0) / 1_000_000L);
                }
                op = ctx.fs.editFile(path, oldStr, newStr, dryRun);
            } else if ("insert".equalsIgnoreCase(command)) {
                if (newStr == null) {
                    return ToolResult.error(env.getTool(), env.getVersion(), "new_str_required")
                            .withTookMs((System.nanoTime() - t0) / 1_000_000L);
                }
                op = ctx.fs.insertIntoFile(path, insertLine, newStr, dryRun);
            } else if ("undo_edit".equalsIgnoreCase(command)) {
                op = ctx.fs.undoEdit(path);
            } else {
                return ToolResult.error(env.getTool(), env.getVersion(), "command_invalid")
                        .withTookMs((System.nanoTime() - t0) / 1_000_000L);
            }
            logger.info("tool.result traceId={} tool={} success={} error={}", ctx.traceId, env.getTool(), op.success, op.error);
            ObjectNode result = ctx.mapper.createObjectNode();
            result.put("command", command);
            result.put("path", path);
            result.put("success", op.success);
            result.put("error", op.error == null ? "" : op.error);
            result.put("preview", op.preview);
            result.put("prevExist", op.prevExist);
            if (op.oldContent != null) result.put("oldContent", op.oldContent);
            if (op.newContent != null) result.put("newContent", op.newContent);
            FileSystemToolService.FileViewResult view = null;
            if (op.success && !op.preview) {
                Integer viewStart = resolveViewStart(ctx, path, command, fileText, oldStr, newStr, insertLine, window);
                int win = window == null || window.intValue() <= 0 ? 120 : window.intValue();
                view = ctx.fs.viewFile(path, viewStart, Integer.valueOf(win), maxChars);
                result.set("view", ctx.mapper.valueToTree(view));
            }
            ToolResult out = op.success
                    ? ToolResult.ok(env.getTool(), env.getVersion(), result)
                    : ToolResult.error(env.getTool(), env.getVersion(), op.error == null || op.error.isEmpty() ? "edit_failed" : op.error)
                        .withExtra("result", result);
            if (op.success) {
                if (view != null && view.error == null) {
                    updateWorkspaceView(ctx.eventStream, ctx.mapper, view);
                }
                updateWorkspaceEdit(ctx.eventStream, ctx.mapper, path, command, op.oldContent, op.newContent, op.preview, op.prevExist, ctx.workspaceRoot, ctx.traceId);
                out = out.withHint("Edit applied. Review the view and proceed.");
            } else {
                out = out.withHint("STR_REPLACE_EDITOR failed. Check path and parameters.");
            }
            return out.withTookMs((System.nanoTime() - t0) / 1_000_000L);
        }
    }

    private static final class DeleteFileTool implements ToolHandler {
        private static final Logger logger = LoggerFactory.getLogger(DeleteFileTool.class);
        private final ToolSpec spec;

        private DeleteFileTool() {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode input = objectSchema(mapper, Map.of(
                    "path", stringSchema(mapper),
                    "preview", booleanSchema(mapper),
                    "dry_run", booleanSchema(mapper)
            ), new String[] { "path" });
            ObjectNode output = objectSchema(mapper, Map.of(
                    "filePath", stringSchema(mapper),
                    "success", booleanSchema(mapper),
                    "error", stringSchema(mapper),
                    "preview", booleanSchema(mapper),
                    "oldContent", stringSchema(mapper),
                    "newContent", stringSchema(mapper)
            ), new String[] { "filePath", "success", "error", "preview" });
            this.spec = new ToolSpec("DELETE_FILE", ToolProtocol.DEFAULT_VERSION, "Delete a file or empty directory", input, output);
        }

        @Override
        public ToolSpec spec() {
            return spec;
        }

        @Override
        public ToolResult execute(ToolEnvelope env, ToolExecutionContext ctx) {
            long t0 = System.nanoTime();
            String path = env.getArgs().path("path").asText("").trim();
            JsonNode dryRunNode = env.getArgs().path("dry_run");
            boolean dryRun = dryRunNode.isMissingNode() || dryRunNode.isNull() ? false : dryRunNode.asBoolean();
            Boolean effectivePreview = dryRun;
            logger.info("tool.call traceId={} tool={} path={} preview={}", ctx.traceId, env.getTool(), truncate(path, 200), effectivePreview);
            if (path.isEmpty()) {
                return ToolResult.error(env.getTool(), env.getVersion(), "path_required")
                        .withTookMs((System.nanoTime() - t0) / 1_000_000L);
            }
            FileSystemToolService.EditFileResult r = ctx.fs.deletePath(path, effectivePreview);
            logger.info("tool.result traceId={} tool={} success={} error={} preview={}", ctx.traceId, env.getTool(), r.success, r.error, r.preview);
            ToolResult out = r.success
                    ? ToolResult.ok(env.getTool(), env.getVersion(), ctx.mapper.valueToTree(r))
                    : ToolResult.error(env.getTool(), env.getVersion(), r.error == null || r.error.isEmpty() ? "delete_failed" : r.error)
                        .withExtra("result", ctx.mapper.valueToTree(r));
            if (r.success) {
                if (!r.preview) {
                    updateWorkspaceDelete(ctx.eventStream, ctx.mapper, path);
                    updateWorkspaceEdit(ctx.eventStream, ctx.mapper, path, "delete_file", r.oldContent, r.newContent, false, r.prevExist, ctx.workspaceRoot, ctx.traceId);
                    out = out.withHint("Path deleted. Continue with next steps.");
                } else {
                    updateWorkspaceEdit(ctx.eventStream, ctx.mapper, path, "delete_file", r.oldContent, r.newContent, r.preview, r.prevExist, ctx.workspaceRoot, ctx.traceId);
                    out = out.withHint("Preview only. No changes applied.");
                }
            } else {
                out = out.withHint("DELETE_FILE failed. Check if path exists and directory is empty.");
            }
            return out.withTookMs((System.nanoTime() - t0) / 1_000_000L);
        }
    }

    private static final class ApplyPatchTool implements ToolHandler {
        private static final Logger logger = LoggerFactory.getLogger(ApplyPatchTool.class);
        private final ToolSpec spec;

        private ApplyPatchTool() {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode input = objectSchema(mapper, Map.of(
                    "diff", stringSchema(mapper),
                    "patch", stringSchema(mapper),
                    "preview", booleanSchema(mapper),
                    "dry_run", booleanSchema(mapper)
            ), new String[] {});
            ObjectNode output = objectSchema(mapper, Map.of(
                    "success", booleanSchema(mapper),
                    "error", stringSchema(mapper),
                    "files", integerSchema(mapper),
                    "filesApplied", integerSchema(mapper),
                    "linesAdded", integerSchema(mapper),
                    "linesRemoved", integerSchema(mapper),
                    "summary", stringSchema(mapper),
                    "preview", booleanSchema(mapper),
                    "results", arraySchema(mapper, objectSchema(mapper, Map.of(
                            "filePath", stringSchema(mapper),
                            "success", booleanSchema(mapper),
                            "error", stringSchema(mapper),
                            "created", booleanSchema(mapper),
                            "deleted", booleanSchema(mapper),
                            "linesAdded", integerSchema(mapper),
                            "linesRemoved", integerSchema(mapper),
                            "oldContent", stringSchema(mapper),
                            "newContent", stringSchema(mapper)
                    ), new String[] {}))
            ), new String[] { "success", "error", "files", "filesApplied", "linesAdded", "linesRemoved", "summary", "preview", "results" });
            this.spec = new ToolSpec("APPLY_PATCH", ToolProtocol.DEFAULT_VERSION, "Apply patches (unified diff or Begin/End patch format) in workspace", input, output);
        }

        @Override
        public ToolSpec spec() {
            return spec;
        }

        @Override
        public ToolResult execute(ToolEnvelope env, ToolExecutionContext ctx) {
            long t0 = System.nanoTime();
            String diff = JsonUtils.textOrFallback(env.getArgs(), "diff", "patch");
            Boolean preview = env.getArgs().path("preview").isMissingNode() ? null : env.getArgs().path("preview").asBoolean();
            // Default dry_run to false (apply immediately) unless explicitly set to true
            JsonNode dryRunNode = env.getArgs().path("dry_run");
            boolean dryRun = dryRunNode.isMissingNode() || dryRunNode.isNull() ? false : dryRunNode.asBoolean();
            Boolean effectivePreview = dryRun;
            if (diff == null || diff.trim().isEmpty()) {
                return ToolResult.error(env.getTool(), env.getVersion(), "diff_required")
                        .withTookMs((System.nanoTime() - t0) / 1_000_000L);
            }
            logger.info("tool.call traceId={} tool={} diffChars={} preview={}", ctx.traceId, env.getTool(), diff.length(), effectivePreview);
            FileSystemToolService.PatchApplyResult r = ctx.fs.applyPatch(diff, effectivePreview);
            logger.info("tool.result traceId={} tool={} success={} error={} preview={}", ctx.traceId, env.getTool(), r.success, r.error, r.preview);
            ToolResult out = r.success
                    ? ToolResult.ok(env.getTool(), env.getVersion(), ctx.mapper.valueToTree(r))
                    : ToolResult.error(env.getTool(), env.getVersion(), r.error == null || r.error.isEmpty() ? "apply_patch_failed" : r.error)
                        .withExtra("result", ctx.mapper.valueToTree(r));
            if (r.success) {
                if (r.results != null && !r.results.isEmpty()) {
                    for (FileSystemToolService.PatchFileResult item : r.results) {
                        if (!item.success) {
                            continue;
                        }
                        boolean prevExist = !item.created;
                        updateWorkspaceEdit(ctx.eventStream, ctx.mapper, item.filePath, "apply_patch", item.oldContent, item.newContent, r.preview, prevExist, ctx.workspaceRoot, ctx.traceId);
                    }
                } else {
                    updateWorkspaceEdit(ctx.eventStream, ctx.mapper, "", "apply_patch", null, null, r.preview, true, ctx.workspaceRoot, ctx.traceId);
                }

                if (!r.preview) {
                    out = out.withHint("Patch applied. Review changes if needed.");
                } else {
                    out = out.withHint("Preview only. No changes applied.");
                }
            } else {
                out = out.withHint("APPLY_PATCH had failures. Check diff context and paths.");
            }
            return out.withTookMs((System.nanoTime() - t0) / 1_000_000L);
        }
    }

    private static final class BatchReplaceTool implements ToolHandler {
        private static final Logger logger = LoggerFactory.getLogger(BatchReplaceTool.class);
        private final ToolSpec spec;

        private BatchReplaceTool() {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode input = objectSchema(mapper, Map.of(
                    "path", stringSchema(mapper),
                    "glob", stringSchema(mapper),
                    "old_str", stringSchema(mapper),
                    "new_str", stringSchema(mapper),
                    "maxFiles", integerSchema(mapper),
                    "maxReplacements", integerSchema(mapper),
                    "preview", booleanSchema(mapper),
                    "dry_run", booleanSchema(mapper)
            ), new String[] { "old_str", "new_str" });
            ObjectNode output = objectSchema(mapper, Map.of(
                    "success", booleanSchema(mapper),
                    "error", stringSchema(mapper),
                    "filesScanned", integerSchema(mapper),
                    "filesChanged", integerSchema(mapper),
                    "replacements", integerSchema(mapper),
                    "truncated", booleanSchema(mapper),
                    "summary", stringSchema(mapper),
                    "preview", booleanSchema(mapper),
                    "items", arraySchema(mapper, objectSchema(mapper, Map.of(
                            "filePath", stringSchema(mapper),
                            "replacements", integerSchema(mapper),
                            "beforeLines", integerSchema(mapper),
                            "afterLines", integerSchema(mapper),
                            "error", stringSchema(mapper),
                            "oldContent", stringSchema(mapper),
                            "newContent", stringSchema(mapper)
                    ), new String[] {}))
            ), new String[] { "success", "error", "filesScanned", "filesChanged", "replacements", "truncated", "summary", "preview", "items" });
            this.spec = new ToolSpec("BATCH_REPLACE", ToolProtocol.DEFAULT_VERSION, "Replace text across files with optional preview", input, output);
        }

        @Override
        public ToolSpec spec() {
            return spec;
        }

        @Override
        public ToolResult execute(ToolEnvelope env, ToolExecutionContext ctx) {
            long t0 = System.nanoTime();
            String path = env.getArgs().path("path").asText("");
            String glob = env.getArgs().path("glob").asText("");
            String oldStr = env.getArgs().path("old_str").asText("");
            String newStr = env.getArgs().path("new_str").asText("");
            Integer maxFiles = JsonUtils.intOrNull(env.getArgs(), "maxFiles", "max_files");
            Integer maxReplacements = JsonUtils.intOrNull(env.getArgs(), "maxReplacements", "max_replacements");
            Boolean preview = env.getArgs().path("preview").isMissingNode() ? null : env.getArgs().path("preview").asBoolean();
            // Default dry_run to false (apply immediately) unless explicitly set to true
            JsonNode dryRunNode = env.getArgs().path("dry_run");
            boolean dryRun = dryRunNode.isMissingNode() || dryRunNode.isNull() ? false : dryRunNode.asBoolean();
            Boolean effectivePreview = dryRun;
            logger.info("tool.call traceId={} tool={} path={} glob={} oldLen={} newLen={}", ctx.traceId, env.getTool(), truncate(path, 200), truncate(glob, 200), oldStr.length(), newStr.length());
            FileSystemToolService.BatchReplaceResult r = ctx.fs.batchReplace(path, glob, oldStr, newStr, maxFiles, maxReplacements, effectivePreview);
            logger.info("tool.result traceId={} tool={} success={} error={}", ctx.traceId, env.getTool(), r.success, r.error);
            ToolResult out = r.success
                    ? ToolResult.ok(env.getTool(), env.getVersion(), ctx.mapper.valueToTree(r))
                    : ToolResult.error(env.getTool(), env.getVersion(), r.error == null || r.error.isEmpty() ? "batch_replace_failed" : r.error)
                        .withExtra("result", ctx.mapper.valueToTree(r));
            if (r.success) {
                if (r.items != null && !r.items.isEmpty()) {
                    for (FileSystemToolService.BatchReplaceItem item : r.items) {
                        updateWorkspaceEdit(ctx.eventStream, ctx.mapper, item.filePath, "batch_replace", item.oldContent, item.newContent, r.preview, true, ctx.workspaceRoot, ctx.traceId);
                    }
                } else {
                    updateWorkspaceEdit(ctx.eventStream, ctx.mapper, path, "batch_replace", null, null, r.preview, true, ctx.workspaceRoot, ctx.traceId);
                }
                if (!r.preview) {
                    out = out.withHint("Batch replace completed. Review summary and items.");
                } else {
                    out = out.withHint("Preview only. No changes applied.");
                }
            } else {
                out = out.withHint("BATCH_REPLACE failed. Check path and parameters.");
            }
            return out.withTookMs((System.nanoTime() - t0) / 1_000_000L);
        }
    }

    private static final class RunCommandTool implements ToolHandler {
        private static final Logger logger = LoggerFactory.getLogger(RunCommandTool.class);
        private final ToolSpec spec;

        private RunCommandTool() {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode input = objectSchema(mapper, Map.of(
                    "command", stringSchema(mapper),
                    "cwd", stringSchema(mapper),
                    "timeoutMs", integerSchema(mapper),
                    "mode", stringSchema(mapper),
                    "runtimeType", stringSchema(mapper)
            ), new String[] { "command" });
            ObjectNode output = objectSchema(mapper, Map.of(
                    "exitCode", integerSchema(mapper),
                    "output", stringSchema(mapper),
                    "error", stringSchema(mapper),
                    "timeout", booleanSchema(mapper),
                    "tookMs", integerSchema(mapper)
            ), new String[] { "exitCode", "output", "error", "timeout", "tookMs" });
            this.spec = new ToolSpec("RUN_COMMAND", ToolProtocol.DEFAULT_VERSION, "Run a shell command in the selected runtime", input, output);
        }

        @Override
        public ToolSpec spec() {
            return spec;
        }

        @Override
        public ToolResult execute(ToolEnvelope env, ToolExecutionContext ctx) {
            long t0 = System.nanoTime();
            String command = JsonUtils.textOrFallback(env.getArgs(), "command", "cmd").trim();
            String cwd = JsonUtils.textOrFallback(env.getArgs(), "cwd", "workdir");
            Integer timeoutMs = JsonUtils.intOrNull(env.getArgs(), "timeoutMs", "timeout_ms");
            String modeRaw = JsonUtils.textOrFallback(env.getArgs(), "mode", "executionMode");
            String runtimeRaw = JsonUtils.textOrFallback(env.getArgs(), "runtimeType", "runtime_type");
            logger.info("tool.call traceId={} tool={} cmdLen={} cwd={}", ctx.traceId, env.getTool(), command.length(), truncate(cwd, 200));
            if (command.isEmpty()) {
                return ToolResult.error(env.getTool(), env.getVersion(), "command_is_blank")
                        .withExtra("command", ctx.mapper.valueToTree(command))
                        .withTookMs((System.nanoTime() - t0) / 1_000_000L);
            }
            if (timeoutMs != null && timeoutMs.intValue() < 0) {
                return ToolResult.error(env.getTool(), env.getVersion(), "timeout_ms_invalid")
                        .withExtra("timeoutMs", ctx.mapper.valueToTree(timeoutMs))
                        .withTookMs((System.nanoTime() - t0) / 1_000_000L);
            }
            if (ctx.runtimeService == null) {
                return ToolResult.error(env.getTool(), env.getVersion(), "runtime_service_unavailable")
                        .withTookMs((System.nanoTime() - t0) / 1_000_000L);
            }
            ExecutionMode mode = parseModeOrNull(modeRaw);
            if (mode == null) {
                return ToolResult.error(env.getTool(), env.getVersion(), "mode_invalid")
                        .withExtra("mode", ctx.mapper.valueToTree(modeRaw))
                        .withTookMs((System.nanoTime() - t0) / 1_000_000L);
            }
            long timeout = timeoutMs == null ? 0L : timeoutMs.longValue();
            RuntimeType override = null;
            if (runtimeRaw != null && !runtimeRaw.trim().isEmpty()) {
                override = parseRuntimeType(runtimeRaw);
                if (override == null) {
                    return ToolResult.error(env.getTool(), env.getVersion(), "runtime_type_invalid")
                            .withExtra("runtimeType", ctx.mapper.valueToTree(runtimeRaw))
                            .withTookMs((System.nanoTime() - t0) / 1_000_000L);
                }
                String dockerRoot = (ctx.sessionRoot != null && !ctx.sessionRoot.isEmpty()) ? ctx.sessionRoot : ctx.workspaceRoot;
                if (override == RuntimeType.DOCKER && (dockerRoot == null || dockerRoot.isEmpty())) {
                    return ToolResult.error(env.getTool(), env.getVersion(), "workspaceRoot_is_blank")
                            .withExtra("runtimeType", ctx.mapper.valueToTree(runtimeRaw))
                            .withTookMs((System.nanoTime() - t0) / 1_000_000L);
                }
            }
            String runRoot = (ctx.sessionRoot != null && !ctx.sessionRoot.isEmpty()) ? ctx.sessionRoot : ctx.workspaceRoot;
            if (cwd == null || cwd.trim().isEmpty()) {
                cwd = runRoot;
            }
            if (runRoot != null && !runRoot.isEmpty()) {
                Path rootPath = Paths.get(runRoot).toAbsolutePath().normalize();
                Path cwdPath = Paths.get(cwd);
                if (!cwdPath.isAbsolute()) {
                    cwdPath = rootPath.resolve(cwdPath).normalize();
                } else {
                    cwdPath = cwdPath.toAbsolutePath().normalize();
                }
                if (!cwdPath.startsWith(rootPath)) {
                    return ToolResult.error(env.getTool(), env.getVersion(), "cwd_outside_session_root")
                            .withExtra("cwd", ctx.mapper.valueToTree(cwd))
                            .withExtra("sessionRoot", ctx.mapper.valueToTree(runRoot))
                            .withTookMs((System.nanoTime() - t0) / 1_000_000L);
                }
                cwd = cwdPath.toString();
            }
            CommandRequest req = new CommandRequest(command, cwd, timeout, mode);
            RuntimeType effective = override == null ? ctx.runtimeService.defaultType() : override;
            CommandResult result = ctx.runtimeService.execute(runRoot, req, override);
            ToolResult out = result.exitCode == 0 && result.error.isEmpty()
                    ? ToolResult.ok(env.getTool(), env.getVersion(), ctx.mapper.valueToTree(result))
                    : ToolResult.error(env.getTool(), env.getVersion(), result.error.isEmpty() ? "command_failed" : result.error)
                        .withExtra("result", ctx.mapper.valueToTree(result));
            out = out.withExtra("exitCode", ctx.mapper.valueToTree(result.exitCode));
            out = out.withExtra("runtimeType", ctx.mapper.valueToTree(effective == null ? "DEFAULT" : effective.name()));
            updateRuntimeState(ctx.eventStream, ctx.mapper, command, cwd, mode, effective, result);
            return out.withTookMs((System.nanoTime() - t0) / 1_000_000L);
        }

        private static ExecutionMode parseModeOrNull(String mode) {
            if (mode == null || mode.trim().isEmpty()) {
                return ExecutionMode.STEP;
            }
            String v = mode.trim().toUpperCase();
            if (v.contains("TASK")) {
                return ExecutionMode.TASK;
            }
            if (v.contains("STEP")) {
                return ExecutionMode.STEP;
            }
            return null;
        }

        private static RuntimeType parseRuntimeType(String raw) {
            if (raw == null || raw.trim().isEmpty()) {
                return null;
            }
            String v = raw.trim().toLowerCase();
            if (v.contains("docker")) {
                return RuntimeType.DOCKER;
            }
            if (v.contains("local")) {
                return RuntimeType.LOCAL;
            }
            return null;
        }
    }

    private static final class TriggerIndexTool implements ToolHandler {
        private static final Logger logger = LoggerFactory.getLogger(TriggerIndexTool.class);
        private final ToolSpec spec;

        private TriggerIndexTool() {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode input = objectSchema(mapper, Map.of(
                    "mode", stringSchema(mapper)
            ), new String[] {});
            ObjectNode output = objectSchema(mapper, Map.of(
                    "mode", stringSchema(mapper),
                    "traceId", stringSchema(mapper),
                    "totalFiles", integerSchema(mapper)
            ), new String[] { "mode", "traceId", "totalFiles" });
            this.spec = new ToolSpec("TRIGGER_INDEX", ToolProtocol.DEFAULT_VERSION, "Trigger a full scan index job", input, output);
        }

        @Override
        public ToolSpec spec() {
            return spec;
        }

        @Override
        public ToolResult execute(ToolEnvelope env, ToolExecutionContext ctx) {
            long t0 = System.nanoTime();
            String mode = env.getArgs().path("mode").asText("kafka");
            logger.info("tool.call traceId={} tool={} mode={} workspaceRoot={}", ctx.traceId, env.getTool(), mode, truncate(ctx.workspaceRoot, 200));
            if (ctx.workspaceRoot.isEmpty()) {
                logger.info("tool.result traceId={} tool={} error=workspaceRoot_is_blank", ctx.traceId, env.getTool());
                return ToolResult.error(env.getTool(), env.getVersion(), "workspaceRoot_is_blank")
                        .withExtra("mode", ctx.mapper.valueToTree(mode))
                        .withTookMs((System.nanoTime() - t0) / 1_000_000L);
            }
            if (ctx.kafkaBootstrapServers.isEmpty()) {
                logger.info("tool.result traceId={} tool={} error=kafka_bootstrap_servers_is_blank", ctx.traceId, env.getTool());
                return ToolResult.error(env.getTool(), env.getVersion(), "kafka_bootstrap_servers_is_blank")
                        .withExtra("mode", ctx.mapper.valueToTree(mode))
                        .withTookMs((System.nanoTime() - t0) / 1_000_000L);
            }
            ctx.indexingWorker.start();
            String t = "trace-fullscan-" + UUID.randomUUID();
            int total = new FullScanService(ctx.kafkaBootstrapServers, ctx.mapper).scanAndPublish(t, Paths.get(ctx.workspaceRoot));
            logger.info("tool.result traceId={} tool={} trace={} totalFiles={}", ctx.traceId, env.getTool(), t, total);
            ToolResult out = ToolResult.ok(env.getTool(), env.getVersion(), null)
                    .withExtra("mode", ctx.mapper.valueToTree(mode))
                    .withExtra("traceId", ctx.mapper.valueToTree(t))
                    .withExtra("totalFiles", ctx.mapper.valueToTree(total));
            return out.withTookMs((System.nanoTime() - t0) / 1_000_000L);
        }
    }

    private static ObjectNode objectSchema(ObjectMapper mapper, Map<String, JsonNode> props, String[] required) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = mapper.createObjectNode();
        for (Map.Entry<String, JsonNode> entry : props.entrySet()) {
            properties.set(entry.getKey(), entry.getValue());
        }
        schema.set("properties", properties);
        ArrayNode req = mapper.createArrayNode();
        if (required != null) {
            for (String r : required) {
                req.add(r);
            }
        }
        schema.set("required", req);
        schema.put("additionalProperties", false);
        return schema;
    }

    private static ObjectNode stringSchema(ObjectMapper mapper) {
        ObjectNode n = mapper.createObjectNode();
        n.put("type", "string");
        return n;
    }

    private static ObjectNode integerSchema(ObjectMapper mapper) {
        ObjectNode n = mapper.createObjectNode();
        n.put("type", "integer");
        return n;
    }

    private static ObjectNode numberSchema(ObjectMapper mapper) {
        ObjectNode n = mapper.createObjectNode();
        n.put("type", "number");
        return n;
    }

    private static ObjectNode booleanSchema(ObjectMapper mapper) {
        ObjectNode n = mapper.createObjectNode();
        n.put("type", "boolean");
        return n;
    }

    private static ObjectNode arraySchema(ObjectMapper mapper, JsonNode items) {
        ObjectNode n = mapper.createObjectNode();
        n.put("type", "array");
        n.set("items", items);
        return n;
    }

    private static Integer resolveViewStart(ToolExecutionContext ctx, String path, String command, String fileText, String oldStr, String newStr, Integer insertLine, Integer window) {
        int win = window == null || window.intValue() <= 0 ? 120 : window.intValue();
        String anchor = "";
        if ("create".equalsIgnoreCase(command)) {
            anchor = firstNonEmptyLine(fileText);
        } else if ("str_replace".equalsIgnoreCase(command) || "insert".equalsIgnoreCase(command)) {
            anchor = firstNonEmptyLine(newStr);
        }
        if (anchor != null && !anchor.isEmpty()) {
            Integer line = ctx.fs.findFirstLineContaining(path, anchor);
            if (line != null) {
                return Integer.valueOf(Math.max(1, line.intValue() - win / 2));
            }
        }
        if (insertLine != null && insertLine.intValue() > 0) {
            return Integer.valueOf(Math.max(1, insertLine.intValue() - win / 2));
        }
        return Integer.valueOf(1);
    }

    private static String firstNonEmptyLine(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String[] parts = text.split("\n");
        for (String part : parts) {
            if (part != null && !part.trim().isEmpty()) {
                return part;
            }
        }
        return "";
    }

    private static void updateWorkspaceView(EventStream eventStream, ObjectMapper mapper, FileSystemToolService.FileViewResult r) {
        if (eventStream == null || mapper == null || r == null) {
            return;
        }
        ObjectNode update = mapper.createObjectNode();
        update.put("activeFile", r.filePath == null ? "" : r.filePath);
        update.put("viewStart", r.startLine);
        update.put("viewEnd", r.endLine);
        update.put("viewWindow", r.window);
        update.put("viewTotalLines", r.totalLines);
        update.put("viewHasMoreAbove", r.hasMoreAbove);
        update.put("viewHasMoreBelow", r.hasMoreBelow);
        update.put("viewUpdatedAt", java.time.Instant.now().toString());
        eventStream.updateWorkspaceState(update, EventSource.AGENT, null);
    }

    private static void updateWorkspaceEdit(EventStream eventStream, ObjectMapper mapper, String path, String command, String oldContent, String newContent, boolean preview, boolean prevExist, String workspaceRoot, String sessionId) {
        if (eventStream == null || mapper == null) {
            return;
        }
        ObjectNode update = mapper.createObjectNode();
        update.put("lastEditFile", path == null ? "" : path);
        update.put("lastEditCommand", command == null ? "" : command);
        update.put("lastEditAt", java.time.Instant.now().toString());
        update.put("lastEditPreview", preview);
        if (oldContent != null || newContent != null) {
            ObjectNode diff = mapper.createObjectNode();
            diff.put("path", path);
            diff.put("oldContent", oldContent);
            diff.put("newContent", newContent);
            update.set("latestFileDiff", diff);

            if (!preview && !"move_path".equalsIgnoreCase(command)) {
                String changeType = "EDIT";
                if (newContent == null) {
                    changeType = "DELETE";
                } else if (!prevExist) {
                    changeType = "CREATE";
                }
                AppliedChangesManager.getInstance().addChange(
                        new AppliedChangesManager.AppliedChange(
                                path,
                                changeType,
                                oldContent,
                                newContent,
                                workspaceRoot,
                                sessionId
                        )
                );
            }

            if (preview) {
                // Add to PendingChangesManager only if not already added by FileSystemToolService
                // This prevents overwriting the correct 'oldContent' (original disk content) with intermediate content
                if (PendingChangesManager.getInstance().getPendingChange(path, workspaceRoot, sessionId).isEmpty()) {
                    String changeType = "EDIT";
                    if (newContent == null) {
                        changeType = "DELETE";
                    } else if (!prevExist) {
                        changeType = "CREATE";
                    }

                    PendingChangesManager.getInstance().addChange(
                        new PendingChangesManager.PendingChange(
                            java.util.UUID.randomUUID().toString(),
                            path,
                            changeType,
                            oldContent,
                            newContent,
                            null, // Preview diff computed by frontend or later
                            System.currentTimeMillis(),
                            workspaceRoot,
                            sessionId
                        )
                    );
                }

                ObjectNode pending = mapper.createObjectNode();
                pending.put("path", path);
                pending.put("old_content", oldContent);
                pending.put("new_content", newContent);
                pending.put("prev_exist", prevExist);
                update.set("pending_diff", pending);
            } else {
                update.putNull("pending_diff");
            }
            
            // Add all pending changes to update
            update.set("pending_changes", PendingChangesManager.getInstance().toJson(mapper, workspaceRoot, sessionId));
        }
        eventStream.updateWorkspaceState(update, EventSource.AGENT, null);
    }

    private static void updateWorkspaceDelete(EventStream eventStream, ObjectMapper mapper, String path) {
        if (eventStream == null || mapper == null) {
            return;
        }
        ObjectNode update = mapper.createObjectNode();
        update.put("lastDeletedPath", path == null ? "" : path);
        update.put("lastDeleteAt", java.time.Instant.now().toString());
        eventStream.updateWorkspaceState(update, EventSource.AGENT, null);
    }

    private static void updateRepoMapState(EventStream eventStream, ObjectMapper mapper, FileSystemToolService.RepoMapResult r, String path, Integer maxDepth, Integer maxFiles) {
        if (eventStream == null || mapper == null || r == null) {
            return;
        }
        ObjectNode update = mapper.createObjectNode();
        update.put("lastRepoMapPath", path == null ? "" : path);
        if (maxDepth != null) {
            update.put("lastRepoMapDepth", maxDepth.intValue());
        }
        if (maxFiles != null) {
            update.put("lastRepoMapFiles", maxFiles.intValue());
        }
        update.put("lastRepoMapTotalFiles", r.totalFiles);
        update.put("lastRepoMapTruncated", r.truncated);
        update.put("lastRepoMapAt", java.time.Instant.now().toString());
        eventStream.updateWorkspaceState(update, EventSource.AGENT, null);
    }

    private static void updateRuntimeState(EventStream eventStream, ObjectMapper mapper, String command, String cwd, ExecutionMode mode, RuntimeType runtimeType, CommandResult result) {
        if (eventStream == null || mapper == null || result == null) {
            return;
        }
        ObjectNode update = mapper.createObjectNode();
        update.put("lastRunCommand", truncate(command, 200));
        update.put("lastRunCwd", cwd == null ? "" : cwd);
        update.put("lastRunMode", mode == null ? "" : mode.name());
        update.put("lastRunRuntimeType", runtimeType == null ? "DEFAULT" : runtimeType.name());
        update.put("lastRunExitCode", result.exitCode);
        update.put("lastRunError", result.error == null ? "" : result.error);
        update.put("lastRunTimeout", result.timeout);
        update.put("lastRunTookMs", result.tookMs);
        update.put("lastRunAt", java.time.Instant.now().toString());
        eventStream.updateWorkspaceState(update, EventSource.AGENT, null);
    }

    private static final class ApplyPendingDiffTool implements ToolHandler {
        private static final Logger logger = LoggerFactory.getLogger(ApplyPendingDiffTool.class);
        private final ToolSpec spec;

        private ApplyPendingDiffTool() {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode input = objectSchema(mapper, Map.of(
                    "path", stringSchema(mapper),
                    "paths", arraySchema(mapper, stringSchema(mapper)),
                    "reject", booleanSchema(mapper)
            ), new String[] {});
            ObjectNode output = objectSchema(mapper, Map.of(
                    "success", booleanSchema(mapper),
                    "error", stringSchema(mapper),
                    "applied", arraySchema(mapper, stringSchema(mapper)),
                    "rejected", booleanSchema(mapper)
            ), new String[] { "success", "error" });
            this.spec = new ToolSpec("APPLY_PENDING_DIFF", ToolProtocol.DEFAULT_VERSION, "Apply or reject pending diffs. If no path specified, applies ALL pending changes.", input, output);
        }

        @Override
        public ToolSpec spec() {
            return spec;
        }

        @Override
        public ToolResult execute(ToolEnvelope env, ToolExecutionContext ctx) {
            long t0 = System.nanoTime();
            String reqPath = env.getArgs().path("path").asText("");
            JsonNode pathsNode = env.getArgs().path("paths");
            boolean reject = env.getArgs().path("reject").asBoolean(false);
            
            List<String> reqPaths = new java.util.ArrayList<>();
            if (pathsNode != null && pathsNode.isArray()) {
                for (JsonNode n : pathsNode) {
                    reqPaths.add(n.asText());
                }
            }
            if (!reqPath.isEmpty()) {
                reqPaths.add(reqPath);
            }

            logger.info("tool.call traceId={} tool={} paths={} reject={}", ctx.traceId, env.getTool(), reqPaths, reject);

            List<PendingChangesManager.PendingChange> allChanges = PendingChangesManager.getInstance().getChanges(ctx.workspaceRoot, ctx.traceId);
            if (allChanges.isEmpty()) {
                 return ToolResult.error(env.getTool(), env.getVersion(), "no_pending_changes");
            }

            List<PendingChangesManager.PendingChange> targets = new java.util.ArrayList<>();
            if (reqPaths.isEmpty()) {
                // Apply ALL
                targets.addAll(allChanges);
            } else {
                // Filter by paths
                for (PendingChangesManager.PendingChange c : allChanges) {
                    if (reqPaths.contains(c.path)) {
                        targets.add(c);
                    }
                }
            }

            if (targets.isEmpty()) {
                 return ToolResult.error(env.getTool(), env.getVersion(), "no_matching_pending_changes")
                        .withExtra("available_paths", ctx.mapper.valueToTree(allChanges.stream().map(c -> c.path).collect(java.util.stream.Collectors.toList())));
            }

            List<String> appliedPaths = new java.util.ArrayList<>();
            List<String> errors = new java.util.ArrayList<>();

            for (PendingChangesManager.PendingChange change : targets) {
                if (reject) {
                    PendingChangesManager.getInstance().removeChange(change.id);
                    appliedPaths.add(change.path);
                    continue;
                }

                String newContent = change.newContent;
                boolean isDelete = newContent == null;
                
                FileSystemToolService.EditFileResult r = ctx.fs.applyToFile(change.path, newContent, isDelete);

                if (r.success) {
                    PendingChangesManager.getInstance().removeChange(change.id);
                    appliedPaths.add(change.path);
                    // Update last edit state (but don't add new pending change)
                    // We need to trigger updateWorkspaceEdit to refresh the state file, especially removing the applied one
                    // We do this after the loop or per item? 
                    // Per item is safer for history, but might be noisy. 
                    // Let's do it per item to record the edit in history properly.
                    updateWorkspaceEdit(ctx.eventStream, ctx.mapper, change.path, "apply_pending_diff", change.oldContent, change.newContent, false, true, ctx.workspaceRoot, ctx.traceId);
                } else {
                    errors.add(change.path + ": " + r.error);
                }
            }

            // If we rejected, we also need to update workspace state to clear the pending list in JSON
            if (reject) {
                ObjectNode update = ctx.mapper.createObjectNode();
                update.set("pending_changes", PendingChangesManager.getInstance().toJson(ctx.mapper, ctx.workspaceRoot, ctx.traceId));
                // Also clear legacy pending_diff if empty
                if (PendingChangesManager.getInstance().getChanges(ctx.workspaceRoot, ctx.traceId).isEmpty()) {
                    update.putNull("pending_diff");
                }
                ctx.eventStream.updateWorkspaceState(update, EventSource.AGENT, null);
            }

            ToolResult out;
            if (errors.isEmpty()) {
                out = ToolResult.ok(env.getTool(), env.getVersion(), ctx.mapper.createObjectNode().put("success", true).put("rejected", reject));
                out = out.withHint(reject ? "Pending diffs rejected and cleared." : "Pending diffs applied successfully.");
            } else {
                out = ToolResult.error(env.getTool(), env.getVersion(), "partial_failure")
                        .withExtra("errors", ctx.mapper.valueToTree(errors));
                out = out.withHint("Some changes failed to apply: " + String.join(", ", errors));
            }
            out = out.withExtra("applied", ctx.mapper.valueToTree(appliedPaths));

            return out.withTookMs((System.nanoTime() - t0) / 1_000_000L);
        }
    }

    private static String extractRepoMapQuery(ObjectNode ws) {
        if (ws == null) {
            return "";
        }
        String goal = ws.path("lastGoal").asText("");
        if (goal != null && !goal.trim().isEmpty()) {
            return goal;
        }
        return ws.path("lastSearchQuery").asText("");
    }

    private static boolean isActiveFileRelevant(String activeFile, String query) {
        if (activeFile == null || activeFile.trim().isEmpty() || query == null || query.trim().isEmpty()) {
            return false;
        }
        String q = query.toLowerCase();
        if (q.contains("current file") || q.contains("active file") || q.contains("当前文件") || q.contains("当前打开")) {
            return true;
        }
        String norm = activeFile.replace('\\', '/');
        String fileName = baseName(norm);
        if (fileName.isEmpty()) {
            return false;
        }
        String fileLower = fileName.toLowerCase();
        if (q.contains(fileLower)) {
            return true;
        }
        String base = removeExtension(fileName).toLowerCase();
        if (!base.isEmpty() && q.contains(base)) {
            return true;
        }
        String parent = parentName(norm);
        if (!parent.isEmpty() && q.contains(parent.toLowerCase())) {
            return true;
        }
        return q.contains(norm.toLowerCase());
    }

    private static String baseName(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        String clean = path.replace('\\', '/');
        int slash = clean.lastIndexOf('/');
        return slash >= 0 ? clean.substring(slash + 1) : clean;
    }

    private static String parentName(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        String clean = path.replace('\\', '/');
        int slash = clean.lastIndexOf('/');
        if (slash <= 0) {
            return "";
        }
        String parentPath = clean.substring(0, slash);
        int parentSlash = parentPath.lastIndexOf('/');
        return parentSlash >= 0 ? parentPath.substring(parentSlash + 1) : parentPath;
    }

    private static String removeExtension(String name) {
        if (name == null || name.isEmpty()) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        if (dot <= 0) {
            return name;
        }
        return name.substring(0, dot);
    }

    private static String truncate(String s, int maxChars) {
        if (s == null) {
            return "";
        }
        return s.length() <= maxChars ? s : s.substring(0, maxChars);
    }

    private static final class ReplaceLinesTool implements ToolHandler {
        private static final Logger logger = LoggerFactory.getLogger(ReplaceLinesTool.class);
        private final ToolSpec spec;

        private ReplaceLinesTool() {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode input = objectSchema(mapper, Map.of(
                    "path", stringSchema(mapper),
                    "startLine", integerSchema(mapper),
                    "endLine", integerSchema(mapper),
                    "newContent", stringSchema(mapper),
                    "preview", booleanSchema(mapper),
                    "dry_run", booleanSchema(mapper)
            ), new String[] { "path", "startLine", "endLine" });
            ObjectNode output = objectSchema(mapper, Map.of(
                    "success", booleanSchema(mapper),
                    "error", stringSchema(mapper),
                    "preview", booleanSchema(mapper),
                    "oldContent", stringSchema(mapper),
                    "newContent", stringSchema(mapper)
            ), new String[] { "success", "error", "preview" });
            this.spec = new ToolSpec("REPLACE_LINES", ToolProtocol.DEFAULT_VERSION, "Replace lines in a file with new content", input, output);
        }

        @Override
        public ToolSpec spec() {
            return spec;
        }

        @Override
        public ToolResult execute(ToolEnvelope env, ToolExecutionContext ctx) {
            long t0 = System.nanoTime();
            String path = env.getArgs().path("path").asText("").trim();
            int startLine = env.getArgs().path("startLine").asInt(0);
            int endLine = env.getArgs().path("endLine").asInt(0);
            String newContent = env.getArgs().path("newContent").asText("");
            JsonNode dryRunNode = env.getArgs().path("dry_run");
            boolean dryRun = dryRunNode.isMissingNode() || dryRunNode.isNull() ? true : dryRunNode.asBoolean();
            Boolean effectivePreview = dryRun;
            
            logger.info("tool.call traceId={} tool={} path={} start={} end={} preview={}", ctx.traceId, env.getTool(), path, startLine, endLine, effectivePreview);
            
            FileSystemToolService.EditFileResult r = ctx.fs.replaceLines(path, startLine, endLine, newContent, effectivePreview);
            
            logger.info("tool.result traceId={} tool={} success={} error={} preview={}", ctx.traceId, env.getTool(), r.success, r.error, r.preview);
            
            ToolResult out = r.success
                    ? ToolResult.ok(env.getTool(), env.getVersion(), ctx.mapper.valueToTree(r))
                    : ToolResult.error(env.getTool(), env.getVersion(), r.error == null || r.error.isEmpty() ? "replace_lines_failed" : r.error)
                        .withExtra("result", ctx.mapper.valueToTree(r));
            
            if (r.success) {
                updateWorkspaceEdit(ctx.eventStream, ctx.mapper, path, "replace_lines", r.oldContent, r.newContent, r.preview, r.prevExist, ctx.workspaceRoot, ctx.traceId);
                if (!r.preview) {
                    out = out.withHint("Lines replaced. Verify content.");
                } else {
                    out = out.withHint("Preview only. No changes applied.");
                }
            } else {
                out = out.withHint("REPLACE_LINES failed. Check path and line numbers.");
            }
            return out.withTookMs((System.nanoTime() - t0) / 1_000_000L);
        }
    }

    private static final class CreateDirectoryTool implements ToolHandler {
        private static final Logger logger = LoggerFactory.getLogger(CreateDirectoryTool.class);
        private final ToolSpec spec;

        private CreateDirectoryTool() {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode input = objectSchema(mapper, Map.of(
                    "path", stringSchema(mapper),
                    "preview", booleanSchema(mapper),
                    "dry_run", booleanSchema(mapper)
            ), new String[] { "path" });
            ObjectNode output = objectSchema(mapper, Map.of(
                    "success", booleanSchema(mapper),
                    "error", stringSchema(mapper),
                    "preview", booleanSchema(mapper)
            ), new String[] { "success", "error", "preview" });
            this.spec = new ToolSpec("CREATE_DIRECTORY", ToolProtocol.DEFAULT_VERSION, "Create a directory", input, output);
        }

        @Override
        public ToolSpec spec() {
            return spec;
        }

        @Override
        public ToolResult execute(ToolEnvelope env, ToolExecutionContext ctx) {
            long t0 = System.nanoTime();
            String path = env.getArgs().path("path").asText("").trim();
            Boolean preview = env.getArgs().path("preview").isMissingNode() ? null : env.getArgs().path("preview").asBoolean();
            // Default dry_run to false (apply immediately) unless explicitly set to true
            JsonNode dryRunNode = env.getArgs().path("dry_run");
            boolean dryRun = dryRunNode.isMissingNode() || dryRunNode.isNull() ? false : dryRunNode.asBoolean();
            Boolean effectivePreview = dryRun;
            
            logger.info("tool.call traceId={} tool={} path={} preview={}", ctx.traceId, env.getTool(), path, effectivePreview);
            
            FileSystemToolService.EditFileResult r = ctx.fs.createDirectory(path, effectivePreview);
            
            logger.info("tool.result traceId={} tool={} success={} error={} preview={}", ctx.traceId, env.getTool(), r.success, r.error, r.preview);
            
            ToolResult out = r.success
                    ? ToolResult.ok(env.getTool(), env.getVersion(), ctx.mapper.valueToTree(r))
                    : ToolResult.error(env.getTool(), env.getVersion(), r.error == null || r.error.isEmpty() ? "create_directory_failed" : r.error)
                        .withExtra("result", ctx.mapper.valueToTree(r));
            
            if (r.success) {
                updateWorkspaceEdit(ctx.eventStream, ctx.mapper, path, "create_directory", null, null, r.preview, r.prevExist, ctx.workspaceRoot, ctx.traceId);
                if (!r.preview) {
                    out = out.withHint("Directory created.");
                } else {
                    out = out.withHint("Preview only. No changes applied.");
                }
            }
            return out.withTookMs((System.nanoTime() - t0) / 1_000_000L);
        }
    }

    private static final class MovePathTool implements ToolHandler {
        private static final Logger logger = LoggerFactory.getLogger(MovePathTool.class);
        private final ToolSpec spec;

        private MovePathTool() {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode input = objectSchema(mapper, Map.of(
                    "source", stringSchema(mapper),
                    "destination", stringSchema(mapper),
                    "preview", booleanSchema(mapper),
                    "dry_run", booleanSchema(mapper)
            ), new String[] { "source", "destination" });
            ObjectNode output = objectSchema(mapper, Map.of(
                    "success", booleanSchema(mapper),
                    "error", stringSchema(mapper),
                    "preview", booleanSchema(mapper)
            ), new String[] { "success", "error", "preview" });
            this.spec = new ToolSpec("MOVE_PATH", ToolProtocol.DEFAULT_VERSION, "Move or rename a file or directory", input, output);
        }

        @Override
        public ToolSpec spec() {
            return spec;
        }

        @Override
        public ToolResult execute(ToolEnvelope env, ToolExecutionContext ctx) {
            long t0 = System.nanoTime();
            String source = env.getArgs().path("source").asText("").trim();
            String destination = env.getArgs().path("destination").asText("").trim();
            Boolean preview = env.getArgs().path("preview").isMissingNode() ? null : env.getArgs().path("preview").asBoolean();
            // Default dry_run to false (apply immediately) unless explicitly set to true
            JsonNode dryRunNode = env.getArgs().path("dry_run");
            boolean dryRun = dryRunNode.isMissingNode() || dryRunNode.isNull() ? false : dryRunNode.asBoolean();
            Boolean effectivePreview = dryRun;
            
            logger.info("tool.call traceId={} tool={} source={} dest={} preview={}", ctx.traceId, env.getTool(), source, destination, effectivePreview);
            
            FileSystemToolService.EditFileResult r = ctx.fs.movePath(source, destination, effectivePreview);
            
            logger.info("tool.result traceId={} tool={} success={} error={} preview={}", ctx.traceId, env.getTool(), r.success, r.error, r.preview);
            
            ToolResult out = r.success
                    ? ToolResult.ok(env.getTool(), env.getVersion(), ctx.mapper.valueToTree(r))
                    : ToolResult.error(env.getTool(), env.getVersion(), r.error == null || r.error.isEmpty() ? "move_path_failed" : r.error)
                        .withExtra("result", ctx.mapper.valueToTree(r));
            
            if (r.success) {
                updateWorkspaceEdit(ctx.eventStream, ctx.mapper, destination, "move_path", "source: " + source, "dest: " + destination, r.preview, r.prevExist, ctx.workspaceRoot, ctx.traceId);
                if (!r.preview) {
                    out = out.withHint("Path moved successfully.");
                } else {
                    out = out.withHint("Preview only. No changes applied.");
                }
            }
            return out.withTookMs((System.nanoTime() - t0) / 1_000_000L);
        }
    }

    private static final class LoadSkillTool implements ToolHandler {
        private static final Logger logger = LoggerFactory.getLogger(LoadSkillTool.class);
        private final ToolSpec spec;

        private LoadSkillTool() {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode input = objectSchema(mapper, Map.of(
                    "skill_name", stringSchema(mapper)
            ), new String[] { "skill_name" });
            ObjectNode output = objectSchema(mapper, Map.of(
                    "name", stringSchema(mapper),
                    "description", stringSchema(mapper),
                    "content", stringSchema(mapper),
                    "baseDir", stringSchema(mapper),
                    "error", stringSchema(mapper)
            ), new String[] { "name", "content", "error" });
            this.spec = new ToolSpec("LOAD_SKILL", ToolProtocol.DEFAULT_VERSION, "Load a skill definition and instructions from .agent/skills", input, output);
        }

        @Override
        public ToolSpec spec() {
            return spec;
        }

        @Override
        public ToolResult execute(ToolEnvelope env, ToolExecutionContext ctx) {
            long t0 = System.nanoTime();
            String skillName = env.getArgs().path("skill_name").asText("");
            logger.info("tool.call traceId={} tool={} skill_name={}", ctx.traceId, env.getTool(), skillName);

            if (ctx.skillManager == null) {
                return ToolResult.error(env.getTool(), env.getVersion(), "skill_manager_not_available")
                        .withTookMs((System.nanoTime() - t0) / 1_000_000L);
            }

            Skill skill = ctx.skillManager.loadSkill(ctx.workspaceRoot, skillName);
            
            ToolResult out;
            if (skill != null) {
                // Append Base directory info to content for relative path resolution
                String augmentedContent = skill.getContent() + "\n\nBase directory: " + skill.getBaseDir();
                
                ObjectNode result = ctx.mapper.createObjectNode();
                result.put("name", skill.getName());
                result.put("description", skill.getDescription());
                result.put("content", augmentedContent);
                result.put("baseDir", skill.getBaseDir());
                
                logger.info("tool.result traceId={} tool={} success=true", ctx.traceId, env.getTool());
                out = ToolResult.ok(env.getTool(), env.getVersion(), result)
                        .withHint("Skill loaded. Follow the instructions in 'content' field carefully.");
            } else {
                logger.info("tool.result traceId={} tool={} success=false error=skill_not_found", ctx.traceId, env.getTool());
                out = ToolResult.error(env.getTool(), env.getVersion(), "skill_not_found")
                        .withHint("Skill '" + skillName + "' not found in .agent/skills or .claude/skills.");
            }
            
            return out.withTookMs((System.nanoTime() - t0) / 1_000_000L);
        }
    }
}
