package com.example.recon.dto;

public record CounterUpdate(String counterKey, String workflowId, int remaining) {
}
