package com.example.recon.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import com.example.recon.design.chunk.activity.ChunkActivitiesImpl;
import com.example.recon.design.file.activity.ReconciliationActivitiesImpl;
import com.example.recon.service.TxnStore;

import io.temporal.spring.boot.WorkerOptionsCustomizer;

@Configuration
@Profile("worker")
public class WorkerConfig {

    @Bean
    public ReconciliationActivitiesImpl reconciliationActivities(TxnStore store,
            ReconProperties props) {
        return new ReconciliationActivitiesImpl(store, props.getReportDir());
    }

    @Bean
    public ChunkActivitiesImpl chunkActivities(TxnStore store, ReconProperties props) {
        return new ChunkActivitiesImpl(store, props.getReportDir());
    }

    @Bean
    public WorkerOptionsCustomizer activityConcurrency(ReconProperties props) {
        return (options, workerName, taskQueue) -> options
                .setMaxConcurrentActivityExecutionSize(props.getWorker().getMaxConcurrentActivities());
    }
}
