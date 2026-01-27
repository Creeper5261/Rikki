package com.zzf.codeagent.service;

import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public final class ContextService {
    @Value("${deepseek.api-key:}")
    private String deepSeekApiKey;

    @Value("${deepseek.base-url:https://api.deepseek.com/v1}")
    private String deepSeekBaseUrl;

    @Value("${deepseek.model-name:deepseek-chat}")
    private String deepSeekModelName;

    @Value("${deepseek.fast-base-url:}")
    private String deepSeekFastBaseUrl;

    @Value("${deepseek.fast-model-name:}")
    private String deepSeekFastModelName;

    @Value("${codeagent.compose-file:}")
    private String composeFile;

    public Map<String, Object> dockerHealth() {
        Map<String, Object> d = new HashMap<String, Object>();
        boolean dockerOk = canRun(new String[]{"docker", "--version"});
        d.put("docker_cli", dockerOk);
        if (!dockerOk) {
            return d;
        }
        boolean engineOk = canRun(new String[]{"docker", "info"});
        d.put("docker_engine", engineOk);
        if (!engineOk) {
            d.put("docker_info", runCapture(new String[]{"docker", "info"}));
        }
        String ctx = runCapture(new String[]{"docker", "context", "show"});
        if (ctx != null && !ctx.trim().isEmpty()) {
            d.put("docker_context", ctx.trim());
        }
        String compose = canRun(new String[]{"docker", "compose", "version"}) ? "docker compose" : (canRun(new String[]{"docker-compose", "--version"}) ? "docker-compose" : null);
        d.put("compose", compose);
        if (compose == null) {
            return d;
        }
        Path composePath = resolveComposePath();
        if (composePath == null) {
            d.put("ps", "compose file not found");
            return d;
        }
        String ps = runCapture(compose.equals("docker-compose")
                ? new String[]{"docker-compose", "-f", composePath.toString(), "ps"}
                : new String[]{"docker", "compose", "-f", composePath.toString(), "ps"});
        d.put("ps", ps);
        return d;
    }

    public Map<String, Object> deepSeekHealth() {
        Map<String, Object> d = new HashMap<String, Object>();
        String apiKey = resolveDeepSeekApiKey();
        d.put("api_key_present", apiKey != null && apiKey.trim().length() > 0);
        if (apiKey == null || apiKey.trim().isEmpty()) {
            d.put("reachable", false);
            return d;
        }
        try {
            OpenAiChatModel model = createHealthModel(apiKey);
            String resp = model.chat("ping");
            d.put("reachable", resp != null && resp.trim().length() > 0);
        } catch (Exception e) {
            d.put("reachable", false);
            d.put("error", e.getMessage());
        }
        return d;
    }

    public String resolveDeepSeekApiKey() {
        String fromYml = deepSeekApiKey;
        if (fromYml != null && fromYml.trim().length() > 0) {
            return fromYml.trim();
        }
        String fromEnv = System.getenv("DEEPSEEK_API_KEY");
        if (fromEnv != null && fromEnv.trim().length() > 0) {
            return fromEnv.trim();
        }
        return null;
    }

    public OpenAiChatModel createChatModel(String apiKey) {
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(deepSeekBaseUrl)
                .modelName(deepSeekModelName)
                .temperature(0.0)
                .timeout(Duration.ofSeconds(120))
                .build();
    }

    public OpenAiChatModel createFastChatModel(String apiKey) {
        String baseUrl = (deepSeekFastBaseUrl == null || deepSeekFastBaseUrl.trim().isEmpty())
                ? deepSeekBaseUrl
                : deepSeekFastBaseUrl;
        String modelName = (deepSeekFastModelName == null || deepSeekFastModelName.trim().isEmpty())
                ? deepSeekModelName
                : deepSeekFastModelName;
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .temperature(0.0)
                .timeout(Duration.ofSeconds(60))
                .build();
    }

    public OpenAiChatModel createHealthModel(String apiKey) {
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(deepSeekBaseUrl)
                .modelName(deepSeekModelName)
                .timeout(Duration.ofSeconds(30))
                .build();
    }

    public String fixMojibakeIfNeeded(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        int suspicious = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= 0x00C0 && ch <= 0x00FF) {
                suspicious++;
            }
        }
        if (suspicious < 8) {
            return s;
        }
        byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        String fixed = new String(bytes, StandardCharsets.UTF_8);
        if (countCjk(fixed) > countCjk(s)) {
            return fixed;
        }
        return s;
    }

    public String rootMessage(Throwable t) {
        Throwable cur = t;
        for (int i = 0; i < 10 && cur.getCause() != null; i++) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        if (msg == null || msg.trim().isEmpty()) {
            msg = cur.getClass().getSimpleName();
        }
        msg = msg.replace("\r", " ").replace("\n", " ").trim();
        return msg.length() <= 300 ? msg : msg.substring(0, 300);
    }

    public String truncate(String s, int maxChars) {
        if (s == null) {
            return "";
        }
        return s.length() <= maxChars ? s : s.substring(0, maxChars);
    }

    private Path resolveComposePath() {
        if (composeFile != null && !composeFile.trim().isEmpty()) {
            Path p = Paths.get(composeFile.trim()).toAbsolutePath().normalize();
            if (Files.exists(p)) {
                return p;
            }
        }
        Path cwd = Paths.get(System.getProperty("user.dir"));
        Path local = cwd.resolve("docker-compose.yml").normalize();
        if (Files.exists(local)) {
            return local;
        }
        Path parent = cwd.resolve("../docker-compose.yml").normalize();
        if (Files.exists(parent)) {
            return parent;
        }
        return null;
    }

    private static int countCjk(String s) {
        if (s == null) {
            return 0;
        }
        int c = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if ((ch >= 0x4E00 && ch <= 0x9FFF) || (ch >= 0x3400 && ch <= 0x4DBF)) {
                c++;
            }
        }
        return c;
    }

    private boolean canRun(String[] cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            Process p = pb.start();
            boolean ok = p.waitFor(5, TimeUnit.SECONDS);
            if (!ok) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private String runCapture(String[] cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            InputStream in = p.getInputStream();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                baos.write(buf, 0, n);
                if (baos.size() > 64 * 1024) {
                    break;
                }
            }
            p.waitFor(5, TimeUnit.SECONDS);
            return baos.toString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            return String.valueOf(e.getMessage());
        }
    }
}
