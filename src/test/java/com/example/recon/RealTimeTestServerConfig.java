package com.example.recon;

import io.temporal.spring.boot.TemporalOptionsCustomizer;
import io.temporal.testing.TestEnvironmentOptions;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration
class RealTimeTestServerConfig {
    @Bean
    TemporalOptionsCustomizer<TestEnvironmentOptions.Builder> noTimeSkipping() {
        return builder -> builder.setUseTimeskipping(false);
    }
}
