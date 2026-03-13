package com.zzf.rikki.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzf.rikki.idea.agent.compat.ModelCapabilities;
import com.zzf.rikki.idea.agent.tools.LiteIdeTools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class SystemPrompt {
    private final ObjectMapper mapper;

    public SystemPrompt(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String build(
            String workspaceRoot,
            JsonNode ideContext,
            ModelCapabilities caps,
            LiteIdeTools.CapabilitySnapshot ideCapabilities,
            String modelId
    ) {
        List<String> sections = new ArrayList<>();
        sections.add(buildBasePrompt(workspaceRoot, ideContext, caps, ideCapabilities, modelId));
        sections.add(buildEnvironment(workspaceRoot));
        return sections.stream().filter(part -> part != null && !part.isBlank()).collect(Collectors.joining("\n\n"));
    }

    private String buildBasePrompt(
            String workspaceRoot,
            JsonNode ideContext,
            ModelCapabilities caps,
            LiteIdeTools.CapabilitySnapshot ideCapabilities,
            String modelId
    ) {
        List<String> sections = new ArrayList<>();
        String sessionPrompt = PromptTextLoader.loadSessionPrompt(modelId);
        if (!sessionPrompt.isBlank()) {
            sections.add(sessionPrompt);
        }
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("workspaceRoot", workspaceRoot);
        variables.put("bridgeAvailable", ideCapabilities.getBridgeAvailable());
        variables.put(
                "ideActionsLine",
                ideCapabilities.getActionOperations().isEmpty()
                        ? ""
                        : "IDE actions available: " + String.join(", ", ideCapabilities.getActionOperations())
        );
        variables.put(
                "toolSupportLine",
                caps.getSupportsTools()
                        ? ""
                        : "This model does not support tool calls; answer without executing tools."
        );
        variables.put("ideContextBlock", renderIdeContextBlock(ideContext));
        String runtimePrompt = PromptTextLoader.renderTemplate(
                PromptTextLoader.loadRuntimePrompt("plugin-runtime"),
                variables
        ).trim();
        if (!runtimePrompt.isBlank()) {
            sections.add(runtimePrompt);
        }
        return String.join("\n\n", sections);
    }

    private String buildEnvironment(String workspaceRoot) {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("workspaceRoot", workspaceRoot);
        variables.put("isGitRepo", Files.exists(Path.of(workspaceRoot).resolve(".git")) ? "yes" : "no");
        variables.put("platform", System.getProperty("os.name"));
        variables.put("today", LocalDate.now().format(DateTimeFormatter.ofPattern("EEE MMM dd yyyy")));
        variables.put("fileIndex", String.join("\n", buildWorkspaceFileIndex(workspaceRoot)));
        return PromptTextLoader.renderTemplate(
                PromptTextLoader.loadRuntimePrompt("environment"),
                variables
        ).trim();
    }

    private String renderIdeContextBlock(JsonNode ideContext) {
        if (ideContext == null || ideContext.isMissingNode() || ideContext.isNull() || ideContext.size() == 0) {
            return "";
        }
        try {
            return "<ide_context>\n"
                    + mapper.writerWithDefaultPrettyPrinter().writeValueAsString(ideContext)
                    + "\n</ide_context>";
        } catch (Exception ignored) {
            return "";
        }
    }

    private List<String> buildWorkspaceFileIndex(String workspaceRoot) {
        Path root;
        try {
            root = Path.of(workspaceRoot).toAbsolutePath().normalize();
        } catch (Exception ignored) {
            return List.of("  (no files indexed)");
        }
        if (!Files.exists(root) || !Files.isDirectory(root)) {
            return List.of("  (no files indexed)");
        }
        int limit = Integer.getInteger("rikki.prompt.fileListLimit", 200);
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> files = walk
                    .filter(Files::isRegularFile)
                    .filter(path -> !path.startsWith(root.resolve(".git")))
                    .sorted(Comparator.comparing(Path::toString))
                    .collect(Collectors.toList());
            if (files.isEmpty()) {
                return List.of("  (no files indexed)");
            }
            List<String> output = new ArrayList<>();
            int visibleCount = Math.min(files.size(), Math.max(limit, 0));
            for (int i = 0; i < visibleCount; i++) {
                output.add("  " + root.relativize(files.get(i)).toString().replace('\\', '/'));
            }
            if (files.size() > visibleCount) {
                output.add("  ... (+" + (files.size() - visibleCount) + " more files omitted)");
            }
            return output;
        } catch (Exception ignored) {
            return List.of("  (no files indexed)");
        }
    }
}
