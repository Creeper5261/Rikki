package com.zzf.codeagent.core.rag.pipeline.kafka;

import com.zzf.codeagent.core.rag.pipeline.CodeIngestionPipeline;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;

import java.nio.file.Paths;

public final class CodeScanConsumer {
    private final CodeIngestionPipeline pipeline;

    public CodeScanConsumer(CodeIngestionPipeline pipeline) {
        this.pipeline = pipeline;
    }

    @KafkaListener(topics = CodeScanProducer.TOPIC_CODE_SCAN, groupId = "code-agent-v2")
    public void onMessage(CodeScanRequest request) {
        if (request == null) {
            return;
        }
        MDC.put("traceId", request.getTraceId());
        try {
            pipeline.ingest(Paths.get(request.getRepoRoot()));
        } finally {
            MDC.remove("traceId");
        }
    }
}
