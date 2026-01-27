package com.zzf.codeagent.core.rag.pipeline;

import java.nio.file.Path;

public interface CodeIngestionPipeline {
    void ingest(Path repoRoot);
}
