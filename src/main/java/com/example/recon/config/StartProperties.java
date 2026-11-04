package com.example.recon.config;

public class StartProperties {
    private int batchSize = 1000;
    private int maxParallel = 10;
    private long deadlineSeconds = 7200;
    private String design = "file";

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

    public String getDesign() {
        return design;
    }

    public void setDesign(String design) {
        this.design = design;
    }
}
