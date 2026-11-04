package com.example.recon.config;

public class WorkerProperties {
    private int maxConcurrentActivities = 20;

    public int getMaxConcurrentActivities() {
        return maxConcurrentActivities;
    }

    public void setMaxConcurrentActivities(int maxConcurrentActivities) {
        this.maxConcurrentActivities = maxConcurrentActivities;
    }
}
