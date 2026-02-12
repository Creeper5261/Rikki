package com.zzf.codeagent.core.tools;

import org.springframework.stereotype.Service;
import java.nio.file.Path;
import java.util.function.Consumer;

@Service
public class FileSystemToolService {
    public FileSystemToolService() {}
    public FileSystemToolService(Path root) {}

    public void setFileChangeListener(Consumer<Path> listener) {
        // Compatibility shell
    }
}
