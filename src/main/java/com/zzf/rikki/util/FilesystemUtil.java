package com.zzf.rikki.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 文件系统工具 (对齐 OpenCode Filesystem)
 */
@Slf4j
@Component
public class FilesystemUtil {

    public boolean exists(String path) {
        return Files.exists(Paths.get(path));
    }

    public List<String> findUp(String target, String start, String stop) {
        List<String> result = new ArrayList<>();
        Path current = Paths.get(start);
        Path stopPath = stop != null ? Paths.get(stop) : null;

        while (true) {
            Path search = current.resolve(target);
            if (Files.exists(search)) {
                result.add(search.toAbsolutePath().toString());
            }

            if (current.equals(stopPath)) break;
            Path parent = current.getParent();
            if (parent == null || parent.equals(current)) break;
            current = parent;
        }
        return result;
    }

    public List<String> globUp(String pattern, String start, String stop) {
        List<String> result = new ArrayList<>();
        Path current = Paths.get(start);
        Path stopPath = stop != null ? Paths.get(stop) : null;

        while (true) {
            result.addAll(glob(current.toString(), pattern));

            if (current.equals(stopPath)) break;
            Path parent = current.getParent();
            if (parent == null || parent.equals(current)) break;
            current = parent;
        }
        return result;
    }

    public List<String> glob(String dir, String pattern) {
        List<String> result = new ArrayList<>();
        try {
            String globPattern = "glob:" + pattern;
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher(globPattern);

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(Paths.get(dir))) {
                for (Path entry : stream) {
                    if (matcher.matches(entry.getFileName())) {
                        result.add(entry.toAbsolutePath().toString());
                    }
                }
            }
        } catch (IOException e) {
            
        }
        return result;
    }
}
