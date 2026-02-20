package com.zzf.rikki.session.model;

import lombok.Builder;
import lombok.Data;
import lombok.extern.jackson.Jacksonized;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Todo 信息模型 (对齐 OpenCode Todo.Info)
 */
@Data
@Builder
@Jacksonized
@JsonIgnoreProperties(ignoreUnknown = true)
public class TodoInfo {
    private String id;
    private String content;
    private String status; 
    private String priority; 
}
