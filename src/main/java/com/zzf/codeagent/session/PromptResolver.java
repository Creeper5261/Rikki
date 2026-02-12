package com.zzf.codeagent.session;

import com.zzf.codeagent.session.model.MessageV2;
import com.zzf.codeagent.session.model.PromptPart;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prompt 解析器 (对齐 OpenCode SessionPrompt.resolvePromptParts)
 */
@Slf4j
@Service
public class PromptResolver {

    // 匹配 @path/to/file 或 @~/path/to/file
    private static final Pattern FILE_REF_PATTERN = Pattern.compile("@([~/a-zA-Z0-9._/\\\\-]+)");

    /**
     * 将模板字符串解析为 PromptPart 列表
     * 对齐 OpenCode 逻辑：
     * 1. 初始为 TextPart
     * 2. 扫描其中的文件引用 (@file)
     * 3. 如果是目录 -> FilePart (mime: directory)
     * 4. 如果是文件 -> FilePart (mime: text/plain)
     * 5. 如果不存在但匹配 Agent 名称 -> AgentPart
     */
    public List<PromptPart> resolvePromptParts(String template, String worktree) {
        List<PromptPart> parts = new ArrayList<>();
        
        // 1. 添加原始文本
        MessageV2.TextPart textPart = new MessageV2.TextPart();
        textPart.setText(template);
        parts.add(textPart);

        // 2. 扫描文件引用
        Matcher matcher = FILE_REF_PATTERN.matcher(template);
        while (matcher.find()) {
            String name = matcher.group(1);
            Path filepath = resolvePath(name, worktree);
            
            File file = filepath.toFile();
            if (file.exists()) {
                MessageV2.FilePart filePart = new MessageV2.FilePart();
                filePart.setFilename(name);
                filePart.setUrl("file://" + filepath.toAbsolutePath().toString());
                
                if (file.isDirectory()) {
                    filePart.setMime("application/x-directory");
                } else {
                    filePart.setMime("text/plain");
                    // 注意：OpenCode 在这里不注入内容，内容注入由 processor 处理
                }
                parts.add(filePart);
            } else {
                // 如果文件不存在，尝试匹配 Agent (这里简化处理，假设所有非文件引用可能是 Agent)
                // 实际 OpenCode 会调用 Agent.get(name)
                MessageV2.AgentPart agentPart = new MessageV2.AgentPart();
                agentPart.setName(name);
                parts.add(agentPart);
            }
        }

        return parts;
    }

    private Path resolvePath(String name, String worktree) {
        if (name.startsWith("~/")) {
            String home = System.getProperty("user.home");
            return Paths.get(home, name.substring(2));
        }
        return Paths.get(worktree, name);
    }
}
