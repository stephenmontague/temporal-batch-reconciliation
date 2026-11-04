package com.example.batchprocessing.basicworkflow.processing;

import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.batchprocessing.basicworkflow.dto.RetryResult;
import com.example.batchprocessing.core.store.TxnStore;

import io.temporal.activity.Activity;
import io.temporal.activity.ActivityExecutionContext;
import io.temporal.failure.ApplicationFailure;

// One attempt at retrying a file's failed rows: send the ones with a retryable error code to the vendor again, wait for
// their answers, then check. If any failed again with a retryable code, throw, and Temporal runs the next attempt after
// the backoff in ReconciliationActivityOptions.FAILED_ROW_RETRY. Each attempt picks up whatever is still failing.
public class FailedRowRetrier {

    public static final String ROWS_STILL_FAILING = "ROWS_STILL_FAILING";

    private static final Logger log = LoggerFactory.getLogger(FailedRowRetrier.class);
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(1);

    private final TxnStore store;

    public FailedRowRetrier(TxnStore store) {
        this.store = store;
    }

    public RetryResult retry(String fileId, List<String> retryableErrorCodes) {
        ActivityExecutionContext context = Activity.getExecutionContext();
        int attempt = context.getInfo().getAttempt();

        int rowsSent = store.resendRetryableFailures(fileId, retryableErrorCodes);

        if (rowsSent == 0) {
            return new RetryResult(attempt, 0);
        }

        log.info("Retry attempt {} for {}: sent {} rows with {} to the vendor again", attempt, fileId, rowsSent, retryableErrorCodes);

        waitForAnswers(fileId, context);

        int rowsStillFailing = store.countRetryableFailures(fileId, retryableErrorCodes);

        if (rowsStillFailing > 0) {
            String message = rowsStillFailing + " of " + rowsSent + " rows failed again with " + retryableErrorCodes;

            throw ApplicationFailure.newFailure(message, ROWS_STILL_FAILING);
        }

        return new RetryResult(attempt, rowsSent);
    }

    // Checks the database every second until every resent row has an answer, heartbeating so Temporal knows this attempt is alive.
    // If the vendor never answers, the attempt's Start-To-Close timeout ends it and the next attempt sends those rows again.
    private void waitForAnswers(String fileId, ActivityExecutionContext context) {
        int rowsAwaiting = store.countAwaitingRetry(fileId);

        while (rowsAwaiting > 0) {
            context.heartbeat(rowsAwaiting);

            sleep(POLL_INTERVAL);

            rowsAwaiting = store.countAwaitingRetry(fileId);
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            throw Activity.wrap(e);
        }
    }
}
