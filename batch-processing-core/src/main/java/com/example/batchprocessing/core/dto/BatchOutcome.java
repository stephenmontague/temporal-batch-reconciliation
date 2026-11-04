package com.example.batchprocessing.core.dto;

// What the batch steps of BasicWorkflow add up to: rows invalid, pending, sent and timed out.
// One outcome for the whole file.
public record BatchOutcome(int rowsInvalid, int rowsPending, int rowsSubmitted, boolean allAnsweredBeforeDeadline, int rowsTimedOut) {
}
