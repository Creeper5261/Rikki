package com.zzf.rikki.core.tools;

import java.nio.file.Path;
import java.util.function.Consumer;

public class FileSystemToolService {
    public FileSystemToolService() {}
    public FileSystemToolService(Path root) {}

    public void setFileChangeListener(Consumer<Path> listener) {
    }
}
