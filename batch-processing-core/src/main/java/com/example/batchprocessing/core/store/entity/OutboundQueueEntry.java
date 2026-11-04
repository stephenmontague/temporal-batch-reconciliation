package com.example.batchprocessing.core.store.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(name = "outbound_queue", indexes = @Index(name = "idx_outbound_status", columnList = "status"))
public class OutboundQueueEntry {

    public static final String QUEUED = "QUEUED";
    public static final String SENT = "SENT";

    @Id
    private String txnId;
    private String fileId;
    private String status;

    protected OutboundQueueEntry() {
    }

    public OutboundQueueEntry(String txnId, String fileId) {
        this.txnId = txnId;
        this.fileId = fileId;
        this.status = QUEUED;
    }

    public String getTxnId() {
        return txnId;
    }

    public String getFileId() {
        return fileId;
    }

    public void markSent() {
        this.status = SENT;
    }

    // Puts a row that was already sent back on the queue, to send it again
    public void requeue() {
        this.status = QUEUED;
    }
}
