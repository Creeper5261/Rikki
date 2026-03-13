package com.zzf.rikki.runtime.scenario;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RuntimeScenarioSpec {
    public String name = "";
    public boolean live = false;
    public String workspaceFixture = "";
    public String goal = "";
    public List<Map<String, Object>> history = new ArrayList<>();
    public Config config = new Config();
    public Expected expected = new Expected();
    public Controls controls = new Controls();
    public List<ScriptTurn> script = new ArrayList<>();
    public Map<String, String> stubs = new LinkedHashMap<>();

    public static final class Config {
        public String provider = "DEEPSEEK";
        public String model = "deepseek-reasoner";
        public String baseUrl = "";
        public String apiKeyEnv = "";
        public String agent = "";
        public String language = "";
        public Double temperature = null;
    }

    public static final class Expected {
        public boolean requireReasoning = false;
        public boolean requireNonEmptyAnswer = true;
        public List<String> requiredTools = new ArrayList<>();
        public List<String> forbiddenEvents = new ArrayList<>();
        public String answerContains = "";
        public String answerRegex = "";
        public Map<String, String> toolOutputContains = new LinkedHashMap<>();
    }

    public static final class Controls {
        public boolean approvePending = false;
        public boolean rejectPending = false;
        public String skipOnTool = "";
        public int skipDelayMs = 200;
    }

    public static final class ScriptTurn {
        public String reasoning = "";
        public String text = "";
        public List<ToolCallSpec> toolCalls = new ArrayList<>();
    }

    public static final class ToolCallSpec {
        public String id = "";
        public String name = "";
        public Map<String, Object> args = new LinkedHashMap<>();
    }
}
