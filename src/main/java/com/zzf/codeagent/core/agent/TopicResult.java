package com.zzf.codeagent.core.agent;

public final class TopicResult {
    public final boolean isNewTopic;
    public final String title;

    public TopicResult(boolean isNewTopic, String title) {
        this.isNewTopic = isNewTopic;
        this.title = title;
    }

    public static TopicResult empty() {
        return new TopicResult(false, null);
    }
}
