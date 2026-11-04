package com.example.batchprocessing.core.store.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// How many rows under one counter key are still waiting on the vendor, and which workflow to signal at zero.
// Keyed by the file ID.
@Entity
@Table(name = "pending_counter")
public class PendingCounter {

    @Id
    private String counterKey;
    private String workflowId;
    private int pending;

    protected PendingCounter() {}

    public PendingCounter(String counterKey, String workflowId, int pending) {
        this.counterKey = counterKey;
        this.workflowId = workflowId;
        this.pending = pending;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public void setWorkflowId(String workflowId) {
        this.workflowId = workflowId;
    }

    public int getPending() {
        return pending;
    }

    public void decrement(int by) {
        this.pending -= by;
    }
}
