package com.example.batchprocessing.basicworkflow.activity;

import java.util.List;

import com.example.batchprocessing.basicworkflow.dto.BatchRequest;
import com.example.batchprocessing.basicworkflow.dto.RetryResult;
import com.example.batchprocessing.basicworkflow.dto.SeedResult;
import com.example.batchprocessing.core.dto.ReportSummary;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

// The only activities in the project, registered by basic-workflow-worker.
//
// counterKey names the pending counter that tracks a set of rows until the vendor answers them. BasicWorkflow uses the file ID.
//
// Each activity type is pinned to a constant, so ReconciliationActivityOptions can key its per-activity options by them.
@ActivityInterface
public interface ReconciliationActivities {

    String COUNT_ROWS = "CountRows";
    String SEED_BATCH = "SeedBatch";
    String START_COUNTER = "StartCounter";
    String SUBMIT_BATCH = "SubmitBatch";
    String GET_PENDING = "GetPending";
    String MARK_TIMEOUTS = "MarkTimeouts";
    String RETRY_FAILED_ROWS = "RetryFailedRows";
    String WRITE_REPORT = "WriteReport";

    // How many data rows are in the file?
    @ActivityMethod(name = COUNT_ROWS)
    int countRows(String fileRef);

    // Parse, validate, and insert one batch of rows, tagged with counterKey
    @ActivityMethod(name = SEED_BATCH)
    SeedResult seedBatch(BatchRequest batch, String counterKey);

    // Create the pending counter from the rows tagged with counterKey, and remember which workflow to signal at zero
    @ActivityMethod(name = START_COUNTER)
    int startCounter(String counterKey);

    // Queue one batch's rows for the vendor
    @ActivityMethod(name = SUBMIT_BATCH)
    int submitBatch(BatchRequest batch);

    // How many rows under counterKey are still waiting on the vendor?
    @ActivityMethod(name = GET_PENDING)
    int getPending(String counterKey);

    // After the deadline: every unanswered row under counterKey becomes TIMED_OUT
    @ActivityMethod(name = MARK_TIMEOUTS)
    int markTimeouts(String counterKey);

    // Send rows that failed with a retryable error code to the vendor again and wait for the answers.
    // Throws if any fail again, so Temporal retries it with backoff (see ReconciliationActivityOptions.FAILED_ROW_RETRY).
    @ActivityMethod(name = RETRY_FAILED_ROWS)
    RetryResult retryFailedRows(String fileId, List<String> retryableErrorCodes);

    // Per-row CSV report for the whole file
    @ActivityMethod(name = WRITE_REPORT)
    ReportSummary writeReport(String fileId);
}
