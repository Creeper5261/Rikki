package com.zzf.codeagent.core.rag.search;

public final class CodeSearchHit {
    private final String filePath;
    private final String symbolKind;
    private final String symbolName;
    private final int startLine;
    private final int endLine;
    private final String snippet;
    private final boolean truncated;
    private double score; // Reranking score

    public CodeSearchHit(
            String filePath,
            String symbolKind,
            String symbolName,
            int startLine,
            int endLine,
            String snippet,
            boolean truncated
    ) {
        this.filePath = filePath;
        this.symbolKind = symbolKind;
        this.symbolName = symbolName;
        this.startLine = startLine;
        this.endLine = endLine;
        this.snippet = snippet;
        this.truncated = truncated;
        this.score = 0.0;
    }

    public void setScore(double score) {
        this.score = score;
    }

    public double getScore() {
        return score;
    }

    public String getFilePath() {
        return filePath;
    }

    public String getSymbolKind() {
        return symbolKind;
    }

    public String getSymbolName() {
        return symbolName;
    }

    public int getStartLine() {
        return startLine;
    }

    public int getEndLine() {
        return endLine;
    }

    public String getSnippet() {
        return snippet;
    }

    public boolean isTruncated() {
        return truncated;
    }
}
