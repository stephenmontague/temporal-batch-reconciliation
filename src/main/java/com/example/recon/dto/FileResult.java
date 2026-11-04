package com.example.recon.dto;

public record FileResult(String fileId, String outcome, int totalRows, int rowsPending,
        int rowsInvalid, int rowsSubmitted, boolean allDoneBeforeDeadline, int rowsTimedOut,
        ReportSummary report) {
}
