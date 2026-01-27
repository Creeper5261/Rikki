package com.zzf.codeagent.core.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

@Service
public class SkillManager {

    private static final Logger log = LoggerFactory.getLogger(SkillManager.class);
    private static final String[] SKILL_DIRS = {".agent/skills", ".claude/skills"};

    private final ResourcePatternResolver resourceResolver;

    public SkillManager(ResourcePatternResolver resourceResolver) {
        this.resourceResolver = resourceResolver;
    }

    public List<Skill> listSkills(String workspaceRoot) {
        // Map to handle deduplication (User skills override Built-in skills)
        Map<String, Skill> skillMap = new ConcurrentHashMap<>();

        // 1. Load Built-in Skills from ClassPath
        loadBuiltInSkills().forEach(s -> skillMap.put(s.getName(), s));

        // 2. Load User Skills from Workspace (overrides built-in)
        if (workspaceRoot != null && !workspaceRoot.isEmpty()) {
            loadUserSkills(workspaceRoot).forEach(s -> skillMap.put(s.getName(), s));
        }

        return new ArrayList<>(skillMap.values());
    }

    public Skill loadSkill(String workspaceRoot, String skillName) {
        // In the future we might want to cache this or index it
        List<Skill> allSkills = listSkills(workspaceRoot);
        return allSkills.stream()
                .filter(s -> s.getName().equals(skillName))
                .findFirst()
                .orElse(null);
    }

    private List<Skill> loadBuiltInSkills() {
        List<Skill> skills = new ArrayList<>();
        try {
            // Look for SKILL.md in classpath under skills directory
            Resource[] resources = resourceResolver.getResources("classpath*:skills/**/SKILL.md");
            for (Resource resource : resources) {
                try (InputStream is = resource.getInputStream()) {
                    // Try to infer name from parent directory name if possible
                    String filename = resource.getFilename();
                    String path = resource.getURI().toString();
                    String parentName = "unknown";
                    if (path.contains("/")) {
                        String[] parts = path.split("/");
                        if (parts.length > 1) {
                            parentName = parts[parts.length - 2];
                        }
                    }
                    
                    Skill skill = parseSkillStream(is, parentName, path);
                    if (skill != null) {
                        skills.add(skill);
                    }
                } catch (Exception e) {
                    log.error("Error loading built-in skill: " + resource, e);
                }
            }
        } catch (IOException e) {
            log.error("Error scanning built-in skills", e);
        }
        return skills;
    }

    private List<Skill> loadUserSkills(String workspaceRoot) {
        List<Skill> skills = new ArrayList<>();
        for (String dirName : SKILL_DIRS) {
            Path skillsPath = Paths.get(workspaceRoot, dirName).normalize();

            // Security Check: Ensure we are still inside workspace (basic path traversal protection)
            if (!skillsPath.startsWith(Paths.get(workspaceRoot).normalize())) {
                log.warn("Security alert: Skill path traversal attempt detected. Path: {}", skillsPath);
                continue;
            }

            if (Files.exists(skillsPath) && Files.isDirectory(skillsPath)) {
                try (Stream<Path> paths = Files.walk(skillsPath)) {
                    paths.filter(Files::isRegularFile)
                            .filter(p -> p.getFileName().toString().equals("SKILL.md"))
                            .forEach(p -> {
                                try (InputStream is = new FileInputStream(p.toFile())) {
                                    Skill skill = parseSkillStream(is, p.getParent().getFileName().toString(), p.getParent().toString());
                                    if (skill != null) {
                                        skills.add(skill);
                                    }
                                } catch (IOException e) {
                                    log.error("Error parsing user skill file: " + p, e);
                                }
                            });
                } catch (IOException e) {
                    log.error("Error walking skills directory: " + skillsPath, e);
                }
            }
        }
        return skills;
    }

    private Skill parseSkillStream(InputStream inputStream, String defaultName, String location) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            StringBuilder content = new StringBuilder();
            String name = null;
            String description = null;
            boolean inFrontmatter = false;
            int dashCount = 0;

            // Simple frontmatter parsing
            while ((line = reader.readLine()) != null) {
                if (line.trim().equals("---")) {
                    dashCount++;
                    if (dashCount == 1) {
                        inFrontmatter = true;
                        continue;
                    } else if (dashCount == 2) {
                        inFrontmatter = false;
                        continue;
                    }
                }

                if (inFrontmatter) {
                    if (line.trim().startsWith("name:")) {
                        name = line.substring(line.indexOf(':') + 1).trim();
                    } else if (line.trim().startsWith("description:")) {
                        description = line.substring(line.indexOf(':') + 1).trim();
                    }
                } else {
                    content.append(line).append("\n");
                }
            }

            if (name == null) {
                name = defaultName;
            }

            return new Skill(name, description, content.toString(), location);

        } catch (IOException e) {
            log.error("Error parsing skill stream from: " + location, e);
            return null;
        }
    }
}
