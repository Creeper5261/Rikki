package com.zzf.codeagent.core.rag.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzf.codeagent.core.rag.index.CodeAgentV2BulkIndexer;
import com.zzf.codeagent.core.rag.index.CodeAgentV2IndexInitializer;
import com.zzf.codeagent.core.rag.index.ElasticsearchIndexNames;
import com.zzf.codeagent.core.rag.pipeline.events.FileChangeEvent;
import com.zzf.codeagent.core.rag.pipeline.ingest.CodeFileIngestService;
import com.zzf.codeagent.core.rag.pipeline.ingest.IngestOutcome;
import com.zzf.codeagent.core.rag.pipeline.kafka.FileChangeEventJson;
import com.zzf.codeagent.core.rag.pipeline.kafka.FileChangeProducer;
import com.zzf.codeagent.core.rag.pipeline.redis.FileHashCache;
import com.zzf.codeagent.core.rag.pipeline.redis.RedisFileHashCache;
import com.zzf.codeagent.core.rag.vector.EmbeddingService;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Collections;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Component
public final class IndexingWorker implements InitializingBean, DisposableBean {
    private static final Logger logger = LoggerFactory.getLogger(IndexingWorker.class);

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread thread;

    private final AtomicLong processed = new AtomicLong(0);
    private final AtomicLong indexed = new AtomicLong(0);
    private final AtomicLong skipped = new AtomicLong(0);
    private final AtomicLong failed = new AtomicLong(0);
    private final AtomicLong embedNanos = new AtomicLong(0);
    private final AtomicLong embedCalls = new AtomicLong(0);

    private volatile String lastError;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final FileChangeEventJson json = new FileChangeEventJson(mapper);

    private final String bootstrapServers;
    private final HttpClient http = HttpClient.newHttpClient();
    private final URI es;
    private final String redisHost;
    private final int redisPort;
    private final int redisTimeoutMs;
    private final String repoName;
    private final EmbeddingService embeddingService;
    private final int embeddingDimension;

    public IndexingWorker(
            @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers,
            @Value("${elasticsearch.scheme:http}") String esScheme,
            @Value("${elasticsearch.host:localhost}") String esHost,
            @Value("${elasticsearch.port:9200}") int esPort,
            @Value("${codeagent.redis.host:localhost}") String redisHost,
            @Value("${codeagent.redis.port:6379}") int redisPort,
            @Value("${codeagent.redis.timeout-ms:2000}") int redisTimeoutMs,
            @Value("${codeagent.repo-name:code-agent}") String repoName,
            EmbeddingService embeddingService,
            @Value("${embedding.api.dimension:2048}") int embeddingDimension
    ) {
        this.bootstrapServers = bootstrapServers;
        this.es = URI.create(esScheme + "://" + esHost + ":" + esPort);
        this.redisHost = redisHost;
        this.redisPort = redisPort;
        this.redisTimeoutMs = redisTimeoutMs;
        this.repoName = repoName;
        this.embeddingService = embeddingService;
        this.embeddingDimension = embeddingDimension;
    }

    @Override
    public void afterPropertiesSet() {
        boolean autoStart = Boolean.parseBoolean(System.getProperty("codeagent.indexer.autostart", "false"));
        if (autoStart) {
            logger.info("indexer.autostart enabled=true");
            start();
        } else {
            logger.info("indexer.autostart enabled=false");
        }
    }

    @Override
    public void destroy() {
        stop();
    }

    public void start() {
        if (running.getAndSet(true)) {
            logger.info("indexer.start skip reason=already_running");
            return;
        }
        logger.info("indexer.start ok bootstrap={} es={} redis={}:{} repoName={} dims={}", bootstrapServers, es, redisHost, redisPort, repoName, embeddingDimension);
        thread = new Thread(this::runLoop, "codeagent-indexing-worker");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running.set(false);
        if (thread != null) {
            try {
                thread.join(3000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        logger.info("indexer.stop ok");
    }

    public Status snapshot() {
        Status s = new Status();
        s.processed = processed.get();
        s.indexed = indexed.get();
        s.skipped = skipped.get();
        s.failed = failed.get();
        s.embedNanos = embedNanos.get();
        s.embedCalls = embedCalls.get();
        s.lastError = lastError;
        return s;
    }

    private void runLoop() {
        CodeAgentV2IndexInitializer initializer = new CodeAgentV2IndexInitializer(http, es, embeddingDimension);
        Set<String> ensured = ConcurrentHashMap.newKeySet();

        FileHashCache cache = new RedisFileHashCache(redisHost, redisPort, redisTimeoutMs);
        CodeAgentV2BulkIndexer indexer = new CodeAgentV2BulkIndexer(http, es);
        CodeFileIngestService ingest = new CodeFileIngestService(cache, embeddingService, indexer, repoName);

        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "code-agent-v2-indexer");
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        KafkaConsumer<String, String> consumer = new KafkaConsumer<String, String>(p);
        consumer.subscribe(Collections.singletonList(FileChangeProducer.TOPIC_FILE_CHANGE));
        logger.info("indexer.consumer.subscribed topic={} group={}", FileChangeProducer.TOPIC_FILE_CHANGE, "code-agent-v2-indexer");

        while (running.get()) {
            try {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                if (!records.isEmpty()) {
                    logger.info("indexer.poll records={}", records.count());
                }
                for (ConsumerRecord<String, String> r : records) {
                    processed.incrementAndGet();
                    try {
                        FileChangeEvent e = json.fromJson(r.value());
                        logger.debug("indexer.event traceId={} repoRoot={} path={} sha256={}", e.getTraceId(), e.getRepoRoot(), e.getRelativePath(), e.getSha256());
                        String indexName = ElasticsearchIndexNames.codeAgentV2IndexForWorkspaceRoot(e.getRepoRoot());
                        if (ensured.add(indexName)) {
                            try {
                                initializer.ensureIndexExists(indexName);
                                logger.info("index.ensure ok index={} dims={}", indexName, embeddingDimension);
                            } catch (Exception ex) {
                                logger.error("index.ensure failed index={}", indexName, ex);
                            }
                        }
                        IngestOutcome out = ingest.ingestOne(Paths.get(e.getRepoRoot()), e.getRelativePath(), e.getSha256());
                        if (out.isSkipped()) {
                            skipped.incrementAndGet();
                        } else {
                            indexed.incrementAndGet();
                        }
                        embedNanos.addAndGet(out.getEmbedNanos());
                        embedCalls.addAndGet(Math.max(0, out.getChunks()));
                        logger.info("indexer.ingest.result path={} skipped={} chunks={} embedMs={}",
                                e.getRelativePath(), out.isSkipped(), out.getChunks(), out.getEmbedNanos() / 1_000_000L);
                    } catch (Exception ex) {
                        failed.incrementAndGet();
                        lastError = ex.getMessage();
                        logger.error("ingest.failed", ex);
                    }
                }
            } catch (Exception e) {
                lastError = e.getMessage();
                logger.error("consumer.poll.failed", e);
                sleep(1000);
            }
        }
        consumer.close(Duration.ofSeconds(2));
        logger.info("indexer.consumer.closed");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    public static final class Status {
        public long processed;
        public long indexed;
        public long skipped;
        public long failed;
        public long embedNanos;
        public long embedCalls;
        public String lastError;
    }
}
