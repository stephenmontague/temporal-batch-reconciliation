package com.example.recon.dto;

public record ParsedRow(String txnId, int lineNo, String accountId, Long amountCents,
        String currency, String txnDate, String merchant, String errorCode) {
}
