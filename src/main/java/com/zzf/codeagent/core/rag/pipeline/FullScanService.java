package com.zzf.codeagent.core.rag.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzf.codeagent.core.rag.pipeline.events.FileChangeEvent;
import com.zzf.codeagent.core.rag.pipeline.kafka.FileChangeEventJson;
import com.zzf.codeagent.core.rag.pipeline.kafka.FileChangeProducer;
import com.zzf.codeagent.core.rag.pipeline.scan.RepoScanner;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

public final class FullScanService {
    private static final Logger logger = LoggerFactory.getLogger(FullScanService.class);

    private final RepoScanner scanner;
    private final KafkaProducer<String, String> producer;
    private final FileChangeEventJson json;

    public FullScanService(String bootstrapServers, ObjectMapper mapper) {
        this.scanner = new RepoScanner();
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        this.producer = new KafkaProducer<String, String>(p);
        this.json = new FileChangeEventJson(mapper);
    }

    public FullScanService(RepoScanner scanner, KafkaProducer<String, String> producer, FileChangeEventJson json) {
        this.scanner = scanner;
        this.producer = producer;
        this.json = json;
    }

    public int scanAndPublish(String traceId, Path repoRoot) {
        long t0 = System.nanoTime();
        logger.info("fullscan.start traceId={} repoRoot={} topic={}", traceId, repoRoot, FileChangeProducer.TOPIC_FILE_CHANGE);
        List<FileChangeEvent> events = scanner.scan(traceId, repoRoot);
        logger.info("fullscan.scan.ok traceId={} repoRoot={} events={}", traceId, repoRoot, events.size());
        for (int i = 0; i < events.size(); i++) {
            FileChangeEvent e = events.get(i);
            String payload = json.toJson(e);
            producer.send(new ProducerRecord<String, String>(FileChangeProducer.TOPIC_FILE_CHANGE, traceId, payload));
            if ((i + 1) % 200 == 0) {
                logger.info("fullscan.publish.progress traceId={} sent={}/{}", traceId, (i + 1), events.size());
            }
        }
        producer.flush();
        long tookMs = (System.nanoTime() - t0) / 1_000_000L;
        logger.info("fullscan.ok traceId={} repoRoot={} events={} tookMs={}", traceId, repoRoot, events.size(), tookMs);
        return events.size();
    }
}
