package com.zzf.codeagent.core.rag.search;

import com.zzf.codeagent.core.rag.code.CodeChunk;
import com.zzf.codeagent.core.rag.index.FileNameIndexService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.util.ArrayList;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class SearchPerformanceBaselineTest {

    @Test
    public void testInMemorySearchBaselineLatency() {
        InMemoryCodeSearchService service = new InMemoryCodeSearchService();
        List<CodeChunk> chunks = new ArrayList<CodeChunk>();
        for (int i = 0; i < 500; i++) {
            String content = "class Service" + i + " { void loginUser" + i + "() {} }";
            chunks.add(new CodeChunk(
                    "id-" + i,
                    "java",
                    "src/Service" + i + ".java",
                    "class",
                    "Service" + i,
                    "Service" + i,
                    1,
                    3,
                    content
            ));
        }
        service.upsertAll(chunks);

        int runs = 30;
        long totalNs = 0L;
        for (int i = 0; i < runs; i++) {
            long t0 = System.nanoTime();
            CodeSearchResponse resp = service.search(new CodeSearchQuery("login user", 5, 200));
            totalNs += (System.nanoTime() - t0);
            assertTrue(resp.getHits().size() > 0);
        }
        double avgMs = totalNs / 1_000_000.0 / runs;
        assertTrue(avgMs < 500.0);
    }

    @Test
    public void testFileNameIndexBaselineLatency(@TempDir Path tempDir) throws Exception {
        for (int i = 0; i < 400; i++) {
            Path file = tempDir.resolve("src/Service" + i + ".java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, "class Service" + i + " {}", java.nio.charset.StandardCharsets.UTF_8);
        }

        FileNameIndexService service = new FileNameIndexService();
        String root = tempDir.toString();
        service.ensureIndexed(root);

        int runs = 30;
        long totalNs = 0L;
        for (int i = 0; i < runs; i++) {
            long t0 = System.nanoTime();
            List<String> hits = service.search(root, "Service");
            totalNs += (System.nanoTime() - t0);
            assertTrue(hits.size() > 0);
        }
        double avgMs = totalNs / 1_000_000.0 / runs;
        assertTrue(avgMs < 300.0);
    }
}
