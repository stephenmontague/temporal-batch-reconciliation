package com.example.batchprocessing.core.dto;

public record CounterUpdate(String counterKey, String workflowId, int remaining) {
}
