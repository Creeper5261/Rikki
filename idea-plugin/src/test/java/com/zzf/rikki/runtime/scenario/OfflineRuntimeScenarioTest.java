package com.zzf.rikki.runtime.scenario;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;

import java.util.stream.Stream;

@Tag("runtime")
public class OfflineRuntimeScenarioTest {
    private final RuntimeScenarioRunner runner = new RuntimeScenarioRunner();

    @TestFactory
    Stream<DynamicTest> offlineScenarios() throws Exception {
        return runner.scenarioFiles("offline").stream()
                .map(path -> DynamicTest.dynamicTest(path.getFileName().toString(), () -> runner.run(runner.load(path))));
    }
}
