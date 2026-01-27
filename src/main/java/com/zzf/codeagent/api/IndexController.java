package com.zzf.codeagent.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzf.codeagent.core.rag.index.CodeAgentV2BulkIndexer;
import com.zzf.codeagent.core.rag.index.CodeAgentV2IndexInitializer;
import com.zzf.codeagent.core.rag.index.ElasticsearchIndexNames;
import com.zzf.codeagent.infrastructure.DockerInfrasManager;
import com.zzf.codeagent.core.rag.pipeline.FullScanService;
import com.zzf.codeagent.core.rag.pipeline.IndexingWorker;
import com.zzf.codeagent.core.rag.pipeline.events.FileChangeEvent;
import com.zzf.codeagent.core.rag.pipeline.ingest.CodeFileIngestService;
import com.zzf.codeagent.core.rag.pipeline.ingest.IngestOutcome;
import com.zzf.codeagent.core.rag.pipeline.redis.InMemoryFileHashCache;
import com.zzf.codeagent.core.rag.pipeline.scan.RepoScanner;
import com.zzf.codeagent.core.rag.vector.EmbeddingService;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeTopicsResult;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsResult;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
public final class IndexController {
    private static final String JSON_UTF8 = "application/json;charset=UTF-8";
    private static final Logger logger = LoggerFactory.getLogger(IndexController.class);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final IndexingWorker worker;
    private final EmbeddingService embeddingService;
    private static final ConcurrentHashMap<String, IndexJob> JOBS = new ConcurrentHashMap<String, IndexJob>();

    @Value("${codeagent.repo-root:}")
    private String repoRoot;

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${codeagent.compose-file:}")
    private String composeFile;

    @Value("${codeagent.repo-name:code-agent}")
    private String repoName;

    @Value("${elasticsearch.scheme:http}")
    private String esScheme;

    @Value("${elasticsearch.host:localhost}")
    private String esHost;

    @Value("${elasticsearch.port:9200}")
    private int esPort;

    @Value("${embedding.api.dimension:2048}")
    private int embeddingDimension;

    public IndexController(IndexingWorker worker, EmbeddingService embeddingService) {
        this.worker = worker;
        this.embeddingService = embeddingService;
    }

    @PostMapping("/api/index/job/start")
    public ResponseEntity<Map<String, Object>> startJob(@RequestBody(required = false) Map<String, Object> req) {
        String jobId = "job-" + UUID.randomUUID();
        String traceId = "trace-indexjob-" + UUID.randomUUID();
        Path repoRootPath = resolveRepoRoot(req);
        boolean startInfra = req != null && Boolean.TRUE.equals(req.get("startInfra"));
        String workspaceRoot = repoRootPath.toString();
        IndexJob job = new IndexJob(jobId, traceId, workspaceRoot);
        JOBS.put(jobId, job);

        Thread t = new Thread(() -> runSyncScanJob(job, repoRootPath, startInfra), "index-job-" + jobId);
        t.setDaemon(true);
        t.start();

        Map<String, Object> out = new HashMap<String, Object>();
        out.put("jobId", jobId);
        out.put("traceId", traceId);
        out.put("workspace_root", workspaceRoot);
        out.put("status", job.status);
        return ResponseEntity.ok().header("Content-Type", JSON_UTF8).body(out);
    }

    @GetMapping("/api/index/job/status")
    public ResponseEntity<Map<String, Object>> jobStatus(@RequestParam("jobId") String jobId) {
        IndexJob job = jobId == null ? null : JOBS.get(jobId.trim());
        Map<String, Object> out = new HashMap<String, Object>();
        out.put("jobId", jobId);
        if (job == null) {
            out.put("status", "not_found");
            return ResponseEntity.ok().header("Content-Type", JSON_UTF8).body(out);
        }
        out.put("traceId", job.traceId);
        out.put("workspace_root", job.workspaceRoot);
        out.put("status", job.status);
        out.put("error", job.error);
        out.put("docker_error", job.dockerError);
        out.put("total_files", job.totalFiles);
        out.put("processed", job.processed);
        out.put("indexed", job.indexed);
        out.put("skipped", job.skipped);
        out.put("failed", job.failed);
        out.put("chunks", job.chunks);
        out.put("embed_ms", job.embedMs);
        out.put("started_at_ms", job.startedAtMs);
        out.put("finished_at_ms", job.finishedAtMs);
        double pct = job.totalFiles <= 0 ? 0.0 : Math.min(1.0, Math.max(0.0, job.processed / (double) job.totalFiles));
        out.put("percent", pct);
        return ResponseEntity.ok().header("Content-Type", JSON_UTF8).body(out);
    }

    @PostMapping("/api/index/job/cancel")
    public ResponseEntity<Map<String, Object>> cancelJob(@RequestBody(required = false) Map<String, Object> req) {
        String jobId = req != null && req.get("jobId") instanceof String ? ((String) req.get("jobId")).trim() : "";
        IndexJob job = jobId.isEmpty() ? null : JOBS.get(jobId);
        Map<String, Object> out = new HashMap<String, Object>();
        out.put("jobId", jobId);
        if (job == null) {
            out.put("status", "not_found");
            return ResponseEntity.ok().header("Content-Type", JSON_UTF8).body(out);
        }
        job.cancelled.set(true);
        out.put("status", "cancelling");
        return ResponseEntity.ok().header("Content-Type", JSON_UTF8).body(out);
    }

    @GetMapping("/api/index/preflight")
    public ResponseEntity<Map<String, Object>> preflight() {
        Map<String, Object> out = new HashMap<String, Object>();
        URI es = URI.create(esScheme + "://" + esHost + ":" + esPort);
        out.put("es", es.toString());

        Path compose = resolveComposePath();
        out.put("compose_file", compose == null ? null : compose.toString());
        DockerInfrasManager manager = compose == null ? DockerInfrasManager.defaultForWorkDir() : new DockerInfrasManager(compose, false);
        out.put("docker_available", manager.isDockerAvailable());

        boolean ok = false;
        Integer httpStatus = null;
        String error = null;
        try {
            HttpClient http = newHttpClient();
            HttpRequest req = HttpRequest.newBuilder(es.resolve("/"))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpResponse<Void> resp = http.send(req, HttpResponse.BodyHandlers.discarding());
            httpStatus = resp.statusCode();
            ok = resp.statusCode() >= 200 && resp.statusCode() < 500;
        } catch (Exception e) {
            error = e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
        }
        out.put("es_ok", ok);
        out.put("es_http_status", httpStatus);
        out.put("es_error", error);
        return ResponseEntity.ok().header("Content-Type", JSON_UTF8).body(out);
    }

    @PostMapping("/api/index/full-scan")
    public ResponseEntity<Map<String, Object>> fullScan(@RequestBody(required = false) Map<String, Object> req) {
        boolean startInfra = req != null && Boolean.TRUE.equals(req.get("startInfra"));
        String dockerError = null;
        String traceId = "trace-fullscan-" + UUID.randomUUID();
        if (startInfra) {
            DockerInfrasManager manager = resolveComposePath() == null
                    ? DockerInfrasManager.defaultForWorkDir()
                    : new DockerInfrasManager(resolveComposePath(), false);
            if (manager.isDockerAvailable()) {
                try {
                    logger.info("index.fullscan.infra.start traceId={} composeFile={}", traceId, resolveComposePath());
                    manager.start();
                    logger.info("index.fullscan.infra.ok traceId={}", traceId);
                } catch (Exception e) {
                    dockerError = e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
                    logger.warn("index.fullscan.infra.fail traceId={} err={}", traceId, dockerError);
                }
            }
        }
        worker.start();

        Path repoRootPath = resolveRepoRoot(req);
        logger.info("index.fullscan.start traceId={} repoRoot={} dims={}", traceId, repoRootPath, embeddingDimension);
        long start = System.nanoTime();
        long beforeIndexed = worker.snapshot().indexed;
        long beforeSkipped = worker.snapshot().skipped;
        long beforeFailed = worker.snapshot().failed;
        long beforeEmbedNanos = worker.snapshot().embedNanos;
        long beforeEmbedCalls = worker.snapshot().embedCalls;

        int total = new FullScanService(bootstrapServers, mapper).scanAndPublish(traceId, repoRootPath);
        logger.info("index.fullscan.publish.ok traceId={} totalFiles={}", traceId, total);

        long deadline = System.currentTimeMillis() + 10 * 60_000L;
        while (System.currentTimeMillis() < deadline) {
            IndexingWorker.Status s = worker.snapshot();
            long done = (s.indexed - beforeIndexed) + (s.skipped - beforeSkipped) + (s.failed - beforeFailed);
            if (done >= total) {
                break;
            }
            sleep(500);
        }

        IndexingWorker.Status after = worker.snapshot();
        long tookMs = (System.nanoTime() - start) / 1_000_000L;
        long newIndexed = after.indexed - beforeIndexed;
        long skipped = after.skipped - beforeSkipped;
        long failed = after.failed - beforeFailed;
        long embedCalls = after.embedCalls - beforeEmbedCalls;
        long embedNanos = after.embedNanos - beforeEmbedNanos;
        double avgEmbeddingSpeed = embedNanos == 0 ? 0.0 : (embedCalls / (embedNanos / 1_000_000_000.0));

        Map<String, Object> report = new HashMap<String, Object>();
        report.put("total_files", total);
        report.put("new_indexed", newIndexed);
        report.put("skipped_by_hash", skipped);
        report.put("failed", failed);
        report.put("total_time_ms", tookMs);
        report.put("avg_embedding_speed", avgEmbeddingSpeed);
        report.put("trace_id", traceId);
        if (dockerError != null) {
            report.put("docker_error", dockerError);
        }
        logger.info("index.fullscan.ok traceId={} totalFiles={} newIndexed={} skipped={} failed={} tookMs={} embedCalls={} embedMs={}",
                traceId, total, newIndexed, skipped, failed, tookMs, embedCalls, embedNanos / 1_000_000L);
        return ResponseEntity.ok().header("Content-Type", JSON_UTF8).body(report);
    }

    @PostMapping("/api/index/sync-scan")
    public ResponseEntity<Map<String, Object>> syncScan(@RequestBody(required = false) Map<String, Object> req) {
        boolean startInfra = req != null && Boolean.TRUE.equals(req.get("startInfra"));
        String dockerError = null;
        String traceId = "trace-syncscan-" + UUID.randomUUID();
        if (startInfra) {
            DockerInfrasManager manager = resolveComposePath() == null
                    ? DockerInfrasManager.defaultForWorkDir()
                    : new DockerInfrasManager(resolveComposePath(), false);
            if (manager.isDockerAvailable()) {
                try {
                    logger.info("index.syncscan.infra.start traceId={} composeFile={}", traceId, resolveComposePath());
                    manager.start();
                    logger.info("index.syncscan.infra.ok traceId={}", traceId);
                } catch (Exception e) {
                    dockerError = e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
                    logger.warn("index.syncscan.infra.fail traceId={} err={}", traceId, dockerError);
                }
            }
        }

        Path repoRootPath = resolveRepoRoot(req);
        String indexName = ElasticsearchIndexNames.codeAgentV2IndexForWorkspaceRoot(repoRootPath.toString());
        logger.info("index.syncscan.start traceId={} repoRoot={} index={} dims={}", traceId, repoRootPath, indexName, embeddingDimension);

        HttpClient http = newHttpClient();
        URI es = URI.create(esScheme + "://" + esHost + ":" + esPort);
        try {
            new CodeAgentV2IndexInitializer(http, es, embeddingDimension).ensureIndexExists(indexName);
        } catch (Exception e) {
            logger.warn("index.syncscan.ensure.fail traceId={} index={} err={}", traceId, indexName, e.toString());
            Map<String, Object> out = new HashMap<String, Object>();
            out.put("trace_id", traceId);
            out.put("workspace_root", repoRootPath.toString());
            out.put("index", indexName);
            out.put("error", "ensureIndexExists failed: " + e.getMessage());
            if (dockerError != null) {
                out.put("docker_error", dockerError);
            }
            return ResponseEntity.ok().header("Content-Type", JSON_UTF8).body(out);
        }

        RepoScanner scanner = new RepoScanner();
        List<FileChangeEvent> events = scanner.scan(traceId, repoRootPath);
        InMemoryFileHashCache cache = new InMemoryFileHashCache();
        CodeAgentV2BulkIndexer bulk = new CodeAgentV2BulkIndexer(http, es);
        CodeFileIngestService ingest = new CodeFileIngestService(cache, embeddingService, bulk, repoName);

        long indexed = 0L;
        long skipped = 0L;
        long failed = 0L;
        long chunks = 0L;
        long embedNanos = 0L;
        long t0 = System.nanoTime();
        for (int i = 0; i < events.size(); i++) {
            FileChangeEvent e = events.get(i);
            try {
                IngestOutcome r = ingest.ingestOne(Paths.get(e.getRepoRoot()), e.getRelativePath(), e.getSha256());
                if (r.isSkipped()) {
                    skipped++;
                } else {
                    indexed++;
                }
                chunks += Math.max(0, r.getChunks());
                embedNanos += Math.max(0L, r.getEmbedNanos());
            } catch (Exception ex) {
                failed++;
            }
        }
        long tookMs = (System.nanoTime() - t0) / 1_000_000L;
        logger.info("index.syncscan.ok traceId={} index={} totalFiles={} indexed={} skipped={} failed={} chunks={} embedMs={} tookMs={}",
                traceId, indexName, events.size(), indexed, skipped, failed, chunks, embedNanos / 1_000_000L, tookMs);

        Map<String, Object> out = new HashMap<String, Object>();
        out.put("trace_id", traceId);
        out.put("workspace_root", repoRootPath.toString());
        out.put("index", indexName);
        out.put("total_files", events.size());
        out.put("indexed", indexed);
        out.put("skipped", skipped);
        out.put("failed", failed);
        out.put("chunks", chunks);
        out.put("embed_ms", embedNanos / 1_000_000L);
        out.put("total_time_ms", tookMs);
        if (dockerError != null) {
            out.put("docker_error", dockerError);
        }
        return ResponseEntity.ok().header("Content-Type", JSON_UTF8).body(out);
    }

    @GetMapping("/api/index/status")
    public ResponseEntity<Map<String, Object>> status() {
        Map<String, Object> out = new HashMap<String, Object>();
        out.put("worker", worker.snapshot());
        out.put("kafka", kafkaLag());
        return ResponseEntity.ok().header("Content-Type", JSON_UTF8).body(out);
    }

    private Map<String, Object> kafkaLag() {
        Map<String, Object> out = new HashMap<String, Object>();
        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", bootstrapServers))) {
            String topic = "code-agent-v2-file-change";
            String group = "code-agent-v2-indexer";

            DescribeTopicsResult topics = admin.describeTopics(java.util.List.of(topic));
            java.util.List<TopicPartitionInfo> partitions = topics.values().get(topic).get(5, TimeUnit.SECONDS).partitions();

            ListConsumerGroupOffsetsResult groupOffsets = admin.listConsumerGroupOffsets(group);
            Map<TopicPartition, OffsetAndMetadata> committed = groupOffsets.partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);

            long totalLag = 0L;
            java.util.List<Map<String, Object>> perPartition = new java.util.ArrayList<Map<String, Object>>();
            Map<TopicPartition, OffsetSpec> endReq = new HashMap<TopicPartition, OffsetSpec>();
            for (int i = 0; i < partitions.size(); i++) {
                TopicPartition tp = new TopicPartition(topic, partitions.get(i).partition());
                endReq.put(tp, OffsetSpec.latest());
            }
            ListOffsetsResult endOffsets = admin.listOffsets(endReq);
            for (int i = 0; i < partitions.size(); i++) {
                int p = partitions.get(i).partition();
                TopicPartition tp = new TopicPartition(topic, p);
                long end = endOffsets.partitionResult(tp).get(5, TimeUnit.SECONDS).offset();
                OffsetAndMetadata om = committed.get(tp);
                long committedOffset = om == null ? 0L : om.offset();
                long lag = Math.max(0L, end - committedOffset);
                totalLag += lag;
                Map<String, Object> row = new HashMap<String, Object>();
                row.put("partition", p);
                row.put("end_offset", end);
                row.put("committed_offset", committedOffset);
                row.put("lag", lag);
                perPartition.add(row);
            }

            out.put("topic", topic);
            out.put("group", group);
            out.put("total_lag", totalLag);
            out.put("partitions", perPartition);
        } catch (Exception e) {
            out.put("error", e.getMessage());
        }
        return out;
    }

    private Path resolveRepoRoot() {
        return resolveRepoRoot(null);
    }

    private Path resolveRepoRoot(Map<String, Object> req) {
        if (req != null) {
            Object fromReq = req.get("workspaceRoot");
            if (fromReq instanceof String && !((String) fromReq).trim().isEmpty()) {
                return Paths.get(((String) fromReq).trim()).toAbsolutePath().normalize();
            }
        }
        if (repoRoot != null && !repoRoot.trim().isEmpty()) {
            return Paths.get(repoRoot.trim()).toAbsolutePath().normalize();
        }
        Path cwd = Paths.get(System.getProperty("user.dir"));
        Path parent = cwd.getParent();
        return parent == null ? cwd : parent;
    }

    private Path resolveComposePath() {
        if (composeFile != null && !composeFile.trim().isEmpty()) {
            Path p = Paths.get(composeFile.trim()).toAbsolutePath().normalize();
            if (Files.exists(p)) {
                return p;
            }
        }
        Path cwd = Paths.get(System.getProperty("user.dir"));
        Path local = cwd.resolve("docker-compose.yml").normalize();
        if (Files.exists(local)) {
            return local;
        }
        Path parent = cwd.resolve("../docker-compose.yml").normalize();
        if (Files.exists(parent)) {
            return parent;
        }
        return null;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private void runSyncScanJob(IndexJob job, Path repoRootPath, boolean startInfra) {
        job.startedAtMs = System.currentTimeMillis();
        job.status = "running";
        String dockerError = null;
        try {
            if (startInfra) {
                DockerInfrasManager manager = resolveComposePath() == null
                        ? DockerInfrasManager.defaultForWorkDir()
                        : new DockerInfrasManager(resolveComposePath(), false);
                if (manager.isDockerAvailable()) {
                    try {
                        logger.info("index.job.infra.start traceId={} jobId={} composeFile={}", job.traceId, job.jobId, resolveComposePath());
                        manager.start();
                        logger.info("index.job.infra.ok traceId={} jobId={}", job.traceId, job.jobId);
                    } catch (Exception e) {
                        dockerError = e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
                        logger.warn("index.job.infra.fail traceId={} jobId={} err={}", job.traceId, job.jobId, dockerError);
                    }
                }
            }

            String indexName = ElasticsearchIndexNames.codeAgentV2IndexForWorkspaceRoot(repoRootPath.toString());
            logger.info("index.job.start traceId={} jobId={} repoRoot={} index={} dims={}", job.traceId, job.jobId, repoRootPath, indexName, embeddingDimension);
            HttpClient http = newHttpClient();
            URI es = URI.create(esScheme + "://" + esHost + ":" + esPort);
            new CodeAgentV2IndexInitializer(http, es, embeddingDimension).ensureIndexExists(indexName);

            RepoScanner scanner = new RepoScanner();
            List<FileChangeEvent> events = scanner.scan(job.traceId, repoRootPath);
            job.totalFiles = events == null ? 0 : events.size();

            InMemoryFileHashCache cache = new InMemoryFileHashCache();
            CodeAgentV2BulkIndexer bulk = new CodeAgentV2BulkIndexer(http, es);
            CodeFileIngestService ingest = new CodeFileIngestService(cache, embeddingService, bulk, repoName);

            long embedNanos = 0L;
            for (int i = 0; i < job.totalFiles; i++) {
                if (job.cancelled.get()) {
                    job.status = "cancelled";
                    break;
                }
                FileChangeEvent e = events.get(i);
                try {
                    IngestOutcome r = ingest.ingestOne(Paths.get(e.getRepoRoot()), e.getRelativePath(), e.getSha256());
                    if (r.isSkipped()) {
                        job.skipped++;
                    } else {
                        job.indexed++;
                    }
                    job.chunks += Math.max(0, r.getChunks());
                    embedNanos += Math.max(0L, r.getEmbedNanos());
                } catch (Exception ex) {
                    job.failed++;
                } finally {
                    job.processed++;
                }
            }
            job.embedMs = embedNanos / 1_000_000L;
            job.dockerError = dockerError;
            if (!"cancelled".equals(job.status)) {
                job.status = "done";
            }
        } catch (Exception e) {
            job.status = "failed";
            job.error = e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
            job.dockerError = dockerError;
            logger.warn("index.job.fail traceId={} jobId={} err={}", job.traceId, job.jobId, job.error);
        } finally {
            job.finishedAtMs = System.currentTimeMillis();
        }
    }

    private static HttpClient newHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    private static final class IndexJob {
        public final String jobId;
        public final String traceId;
        public final String workspaceRoot;
        public final AtomicBoolean cancelled = new AtomicBoolean(false);
        public volatile String status = "queued";
        public volatile String error;
        public volatile String dockerError;
        public volatile int totalFiles;
        public volatile int processed;
        public volatile long indexed;
        public volatile long skipped;
        public volatile long failed;
        public volatile long chunks;
        public volatile long embedMs;
        public volatile long startedAtMs;
        public volatile long finishedAtMs;

        private IndexJob(String jobId, String traceId, String workspaceRoot) {
            this.jobId = jobId;
            this.traceId = traceId;
            this.workspaceRoot = workspaceRoot;
        }
    }
}
