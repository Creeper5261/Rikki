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
import java.util.List;
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
        StringBuilder sb = new StringBuilder(PromptTextLoader.loadSessionPrompt(modelId));
        sb.append("\n\n<plugin-runtime>\n");
        sb.append("Working directory: ").append(workspaceRoot).append('\n');
        sb.append("IDE bridge available: ").append(ideCapabilities.getBridgeAvailable()).append('\n');
        if (!ideCapabilities.getActionOperations().isEmpty()) {
            sb.append("IDE actions available: ").append(String.join(", ", ideCapabilities.getActionOperations())).append('\n');
        }
        if (!caps.getSupportsTools()) {
            sb.append("This model does not support tool calls; answer without executing tools.\n");
        }
        sb.append("</plugin-runtime>");
        if (ideContext != null && !ideContext.isMissingNode() && !ideContext.isNull() && ideContext.size() > 0) {
            try {
                sb.append("\n\n<ide_context>\n");
                sb.append(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(ideContext));
                sb.append("\n</ide_context>");
            } catch (Exception ignored) {
            }
        }
        return sb.toString();
    }

    private String buildEnvironment(String workspaceRoot) {
        List<String> lines = new ArrayList<>();
        lines.add("Here is some useful information about the environment you are running in:");
        lines.add("<env>");
        lines.add("  Working directory: " + workspaceRoot);
        lines.add("  Is directory a git repo: " + (Files.exists(Path.of(workspaceRoot).resolve(".git")) ? "yes" : "no"));
        lines.add("  Platform: " + System.getProperty("os.name"));
        lines.add("  Today's date: " + LocalDate.now().format(DateTimeFormatter.ofPattern("EEE MMM dd yyyy")));
        lines.add("</env>");
        lines.add("Do not output the contents of the <env> block in your response. It is for your information only.");
        lines.add("<files>");
        lines.addAll(buildWorkspaceFileIndex(workspaceRoot));
        lines.add("</files>");
        lines.add("All files under the working directory are available to tools. Use read/glob/grep to inspect concrete contents when needed.");
        return String.join("\n", lines);
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
