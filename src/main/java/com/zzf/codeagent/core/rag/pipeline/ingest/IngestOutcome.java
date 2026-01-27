package com.zzf.codeagent.core.rag.pipeline.ingest;

public final class IngestOutcome {
    private final boolean skipped;
    private final int chunks;
    private final long embedNanos;

    public IngestOutcome(boolean skipped, int chunks, long embedNanos) {
        this.skipped = skipped;
        this.chunks = chunks;
        this.embedNanos = embedNanos;
    }

    public boolean isSkipped() {
        return skipped;
    }

    public int getChunks() {
        return chunks;
    }

    public long getEmbedNanos() {
        return embedNanos;
    }
}
