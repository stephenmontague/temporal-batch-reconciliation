package com.example.batchprocessing.core.store;

import java.util.List;

// A row's life: SEEDED (or INVALID) -> SUBMITTED -> COMPLETED | FAILED | TIMED_OUT
// A FAILED row with a retryable error code goes FAILED -> RETRYING -> COMPLETED | FAILED, once per retry attempt.
public final class TxnState {

    public static final String SEEDED = "SEEDED";
    public static final String INVALID = "INVALID";
    public static final String SUBMITTED = "SUBMITTED";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";
    public static final String TIMED_OUT = "TIMED_OUT";

    // Sent to the vendor again after failing with a retryable error code, and waiting for the new answer
    public static final String RETRYING = "RETRYING";

    public static final List<String> ALL = List.of(COMPLETED, FAILED, INVALID, RETRYING, SEEDED, SUBMITTED, TIMED_OUT);

    // Rows sent to the vendor and waiting for an answer
    public static final List<String> AWAITING_ANSWER = List.of(SUBMITTED, RETRYING);

    // Rows the vendor hasn't answered yet; the deadline sweep moves these to TIMED_OUT
    public static final List<String> UNANSWERED = List.of(SEEDED, SUBMITTED, RETRYING);

    // Rows that carry an error code: rejected by validation or by the vendor
    public static final List<String> WITH_ERROR_CODE = List.of(FAILED, INVALID);

    private TxnState() {}
}
