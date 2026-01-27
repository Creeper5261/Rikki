package com.zzf.codeagent.infrastructure;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ApplicationDevYamlUpdater {
    private final Path devYamlPath;

    public ApplicationDevYamlUpdater(Path devYamlPath) {
        this.devYamlPath = devYamlPath;
    }

    public void ensureDevConfig() {
        try {
            Files.createDirectories(devYamlPath.getParent());
            String yaml = ""
                    + "spring:\n"
                    + "  kafka:\n"
                    + "    bootstrap-servers: localhost:9092\n"
                    + "elasticsearch:\n"
                    + "  host: localhost\n"
                    + "  port: 9200\n"
                    + "  scheme: http\n"
                    + "codeagent:\n"
                    + "  redis:\n"
                    + "    host: localhost\n"
                    + "    port: 6379\n";
            Files.writeString(devYamlPath, yaml, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
