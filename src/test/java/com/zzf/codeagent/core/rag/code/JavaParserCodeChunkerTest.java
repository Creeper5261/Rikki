package com.zzf.codeagent.core.rag.code;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class JavaParserCodeChunkerTest {

    @Test
    public void testChunkParsesClassConstructorAndMethod() {
        String source = "package com.demo;\n" +
                "public class Sample {\n" +
                "  public Sample() {}\n" +
                "  public int sum(int a, String b) { return a; }\n" +
                "}\n";
        JavaParserCodeChunker chunker = new JavaParserCodeChunker();

        List<CodeChunk> chunks = chunker.chunk(Path.of("src/Sample.java"), source);

        assertEquals(3, chunks.size());
        assertTrue(chunks.stream().anyMatch(c -> c.getSymbolKind().equals("class") && c.getSymbolName().equals("com.demo.Sample")));
        assertTrue(chunks.stream().anyMatch(c -> c.getSymbolKind().equals("ctor") && c.getSignature().equals("Sample()")));
        assertTrue(chunks.stream().anyMatch(c -> c.getSymbolKind().equals("method") && c.getSignature().equals("int sum(int, String)")));
    }
}
