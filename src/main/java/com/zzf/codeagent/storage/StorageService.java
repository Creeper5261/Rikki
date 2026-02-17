package com.zzf.codeagent.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzf.codeagent.project.ProjectContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Storage 系统 (对齐 opencode/src/storage)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StorageService {

    private final ProjectContext projectContext;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, ReadWriteLock> locks = new ConcurrentHashMap<>();

    private Path getStorageDir() {
        return Paths.get(projectContext.getDirectory(), ".code-agent", "storage");
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(getStorageDir());
            runMigrations();
        } catch (Exception e) {
            log.error("Failed to initialize storage directory", e);
        }
    }

    private interface Migration {
        void run(Path dir) throws Exception;
    }

    private void runMigrations() {
        List<Migration> migrations = new ArrayList<>();
        
        
        
        migrations.add(dir -> {
            Path legacyProjectDir = dir.getParent().resolve("project");
            if (!Files.exists(legacyProjectDir) || !Files.isDirectory(legacyProjectDir)) {
                return;
            }
            
            
            
            log.info("Found legacy project directory, starting migration...");
            
        });

        
        List<String> versionKey = List.of("meta", "version");
        Integer currentVersion = read(versionKey, Integer.class);
        if (currentVersion == null) currentVersion = 0;

        for (int i = currentVersion; i < migrations.size(); i++) {
            try {
                log.info("Running storage migration {}...", i + 1);
                migrations.get(i).run(getStorageDir());
                write(versionKey, i + 1);
            } catch (Exception e) {
                log.error("Migration {} failed", i + 1, e);
                break;
            }
        }
        log.info("Storage migrations check completed. Current version: {}", migrations.size());
    }

    private ReadWriteLock getLock(String path) {
        return locks.computeIfAbsent(path, k -> new ReentrantReadWriteLock());
    }

    public <T> T read(List<String> key, Class<T> clazz) {
        Path target = getTargetPath(key);
        if (!Files.exists(target)) {
            return null;
        }

        ReadWriteLock lock = getLock(target.toString());
        lock.readLock().lock();
        try {
            return objectMapper.readValue(target.toFile(), clazz);
        } catch (Exception e) {
            log.error("Failed to read from storage: {}", target, e);
            return null;
        } finally {
            lock.readLock().unlock();
        }
    }

    public <T> void write(List<String> key, T content) {
        Path target = getTargetPath(key);
        ReadWriteLock lock = getLock(target.toString());
        lock.writeLock().lock();
        try {
            Files.createDirectories(target.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), content);
        } catch (Exception e) {
            log.error("Failed to write to storage: {}", target, e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public <T> void update(List<String> key, Class<T> clazz, Consumer<T> updater) {
        Path target = getTargetPath(key);
        ReadWriteLock lock = getLock(target.toString());
        lock.writeLock().lock();
        try {
            T content = null;
            if (Files.exists(target)) {
                content = objectMapper.readValue(target.toFile(), clazz);
            }
            if (content != null) {
                updater.accept(content);
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), content);
            }
        } catch (Exception e) {
            log.error("Failed to update storage: {}", target, e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void remove(List<String> key) {
        Path target = getTargetPath(key);
        ReadWriteLock lock = getLock(target.toString());
        lock.writeLock().lock();
        try {
            Files.deleteIfExists(target);
        } catch (Exception e) {
            log.error("Failed to remove from storage: {}", target, e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<List<String>> list(List<String> prefix) {
        Path root = getStorageDir();
        for (String p : prefix) {
            root = root.resolve(p);
        }

        if (!Files.exists(root) || !Files.isDirectory(root)) {
            return new ArrayList<>();
        }

        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".json"))
                    .map(p -> {
                        Path relative = getStorageDir().relativize(p);
                        List<String> key = new ArrayList<>();
                        for (int i = 0; i < relative.getNameCount(); i++) {
                            String name = relative.getName(i).toString();
                            if (i == relative.getNameCount() - 1) {
                                name = name.substring(0, name.length() - 5);
                            }
                            key.add(name);
                        }
                        return key;
                    })
                    .sorted((a, b) -> String.join("/", a).compareTo(String.join("/", b)))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Failed to list storage: {}", root, e);
            return new ArrayList<>();
        }
    }

    private Path getTargetPath(List<String> key) {
        Path path = getStorageDir();
        for (int i = 0; i < key.size(); i++) {
            String part = key.get(i);
            if (i == key.size() - 1) {
                part += ".json";
            }
            path = path.resolve(part);
        }
        return path;
    }
}
