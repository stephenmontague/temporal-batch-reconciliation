package com.example.recon.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import com.example.recon.service.FileIntake;

import io.temporal.client.WorkflowClient;

@Configuration
@Profile({ "intake", "start" })
public class IntakeConfig {

    @Bean
    public FileIntake fileIntake(WorkflowClient client, ReconProperties props) {
        return new FileIntake(client, props.getFileTaskQueue(), props.getChunkTaskQueue());
    }
}
