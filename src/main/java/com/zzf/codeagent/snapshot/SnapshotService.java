package com.zzf.codeagent.snapshot;

import com.zzf.codeagent.project.ProjectContext;
import com.zzf.codeagent.shell.ShellService;
import com.zzf.codeagent.shell.ShellService.ExecuteResult;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Snapshot 系统 (对齐 opencode/src/snapshot)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SnapshotService {

    private final ProjectContext projectContext;
    private final ShellService shellService;

    private String gitdir() {
        return Paths.get(projectContext.getDirectory(), ".code-agent", "snapshot").toString();
    }

    @Scheduled(fixedRate = 3600000) 
    public void cleanup() {
        if (!projectContext.isGit()) return;
        String gitDir = gitdir();
        if (!new File(gitDir).exists()) return;
        
        ExecuteResult result = shellService.execute(
            "git gc --prune=7.days", 
            gitDir, projectContext.getWorktree()
        );
        if (result.getExitCode() != 0) {
            log.warn("Snapshot cleanup failed: {}", result.getStderr());
            return;
        }
        log.info("Snapshot cleanup completed");
    }

    public String track() {
        if (!projectContext.isGit()) return null;
        
        String gitDir = gitdir();
        File gitDirFile = new File(gitDir);
        if (!gitDirFile.exists()) {
            gitDirFile.mkdirs();
            shellService.execute("git init", gitDir, projectContext.getWorktree());
            shellService.execute("git config core.autocrlf false", gitDir, projectContext.getWorktree());
            log.info("Snapshot initialized at {}", gitDir);
        }

        shellService.execute("git add .", gitDir, projectContext.getWorktree());
        String hash = shellService.execute("git write-tree", gitDir, projectContext.getWorktree()).text().trim();
        log.info("Tracking snapshot: {}", hash);
        return hash;
    }

    @Data
    @Builder
    public static class Patch {
        private String hash;
        private List<String> files;
    }

    public Patch patch(String hash) {
        String gitDir = gitdir();
        shellService.execute("git add .", gitDir, projectContext.getWorktree());
        
        ExecuteResult result = shellService.execute(
            "git -c core.autocrlf=false -c core.quotepath=false diff --no-ext-diff --name-only " + hash + " -- .",
            gitDir, projectContext.getWorktree()
        );

        if (result.getExitCode() != 0) {
            log.warn("Failed to get patch for hash {}: {}", hash, result.getStderr());
            return Patch.builder().hash(hash).files(new ArrayList<>()).build();
        }

        List<String> files = Arrays.stream(result.text().split("\n"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> Paths.get(projectContext.getWorktree(), s).toString())
                .collect(Collectors.toList());

        return Patch.builder().hash(hash).files(files).build();
    }

    public void restore(String snapshot) {
        log.info("Restoring snapshot: {}", snapshot);
        String gitDir = gitdir();
        ExecuteResult result = shellService.execute(
            "git read-tree " + snapshot + " && git checkout-index -a -f",
            gitDir, projectContext.getWorktree()
        );

        if (result.getExitCode() != 0) {
            log.error("Failed to restore snapshot {}: {}", snapshot, result.getStderr());
        }
    }

    public void revert(List<Patch> patches) {
        Set<String> processedFiles = new HashSet<>();
        String gitDir = gitdir();
        for (Patch item : patches) {
            for (String file : item.getFiles()) {
                if (processedFiles.contains(file)) continue;
                log.info("Reverting file {} to hash {}", file, item.getHash());
                
                ExecuteResult result = shellService.execute(
                    "git checkout " + item.getHash() + " -- " + file,
                    gitDir, projectContext.getWorktree()
                );
                
                if (result.getExitCode() != 0) {
                    String relativePath = Paths.get(projectContext.getWorktree()).relativize(Paths.get(file)).toString();
                    ExecuteResult checkTree = shellService.execute(
                        "git ls-tree " + item.getHash() + " -- " + relativePath,
                        gitDir, projectContext.getWorktree()
                    );
                    
                    if (checkTree.getExitCode() == 0 && !checkTree.text().trim().isEmpty()) {
                        log.info("File existed in snapshot but checkout failed, keeping: {}", file);
                    } else {
                        log.info("File did not exist in snapshot, deleting: {}", file);
                        try {
                            Files.deleteIfExists(Paths.get(file));
                        } catch (Exception e) {
                            log.warn("Failed to delete file {}: {}", file, e.getMessage());
                        }
                    }
                }
                processedFiles.add(file);
            }
        }
    }

    public String diff(String hash) {
        String gitDir = gitdir();
        shellService.execute("git add .", gitDir, projectContext.getWorktree());
        return shellService.execute(
            "git -c core.autocrlf=false -c core.quotepath=false diff --no-ext-diff " + hash + " -- .",
            gitDir, projectContext.getWorktree()
        ).text().trim();
    }

    public void record(FileDiff filediff) {
        
        log.info("Recording file diff for: {}", filediff.getFile());
        
    }

    @Data
    @Builder
    public static class FileDiff {
        private String file;
        private String before;
        private String after;
        private int additions;
        private int deletions;
    }

    public List<FileDiff> diffFull(String from, String to) {
        String gitDir = gitdir();
        List<FileDiff> result = new ArrayList<>();
        
        ExecuteResult numstat = shellService.execute(
            "git -c core.autocrlf=false -c core.quotepath=false diff --no-ext-diff --no-renames --numstat " + from + " " + to + " -- .",
            gitDir, projectContext.getWorktree()
        );

        if (numstat.getExitCode() != 0) {
            return result;
        }

        String[] lines = numstat.text().split("\n");
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            String[] parts = line.split("\t");
            if (parts.length < 3) continue;

            String additionsStr = parts[0];
            String deletionsStr = parts[1];
            String file = parts[2];

            boolean isBinary = additionsStr.equals("-") && deletionsStr.equals("-");
            
            String before = "";
            if (!isBinary) {
                before = shellService.execute(
                    "git -c core.autocrlf=false show " + from + ":" + file,
                    gitDir, projectContext.getWorktree()
                ).text();
            }

            String after = "";
            if (!isBinary) {
                after = shellService.execute(
                    "git -c core.autocrlf=false show " + to + ":" + file,
                    gitDir, projectContext.getWorktree()
                ).text();
            }

            int added = isBinary ? 0 : parseNum(additionsStr);
            int deleted = isBinary ? 0 : parseNum(deletionsStr);

            result.add(FileDiff.builder()
                    .file(file)
                    .before(before)
                    .after(after)
                    .additions(added)
                    .deletions(deleted)
                    .build());
        }
        return result;
    }

    private int parseNum(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
