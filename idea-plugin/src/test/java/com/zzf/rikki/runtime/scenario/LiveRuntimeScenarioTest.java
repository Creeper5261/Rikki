package com.zzf.rikki.runtime.scenario;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;

import java.util.stream.Stream;

@Tag("live-runtime")
public class LiveRuntimeScenarioTest {
    private final RuntimeScenarioRunner runner = new RuntimeScenarioRunner();

    @TestFactory
    Stream<DynamicTest> liveScenarios() throws Exception {
        return runner.scenarioFiles("live").stream()
                .map(path -> DynamicTest.dynamicTest(path.getFileName().toString(), () -> {
                    RuntimeScenarioSpec spec = runner.load(path);
                    Assumptions.assumeTrue("1".equals(System.getenv("RIKKI_LIVE_AGENT_TESTS")), "RIKKI_LIVE_AGENT_TESTS is not enabled");
                    Assumptions.assumeTrue(
                            spec.config.apiKeyEnv != null
                                    && !spec.config.apiKeyEnv.isBlank()
                                    && System.getenv(spec.config.apiKeyEnv) != null
                                    && !System.getenv(spec.config.apiKeyEnv).isBlank(),
                            "Missing API key env for live scenario"
                    );
                    runner.run(spec);
                }));
    }
}
