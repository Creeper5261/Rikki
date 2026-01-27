package com.zzf.codeagent.core.rag.search;

public final class CodeSearchQuery {
    private final String query;
    private final int topK;
    private final int maxSnippetChars;

    public CodeSearchQuery(String query, int topK, int maxSnippetChars) {
        this.query = query;
        this.topK = topK;
        this.maxSnippetChars = maxSnippetChars;
    }

    public String getQuery() {
        return query;
    }

    public int getTopK() {
        return topK;
    }

    public int getMaxSnippetChars() {
        return maxSnippetChars;
    }
}
