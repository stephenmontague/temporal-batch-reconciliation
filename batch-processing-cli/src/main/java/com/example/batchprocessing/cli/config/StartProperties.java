package com.example.batchprocessing.cli.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

// Defaults for the start and intake options
@ConfigurationProperties(prefix = "batch-processing.start")
public class StartProperties {

    private int batchSize = 1000;
    private int maxParallel = 10;
    private long deadlineSeconds = 7200;
    private List<String> retryCodes = List.of("TIMEOUT");

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getMaxParallel() {
        return maxParallel;
    }

    public void setMaxParallel(int maxParallel) {
        this.maxParallel = maxParallel;
    }

    public long getDeadlineSeconds() {
        return deadlineSeconds;
    }

    public void setDeadlineSeconds(long deadlineSeconds) {
        this.deadlineSeconds = deadlineSeconds;
    }

    public List<String> getRetryCodes() {
        return retryCodes;
    }

    public void setRetryCodes(List<String> retryCodes) {
        this.retryCodes = retryCodes;
    }
}
