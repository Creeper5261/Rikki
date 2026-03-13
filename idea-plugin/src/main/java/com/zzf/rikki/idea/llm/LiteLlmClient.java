package com.zzf.rikki.idea.llm;

import com.zzf.rikki.idea.completion.CompletionClient;
import com.zzf.rikki.idea.completion.CompletionConfigResolver;
import com.zzf.rikki.idea.settings.RikkiSettings;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

public final class LiteLlmClient implements CompletionClient {
    private static final String CHAT_SYSTEM_PROMPT = """
            You are a code completion engine. Complete the code at <|CURSOR|>.
            Rules:
            - Output ONLY the raw completion text. Nothing else.
            - No explanation. No markdown. No backticks. No prose.
            - Stop at a natural completion point (end of statement, block, or function).
            - Match the indentation and style of the surrounding code.
            """.trim();
    private static final LiteLlmClient INSTANCE = new LiteLlmClient();

    private LiteLlmClient() {
    }

    public static LiteLlmClient getInstance() {
        return INSTANCE;
    }

    public static void streamCompletion(
            String prefix,
            String suffix,
            String language,
            CompletionClient.TokenConsumer onToken
    ) throws Exception {
        RikkiSettings.State state = RikkiSettings.getInstance().getState();
        INSTANCE.streamCompletion(CompletionConfigResolver.resolve(state), prefix, suffix, language, onToken);
    }

    @Override
    public void streamCompletion(
            CompletionConfigResolver.CompletionConfig config,
            String prefix,
            String suffix,
            String language,
            TokenConsumer onToken
    ) throws Exception {
        if (!config.enabled()) {
            return;
        }
        if ((config.apiKey() == null || config.apiKey().isBlank()) && config.requiresApiKey()) {
            return;
        }
        String baseUrl = trimTrailingSlash(config.baseUrl());
        if (config.useFim()) {
            streamFim(baseUrl, config.apiKey(), config.model(), prefix, suffix, onToken);
        } else {
            streamChat(baseUrl, config.apiKey(), config.model(), prefix, suffix, language, onToken);
        }
    }

    private void streamFim(
            String baseUrl,
            String apiKey,
            String model,
            String prefix,
            String suffix,
            TokenConsumer onToken
    ) throws Exception {
        HttpURLConnection connection = openConnection(baseUrl + "/completions", apiKey);
        if (connection == null) {
            return;
        }
        try {
            connection.getOutputStream().write(buildFimBody(model, prefix, suffix).getBytes(StandardCharsets.UTF_8));
            if (connection.getResponseCode() < 200 || connection.getResponseCode() > 299) {
                return;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String data = line.substring("data:".length()).trim();
                    if (data.isBlank() || "[DONE]".equals(data)) {
                        continue;
                    }
                    String token = extractFimText(data);
                    if (token != null && !token.isEmpty()) {
                        onToken.onToken(token);
                    }
                }
            }
        } finally {
            connection.disconnect();
        }
    }

    private void streamChat(
            String baseUrl,
            String apiKey,
            String model,
            String prefix,
            String suffix,
            String language,
            TokenConsumer onToken
    ) throws Exception {
        HttpURLConnection connection = openConnection(baseUrl + "/chat/completions", apiKey);
        if (connection == null) {
            return;
        }
        try {
            connection.getOutputStream().write(buildChatBody(model, prefix, suffix, language).getBytes(StandardCharsets.UTF_8));
            if (connection.getResponseCode() < 200 || connection.getResponseCode() > 299) {
                return;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String data = line.substring("data:".length()).trim();
                    if (data.isBlank() || "[DONE]".equals(data)) {
                        continue;
                    }
                    String token = extractChatContent(data);
                    if (token != null && !token.isEmpty()) {
                        onToken.onToken(token);
                    }
                }
            }
        } finally {
            connection.disconnect();
        }
    }

    public static String buildFimBody(String model, String prefix, String suffix) {
        return "{\"model\":\"" + escapeJson(model) + "\",\"stream\":true,\"max_tokens\":128,\"temperature\":0.0," +
                "\"prompt\":\"" + escapeJson(prefix) + "\",\"suffix\":\"" + escapeJson(suffix) + "\"}";
    }

    public static String extractFimText(String json) {
        String key = "\"text\":\"";
        int start = json.indexOf(key);
        if (start < 0) {
            return null;
        }
        return emptyToNull(extractEscapedString(json, start + key.length()));
    }

    public static String buildChatBody(String model, String prefix, String suffix, String language) {
        String user = "Language: " + escapeJson(language) + "\\n\\n" + escapeJson(prefix) + "<|CURSOR|>" + escapeJson(suffix);
        return "{\"model\":\"" + escapeJson(model) + "\",\"stream\":true,\"max_tokens\":128,\"temperature\":0.1," +
                "\"messages\":[{\"role\":\"system\",\"content\":\"" + escapeJson(CHAT_SYSTEM_PROMPT) + "\"}," +
                "{\"role\":\"user\",\"content\":\"" + user + "\"}]}";
    }

    public static String extractChatContent(String json) {
        if (json.contains("\"content\":null")) {
            return null;
        }
        String key = "\"content\":\"";
        int start = json.indexOf(key);
        if (start < 0) {
            return null;
        }
        return emptyToNull(extractEscapedString(json, start + key.length()));
    }

    public static String extractEscapedString(String json, int start) {
        StringBuilder builder = new StringBuilder();
        int index = start;
        while (index < json.length()) {
            char c = json.charAt(index);
            if (c == '\\' && index + 1 < json.length()) {
                char escaped = json.charAt(index + 1);
                switch (escaped) {
                    case 'n' -> builder.append('\n');
                    case 'r' -> builder.append('\r');
                    case 't' -> builder.append('\t');
                    case '"' -> builder.append('"');
                    case '\\' -> builder.append('\\');
                    default -> builder.append(c);
                }
                index += 2;
                continue;
            }
            if (c == '"') {
                break;
            }
            builder.append(c);
            index++;
        }
        return builder.toString();
    }

    private HttpURLConnection openConnection(String url, String apiKey) {
        try {
            HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            if (apiKey != null && !apiKey.isBlank()) {
                connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            }
            connection.setDoOutput(true);
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(30_000);
            return connection;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String trimTrailingSlash(String value) {
        return value == null ? "" : value.trim().replaceAll("/+$", "");
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
