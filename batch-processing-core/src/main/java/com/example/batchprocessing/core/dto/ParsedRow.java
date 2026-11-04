package com.example.batchprocessing.core.dto;

// One CSV data row after parsing. errorCode is null for a valid row.
public record ParsedRow(
        String txnId,
        int lineNo,
        String accountId,
        Long amountCents,
        String currency,
        String txnDate,
        String merchant,
        String errorCode) {

    // A row too broken to read any fields from
    public static ParsedRow unreadable(String txnId, int lineNo, String errorCode) {
        return new ParsedRow(txnId, lineNo, null, null, null, null, null, errorCode);
    }

    public boolean isInvalid() {
        return errorCode != null;
    }
}
