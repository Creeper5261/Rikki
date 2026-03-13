package com.zzf.rikki.core.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.zzf.rikki.session.model.MessageV2;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public interface Tool {

    final class Context {
        private String sessionID;
        private String messageID;
        private String agent;
        private String callID;
        private List<MessageV2.WithParts> messages = List.of();
        private Map<String, Object> extra = Map.of();
        private MetadataConsumer metadataConsumer;
        private PermissionAsker permissionAsker;

        public static Context basic(String sessionID, String messageID, String callID) {
            Context context = new Context();
            context.setSessionID(sessionID);
            context.setMessageID(messageID);
            context.setCallID(callID);
            return context;
        }

        public String getSessionID() {
            return sessionID;
        }

        public void setSessionID(String sessionID) {
            this.sessionID = sessionID;
        }

        public String getMessageID() {
            return messageID;
        }

        public void setMessageID(String messageID) {
            this.messageID = messageID;
        }

        public String getAgent() {
            return agent;
        }

        public void setAgent(String agent) {
            this.agent = agent;
        }

        public String getCallID() {
            return callID;
        }

        public void setCallID(String callID) {
            this.callID = callID;
        }

        public List<MessageV2.WithParts> getMessages() {
            return messages;
        }

        public void setMessages(List<MessageV2.WithParts> messages) {
            this.messages = messages == null ? List.of() : new ArrayList<>(messages);
        }

        public Map<String, Object> getExtra() {
            return extra;
        }

        public void setExtra(Map<String, Object> extra) {
            this.extra = extra == null ? Map.of() : new LinkedHashMap<>(extra);
        }

        public MetadataConsumer getMetadataConsumer() {
            return metadataConsumer;
        }

        public void setMetadataConsumer(MetadataConsumer metadataConsumer) {
            this.metadataConsumer = metadataConsumer;
        }

        public PermissionAsker getPermissionAsker() {
            return permissionAsker;
        }

        public void setPermissionAsker(PermissionAsker permissionAsker) {
            this.permissionAsker = permissionAsker;
        }

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

    final class Result {
        private final String title;
        private final Map<String, Object> metadata;
        private final String output;
        private final List<MessageV2.FilePart> attachments;

        public Result(String title, Map<String, Object> metadata, String output, List<MessageV2.FilePart> attachments) {
            this.title = title;
            this.metadata = metadata == null ? Map.of() : new LinkedHashMap<>(metadata);
            this.output = output == null ? "" : output;
            this.attachments = attachments == null ? List.of() : new ArrayList<>(attachments);
        }

        public static Result of(String title, String output) {
            return new Result(title, Map.of(), output, List.of());
        }

        public String getTitle() {
            return title;
        }

        public Map<String, Object> getMetadata() {
            return metadata;
        }

        public String getOutput() {
            return output;
        }

        public List<MessageV2.FilePart> getAttachments() {
            return attachments;
        }
    }

    String getId();

    String getDescription();

    JsonNode getParametersSchema();

    CompletableFuture<Result> execute(JsonNode args, Context ctx);

    default void cancel(String sessionID, String callID) {
    }
}