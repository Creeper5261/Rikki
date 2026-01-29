package com.zzf.codeagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "codeagent.agent")
public class AgentConfig {
    private int maxTurns = 30;
    private int maxToolCalls = 18;
    private int maxConsecutiveToolErrors = 3;
    private int toolBackoffThreshold = 2;
    private int maxObsChars = 4000;
    private int maxPromptChars = 24000;
    private int maxGoalChars = 4000;
    private int maxChatLines = 8;
    private int maxChatLineChars = 600;
    private int maxChatBlockChars = 4000;
    private int maxFacts = 120;
    private int maxFactsBlockChars = 6000;
    private int maxPinnedChars = 4000;
    private int maxIdeContextChars = 6000;
    private int maxHistoryLines = 6;
    private int maxClaudeMemoryChars = 6000;
    private int maxLongTermMemoryChars = 8000;
    private int maxSkillContentChars = 2500;
    private int maxAutoSkills = 2;
    private int readFilePreviewChars = 1200;
    private int thoughtChunkSize = 160;
    private String systemHeaderResource = "prompt/codex_header.txt";

    public int getMaxTurns() {
        return maxTurns;
    }

    public void setMaxTurns(int maxTurns) {
        this.maxTurns = maxTurns;
    }

    public int getMaxToolCalls() {
        return maxToolCalls;
    }

    public void setMaxToolCalls(int maxToolCalls) {
        this.maxToolCalls = maxToolCalls;
    }

    public int getMaxConsecutiveToolErrors() {
        return maxConsecutiveToolErrors;
    }

    public void setMaxConsecutiveToolErrors(int maxConsecutiveToolErrors) {
        this.maxConsecutiveToolErrors = maxConsecutiveToolErrors;
    }

    public int getToolBackoffThreshold() {
        return toolBackoffThreshold;
    }

    public void setToolBackoffThreshold(int toolBackoffThreshold) {
        this.toolBackoffThreshold = toolBackoffThreshold;
    }

    public int getMaxObsChars() {
        return maxObsChars;
    }

    public void setMaxObsChars(int maxObsChars) {
        this.maxObsChars = maxObsChars;
    }

    public int getMaxPromptChars() {
        return maxPromptChars;
    }

    public void setMaxPromptChars(int maxPromptChars) {
        this.maxPromptChars = maxPromptChars;
    }

    public int getMaxGoalChars() {
        return maxGoalChars;
    }

    public void setMaxGoalChars(int maxGoalChars) {
        this.maxGoalChars = maxGoalChars;
    }

    public int getMaxChatLines() {
        return maxChatLines;
    }

    public void setMaxChatLines(int maxChatLines) {
        this.maxChatLines = maxChatLines;
    }

    public int getMaxChatLineChars() {
        return maxChatLineChars;
    }

    public void setMaxChatLineChars(int maxChatLineChars) {
        this.maxChatLineChars = maxChatLineChars;
    }

    public int getMaxChatBlockChars() {
        return maxChatBlockChars;
    }

    public void setMaxChatBlockChars(int maxChatBlockChars) {
        this.maxChatBlockChars = maxChatBlockChars;
    }

    public int getMaxFacts() {
        return maxFacts;
    }

    public void setMaxFacts(int maxFacts) {
        this.maxFacts = maxFacts;
    }

    public int getMaxFactsBlockChars() {
        return maxFactsBlockChars;
    }

    public void setMaxFactsBlockChars(int maxFactsBlockChars) {
        this.maxFactsBlockChars = maxFactsBlockChars;
    }

    public int getMaxPinnedChars() {
        return maxPinnedChars;
    }

    public void setMaxPinnedChars(int maxPinnedChars) {
        this.maxPinnedChars = maxPinnedChars;
    }

    public int getMaxIdeContextChars() {
        return maxIdeContextChars;
    }

    public void setMaxIdeContextChars(int maxIdeContextChars) {
        this.maxIdeContextChars = maxIdeContextChars;
    }

    public int getMaxHistoryLines() {
        return maxHistoryLines;
    }

    public void setMaxHistoryLines(int maxHistoryLines) {
        this.maxHistoryLines = maxHistoryLines;
    }

    public int getMaxClaudeMemoryChars() {
        return maxClaudeMemoryChars;
    }

    public void setMaxClaudeMemoryChars(int maxClaudeMemoryChars) {
        this.maxClaudeMemoryChars = maxClaudeMemoryChars;
    }

    public int getMaxLongTermMemoryChars() {
        return maxLongTermMemoryChars;
    }

    public void setMaxLongTermMemoryChars(int maxLongTermMemoryChars) {
        this.maxLongTermMemoryChars = maxLongTermMemoryChars;
    }

    public int getMaxSkillContentChars() {
        return maxSkillContentChars;
    }

    public void setMaxSkillContentChars(int maxSkillContentChars) {
        this.maxSkillContentChars = maxSkillContentChars;
    }

    public int getMaxAutoSkills() {
        return maxAutoSkills;
    }

    public void setMaxAutoSkills(int maxAutoSkills) {
        this.maxAutoSkills = maxAutoSkills;
    }

    public int getReadFilePreviewChars() {
        return readFilePreviewChars;
    }

    public void setReadFilePreviewChars(int readFilePreviewChars) {
        this.readFilePreviewChars = readFilePreviewChars;
    }

    public int getThoughtChunkSize() {
        return thoughtChunkSize;
    }

    public void setThoughtChunkSize(int thoughtChunkSize) {
        this.thoughtChunkSize = thoughtChunkSize;
    }

    public String getSystemHeaderResource() {
        return systemHeaderResource;
    }

    public void setSystemHeaderResource(String systemHeaderResource) {
        this.systemHeaderResource = systemHeaderResource;
    }

    public String getSkillSystemPromptResource() {
        return skillSystemPromptResource;
    }

    public void setSkillSystemPromptResource(String skillSystemPromptResource) {
        this.skillSystemPromptResource = skillSystemPromptResource;
    }

    public String getCheckNewTopicPromptResource() {
        return checkNewTopicPromptResource;
    }

    public void setCheckNewTopicPromptResource(String checkNewTopicPromptResource) {
        this.checkNewTopicPromptResource = checkNewTopicPromptResource;
    }

    public String getIdeOpenedFilePromptResource() {
        return ideOpenedFilePromptResource;
    }

    public void setIdeOpenedFilePromptResource(String ideOpenedFilePromptResource) {
        this.ideOpenedFilePromptResource = ideOpenedFilePromptResource;
    }
}
