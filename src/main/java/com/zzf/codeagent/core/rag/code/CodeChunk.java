package com.zzf.codeagent.core.rag.code;

public final class CodeChunk {
    private final String id;
    private final String language;
    private final String filePath;
    private final String symbolKind;
    private final String symbolName;
    private final String signature;
    private final int startLine;
    private final int endLine;
    private final String content;

    public CodeChunk(
            String id,
            String language,
            String filePath,
            String symbolKind,
            String symbolName,
            String signature,
            int startLine,
            int endLine,
            String content
    ) {
        this.id = id;
        this.language = language;
        this.filePath = filePath;
        this.symbolKind = symbolKind;
        this.symbolName = symbolName;
        this.signature = signature;
        this.startLine = startLine;
        this.endLine = endLine;
        this.content = content;
    }

    public String getId() {
        return id;
    }

    public String getLanguage() {
        return language;
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

    public String getSignature() {
        return signature;
    }

    public int getStartLine() {
        return startLine;
    }

    public int getEndLine() {
        return endLine;
    }

    public String getContent() {
        return content;
    }
}
