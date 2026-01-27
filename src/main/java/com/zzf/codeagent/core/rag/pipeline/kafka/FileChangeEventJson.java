package com.zzf.codeagent.core.rag.pipeline.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzf.codeagent.core.rag.pipeline.events.FileChangeEvent;

public final class FileChangeEventJson {
    private final ObjectMapper mapper;

    public FileChangeEventJson(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String toJson(FileChangeEvent e) {
        try {
            return mapper.writeValueAsString(e);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    public FileChangeEvent fromJson(String json) {
        try {
            return mapper.readValue(json, FileChangeEvent.class);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}
