package com.example.recon.config;

import java.util.List;

public class VendorProperties {
    private double minDelaySeconds = 1;
    private double maxDelaySeconds = 30;
    private double failureRate = 0.01;
    private List<String> errorCodes = List.of("ACCOUNT_LOCKED", "TIMEOUT");
    private long pollMillis = 500;
    private int pollBatch = 2000;

    public double getMinDelaySeconds() {
        return minDelaySeconds;
    }

    public void setMinDelaySeconds(double minDelaySeconds) {
        this.minDelaySeconds = minDelaySeconds;
    }

    public double getMaxDelaySeconds() {
        return maxDelaySeconds;
    }

    public void setMaxDelaySeconds(double maxDelaySeconds) {
        this.maxDelaySeconds = maxDelaySeconds;
    }

    public double getFailureRate() {
        return failureRate;
    }

    public void setFailureRate(double failureRate) {
        this.failureRate = failureRate;
    }

    public List<String> getErrorCodes() {
        return errorCodes;
    }

    public void setErrorCodes(List<String> errorCodes) {
        this.errorCodes = errorCodes;
    }

    public long getPollMillis() {
        return pollMillis;
    }

    public void setPollMillis(long pollMillis) {
        this.pollMillis = pollMillis;
    }

    public int getPollBatch() {
        return pollBatch;
    }

    public void setPollBatch(int pollBatch) {
        this.pollBatch = pollBatch;
    }
}
