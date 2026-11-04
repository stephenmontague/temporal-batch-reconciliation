package com.example.batchprocessing.core.store.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(name = "txn", indexes = {
        @Index(name = "idx_txn_file_line", columnList = "fileId, lineNo"),
        @Index(name = "idx_txn_file_state", columnList = "fileId, state"),
        @Index(name = "idx_txn_counter_state", columnList = "counterKey, state")
})
public class TxnRecord {

    @Id
    private String txnId;
    private String fileId;
    private String counterKey;
    private int lineNo;
    private String accountId;
    private Long amountCents;
    private String currency;
    private String txnDate;
    private String merchant;
    private String state;
    private String errorCode;

    protected TxnRecord() {
    }

    public TxnRecord(
            String txnId,
            String fileId,
            String counterKey,
            int lineNo,
            String accountId,
            Long amountCents,
            String currency,
            String txnDate,
            String merchant,
            String state,
            String errorCode) {
        this.txnId = txnId;
        this.fileId = fileId;
        this.counterKey = counterKey;
        this.lineNo = lineNo;
        this.accountId = accountId;
        this.amountCents = amountCents;
        this.currency = currency;
        this.txnDate = txnDate;
        this.merchant = merchant;
        this.state = state;
        this.errorCode = errorCode;
    }

    public String getTxnId() {
        return txnId;
    }

    public String getCounterKey() {
        return counterKey;
    }

    public int getLineNo() {
        return lineNo;
    }

    public String getState() {
        return state;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void moveTo(String state, String errorCode) {
        this.state = state;
        this.errorCode = errorCode;
    }
}
