package com.zzf.codeagent.core.rag.pipeline.kafka;

import com.zzf.codeagent.core.rag.pipeline.events.FileChangeEvent;
import com.zzf.codeagent.core.rag.pipeline.ingest.CodeFileIngestService;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;

import java.nio.file.Paths;

public final class FileChangeConsumer {
    private final CodeFileIngestService ingestService;

    public FileChangeConsumer(CodeFileIngestService ingestService) {
        this.ingestService = ingestService;
    }

    @KafkaListener(topics = FileChangeProducer.TOPIC_FILE_CHANGE, groupId = "code-agent-v2")
    public void onMessage(FileChangeEvent event) {
        if (event == null) {
            return;
        }
        MDC.put("traceId", event.getTraceId());
        try {
            ingestService.ingestOne(Paths.get(event.getRepoRoot()), event.getRelativePath(), event.getSha256());
        } finally {
            MDC.remove("traceId");
        }
    }
}
