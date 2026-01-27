package com.zzf.codeagent.core.rag.pipeline.kafka;

import com.zzf.codeagent.core.rag.pipeline.events.FileChangeEvent;
import org.springframework.kafka.core.KafkaTemplate;

public final class FileChangeProducer {
    public static final String TOPIC_FILE_CHANGE = "code-agent-v2-file-change";

    private final KafkaTemplate<String, FileChangeEvent> kafkaTemplate;

    public FileChangeProducer(KafkaTemplate<String, FileChangeEvent> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publish(FileChangeEvent event) {
        kafkaTemplate.send(TOPIC_FILE_CHANGE, event.getTraceId(), event);
    }
}
