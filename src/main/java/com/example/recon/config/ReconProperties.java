package com.example.recon.config;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "recon")
public class ReconProperties {

    private String fileTaskQueue = "reconciliation-file";
    private String chunkTaskQueue = "reconciliation-chunk";
    private Path db = Path.of("data/recon.db");
    private Path reportDir = Path.of("data/reports");
    private Path inbox = Path.of("data/inbox");
    private String uiBaseUrl = "http://localhost:8233";
    private final StartProperties start = new StartProperties();
    private final VendorProperties vendor = new VendorProperties();
    private final WorkerProperties worker = new WorkerProperties();

    public String getFileTaskQueue() {
        return fileTaskQueue;
    }

    public void setFileTaskQueue(String fileTaskQueue) {
        this.fileTaskQueue = fileTaskQueue;
    }

    public String getChunkTaskQueue() {
        return chunkTaskQueue;
    }

    public void setChunkTaskQueue(String chunkTaskQueue) {
        this.chunkTaskQueue = chunkTaskQueue;
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

    public StartProperties getStart() {
        return start;
    }

    public VendorProperties getVendor() {
        return vendor;
    }

    public WorkerProperties getWorker() {
        return worker;
    }
}
