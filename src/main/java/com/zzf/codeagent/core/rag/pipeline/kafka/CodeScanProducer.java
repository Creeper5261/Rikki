package com.zzf.codeagent.core.rag.pipeline.kafka;

import org.springframework.kafka.core.KafkaTemplate;

public final class CodeScanProducer {
    public static final String TOPIC_CODE_SCAN = "code-agent-v2-scan";

    private final KafkaTemplate<String, CodeScanRequest> kafkaTemplate;

    public CodeScanProducer(KafkaTemplate<String, CodeScanRequest> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publish(CodeScanRequest request) {
        kafkaTemplate.send(TOPIC_CODE_SCAN, request.getTraceId(), request);
    }
}
