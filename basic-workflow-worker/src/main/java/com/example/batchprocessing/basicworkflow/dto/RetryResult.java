package com.example.batchprocessing.basicworkflow.dto;

// What one successful retryFailedRows attempt returns: which attempt it was, and how many rows it sent again
public record RetryResult(int attempt, int rowsSent) {
}
