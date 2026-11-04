package com.example.batchprocessing.core.dto;

// The vendor's answer for one row. errorCode is null when the vendor accepted the row.
public record VendorAnswer(String txnId, String fileId, String errorCode) {
}
