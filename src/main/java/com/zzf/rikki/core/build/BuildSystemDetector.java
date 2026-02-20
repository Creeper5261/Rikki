package com.zzf.rikki.core.build;
import java.nio.file.Path;
public class BuildSystemDetector {
    private BuildSystemDetector() {}
    public static BuildSystemDetection detect(Path workdir, String command) {
        if (workdir == null) return new BuildSystemDetection(BuildSystemType.UNKNOWN);
        if (workdir.resolve("pom.xml").toFile().exists()) return new BuildSystemDetection(BuildSystemType.MAVEN);
        if (workdir.resolve("build.gradle").toFile().exists() || workdir.resolve("build.gradle.kts").toFile().exists()) return new BuildSystemDetection(BuildSystemType.GRADLE);
        if (workdir.resolve("package.json").toFile().exists()) return new BuildSystemDetection(BuildSystemType.NPM);
        if (workdir.resolve("Cargo.toml").toFile().exists()) return new BuildSystemDetection(BuildSystemType.CARGO);
        if (workdir.resolve("go.mod").toFile().exists()) return new BuildSystemDetection(BuildSystemType.GO);
        return new BuildSystemDetection(BuildSystemType.UNKNOWN);
    }
}
