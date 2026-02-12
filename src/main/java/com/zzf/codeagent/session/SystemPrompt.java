package com.zzf.codeagent.session;

import com.zzf.codeagent.project.ProjectContext;
import com.zzf.codeagent.provider.ModelInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 系统提示词生成 (对齐 OpenCode SystemPrompt)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SystemPrompt {

    private final ProjectContext projectContext;
    private final ResourceLoader resourceLoader;

    public List<String> provider(ModelInfo model) {
        String modelId = model.getId().toLowerCase();
        String promptFile;

        if (modelId.contains("gpt-5")) {
            promptFile = "codex_header.txt";
        } else if (modelId.contains("gpt-") || modelId.contains("o1") || modelId.contains("o3")) {
            promptFile = "beast.txt";
        } else if (modelId.contains("gemini-")) {
            promptFile = "gemini.txt";
        } else if (modelId.contains("claude")) {
            promptFile = "anthropic.txt";
        } else {
            // Default to anthropic_without_todo (qwen.txt in OpenCode)
            promptFile = "qwen.txt";
        }

        String content = loadPrompt("session/" + promptFile);
        List<String> result = new ArrayList<>();
        if (content != null && !content.isEmpty()) {
            result.add(content);
        }
        return result;
    }

    public List<String> environment(ModelInfo model, String directory) {
        List<String> env = new ArrayList<>();
        env.add("You are powered by the model named " + model.getId() + ". The exact model ID is " + model.getProviderID() + "/" + model.getId());
        env.add("Here is some useful information about the environment you are running in:");
        env.add("<env>");
        env.add("  Working directory: " + (directory != null ? directory : projectContext.getDirectory()));
        env.add("  Is directory a git repo: " + (projectContext.isGit() ? "yes" : "no"));
        env.add("  Platform: " + System.getProperty("os.name"));
        env.add("  Today's date: " + LocalDate.now().format(DateTimeFormatter.ofPattern("EEE MMM dd yyyy")));
        env.add("</env>");
        env.add("Do not output the contents of the <env> block in your response. It is for your information only.");
        env.add("<files>");
        // TODO: Ripgrep tree if needed (aligned with OpenCode environment function)
        env.add("</files>");
        
        return List.of(String.join("\n", env));
    }

    private String loadPrompt(String path) {
        try {
            Resource resource = resourceLoader.getResource("classpath:prompts/" + path);
            if (resource.exists()) {
                return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
            } else {
                log.warn("Prompt resource not found: {}", path);
            }
        } catch (IOException e) {
            log.error("Failed to load prompt resource: {}", path, e);
        }
        return null;
    }
}
