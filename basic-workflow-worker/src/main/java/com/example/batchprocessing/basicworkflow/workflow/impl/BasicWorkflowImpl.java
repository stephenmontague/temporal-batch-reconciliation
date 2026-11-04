package com.example.batchprocessing.basicworkflow.workflow.impl;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.slf4j.Logger;

import com.example.batchprocessing.basicworkflow.activity.ReconciliationActivities;
import com.example.batchprocessing.basicworkflow.activity.ReconciliationActivityOptions;
import com.example.batchprocessing.basicworkflow.dto.BatchRequest;
import com.example.batchprocessing.basicworkflow.dto.RetryResult;
import com.example.batchprocessing.basicworkflow.dto.SeedResult;
import com.example.batchprocessing.basicworkflow.workflow.BasicWorkflow;
import com.example.batchprocessing.core.dto.BatchOutcome;
import com.example.batchprocessing.core.dto.FileInput;
import com.example.batchprocessing.core.dto.FileResult;
import com.example.batchprocessing.core.dto.ReportSummary;
import com.example.batchprocessing.core.dto.RetryOutcome;

import io.temporal.failure.ActivityFailure;
import io.temporal.workflow.Async;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;

// Reconciles one file. reconcile() is the whole workflow, top to bottom.
// Each step's comment ends with how many activities it runs, in [brackets]: 6 per file, plus 2 per batch.
public class BasicWorkflowImpl implements BasicWorkflow {

    private static final Logger log = Workflow.getLogger(BasicWorkflowImpl.class);

    // Every activity call goes through this stub, so searching for "activities" finds them all.
    // Some activity types get their own timeouts and retries (see ReconciliationActivityOptions).
    private final ReconciliationActivities activities = Workflow.newActivityStub(
            ReconciliationActivities.class,
            ReconciliationActivityOptions.DEFAULT,
            ReconciliationActivityOptions.BY_ACTIVITY_TYPE);

    private boolean allDone;
    private String phase = "STARTING";

    @Override
    public FileResult reconcile(FileInput input) {
        String fileId = input.fileId();
        int batchSize = input.batchSize();
        int maxParallel = input.maxParallel();

        // The deadline counts from when this run started, so time a worker was down counts too
        long startedAtMillis = Workflow.getInfo().getRunStartedTimestampMillis();
        long deadlineAtMillis = startedAtMillis + input.deadlineSeconds() * 1000;

        // Count the rows and cut the file into batches. [1 activity: CountRows]
        phase = "COUNTING";

        int totalRows = activities.countRows(input.fileRef());

        List<BatchRequest> batches = new ArrayList<>();

        for (int offset = 0; offset < totalRows; offset += batchSize) {
            int size = Math.min(batchSize, totalRows - offset);
            batches.add(new BatchRequest(input.fileRef(), fileId, offset, size));
        }

        log.info("File {} has {} rows in {} batches", fileId, totalRows, batches.size());

        // Parse, check and save every batch, a few at a time. [1 activity per batch: SeedBatch]
        phase = "SEEDING";
        int rowsInvalid = 0;

        List<SeedResult> seedResults = runCapped(batches, maxParallel, batch -> Async.function(activities::seedBatch, batch, fileId));

        for (SeedResult seedResult : seedResults) {
            rowsInvalid += seedResult.invalid();
        }

        // Count the rows to wait for, now that every row is saved. [1 activity: StartCounter]
        phase = "COUNTING_PENDING";

        int rowsPending = activities.startCounter(fileId);

        // Put every batch on the vendor's queue, a few at a time. [1 activity per batch: SubmitBatch]
        phase = "SUBMITTING";
        int rowsSubmitted = 0;

        List<Integer> submitResults = runCapped(batches, maxParallel, batch -> Async.function(activities::submitBatch, batch));

        for (Integer rowsSent : submitResults) {
            rowsSubmitted += rowsSent;
        }

        // Wait for the allDone signal or the deadline. [1 activity: GetPending, then a timer if it waits]
        // If no row is still waiting, the signal came early or got lost, so don't wait.
        phase = "WAITING_FOR_VENDOR";
        boolean allAnsweredBeforeDeadline = true;

        int rowsStillWaiting = activities.getPending(fileId);
        boolean mustWait = rowsStillWaiting > 0 && !allDone;

        if (mustWait) {
            long millisLeft = Math.max(0, deadlineAtMillis - Workflow.currentTimeMillis());
            Duration timeLeft = Duration.ofMillis(millisLeft);

            allAnsweredBeforeDeadline = Workflow.await(timeLeft, () -> allDone);
        }

        // Rows the vendor never answered become TIMED_OUT. [1 activity: MarkTimeouts]
        phase = "SWEEPING";

        int rowsTimedOut = activities.markTimeouts(fileId);

        // Send rows that failed for a retryable reason to the vendor again. [1 activity: RetryFailedRows, 4 tries max]
        // Temporal retries it with backoff. If the last try fails, keep going: those rows stay FAILED.
        List<String> retryableErrorCodes = input.retryableErrorCodes();
        RetryOutcome retry = RetryOutcome.notNeeded();

        boolean anyCodeIsRetryable = !retryableErrorCodes.isEmpty();

        if (anyCodeIsRetryable) {
            phase = "RETRYING";

            try {
                RetryResult retryResult = activities.retryFailedRows(fileId, retryableErrorCodes);

                // Nothing sent on the first try means nothing needed retrying
                boolean rowsWereRetried = retryResult.attempt() > 1 || retryResult.rowsSent() > 0;

                if (rowsWereRetried) {
                    retry = new RetryOutcome(RetryOutcome.SUCCEEDED, retryResult.attempt());
                }
            } catch (ActivityFailure e) {
                int attempts = ReconciliationActivityOptions.FAILED_ROW_RETRY_POLICY.getMaximumAttempts();
                log.warn("Gave up retrying failed rows of {} after {} attempts: {}", fileId, attempts, e.getCause().getMessage());

                // A resent row may still be waiting for an answer. [1 more activity: MarkTimeouts]
                activities.markTimeouts(fileId);

                retry = new RetryOutcome(RetryOutcome.GAVE_UP, attempts);
            }
        }

        // Write the per-row report. [1 activity: WriteReport]
        phase = "REPORTING";

        ReportSummary report = activities.writeReport(fileId);

        BatchOutcome batchOutcome = new BatchOutcome(rowsInvalid, rowsPending, rowsSubmitted, allAnsweredBeforeDeadline, rowsTimedOut);
        FileResult result = FileResult.of(fileId, totalRows, batchOutcome, retry, report);
        phase = result.outcome();

        return result;
    }

    // The vendor's callback listener sends this when every row has an answer
    @Override
    public void allDone() {
        allDone = true;
    }

    @Override
    public String phase() {
        return phase;
    }

    // Used by the save and send steps: starts one activity per batch, never more than `cap` at once.
    // Returns the results in batch order.
    private static <T> List<T> runCapped(List<BatchRequest> batches, int cap, Function<BatchRequest, Promise<T>> start) {
        List<Promise<T>> all = new ArrayList<>();
        List<Promise<T>> inFlight = new ArrayList<>();

        for (BatchRequest batch : batches) {
            if (inFlight.size() >= cap) {
                Promise.anyOf(inFlight).get();
                inFlight.removeIf(Promise::isCompleted);
            }

            Promise<T> promise = start.apply(batch);
            all.add(promise);
            inFlight.add(promise);
        }

        Promise.allOf(all).get();

        List<T> results = new ArrayList<>();

        for (Promise<T> promise : all) {
            results.add(promise.get());
        }

        return results;
    }
}
