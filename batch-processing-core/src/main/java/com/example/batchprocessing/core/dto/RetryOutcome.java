package com.example.batchprocessing.core.dto;

// How retrying the file's failed rows went:
//   NOT_NEEDED  no row failed with a retryable error code (or no codes are retryable)
//   SUCCEEDED   every retried row got through; attempts is how many tries it took
//   GAVE_UP     some rows still failed after the last attempt; they stay FAILED in the report
public record RetryOutcome(String status, int attempts) {

    public static final String NOT_NEEDED = "NOT_NEEDED";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String GAVE_UP = "GAVE_UP";

    public static RetryOutcome notNeeded() {
        return new RetryOutcome(NOT_NEEDED, 0);
    }
}
