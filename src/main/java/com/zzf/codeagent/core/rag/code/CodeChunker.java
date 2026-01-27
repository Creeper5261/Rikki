package com.zzf.codeagent.core.rag.code;

import java.nio.file.Path;
import java.util.List;

public interface CodeChunker {
    List<CodeChunk> chunk(Path filePath, String sourceCode);
}
