package com.example.batchprocessing.core.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import io.temporal.spring.boot.TemporalOptionsCustomizer;
import io.temporal.testing.TestEnvironmentOptions;

// The starter's embedded test server skips time by default; the end-to-end tests need a real clock for the vendor's delays
@TestConfiguration
public class RealTimeTestServerConfig {

    @Bean
    public TemporalOptionsCustomizer<TestEnvironmentOptions.Builder> noTimeSkipping() {
        return builder -> builder.setUseTimeskipping(false);
    }
}
