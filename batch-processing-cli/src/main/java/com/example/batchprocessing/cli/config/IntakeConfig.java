package com.example.batchprocessing.cli.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import com.example.batchprocessing.cli.start.FileIntake;
import com.example.batchprocessing.core.config.BatchProcessingProperties;

import io.temporal.client.WorkflowClient;

@Configuration
@Profile({ "intake", "start" })
public class IntakeConfig {

    @Bean
    public FileIntake fileIntake(WorkflowClient client, BatchProcessingProperties props) {
        return new FileIntake(client, props);
    }
}
