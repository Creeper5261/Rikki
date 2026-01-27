package com.zzf.codeagent.core.skill;

public class Skill {
    private String name;
    private String description;
    private String content;
    private String baseDir;

    public Skill(String name, String description, String content, String baseDir) {
        this.name = name;
        this.description = description;
        this.content = content;
        this.baseDir = baseDir;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getBaseDir() {
        return baseDir;
    }

    public void setBaseDir(String baseDir) {
        this.baseDir = baseDir;
    }
}
