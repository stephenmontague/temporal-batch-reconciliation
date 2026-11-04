package com.example.batchprocessing.core.config;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

// Settings every application shares. App-specific settings live in each app
// (batch-processing.start in batch-processing-cli, batch-processing.vendor in fake-vendor).
@ConfigurationProperties(prefix = "batch-processing")
public class BatchProcessingProperties {

    private String basicWorkflowTaskQueue = "batch-processing-basic-workflow";
    private Path db = Path.of("data/batch-processing.db");
    private Path reportDir = Path.of("data/reports");
    private Path inbox = Path.of("data/inbox");
    private String uiBaseUrl = "http://localhost:8233";

    public String getBasicWorkflowTaskQueue() {
        return basicWorkflowTaskQueue;
    }

    public void setBasicWorkflowTaskQueue(String basicWorkflowTaskQueue) {
        this.basicWorkflowTaskQueue = basicWorkflowTaskQueue;
    }

    public Path getDb() {
        return db;
    }

    public void setDb(Path db) {
        this.db = db;
    }

    public Path getReportDir() {
        return reportDir;
    }

    public void setReportDir(Path reportDir) {
        this.reportDir = reportDir;
    }

    public Path getInbox() {
        return inbox;
    }

    public void setInbox(Path inbox) {
        this.inbox = inbox;
    }

    public String getUiBaseUrl() {
        return uiBaseUrl;
    }

    public void setUiBaseUrl(String uiBaseUrl) {
        this.uiBaseUrl = uiBaseUrl;
    }
}
