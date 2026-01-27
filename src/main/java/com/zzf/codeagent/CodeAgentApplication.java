package com.zzf.codeagent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.net.URI;

@SpringBootApplication
public class CodeAgentApplication {
    private static final Logger logger = LoggerFactory.getLogger(CodeAgentApplication.class);

    public static void main(String[] args) {
        guardRuntimeClasses();
        SpringApplication.run(CodeAgentApplication.class, args);
    }

    private static void guardRuntimeClasses() {
        try {
            URI location = CodeAgentApplication.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            String path = location == null ? "" : location.getPath();
            String normalized = path.replace('\\', '/');
            if (normalized.contains("/out/production/")) {
                logger.error("runtime.classpath.invalid location={}", path);
                throw new IllegalStateException("Detected IDE out/production classes on classpath. Please run via Gradle (bootRun) or use build/classes output.");
            }
        } catch (Exception e) {
            logger.warn("runtime.classpath.check.failed err={}", e.toString());
        }
    }
}
