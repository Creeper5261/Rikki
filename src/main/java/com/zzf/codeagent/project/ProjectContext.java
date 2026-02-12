package com.zzf.codeagent.project;

import org.springframework.stereotype.Component;
import lombok.Data;

@Component
@Data
public class ProjectContext {
    // 默认为当前工作目录，后续可通过 API 初始化更新
    private String directory = System.getProperty("user.dir"); 
    private String worktree = System.getProperty("user.dir");

    public boolean isGit() {
        return new java.io.File(directory, ".git").exists();
    }
}
