package com.zzf.rikki.idea.agent.compat;

import com.fasterxml.jackson.databind.JsonNode;

public class ToolCallInfo {
    private final String id;
    private final String name;
    private final String argsRaw;
    private final JsonNode args;

    public ToolCallInfo(String id, String name, String argsRaw, JsonNode args) {
        this.id = id;
        this.name = name;
        this.argsRaw = argsRaw;
        this.args = args;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getArgsRaw() {
        return argsRaw;
    }

    public JsonNode getArgs() {
        return args;
    }
}
