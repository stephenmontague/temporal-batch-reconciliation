package com.example.batchprocessing.basicworkflow.activity.impl;

import java.nio.file.Path;
import java.util.List;

import com.example.batchprocessing.basicworkflow.activity.ReconciliationActivities;
import com.example.batchprocessing.basicworkflow.dto.BatchRequest;
import com.example.batchprocessing.basicworkflow.dto.RetryResult;
import com.example.batchprocessing.basicworkflow.dto.SeedResult;
import com.example.batchprocessing.basicworkflow.processing.FailedRowRetrier;
import com.example.batchprocessing.basicworkflow.processing.ReportWriter;
import com.example.batchprocessing.basicworkflow.processing.RowLoader;
import com.example.batchprocessing.core.dto.ReportSummary;
import com.example.batchprocessing.core.store.TxnStore;

import io.temporal.activity.Activity;

// The real work: file, database, vendor queue. Each method is one activity.
// If an activity throws, Temporal retries it (see ReconciliationActivityOptions), so every method is safe to run twice.
public class ReconciliationActivitiesImpl implements ReconciliationActivities {

    private final TxnStore store;
    private final RowLoader loader;
    private final FailedRowRetrier retrier;
    private final ReportWriter reports;

    public ReconciliationActivitiesImpl(TxnStore store, Path reportDir) {
        this.store = store;
        this.loader = new RowLoader(store);
        this.retrier = new FailedRowRetrier(store);
        this.reports = new ReportWriter(store, reportDir);
    }

    @Override
    public int countRows(String fileRef) {
        return loader.countRows(fileRef);
    }

    // Heartbeats a checkpoint after every 200 rows (see RowLoader); a retry resumes from the last one the server received.
    // Rows that already exist are skipped, so resuming from an older checkpoint is safe.
    @Override
    public SeedResult seedBatch(BatchRequest batch, String counterKey) {
        return loader.load(batch, counterKey);
    }

    // Saves the calling workflow's ID on the counter, so the vendor's callback listener knows whom to signal at zero
    @Override
    public int startCounter(String counterKey) {
        String workflowId = Activity.getExecutionContext().getInfo().getWorkflowId();

        return store.initCounter(counterKey, workflowId);
    }

    // Rows already queued are skipped
    @Override
    public int submitBatch(BatchRequest batch) {
        // Heartbeats once, then runs one database transaction. That's only safe while the transaction finishes
        // well inside the 30 s heartbeat timeout; it takes milliseconds for a 1,000-5,000 row batch.
        Activity.getExecutionContext().heartbeat(batch.offset());

        int fromLine = batch.offset();
        int toLine = batch.offset() + batch.size();

        return store.enqueueOutbound(batch.fileId(), fromLine, toLine);
    }

    @Override
    public int getPending(String counterKey) {
        return store.getPending(counterKey);
    }

    @Override
    public int markTimeouts(String counterKey) {
        return store.markTimeouts(counterKey);
    }

    @Override
    public RetryResult retryFailedRows(String fileId, List<String> retryableErrorCodes) {
        return retrier.retry(fileId, retryableErrorCodes);
    }

    @Override
    public ReportSummary writeReport(String fileId) {
        return reports.write(fileId);
    }
}
