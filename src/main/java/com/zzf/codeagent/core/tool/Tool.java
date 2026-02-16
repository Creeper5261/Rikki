package com.zzf.codeagent.core.tool;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.zzf.codeagent.session.model.MessageV2;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Tool 基础接口 (对齐 opencode/src/tool/tool.ts)
 */
public interface Tool {

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    class Context {
        private String sessionID;
        private String messageID;
        private String agent;
        private String callID;
        private List<MessageV2.WithParts> messages;
        private Map<String, Object> extra;
        
        // 用于更新元数据
        private MetadataConsumer metadataConsumer;
        // 用于权限请求
        private PermissionAsker permissionAsker;

        public void metadata(String title, Map<String, Object> metadata) {
            if (metadataConsumer != null) {
                metadataConsumer.accept(title, metadata);
            }
        }

        public CompletableFuture<Void> ask(Map<String, Object> request) {
            if (permissionAsker != null) {
                return permissionAsker.ask(request);
            }
            return CompletableFuture.completedFuture(null);
        }
    }

    @FunctionalInterface
    interface MetadataConsumer {
        void accept(String title, Map<String, Object> metadata);
    }

    @FunctionalInterface
    interface PermissionAsker {
        CompletableFuture<Void> ask(Map<String, Object> request);
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    class Result {
        private String title;
        private Map<String, Object> metadata;
        private String output;
        private List<MessageV2.FilePart> attachments;
    }

    String getId();

    String getDescription();

    JsonNode getParametersSchema();

    CompletableFuture<Result> execute(JsonNode args, Context ctx);

    default void cancel(String sessionID, String callID) {
        // Optional cancellation hook for long-running tools.
    }
}
