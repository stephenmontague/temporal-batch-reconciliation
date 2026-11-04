package com.example.batchprocessing.core.dto;

// What BasicWorkflow returns for a file.
public record FileResult(
        String fileId,
        String outcome,
        int totalRows,
        int rowsPending,
        int rowsInvalid,
        int rowsSubmitted,
        boolean allDoneBeforeDeadline,
        int rowsTimedOut,
        RetryOutcome retry,
        ReportSummary report) {

    public static final String COMPLETED = "COMPLETED";
    public static final String TIMED_OUT = "TIMED_OUT";

    // COMPLETED if the deadline sweep found nothing, otherwise TIMED_OUT
    public static FileResult of(String fileId, int totalRows, BatchOutcome batches, RetryOutcome retry, ReportSummary report) {
        String outcome = batches.rowsTimedOut() == 0 ? COMPLETED : TIMED_OUT;

        return new FileResult(
                fileId,
                outcome,
                totalRows,
                batches.rowsPending(),
                batches.rowsInvalid(),
                batches.rowsSubmitted(),
                batches.allAnsweredBeforeDeadline(),
                batches.rowsTimedOut(),
                retry,
                report);
    }
}
